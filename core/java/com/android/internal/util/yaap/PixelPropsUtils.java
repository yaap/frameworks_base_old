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

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public final class PixelPropsUtils {

    private static final String TAG = "PixelPropsUtils";
    private static final boolean DEBUG = true;

    private static final Map<String, Object> commonProps = Map.of(
        "BRAND", "google",
        "MANUFACTURER", "Google",
        "IS_DEBUGGABLE", false,
        "IS_ENG", false,
        "IS_USERDEBUG", false,
        "IS_USER", true,
        "TYPE", "user"
    );

    private static final Map<String, String> redfinProps = Map.of(
        "DEVICE", "redfin",
        "PRODUCT", "redfin",
        "MODEL", "Pixel 5",
        "FINGERPRINT", "google/redfin/redfin:12/SP1A.210812.015/7679548:user/release-keys"
    );

    private static final Map<String, String> marlinProps = Map.of(
        "DEVICE", "marlin",
        "PRODUCT", "marlin",
        "MODEL", "Pixel XL",
        "FINGERPRINT", "google/marlin/marlin:10/QP1A.191005.007.A3/5972272:user/release-keys"
    );

    private static final Map<String, String> walleyeProps = Map.of(
        "DEVICE", "walleye",
        "PRODUCT", "walleye",
        "MODEL", "Pixel 2",
        "FINGERPRINT", "google/walleye/walleye:10/QP1A.190711.019/5790879:user/release-keys"
    );

    private static final Map<String, String> crosshatchProps = Map.of(
        "DEVICE", "crosshatch",
        "PRODUCT", "crosshatch",
        "MODEL", "Pixel 3 XL",
        "FINGERPRINT", "google/crosshatch/crosshatch:12/SP1A.210812.015/7679548:user/release-keys"
    );

    private static final List<String> packagesToChange = List.of(
        "com.android.vending",
        "com.breel.wallpapers20",
        "com.google.android.apps.customization.pixel",
        "com.google.android.apps.fitness",
        "com.google.android.apps.gcs",
        "com.google.android.apps.maps",
        "com.google.android.apps.nexuslauncher",
        "com.google.android.apps.messaging",
        "com.google.android.apps.pixelmigrate",
        "com.google.android.apps.recorder",
        "com.google.android.apps.safetyhub",
        "com.google.android.apps.subscriptions.red",
        "com.google.android.apps.tachyon",
        "com.google.android.apps.turbo",
        "com.google.android.apps.turboadapter",
        "com.google.android.apps.wallpaper",
        "com.google.android.apps.wallpaper.pixel",
        "com.google.android.apps.wellbeing",
        "com.google.android.as",
        "com.google.android.configupdater",
        "com.google.android.dialer",
        "com.google.android.ext.services",
        "com.google.android.gms",
        "com.google.android.gms.location.history",
        "com.google.android.gsf",
        "com.google.android.inputmethod.latin",
        "com.google.android.soundpicker",
        "com.google.intelligence.sense",
        "com.google.pixel.dynamicwallpapers",
        "com.google.pixel.livewallpaper"
    );

    private static final List<String> packagesToChangePixelXL = List.of(
        "com.google.android.apps.photos",
        "com.samsung.accessory.berrymgr",
        "com.samsung.accessory.fridaymgr",
        "com.samsung.accessory.neobeanmgr",
        "com.samsung.android.app.watchmanager",
        "com.samsung.android.geargplugin",
        "com.samsung.android.gearnplugin",
        "com.samsung.android.modenplugin",
        "com.samsung.android.neatplugin",
        "com.samsung.android.waterplugin"
    );

    private static final List<String> packagesToChangePixel3XL = List.of(
        "com.google.android.googlequicksearchbox"
    );

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
        } else if (packagesToChangePixelXL.contains(packageName)) {
            commonProps.forEach(PixelPropsUtils::setPropValue);
            marlinProps.forEach(PixelPropsUtils::setPropValue);
        } else if (packagesToChangePixel3XL.contains(packageName)) {
            commonProps.forEach(PixelPropsUtils::setPropValue);
            crosshatchProps.forEach(PixelPropsUtils::setPropValue);
        }
        // Set proper indexing fingerprint
        if (packageName.equals("com.google.android.settings.intelligence")) {
            setPropValue("FINGERPRINT", Build.DATE);
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
