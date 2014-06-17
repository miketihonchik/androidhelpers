package com.tihonchik.pushmsg.service;

public enum MQTTConnectionStatus {
    INITIAL,                            // initial status
    CONNECTING,                         // attempting to connect
    CONNECTED,                          // connected
    NOTCONNECTED_WAITINGFORINTERNET,    // can't connect because the phone does not have Internet access
    NOTCONNECTED_USERDISCONNECT,        // user has explicitly requested disconnection
    NOTCONNECTED_DATADISABLED,          // can't connect because the user has disabled data access
    NOTCONNECTED_UNKNOWNREASON          // failed to connect for some reason
}
