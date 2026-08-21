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
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import com.storm.iotdata.models.SpoutProp;
import com.storm.iotdata.models.StormConfig;

import org.apache.commons.io.FileUtils;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.IRichSpout;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;

/**
 * Spout_data là điểm vào của dữ liệu IoT thô từ MQTT.
 *
 * Vai trò:
 * - Subscribe một topic MQTT.
 * - Nhận message, đẩy vào hàng đợi nội bộ rồi emit các bản ghi "load" sang stream `data`.
 * - Tạo log ingest định kỳ để `Spout_trigger` tổng hợp.
 *
 * Input:
 * - Message MQTT dạng CSV từ broker cấu hình trong `StormConfig`.
 *
 * Output:
 * - Stream `data` gửi sang `Bolt_split`.
 * - File log tạm và MQTT log phục vụ theo dõi, không phải tuple downstream.
 *
 * State:
 * - `messages`: hàng đợi message nhận qua callback nhưng chưa emit.
 * - Các bộ đếm `total/success/fail/speed/load/totalLoad` để đo hiệu năng ingest.
 *
 * Khi state thay đổi:
 * - Thêm vào queue tại `messageArrived`.
 * - Bỏ khỏi queue và emit ở `nextTuple`.
 * - Ghi log ở `log()`.
 * - Không ghi DB trực tiếp.
 *
 * Tóm tắt:
 * - Component này chuyển MQTT message thành tuple Storm.
 * - State quan trọng nhất là `messages` và các bộ đếm tốc độ.
 * - Có thể scale song song theo topic hoặc nhiều instance, nhưng cần chú ý semantics subscribe MQTT.
 * - Điểm nghẽn hiệu năng nằm ở parse chuỗi CSV, I/O log định kỳ và queue nội bộ nếu tốc độ ingest cao.
 */
public class Spout_data implements MqttCallback, IRichSpout {

    // Collector dùng để emit tuple Storm.
    private SpoutOutputCollector _collector;
    // Hàng đợi đệm giữa callback MQTT và vòng lặp `nextTuple()` của Storm.
    // Tên đề xuất dễ hiểu hơn: `pendingMessages`.
    LinkedBlockingQueue<String> messages;
    // Tổng số message MQTT đã nhận từ broker.
    Long total = Long.valueOf(0);
    // Số tuple đã được Storm ack.
    Long success = Long.valueOf(0);
    // Số tuple bị Storm fail.
    Long fail = Long.valueOf(0);
    // Bộ đếm message dùng để tính tốc độ ingest trong khoảng log gần nhất.
    Long speed = Long.valueOf(0);
    // Số message thuộc property "load" trong khoảng log gần nhất.
    Long load = Long.valueOf(0);
    // Tổng số message "load" từ lúc spout khởi động.
    Long totalLoad = Long.valueOf(0);
    // Mốc thời gian log gần nhất.
    // Tên đề xuất dễ hiểu hơn: `lastLogAt`.
    Long last = System.currentTimeMillis();
    // MQTT client kết nối broker nguồn.
    MqttClient client;
    // Client id MQTT, gắn theo topology + topic.
    String clientId = "";
    // Topic MQTT đang subscribe.
    String topic = "iot-data";
    // Format topic publish log trạng thái ingest.
    String logTopic = "%sspout-log";
    // Cấu hình toàn cục.
    StormConfig config;

    public Spout_data(StormConfig config, String topic) {
        this.config = config;
        this.topic = topic;
        clientId = new String(config.getTopologyName() + "@" + topic);
        messages = new LinkedBlockingQueue<String>();
        if (!(new File("tmp").isDirectory())) {
            new File("tmp").mkdir();
        } else {
            try {
                FileUtils.cleanDirectory(new File("tmp"));
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public void messageArrived(String topic, MqttMessage message) throws Exception {
        // Callback MQTT chỉ enqueue dữ liệu để tránh làm nặng thread callback bằng xử lý business.
        messages.add(message.toString());
        total++;
        speed++;
    }

    public void connectionLost(Throwable cause) {
        try {
            log();
            System.out.println("[Spout-data-" + topic + "] Lost connection with broker. Trying to reconnect in 10s");
            Thread.sleep(10000);
            client.reconnect();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
        _collector = collector;
        System.out.println("[Spout-data-" + topic + "] Connecting to broker (" + config.getSpoutBrokerURL() + ")..");
        initMQTTClient();
    }

    public void close() {
        try {
            client.disconnect();
        } catch (MqttException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        new File("/tmp/spout_log_" + topic + ".tmp").delete();
    }

    public void activate() {
        if (client.isConnected()) {
            try {
                client.subscribe(topic);
                System.out.println("[Spout-data-" + topic + "] Subscribed to topic " + topic + ".");
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            try {
                client.connect();
                client.subscribe(topic);
            } catch (MqttSecurityException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                initMQTTClient();
                e.printStackTrace();
            }
        }
    }

    public void deactivate() {
        if (client.isConnected()) {
            try {
                client.unsubscribe(topic);
                System.out.println("[Spout-data-" + topic + "] Unsubscribed topic " + topic + ".");
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } else {
            try {
                client.reconnect();
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    public void nextTuple() {
        while (!messages.isEmpty()) {
            try {
                // Lấy từng message khỏi queue để chuyển thành tuple Storm.
                String message = messages.poll();
                String[] metric = message.split(",");
                // Chỉ emit metric có property == 1 (load), vì pipeline phía sau đang giả định input là công suất tiêu thụ.
                // TODO: magic number `1` khó mở rộng; nên có enum/hằng số mô tả rõ property.
                if (Integer.parseInt(metric[3]) == 1) { // On prend juste les loads
                    _collector.emit("data", new Values(metric[1], metric[2], metric[3], metric[4], metric[5], metric[6]),
                            message);
                    load++;
                    totalLoad++;
                }
                // Ghi log định kỳ để Spout_trigger có snapshot mới nhất khi phát trigger.
                if (System.currentTimeMillis() - last > 10000) {
                    log();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (System.currentTimeMillis() - last > 10000) {
            log();
        }
    }

    public void log() {
        // Snapshot tình trạng ingest hiện tại dưới dạng JSON và publish/log ra ngoài.
        String log = new SpoutProp(clientId, client.isConnected(),
                (float) (speed * 1000 / (System.currentTimeMillis() - last)),
                (float) (load * 1000 / (System.currentTimeMillis() - last)), total, totalLoad, messages.size(), success,
                fail).toString();
        new SpoutDataLogger(client, String.format(logTopic, config.getMqttTopicPrefix()),
                new File("tmp/spout_data_log_" + topic + ".tmp"), log).start();
        speed = Long.valueOf(0);
        load = Long.valueOf(0);
        last = System.currentTimeMillis();
    }

    public void ack(Object msgId) {
        success++;
    }

    public void fail(Object msgId) {
        fail++;
    }

    public Map<String, Object> getComponentConfiguration() {
        return null;
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        /* uses default stream id */
        declarer.declareStream("data", new Fields("timestamp", "value", "property", "plugId", "householdId", "houseId"));
    }

    public void initMQTTClient() {
        System.out.println("[Spout-data-" + topic + "] Connecting to broker (" + config.getSpoutBrokerURL() + ")..");
        try {
            if (client!=null) {
                // Đóng client cũ trước khi tạo lại để tránh giữ socket cũ khi reconnect đệ quy.
                client.close(true);
            }
            client = new MqttClient(config.getSpoutBrokerURL(), clientId);
            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(10);
            client.connect(options);
            System.out.println("[Spout-data-" + topic + "] Connected to broker (" + config.getSpoutBrokerURL() + ").");
            client.setCallback(this);
        } catch (MqttException e) {
            e.printStackTrace();
            try {
                Thread.sleep(10000);
                System.out.println("[Spout-data-" + topic + "] Waiting 10s before retry to connect to MQTT Broker (" + config.getSpoutBrokerURL() + ").");
                initMQTTClient();
            } catch (InterruptedException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
        }
    }
}

class SpoutDataLogger extends Thread{
    String log;
    MqttClient client;
    String logTopic;
    File logFile;

    public SpoutDataLogger(MqttClient client,String logTopic, File logFile, String log){
        this.client = client;
        this.logTopic = logTopic;
        this.logFile = logFile;
        this.log = log;
    }

    @Override
    public void run(){
        try {
            FileWriter logWriter = new FileWriter(logFile, false);
            PrintWriter pwOb = new PrintWriter(logWriter , false);
            pwOb.flush();
            logWriter.write(log);
            pwOb.close();
            logWriter.close();
            byte[] payLoad = log.getBytes();
            client.publish(logTopic, payLoad, 0, true);
        } catch (MqttException ex) {
            ex.printStackTrace();
            try {
                client.reconnect();
            } catch (MqttException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
