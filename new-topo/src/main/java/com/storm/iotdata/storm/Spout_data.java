package com.storm.iotdata.storm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.storm.spout.SpoutOutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseRichSpout;
import org.apache.storm.tuple.Fields;
import org.apache.storm.tuple.Values;
import org.apache.storm.utils.Utils;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.storm.iotdata.models.StormConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Storm spout that subscribes to an MQTT topic, parses incoming JSON load
 * measurements, keeps only load events (property == 1) and emits:
 * - a {@code "data"} tuple per load event, and
 * - a {@code "trigger"} tuple every time the event-time crosses a 60-second
 *   boundary (event-time watermark, no stored lateness).
 *
 * <p>This merges the legacy {@code Spout_trigger} heartbeat into the spout
 * itself and replaces the previous per-window punctuation streams (1m/5m/...
 * /120m) with a single 60-second event-time trigger.</p>
 *
 * <p>There is intentionally no ingest monitoring (no counters, no ingest log
 * published to MQTT / written to tmp files): the trigger is a pure
 * event-time window marker. Reliability (ack/fail) is not required.</p>
 */
public class Spout_data extends BaseRichSpout {

    private static final Logger LOGGER = LoggerFactory.getLogger(Spout_data.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String brokerUri;
    private final String topic;
    private final int qos;
    private final int maxEmitPerNextTuple;
    private final int queueCapacity;
    private final int triggerIntervalSeconds;   // length of the event-time window => 60s
    private final String streamIdData;
    private final String streamIdTrigger;
    private final String fieldId;
    private final String fieldTimestamp;
    private final String fieldValue;
    private final String fieldPlugId;
    private final String fieldHouseholdId;
    private final String fieldHouseId;
    private final String fieldWindowSize;
    private final int propertyLoad;
    private final int connectionTimeoutSeconds;

    private transient SpoutOutputCollector collector;
    private transient MqttClient mqttClient;

    /** Bounded buffer between the MQTT callback thread and {@link #nextTuple()}. */
    private final BlockingQueue<StreamEvent> eventQueue;

    /**
     * Floor index (timestamp / triggerIntervalSeconds) of the last observed event.
     * {@link Long#MIN_VALUE} means no event has been observed yet.
     */
    private long lastObservedInterval = Long.MIN_VALUE;

    public Spout_data() {
        this.brokerUri = StormConfig.getBrokerUri();
        this.topic = StormConfig.getBrokerTopic();
        this.qos = StormConfig.getQos();
        this.maxEmitPerNextTuple = StormConfig.getMaxEmitPerNextTuple();
        this.queueCapacity = StormConfig.getQueueCapacity();
        this.triggerIntervalSeconds = StormConfig.getTriggerIntervalSeconds();
        this.propertyLoad = StormConfig.getPropertyLoad();
        this.connectionTimeoutSeconds = StormConfig.getConnectionTimeoutSeconds();

        this.streamIdData = "data";
        this.streamIdTrigger = "trigger";
        this.fieldId = "id";
        this.fieldTimestamp = "timestamp";
        this.fieldValue = "value";
        this.fieldPlugId = "plugId";
        this.fieldHouseholdId = "householdId";
        this.fieldHouseId = "houseId";
        this.fieldWindowSize = "windowSize";

        this.eventQueue = new LinkedBlockingQueue<>(this.queueCapacity);
    }

    @Override
    public void open(Map<String, Object> conf, TopologyContext context, SpoutOutputCollector collector) {
        this.collector = collector;
        initializeMqttClient();
    }

    @Override
    public void nextTuple() {
        int emitted = 0;

        while (emitted < maxEmitPerNextTuple) {
            StreamEvent event = eventQueue.poll();
            if (event == null) {
                break;
            }

            if (event instanceof LoadEvent) {
                emitDataEvent((LoadEvent) event);
            } else if (event instanceof TriggerEvent) {
                emitTriggerEvent((TriggerEvent) event);
            }
            emitted += 1;
        }

        if (emitted == 0) {
            Utils.sleep(1);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        // "data" stream: one load measurement tuple.
        declarer.declareStream(
            streamIdData,
            new Fields(fieldId, fieldTimestamp, fieldValue, fieldPlugId, fieldHouseholdId, fieldHouseId)
        );
        // "trigger" stream: one event-time window-finalization marker per 60s.
        declarer.declareStream(
            streamIdTrigger,
            new Fields(fieldWindowSize, fieldTimestamp)
        );
    }

    @Override
    public void close() {
        if (mqttClient == null) {
            return;
        }

        try {
            mqttClient.disconnect();
        } catch (MqttException exception) {
            LOGGER.warn("Failed to disconnect MQTT client cleanly", exception);
        } finally {
            try {
                mqttClient.close();
            } catch (MqttException exception) {
                LOGGER.warn("Failed to close MQTT client", exception);
            }
            mqttClient = null;
        }
    }

    @Override
    public void ack(Object msgId) {
        // Reliability is not required for this spout.
    }

    @Override
    public void fail(Object msgId) {
        // Reliability is not required for this spout.
    }

    private void initializeMqttClient() {
        try {
            String clientId = "storm-spout-" + UUID.randomUUID();
            mqttClient = new MqttClient(brokerUri, clientId);

            MqttConnectOptions connectOptions = new MqttConnectOptions();
            connectOptions.setAutomaticReconnect(true);
            connectOptions.setCleanSession(true);
            connectOptions.setConnectionTimeout(connectionTimeoutSeconds);

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    if (reconnect) {
                        LOGGER.info("MQTT reconnected to {}", serverURI);
                    } else {
                        LOGGER.info("MQTT connected to {}", serverURI);
                    }
                    subscribeToTopic();
                }

                @Override
                public void connectionLost(Throwable cause) {
                    LOGGER.warn("MQTT connection lost", cause);
                }

                @Override
                public void messageArrived(String incomingTopic, MqttMessage message) {
                    handleIncomingMessage(incomingTopic, message);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                    // Not used by this spout.
                }
            });

            mqttClient.connect(connectOptions);
        } catch (MqttException exception) {
            throw new IllegalStateException("Unable to initialize MQTT client", exception);
        }
    }

    private void subscribeToTopic() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            return;
        }

        try {
            mqttClient.subscribe(topic, qos);
            LOGGER.info("Subscribed to MQTT topic {} with qos {}", topic, qos);
        } catch (MqttException exception) {
            LOGGER.error("Failed to subscribe to topic {}", topic, exception);
        }
    }

    private void handleIncomingMessage(String incomingTopic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            LoadEvent event = parseLoadEvent(root);

            if (event == null) {
                return;
            }

            List<StreamEvent> streamEvents = buildStreamEvents(event);
            for (StreamEvent streamEvent : streamEvents) {
                boolean enqueued = eventQueue.offer(streamEvent);
                if (!enqueued) {
                    LOGGER.warn("Event queue is full, dropping message from topic {}", incomingTopic);
                    return;
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Failed to parse MQTT JSON payload from topic {}", incomingTopic, exception);
        }
    }

    private LoadEvent parseLoadEvent(JsonNode root) {
        if (root == null) {
            return null;
        }

        int property = getRequiredInt(root, "property");
        if (property != propertyLoad) {
            return null;
        }

        return new LoadEvent(
            getRequiredLong(root, "id"),
            getRequiredLong(root, "timestamp"),
            getRequiredDouble(root, "value"),
            getRequiredInt(root, "plug_id"),
            getRequiredInt(root, "household_id"),
            getRequiredInt(root, "house_id")
        );
    }

    /**
     * Builds the sequence of stream events produced by one load measurement:
     * any {@link TriggerEvent} for completed 60-second windows that became
     * observable, then the {@link LoadEvent} itself.
     *
     * <p>The event-time watermark only moves forward: a 60-second window
     * {@code [minute*interval, minute*interval + interval)} is finalized as
     * soon as an event whose timestamp is in a strictly later window is
     * observed. Late (out-of-order) events never regress the watermark and
     * emit no trigger.</p>
     *
     * @param event the parsed load measurement
     * @return ordered list of stream events (triggers first, then the load event)
     */
    private List<StreamEvent> buildStreamEvents(LoadEvent event) {
        List<StreamEvent> streamEvents = new ArrayList<>();

        long currentInterval = Math.floorDiv(event.timestamp, triggerIntervalSeconds);

        if (currentInterval > lastObservedInterval) {
            if (lastObservedInterval != Long.MIN_VALUE) {
                // Finalize every 60-second window strictly before the current one
                // that has not been finalized yet. Each completed window is emitted
                // exactly once, in chronological order.
                for (long interval = lastObservedInterval; interval < currentInterval; interval += 1) {
                    long sliceTimestamp = interval * triggerIntervalSeconds;
                    streamEvents.add(new TriggerEvent(triggerIntervalSeconds, sliceTimestamp));
                    LOGGER.info("Emitted trigger: window={}s finalizedAt={}", triggerIntervalSeconds, sliceTimestamp);
                }
            }
            lastObservedInterval = currentInterval;
        }

        streamEvents.add(event);
        return streamEvents;
    }

    private void emitDataEvent(LoadEvent event) {
        collector.emit(
            streamIdData,
            new Values(event.id, event.timestamp, event.value, event.plugId, event.householdId, event.houseId),
            event.id
        );
    }

    private void emitTriggerEvent(TriggerEvent event) {
        collector.emit(
            streamIdTrigger,
            new Values(event.windowSize, event.timestamp),
            streamIdTrigger + "-" + event.timestamp
        );
    }

    private int getRequiredInt(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);

        if (node == null) {
            throw new IllegalArgumentException("Missing field: " + fieldName);
        }

        try {
            return Integer.parseInt(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer field: " + fieldName, e);
        }
    }

    private long getRequiredLong(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null || !node.canConvertToLong()) {
            throw new IllegalArgumentException("Missing or invalid field: " + fieldName);
        }
        return node.asLong();
    }

    private double getRequiredDouble(JsonNode root, String fieldName) {
        JsonNode node = root.get(fieldName);
        if (node == null) {
            throw new IllegalArgumentException("Missing field: " + fieldName);
        }

        try {
            return Double.parseDouble(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid double field: " + fieldName, e);
        }
    }

    /** Marker interface for every event pushed to the internal queue. */
    private interface StreamEvent {
    }

    /**
     * Event emitted on the {@code "trigger"} stream at each completed 60-second
     * window boundary.
     */
    private static final class TriggerEvent implements StreamEvent {

        private final int windowSize;   // seconds (60)
        private final long timestamp;   // start second of the completed window

        private TriggerEvent(int windowSize, long timestamp) {
            this.windowSize = windowSize;
            this.timestamp = timestamp;
        }
    }

    /** Parsed load measurement emitted on the {@code "data"} stream. */
    private static final class LoadEvent implements StreamEvent {

        private final long id;
        private final long timestamp;
        private final double value;
        private final int plugId;
        private final int householdId;
        private final int houseId;

        private LoadEvent(long id, long timestamp, double value, int plugId, int householdId, int houseId) {
            this.id = id;
            this.timestamp = timestamp;
            this.value = value;
            this.plugId = plugId;
            this.householdId = householdId;
            this.houseId = houseId;
        }
    }
}
