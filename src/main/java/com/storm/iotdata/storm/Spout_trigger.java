package com.storm.iotdata.storm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.Gson;
import com.storm.iotdata.models.SpoutProp;
import com.storm.iotdata.models.StormConfig;

import org.apache.commons.io.FileUtils;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;

/**
 * Spout_trigger phát tín hiệu nhịp (heartbeat) cho toàn topology theo `updateInterval`.
 *
 * Vai trò:
 * - Đồng bộ thời điểm flush state trong các bolt phía sau.
 * - Tổng hợp log từ các `Spout_data` thành một `SpoutProp` chung để downstream ghi log/đính kèm metadata.
 *
 * Input:
 * - Không nhận tuple đầu vào; chỉ đọc file log tạm của các `Spout_data`.
 *
 * Output:
 * - Stream `trigger` gửi tới `Bolt_avg`, sau đó được chuyền tiếp sang `Bolt_sum` và `Bolt_forecast`.
 *
 * State:
 * - Không có cache dữ liệu phân tích; chỉ giữ collector, config và bộ đếm `triggerSequence`.
 *
 * Tóm tắt:
 * - Component này là bộ phát nhịp cho pipeline.
 * - State quan trọng là `triggerSequence`.
 * - Có thể scale song song về mặt kỹ thuật, nhưng thường nên để 1 để tránh nhiều trigger đồng thời.
 * - Điểm nghẽn chính là `Thread.sleep()` và thao tác đọc nhiều file log trong mỗi chu kỳ.
 */
public class Spout_trigger extends BaseRichSpout {

    // Collector dùng để emit stream trigger.
    private SpoutOutputCollector _collector;
    // Cấu hình toàn cục, đặc biệt là chu kỳ trigger.
    private StormConfig config;
    // Message id tăng dần để Storm theo dõi ack/fail cho từng trigger.
    // Tên đề xuất dễ hiểu hơn: `triggerMessageSequence`.
    private long triggerSequence = 0L;

    public Spout_trigger(StormConfig config) {
        this.config = config;
    }

    @Override
    public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
        _collector = collector;
    }

    @Override
    public void nextTuple() {
        try {
            // Mỗi vòng lặp ngủ đúng `updateInterval` giây để tạo nhịp flush ổn định cho topology.
            System.out.println("[Spout-trigger] Sleeping for "+config.getUpdateInterval()+" second(s)");
            Thread.sleep(config.getUpdateInterval() * 1000);
            // Đọc các file log tạm mà từng Spout_data đã ghi ra, sau đó gộp thành một snapshot tổng.
            File tempFolder = new File("./tmp");
            File[] spoutLogs = tempFolder.listFiles();
            String name = String.format("%s@all-spout", config.getTopologyName());
            Boolean connect = true; 
            Float totalSpeed = Float.valueOf(0), loadSpeed = Float.valueOf(0);
            Long total = Long.valueOf(0), load = Long.valueOf(0), success = Long.valueOf(0), fail = Long.valueOf(0);
            Integer queue = 0;
            for (File Log : spoutLogs) {
                try {
                    if(Log.getName().contains("spout_data_log_")){
                        // Parse JSON log của từng spout để cộng dồn tốc độ, queue và trạng thái kết nối.
                        String raw = FileUtils.readFileToString(Log, StandardCharsets.UTF_8);
                        Gson gson = new Gson();
                        SpoutProp datas = gson.fromJson(raw, SpoutProp.class);
                        if(!datas.isConnect()){
                            connect = false;
                        }
                        totalSpeed+=datas.getTotalSpeed();
                        loadSpeed+=datas.getLoadSpeed();
                        total+=datas.getTotal();
                        load+=datas.getLoad();
                        queue+=datas.getQueue();
                        success+=datas.getSuccess();
                        fail+=datas.getFail();
                    }
                } catch (FileNotFoundException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // Emit trigger kèm snapshot `SpoutProp` để downstream vừa flush state vừa biết tình trạng ingest hiện tại.
            _collector.emit("trigger", new Values(config.getUpdateInterval(), new SpoutProp(name, connect, totalSpeed, loadSpeed, total, load, queue, success, fail)), triggerSequence++); // Trigger signal to write data to file after 1 min
        } catch (InterruptedException e) {
            e.printStackTrace();
            // Nếu bị interrupt vẫn phát một trigger fallback để tránh nghẽn cả pipeline flush.
            // TODO: nhánh này che mất nguyên nhân interrupt thật, chỉ nên dùng khi chấp nhận eventual consistency.
            _collector.emit("trigger", new Values(config.getUpdateInterval(), new SpoutProp()), triggerSequence++); // Trigger signal to write data to file after 1 min
        } 
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream("trigger", new Fields("trigger", "spoutProp"));
    }
}