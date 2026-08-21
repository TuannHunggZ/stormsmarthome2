
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.storm.iotdata.storm;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;

import com.storm.iotdata.models.*;

/**
 *
 * @author hiiamlala
 */
/**
 * Bolt_split chuyển bản ghi thô thành nhiều stream theo window động.
 *
 * Vai trò:
 * - Parse tuple raw từ `Spout_data`.
 * - Tính `year/month/day/sliceIndex` cho từng window.
 * - Fan-out cùng một bản ghi sang nhiều stream `window-*` để mỗi `Bolt_avg` xử lý một độ dài cửa sổ.
 *
 * Input:
 * - Stream `data` từ `Spout_data`.
 *
 * Output:
 * - Các stream động `window-{minutes}` gửi tới `Bolt_avg` tương ứng.
 *
 * State:
 * - Không có state dữ liệu tích lũy; chỉ giữ danh sách window cấu hình.
 *
 * Tóm tắt:
 * - Component này là bước chuẩn hóa thời gian và nhân bản dữ liệu theo nhiều window.
 * - State quan trọng là `windowList`.
 * - Có thể chạy song song, nhưng việc dùng `allGrouping` ở upstream/downstream ảnh hưởng pattern phân phối.
 * - Điểm nghẽn chủ yếu là parse chuỗi và emit lặp lại theo số lượng window.
 */
public class Bolt_split extends BaseRichBolt {
    private static final String INPUT_STREAM = "data";
    private static final String STREAM_PREFIX = "window-";

    // Cấu hình toàn cục; hiện được dùng để lấy danh sách window.
    private StormConfig config;
    // Danh sách window (phút) cần fan-out dữ liệu sang.
    // Tên đề xuất dễ hiểu hơn: `windowSizesInMinutes`.
    private List<Integer> windowList;

    public Bolt_split(StormConfig config) {
        this.config = config;
        this.windowList = config.getWindowList();
    }

    // output collector
    private OutputCollector _collector;

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        for (Integer window : windowList) {
            declarer.declareStream(
                STREAM_PREFIX + window,
                new Fields("houseId", "householdId", "deviceId", "year", "month", "day", "sliceIndex", "value")
            );
        }
    }

    @Override
    public void prepare(Map<String, Object> map, TopologyContext tc, OutputCollector oc) {
        _collector = oc;
    }

    @Override
    public void execute(Tuple tuple) {
        try{
            if(tuple.getSourceStreamId().equals(INPUT_STREAM)){
                // Parse dữ liệu thô từ Spout_data.
                Integer houseId     = Integer.parseInt((String)tuple.getValueByField("houseId"));
                Integer householdId = Integer.parseInt((String)tuple.getValueByField("householdId"));
                Integer plugId      = Integer.parseInt((String)tuple.getValueByField("plugId"));
                Long    timestamp    = Long.parseLong((String)tuple.getValueByField("timestamp"));
                Double  value        = Double.parseDouble((String)tuple.getValueByField("value"));
                // Integer property     = Integer.parseInt((String)tuple.getValueByField("property"));
                // Timestamp stamp = new Timestamp(timestamp);
                // Chuyển Unix timestamp sang các thành phần thời gian để downstream dùng làm khóa timeslice.
                Date date = new Date(timestamp*1000);
                String year = Integer.toString(1900 + date.getYear());;
                String month = String.format("%02d", (1+date.getMonth()));
                String day = String.format("%02d", date.getDate()) ;
                // Số mili giây tính từ đầu ngày, dùng để nội suy index theo từng window.
                Long time = (date.getTime()%86400000);

                for (Integer window : windowList) {
                    // Mỗi window tạo ra một sliceIndex khác nhau cho cùng bản ghi.
                    // Bước này bắt buộc vì downstream lưu state theo `(house/device, timeslice)`.
                    int sliceIndex = (int) Math.floorDiv(time, (window * 60000));
                    _collector.emit(
                        STREAM_PREFIX + window,
                        tuple,
                        new Values(houseId, householdId, plugId, year, month, day, sliceIndex, value)
                    );
                }
                _collector.ack(tuple);
            }
            else{
                _collector.fail(tuple);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            _collector.fail(tuple);
        }
    }
}