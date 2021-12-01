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
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class PixelPropsUtils {

    private static final String TAG = "PixelPropsUtils";
    private static final boolean DEBUG = false;

    private static final String build_device = Resources.getSystem().getString(com.android.internal.R.string.build_device);
    private static final String build_fp = Resources.getSystem().getString(com.android.internal.R.string.build_fp);
    private static final String build_model = Resources.getSystem().getString(com.android.internal.R.string.build_model);
    private static final Map<String, String> redfinProps = Map.of(
        "DEVICE", build_device,
        "PRODUCT", build_device,
        "MODEL", build_model,
        "FINGERPRINT", build_fp
    );

    private static final Map<String, Object> commonProps = Map.of(
        "BRAND", "google",
        "MANUFACTURER", "Google",
        "IS_DEBUGGABLE", false,
        "IS_ENG", false,
        "IS_USERDEBUG", false,
        "IS_USER", true,
        "TYPE", "user"
    );

    private static final List<String> packagesToChange = Arrays.asList(Resources.getSystem().getStringArray(com.android.internal.R.array.gaaps_package_names));

    public static void setProps(String packageName) {
        if (packageName == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Package = " + packageName);
        }
        if (packagesToChange.contains(packageName)) {
            commonProps.forEach(PixelPropsUtils::setPropValue);
            redfinProps.forEach((key, value) -> {
                if (packageName.equals("com.google.android.gms") && key.equals("MODEL")) {
                    return;
                } else {
                    setPropValue(key, value);
                }
            });
        }
        // Set proper indexing fingerprint
        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", Build.VERSION.INCREMENTAL);
        }
    }

    private static void setPropValue(String key, Object value) {
        try {
            if (DEBUG) {
                Log.d(TAG, "Setting prop " + key + " to " + value);
            }
            final Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }
}
