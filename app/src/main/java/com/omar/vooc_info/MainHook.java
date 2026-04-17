package com.omar.vooc_info;

import android.content.Context;
import android.content.Intent;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.text.NumberFormat;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that backports VOOC fast-charging support to GSI / AOSP ROMs.
 *
 * Simplified pipeline (learned from three iterations of logs):
 *
 *  1. BatteryService — inject "vooc_charger" extra into ACTION_BATTERY_CHANGED.
 *     The method name is resolved from a priority list; "sendBatteryLevelChangedIntentLocked"
 *     is known-correct for this ROM from previous log analysis.
 *
 *  2. KeyguardIndicationController.computePowerIndication() — before hook.
 *     Reads the sysfs node directly; when VOOC is active and the charger is wired,
 *     builds and returns the "VOOC Charging" indication string without touching
 *     BatteryStatus at all (its constructor signature differs on this ROM).
 *
 *  BatteryStatus and KeyguardUpdateMonitor hooks are intentionally omitted:
 *  the BatteryStatus(Intent) constructor does not exist on this GSI, and KUM's
 *  shouldTriggerBatteryUpdate also depends on it. The KIC before-hook already
 *  covers the only user-visible behaviour we need.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VoocChargerHook";

    /** Kernel sysfs node written by the VOOC charger driver. */
    private static final String VOOC_NODE = "/sys/class/power_supply/battery/voocchg_ing";

    /** Extra key injected into ACTION_BATTERY_CHANGED (kept for completeness). */
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case "android":
                hookBatteryService(lpparam.classLoader);
                break;
            case "com.android.systemui":
                hookKeyguardIndicationController(lpparam.classLoader);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // 1. BatteryService — inject vooc_charger into ACTION_BATTERY_CHANGED
    // -----------------------------------------------------------------------

    private void hookBatteryService(ClassLoader cl) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass("com.android.server.BatteryService", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BatteryService class not found: " + t.getMessage());
            return;
        }

        // Priority order — first match wins.
        // "sendBatteryLevelChangedIntentLocked" confirmed present on this ROM via log scan.
        String[] candidates = {
            "sendBatteryLevelChangedIntentLocked", // confirmed on this ROM
            "sendBatteryChangedIntentLocked",       // AOSP R/S mainline name
            "processValuesLocked",                  // some GSIs
        };
        XC_MethodHook cb = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                injectVoocExtra(param.thisObject);
            }
        };
        for (String name : candidates) {
            try {
                XposedHelpers.findAndHookMethod(cls, name, cb);
                XposedBridge.log(TAG + ": hooked BatteryService." + name);
                return;
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + ": BatteryService — no candidate method matched");
    }

    /**
     * Reads the sysfs node and injects vooc_charger into the pending sticky
     * intent held in the BatteryService instance field mBatteryChangedIntent.
     */
    private void injectVoocExtra(Object svc) {
        boolean vooc = isVoocCharger();
        // Try the well-known AOSP field name first.
        try {
            Intent intent = (Intent) XposedHelpers.getObjectField(svc, "mBatteryChangedIntent");
            if (intent != null) {
                intent.putExtra(EXTRA_VOOC_CHARGER, vooc);
                return;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: scan all Intent fields for the ACTION_BATTERY_CHANGED one.
        for (Field f : svc.getClass().getDeclaredFields()) {
            if (!Intent.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Intent candidate = (Intent) f.get(svc);
                if (candidate != null &&
                        Intent.ACTION_BATTERY_CHANGED.equals(candidate.getAction())) {
                    candidate.putExtra(EXTRA_VOOC_CHARGER, vooc);
                    return;
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    // -----------------------------------------------------------------------
    // 2. KeyguardIndicationController.computePowerIndication()
    //
    // Smali analysis of the actual method on this ROM:
    //   • mBatteryDefender        → early return
    //   • mPowerPluggedIn && mIncompatibleCharger → early return
    //   • mPowerCharged           → early return
    //   • if mPowerPluggedInWired:
    //       switch mChargingSpeed: 0=slow, 2=fast, else=regular
    //   • elif mPowerPluggedInWireless / mPowerPluggedInDock / else
    //   • format pct + optional time-remaining
    //
    // We hook BEFORE the method. When VOOC is active and charger is wired we
    // read the sysfs node directly (bypassing BatteryStatus entirely) and
    // return the VOOC Charging string, short-circuiting the original.
    // -----------------------------------------------------------------------

    private void hookKeyguardIndicationController(ClassLoader cl) {
        try {
            Class<?> kicClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController", cl);

            XposedHelpers.findAndHookMethod(kicClass, "computePowerIndication",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            buildVoocIndication(param);
                        }
                    });

            XposedBridge.log(TAG + ": hooked KIC.computePowerIndication");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": KIC hook failed: " + t.getMessage());
        }
    }

    /**
     * Called before computePowerIndication(). Mirrors the method's guard
     * sequence, then short-circuits with a VOOC string when appropriate.
     * Reads isVoocCharger() directly — no dependency on BatteryStatus.
     */
    private void buildVoocIndication(XC_MethodHook.MethodHookParam param) {
        Object kic = param.thisObject;
        try {
            // Respect the same early-return guards as the original method.
            if (XposedHelpers.getBooleanField(kic, "mBatteryDefender")) return;
            if (XposedHelpers.getBooleanField(kic, "mPowerPluggedIn") &&
                    XposedHelpers.getBooleanField(kic, "mIncompatibleCharger")) return;
            if (XposedHelpers.getBooleanField(kic, "mPowerCharged")) return;

            // Only apply to wired charging.
            if (!XposedHelpers.getBooleanField(kic, "mPowerPluggedInWired")) return;

            // Read the sysfs node directly — this is the ground truth.
            if (!isVoocCharger()) return;

            Context ctx = (Context) XposedHelpers.getObjectField(kic, "mContext");
            long timeRemaining = XposedHelpers.getLongField(kic, "mChargingTimeRemaining");
            int level = XposedHelpers.getIntField(kic, "mBatteryLevel");
            String pct = NumberFormat.getPercentInstance().format(level / 100.0);

            String result;
            if (timeRemaining > 0) {
                String timeStr = formatElapsedTime(ctx, timeRemaining);
                result = pct + " \u2022 VOOC Charging (" + timeStr + " until full)";
            } else {
                result = pct + " \u2022 VOOC Charging";
            }

            param.setResult(result);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildVoocIndication error: " + t.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns true iff the VOOC sysfs node exists and reads "1". */
    private boolean isVoocCharger() {
        try (BufferedReader br = new BufferedReader(new FileReader(VOOC_NODE))) {
            String line = br.readLine();
            return "1".equals(line != null ? line.trim() : null);
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            Log.w(TAG, "isVoocCharger: " + e.getMessage());
        }
        return false;
    }

    /**
     * Calls Formatter.formatShortElapsedTimeRoundingUpToMinutes via reflection
     * (@hide API). Falls back to a plain "Xh Ym" string if unavailable.
     */
    private String formatElapsedTime(Context ctx, long millis) {
        try {
            Class<?> cls = Class.forName("android.text.format.Formatter");
            java.lang.reflect.Method m = cls.getMethod(
                    "formatShortElapsedTimeRoundingUpToMinutes",
                    Context.class, long.class);
            return (String) m.invoke(null, ctx, millis);
        } catch (Throwable ignored) {
        }
        long totalMinutes = (millis + 59_999) / 60_000;
        long hours   = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
