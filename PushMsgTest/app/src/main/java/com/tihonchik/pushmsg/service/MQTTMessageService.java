package com.tihonchik.pushmsg.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.ibm.mqtt.IMqttClient;
import com.ibm.mqtt.MqttClient;
import com.ibm.mqtt.MqttException;
import com.ibm.mqtt.MqttNotConnectedException;
import com.ibm.mqtt.MqttPersistence;
import com.ibm.mqtt.MqttPersistenceException;
import com.ibm.mqtt.MqttSimpleCallback;
import com.tihonchik.pushmsg.AppConfig;
import com.tihonchik.pushmsg.AppDefines;
import com.tihonchik.pushmsg.R;
import com.tihonchik.pushmsg.ui.MainActivity;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;

public class MQTTMessageService extends Service implements MqttSimpleCallback {

    private MQTTConnectionStatus connectionStatus = MQTTConnectionStatus.INITIAL;
    private String brokerHostName = "";
    private String topicName = "";

    private MqttPersistence usePersistence = null;
    private int[] qualitiesOfService = {0};

    private short keepAliveSeconds = 20 * 60;

    private String mqttClientId = null;
    private LocalBinder<MQTTMessageService> mBinder;

    private IMqttClient mqttClient = null;
    private NetworkConnectionIntentReceiver netConnReceiver;
    private BackgroundDataChangeIntentReceiver dataEnabledReceiver;
    private PingSender pingSender;

    private Hashtable<String, String> dataCache = new Hashtable<>();


    @Override
    public void onCreate() {
        super.onCreate();
        connectionStatus = MQTTConnectionStatus.INITIAL;
        mBinder = new LocalBinder<>(this);

        SharedPreferences settings = getSharedPreferences(AppConfig.APP_ID, MODE_PRIVATE);
        brokerHostName = settings.getString("broker", "");
        topicName = settings.getString("topic", "");
        dataEnabledReceiver = new BackgroundDataChangeIntentReceiver();
        registerReceiver(dataEnabledReceiver, new IntentFilter(ConnectivityManager.ACTION_BACKGROUND_DATA_SETTING_CHANGED));

        defineConnectionToBroker(brokerHostName);
    }


    @Override
    public int onStartCommand(final Intent intent, int flags, final int startId) {
        handleStart(intent, startId);
        new Thread(new Runnable() {
            @Override
            public void run() {
                handleStart(intent, startId);
            }
        }, "MQTTservice").start();

        return START_NOT_STICKY;
    }

    synchronized void handleStart(Intent intent, int startId) {
        if (mqttClient == null) {
            stopSelf();
            return;
        }

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm.getActiveNetworkInfo() == null) {
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_DATADISABLED;
            broadcastServiceStatus("Not connected - background data disabled");
            return;
        }

        rebroadcastStatus();
        rebroadcastReceivedMessages();

        if (!isAlreadyConnected()) {
            connectionStatus = MQTTConnectionStatus.CONNECTING;
            notifyUser("MQTT", "MQTT Service is running", AppDefines.MQTT_NOTIFICATION_ONGOING);

            if (isOnline()) {
                if (connectToBroker()) {
                    subscribeToTopic(topicName);
                }
            } else {
                connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;
                broadcastServiceStatus("Waiting for network connection");
            }
        }

        if (netConnReceiver == null) {
            netConnReceiver = new NetworkConnectionIntentReceiver();
            registerReceiver(netConnReceiver,
                    new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }

        if (pingSender == null) {
            pingSender = new PingSender();
            registerReceiver(pingSender, new IntentFilter(AppConfig.MQTT_PING_ACTION));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // disconnect immediately
        disconnectFromBroker();

        // inform the app that the app has successfully disconnected
        broadcastServiceStatus("Disconnected");

        // try not to leak the listener
        if (dataEnabledReceiver != null) {
            unregisterReceiver(dataEnabledReceiver);
            dataEnabledReceiver = null;
        }

        if (mBinder != null) {
            mBinder.close();
            mBinder = null;
        }
    }


    private void broadcastServiceStatus(String statusDescription) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(AppConfig.MQTT_STATUS_INTENT);
        broadcastIntent.putExtra(AppConfig.MQTT_STATUS_MSG, statusDescription);
        sendBroadcast(broadcastIntent);
    }

    private void broadcastReceivedMessage(String topic, String message) {
        Intent broadcastIntent = new Intent();
        broadcastIntent.setAction(AppConfig.MQTT_MSG_RECEIVED_INTENT);
        broadcastIntent.putExtra(AppConfig.MQTT_MSG_RECEIVED_TOPIC, topic);
        broadcastIntent.putExtra(AppConfig.MQTT_MSG_RECEIVED_MSG, message);
        sendBroadcast(broadcastIntent);
    }

    private void notifyUser(String title, String body, int notificationType) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_launcher)
                .setTicker(title).setWhen(System.currentTimeMillis()).setAutoCancel(true)
                .setContentTitle(title).setDefaults(Notification.DEFAULT_ALL)
                .setContentText(body)
                .setContentIntent(contentIntent).build();
        nm.notify(notificationType, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class LocalBinder<S> extends Binder {
        private WeakReference<S> mService;

        public LocalBinder(S service) {
            mService = new WeakReference<>(service);
        }

        public S getService() {
            return mService.get();
        }

        public void close() {
            mService = null;
        }
    }

    public void rebroadcastStatus() {
        String status = "";

        switch (connectionStatus) {
            case INITIAL:
                status = "Please wait";
                break;
            case CONNECTING:
                status = "Connecting...";
                break;
            case CONNECTED:
                status = "Connected";
                break;
            case NOTCONNECTED_UNKNOWNREASON:
                status = "Not connected - waiting for network connection";
                break;
            case NOTCONNECTED_USERDISCONNECT:
                status = "Disconnected";
                break;
            case NOTCONNECTED_DATADISABLED:
                status = "Not connected - background data disabled";
                break;
            case NOTCONNECTED_WAITINGFORINTERNET:
                status = "Unable to connect";
                break;
        }
        broadcastServiceStatus(status);
    }

    public MQTTConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public void disconnect() {
        disconnectFromBroker();
        connectionStatus = MQTTConnectionStatus.NOTCONNECTED_USERDISCONNECT;
        broadcastServiceStatus("Disconnected");
    }

    public void connectionLost() throws Exception {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();

        if (!isOnline()) {
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_WAITINGFORINTERNET;
            broadcastServiceStatus("Connection lost - no network connection");
            notifyUser("MQTT", "Connection lost - no network connection", AppDefines.MQTT_NOTIFICATION_UPDATE);
        } else {
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            broadcastServiceStatus("Connection lost - reconnecting...");
            if (connectToBroker()) {
                subscribeToTopic(topicName);
            }
        }
        wl.release();
    }

    public void publishArrived(String topic, byte[] payloadbytes, int qos, boolean retained) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
        wl.acquire();
        String messageBody = new String(payloadbytes);
        if (addReceivedMessageToStore(topic, messageBody)) {
            broadcastReceivedMessage(topic, messageBody);
            notifyUser(topic, messageBody, AppDefines.MQTT_NOTIFICATION_UPDATE);
        }
        scheduleNextPing();
        wl.release();
    }

    private void defineConnectionToBroker(String brokerHostName) {
        int brokerPortNumber = 1883;
        String mqttConnSpec = "tcp://" + brokerHostName + "@" + brokerPortNumber;
        try {
            mqttClient = MqttClient.createMqttClient(mqttConnSpec, usePersistence);
            mqttClient.registerSimpleHandler(this);
        } catch (MqttException e) {
            mqttClient = null;
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            broadcastServiceStatus("Invalid connection parameters");
            notifyUser("MQTT", "Unable to connect", AppDefines.MQTT_NOTIFICATION_UPDATE);
        }
    }

    private boolean connectToBroker() {
        try {
            mqttClient.connect(generateClientId(), false, keepAliveSeconds);
            broadcastServiceStatus("Connected");
            connectionStatus = MQTTConnectionStatus.CONNECTED;
            scheduleNextPing();
            return true;
        } catch (MqttException e) {
            connectionStatus = MQTTConnectionStatus.NOTCONNECTED_UNKNOWNREASON;
            broadcastServiceStatus("Unable to connect");
            notifyUser("MQTT", "Unable to connect - will retry later", AppDefines.MQTT_NOTIFICATION_UPDATE);
            scheduleNextPing();
            return false;
        }
    }

    private void subscribeToTopic(String topicName) {
        boolean subscribed = false;

        if (!isAlreadyConnected()) {
            Log.e("mqtt", "Unable to subscribe as we are not connected");
        } else {
            try {
                String[] topics = {topicName};
                mqttClient.subscribe(topics, qualitiesOfService);
                subscribed = true;
            } catch (MqttNotConnectedException e) {
                Log.e("mqtt", "subscribe failed - MQTT not connected", e);
            } catch (IllegalArgumentException e) {
                Log.e("mqtt", "subscribe failed - illegal argument", e);
            } catch (MqttException e) {
                Log.e("mqtt", "subscribe failed - MQTT exception", e);
            }
        }

        if (!subscribed) {
            broadcastServiceStatus("Unable to subscribe");
            notifyUser("MQTT", "Unable to subscribe", AppDefines.MQTT_NOTIFICATION_UPDATE);
        }
    }

    private void disconnectFromBroker() {
        try {
            if (netConnReceiver != null) {
                unregisterReceiver(netConnReceiver);
                netConnReceiver = null;
            }
            if (pingSender != null) {
                unregisterReceiver(pingSender);
                pingSender = null;
            }
        } catch (Exception eee) {
            Log.e("mqtt", "unregister failed", eee);
        }

        try {
            if (mqttClient != null) {
                mqttClient.disconnect();
            }
        } catch (MqttPersistenceException e) {
            Log.e("mqtt", "disconnect failed - persistence exception", e);
        } finally {
            mqttClient = null;
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancelAll();
    }

    private boolean isAlreadyConnected() {
        return ((mqttClient != null) && mqttClient.isConnected());
    }

    private class BackgroundDataChangeIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
            wl.acquire();

            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm.getActiveNetworkInfo() == null) {
                defineConnectionToBroker(brokerHostName);
                handleStart(intent, 0);
            } else {
                connectionStatus = MQTTConnectionStatus.NOTCONNECTED_DATADISABLED;
                broadcastServiceStatus("Not connected - background data disabled");
                disconnectFromBroker();
            }
            wl.release();
        }
    }

    private class NetworkConnectionIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MQTT");
            wl.acquire();

            if (isOnline()) {
                if (connectToBroker()) {
                    subscribeToTopic(topicName);
                }
            }
            wl.release();
        }
    }

    private void scheduleNextPing() {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(AppConfig.MQTT_PING_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT);

        Calendar wakeUpTime = Calendar.getInstance();
        wakeUpTime.add(Calendar.SECOND, keepAliveSeconds);

        AlarmManager aMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        aMgr.set(AlarmManager.RTC_WAKEUP,
                wakeUpTime.getTimeInMillis(),
                pendingIntent);
    }

    public class PingSender extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                mqttClient.ping();
            } catch (MqttException e) {
                Log.e("mqtt", "ping failed - MQTT exception", e);
                try {
                    mqttClient.disconnect();
                } catch (MqttPersistenceException e1) {
                    Log.e("mqtt", "disconnect failed - persistence exception", e1);
                }
                if (connectToBroker()) {
                    subscribeToTopic(topicName);
                }
            }
            scheduleNextPing();
        }
    }

    private boolean addReceivedMessageToStore(String key, String value) {
        String previousValue;
        if (value.length() == 0) {
            previousValue = dataCache.remove(key);
        } else {
            previousValue = dataCache.put(key, value);
        }
        return ((previousValue == null) || !previousValue.equals(value));
    }

    public void rebroadcastReceivedMessages() {
        Enumeration<String> e = dataCache.keys();
        while (e.hasMoreElements()) {
            String nextKey = e.nextElement();
            String nextValue = dataCache.get(nextKey);
            broadcastReceivedMessage(nextKey, nextValue);
        }
    }

    private String generateClientId() {
        if (mqttClientId == null) {
            String timestamp = "" + (new Date()).getTime();
            String android_id = Settings.System.getString(getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            mqttClientId = timestamp + android_id;

            if (mqttClientId.length() > AppDefines.MAX_MQTT_CLIENTID_LENGTH) {
                mqttClientId = mqttClientId.substring(0, AppDefines.MAX_MQTT_CLIENTID_LENGTH);
            }
        }
        return mqttClientId;
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        return (cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isAvailable() &&
                cm.getActiveNetworkInfo().isConnected());
    }
}

