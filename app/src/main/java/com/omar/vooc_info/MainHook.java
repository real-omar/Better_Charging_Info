package com.omar.vooc_info;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VoocChargerHook";
    private static final String VOOC_NODE = "/sys/class/power_supply/battery/voocchg_ing";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log(TAG + ": loaded in " + lpparam.packageName);
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        hookKeyguardIndicationController(lpparam.classLoader);
    }

    private void hookKeyguardIndicationController(ClassLoader cl) {
        Class<?> kicClass;
        try {
            kicClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.KeyguardIndicationController", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": KIC class not found: " + t.getMessage());
            return;
        }

        // Log every declared method so we can see the real signature on this ROM.
        for (Method m : kicClass.getDeclaredMethods()) {
            if (m.getName().toLowerCase().contains("powerindication")) {
                XposedBridge.log(TAG + ": candidate method " + m.getName()
                        + " args=" + Arrays.toString(m.getParameterTypes()));
            }
        }

        // Try the common variants: no-arg and boolean-arg.
        boolean hooked = false;
        try {
            XposedHelpers.findAndHookMethod(kicClass, "computePowerIndication",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            buildVoocIndication(p);
                        }
                    });
            hooked = true;
        } catch (Throwable ignored) {}
        if (!hooked) {
            try {
                XposedHelpers.findAndHookMethod(kicClass, "computePowerIndication",
                        boolean.class, new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                buildVoocIndication(p);
                            }
                        });
                hooked = true;
            } catch (Throwable ignored) {}
        }
        XposedBridge.log(TAG + ": KIC hook " + (hooked ? "OK" : "FAILED"));
    }

    private void buildVoocIndication(XC_MethodHook.MethodHookParam param) {
        Object kic = param.thisObject;
        try {
            // Guard fields may be absent — use a safe getter.
            if (getBoolSafe(kic, "mBatteryDefender")) return;
            if (getBoolSafe(kic, "mPowerPluggedIn")
                    && getBoolSafe(kic, "mIncompatibleCharger")) return;
            if (getBoolSafe(kic, "mPowerCharged")) return;

            // If wired-field name differs, fall back to "any plugged in".
            boolean wired = getBoolSafe(kic, "mPowerPluggedInWired")
                         || getBoolSafe(kic, "mPowerPluggedIn");
            if (!wired) {
                XposedBridge.log(TAG + ": not wired-plugged, bail");
                return;
            }

            boolean vooc = isVoocCharger();
            XposedBridge.log(TAG + ": vooc=" + vooc);
            if (!vooc) return;

            Context ctx = (Context) XposedHelpers.getObjectField(kic, "mContext");
            int level = getIntSafe(kic, "mBatteryLevel", -1);
            XposedBridge.log(TAG + ": level=" + level);
            if (level < 0 || level > 100) return;
            String pct = level + "%";

            long timeRemaining = estimateChargingTimeMs();
            XposedBridge.log(TAG + ": estMs=" + timeRemaining);
            if (timeRemaining <= 0) {
                timeRemaining = getLongSafe(kic, "mChargingTimeRemaining", 0);
            }

            String result = (timeRemaining > 0)
                    ? pct + " \u2022 VOOC Charging (" + formatElapsedTime(ctx, timeRemaining) + " until full)"
                    : pct + " \u2022 VOOC Charging";
            XposedBridge.log(TAG + ": result=" + result);
            param.setResult(result);

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": buildVoocIndication error: " + t);
        }
    }

    // ---- safe field accessors ----
    private boolean getBoolSafe(Object o, String name) {
        try { return XposedHelpers.getBooleanField(o, name); }
        catch (Throwable ignored) { return false; }
    }
    private int getIntSafe(Object o, String name, int def) {
        try { return XposedHelpers.getIntField(o, name); }
        catch (Throwable ignored) { return def; }
    }
    private long getLongSafe(Object o, String name, long def) {
        try { return XposedHelpers.getLongField(o, name); }
        catch (Throwable ignored) { return def; }
    }

    // ---- sysfs ----
    private boolean isVoocCharger() {
        try (BufferedReader br = new BufferedReader(new FileReader(VOOC_NODE))) {
            String line = br.readLine();
            return line != null && "1".equals(line.trim());
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            Log.w(TAG, "isVoocCharger: " + e.getMessage());
        }
        return false;
    }

    private long estimateChargingTimeMs() {
        try {
            long current = readSysfsLong("/sys/class/power_supply/battery/current_now");
            if (current == Long.MIN_VALUE || current == 0) return -1;
            current = Math.abs(current);
            // Detect units: MTK reports µA (e.g. 2_000_000). Some report mA (e.g. 2000).
            if (current < 100) return -1;          // < 100 µA — idle
            if (current < 20_000) current *= 1000; // likely mA → convert to µA

            long chargeFull = readSysfsLong("/sys/class/power_supply/battery/charge_full");
            long chargeNow  = readSysfsLong("/sys/class/power_supply/battery/charge_now");
            if (chargeFull > 0 && chargeNow > 0 && chargeFull > chargeNow) {
                long remainingUAh = chargeFull - chargeNow;
                return (remainingUAh * 3_600_000L) / current;
            }
        } catch (Throwable t) {
            Log.w(TAG, "estimateChargingTimeMs: " + t);
        }
        return -1;
    }

    private long readSysfsLong(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line == null) return -1;
            return Long.parseLong(line.trim());
        } catch (FileNotFoundException ignored) {
            return -1; // expected on many GSIs — don't spam
        } catch (NumberFormatException | IOException e) {
            Log.w(TAG, "readSysfsLong(" + path + "): " + e.getMessage());
            return -1;
        }
    }

    private String formatElapsedTime(Context ctx, long millis) {
        try {
            Class<?> cls = Class.forName("android.text.format.Formatter");
            Method m = cls.getMethod("formatShortElapsedTimeRoundingUpToMinutes",
                    Context.class, long.class);
            return (String) m.invoke(null, ctx, millis);
        } catch (Throwable ignored) {}
        long totalMinutes = (millis + 59_999) / 60_000;
        long h = totalMinutes / 60, m = totalMinutes % 60;
        return h > 0 ? h + "h " + m + "m" : m + "m";
    }
}
