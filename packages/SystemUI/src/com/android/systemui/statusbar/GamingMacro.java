/*
 * Copyright (C) 2024 Yet Another AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MIN;
import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManager;
import android.hardware.power.Mode;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.media.AudioManager;
import android.provider.Settings;

import com.android.server.LocalServices;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;

/** A class to set/restore gaming macro **/
public class GamingMacro {
    private static final int NOTIFICATION_ID = 10000;
    private static final String TAG = "GamingMacro";
    private static final String CHANNEL_ID = "gaming_mode";
    private static final String ACTION_STOP = "gaming_macro_stop";
    private static final Intent SETTINGS_INTENT = new Intent("com.android.settings.GAMING_MODE_SETTINGS");
    static {
        SETTINGS_INTENT.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    // saved settings state keys
    private static final String KEY_HEADSUP_STATE = "gaming_mode_state_headsup";
    private static final String KEY_ZEN_STATE = "gaming_mode_state_zen";
    private static final String KEY_RINGER_MODE = "gmaing_mode_ringer_mode";
    // private static final String KEY_NAVBAR_STATE = "gaming_mode_state_navbar";
    // private static final String KEY_HW_KEYS_STATE = "gaming_mode_state_hw_keys";
    private static final String KEY_NIGHT_LIGHT = "gaming_mode_night_light";
    private static final String KEY_NIGHT_LIGHT_AUTO = "gaming_mode_night_light_auto";
    private static final String KEY_BATTERY_SAVER = "gaming_mode_battery_saver";
    private static final String KEY_BATTERY_SAVER_MODE = "gaming_mode_battery_saver_mode";
    private static final String KEY_BATTERY_SAVER_LEVEL = "gaming_mode_battery_saver_level";
    private static final String KEY_BLUETOOTH = "gaming_mode_bluetooth";
    private static final String KEY_THREE_FINGER = "gaming_mode_three_finger";
    private static final String KEY_EXTRA_DIM = "gaming_mode_extra_dim";
    private static final String KEY_EXTRA_DIM_SCHEDULE = "gaming_mode_extra_dim_schedule";
    private static final String KEY_BRIGHTNESS_STATE = "gaming_mode_state_brightness";
    private static final String KEY_BRIGHTNESS_LEVEL = "gaming_mode_level_brightness";
    private static final String KEY_MEDIA_LEVEL = "gaming_mode_level_media";

    private final Context mContext;
    private final AudioManager mAudio;
    private final NotificationManager mNm;
    private final ContentResolver mResolver;
    private final GamingStopBroadcastReceiver mStopBroadcastReceiver;
    private final ScreenBroadcastReceiver mScreenBroadcastReceiver;
    private final BatteryBroadcastReceiver mBatteryBroadcastReceiver;
    private final BatteryController mBatteryController;
    private final DisplayManager mDisplayManager;
    private final PowerManager mPowerManager;
    private final PowerManagerInternal mPowerManagerInternal;
    private final ColorDisplayManager mColorManager;
    private final BluetoothController mBluetoothController;
    private final SharedPreferences mPrefs;
    // private final boolean mHasHWKeys;
    private boolean mScreenRegistered;
    private boolean mBatteryRegistered;
    private boolean mStopRegistered;

    // user settings
    private boolean mHeadsUpEnabled;
    private boolean mZenEnabled;
    // private boolean mNavBarEnabled;
    // private boolean mHwKeysEnabled;
    private boolean mNightLightEnabled;
    private boolean mBatterySaverEnabled;
    private boolean mPowerEnabled;
    private boolean mBluetoothEnabled;
    private boolean mThreeFingerEnabled;
    private boolean mExtraDimEnabled;
    private boolean mBrightnessEnabled;
    private boolean mMediaEnabled;
    private boolean mScreenOffEnabled;
    private boolean mBatterySaverDisables;

    private int mRingerMode = 0;
    private int mBrightnessLevel = 80;
    private int mMediaLevel = 80;

    private float mBrightnessMin = PowerManager.BRIGHTNESS_MIN;
    private float mBrightnessMax = PowerManager.BRIGHTNESS_MAX;

    public GamingMacro(Context context,
            ColorDisplayManager colorManager,
            BatteryController batteryController,
            BluetoothController bluetoothController
    ) {
        mContext = context;
        mResolver = context.getContentResolver();
        mAudio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mNm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mDisplayManager = (DisplayManager) context.getSystemService(DisplayManager.class);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mColorManager = colorManager;
        mBatteryController = batteryController;
        mBluetoothController = bluetoothController;
        mPrefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences(TAG, Context.MODE_PRIVATE);

        final BrightnessInfo info = context.getDisplay().getBrightnessInfo();
        if (info != null) {
            mBrightnessMin = info.brightnessMinimum;
            mBrightnessMax = info.brightnessMaximum;
        }

        // find out if a physical navbar is present
        // Configuration c = context.getResources().getConfiguration();
        // mHasHWKeys = c.navigation != Configuration.NAVIGATION_NONAV;

        mStopBroadcastReceiver = new GamingStopBroadcastReceiver();
        mScreenBroadcastReceiver = new ScreenBroadcastReceiver();
        mBatteryBroadcastReceiver = new BatteryBroadcastReceiver();
    }

    /**
     * Activates/Deactivates the macro
     * Only call externally!
     */
    public synchronized void setEnabled(boolean enabled) {
        if (enabled) {
            saveSettingsState();
            updateUserSettings();

            if (mHeadsUpEnabled) {
                Settings.Global.putInt(mResolver,
                        Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 0);
            }

            if (mZenEnabled) {
                mNm.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            }

            if (mRingerMode != 0) {
                // if we somehow have an invalid setting value stay at the same mode
                int mode = mAudio.getRingerModeInternal();
                if (mRingerMode == 1) {
                    mode = AudioManager.RINGER_MODE_VIBRATE;
                } else if (mRingerMode == 2) {
                    mode = AudioManager.RINGER_MODE_SILENT;
                }
                mAudio.setRingerModeInternal(mode);
            }

            // if (mNavBarEnabled) {
            //     Settings.System.putInt(mResolver,
            //             Settings.System.FORCE_SHOW_NAVBAR, 0);
            // }
            //
            // if (mHwKeysEnabled && mHasHWKeys) {
            //     Settings.Secure.putInt(mResolver,
            //             Settings.Secure.HARDWARE_KEYS_DISABLE, 1);
            // }

            if (mNightLightEnabled) {
                mColorManager.setNightDisplayActivated(false);
                mColorManager.setNightDisplayAutoMode(ColorDisplayManager.AUTO_MODE_DISABLED);
            }

            if (mBatterySaverEnabled) {
                // disable
                mBatteryController.setPowerSaveMode(false);
                // Set to percentage mode at 0
                Settings.Global.putInt(mResolver,
                        Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0);
                Settings.Global.putInt(mResolver,
                        Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
                        PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
            }

            if (mPowerEnabled && mPowerManagerInternal != null) {
                mPowerManagerInternal.setPowerMode(Mode.GAME, true);
            }

            if (mBluetoothEnabled) {
                mBluetoothController.setBluetoothEnabled(true);
            }

            if (mThreeFingerEnabled) {
                Settings.System.putInt(mResolver,
                        Settings.System.GAMING_MODE_THREE_FINGER, 0);
            }

            if (mExtraDimEnabled) {
                mColorManager.setReduceBrightColorsActivated(false);
                Settings.Secure.putInt(mResolver,
                        Settings.Secure.EXTRA_DIM_AUTO_MODE, 0);
            }

            if (mBrightnessEnabled) {
                // Set manual
                Settings.System.putInt(mResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                if (mBrightnessLevel != 0) {
                    // Set level
                    final int gamma = Math.round(GAMMA_SPACE_MIN +
                            (mBrightnessLevel / 100f) * (GAMMA_SPACE_MAX - GAMMA_SPACE_MIN));
                    final float lFloat = convertGammaToLinearFloat(gamma, mBrightnessMin, mBrightnessMax);
                    mDisplayManager.setBrightness(mContext.getDisplayId(),
                            Math.min(lFloat, mBrightnessMax));
                }
            }

            if (mMediaEnabled) {
                final int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                final int level = Math.round((float)max * ((float)mMediaLevel / 100f));
                mAudio.setStreamVolume(AudioManager.STREAM_MUSIC, level,
                        AudioManager.FLAG_SHOW_UI);
            }

            if (mScreenOffEnabled) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                mContext.registerReceiver(mScreenBroadcastReceiver, filter);
                mScreenRegistered = true;
            }

            if (mBatterySaverDisables && !mBatterySaverEnabled) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
                mContext.registerReceiver(mBatteryBroadcastReceiver, filter);
                mBatteryRegistered = true;
            }
        } else {
            restoreSettingsState();
            if (mScreenRegistered) {
                mContext.unregisterReceiver(mScreenBroadcastReceiver);
                mScreenRegistered = false;
            }
            if (mBatteryRegistered) {
                mContext.unregisterReceiver(mBatteryBroadcastReceiver);
                mBatteryRegistered = false;
            }
        }
        setNotification(enabled);
    }

    private void updateUserSettings() {
        mHeadsUpEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_HEADS_UP, 1) == 1;
        mZenEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_ZEN, 0) == 1;
        mRingerMode = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_RINGER, 0);
        // mNavBarEnabled = Settings.System.getInt(mResolver,
        //         Settings.System.GAMING_MODE_NAVBAR, 0) == 1;
        // mHwKeysEnabled = Settings.System.getInt(mResolver,
        //         Settings.System.GAMING_MODE_HW_BUTTONS, 1) == 1;
        mNightLightEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_NIGHT_LIGHT, 0) == 1;
        mBatterySaverEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_BATTERY_SCHEDULE, 0) == 1;
        mPowerEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_POWER, 1) == 1;
        mBluetoothEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_BLUETOOTH, 0) == 1;
        mThreeFingerEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_THREE_FINGER, 0) == 1;
        mExtraDimEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_EXTRA_DIM, 0) == 1;
        mBrightnessEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_BRIGHTNESS_ENABLED, 0) == 1;
        mBrightnessLevel = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_BRIGHTNESS, 80);
        mMediaEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_MEDIA_ENABLED, 0) == 1;
        mMediaLevel = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_MEDIA, 80);
        mScreenOffEnabled = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_SCREEN_OFF, 0) == 1;
        mBatterySaverDisables = Settings.System.getInt(mResolver,
                Settings.System.GAMING_MODE_BATTERY_SAVER_DISABLES, 0) == 1;
    }

    private void saveSettingsState() {
        SharedPreferences.Editor editor = mPrefs.edit();
        // remove all keys first. in restore we check which ones exist
        editor.clear();
        if (mHeadsUpEnabled) {
            editor.putInt(KEY_HEADSUP_STATE, Settings.Global.getInt(mResolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED, 1));
        }

        if (mZenEnabled) {
            editor.putInt(KEY_ZEN_STATE, Settings.Global.getInt(mResolver,
                    Settings.Global.ZEN_MODE, 0) != 0 ? 1 : 0);
        }

        if (mRingerMode != 0) {
            editor.putInt(KEY_RINGER_MODE, mAudio.getRingerModeInternal());
        }

        // if (mNavBarEnabled) {
        //     editor.putInt(KEY_NAVBAR_STATE, Settings.System.getInt(mResolver,
        //             Settings.System.FORCE_SHOW_NAVBAR, 1));
        // }
        //
        // if (mHwKeysEnabled && mHasHWKeys) {
        //     editor.putInt(KEY_HW_KEYS_STATE, Settings.Secure.getInt(mResolver,
        //             Settings.Secure.HARDWARE_KEYS_DISABLE, 0));
        // }

        if (mNightLightEnabled) {
            editor.putInt(KEY_NIGHT_LIGHT, mColorManager.isNightDisplayActivated() ? 1 : 0);
            editor.putInt(KEY_NIGHT_LIGHT_AUTO, mColorManager.getNightDisplayAutoMode());
        }

        if (mBatterySaverEnabled) {
            editor.putInt(KEY_BATTERY_SAVER, mBatteryController.isPowerSave() ? 1 : 0);
            editor.putInt(KEY_BATTERY_SAVER_MODE, Settings.Global.getInt(mResolver,
                    Settings.Global.AUTOMATIC_POWER_SAVE_MODE,
                    PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE));
            editor.putInt(KEY_BATTERY_SAVER_LEVEL, Settings.Global.getInt(mResolver,
                    Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, 0));
        }

        if (mBluetoothEnabled) {
            editor.putInt(KEY_BLUETOOTH, mBluetoothController.isBluetoothEnabled() ? 1 : 0);
        }

        if (mThreeFingerEnabled) {
            editor.putInt(KEY_THREE_FINGER, Settings.System.getInt(mResolver,
                    Settings.System.GAMING_MODE_THREE_FINGER, 0));
        }

        if (mExtraDimEnabled) {
            editor.putInt(KEY_EXTRA_DIM,
                    mColorManager.isReduceBrightColorsActivated() ? 1 : 0);
            editor.putInt(KEY_EXTRA_DIM_SCHEDULE, Settings.Secure.getInt(mResolver,
                    Settings.Secure.EXTRA_DIM_AUTO_MODE, 0));
        }

        if (mBrightnessEnabled) {
            editor.putInt(KEY_BRIGHTNESS_STATE, Settings.System.getInt(mResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
            editor.putInt(KEY_BRIGHTNESS_LEVEL,
                    Math.round(mDisplayManager.getBrightness(mContext.getDisplayId()) * 100f));
        }

        if (mMediaEnabled) {
            // save current volume as percentage
            // we can restore it that way even if vol steps was changed in runtime
            final int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            final int curr = mAudio.getStreamVolume(AudioManager.STREAM_MUSIC);
            editor.putInt(KEY_MEDIA_LEVEL, Math.round((float)curr * 100f / (float)max));
        }

        // use commit to keep this synced
        editor.commit();
    }

    private void restoreSettingsState() {
        if (mPrefs.contains(KEY_HEADSUP_STATE)) {
            Settings.Global.putInt(mResolver,
                    Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED,
                    mPrefs.getInt(KEY_HEADSUP_STATE, 1));
        }

        if (mPrefs.contains(KEY_ZEN_STATE)) {
            mNm.setInterruptionFilter(mPrefs.getInt(KEY_ZEN_STATE, 0) == 1
                    ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    : NotificationManager.INTERRUPTION_FILTER_ALL);
        }

        if (mPrefs.contains(KEY_RINGER_MODE)) {
            mAudio.setRingerModeInternal(mPrefs.getInt(
                    KEY_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL));
        }

        // if (mPrefs.contains(KEY_NAVBAR_STATE)) {
        //     Settings.System.putInt(mResolver,
        //             Settings.System.FORCE_SHOW_NAVBAR,
        //             mPrefs.getInt(KEY_NAVBAR_STATE, 1));
        // }
        //
        // if (mPrefs.contains(KEY_HW_KEYS_STATE)) {
        //     Settings.Secure.putInt(mResolver,
        //             Settings.Secure.HARDWARE_KEYS_DISABLE,
        //             mPrefs.getInt(KEY_HW_KEYS_STATE, 0));
        // }

        if (mPrefs.contains(KEY_NIGHT_LIGHT)) {
            mColorManager.setNightDisplayActivated(
                    mPrefs.getInt(KEY_NIGHT_LIGHT, 0) == 1);
            mColorManager.setNightDisplayAutoMode(
                    mPrefs.getInt(KEY_NIGHT_LIGHT_AUTO, 0));
        }

        if (mPrefs.contains(KEY_BATTERY_SAVER_MODE)) {
            final int prevMode = mPrefs.getInt(KEY_BATTERY_SAVER_MODE,
                    PowerManager.POWER_SAVE_MODE_TRIGGER_PERCENTAGE);
            final int prevLevel = mPrefs.getInt(KEY_BATTERY_SAVER_LEVEL, 0);
            Settings.Global.putInt(mResolver,
                    Settings.Global.LOW_POWER_MODE_TRIGGER_LEVEL, prevLevel);
            Settings.Global.putInt(mResolver,
                    Settings.Global.AUTOMATIC_POWER_SAVE_MODE, prevMode);
            mBatteryController.setPowerSaveMode(
                    mPrefs.getInt(KEY_BATTERY_SAVER, 0) == 1);
        }

        if (mPowerManagerInternal != null) {
            // disabling regardless of setting
            mPowerManagerInternal.setPowerMode(Mode.GAME, false);
        }

        if (mPrefs.contains(KEY_BLUETOOTH)) {
            mBluetoothController.setBluetoothEnabled(
                mPrefs.getInt(KEY_BLUETOOTH, 0) == 1);
        }

        if (mPrefs.contains(KEY_THREE_FINGER)) {
            Settings.System.putInt(mResolver,
                    Settings.System.GAMING_MODE_THREE_FINGER,
                    mPrefs.getInt(KEY_THREE_FINGER, 0));
        }

        if (mPrefs.contains(KEY_EXTRA_DIM)) {
            mColorManager.setReduceBrightColorsActivated(
                    mPrefs.getInt(KEY_EXTRA_DIM, 0) == 1);
            final int prevMode = mPrefs.getInt(KEY_EXTRA_DIM_SCHEDULE, 0);
            Settings.Secure.putInt(mResolver,
                    Settings.Secure.EXTRA_DIM_AUTO_MODE, prevMode);
        }

        if (mPrefs.contains(KEY_BRIGHTNESS_STATE)) {
            final int prevMode = mPrefs.getInt(KEY_BRIGHTNESS_STATE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            Settings.System.putInt(mResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE, prevMode);
            if (prevMode != Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    && mBrightnessLevel != 0) {
                mDisplayManager.setBrightness(mContext.getDisplayId(),
                        mPrefs.getInt(KEY_BRIGHTNESS_LEVEL, 0) / 100f);
            }
        }

        if (mPrefs.contains(KEY_MEDIA_LEVEL)) {
            final int max = mAudio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            final int prevVol = mPrefs.getInt(KEY_MEDIA_LEVEL, 80);
            mAudio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.round((float)max * (float)prevVol / 100f),
                    AudioManager.FLAG_SHOW_UI);
        }
    }

    public void setNotification(boolean show) {
        if (show) {
            final Resources res = mContext.getResources();
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    res.getString(R.string.gaming_mode_tile_title),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(res.getString(R.string.accessibility_quick_settings_gaming_mode_on));
            channel.enableVibration(false);
            mNm.createNotificationChannel(channel);

            Intent stopIntent = new Intent(mContext, GamingStopBroadcastReceiver.class);
            stopIntent.setAction(ACTION_STOP);
            stopIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            PendingIntent stopPI = PendingIntent.getBroadcast(mContext, mContext.getUserId(), stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Action stopAction = new Notification.Action.Builder(
                R.drawable.ic_qs_gaming_mode,
                res.getString(R.string.screenrecord_stop_label),
                stopPI
            ).build();
            if (!mStopRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(ACTION_STOP);
                mContext.registerReceiver(mStopBroadcastReceiver, filter,
                        Context.RECEIVER_EXPORTED);
                mStopRegistered = true;
            }

            PendingIntent contentPI = PendingIntent.getActivity(
                    mContext, 0, SETTINGS_INTENT, PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_qs_gaming_mode)
                    .setContentTitle(res.getString(R.string.gaming_mode_tile_title))
                    .setContentText(res.getString(R.string.gaming_mode_notification_content))
                    .setContentIntent(contentPI)
                    .setAutoCancel(false)
                    .setShowWhen(true)
                    .setOngoing(true)
                    .addAction(stopAction)
                    .build();
            mNm.notifyAsUser(null, NOTIFICATION_ID, notification, UserHandle.CURRENT);
        } else {
            mNm.cancelAsUser(null, NOTIFICATION_ID, UserHandle.CURRENT);
            if (mStopRegistered) {
                mContext.unregisterReceiver(mStopBroadcastReceiver);
                mStopRegistered = false;
            }
        }
    }

    private class ScreenBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                Settings.Global.putInt(mResolver, Settings.Global.GAMING_MACRO_ENABLED, 0);
            }
        }
    }

    private class BatteryBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())
                    && mPowerManager.isPowerSaveMode()) {
                Settings.Global.putInt(mResolver, Settings.Global.GAMING_MACRO_ENABLED, 0);
            }
        }
    }
}
