package com.mrx7014.s25ultraspoofer;

import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module replicating the Oplus/OPPO UDFPS fix originally applied as a
 * frameworks/base patch (PHH treble / phh-treble-based GSIs).
 *
 * Targets three classes:
 *  1. com.android.systemui.biometrics.AuthController
 *       – onFingerUp / onFingerDown  →  sets sys.phh.oplus.fppress
 *  2. com.android.server.biometrics.AuthService  (getUdfpsProps)
 *       – parses persist.vendor.fingerprint.optical.sensorlocation / iconsize
 *  3. com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider
 *       – forces sensorType = TYPE_UDFPS_OPTICAL and halHandlesDisplayTouches = true
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PHH-OplusUdfpsFix";

    // -----------------------------------------------------------------------
    // Package / class constants
    // -----------------------------------------------------------------------
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android"; // system_server

    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";
    private static final String CLS_UDFPS_CALLBACK =
            "com.android.systemui.biometrics.UdfpsController$Callback";

    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";

    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";

    /** android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL = 3 */
    private static final int TYPE_UDFPS_OPTICAL = 3;

    // -----------------------------------------------------------------------
    // SystemProperties helpers (hidden API – accessed via reflection)
    // -----------------------------------------------------------------------

    /**
     * Equivalent to android.os.SystemProperties.get(key, def)
     * Called at runtime inside hooks, so the hidden class is already loaded.
     */
    private static String sysPropGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties.get failed for key=" + key, t);
            return def;
        }
    }

    /**
     * Equivalent to android.os.SystemProperties.set(key, value)
     * Works because the hook runs inside SystemUI / system_server process
     * which already holds the required permission.
     */
    private static void sysPropSet(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties.set failed key=" + key + " val=" + value, t);
        }
    }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        switch (lpparam.packageName) {
            case PKG_SYSTEMUI:
                hookAuthController(lpparam.classLoader);
                break;
            case PKG_SYSTEM:
                hookAuthService(lpparam.classLoader);
                hookFingerprintProvider(lpparam.classLoader);
                break;
        }
    }

    // -----------------------------------------------------------------------
    // 1. AuthController – set sys.phh.oplus.fppress on finger up / down
    // -----------------------------------------------------------------------
    private void hookAuthController(ClassLoader cl) {
        // The anonymous UdfpsController.Callback is compiled as AuthController$N.
        // Scan $1..$10 for the one that declares both onFingerUp and onFingerDown.
        boolean hooked = false;
        for (int i = 1; i <= 10; i++) {
            try {
                Class<?> candidate = XposedHelpers.findClass(
                        CLS_AUTH_CONTROLLER + "$" + i, cl);
                candidate.getDeclaredMethod("onFingerUp");
                candidate.getDeclaredMethod("onFingerDown");
                hookUdfpsCallbackClass(candidate);
                hooked = true;
                Log.i(TAG, "Hooked UdfpsController.Callback impl: " + candidate.getName());
                break;
            } catch (NoSuchMethodException | XposedHelpers.ClassNotFoundError ignored) {
                // not this inner class
            }
        }

        if (!hooked) {
            Log.w(TAG, "Could not locate AuthController inner UdfpsController.Callback – "
                    + "sys.phh.oplus.fppress hook inactive.");
        }
    }

    private void hookUdfpsCallbackClass(Class<?> cls) {
        // onFingerDown → sys.phh.oplus.fppress = "1"
        XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    sysPropSet("sys.phh.oplus.fppress", "1");
                    Log.d(TAG, "onFingerDown: set sys.phh.oplus.fppress=1");
                } catch (Throwable t) {
                    Log.e(TAG, "onFingerDown hook error", t);
                }
            }
        });

        // onFingerUp → sys.phh.oplus.fppress = "0"
        XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    sysPropSet("sys.phh.oplus.fppress", "0");
                    Log.d(TAG, "onFingerUp: set sys.phh.oplus.fppress=0");
                } catch (Throwable t) {
                    Log.e(TAG, "onFingerUp hook error", t);
                }
            }
        });
    }

    // -----------------------------------------------------------------------
    // 2. AuthService – inject Oplus UDFPS sensor location into getUdfpsProps()
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
        try {
            Class<?> authServiceCls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);

            XposedBridge.hookAllMethods(authServiceCls, "getUdfpsProps",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String fpLocation = sysPropGet(
                                        "persist.vendor.fingerprint.optical.sensorlocation", "");

                                if (!TextUtils.isEmpty(fpLocation)
                                        && fpLocation.contains("::")) {

                                    String[] coords = fpLocation.split("::");
                                    if (coords.length < 2) return;

                                    int x = Integer.parseInt(coords[0].trim());
                                    int y = Integer.parseInt(coords[1].trim());

                                    String iconSizeStr = sysPropGet(
                                            "persist.vendor.fingerprint.optical.iconsize", "0");
                                    int radius = Integer.parseInt(iconSizeStr.trim()) / 2;

                                    int[] udfpsProps = new int[]{x, y, radius};
                                    Log.d(TAG, "Oplus/OPPO UDFPS detected. Props: "
                                            + Arrays.toString(udfpsProps));

                                    // Short-circuit: return our result before original runs
                                    param.setResult(udfpsProps);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "getUdfpsProps hook error", t);
                            }
                        }
                    });

            Log.i(TAG, "Hooked AuthService#getUdfpsProps");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook AuthService", t);
        }
    }

    // -----------------------------------------------------------------------
    // 3. FingerprintProvider – force TYPE_UDFPS_OPTICAL when location X > 0
    // -----------------------------------------------------------------------
    private void hookFingerprintProvider(ClassLoader cl) {
        try {
            Class<?> fpProviderCls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);

            XposedBridge.hookAllMethods(fpProviderCls, "addSensor",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // Find the FingerprintSensorPropertiesInternal argument
                                Object prop = null;
                                for (Object arg : param.args) {
                                    if (arg != null && arg.getClass().getName()
                                            .equals(CLS_FP_SENSOR_PROPS)) {
                                        prop = arg;
                                        break;
                                    }
                                }
                                if (prop == null) return;

                                Object[] sensorLocations = (Object[])
                                        XposedHelpers.getObjectField(prop, "sensorLocations");

                                if (sensorLocations == null
                                        || sensorLocations.length != 1) return;

                                int sensorLocationX = XposedHelpers.getIntField(
                                        sensorLocations[0], "sensorLocationX");

                                if (sensorLocationX > 0) {
                                    Log.e(TAG, "Set fingerprint sensor type UDFPS Optical");
                                    XposedHelpers.setIntField(
                                            prop, "sensorType", TYPE_UDFPS_OPTICAL);
                                    XposedHelpers.setBooleanField(
                                            prop, "halHandlesDisplayTouches", true);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "addSensor hook error", t);
                            }
                        }
                    });

            Log.i(TAG, "Hooked FingerprintProvider#addSensor");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook FingerprintProvider", t);
        }
    }
}
