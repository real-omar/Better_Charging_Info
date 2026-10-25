package com.omar.vooc_info;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VoocChargerHook";
    private static final String VOOC_NODE = "/sys/class/power_supply/battery/voocchg_ing";
    private static final String BATT = "/sys/class/power_supply/battery/";

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

            String info = buildElectricalInfo();
            XposedBridge.log(TAG + ": info=" + info);
            StringBuilder sb = new StringBuilder(pct).append(" \u2022 VOOC Charging");
            if (info != null) sb.append(" \u2022 ").append(info);
            if (timeRemaining > 0) {
                sb.append(" (").append(formatElapsedTime(ctx, timeRemaining)).append(" until full)");
            }
            String result = sb.toString();
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

    /** Returns current in µA (absolute), or -1 if unavailable. Kernel reports mA. */
    private long readCurrentUa() {
        long c = readSysfsLong(BATT + "current_now");
        if (c == -1 || c == 0) return -1;
        return Math.abs(c) * 1000;   // mA -> µA
    }

    /** Returns voltage in µV, or -1 if unavailable. Kernel reports µV. */
    private long readVoltageUv() {
        long v = readSysfsLong(BATT + "voltage_now");
        return v > 0 ? v : -1;       // already µV
    }

    /** "4.32 V • 3.33 A • 14.4 W" or null if we can't read both. */
    private String buildElectricalInfo() {
        long ua = readCurrentUa();
        long uv = readVoltageUv();
        if (ua <= 0 || uv <= 0) return null;
        double amps  = ua / 1_000_000.0;
        double volts = uv / 1_000_000.0;
        double watts = amps * volts;
        return String.format(Locale.US, "%.2f V \u2022 %.2f A \u2022 %.1f W", volts, amps, watts);
    }

     private long estimateChargingTimeMs() {
    try {
        long current = readCurrentUa();
        if (current <= 0) return -1;

        // Prefer design capacity (reported in µAh on your kernel).
        long full = readSysfsLong(BATT + "charge_full_design");
        if (full <= 0) {
            full = readSysfsLong(BATT + "charge_full");
            if (full > 0 && full < 100_000) full *= 1000;   // mAh -> µAh
        }

        // charge_now is broken on this kernel, so derive from capacity %.
        long cap = readSysfsLong(BATT + "capacity");
        if (full <= 0 || cap < 0 || cap >= 100) return -1;

        long remainingUAh = full * (100 - cap) / 100;
        return (remainingUAh * 3_600_000L) / current;
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
