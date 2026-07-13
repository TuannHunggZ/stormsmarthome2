/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.storm.iotdata;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

import com.storm.iotdata.functions.*;
import com.storm.iotdata.models.*;
import com.storm.iotdata.storm.*;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.storm.Config;
import org.apache.storm.LocalCluster;
import org.apache.storm.StormSubmitter;
import org.apache.storm.topology.BoltDeclarer;
import org.apache.storm.topology.TopologyBuilder;

/**
 * MainTopo dựng toàn bộ topology Apache Storm cho bài toán phân tích điện năng IoT.
 *
 * Vai trò:
 * - Khởi tạo config, nguồn dữ liệu, và kết nối giữa các component.
 * - Một luồng dữ liệu thô đi theo hướng: Spout_data -> Bolt_split -> Bolt_avg -> Bolt_sum/Bolt_forecast.
 * - Một luồng trigger đồng bộ chu kỳ đi theo hướng: Spout_trigger -> Bolt_avg -> Bolt_sum -> Bolt_forecast.
 *
 * Input:
 * - Tham số CLI để override broker, topic, danh sách window và chế độ chạy local.
 *
 * Output:
 * - Topology đã được submit lên LocalCluster hoặc Storm cluster.
 *
 * State:
 * - File này không giữ state runtime lâu dài; chỉ giữ cấu hình và mapping tạm thời khi dựng topology.
 *
 * Tóm tắt:
 * - Component này chịu trách nhiệm "wiring" toàn bộ graph xử lý.
 * - State quan trọng là `config`, `avgList`, `sumList`, `forecastList`.
 * - Bản thân file này không phải điểm nghẽn xử lý vì chỉ chạy lúc khởi động.
 * - Có thể mở rộng parallelism ở đây bằng cách tăng parallelism hint khi setSpout/setBolt.
 */
public class MainTopo {
    public static void main(String[] args) throws Exception {
        // Cấu hình dùng chung cho toàn bộ topology: broker, topic, window, notification...
        StormConfig config = new StormConfig();
        System.out.println(config.toString());
        try {
            // Định nghĩa CLI option để override cấu hình từ file YAML khi cần chạy thử.
            Options options = new Options();
            Option opt_purge = new Option("p", "purge", false, "Purge data in DB");
            options.addOption(opt_purge);
            Option opt_init = new Option("i", "init", false, "Init DB");
            options.addOption(opt_init);
            Option opt_broker = new Option("b", "broker", true, "Broker URL");
            options.addOption(opt_broker);
            Option opt_topic_list = new Option("t", "topic", true, "Topic list (split by \",\" )");
            options.addOption(opt_topic_list);
            Option opt_windows_list = new Option("w", "windows", true, "Windows list (split by \",\" )");
            options.addOption(opt_windows_list);
            Option opt_develop = new Option("d", "develop", false, "Developing mode");
            options.addOption(opt_develop);

            CommandLineParser parser = new DefaultParser();
            HelpFormatter formatter = new HelpFormatter();
            CommandLine cmd;

            try {
                cmd = parser.parse(options, args);

                // Khởi tạo trạng thái DB trước khi submit topology.
                // TODO: Hai nhánh purge/init được điều khiển bằng config và CLI, cần cẩn thận khi chạy production.
                if(cmd.hasOption("purge") || config.isCleanDatabase()){
                    DB_store.purgeData();
                }
                else{
                    DB_store.initData();
                }

                // Init Broker URL
                if(cmd.hasOption("broker")){
                    config.setSpoutBrokerURL("tcp://" + cmd.getOptionValue("broker"));
                }

                // Init topic list
                if(cmd.hasOption("topic")){
                    config.setSpoutTopicList(Arrays.asList(cmd.getOptionValue("topic").split(",")));
                }

                // Init windows list

                if(cmd.hasOption("windows")){
                    Integer[] windowList = new Integer[100];
                    String[] tmp = cmd.getOptionValue("windows").split(",");
                    for(int i = 0; i < tmp.length; i++) {
                        windowList[i] = Integer.parseInt(tmp[i]);
                    }
                    config.setWindowList(Arrays.asList(windowList));
                }

                // Tạo topology graph.
                TopologyBuilder builder = new TopologyBuilder();
                builder.setSpout("spout-trigger", new Spout_trigger(config), 1);

                for (String topic : config.getSpoutTopicList()) {
                    builder.setSpout("spout-data-" + topic, new Spout_data(config, topic), 1);
                }

                // Mapping tên component theo từng window để tiện nối topology động.
                // Tên đề xuất dễ hiểu hơn:
                // - `avgList` -> `avgBoltsByWindow`
                // - `sumList` -> `sumBoltsByWindow`
                // - `forecastList` -> `forecastBoltsByWindow`
                HashMap<String, BoltDeclarer> avgList = new HashMap<String, BoltDeclarer>();
                HashMap<String, BoltDeclarer> sumList = new HashMap<String, BoltDeclarer>();
                HashMap<String, BoltDeclarer> forecastList = new HashMap<String, BoltDeclarer>();
                BoltDeclarer splitBolt = builder.setBolt("split", new Bolt_split(config), 1);

                for (Integer windowSize : config.getWindowList()) {
                    avgList.put("avg-" + windowSize,
                            builder.setBolt("avg-" + windowSize, new Bolt_avg(windowSize, config), 1).addConfiguration("tags", "cloud"));
                    sumList.put("sum-" + windowSize,
                            builder.setBolt("sum-" + windowSize, new Bolt_sum(windowSize, config), 1).addConfiguration("tags", "cloud"));
                    forecastList.put("forecast-" + windowSize,
                            builder.setBolt("forecast-" + windowSize, new Bolt_forecast(windowSize, config), 1).addConfiguration("tags", "cloud"));
                }

                // Tất cả dữ liệu thô từ các topic đều đi vào bolt chia timeslice.
                // allGrouping khiến mọi instance của `split` (nếu scale lên) đều thấy cùng một luồng dữ liệu.
                for (String topic : config.getSpoutTopicList()){
                    splitBolt.allGrouping("spout-data-" + topic, "data");
                }

                for (Integer windowSize : config.getWindowList()) {
                    // Nhánh tính trung bình theo từng window.
                    avgList.get("avg-" + windowSize).shuffleGrouping("split", "window-" + windowSize);
                    avgList.get("avg-" + windowSize).shuffleGrouping("spout-trigger", "trigger");
                    // Nhánh tính tổng house/household nhận DeviceData tổng hợp từ avg.
                    sumList.get("sum-" + windowSize).shuffleGrouping("avg-" + windowSize, "data");
                    sumList.get("sum-" + windowSize).shuffleGrouping("avg-" + windowSize, "trigger");
                    // Nhánh forecast dùng cả dữ liệu từ avg (device-level) và sum (house/household-level).
                    forecastList.get("forecast-" + windowSize).shuffleGrouping("avg-" + windowSize, "data");
                    forecastList.get("forecast-" + windowSize).shuffleGrouping("sum-" + windowSize, "data");
                    forecastList.get("forecast-" + windowSize).shuffleGrouping("sum-" + windowSize, "trigger");
                }

                Config conf = new Config();
                conf.setDebug(true);
                // conf.put(Config.TOPOLOGY_MAX_SPOUT_PENDING, 5000);
                conf.setNumWorkers(4);
                conf.registerSerialization(SpoutProp.class);
                conf.registerSerialization(DeviceData.class);
                conf.registerSerialization(HouseData.class);
                conf.registerSerialization(HouseholdData.class);

                // Chạy local để debug hoặc submit lên cluster thật.
                if(cmd.hasOption("develop")){
                    LocalCluster cluster = new LocalCluster(); // create the local cluster
                    cluster.submitTopology(config.getTopologyName(), conf, builder.createTopology());
                }
                else {
                    System.out.println("Sending Topo....");
                    StormSubmitter.submitTopology(config.getTopologyName(), conf, builder.createTopology()); // define the name of
                                                                                                // mylocal cluster, my
                                                                                                // configuration object,
                                                                                                // and my topology
                    System.out.println("Sent");
                }
            } catch (ParseException e) {
                System.out.println(e.getMessage());
                formatter.printHelp("utility-name", options);
                System.exit(1);
            }
        } catch (Exception e) {
            System.out.println(e.toString());
            BufferedWriter log = new BufferedWriter(new FileWriter(new File("Error.log"), true));
            log.write(new Date().toString() + "|" + e.toString() + "\n");
            log.close();
        }
    }
}