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

    private static final String TAG = "PixelPropsUtils";

    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String PROCESS_GMS_PERSISTENT = PACKAGE_GMS + ".persistent";
    private static final String VERSION_PREFIX = "VERSION.";

    private static final Resources mResources;
    static {
        // make sure we only use the english strings
        Resources res = Resources.getSystem();
        Configuration conf = res.getConfiguration();
        conf.setLocale(Locale.ENGLISH);
        res.updateConfiguration(conf, null);
        mResources = res;
    }

    private static final String build_device = mResources.getString(R.string.build_device);
    private static final String build_fp = mResources.getString(R.string.build_fp);
    private static final String build_model = mResources.getString(R.string.build_model);

    private static final HashMap<String, String> certifiedProps = new HashMap<>(Map.of(
        "ID", "NRD90M",
        "BRAND", "NVIDIA",
        "MANUFACTURER", "NVIDIA",
        "MODEL", "SHIELD Android TV",
        "PRODUCT", "foster_e",
        "DEVICE", "foster",
        VERSION_PREFIX + "SECURITY_PATCH", "2018-01-05",
        "FINGERPRINT", "NVIDIA/foster_e/foster:7.0/NRD90M/2427173_1038.2788:user/release-keys"
    ));

    private static final HashMap<String, String> buildProps = new HashMap<>(Map.of(
        "ID", build_fp.split("/", 5)[3],
        "DEVICE", build_device,
        "PRODUCT", build_device,
        "MODEL", build_model,
        "FINGERPRINT", build_fp
    ));

    private static final HashMap<String, Object> commonProps;
    static {
        Map<String, Object> tMap = new HashMap<>();
        tMap.put("BRAND", "google");
        tMap.put("MANUFACTURER", "Google");
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
        commonProps = new HashMap<>(tMap);
    }

    private static final HashMap<String, HashMap<String, String>> propsToKeep;
    static {
        // null means skip the package
        Map<String, HashMap<String, String>> tMap = new HashMap<>();
        tMap.put("com.google.android.settings.intelligence", // Set proper indexing fingerprint
                new HashMap<>(Map.of("FINGERPRINT", Build.VERSION.INCREMENTAL)));
        tMap.put("com.google.android.GoogleCamera", null);
        tMap.put("com.google.android.GoogleCameraGood", null);
        tMap.put("com.google.android.GoogleCamera.Cameight", null);
        tMap.put("com.google.android.GoogleCamera.Go", null);
        tMap.put("com.google.android.GoogleCamera.Urnyx", null);
        tMap.put("com.google.android.GoogleCameraAsp", null);
        tMap.put("com.google.android.GoogleCameraCVM", null);
        tMap.put("com.google.android.GoogleCameraEng", null);
        tMap.put("com.google.android.GoogleCameraEng2", null);
        tMap.put("com.google.android.MTCL83", null);
        tMap.put("com.google.android.UltraCVM", null);
        tMap.put("com.google.android.apps.cameralite", null);
        tMap.put("com.google.ar.core", null);
        tMap.put("com.google.android.tts", null);
        propsToKeep = new HashMap<>(tMap);
    }

    private static final HashSet<String> extraPackagesToChange = new HashSet<>(Set.of(
        "com.breel.wallpapers20"
    ));

    private static final HashSet<String> extraGMSProcToChange = new HashSet<>(Set.of(
        "com.google.android.gms.ui",
        "com.google.android.gms.learning"
    ));

    private static volatile boolean sIsFinsky = false;

    public static void setProps(String packageName) {
        if (packageName == null) return;
        if (isLoggable()) Log.d(TAG, "Package = " + packageName);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        if (packageName.equals(PACKAGE_GMS)) {
            final String procName = Application.getProcessName();
            final boolean isUnstable = PROCESS_GMS_UNSTABLE.equals(procName);
            final boolean isPersistent = !isUnstable && PROCESS_GMS_PERSISTENT.equals(procName);
            final boolean isExtra = !isUnstable && !isPersistent
                    && extraGMSProcToChange.contains(procName);
            // GMS specific spoofing
            if (!isUnstable && !isPersistent && !isExtra) return;
            commonProps.forEach(PixelPropsUtils::setPropValue);
            if (isUnstable) {
                certifiedProps.forEach(PixelPropsUtils::setPropValue);
                return;
            }
            buildProps.forEach(PixelPropsUtils::setPropValue);
        } else if (packageName.startsWith("com.google.")
                || extraPackagesToChange.contains(packageName)) {
            final boolean isInKeep = propsToKeep.containsKey(packageName);
            final HashMap<String, String> keepMap = isInKeep ? propsToKeep.get(packageName) : null;
            if (isInKeep && keepMap == null) {
                if (isLoggable()) Log.d(TAG, "Skipping all props for: " + packageName);
                return;
            }
            commonProps.forEach(PixelPropsUtils::setPropValue);
            buildProps.forEach((key, value) -> {
                if (isInKeep && keepMap.containsKey(key)) {
                    final String keyValue = keepMap.get(key);
                    if (keyValue == null) {
                        if (isLoggable())
                            Log.d(TAG, "Not defining " + key + " prop for: " + packageName);
                        return;
                    }
                    value = keyValue;
                }
                if (isLoggable()) Log.d(TAG, "Defining " + key + " prop for: " + packageName);
                setPropValue(key, value);
            });
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (isLoggable()) Log.d(TAG, "Setting prop " + key + " to " + value);
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
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    public static boolean getIsFinsky() {
        return sIsFinsky;
    }

    private static boolean isLoggable() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }
}
