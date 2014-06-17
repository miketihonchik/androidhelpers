package com.tihonchik.pushmsg.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.tihonchik.pushmsg.AppConfig;
import com.tihonchik.pushmsg.AppDefines;
import com.tihonchik.pushmsg.R;
import com.tihonchik.pushmsg.service.MQTTMessageService;

public class MainActivity extends Activity {
    private String mDeviceID;
    private StatusUpdateReceiver statusUpdateIntentReceiver;
    private MQTTMessageReceiver messageIntentReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        statusUpdateIntentReceiver = new StatusUpdateReceiver();
        IntentFilter intentSFilter = new IntentFilter(AppConfig.MQTT_STATUS_INTENT);
        registerReceiver(statusUpdateIntentReceiver, intentSFilter);

        messageIntentReceiver = new MQTTMessageReceiver();
        IntentFilter intentCFilter = new IntentFilter(AppConfig.MQTT_MSG_RECEIVED_INTENT);
        registerReceiver(messageIntentReceiver, intentCFilter);

        SharedPreferences settings = getSharedPreferences(AppConfig.APP_ID, 0);
        SharedPreferences.Editor editor = settings.edit();
        //editor.putString("broker", preferenceBrokerHost);
        //editor.putString("topic",  preferenceBrokerUser);
        editor.commit();

        if (Build.FINGERPRINT.contains("generic")) {
            mDeviceID = AppDefines.EMULATOR_ID;
        } else {
            mDeviceID = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        ((TextView) findViewById(R.id.target_text)).setText(mDeviceID);

        final Button startButton = ((Button) findViewById(R.id.start_button));
        final Button stopButton = ((Button) findViewById(R.id.stop_button));
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent svc = new Intent(getApplicationContext(), MQTTMessageService.class);
                startService(svc);
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent svc = new Intent(getApplicationContext(), MQTTMessageService.class);
                stopService(svc);
                startButton.setEnabled(true);
                stopButton.setEnabled(false);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        /* TODO: insert logic to check if MQTT is running */
        boolean started = true;
        findViewById(R.id.start_button).setEnabled(!started);
        findViewById(R.id.stop_button).setEnabled(started);
    }

    public class StatusUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle notificationData = intent.getExtras();
            String newStatus = notificationData.getString(AppConfig.MQTT_STATUS_MSG);
        }
    }

    public class MQTTMessageReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle notificationData = intent.getExtras();
            String newTopic = notificationData.getString(AppConfig.MQTT_MSG_RECEIVED_TOPIC);
            String newData = notificationData.getString(AppConfig.MQTT_MSG_RECEIVED_MSG);
        }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(statusUpdateIntentReceiver);
        unregisterReceiver(messageIntentReceiver);
    }
}
