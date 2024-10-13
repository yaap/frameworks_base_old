/*
 * Copyright (C) 2020 The Pixel Experience Project
 *               2021 AOSP-Krypton Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.internal.util.yaap;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import com.android.internal.R;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PixelPropsUtils {
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String VERSION_PREFIX = "VERSION.";

    private final HashMap<String, Object> certifiedProps;

    private static volatile boolean sIsFinsky = false;
    private static volatile boolean sIsEnabled = false;

    private static PixelPropsUtils sInstance = null;

    public static PixelPropsUtils getInstance(Context context) {
        if (sInstance == null) {
            synchronized (PixelPropsUtils.class) {
                try {
                    // see if we can even read the resource before we cache it forever
                    final String fp = context.getResources().getString(R.string.cert_fp);
                    if (fp == null || fp.isEmpty()) {
                        Logger.d("Can't read props from \"" +
                            context.getPackageName() + "\" context");
                        return null;
                    }
                } catch (Resources.NotFoundException e) {
                    Logger.d("Can't read props from \"" +
                            context.getPackageName() + "\" context");
                    return null;
                }
                sIsEnabled = true;
                sInstance = new PixelPropsUtils(context);
            }
        }
        return sInstance;
    }

    private PixelPropsUtils(Context context) {
        Resources res = context.getResources();

        // init certified props
        final String cert_device = res.getString(R.string.cert_device);
        final String cert_fp = res.getString(R.string.cert_fp);
        final String cert_model = res.getString(R.string.cert_model);
        final String cert_spl = res.getString(R.string.cert_spl);
        final String cert_manufacturer = res.getString(R.string.cert_manufacturer);
        final int cert_sdk = Integer.parseInt(res.getString(R.string.cert_sdk));

        Map<String, Object> tMap = new HashMap<>();
        String[] sections = cert_fp.split("/");
        tMap.put("ID", sections[3]);
        tMap.put("BRAND", sections[0]);
        tMap.put("MANUFACTURER", cert_manufacturer);
        tMap.put("MODEL", cert_model);
        tMap.put("PRODUCT", sections[1]);
        tMap.put("DEVICE", cert_device);
        tMap.put(VERSION_PREFIX + "RELEASE", sections[2].split(":")[1]);
        tMap.put(VERSION_PREFIX + "INCREMENTAL", sections[4].split(":")[0]);
        tMap.put(VERSION_PREFIX + "SECURITY_PATCH", cert_spl);
        tMap.put(VERSION_PREFIX + "DEVICE_INITIAL_SDK_INT", cert_sdk);
        tMap.put("FINGERPRINT", cert_fp);
        // conditionally spoofing if different
        if (Build.IS_DEBUGGABLE)
            tMap.put("IS_DEBUGGABLE", false);
        if (Build.IS_ENG)
            tMap.put("IS_ENG", false);
        if (!Build.IS_USER)
            tMap.put("IS_USER", true);
        if (!Build.TYPE.equals("user"))
            tMap.put("TYPE", "user");
        if (!Build.TAGS.equals("release-keys"))
            tMap.put("TAGS", "release-keys");
        certifiedProps = new HashMap<>(tMap);
    }

    public void setProps(String packageName) {
        if (packageName == null || !sIsEnabled) {
            return;
        }
        final String fp = (String) certifiedProps.get("FINGERPRINT");
        if (fp == null || fp.isEmpty()) {
            // no spoofing if the overlay doesn't exist
            Logger.d("Skipping setProps for \"" + packageName +
                    "\" because FINGERPRINT is empty");
            sIsEnabled = false;
            return;
        }
        Logger.d("Package = " + packageName);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        if (sIsFinsky || !packageName.equals(PACKAGE_GMS) ||
                !PROCESS_GMS_UNSTABLE.equals(Application.getProcessName())) {
            return;
        }
        certifiedProps.forEach(PixelPropsUtils::setPropValue);
    }

    private static void setPropValue(String key, Object value) {
        try {
            Logger.d("Setting prop " + key + " to " + value);
            Field field;
            if (key.startsWith(VERSION_PREFIX)) {
                field = Build.VERSION.class.getDeclaredField(
                        key.substring(VERSION_PREFIX.length()));
            } else {
                field = Build.class.getDeclaredField(key);
            }
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Logger.e("Failed to set prop " + key, e);
        }
    }

    public static boolean getIsFinsky() {
        return sIsFinsky;
    }

    public static boolean getIsEnabled() {
        return sIsEnabled;
    }

    private static class Logger {
        private static final String TAG = "PixelPropsUtils";

        private static void e(String msg, Exception e) {
            Log.e(TAG, msg, e);
        }

        private static void d(String msg) {
            if (!isLoggable()) return;
            Log.d(TAG, msg);
        }

        private static boolean isLoggable() {
            return Log.isLoggable(TAG, Log.DEBUG);
        }
    }
}
