package com.tihonchik.pushmsg;

public class AppConfig {
    public static final String APP_ID = "com.tihonchik.pushmsg";

    public static final String MQTT_MSG_RECEIVED_INTENT = APP_ID + "MSGRECVD";
    public static final String MQTT_MSG_RECEIVED_TOPIC = APP_ID + "MSGRECVD_TOPIC";
    public static final String MQTT_MSG_RECEIVED_MSG = APP_ID + "MSGRECVD_MSGBODY";

    public static final String MQTT_STATUS_INTENT = APP_ID + "STATUS";
    public static final String MQTT_STATUS_MSG = APP_ID + "STATUS_MSG";

    public static final String MQTT_PING_ACTION = APP_ID + "PING";
}
