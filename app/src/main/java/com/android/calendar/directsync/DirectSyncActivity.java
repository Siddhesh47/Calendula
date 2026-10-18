package com.android.calendar.directsync;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ws.xsoh.etar.R;

/**
 * Activity for direct device-to-device calendar synchronization over local Wi-Fi / Hotspot.
 */
public class DirectSyncActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "direct_sync_prefs";
    private static final String PREF_LAST_IP = "last_peer_ip";
    private static final String PREF_LAST_SYNC_TIME = "last_sync_time";
    private static final String PREF_HOST_MODE = "host_mode_enabled";

    private MaterialSwitch mHostSwitch;
    private TextView mHostStatusText;
    private TextView mDiscoveryStatusText;
    private LinearLayout mDiscoveredDevicesContainer;
    private TextView mNoDevicesText;
    private EditText mPeerIpInput;
    private Button mBtnManualSync;
    private ProgressBar mSyncProgress;
    private TextView mEventCountText;
    private TextView mLastSyncedText;
    private Button mBtnExportShare;

    private DirectSyncServer mServer;
    private DirectSyncDiscovery mDiscovery;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, View> mDiscoveredViews = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_direct_sync);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mHostSwitch = findViewById(R.id.host_switch);
        mHostStatusText = findViewById(R.id.host_status_text);
        mDiscoveryStatusText = findViewById(R.id.discovery_status_text);
        mDiscoveredDevicesContainer = findViewById(R.id.discovered_devices_container);
        mNoDevicesText = findViewById(R.id.no_devices_text);
        mPeerIpInput = findViewById(R.id.peer_ip_input);
        mBtnManualSync = findViewById(R.id.btn_manual_sync);
        mSyncProgress = findViewById(R.id.sync_progress);
        mEventCountText = findViewById(R.id.event_count_text);
        mLastSyncedText = findViewById(R.id.last_synced_text);
        mBtnExportShare = findViewById(R.id.btn_export_share);

        mServer = new DirectSyncServer(this);
        mDiscovery = new DirectSyncDiscovery(this);

        // Restore preferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String lastIp = prefs.getString(PREF_LAST_IP, "");
        if (!lastIp.isEmpty()) {
            mPeerIpInput.setText(lastIp);
        }

        boolean hostEnabled = prefs.getBoolean(PREF_HOST_MODE, false);
        mHostSwitch.setChecked(hostEnabled);
        updateHostServer(hostEnabled);

        mHostSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(PREF_HOST_MODE, isChecked)
                    .apply();
            updateHostServer(isChecked);
        });

        mBtnManualSync.setOnClickListener(v -> {
            String ip = mPeerIpInput.getText().toString().trim();
            if (ip.isEmpty()) {
                mPeerIpInput.setError(getString(R.string.direct_sync_ip_hint));
                return;
            }
            startSync(ip, DirectSyncServer.DEFAULT_PORT);
        });

        mBtnExportShare.setOnClickListener(v -> exportAndShareCalendar());

        updateCalendarStats();
        startPeerDiscovery();
    }

    private void updateHostServer(boolean enable) {
        if (enable) {
            boolean started = mServer.start();
            if (started) {
                String ip = DirectSyncServer.getLocalIpAddress(this);
                mHostStatusText.setText(getString(R.string.direct_sync_server_running, ip + ":" + mServer.getPort()));
                mDiscovery.registerService(mServer.getPort());
            } else {
                mHostStatusText.setText(R.string.direct_sync_server_stopped);
                mHostSwitch.setChecked(false);
            }
        } else {
            mDiscovery.unregisterService();
            mServer.stop();
            mHostStatusText.setText(R.string.direct_sync_server_stopped);
        }
    }

    private void startPeerDiscovery() {
        mDiscovery.startDiscovery(new DirectSyncDiscovery.OnDeviceDiscoveredListener() {
            @Override
            public void onDeviceFound(final String name, final String host, final int port) {
                mMainHandler.post(() -> addDiscoveredDevice(name, host, port));
            }

            @Override
            public void onDeviceLost(final String name) {
                mMainHandler.post(() -> removeDiscoveredDevice(name));
            }
        });
    }

    private void addDiscoveredDevice(final String name, final String host, final int port) {
        String key = host + ":" + port;
        if (mDiscoveredViews.containsKey(key)) return;

        mNoDevicesText.setVisibility(View.GONE);

        View itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, mDiscoveredDevicesContainer, false);
        TextView text1 = itemView.findViewById(android.R.id.text1);
        TextView text2 = itemView.findViewById(android.R.id.text2);

        text1.setText(name);
        text2.setText(host + ":" + port);

        itemView.setOnClickListener(v -> startSync(host, port));

        mDiscoveredDevicesContainer.addView(itemView);
        mDiscoveredViews.put(key, itemView);
    }

    private void removeDiscoveredDevice(String name) {
        // Remove views matching
        for (Map.Entry<String, View> entry : new HashMap<>(mDiscoveredViews).entrySet()) {
            mDiscoveredDevicesContainer.removeView(entry.getValue());
            mDiscoveredViews.remove(entry.getKey());
        }
        if (mDiscoveredViews.isEmpty()) {
            mNoDevicesText.setVisibility(View.VISIBLE);
        }
    }

    private void startSync(final String host, final int port) {
        mBtnManualSync.setEnabled(false);
        mSyncProgress.setVisibility(View.VISIBLE);
        Toast.makeText(this, R.string.direct_sync_syncing, Toast.LENGTH_SHORT).show();

        // Save last used host
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_LAST_IP, host)
                .apply();

        new Thread(() -> {
            final DirectSyncClient.SyncResult result = DirectSyncClient.syncWithPeer(DirectSyncActivity.this, host, port);
            mMainHandler.post(() -> {
                mBtnManualSync.setEnabled(true);
                mSyncProgress.setVisibility(View.GONE);

                if (result.success) {
                    long now = System.currentTimeMillis();
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                            .putLong(PREF_LAST_SYNC_TIME, now)
                            .apply();
                    Toast.makeText(DirectSyncActivity.this,
                            getString(R.string.direct_sync_success, result.syncedCount), Toast.LENGTH_LONG).show();
                    updateCalendarStats();
                } else {
                    Toast.makeText(DirectSyncActivity.this,
                            getString(R.string.direct_sync_failed, result.errorMessage), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void updateCalendarStats() {
        new Thread(() -> {
            final int count = DirectSyncCalendarManager.getSharedEventCount(this);
            final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            final long lastSync = prefs.getLong(PREF_LAST_SYNC_TIME, 0L);

            mMainHandler.post(() -> {
                mEventCountText.setText(getString(R.string.direct_sync_event_count, count));
                if (lastSync > 0) {
                    String timeStr = DateFormat.getDateTimeInstance().format(new Date(lastSync));
                    mLastSyncedText.setText(getString(R.string.direct_sync_last_synced, timeStr));
                } else {
                    mLastSyncedText.setText(getString(R.string.direct_sync_last_synced, getString(R.string.direct_sync_never)));
                }
            });
        }).start();
    }

    private void exportAndShareCalendar() {
        new Thread(() -> {
            List<DirectSyncEvent> events = DirectSyncCalendarManager.getSharedEvents(this);
            StringBuilder ics = new StringBuilder();
            ics.append("BEGIN:VCALENDAR\r\n");
            ics.append("VERSION:2.0\r\n");
            ics.append("PRODID:-//Etar Direct Sync//EN\r\n");

            for (DirectSyncEvent ev : events) {
                ics.append("BEGIN:VEVENT\r\n");
                ics.append("UID:").append(ev.uid).append("\r\n");
                ics.append("SUMMARY:").append(ev.title).append("\r\n");
                if (ev.description != null && !ev.description.isEmpty()) {
                    ics.append("DESCRIPTION:").append(ev.description).append("\r\n");
                }
                if (ev.location != null && !ev.location.isEmpty()) {
                    ics.append("LOCATION:").append(ev.location).append("\r\n");
                }
                ics.append("DTSTART:").append(ev.startMillis).append("\r\n");
                ics.append("DTEND:").append(ev.endMillis).append("\r\n");
                if (ev.rrule != null && !ev.rrule.isEmpty()) {
                    ics.append("RRULE:").append(ev.rrule).append("\r\n");
                }
                ics.append("END:VEVENT\r\n");
            }
            ics.append("END:VCALENDAR\r\n");

            try {
                File cachePath = new File(getCacheDir(), "shared_calendar");
                cachePath.mkdirs();
                File file = new File(cachePath, "shared_calendar.ics");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(ics.toString().getBytes(StandardCharsets.UTF_8));
                fos.close();

                Uri contentUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", file);

                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/calendar");
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                mMainHandler.post(() -> startActivity(Intent.createChooser(shareIntent, getString(R.string.direct_sync_export_share))));
            } catch (Exception e) {
                mMainHandler.post(() -> Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDiscovery.stopDiscovery();
        mDiscovery.unregisterService();
        mServer.stop();
    }
}
