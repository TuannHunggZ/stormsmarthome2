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
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import com.storm.iotdata.functions.*;
import com.storm.iotdata.models.*;

/**
 *
 * @author hiiamlala
 */
/**
 * Bolt_sum tổng hợp dữ liệu từ cấp device lên household và house theo từng timeslice.
 *
 * Vai trò:
 * - Nhận `DeviceData` đã chốt từ `Bolt_avg`.
 * - Lưu cache phân cấp theo `timeslice -> house -> household -> device`.
 * - Khi nhận trigger, tính ra `HouseData` và `HouseholdData`, ghi DB, cập nhật prop/outlier,
 *   rồi emit các aggregate đó xuống `Bolt_forecast`.
 *
 * Input:
 * - Stream `data` từ `Bolt_avg` (chỉ xử lý `type == DeviceData`).
 * - Stream `trigger` chuyển tiếp từ `Bolt_avg`.
 *
 * Output:
 * - Stream `data` chứa `HouseData` và `HouseholdData`.
 * - Stream `trigger` chuyển tiếp sang `Bolt_forecast`.
 *
 * State:
 * - `allData`: cache nguyên liệu device theo cấu trúc timeslice/house/household/device.
 * - `finalHouseDataList`: cache aggregate house theo houseId và sliceId.
 * - `finalHouseholdDataList`: cache aggregate household theo householdUniqueId và sliceId.
 * - `housePropList`, `householdPropList`: thống kê lịch sử phục vụ phát hiện bất thường.
 *
 * Khi state thay đổi:
 * - Thêm/cập nhật `allData` khi nhận DeviceData.
 * - Tạo/cập nhật `finalHouseDataList` và `finalHouseholdDataList` ở mỗi lần trigger.
 * - Ghi DB/notification ở trigger.
 * - Xóa state cũ khi bản ghi đã lưu và quá hạn dọn dẹp.
 *
 * Tóm tắt:
 * - Đây là tầng aggregate thứ hai, nâng từ device lên household/house.
 * - State quan trọng nhất là `allData` và hai map aggregate cuối.
 * - Có thể scale song song nếu partitioning theo timeslice/house ổn định; hiện state local làm việc scale phức tạp.
 * - Điểm nghẽn là cấu trúc HashMap lồng sâu, nhiều vòng lặp toàn phần và nhiều lần ghi DB trong một trigger.
 */
public class Bolt_sum extends BaseRichBolt {
    // Số lần liên tiếp ghi HouseData thất bại.
    public Integer houseDataUpdateFailCount = 0;
    // Số lần liên tiếp ghi HouseNotification thất bại.
    public Integer houseNotiUpdateFailCount = 0;
    // Số lần liên tiếp ghi HouseProp thất bại.
    public Integer housePropUpdateFailCount = 0;
    // Số lần liên tiếp ghi HouseholdData thất bại.
    public Integer householdDataUpdateFailCount = 0;
    // Số lần liên tiếp ghi HouseholdNotification thất bại.
    public Integer householdNotiUpdateFailCount = 0;
    // Số lần liên tiếp ghi HouseholdProp thất bại.
    public Integer householdPropUpdateFailCount = 0;
    // Cấu hình toàn cục.
    private StormConfig config;
    // Kích thước window của bolt này, đồng bộ với `Bolt_avg` upstream tương ứng.
    public Integer gap;
    // Sau bao nhiêu chu kỳ thì aggregate đã lưu được phép xóa khỏi cache.
    public Integer cleanTrigger = 5; //older than 5*gap will be clean 
    private OutputCollector _collector;
    // Cache dữ liệu thiết bị gốc để tính tổng lại theo cấu trúc phân cấp:
    // sliceId -> houseId -> householdId -> deviceUniqueId -> DeviceData
    // Tên đề xuất dễ hiểu hơn: `deviceDataBySlice`.
    public HashMap<String, HashMap<Integer, HashMap<Integer, HashMap<String, DeviceData> > > > allData = new HashMap<String, HashMap<Integer,HashMap<Integer,HashMap<String, DeviceData> > > >();
    // Cache kết quả aggregate ở cấp house: houseId -> sliceId -> HouseData
    public HashMap <Integer, HashMap<String, HouseData> > finalHouseDataList = new HashMap <Integer, HashMap<String, HouseData> >();
    // Cache kết quả aggregate ở cấp household: householdUniqueId -> sliceId -> HouseholdData
    public HashMap <String, HashMap<String, HouseholdData> > finalHouseholdDataList = new HashMap <String, HashMap<String, HouseholdData> >();
    // Cache thống kê lịch sử theo house để phát hiện outlier.
    public HashMap <String, HouseProp> housePropList = new HashMap<String, HouseProp>();
    // Cache thống kê lịch sử theo household để phát hiện outlier.
    public HashMap <String, HouseholdProp> householdPropList = new HashMap<String, HouseholdProp>();
    
    public Bolt_sum(int gap, StormConfig config) {
        this.gap = gap;
        this.config = config;
        this.housePropList = DB_store.initHousePropList();
        this.householdPropList = DB_store.initHouseholdPropList();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream("data", new Fields("type","data"));
        declarer.declareStream("trigger", new Fields("trigger", "spoutProp"));
    }

    @Override
    public void prepare(Map<String, Object> map, TopologyContext tc, OutputCollector oc) {
        _collector = oc;
    }

    @Override
    public void execute(Tuple tuple) {
        try{
            if(tuple.getSourceStreamId().equals("trigger")){
                // Trigger là thời điểm đóng batch aggregate và persist dữ liệu ở tầng house/household.
                Integer triggerInterval = (Integer) tuple.getValueByField("trigger");
                SpoutProp spoutProp = (SpoutProp) tuple.getValueByField("spoutProp");

                Long startExec = System.currentTimeMillis();
                Integer allDataSize = 0;
                Integer finalHouseDataSize = 0;
                Integer finalHouseholdDataSize = 0;
                Stack<HouseData> houseDataNeedSave = new Stack<HouseData>();
                Stack<HouseholdData> householdDataNeedSave = new Stack<HouseholdData>();
                Stack<HouseData> houseDataNeedClean = new Stack<HouseData>();
                Stack<HouseholdData> householdDataNeedClean = new Stack<HouseholdData>();
                Stack<String> timesliceNeedClean = new Stack<String>();
                Stack<HouseNotification> houseNotificationList = new Stack<HouseNotification>();
                Stack<HouseholdNotification> householdNotificationList = new Stack<HouseholdNotification>();

                // Tính lại aggregate từ dữ liệu device đã lưu trong `allData`.
                // Bước này giữ `allData` làm "source of truth" để luôn tính ra House/Household mới nhất theo từng timeslice.
                for(String timeslice : allData.keySet()){
                    HashMap<Integer, HashMap<Integer, HashMap<String, DeviceData> > > sliceData = allData.get(timeslice);
                    for(Integer houseId : sliceData.keySet()) {
                        Double houseValue = Double.valueOf(0);
                        HashMap<Integer,HashMap<String, DeviceData> > houseData = sliceData.get(houseId);
                        for(Integer householdId : houseData.keySet()) {
                            Double householdValue = Double.valueOf(0);
                            HashMap<String, DeviceData> householdData = houseData.get(householdId);
                            for(String dataId : householdData.keySet()) {
                                allDataSize++;
                                DeviceData data = householdData.get(dataId);
                                houseValue+=data.getAvg();
                                householdValue+=data.getAvg();
                            }
                            HouseholdData calHouseholdData = new HouseholdData(houseId, householdId, timeslice, householdValue);
                            HashMap<String, HouseholdData> tempFinalHouseholdData = finalHouseholdDataList.getOrDefault(calHouseholdData.getHouseholdUniqueId(), new HashMap<String, HouseholdData>());
                            HouseholdData tempHouseholdData = tempFinalHouseholdData.getOrDefault(calHouseholdData.getSliceId(), calHouseholdData);
                            tempHouseholdData.setValue(householdValue);
                            tempFinalHouseholdData.put(calHouseholdData.getSliceId(), tempHouseholdData);
                            finalHouseholdDataList.put(calHouseholdData.getHouseholdUniqueId(), tempFinalHouseholdData);
                        }
                        HouseData calHouseData = new HouseData(houseId, timeslice, houseValue);
                        HashMap<String, HouseData> tempFinalHouseData = finalHouseDataList.getOrDefault(calHouseData.getHouseId(), new HashMap<String, HouseData>());
                        HouseData tempHouseData = tempFinalHouseData.getOrDefault(calHouseData.getSliceId(), calHouseData);
                        tempHouseData.setValue(houseValue);
                        tempFinalHouseData.put(calHouseData.getSliceId(), tempHouseData);
                        finalHouseDataList.put(calHouseData.getHouseId(), tempFinalHouseData);
                    }
                }

                // Chuẩn bị danh sách aggregate cần lưu hoặc cần dọn cho HouseData.
                for(Integer houseId : finalHouseDataList.keySet()) {
                    HashMap<String, HouseData> tempFinalHouseData = finalHouseDataList.get(houseId);
                    for(String timeslice : tempFinalHouseData.keySet()){
                        finalHouseDataSize++;
                        HouseData houseData = tempFinalHouseData.get(timeslice);
                        if(!houseData.isSaved()){
                            _collector.emit("data", tuple, new Values(houseData.getClass().getSimpleName(), houseData));
                            houseDataNeedSave.push(houseData);
                        }
                        else if((System.currentTimeMillis()-houseData.getLastUpdate())>(cleanTrigger*gap*1000)){
                            houseDataNeedClean.push(houseData);
                        }
                    }
                }

                // Tương tự cho HouseholdData.
                for(String uniqueHouseholdId : finalHouseholdDataList.keySet()) {
                    HashMap<String, HouseholdData> tempFinalHouseholdData = finalHouseholdDataList.get(uniqueHouseholdId);
                    for(String timeslice : tempFinalHouseholdData.keySet()){
                        finalHouseholdDataSize++;
                        HouseholdData householdData = tempFinalHouseholdData.get(timeslice);
                        if(!householdData.isSaved()){
                            _collector.emit("data", tuple, new Values(householdData.getClass().getSimpleName(), householdData));
                            householdDataNeedSave.push(householdData);
                        }
                        else if((System.currentTimeMillis()-householdData.getLastUpdate())>(cleanTrigger*gap*1000)){
                            householdDataNeedClean.push(householdData);
                        }
                    }
                }

                // Chỉ xóa hẳn một timeslice khi cả house và household cùng quá hạn,
                // nhằm tránh mất nguyên liệu khi một phía vẫn còn cần dùng.
                for(HouseData houseData : houseDataNeedClean){
                    Timeslice timeslice = houseData.getTimeslice();
                    if(timesliceNeedClean.contains(timeslice.getSliceId())) break;
                    for(HouseholdData householdData : householdDataNeedClean){
                        if(timeslice.isSameTimeslice(householdData.getTimeslice())){
                            timesliceNeedClean.push(timeslice.getSliceId());
                            break;
                        }
                    }
                }

                // Persist aggregate house/household xuống DB.
                if(houseDataNeedSave.size()!=0){
                    if(DB_store.pushHouseData(houseDataNeedSave, new File("./tmp/houseData2db-" + gap + ".lck"))){
                        houseDataUpdateFailCount=0;
                        for(HouseData data : houseDataNeedSave){
                            finalHouseDataList.get(data.getHouseId()).get(data.getSliceId()).save();
                        }
                    }
                }
                else if(++houseDataUpdateFailCount>=3){
                    new File("./tmp/houseData2db-" + gap + ".lck").delete();
                }

                if(householdDataNeedSave.size()!=0){
                    if(DB_store.pushHouseHoldData(householdDataNeedSave, new File("./tmp/householdData2db-" + gap + ".lck"))){
                        householdDataUpdateFailCount=0;
                        for(HouseholdData data : householdDataNeedSave){
                            finalHouseholdDataList.get(data.getHouseholdUniqueId()).get(data.getSliceId()).save();
                        }
                    }
                }
                else if(++householdDataUpdateFailCount>=3){
                    new File("./tmp/householdData2db-" + gap + ".lck").delete();
                }

                // Persist rolling statistics cho house/household để phục vụ outlier về sau.
                Stack<HouseProp> tempHousePropList = new Stack<HouseProp>();
                tempHousePropList.addAll(housePropList.values());
                if(DB_store.pushHouseProp(tempHousePropList, new File("./tmp/houseProp2db-"+ gap +".lck"))){
                    housePropUpdateFailCount=0;
                    for(HouseProp houseProp : tempHousePropList){
                        housePropList.get(houseProp.getHouseUniqueId()).save();
                    }
                }
                else if(++housePropUpdateFailCount>=3){
                    new File("./tmp/houseProp2db-"+ gap +".lck").delete();
                }

                Stack<HouseholdProp> tempHouseholdPropList = new Stack<HouseholdProp>();
                tempHouseholdPropList.addAll(householdPropList.values());
                if(DB_store.pushHouseholdProp(tempHouseholdPropList, new File("./tmp/householdProp2db-"+ gap +".lck"))){
                    householdPropUpdateFailCount=0;
                    for(HouseholdProp householdProp : tempHouseholdPropList){
                        householdPropList.get(householdProp.getHouseholdUniqueId()).save();
                    }
                }
                else if(++householdPropUpdateFailCount>=3){
                    new File("./tmp/householdProp2db-"+ gap +".lck").delete();
                }

                // Phát hiện bất thường dựa trên min/avg/max lịch sử hiện có.
                for(HouseData houseData : houseDataNeedSave){
                    HouseProp houseProp = housePropList.getOrDefault(houseData.getHouseUniqueId(), new HouseProp(houseData.getHouseId(), houseData.getGap()));
                    // Check min
                    if(config.isHouseCheckMin() && houseProp.getMin()!=0 && (houseProp.getMin() - houseData.getAvg()) <= (houseProp.getMin()*config.getHouseLogGap()/100)){
                        houseNotificationList.push(new HouseNotification(-1, houseData, houseProp));
                    }
                    // Check avg
                    if(config.isHouseCheckAvg() && houseProp.getAvg()!=0 && (houseData.getAvg() - houseProp.getAvg()) >= (houseProp.getAvg()*config.getHouseLogGap()/100)){
                        houseNotificationList.push(new HouseNotification(0, houseData, houseProp));
                    }
                    // Check max
                    if(config.isHouseCheckMax() && houseProp.getMax()!=0 && (houseData.getAvg() - houseProp.getMax()) >= (houseProp.getMax()*config.getHouseLogGap()/100)){
                        houseNotificationList.push(new HouseNotification(1, houseData, houseProp));
                    }
                    housePropList.put(houseData.getHouseUniqueId(), houseProp.addValue(houseData.getAvg()));
                }

                for(HouseholdData householdData : householdDataNeedSave){
                    HouseholdProp householdProp = householdPropList.getOrDefault(householdData.getHouseholdUniqueId(), new HouseholdProp(householdData.getHouseId(), householdData.getHouseholdId(), householdData.getGap()));
                    // Check min
                    if(config.isHouseholdCheckMin() && householdProp.getMin()!=0 && (householdProp.getMin() - householdData.getAvg()) <= (householdProp.getMin()*config.getHouseholdLogGap()/100)){
                        householdNotificationList.push(new HouseholdNotification(-1, householdData, householdProp));
                    }
                    // Check avg
                    if(config.isHouseholdCheckAvg() && householdProp.getAvg()!=0 && (householdData.getAvg() - householdProp.getAvg()) >= (householdProp.getAvg()*config.getHouseholdLogGap()/100)){
                        householdNotificationList.push(new HouseholdNotification(0, householdData, householdProp));
                    }
                    // Check max
                    if(config.isHouseholdCheckMax() && householdProp.getMax()!=0 && (householdData.getAvg() - householdProp.getMax()) >= (householdProp.getMax()*config.getHouseholdLogGap()/100)){
                        householdNotificationList.push(new HouseholdNotification(1, householdData, householdProp));
                    }
                    householdPropList.put(householdData.getHouseholdUniqueId(), householdProp.addValue(householdData.getAvg()));
                }
                
                // Lưu và publish notification nếu được bật.
                if(DB_store.pushHouseNotification(houseNotificationList, new File("./tmp/housenoti2db-" + gap + ".lck"))){
                    houseNotiUpdateFailCount=0;
                    // House noti saved
                    // Publish noti
                    if(config.isNotificationMQTT()){
                        MQTT_publisher.houseNotificationsPublish(houseNotificationList, config.getNotificationBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/housenoti2mqtt-"+gap+".lck"));
                    }
                }
                else if(++houseNotiUpdateFailCount>=3){
                    new File("./tmp/housenoti2db-" + gap + ".lck").delete();
                }

                if(DB_store.pushHouseholdNotification(householdNotificationList, new File("./tmp/householdnoti2db-" + gap + ".lck"))){
                    householdNotiUpdateFailCount=0;
                    // House noti pushed
                    // Publish noti
                    if(config.isNotificationMQTT()){
                        MQTT_publisher.householdNotificationsPublish(householdNotificationList, config.getNotificationBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/householdnoti2mqtt-"+gap+".lck"));
                    }
                }
                else if(++householdNotiUpdateFailCount>=3){
                    new File("./tmp/householdnoti2db-" + gap + ".lck").delete();
                }

                // Log kích thước state và thời gian flush để theo dõi điểm nóng hiệu năng.
                Long execTime = System.currentTimeMillis() - startExec;

                Stack<String> logs = new Stack<String>();
                logs.push(String.format("[Bolt_sum_%-3d] HouseData | Total: %-10d | Need save: %-10d | Need clean: %-10d\n", gap, finalHouseDataSize, houseDataNeedSave.size(), houseDataNeedClean.size()));
                logs.push(String.format("[Bolt_sum_%-3d] HouseholdData | Total: %-10d | Need save: %-10d | Need clean: %-10d\n",gap, finalHouseholdDataSize, householdDataNeedSave.size(), householdDataNeedClean.size()));
                logs.push(String.format("[Bolt_sum_%-3d] Timeslice | Total: %-10d | Need clean: %-10d\n", gap, allData.size(), timesliceNeedClean.size()));
                logs.push(String.format("[Bolt_sum_%-3d] Notification | House: %-10d | Household: %-10d\n", gap, houseNotificationList.size(), householdNotificationList.size()));
                logs.push(String.format("[Bolt_sum_%-3d] Storing data execute time %.3f s\n", gap, (float) execTime/1000));
                MQTT_publisher.stormLogPublish(logs, config.getNotificationBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/bolt-sum-"+gap+"-log-publish.lck"));
                for(String data : logs){
                    System.out.println(data);
                }
                try {
                    FileWriter log = new FileWriter(new File("./tmp/bolt_sum_"+ gap +".tmp"), false);
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

                // Dọn state cũ sau khi persist thành công để giải phóng bộ nhớ.
                for(String timeslice : timesliceNeedClean){
                    allData.remove(timeslice);
                }

                for(HouseData houseData : houseDataNeedClean){
                    finalHouseDataList.get(houseData.getHouseId()).remove(houseData.getSliceId());
                }

                for(HouseholdData householdData : householdDataNeedClean) {
                    finalHouseholdDataList.get(householdData.getHouseholdUniqueId()).remove(householdData.getSliceId());
                }
                // Chuyển trigger cho tầng forecast sau khi tầng sum đã chốt xong dữ liệu.
                _collector.emit("trigger", tuple, new Values(triggerInterval, spoutProp));
                _collector.ack(tuple);
            }
            else if(tuple.getSourceStreamId().equals("data") && tuple.getValueByField("type").equals(DeviceData.class.getSimpleName())){
                // Chỉ nhận DeviceData từ Bolt_avg rồi nạp vào cache phân cấp để đợi trigger tổng hợp.
                DeviceData tempData     = (DeviceData) tuple.getValueByField("data");

                HashMap<Integer, HashMap<Integer,HashMap<String, DeviceData> > > sliceData = allData.getOrDefault(tempData.getSliceId(), new HashMap<Integer, HashMap<Integer,HashMap<String, DeviceData> > >());
                HashMap<Integer,HashMap<String, DeviceData> > houseData = sliceData.getOrDefault(tempData.getHouseId(), new HashMap<Integer,HashMap<String, DeviceData> >());
                HashMap<String, DeviceData> householdData = houseData.getOrDefault(tempData.getHouseholdId(), new HashMap<String, DeviceData>());
                householdData.put(tempData.getUniqueId(), tempData);
                houseData.put(tempData.getHouseholdId(), householdData);
                sliceData.put(tempData.getHouseId(), houseData);
                allData.put(tempData.getSliceId(), sliceData);
                _collector.ack(tuple);
            }
            else{
                _collector.fail(tuple);
            }
        }
        catch (Exception ex){
            ex.printStackTrace();
            _collector.fail(tuple);
        }
    }
}