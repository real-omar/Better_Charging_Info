package com.mrx7014.s25ultraspoofer;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that backports VOOC fast-charging support to GSI / AOSP ROMs.
 *
 * What the original patch does (summarised):
 *  1. BatteryService reads /sys/class/power_supply/battery/voocchg_ing and
 *     injects "vooc_charger" = true into ACTION_BATTERY_CHANGED when the node
 *     reads "1".
 *  2. BatteryStatus.getChargingSpeed() returns CHARGING_VOOC (3) when
 *     voocChargeStatus is true.
 *  3. KeyguardIndicationController shows "VOOC Charging" text on the lock
 *     screen for CHARGING_VOOC.
 *
 * All three behaviours are replicated here via hooks, so no framework source
 * changes or custom ROM are required.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VoocChargerHook";

    // Path to the kernel sysfs node that signals VOOC charging
    private static final String VOOC_NODE = "/sys/class/power_supply/battery/voocchg_ing";

    // Extra key added by the patch to ACTION_BATTERY_CHANGED
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";

    // BatteryStatus charging speed constant added by the patch
    private static final int CHARGING_VOOC = 3;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case "android":
                // BatteryService lives in the system_server process whose
                // package name is reported as "android".
                hookBatteryService(lpparam.classLoader);
                hookBatteryStatus(lpparam.classLoader);
                break;

            case "com.android.systemui":
                hookKeyguardIndicationController(lpparam.classLoader);
                hookKeyguardUpdateMonitor(lpparam.classLoader);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // 1. BatteryService — inject vooc_charger into ACTION_BATTERY_CHANGED
    // -----------------------------------------------------------------------

    /**
     * Hook BatteryService.sendBatteryChangedIntentLocked() (or the equivalent
     * method that builds / broadcasts ACTION_BATTERY_CHANGED).
     *
     * The exact method name varies slightly between Android versions:
     *   - Android R/S: sendBatteryChangedIntentLocked
     *   - Some builds:  broadcastBatteryStatsLocked / sendIntentLocked
     *
     * We hook the method that calls intent.putExtra(...) for battery fields and
     * inject our own extra right after.
     */
    private void hookBatteryService(ClassLoader cl) {
        try {
            Class<?> batteryServiceClass =
                    XposedHelpers.findClass("com.android.server.BatteryService", cl);

            // Hook the method that stuffs extras into the battery intent.
            // It returns void and takes no arguments in most AOSP builds.
            XposedHelpers.findAndHookMethod(
                    batteryServiceClass,
                    "sendBatteryChangedIntentLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // After the original method has put all standard
                            // extras into the sticky broadcast, inject ours.
                            injectVoocExtra(param.thisObject);
                        }
                    });

            XposedBridge.log(TAG + ": hooked BatteryService.sendBatteryChangedIntentLocked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook BatteryService: " + t.getMessage());
        }
    }

    /**
     * Reads the sysfs node, then reaches into the BatteryService instance to
     * find the pending Intent and injects the vooc_charger extra.
     *
     * The intent is stored in the field mBatteryChangedIntent (common AOSP name).
     */
    private void injectVoocExtra(Object batteryServiceInstance) {
        boolean isVooc = isVoocCharger();
        try {
            // Try the common field name first; fall back to scanning fields.
            Intent intent = (Intent) XposedHelpers.getObjectField(
                    batteryServiceInstance, "mBatteryChangedIntent");
            if (intent != null) {
                intent.putExtra(EXTRA_VOOC_CHARGER, isVooc);
            }
        } catch (Throwable t) {
            // Field not found under that name — scan for Intent fields.
            for (Field f : batteryServiceInstance.getClass().getDeclaredFields()) {
                if (f.getType() == Intent.class) {
                    try {
                        f.setAccessible(true);
                        Intent candidate = (Intent) f.get(batteryServiceInstance);
                        if (candidate != null &&
                                Intent.ACTION_BATTERY_CHANGED.equals(candidate.getAction())) {
                            candidate.putExtra(EXTRA_VOOC_CHARGER, isVooc);
                            break;
                        }
                    } catch (IllegalAccessException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Reads /sys/class/power_supply/battery/voocchg_ing.
     * Returns true only when the node exists and contains "1".
     */
    private boolean isVoocCharger() {
        try (BufferedReader br = new BufferedReader(new FileReader(VOOC_NODE))) {
            String line = br.readLine();
            return "1".equals(line != null ? line.trim() : null);
        } catch (FileNotFoundException e) {
            // Node does not exist on this device — silently ignore.
        } catch (IOException e) {
            Log.w(TAG, "isVoocCharger IOException: " + e.getMessage());
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // 2. BatteryStatus.getChargingSpeed() — return CHARGING_VOOC when needed
    // -----------------------------------------------------------------------

    /**
     * The patch makes getChargingSpeed() return CHARGING_VOOC (3) when
     * voocChargeStatus is true, before the normal wattage-based logic.
     *
     * We hook the constructor that takes an Intent so that voocChargeStatus is
     * populated, and then hook getChargingSpeed() to honour it.
     */
    private void hookBatteryStatus(ClassLoader cl) {
        try {
            Class<?> batteryStatusClass = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus", cl);

            // --- 2a. Hook Intent constructor to capture vooc_charger extra ---
            XposedHelpers.findAndHookConstructor(
                    batteryStatusClass,
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Intent intent = (Intent) param.args[0];
                            if (intent == null) return;
                            boolean vooc = intent.getBooleanExtra(EXTRA_VOOC_CHARGER, false);
                            try {
                                // Try to set the field if the ROM already has it.
                                XposedHelpers.setObjectField(param.thisObject,
                                        "voocChargeStatus", vooc);
                            } catch (Throwable ignored) {
                                // Field doesn't exist in this ROM — store in
                                // the additional fields map instead.
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject, "voocChargeStatus", vooc);
                            }
                        }
                    });

            // --- 2b. Hook getChargingSpeed() to return CHARGING_VOOC ---
            XposedHelpers.findAndHookMethod(
                    batteryStatusClass,
                    "getChargingSpeed",
                    Resources.class,          // method signature: getChargingSpeed(Resources)
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            boolean vooc = getVoocStatus(param.thisObject);
                            if (vooc) {
                                param.setResult(CHARGING_VOOC);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": hooked BatteryStatus");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook BatteryStatus: " + t.getMessage());
        }
    }

    /** Retrieves voocChargeStatus from either the real field or the extra-fields map. */
    private boolean getVoocStatus(Object batteryStatusInstance) {
        try {
            Object val = XposedHelpers.getObjectField(batteryStatusInstance, "voocChargeStatus");
            if (val instanceof Boolean) return (Boolean) val;
        } catch (Throwable ignored) {
        }
        Object extra = XposedHelpers.getAdditionalInstanceField(
                batteryStatusInstance, "voocChargeStatus");
        return Boolean.TRUE.equals(extra);
    }

    // -----------------------------------------------------------------------
    // 3. KeyguardIndicationController — show "VOOC Charging" text
    // -----------------------------------------------------------------------

    /**
     * Hooks the switch/if-else inside KeyguardIndicationController that selects
     * the charging string resource ID, adding a branch for CHARGING_VOOC.
     *
     * The method of interest is updateBatteryIndication() or
     * computePowerIndication() depending on the Android version.
     */
    private void hookKeyguardIndicationController(ClassLoader cl) {
        try {
            Class<?> kicClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController", cl);

            // The method name changed across versions; try both.
            String methodName = findChargingMethod(kicClass);
            if (methodName == null) {
                XposedBridge.log(TAG + ": KeyguardIndicationController charging method not found");
                return;
            }

            XposedHelpers.findAndHookMethod(
                    kicClass,
                    methodName,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // If the result already mentions "VOOC" we're done.
                            Object result = param.getResult();
                            if (result instanceof CharSequence &&
                                    result.toString().contains("VOOC")) {
                                return;
                            }
                            overrideWithVoocString(param);
                        }
                    });

            XposedBridge.log(TAG + ": hooked KeyguardIndicationController." + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": failed to hook KIC: " + t.getMessage());
        }
    }

    /**
     * Scans for the method that returns the charging indication string.
     * Returns the first matching method name, or null if none found.
     */
    private String findChargingMethod(Class<?> kicClass) {
        String[] candidates = {
                "computePowerIndication",
                "updateBatteryIndication",
                "getChargingMessage",
        };
        for (String name : candidates) {
            try {
                kicClass.getDeclaredMethod(name);
                return name;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /**
     * Checks if the current battery status is VOOC and, if so, replaces the
     * return value of the indication method with a "VOOC Charging" string.
     *
     * We reach the BatteryStatus via the KIC's mBatteryStatus field (AOSP name).
     */
    private void overrideWithVoocString(XC_MethodHook.MethodHookParam param) {
        try {
            Object batteryStatus = XposedHelpers.getObjectField(
                    param.thisObject, "mBatteryStatus");
            if (batteryStatus == null) return;

            boolean vooc = getVoocStatus(batteryStatus);
            if (!vooc) return;

            // Retrieve percentage so we can embed it in the string, matching
            // the format: "%s • VOOC Charging"
            int level = 0;
            try {
                level = (int) XposedHelpers.getIntField(batteryStatus, "level");
            } catch (Throwable ignored) {
            }

            String indication = level + "% • VOOC Charging";
            param.setResult(indication);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": overrideWithVoocString error: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // 4. KeyguardUpdateMonitor — trigger refresh when voocChargeStatus changes
    // -----------------------------------------------------------------------

    /**
     * The patch adds a check so that a change in VOOC status while plugged in
     * triggers a keyguard indication update.
     *
     * We hook shouldTriggerBatteryUpdate() to return true when voocChargeStatus
     * differs between current and old BatteryStatus.
     */
    private void hookKeyguardUpdateMonitor(ClassLoader cl) {
        try {
            Class<?> kumClass = XposedHelpers.findClass(
                    "com.android.keyguard.KeyguardUpdateMonitor", cl);

            XposedHelpers.findAndHookMethod(
                    kumClass,
                    "shouldTriggerBatteryUpdate",
                    // Method signature: shouldTriggerBatteryUpdate(BatteryStatus, BatteryStatus)
                    XposedHelpers.findClass(
                            "com.android.settingslib.fuelgauge.BatteryStatus", cl),
                    XposedHelpers.findClass(
                            "com.android.settingslib.fuelgauge.BatteryStatus", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // Only override if the original returned false.
                            if (Boolean.TRUE.equals(param.getResult())) return;

                            Object current = param.args[0];
                            Object old     = param.args[1];
                            if (current == null || old == null) return;

                            boolean currentVooc = getVoocStatus(current);
                            boolean oldVooc     = getVoocStatus(old);

                            if (currentVooc != oldVooc) {
                                param.setResult(true);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": hooked KeyguardUpdateMonitor.shouldTriggerBatteryUpdate");
        } catch (Throwable t) {
            // Method signature may differ — not fatal, rest of module still works.
            XposedBridge.log(TAG + ": failed to hook KUM (non-fatal): " + t.getMessage());
        }
    }
}
