package com.omar.vooc_info;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

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
 * Pipeline:
 *  1. BatteryService.sendBatteryChangedIntentLocked() — after hook reads
 *     /sys/class/power_supply/battery/voocchg_ing and injects
 *     "vooc_charger" = true into the sticky ACTION_BATTERY_CHANGED intent.
 *
 *  2. BatteryStatus(Intent) constructor — after hook reads vooc_charger from
 *     the intent and stores it. BatteryStatus.getChargingSpeed() — before hook
 *     returns CHARGING_VOOC (3) when the flag is set.
 *
 *  3. KeyguardIndicationController.computePowerIndication() — before hook:
 *     when mChargingSpeed == 3 (VOOC) AND mPowerPluggedInWired is true, the
 *     method builds and returns the "VOOC Charging" string itself, short-
 *     circuiting the original logic entirely.
 *     This exactly mirrors the extra case added by the patch.
 *
 *  4. KeyguardUpdateMonitor.shouldTriggerBatteryUpdate() — after hook forces
 *     true when voocChargeStatus changes between old and new BatteryStatus.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VoocChargerHook";

    /** Kernel sysfs node written by the VOOC charger driver. */
    private static final String VOOC_NODE = "/sys/class/power_supply/battery/voocchg_ing";

    /** Extra key injected into ACTION_BATTERY_CHANGED. */
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";

    /**
     * Charging speed constant for VOOC — value 3, sitting above CHARGING_FAST (2).
     * Used both in BatteryStatus.getChargingSpeed() and as the sentinel we write
     * into KIC's mChargingSpeed field.
     */
    private static final int CHARGING_VOOC = 3;

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case "android":
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

    private void hookBatteryService(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.android.server.BatteryService", cl);
            XposedHelpers.findAndHookMethod(cls, "sendBatteryChangedIntentLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            injectVoocExtra(param.thisObject);
                        }
                    });
            XposedBridge.log(TAG + ": hooked BatteryService.sendBatteryChangedIntentLocked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BatteryService hook failed: " + t.getMessage());
        }
    }

    /**
     * After sendBatteryChangedIntentLocked() runs, reads the sysfs node and
     * injects the extra into the pending sticky intent (mBatteryChangedIntent).
     */
    private void injectVoocExtra(Object svc) {
        boolean vooc = isVoocCharger();
        try {
            Intent intent = (Intent) XposedHelpers.getObjectField(svc, "mBatteryChangedIntent");
            if (intent != null) {
                intent.putExtra(EXTRA_VOOC_CHARGER, vooc);
                return;
            }
        } catch (Throwable ignored) {
        }
        // Fallback: scan all Intent fields for one with ACTION_BATTERY_CHANGED.
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

    // -----------------------------------------------------------------------
    // 2. BatteryStatus — track voocChargeStatus, override getChargingSpeed()
    // -----------------------------------------------------------------------

    private void hookBatteryStatus(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus", cl);

            // 2a. Read vooc_charger from intent in the Intent constructor.
            XposedHelpers.findAndHookConstructor(cls, Intent.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if (intent == null) return;
                    boolean vooc = intent.getBooleanExtra(EXTRA_VOOC_CHARGER, false);
                    storeVoocStatus(param.thisObject, vooc);
                }
            });

            // 2b. Short-circuit getChargingSpeed() for VOOC.
            XposedHelpers.findAndHookMethod(cls, "getChargingSpeed", Resources.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (loadVoocStatus(param.thisObject)) {
                                param.setResult(CHARGING_VOOC);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": hooked BatteryStatus");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": BatteryStatus hook failed: " + t.getMessage());
        }
    }

    private void storeVoocStatus(Object obj, boolean vooc) {
        try {
            XposedHelpers.setBooleanField(obj, "voocChargeStatus", vooc);
        } catch (Throwable ignored) {
            XposedHelpers.setAdditionalInstanceField(obj, "voocChargeStatus", vooc);
        }
    }

    private boolean loadVoocStatus(Object obj) {
        try {
            return XposedHelpers.getBooleanField(obj, "voocChargeStatus");
        } catch (Throwable ignored) {
        }
        Object v = XposedHelpers.getAdditionalInstanceField(obj, "voocChargeStatus");
        return Boolean.TRUE.equals(v);
    }

    // -----------------------------------------------------------------------
    // 3. KeyguardIndicationController.computePowerIndication()
    //
    // Smali analysis of the actual method on this ROM:
    //
    //   • mBatteryDefender        → early return (battery defender string)
    //   • mPowerPluggedIn && mIncompatibleCharger → early return (incompatible)
    //   • mPowerCharged           → early return (charged string)
    //   • hasChargingTime = mChargingTimeRemaining > 0
    //   • if mPowerPluggedInWired:
    //       switch mChargingSpeed:
    //         0 (slow)  → slowly strings
    //         2 (fast)  → fast strings        ← patch inserts case 3 (VOOC) here
    //         else      → regular strings
    //   • elif mPowerPluggedInWireless → wireless strings
    //   • elif mPowerPluggedInDock     → dock strings
    //   • else                         → regular strings
    //   • format with percent + optional time-remaining
    //
    // We hook BEFORE the method runs. When mChargingSpeed == 3 and
    // mPowerPluggedInWired is true, we replicate the exact string-building
    // logic for the VOOC case and setResult(), preventing the original from
    // running.
    // -----------------------------------------------------------------------

    private void hookKeyguardIndicationController(ClassLoader cl) {
        try {
            Class<?> kicClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController", cl);

            XposedHelpers.findAndHookMethod(kicClass, "computePowerIndication",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param)
                                throws Throwable {
                            buildVoocIndication(param);
                        }
                    });

            XposedBridge.log(TAG + ": hooked KIC.computePowerIndication");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": KIC hook failed: " + t.getMessage());
        }
    }

    /**
     * Called before computePowerIndication(). Checks the same guards the
     * original method does, then builds the VOOC string if appropriate.
     *
     * Field names are taken directly from the smali disassembly:
     *   mBatteryDefender, mPowerPluggedIn, mIncompatibleCharger, mPowerCharged,
     *   mChargingTimeRemaining (long), mPowerPluggedInWired, mChargingSpeed (int),
     *   mBatteryLevel (int), mContext.
     */
    private void buildVoocIndication(XC_MethodHook.MethodHookParam param) {
        Object kic = param.thisObject;
        try {
            // Mirror the early-return guards so we don't interfere with them.
            if (XposedHelpers.getBooleanField(kic, "mBatteryDefender")) return;
            if (XposedHelpers.getBooleanField(kic, "mPowerPluggedIn") &&
                    XposedHelpers.getBooleanField(kic, "mIncompatibleCharger")) return;
            if (XposedHelpers.getBooleanField(kic, "mPowerCharged")) return;

            // Only intercept when wired AND speed == VOOC.
            if (!XposedHelpers.getBooleanField(kic, "mPowerPluggedInWired")) return;
            int speed = XposedHelpers.getIntField(kic, "mChargingSpeed");
            if (speed != CHARGING_VOOC) return;

            // --- Build the string exactly as computePowerIndication() does ---
            Context ctx = (Context) XposedHelpers.getObjectField(kic, "mContext");
            Resources res = ctx.getResources();

            long timeRemaining = XposedHelpers.getLongField(kic, "mChargingTimeRemaining");
            boolean hasTime = timeRemaining > 0;

            // Format battery level as a percentage string (e.g. "73%").
            int level = XposedHelpers.getIntField(kic, "mBatteryLevel");
            String pct = NumberFormat.getPercentInstance().format(level / 100.0);

            String result;
            if (hasTime) {
                // formatShortElapsedTimeRoundingUpToMinutes is @hide — call via reflection.
                // Falls back to a plain "Xh Ym" string if the method is unavailable.
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

    /**
     * Calls android.text.format.Formatter.formatShortElapsedTimeRoundingUpToMinutes
     * via reflection (it is @hide and not in the public SDK).
     * Falls back to a simple "Xh Ym" string if reflection fails.
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
        // Plain fallback: "1h 23m" style
        long totalMinutes = (millis + 59_999) / 60_000;
        long hours   = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    // -----------------------------------------------------------------------
    // 4. KeyguardUpdateMonitor — force refresh when VOOC status changes
    // -----------------------------------------------------------------------

    /**
     * Hooks shouldTriggerBatteryUpdate(BatteryStatus old, BatteryStatus current).
     * Returns true (force update) when voocChargeStatus changed between the two,
     * matching the patch's added guard:
     *   if (nowPluggedIn && current.voocChargeStatus != old.voocChargeStatus)
     *       return true;
     */
    private void hookKeyguardUpdateMonitor(ClassLoader cl) {
        try {
            Class<?> kumClass = XposedHelpers.findClass(
                    "com.android.keyguard.KeyguardUpdateMonitor", cl);
            Class<?> bsClass = XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus", cl);

            XposedHelpers.findAndHookMethod(kumClass, "shouldTriggerBatteryUpdate",
                    bsClass, bsClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(param.getResult())) return;
                            Object oldStatus     = param.args[0];
                            Object currentStatus = param.args[1];
                            if (oldStatus == null || currentStatus == null) return;
                            if (loadVoocStatus(currentStatus) != loadVoocStatus(oldStatus)) {
                                param.setResult(true);
                            }
                        }
                    });

            XposedBridge.log(TAG + ": hooked KUM.shouldTriggerBatteryUpdate");
        } catch (Throwable t) {
            // Non-fatal: the indication text still works without this.
            XposedBridge.log(TAG + ": KUM hook failed (non-fatal): " + t.getMessage());
        }
    }
}
