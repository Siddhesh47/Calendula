package com.android.calendar.directsync;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight embedded HTTP Server for direct peer-to-peer calendar synchronization.
 */
public class DirectSyncServer {
    private static final String TAG = "DirectSyncServer";
    public static final int DEFAULT_PORT = 8765;

    private final Context mContext;
    private final int mPort;
    private ServerSocket mServerSocket;
    private Thread mServerThread;
    private volatile boolean mIsRunning = false;

    public DirectSyncServer(Context context) {
        this(context, DEFAULT_PORT);
    }

    public DirectSyncServer(Context context, int port) {
        this.mContext = context.getApplicationContext();
        this.mPort = port;
    }

    public synchronized boolean start() {
        if (mIsRunning) return true;

        try {
            mServerSocket = new ServerSocket(mPort);
            mIsRunning = true;

            mServerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (mIsRunning && mServerSocket != null && !mServerSocket.isClosed()) {
                        try {
                            Socket clientSocket = mServerSocket.accept();
                            handleClientSocket(clientSocket);
                        } catch (Exception e) {
                            if (mIsRunning) {
                                Log.e(TAG, "Error accepting client connection", e);
                            }
                        }
                    }
                }
            }, "DirectSyncServerThread");
            mServerThread.start();
            Log.d(TAG, "DirectSyncServer started on port " + mPort);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start DirectSyncServer on port " + mPort, e);
            mIsRunning = false;
            return false;
        }
    }

    public synchronized void stop() {
        mIsRunning = false;
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing server socket", e);
            }
            mServerSocket = null;
        }
        if (mServerThread != null) {
            mServerThread.interrupt();
            mServerThread = null;
        }
        Log.d(TAG, "DirectSyncServer stopped");
    }

    public boolean isRunning() {
        return mIsRunning;
    }

    public int getPort() {
        return mPort;
    }

    private void handleClientSocket(final Socket socket) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream output = socket.getOutputStream();

                    // Read request line (e.g., "GET /events HTTP/1.1")
                    String requestLine = reader.readLine();
                    if (requestLine == null) {
                        socket.close();
                        return;
                    }

                    String[] parts = requestLine.split(" ");
                    if (parts.length < 2) {
                        sendHttpResponse(output, 400, "Bad Request", "text/plain", "Bad Request".getBytes(StandardCharsets.UTF_8));
                        socket.close();
                        return;
                    }

                    String method = parts[0].toUpperCase();
                    String path = parts[1];

                    // Read headers and extract Content-Length if present
                    int contentLength = 0;
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            String lenStr = line.substring("content-length:".length()).trim();
                            try {
                                contentLength = Integer.parseInt(lenStr);
                            } catch (NumberFormatException ignored) {}
                        }
                    }

                    // Route requests
                    if ("GET".equals(method) && "/status".equals(path)) {
                        JSONObject status = new JSONObject();
                        status.put("deviceName", Build.MODEL);
                        status.put("version", "1.0");
                        status.put("calendarName", DirectSyncCalendarManager.SHARED_CALENDAR_DISPLAY_NAME);
                        status.put("eventCount", DirectSyncCalendarManager.getSharedEventCount(mContext));
                        byte[] bytes = status.toString().getBytes(StandardCharsets.UTF_8);
                        sendHttpResponse(output, 200, "OK", "application/json", bytes);

                    } else if ("GET".equals(method) && "/events".equals(path)) {
                        List<DirectSyncEvent> events = DirectSyncCalendarManager.getSharedEvents(mContext);
                        JSONArray array = new JSONArray();
                        for (DirectSyncEvent ev : events) {
                            array.put(ev.toJson());
                        }
                        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
                        sendHttpResponse(output, 200, "OK", "application/json", bytes);

                    } else if ("POST".equals(method) && "/events".equals(path)) {
                        // Read body
                        char[] bodyChars = new char[contentLength];
                        int totalRead = 0;
                        while (totalRead < contentLength) {
                            int r = reader.read(bodyChars, totalRead, contentLength - totalRead);
                            if (r == -1) break;
                            totalRead += r;
                        }
                        String body = new String(bodyChars, 0, totalRead);

                        JSONArray array = new JSONArray(body);
                        List<DirectSyncEvent> incoming = new ArrayList<>();
                        for (int i = 0; i < array.length(); i++) {
                            incoming.add(DirectSyncEvent.fromJson(array.getJSONObject(i)));
                        }

                        int changed = DirectSyncCalendarManager.mergeIncomingEvents(mContext, incoming);
                        JSONObject response = new JSONObject();
                        response.put("success", true);
                        response.put("changed", changed);

                        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                        sendHttpResponse(output, 200, "OK", "application/json", bytes);

                    } else {
                        sendHttpResponse(output, 404, "Not Found", "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8));
                    }

                    output.flush();
                    socket.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error handling client request", e);
                    try {
                        socket.close();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void sendHttpResponse(OutputStream out, int statusCode, String statusText, String contentType, byte[] body) throws Exception {
        String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
    }

    /**
     * Resolves the local IP address for this device on Wi-Fi or Hotspot.
     */
    public static String getLocalIpAddress(Context context) {
        try {
            // First try WifiManager
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ipInt = wm.getConnectionInfo().getIpAddress();
                if (ipInt != 0) {
                    return Formatter.formatIpAddress(ipInt);
                }
            }

            // Fallback: enumerate network interfaces (useful for hotspot / tethering / IPv4)
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        if (sAddr != null && sAddr.indexOf(':') < 0) { // IPv4
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error obtaining local IP address", e);
        }
        return "127.0.0.1";
    }
}
