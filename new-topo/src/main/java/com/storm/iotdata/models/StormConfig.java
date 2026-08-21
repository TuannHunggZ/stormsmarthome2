package com.storm.iotdata.models;

public class StormConfig {

    // =====================================================================
    // TRIGGER GENERATION (Event-Time)
    // =====================================================================
    // Length, in seconds, of the event-time window after which the spout emits
    // a "trigger" tuple. A window [m*interval, (m+1)*interval) is finalized as
    // soon as an event whose timestamp is in a strictly later window is observed
    // (event-time watermark, no stored lateness). This replaces the legacy
    // processing-time Spout_trigger heartbeat and the per-window punctuation
    // streams (1m/5m/.../120m).
    private static final int triggerIntervalSeconds = 60;

    public static int getTriggerIntervalSeconds() {
        return triggerIntervalSeconds;
    }

    // =====================================================================
    // SPOUT-DATA
    // =====================================================================
    // MQTT broker URI that the spout connects to.
    private static final String brokerUri = "tcp://mqtt-broker:1883";
    // MQTT topic consumed by the spout.
    private static final String brokerTopic = "iot-data";
    // MQTT subscription QoS used by the spout.
    private static final int qos = 0;
    // Maximum number of stream events emitted by one nextTuple() call.
    private static final int maxEmitPerNextTuple = 100;
    // Maximum number of stream events buffered before new messages are dropped.
    private static final int queueCapacity = 10000;
    // Storm stream id used for data tuples.
    private static final String streamIdData = "data";
    // MQTT property value that identifies a load event.
    private static final int propertyLoad = 1;
    // MQTT connection timeout in seconds.
    private static final int connectionTimeoutSeconds = 10;

    public static String getBrokerUri() {
        return brokerUri;
    }

    public static String getBrokerTopic() {
        return brokerTopic;
    }

    public static int getQos() {
        return qos;
    }

    public static int getMaxEmitPerNextTuple() {
        return maxEmitPerNextTuple;
    }

    public static int getQueueCapacity() {
        return queueCapacity;
    }

    public static String getStreamIdData() {
        return streamIdData;
    }

    public static int getPropertyLoad() {
        return propertyLoad;
    }

    public static int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }
}
