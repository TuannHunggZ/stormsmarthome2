package com.storm.iotdata.storm;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Tuple;
import com.storm.iotdata.functions.DB_store;
import com.storm.iotdata.functions.MQTT_publisher;
import com.storm.iotdata.models.*;

/**
 * Bolt_forecast tạo dữ liệu dự báo cho house/household/device từ aggregate đã chốt.
 *
 * Vai trò:
 * - Nhận `DeviceData` từ `Bolt_avg` và `HouseData`/`HouseholdData` từ `Bolt_sum`.
 * - Giữ chúng trong cache cho tới khi nhận trigger.
 * - Khi trigger đến, truy vấn dữ liệu lịch sử trong DB, tính median và tạo forecast cho timeslice kế tiếp.
 *
 * Input:
 * - Stream `data` từ `Bolt_avg` và `Bolt_sum`.
 * - Stream `trigger` từ `Bolt_sum`.
 *
 * Output:
 * - Không emit tuple tiếp; chỉ ghi forecast xuống DB và log.
 *
 * State:
 * - `houseDataList`, `householdDataList`, `deviceDataList`: cache dữ liệu đầu vào chờ forecast.
 *
 * Khi state thay đổi:
 * - Thêm/cập nhật khi nhận tuple `data`.
 * - Ghi DB forecast ở trigger.
 * - Xóa khỏi cache nếu đã lưu forecast thành công.
 *
 * Tóm tắt:
 * - Đây là sink tính forecast cuối pipeline.
 * - State quan trọng là ba map cache theo loại aggregate.
 * - Có thể chạy song song nếu chia partition dữ liệu hợp lý, nhưng mỗi instance sẽ tự truy vấn DB riêng.
 * - Điểm nghẽn là I/O DB khi query lịch sử cho từng record và thao tác sort để tính median.
 */
public class Bolt_forecast extends BaseRichBolt {
    // Cấu hình toàn cục.
    private StormConfig config;
    // Kích thước window của dữ liệu mà bolt này đang forecast.
    private int gap;
    // Cache HouseData chờ forecast, key là `HouseData.getUniqueId()`.
    private HashMap<String,HouseData> houseDataList;
    // Cache HouseholdData chờ forecast, key là `HouseholdData.getUniqueId()`.
    private HashMap<String,HouseholdData> householdDataList;
    // Cache DeviceData chờ forecast, key là `DeviceData.getUniqueId()`.
    private HashMap<String,DeviceData> deviceDataList;
    private OutputCollector _collector;

    public Bolt_forecast(Integer gap, StormConfig config){
        this.gap = gap;
        this.config = config;
        houseDataList = new HashMap<String,HouseData>();
        householdDataList = new HashMap<String,HouseholdData>();
        deviceDataList = new HashMap<String,DeviceData>();
    }


    @Override
    public void prepare(Map<String, Object> topoConf, TopologyContext context, OutputCollector collector) {
        _collector = collector;
    }

    @Override
    public void execute(Tuple input) {
        try{
            if(input.getSourceStreamId().equals("trigger")){
                // Trigger là thời điểm chốt toàn bộ cache thành forecast và ghi DB.
                Stack<String> logs = new Stack<String>();
                // Forecast cấp house.
                Long start = System.currentTimeMillis();
                HashMap<String, HouseData> houseDataForecast= forecast(houseDataList);
                Stack<HouseData> tempHouseDataForecast = new Stack<HouseData>();
                tempHouseDataForecast.addAll(houseDataForecast.values());
                if(DB_store.pushHouseDataForecast("v0", tempHouseDataForecast, new File("./tmp/houseDataForecast2db-" + gap + ".lck"))){
                    for(String key : houseDataForecast.keySet()){
                        houseDataList.remove(key);
                    }
                    //Log HouseData
                    logs.add(String.format("[Bolt_forecast_%d] HouseData forecast took %.2fs\n", gap, (float)(System.currentTimeMillis()-start)/1000));
                    logs.add(String.format("[Bolt_forecast_%d] HouseData Total: %-10d | Saved and clean: %-10d\n", gap, houseDataList.size(), tempHouseDataForecast.size()));
                    //Cleanning
                    houseDataForecast = null;
                    tempHouseDataForecast = null;
                }
                else {
                    logs.add(String.format("[Bolt_forecast_%d] HouseData forecast not saved\n", gap));
                }

                // Forecast cấp household.
                start = System.currentTimeMillis();
                HashMap<String, HouseholdData> householdDataForecast = forecast(householdDataList);
                Stack<HouseholdData> tempHouseholdDataForecast = new Stack<HouseholdData>();
                tempHouseholdDataForecast.addAll(householdDataForecast.values());
                if(DB_store.pushHouseholdDataForecast("v0", tempHouseholdDataForecast, new File("./tmp/householdDataForecast2db-" + gap + ".lck"))){
                    for(String key : householdDataForecast.keySet()){
                        householdDataList.remove(key);
                    }
                    //Log HouseholdData
                    logs.add(String.format("[Bolt_forecast_%d] HouseholdData forecast took %.2fs\n", gap, (float)(System.currentTimeMillis()-start)/1000));
                    logs.add(String.format("[Bolt_forecast_%d] HouseholdData Total: %-10d | Saved and clean: %-10d\n", gap, householdDataList.size(), tempHouseholdDataForecast.size()));
                    //Cleaning
                    householdDataForecast = null;
                    tempHouseholdDataForecast = null;
                }
                else {
                    logs.add(String.format("[Bolt_forecast_%d] HouseholdData forecast not saved\n", gap));
                }

                // Forecast cấp device.
                HashMap<String, DeviceData> deviceDataForecast = forecast(deviceDataList);
                Stack<DeviceData> tempDeviceDataForecast = new Stack<DeviceData>();
                tempDeviceDataForecast.addAll(deviceDataForecast.values());
                if(DB_store.pushDeviceDataForecast("v0", tempDeviceDataForecast, new File("./tmp/deviceDataForecast2db-" + gap + ".lck"))){
                    for(String key : deviceDataForecast.keySet()){
                        deviceDataList.remove(key);
                    }
                    //Log HouseData
                    logs.add(String.format("[Bolt_forecast_%d] DeviceData forecast took %.2fs\n", gap, (float)(System.currentTimeMillis()-start)/1000));
                    logs.add(String.format("[Bolt_forecast_%d] DeviceData Total: %-10d | Saved and clean: %-10d\n", gap, deviceDataList.size(), tempDeviceDataForecast.size()));
                    //Cleaning
                    deviceDataForecast = null;
                    tempDeviceDataForecast = null;
                }
                else {
                    logs.add(String.format("[Bolt_forecast_%d] DeviceData forecast not saved\n", gap));
                }

                MQTT_publisher.stormLogPublish(logs, config.getNotificationBrokerURL(), config.getMqttTopicPrefix(), new File("./tmp/bolt-forecast-"+ gap +"-log-publish.lck"));
                for(String data : logs){
                    System.out.println(data);
                }
                try {
                    FileWriter log = new FileWriter(new File("./tmp/bolt_forecast_"+ gap +".tmp"), false);
                    PrintWriter pwOb = new PrintWriter(log , false);
                    pwOb.flush();
                    for(String data : logs){
                        log.write(data);
                    }
                    pwOb.close();
                    log.close();
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }

                _collector.ack(input);
            }
            else if(input.getSourceStreamId().equals("data")){
                // Nhận dữ liệu aggregate đã chốt từ upstream và lưu theo từng loại để chờ trigger.
                if(input.getValueByField("type").equals(HouseData.class.getSimpleName())){
                    HouseData data = (HouseData) input.getValueByField("data");
                    houseDataList.put(data.getUniqueId(), data);
                    _collector.ack(input);
                }
                else if(input.getValueByField("type").equals(HouseholdData.class.getSimpleName())){
                    HouseholdData data = (HouseholdData) input.getValueByField("data");
                    householdDataList.put(data.getUniqueId(), data);
                    _collector.ack(input);
                }
                else if(input.getValueByField("type").equals(DeviceData.class.getSimpleName())){
                    DeviceData data = (DeviceData) input.getValueByField("data");
                    deviceDataList.put(data.getUniqueId(), data);
                    _collector.ack(input);
                }
                else {
                    _collector.fail(input);
                }
            }
            else {
                _collector.fail(input);
            }
        } catch (Exception ex){
            ex.printStackTrace();
            _collector.fail(input);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // Bolt cuối pipeline, không emit stream downstream.
    }

    public static <E> HashMap<String, E> forecast(HashMap<String,E> inputData){
        HashMap<String, E> result = new HashMap<String, E>();
        try{
            if(inputData.size()!=0){
                try (Connection conn = DB_store.initConnection()){
                    // Chọn chiến lược dựng forecast theo đúng kiểu dữ liệu hiện có trong cache.
                    if(inputData.values().toArray()[0] instanceof HouseData){
                        for(String key : inputData.keySet()){
                            HouseData ele = (HouseData) inputData.get(key);
                            // Median dữ liệu lịch sử được dùng như tín hiệu làm mượt cho giá trị dự báo.
                            Double median = getMedian(DB_store.queryBefore(ele, conn));
                            Double forecastValue = ele.getAvg();
                            if(median > 0){
                                forecastValue = (forecastValue + median)/2;
                            }
                            result.put(key, (E) new HouseData(ele.getHouseId(), ele.getTimeslice().getNextTimeslice(2), forecastValue));
                        }
                    }
                    else if(inputData.values().toArray()[0] instanceof HouseholdData){
                        for(String key : inputData.keySet()){
                            HouseholdData ele = (HouseholdData) inputData.get(key);
                            Double median = getMedian(DB_store.queryBefore(ele, conn));
                            Double forecastValue = ele.getAvg();
                            if(median > 0){
                                forecastValue = (forecastValue + median)/2;
                            }
                            result.put(key, (E) new HouseholdData(ele.getHouseId(), ele.getHouseholdId() , ele.getTimeslice().getNextTimeslice(2), forecastValue));
                        }
                    }
                    else if(inputData.values().toArray()[0] instanceof DeviceData){
                        for(String key : inputData.keySet()){
                            DeviceData ele = (DeviceData) inputData.get(key);
                            Double median = getMedian(DB_store.queryBefore(ele, conn));
                            Double forecastValue = ele.getAvg();
                            if(median > 0){
                                forecastValue = (forecastValue + median)/2;
                            }
                            result.put(key, (E) new DeviceData(ele.getHouseId(), ele.getHouseholdId(), ele.getDeviceId(), ele.getTimeslice().getNextTimeslice(2), forecastValue));
                        }
                    }
                    conn.close();
                }
            }
        } catch (Exception ex){
            ex.printStackTrace();
        } finally {
            return result;
        }
    }

    public static <E> Double getMedian(HashMap<String, E> beforeData){
        Double median = Double.valueOf(0);
        if(beforeData.size()>0){
            // Sắp xếp theo avg để lấy trung vị, giảm ảnh hưởng của outlier đơn lẻ so với mean.
            ArrayList<E> beforeAvgs = new ArrayList<>(beforeData.values());
            beforeAvgs.sort(new Comparator<E>(){
                @Override
                public int compare(E data1, E data2) {
                    if(data1 instanceof HouseData){
                        HouseData temp1 = (HouseData) data1;
                        HouseData temp2 = (HouseData) data2;
                        return Double.compare(temp1.getAvg(), temp2.getAvg());
                    }
                    else if(data1 instanceof HouseholdData){
                        HouseholdData temp1 = (HouseholdData) data1;
                        HouseholdData temp2 = (HouseholdData) data2;
                        return Double.compare(temp1.getAvg(), temp2.getAvg());
                    }
                    else if(data1 instanceof DeviceData){
                        DeviceData temp1 = (DeviceData) data1;
                        DeviceData temp2 = (DeviceData) data2;
                        return Double.compare(temp1.getAvg(), temp2.getAvg());
                    }
                    return 0;
                }
            });
            if(beforeAvgs.size()%2==0){
                if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof HouseData){
                    HouseData temp1 = (HouseData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    HouseData temp2 = (HouseData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)-1);
                    median = (temp1.getAvg() + temp2.getAvg())/2;
                }
                else if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof HouseholdData){
                    HouseholdData temp1 = (HouseholdData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    HouseholdData temp2 = (HouseholdData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)-1);
                    median = (temp1.getAvg() + temp2.getAvg())/2;
                }
                else if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof DeviceData){
                    DeviceData temp1 = (DeviceData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    DeviceData temp2 = (DeviceData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)-1);
                    median = (temp1.getAvg() + temp2.getAvg())/2;
                }
            }
            else {
                if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof HouseData){
                    HouseData temp = (HouseData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    median = temp.getAvg();
                }
                else if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof HouseholdData){
                    HouseholdData temp = (HouseholdData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    median = temp.getAvg();
                }
                else if(beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2)) instanceof HouseholdData){
                    DeviceData temp = (DeviceData) beforeAvgs.get(Math.floorDiv(beforeAvgs.size(), 2));
                    median = temp.getAvg();
                }
            }
        }
        return median;
    }
}
