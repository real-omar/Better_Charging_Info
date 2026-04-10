package com.mrx7014.s25ultraspoofer;
 
import android.text.TextUtils;
import android.util.Log;
import java.util.Set;
import java.lang.reflect.Method;
import java.util.Arrays;
 
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
 
/**
 * LSPosed module replicating the Oplus/OPPO UDFPS fix.
 *
 * Covers the frameworks/base patch across three classes:
 *  1. com.android.systemui.biometrics.AuthController  (SystemUI)
 *       onFingerUp / onFingerDown  →  sets sys.phh.oplus.fppress
 *  2. com.android.server.biometrics.AuthService  (system_server)
 *       getUdfpsProps()  →  reads persist.vendor.fingerprint.optical.sensorlocation
 *  3. com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider  (system_server)
 *       addSensor()  →  forces sensorType=TYPE_UDFPS_OPTICAL, halHandlesDisplayTouches=true
 *
 * NOTE – frameworks/native patch (UdfpsExtension.cpp / SurfaceFlinger):
 *   The companion native patch modifies getUdfpsDimZOrder() (always returns 0x41000005)
 *   and getUdfpsZOrder() (returns 0x41000033 when touched, original z otherwise) inside
 *   SurfaceFlinger's UdfpsExtension.cpp. SurfaceFlinger is a native C++ process;
 *   LSPosed/Xposed CANNOT hook it. To apply that part you need either:
 *     a) A Magisk module that bind-mounts a patched libsurfaceflinger.so, or
 *     b) The patch compiled into the ROM's SurfaceFlinger at build time.
 *   This Java module handles everything that can be hooked via Xposed.
 *
 * REQUIRED module scope (xposed_scope / AndroidManifest meta-data):
 *   - com.android.systemui
 *   - android           ← system_server; WITHOUT this, hooks 2 & 3 never fire!
 */
public class MainHook implements IXposedHookLoadPackage {
 
    private static final String TAG = "PHH-OplusUdfpsFix";
 
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_SYSTEM   = "android"; // system_server
 
    private static final String CLS_AUTH_CONTROLLER =
            "com.android.systemui.biometrics.AuthController";
    private static final String CLS_AUTH_SERVICE =
            "com.android.server.biometrics.AuthService";
    private static final String CLS_FP_PROVIDER =
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider";
    private static final String CLS_FP_SENSOR_PROPS =
            "android.hardware.fingerprint.FingerprintSensorPropertiesInternal";
 
    // android.hardware.fingerprint.FingerprintSensorProperties.TYPE_UDFPS_OPTICAL = 3
    private static final int TYPE_UDFPS_OPTICAL = 3;
 
    // -----------------------------------------------------------------------
    // SystemProperties via reflection (hidden API – not importable at compile time)
    // -----------------------------------------------------------------------
    private static String sysPropGet(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, key, def);
        } catch (Throwable t) {
            Log.e(TAG, "SystemProperties.get failed key=" + key, t);
            return def;
        }
    }
 
    private static void sysPropSet(String key, String value) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method set = sp.getMethod("set", String.class, String.class);
            set.invoke(null, key, value);
        } catch (Throwable t) {
            // SELinux may block this from SystemUI on user builds; non-fatal.
            Log.e(TAG, "SystemProperties.set failed key=" + key + " val=" + value, t);
        }
    }
 
    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        Log.d(TAG, "handleLoadPackage: " + lpparam.packageName
                + " pid=" + android.os.Process.myPid());
 
        if (PKG_SYSTEMUI.equals(lpparam.packageName)) {
            hookAuthController(lpparam.classLoader);
        } else if (PKG_SYSTEM.equals(lpparam.packageName)) {
            hookAuthService(lpparam.classLoader);
            hookFingerprintProvider(lpparam.classLoader);
        }
    }
 
    // -----------------------------------------------------------------------
    // 1. AuthController – set sys.phh.oplus.fppress on finger up / down
    //
    // The patch inserts calls into the anonymous UdfpsController.Callback
    // registered inside AuthController. That anonymous class compiles to
    // AuthController$N. We scan $1..$15 for the one with both methods.
    // -----------------------------------------------------------------------
    private void hookAuthController(ClassLoader cl) {
        boolean hooked = false;
 
        // Scan anonymous inner classes
        for (int i = 1; i <= 15; i++) {
            try {
                Class<?> candidate = XposedHelpers.findClass(
                        CLS_AUTH_CONTROLLER + "$" + i, cl);
                candidate.getDeclaredMethod("onFingerUp");
                candidate.getDeclaredMethod("onFingerDown");
                hookUdfpsCallbackClass(candidate);
                hooked = true;
                Log.i(TAG, "Hooked UdfpsController.Callback: " + candidate.getName());
                break;
            } catch (NoSuchMethodException ignored) {
                // this $i exists but isn't the callback class
            } catch (XposedHelpers.ClassNotFoundError ignored) {
                // $i doesn't exist, keep scanning
            }
        }
 
        // Fallback: some ROMs use a named inner class
        if (!hooked) {
            String[] named = {
                    CLS_AUTH_CONTROLLER + "$UdfpsCallback",
                    CLS_AUTH_CONTROLLER + "$AuthControllerUdfpsCallback",
            };
            for (String name : named) {
                try {
                    Class<?> candidate = XposedHelpers.findClass(name, cl);
                    candidate.getDeclaredMethod("onFingerUp");
                    candidate.getDeclaredMethod("onFingerDown");
                    hookUdfpsCallbackClass(candidate);
                    hooked = true;
                    Log.i(TAG, "Hooked named UdfpsCallback: " + candidate.getName());
                    break;
                } catch (NoSuchMethodException | XposedHelpers.ClassNotFoundError ignored) {}
            }
        }
 
        if (!hooked) {
            Log.w(TAG, "Could not find UdfpsController.Callback in AuthController – "
                    + "sys.phh.oplus.fppress hook inactive.");
        }
    }
 
    private void hookUdfpsCallbackClass(Class<?> cls) {
        XposedBridge.hookAllMethods(cls, "onFingerDown", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                sysPropSet("sys.phh.oplus.fppress", "1");
                Log.d(TAG, "onFingerDown → sys.phh.oplus.fppress=1");
            }
        });
 
        XposedBridge.hookAllMethods(cls, "onFingerUp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                sysPropSet("sys.phh.oplus.fppress", "0");
                Log.d(TAG, "onFingerUp → sys.phh.oplus.fppress=0");
            }
        });
    }
 
    // -----------------------------------------------------------------------
    // 2. AuthService – inject Oplus UDFPS coords before getUdfpsProps() runs
    //
    // Reads persist.vendor.fingerprint.optical.sensorlocation ("x::y") and
    // persist.vendor.fingerprint.optical.iconsize, returns int[]{x, y, r}.
    //
    // RUNS IN: system_server (package "android")
    // -----------------------------------------------------------------------
    private void hookAuthService(ClassLoader cl) {
    try {
        Class<?> cls = XposedHelpers.findClass(CLS_AUTH_SERVICE, cl);
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(cls, "getUdfpsProps",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String fpLocation = sysPropGet(
                                    "persist.vendor.fingerprint.optical.sensorlocation", "");
                            if (TextUtils.isEmpty(fpLocation) || !fpLocation.contains("::"))
                                return;

                            String[] coords = fpLocation.split("::");
                            if (coords.length < 2) return;

                            int x = Integer.parseInt(coords[0].trim());
                            int y = Integer.parseInt(coords[1].trim());

                            String iconSizeStr = sysPropGet(
                                    "persist.vendor.fingerprint.optical.iconsize", "0");
                            int radius = Integer.parseInt(iconSizeStr.trim()) / 2;

                            int[] udfpsProps = {x, y, radius};
                            Log.d(TAG, "Oplus UDFPS props: " + Arrays.toString(udfpsProps));
                            param.setResult(udfpsProps);
                        } catch (Throwable t) {
                            Log.e(TAG, "getUdfpsProps hook error", t);
                        }
                    }
                });
        Log.i(TAG, "Hooked AuthService#getUdfpsProps (" + hooks.size() + " methods)");
    } catch (XposedHelpers.ClassNotFoundError e) {
        Log.e(TAG, "AuthService not found – add 'android' to module scope!", e);
    } catch (Throwable t) {
        Log.e(TAG, "Failed to hook AuthService", t);
    }
}

 
    // -----------------------------------------------------------------------
    // 3. FingerprintProvider – force TYPE_UDFPS_OPTICAL when sensorLocationX > 0
    //
    // Sets prop.sensorType = TYPE_UDFPS_OPTICAL (3) and
    // prop.halHandlesDisplayTouches = true before the Sensor object is built.
    //
    // RUNS IN: system_server (package "android")
    // -----------------------------------------------------------------------
    private void hookFingerprintProvider(ClassLoader cl) {
    try {
        Class<?> cls = XposedHelpers.findClass(CLS_FP_PROVIDER, cl);
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(cls, "addSensor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
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
                            if (sensorLocations == null || sensorLocations.length != 1)
                                return;

                            int sensorLocationX = XposedHelpers.getIntField(
                                    sensorLocations[0], "sensorLocationX");
                            if (sensorLocationX <= 0) return;

                            Log.e(TAG, "Set fingerprint sensor type UDFPS Optical"
                                    + " (sensorLocationX=" + sensorLocationX + ")");
                            XposedHelpers.setIntField(prop, "sensorType", TYPE_UDFPS_OPTICAL);
                            XposedHelpers.setBooleanField(
                                    prop, "halHandlesDisplayTouches", true);
                        } catch (Throwable t) {
                            Log.e(TAG, "addSensor hook error", t);
                        }
                    }
                });
        Log.i(TAG, "Hooked FingerprintProvider#addSensor (" + hooks.size() + " methods)");
    } catch (XposedHelpers.ClassNotFoundError e) {
        Log.e(TAG, "FingerprintProvider not found – add 'android' to module scope!", e);
    } catch (Throwable t) {
        Log.e(TAG, "Failed to hook FingerprintProvider", t);
    }
}
}
