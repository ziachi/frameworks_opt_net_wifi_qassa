/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wifi;

import android.app.AppGlobals;
import android.content.pm.IPackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiScanner;
import android.os.Binder;
import android.os.Process;
import android.os.ShellCommand;

import com.android.server.wifi.util.ApConfigUtil;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Interprets and executes 'adb shell cmd wifi [args]'.
 *
 * To add new commands:
 * - onCommand: Add a case "<command>" execute. Return a 0
 *   if command executed successfully.
 * - onHelp: add a description string.
 *
 * If additional state objects are necessary add them to the
 * constructor.
 *
 * Permissions: currently root permission is required for all
 * commands. If the requirement needs to be relaxed then modify
 * the onCommand method to check for specific permissions on
 * individual commands.
 */
public class WifiShellCommand extends ShellCommand {
    private final ClientModeImpl mClientModeImpl;
    private final WifiLockManager mWifiLockManager;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    private final WifiConfigManager mWifiConfigManager;
    private final IPackageManager mPM;
    private final WifiNative mWifiNative;
    private final HostapdHal mHostapdHal;
    private final WifiCountryCode mWifiCountryCode;
    private final WifiScanner mWifiScanner;
    private volatile List<ScanResult> mScanResults;

    WifiShellCommand(WifiInjector wifiInjector) {
        mClientModeImpl = wifiInjector.getClientModeImpl();
        mWifiLockManager = wifiInjector.getWifiLockManager();
        mWifiNetworkSuggestionsManager = wifiInjector.getWifiNetworkSuggestionsManager();
        mWifiConfigManager = wifiInjector.getWifiConfigManager();
        mPM = AppGlobals.getPackageManager();
        mHostapdHal = wifiInjector.getHostapdHal();
        mWifiNative = wifiInjector.getWifiNative();
        mWifiCountryCode = wifiInjector.getWifiCountryCode();
        mWifiScanner = wifiInjector.getWifiScanner();
    }

    @Override
    public int onCommand(String cmd) {
        checkRootPermission();

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd != null ? cmd : "") {
                case "set-ipreach-disconnect": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'set-ipreach-disconnect' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    mClientModeImpl.setIpReachabilityDisconnectEnabled(enabled);
                    return 0;
                }
                case "get-ipreach-disconnect":
                    pw.println("IPREACH_DISCONNECT state is "
                            + mClientModeImpl.getIpReachabilityDisconnectEnabled());
                    return 0;
                case "set-poll-rssi-interval-msecs":
                    int newPollIntervalMsecs;
                    try {
                        newPollIntervalMsecs = Integer.parseInt(getNextArgRequired());
                    } catch (NumberFormatException e) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    if (newPollIntervalMsecs < 1) {
                        pw.println(
                                "Invalid argument to 'set-poll-rssi-interval-msecs' "
                                        + "- must be a positive integer");
                        return -1;
                    }

                    mClientModeImpl.setPollRssiIntervalMsecs(newPollIntervalMsecs);
                    return 0;
                case "get-poll-rssi-interval-msecs":
                    pw.println("ClientModeImpl.mPollRssiIntervalMsecs = "
                            + mClientModeImpl.getPollRssiIntervalMsecs());
                    return 0;
                case "force-hi-perf-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-hi-perf-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceHiPerfMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "force-low-latency-mode": {
                    boolean enabled;
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg)) {
                        enabled = true;
                    } else if ("disabled".equals(nextArg)) {
                        enabled = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-low-latency-mode' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                    if (!mWifiLockManager.forceLowLatencyMode(enabled)) {
                        pw.println("Command execution failed");
                    }
                    return 0;
                }
                case "network-suggestions-set-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean approved;
                    String nextArg = getNextArgRequired();
                    if ("yes".equals(nextArg)) {
                        approved = true;
                    } else if ("no".equals(nextArg)) {
                        approved = false;
                    } else {
                        pw.println(
                                "Invalid argument to 'network-suggestions-set-user-approved' "
                                        + "- must be 'yes' or 'no'");
                        return -1;
                    }
                    mWifiNetworkSuggestionsManager.setHasUserApprovedForApp(approved, packageName);
                    return 0;
                }
                case "network-suggestions-has-user-approved": {
                    String packageName = getNextArgRequired();
                    boolean hasUserApproved =
                            mWifiNetworkSuggestionsManager.hasUserApprovedForApp(packageName);
                    pw.println(hasUserApproved ? "yes" : "no");
                    return 0;
                }
                case "network-requests-remove-user-approved-access-points": {
                    String packageName = getNextArgRequired();
                    mClientModeImpl.removeNetworkRequestUserApprovedAccessPointsForApp(packageName);
                    return 0;
                }
                case "clear-deleted-ephemeral-networks": {
                    mWifiConfigManager.clearDeletedEphemeralNetworks();
                    return 0;
                }
                case "send-link-probe": {
                    return sendLinkProbe(pw);
                }
                case "force-softap-channel": {
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg))  {
                        int apChannelMHz;
                        try {
                            apChannelMHz = Integer.parseInt(getNextArgRequired());
                        } catch (NumberFormatException e) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a positive integer");
                            return -1;
                        }
                        int apChannel = ApConfigUtil.convertFrequencyToChannel(apChannelMHz);
                        if (apChannel == -1 || !isApChannelMHzValid(apChannelMHz)) {
                            pw.println("Invalid argument to 'force-softap-channel enabled' "
                                    + "- must be a valid WLAN channel");
                            return -1;
                        }
                        mHostapdHal.enableForceSoftApChannel(apChannel);
                        return 0;
                    } else if ("disabled".equals(nextArg)) {
                        mHostapdHal.disableForceSoftApChannel();
                        return 0;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-softap-channel' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                }
                case "force-country-code": {
                    String nextArg = getNextArgRequired();
                    if ("enabled".equals(nextArg))  {
                        String countryCode = getNextArgRequired();
                        if (!(countryCode.length() == 2
                                && countryCode.chars().allMatch(Character::isLetter))) {
                            pw.println("Invalid argument to 'force-country-code enabled' "
                                    + "- must be a two-letter string");
                            return -1;
                        }
                        mWifiCountryCode.enableForceCountryCode(countryCode);
                        return 0;
                    } else if ("disabled".equals(nextArg)) {
                        mWifiCountryCode.disableForceCountryCode();
                        return 0;
                    } else {
                        pw.println(
                                "Invalid argument to 'force-country-code' - must be 'enabled'"
                                        + " or 'disabled'");
                        return -1;
                    }
                }
                case "get-country-code": {
                    pw.println("Wifi Country Code = "
                            + mWifiCountryCode.getCountryCode());
                    return 0;
                }
                case "connect-network": {
                    return connectNetwork();
                }
                case "scan": {
                    return scan();
                }
                case "list-scan-results": {
                    return listScanResults();
                }
                case "disconnect": {
                    return disconnect();
                }
                case "status": {
                    return status();
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception while executing WifiShellCommand: ");
            e.printStackTrace(pw);
        }
        return -1;
    }

    /**
     * Backport of the Android 11 {@code cmd wifi connect-network} command.
     *
     * The framework still owns the connection: this only saves the network and
     * asks ClientModeImpl to connect, so DHCP, DNS and the network agent stay
     * with the framework.
     */
    private int connectNetwork() {
        final PrintWriter pw = getOutPrintWriter();
        String ssid = getNextArgRequired();
        String type = getNextArgRequired();
        String passphrase = null;
        if ("wpa2".equals(type) || "wpa3".equals(type)) {
            passphrase = getNextArgRequired();
        } else if (!"open".equals(type)) {
            pw.println("Invalid network type: " + type + " (expected open, wpa2 or wpa3)");
            return -1;
        }

        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + ssid + "\"";
        if (passphrase == null) {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        } else {
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            config.preSharedKey = "\"" + passphrase + "\"";
        }

        NetworkUpdateResult result =
                mWifiConfigManager.addOrUpdateNetwork(config, Process.SHELL_UID);
        if (!result.isSuccess()) {
            pw.println("Failed to add/update network " + ssid);
            return -1;
        }
        int networkId = result.getNetworkId();
        if (!mWifiConfigManager.enableNetwork(networkId, true, Process.SHELL_UID)) {
            pw.println("Failed to enable network " + ssid);
            return -1;
        }
        mClientModeImpl.startConnectToNetwork(networkId, Process.SHELL_UID, null);
        pw.println("Connecting to " + ssid + " (networkId=" + networkId + ")");
        return 0;
    }

    private int sendLinkProbe(PrintWriter pw) throws InterruptedException {
        ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);
        mClientModeImpl.probeLink(new WifiNative.SendMgmtFrameCallback() {
            @Override
            public void onAck(int elapsedTimeMs) {
                queue.offer("Link probe succeeded after " + elapsedTimeMs + " ms");
            }

            @Override
            public void onFailure(int reason) {
                queue.offer("Link probe failed with reason " + reason);
            }
        }, -1);

        // block until msg is received, or timed out
        String msg = queue.poll(WificondControl.SEND_MGMT_FRAME_TIMEOUT_MS + 1000,
                TimeUnit.MILLISECONDS);
        if (msg == null) {
            pw.println("Link probe timed out");
        } else {
            pw.println(msg);
        }
        return 0;
    }

    private boolean isApChannelMHzValid(int apChannelMHz) {
        int[] allowed2gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_24_GHZ);
        int[] allowed5gFreq = mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ);
        int[] allowed5gDfsFreq =
            mWifiNative.getChannelsForBand(WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY);
        if (allowed2gFreq == null) {
            allowed2gFreq = new int[0];
        }
        if (allowed5gFreq == null) {
            allowed5gFreq = new int[0];
        }
        if (allowed5gDfsFreq == null) {
            allowed5gDfsFreq = new int[0];
        }
        return (Arrays.binarySearch(allowed2gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gFreq, apChannelMHz) >= 0
                || Arrays.binarySearch(allowed5gDfsFreq, apChannelMHz) >= 0);
    }

    private void checkRootPermission() {
        final int uid = Binder.getCallingUid();
        if (uid == 0) {
            // Root can do anything.
            return;
        }
        throw new SecurityException("Uid " + uid + " does not have access to wifi commands");
    }

    private int scan() {
        final CountDownLatch latch = new CountDownLatch(1);
        final WifiScanner.ScanListener listener = new WifiScanner.ScanListener() {
            @Override
            public void onSuccess() {
            }

            @Override
            public void onFailure(int reason, String description) {
                latch.countDown();
            }

            @Override
            public void onResults(WifiScanner.ScanData[] results) {
                if (results != null && results.length > 0) {
                    mScanResults = Arrays.asList(results[0].getResults());
                }
                latch.countDown();
            }

            @Override
            public void onFullResult(ScanResult fullScanResult) {
            }

            @Override
            public void onPeriodChanged(int periodInMs) {
            }
        };
        final WifiScanner.ScanSettings settings = new WifiScanner.ScanSettings();
        settings.band = WifiScanner.WIFI_BAND_BOTH_WITH_DFS;
        mWifiScanner.startScan(settings, listener);
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return listScanResults();
    }

    private int listScanResults() {
        final PrintWriter pw = getOutPrintWriter();
        final List<ScanResult> results = mScanResults;
        if (results == null || results.isEmpty()) {
            pw.println("no networks found");
            return 0;
        }
        final List<ScanResult> sorted = new ArrayList<>(results);
        Collections.sort(sorted, new Comparator<ScanResult>() {
            @Override
            public int compare(ScanResult left, ScanResult right) {
                return right.level - left.level;
            }
        });
        for (ScanResult result : sorted) {
            final String ssid = result.SSID == null || result.SSID.isEmpty()
                    ? "<hidden>" : result.SSID;
            pw.println(String.format("%-24s %4d dBm  %s",
                    ssid, result.level, securityOf(result)));
        }
        return 0;
    }

    private static String securityOf(ScanResult result) {
        final String caps = result.capabilities;
        if (caps == null) {
            return "open";
        }
        if (caps.contains("WPA3")) {
            return "WPA3";
        }
        if (caps.contains("WPA2")) {
            return "WPA2";
        }
        if (caps.contains("WPA")) {
            return "WPA";
        }
        if (caps.contains("WEP")) {
            return "WEP";
        }
        return "open";
    }

    private int disconnect() {
        mClientModeImpl.disconnectCommand();
        getOutPrintWriter().println("disconnected");
        return 0;
    }

    private int status() {
        final PrintWriter pw = getOutPrintWriter();
        final WifiInfo info = mClientModeImpl.getWifiInfo();
        if (info == null || info.getNetworkId() == WifiConfiguration.INVALID_NETWORK_ID) {
            pw.println("not connected");
            return 0;
        }
        String ssid = info.getSSID();
        if (ssid != null && ssid.length() > 1
                && ssid.charAt(0) == '"' && ssid.charAt(ssid.length() - 1) == '"') {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        pw.println("ssid  " + ssid);
        pw.println("rssi  " + info.getRssi() + " dBm");
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();

        pw.println("Wi-Fi (wifi) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  connect-network <ssid> open|wpa2|wpa3 [passphrase]");
        pw.println("    Connect to the specified network.");
        pw.println("  scan");
        pw.println("    Scan for nearby networks and print them.");
        pw.println("  list-scan-results");
        pw.println("    Print the results of the last scan.");
        pw.println("  disconnect");
        pw.println("    Disconnect from the current network.");
        pw.println("  status");
        pw.println("    Print the current ssid and signal.");
        pw.println("  set-ipreach-disconnect enabled|disabled");
        pw.println("    Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects.");
        pw.println("  get-ipreach-disconnect");
        pw.println("    Gets setting of CMD_IP_REACHABILITY_LOST events triggering disconnects.");
        pw.println("  set-poll-rssi-interval-msecs <int>");
        pw.println("    Sets the interval between RSSI polls to <int> milliseconds.");
        pw.println("  get-poll-rssi-interval-msecs");
        pw.println("    Gets current interval between RSSI polls, in milliseconds.");
        pw.println("  force-hi-perf-mode enabled|disabled");
        pw.println("    Sets whether hi-perf mode is forced or left for normal operation.");
        pw.println("  force-low-latency-mode enabled|disabled");
        pw.println("    Sets whether low latency mode is forced or left for normal operation.");
        pw.println("  network-suggestions-set-user-approved <package name> yes|no");
        pw.println("    Sets whether network suggestions from the app is approved or not.");
        pw.println("  network-suggestions-has-user-approved <package name>");
        pw.println("    Queries whether network suggestions from the app is approved or not.");
        pw.println("  network-requests-remove-user-approved-access-points <package name>");
        pw.println("    Removes all user approved network requests for the app.");
        pw.println("  clear-deleted-ephemeral-networks");
        pw.println("    Clears the deleted ephemeral networks list.");
        pw.println("  send-link-probe");
        pw.println("    Manually triggers a link probe.");
        pw.println("  force-softap-channel enabled <int> | disabled");
        pw.println("    Sets whether soft AP channel is forced to <int> MHz");
        pw.println("    or left for normal   operation.");
        pw.println("  force-country-code enabled <two-letter code> | disabled ");
        pw.println("    Sets country code to <two-letter code> or left for normal value");
        pw.println("  get-country-code");
        pw.println("    Gets country code as a two-letter string");
        pw.println();
    }
}
