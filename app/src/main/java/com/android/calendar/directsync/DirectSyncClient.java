package com.android.calendar.directsync;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Client that connects to a peer device and performs bidirectional synchronization of shared events.
 */
public class DirectSyncClient {
    private static final String TAG = "DirectSyncClient";
    private static final int TIMEOUT_MS = 10000;

    public static class SyncResult {
        public final boolean success;
        public final int syncedCount;
        public final String errorMessage;

        public SyncResult(boolean success, int syncedCount, String errorMessage) {
            this.success = success;
            this.syncedCount = syncedCount;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Executes bidirectional sync with the peer device at host:port.
     */
    public static SyncResult syncWithPeer(Context context, String host, int port) {
        if (context == null || host == null || host.trim().isEmpty()) {
            return new SyncResult(false, 0, "Invalid host address");
        }

        host = host.trim();
        // Remove http:// if the user typed it
        if (host.startsWith("http://")) {
            host = host.substring("http://".length());
        }
        if (host.startsWith("https://")) {
            host = host.substring("https://".length());
        }
        if (host.contains(":")) {
            String[] parts = host.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {}
        }

        String baseUrl = "http://" + host + ":" + port;
        int totalChanges = 0;

        try {
            // STEP 1: Fetch events from peer (GET /events)
            URL getUrl = new URL(baseUrl + "/events");
            HttpURLConnection getConn = (HttpURLConnection) getUrl.openConnection();
            getConn.setRequestMethod("GET");
            getConn.setConnectTimeout(TIMEOUT_MS);
            getConn.setReadTimeout(TIMEOUT_MS);

            int getCode = getConn.getResponseCode();
            if (getCode != 200) {
                return new SyncResult(false, 0, "Peer responded with HTTP code: " + getCode);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getConn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            getConn.disconnect();

            JSONArray peerArray = new JSONArray(sb.toString());
            List<DirectSyncEvent> peerEvents = new ArrayList<>();
            for (int i = 0; i < peerArray.length(); i++) {
                peerEvents.add(DirectSyncEvent.fromJson(peerArray.getJSONObject(i)));
            }

            // Merge peer events locally
            int localChanges = DirectSyncCalendarManager.mergeIncomingEvents(context, peerEvents);
            totalChanges += localChanges;

            // STEP 2: Send local events to peer (POST /events)
            List<DirectSyncEvent> localEvents = DirectSyncCalendarManager.getSharedEvents(context);
            JSONArray localArray = new JSONArray();
            for (DirectSyncEvent ev : localEvents) {
                localArray.put(ev.toJson());
            }

            URL postUrl = new URL(baseUrl + "/events");
            HttpURLConnection postConn = (HttpURLConnection) postUrl.openConnection();
            postConn.setRequestMethod("POST");
            postConn.setDoOutput(true);
            postConn.setConnectTimeout(TIMEOUT_MS);
            postConn.setReadTimeout(TIMEOUT_MS);
            postConn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            byte[] postBytes = localArray.toString().getBytes(StandardCharsets.UTF_8);
            postConn.setFixedLengthStreamingMode(postBytes.length);
            OutputStream out = postConn.getOutputStream();
            out.write(postBytes);
            out.flush();
            out.close();

            int postCode = postConn.getResponseCode();
            if (postCode != 200) {
                return new SyncResult(false, totalChanges, "Failed to send events to peer. Code: " + postCode);
            }

            BufferedReader postReader = new BufferedReader(
                    new InputStreamReader(postConn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder postSb = new StringBuilder();
            while ((line = postReader.readLine()) != null) {
                postSb.append(line);
            }
            postReader.close();
            postConn.disconnect();

            JSONObject postResp = new JSONObject(postSb.toString());
            int remoteChanges = postResp.optInt("changed", 0);
            totalChanges += remoteChanges;

            Log.d(TAG, "Sync complete with " + host + ". Total changes applied: " + totalChanges);
            return new SyncResult(true, totalChanges, null);

        } catch (Exception e) {
            Log.e(TAG, "Sync failed with peer " + baseUrl, e);
            return new SyncResult(false, 0, e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString());
        }
    }
}
