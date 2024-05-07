/*
 * Copyright (C) 2018 FireHound
 *               2022-2023 Yet Another AOSP Project
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

package com.android.systemui.qs.tiles;

import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/** Quick settings tile: Gaming Mode tile **/
public class GamingModeTile extends QSTileImpl<BooleanState> {
    public static final String TILE_SPEC = "gaming";

    private static final String KEY_DIALOG_SHOWN = "gaming_mode_dialog_shown";
    private static final Intent SETTINGS_INTENT = new Intent("com.android.settings.GAMING_MODE_SETTINGS");

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_qs_gaming_mode);
    private final CustomObserver mObserver = new CustomObserver();
    private final SharedPreferences mPrefs;

    private boolean mIsSelfChange = false;

    @Inject
    public GamingModeTile(QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BroadcastDispatcher broadcastDispatcher,
            KeyguardStateController keyguardStateController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mPrefs = mContext.getSharedPreferences(mContext.getPackageName(), Context.MODE_PRIVATE);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void handleClick(@Nullable View view) {
        if (!mPrefs.getBoolean(KEY_DIALOG_SHOWN, false)) {
            showGamingModeWhatsThisDialog();
            return;
        }
        final boolean newState = !mState.value;
        mIsSelfChange = true;
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.GAMING_MACRO_ENABLED, newState ? 1 : 0);
        refreshState(newState);
    }

    @Override
    public Intent getLongClickIntent() {
        // no need to show it to the user if they already did this
        mPrefs.edit().putBoolean(KEY_DIALOG_SHOWN, true).apply();
        return SETTINGS_INTENT;
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean enable = state.value;
        if (arg instanceof Boolean) {
            enable = (Boolean) arg;
        } else {
            enable = isEnabled();
        }
        state.icon = mIcon;
        state.value = enable;
        state.label = mContext.getString(R.string.gaming_mode_tile_title);
        if (enable) {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_on);
            state.state = Tile.STATE_ACTIVE;
        } else {
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_gaming_mode_off);
            state.state = Tile.STATE_INACTIVE;
        }
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.gaming_mode_tile_title);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.YASP;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            mObserver.observe();
            return;
        }
        mObserver.stop();
    }

    private void showGamingModeWhatsThisDialog() {
        SystemUIDialog dialog = new SystemUIDialog(mContext);
        dialog.setTitle(R.string.gaming_mode_dialog_title);
        dialog.setMessage(R.string.gaming_mode_dialog_message);
        dialog.setPositiveButton(com.android.internal.R.string.ok,
                (DialogInterface.OnClickListener) (dialog1, which) ->
                    mPrefs.edit().putBoolean(KEY_DIALOG_SHOWN, true).apply());
        dialog.setShowForAllUsers(true);
        dialog.show();
    }

    private boolean isEnabled() {
        return Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.GAMING_MACRO_ENABLED, 0) == 1;
    }

    private class CustomObserver extends ContentObserver {
        CustomObserver() {
            super(mHandler);
        }

        void observe() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.GAMING_MACRO_ENABLED),
                    false, this, UserHandle.USER_ALL);
        }

        void stop() {
            mContext.getContentResolver().unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mIsSelfChange) {
                mIsSelfChange = false;
                return;
            }
            if (selfChange) return;
            refreshState(isEnabled());
        }
    }
}
