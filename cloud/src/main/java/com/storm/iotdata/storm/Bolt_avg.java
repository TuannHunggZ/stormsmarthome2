/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.storm.iotdata.storm;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import com.storm.iotdata.models.*;
import com.storm.iotdata.functions.*;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

/**
 *
 * @author hiiamlala
 */
/**
 * Bolt_avg gom dữ liệu device theo từng timeslice để tính trung bình và phát hiện bất thường ở cấp thiết bị.
 *
 * Vai trò:
 * - Nhận dữ liệu từ stream `window-*` của `Bolt_split`.
 * - Tích lũy tổng `value` và `count` cho từng device trong từng timeslice.
 * - Khi nhận trigger đủ chu kỳ, ghi `DeviceData`, cập nhật `DeviceProp`, tạo `DeviceNotification`,
 *   rồi emit `DeviceData` đã chốt xuống `Bolt_sum` và `Bolt_forecast`.
 *
 * Input:
 * - Stream `window-{gap}` từ `Bolt_split`.
 * - Stream `trigger` từ `Spout_trigger`.
 *
 * Output:
 * - Stream `data` chứa `DeviceData` đã tổng hợp.
 * - Stream `trigger` chuyển tiếp sang `Bolt_sum`.
 *
 * State:
 * - `deviceDataList`: cache `DeviceData` theo `uniqueId` của device-timeslice.
 * - `devicePropList`: thống kê lịch sử min/max/avg theo device và gap.
 *
 * Khi state thay đổi:
 * - Thêm/cập nhật `deviceDataList` khi có tuple dữ liệu mới.
 * - Ghi DB khi tới trigger phù hợp.
 * - Xóa khỏi cache khi bản ghi đã lưu và quá hạn `cleanTrigger`.
 *
 * Tóm tắt:
 * - Component này là tầng aggregate đầu tiên, giữ state quan trọng nhất ở cấp device.
 * - Có thể scale song song nếu partitioning nhất quán, nhưng state local khiến repartition không đơn giản.
 * - Điểm nghẽn là duyệt toàn bộ `deviceDataList` mỗi lần flush, ghi DB và publish notification đồng bộ.
 */
public class Bolt_avg extends BaseRichBolt {
    private static final String WINDOW_STREAM_PREFIX = "window-";

    // Đếm số tuple dữ liệu đã xử lý trong chu kỳ hiện tại để phục vụ log tốc độ.
    // Tên đề xuất dễ hiểu hơn: `processedTupleCount`.
    public Long processSpeed = Long.valueOf(0);
    // Số lần liên tiếp ghi DeviceData xuống DB thất bại.
    public Integer deviceDataUpdateFailCount = 0;
    // Số lần liên tiếp ghi DeviceNotification thất bại.
    public Integer deviceNotiUpdateFailCount = 0;
    // Số lần liên tiếp ghi DeviceProp thất bại.
    public Integer devicePropUpdateFailCount = 0;
    // Cấu hình toàn cục.
    private StormConfig config;
    // Kích thước window/phân giải timeslice của bolt này, tính theo phút.
    // Tên đề xuất dễ hiểu hơn: `windowGapMinutes`.
    public Integer gap;
    // Đếm số trigger đã nhận để biết khi nào đến lượt bolt này flush.
    public Integer triggerCount = 0;
    // Số chu kỳ trigger tối đa giữ lại record đã lưu trước khi dọn khỏi cache.
    public Integer cleanTrigger = 5; //older than 5*gap will be clean
    // TODO: `total` hiện không được dùng trong logic.
    public Double total = Double.valueOf(0);
    // Cache aggregate theo khóa device + timeslice.
    public HashMap<String, DeviceData> deviceDataList = new HashMap<String, DeviceData>();
    // Cache thống kê lịch sử theo device để phát hiện outlier.
    public HashMap<String, DeviceProp> devicePropList = new HashMap<String, DeviceProp>();

    public Bolt_avg(Integer gap, StormConfig config) {
        this.gap = gap;
        this.config = config;
        devicePropList = DB_store.initDevicePropList();
    }

    private OutputCollector _collector;

    @Override
    public void prepare(Map<String, Object> map, TopologyContext tc, OutputCollector oc) {
        _collector = oc;
    }

    @Override
    public void execute(Tuple tuple) {
        try{
            if(tuple.getSourceStreamId().equals("trigger")){
                // Bolt_avg chỉ flush khi số lần trigger chia hết cho `gap`.
                // Mục đích là đồng bộ nhịp flush với độ dài window của bolt.
                if(((++triggerCount)%gap)==0){
                    Integer triggerInterval = (Integer) tuple.getValueByField("trigger");
                    SpoutProp spoutProp = (SpoutProp) tuple.getValueByField("spoutProp");

                    // Các stack tạm gom record cần lưu/xóa/thông báo trong một chu kỳ flush.
                    Long startExec = System.currentTimeMillis();
                    Stack<String> needClean = new Stack<String>();
                    Stack<DeviceData> needSave = new Stack<DeviceData>();
                    Stack<DeviceNotification> deviceNotificationList = new Stack<DeviceNotification>();

                    // Duyệt toàn bộ cache:
                    // - record chưa `saved` sẽ emit xuống stream `data` và chuẩn bị lưu DB;
                    // - record đã lưu quá lâu sẽ được đánh dấu xóa khỏi cache để giải phóng RAM.
                    for(String key : deviceDataList.keySet()){
                        DeviceData data = deviceDataList.get(key);
                        if(!data.isSaved()){
                            _collector.emit("data", tuple, new Values(data.getClass().getSimpleName(), data));
                            needSave.push(data);
                        }
                        else if(data.isSaved() && (System.currentTimeMillis()-data.getLastUpdate())>(cleanTrigger*gap*60000)){
                            needClean.push(key);
                        }
                    }

                    // Ghi DeviceData đã chốt xuống DB trước, vì đây là dữ liệu đầu ra chính của tầng avg.
                    if(DB_store.pushDeviceData(needSave, new File("./tmp/deviceData2db-" + gap + ".lck"))){
                        deviceDataUpdateFailCount=0;
                        for(DeviceData deviceData : needSave){
                            deviceDataList.get(deviceData.getUniqueId()).save();
                        }
                    }
                    else if(++deviceDataUpdateFailCount >= 3) {
                        new File("./tmp/deviceData2db-" + gap + ".lck").delete();
                    }

                    // Ghi thống kê DeviceProp để dùng cho các chu kỳ phát hiện bất thường tiếp theo.
                    Stack<DeviceProp> tempDevicePropList = new Stack<DeviceProp>();
                    tempDevicePropList.addAll(devicePropList.values());
                    if(DB_store.pushDeviceProp(tempDevicePropList, new File("./tmp/deviceProp2db-"+ gap + ".lck"))){
                        devicePropUpdateFailCount=0;
                        for(DeviceProp deviceProp : tempDevicePropList){
                            devicePropList.get(deviceProp.getDeviceUniqueId()).save();
                        }
                    }
                    else if(++devicePropUpdateFailCount >= 3) {
                        new File("./tmp/deviceProp2db-"+ gap + ".lck").delete();
                    }

                    // Kiểm tra outlier trên chính các record vừa chốt, dựa vào thống kê lịch sử cùng device/gap.
                    for(DeviceData deviceData : needSave){
                        String devicePropUniqueId = deviceData.getDeviceUniqueId();
                        DeviceProp deviceProp = devicePropList.getOrDefault(devicePropUniqueId, new DeviceProp(deviceData.houseId, deviceData.householdId, deviceData.deviceId, this.gap));
                        if(config.isDeviceCheckMax() && deviceProp.getMax()!=0 && (deviceData.getAvg()-deviceProp.getMax())>=(deviceProp.getMax()*config.getDeviceLogGap()/100)){
                            //Check over Max
                            deviceNotificationList.push(new DeviceNotification(1, deviceData, deviceProp));
                        }
                        if(config.isDeviceCheckAvg() && deviceProp.getAvg()!=0 && (deviceData.getAvg()-deviceProp.getAvg())>=(deviceProp.getAvg()*config.getDeviceLogGap()/100)){
                            //Check over Avg
                            deviceNotificationList.push(new DeviceNotification(0, deviceData, deviceProp));
                        }
                        if(config.isDeviceCheckMin() && deviceProp.getMin()!=0 && (deviceProp.getMin()-deviceData.getAvg())<=(deviceProp.getMin()*config.getDeviceLogGap()/100)){
                            //Check under Min
                            deviceNotificationList.push(new DeviceNotification(-1, deviceData, deviceProp));
                        }
                        // Cập nhật rolling statistic sau khi đã đánh giá outlier cho record hiện tại.
                        devicePropList.put(devicePropUniqueId, deviceProp.addValue(deviceData.getAvg()));
                    }

                    // Lưu và publish notification nếu cần.
                    if(DB_store.pushDeviceNotification(deviceNotificationList, new File("./tmp/devicenoti2db-" + gap + ".lck"))){
                        deviceNotiUpdateFailCount = 0;
                        //Noti saved
                        //Publish Noti
                        if(config.isNotificationMQTT()){
                            MQTT_publisher.deviceNotificationsPublish(deviceNotificationList, config.getSpoutBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/devicenoti2mqtt-"+ gap +".lck"));
                        }
                    }
                    else if(++deviceNotiUpdateFailCount>=3){
                        new File("./tmp/devicenoti2db-" + gap + ".lck").delete();
                    }

                    // Ghi log để quan sát độ lớn state, throughput và thời gian flush.
                    Long execTime = System.currentTimeMillis() - startExec;

                    Stack<String> logs = new Stack<String>();
                    logs.push(String.format("[Bolt_avg_%-3d] Process speed: %-10d mess/s\n", gap, processSpeed/triggerInterval/triggerCount));
                    logs.push(String.format("[Bolt_avg_%-3d] Noti list: %-10d\n", gap, deviceNotificationList.size()));
                    logs.push(String.format("[Bolt_avg_%-3d] Total: %-10d | Already saved: %-10d | Need save: %-10d | Need clean: %-10d\n",gap, deviceDataList.size(), deviceDataList.size()-needSave.size(), needSave.size(), needClean.size()));
                    logs.push(String.format("[Bolt_avg_%-3d] Storing data execute time %.3f s\n", gap, (float) execTime/1000));

                    MQTT_publisher.stormLogPublish(logs, config.getNotificationBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/bolt-avg-"+ gap +"-log-publish.lck"));
                    for(String data : logs){
                        System.out.println(data);
                    }
                    try {
                        FileWriter log = new FileWriter(new File("./tmp/bolt_avg_"+ gap +".tmp"), false);
                        PrintWriter pwOb = new PrintWriter(log , false);
                        pwOb.flush();
                        for(String data : logs){
                            log.write(data);
                        }
                        pwOb.close();
                        log.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }

                    // Xóa cache cũ sau khi đã lưu xong để tránh tăng bộ nhớ vô hạn.
                    for(String key : needClean){
                        deviceDataList.remove(key);
                    }

                    // Chuyển trigger cho tầng aggregate tiếp theo sau khi flush xong tầng device.
                    _collector.emit("trigger", tuple, new Values(triggerInterval, spoutProp));
                    processSpeed = Long.valueOf(0);
                    triggerCount = 0;
                }
                _collector.ack(tuple);
            }
            else if (tuple.getSourceStreamId().startsWith(WINDOW_STREAM_PREFIX)) {
                // Nhận tuple dữ liệu của đúng window mà bolt này quản lý và gom dần theo device-timeslice.
                Integer houseId         = (Integer) tuple.getValueByField("houseId");
                Integer householdId     = (Integer)tuple.getValueByField("householdId");
                Integer deviceId        = (Integer)tuple.getValueByField("deviceId");
                String year             = (String)tuple.getValueByField("year");
                String month            = (String)tuple.getValueByField("month");
                String day              = (String)tuple.getValueByField("day");
                Integer index           = (Integer) tuple.getValueByField("sliceIndex");
                Double  value           = (Double) tuple.getValueByField("value");
                // Khóa cache được tạo từ device + timeslice để nhiều tuple cùng lát thời gian nhập chung vào một aggregate.
                DeviceData deviceData   = new DeviceData(houseId, householdId, deviceId, year, month, day, index, gap);
                deviceDataList.put(deviceData.getUniqueId(), deviceDataList.getOrDefault(deviceData.getUniqueId(), deviceData ).increaseValue(value));
                processSpeed++;
                _collector.ack(tuple);
            }
            else{
                _collector.fail(tuple);
            }
        }catch (Exception ex){
            ex.printStackTrace();
            _collector.fail(tuple);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream("data", new Fields("type","data"));
        declarer.declareStream("trigger", new Fields("trigger", "spoutProp"));
    }

}