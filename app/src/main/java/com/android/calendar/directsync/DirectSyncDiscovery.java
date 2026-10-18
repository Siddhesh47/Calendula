package com.android.calendar.directsync;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;

/**
 * Manages local network service discovery (mDNS / DNS-SD via Android NsdManager)
 * for automatic peer discovery on the same Wi-Fi / hotspot.
 */
public class DirectSyncDiscovery {
    private static final String TAG = "DirectSyncDiscovery";
    public static final String SERVICE_TYPE = "_etarsync._tcp.";
    public static final String SERVICE_NAME_PREFIX = "Etar-";

    public interface OnDeviceDiscoveredListener {
        void onDeviceFound(String name, String host, int port);
        void onDeviceLost(String name);
    }

    private final NsdManager mNsdManager;
    private NsdManager.RegistrationListener mRegistrationListener;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private boolean mIsRegistered = false;
    private boolean mIsDiscovering = false;

    public DirectSyncDiscovery(Context context) {
        mNsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }

    /**
     * Registers this device's sync service on the local network.
     */
    public synchronized void registerService(int port) {
        if (mIsRegistered || mNsdManager == null) return;

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(SERVICE_NAME_PREFIX + Build.MODEL);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        mRegistrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                mIsRegistered = true;
                Log.d(TAG, "NSD Service registered: " + nsdServiceInfo.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                mIsRegistered = false;
                Log.e(TAG, "NSD Registration failed with error: " + errorCode);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                mIsRegistered = false;
                Log.d(TAG, "NSD Service unregistered");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "NSD Unregistration failed with error: " + errorCode);
            }
        };

        try {
            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrationListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register NSD service", e);
        }
    }

    public synchronized void unregisterService() {
        if (!mIsRegistered || mNsdManager == null || mRegistrationListener == null) return;
        try {
            mNsdManager.unregisterService(mRegistrationListener);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering NSD service", e);
        }
        mIsRegistered = false;
    }

    /**
     * Starts discovering other peer devices on the same Wi-Fi network.
     */
    public synchronized void startDiscovery(final OnDeviceDiscoveredListener listener) {
        if (mIsDiscovering || mNsdManager == null) return;

        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed to start: " + errorCode);
                mIsDiscovering = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed to stop: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                mIsDiscovering = true;
                Log.d(TAG, "Service discovery started for " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                mIsDiscovering = false;
                Log.d(TAG, "Service discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.d(TAG, "Service found: " + service.getServiceName());
                if (!service.getServiceType().contains("etarsync")) {
                    return;
                }

                // Resolve service host and port
                try {
                    mNsdManager.resolveService(service, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                            Log.e(TAG, "Resolve failed for " + serviceInfo.getServiceName() + ": " + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            InetAddress host = serviceInfo.getHost();
                            if (host != null && listener != null) {
                                String hostAddress = host.getHostAddress();
                                int port = serviceInfo.getPort();
                                listener.onDeviceFound(serviceInfo.getServiceName(), hostAddress, port);
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error initiating resolve for service " + service.getServiceName(), e);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.d(TAG, "Service lost: " + service.getServiceName());
                if (listener != null) {
                    listener.onDeviceLost(service.getServiceName());
                }
            }
        };

        try {
            mNsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service discovery", e);
        }
    }

    public synchronized void stopDiscovery() {
        if (!mIsDiscovering || mNsdManager == null || mDiscoveryListener == null) return;
        try {
            mNsdManager.stopServiceDiscovery(mDiscoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping discovery", e);
        }
        mIsDiscovering = false;
    }
}
