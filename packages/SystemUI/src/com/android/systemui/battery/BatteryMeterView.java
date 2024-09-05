/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.battery;

import static android.provider.Settings.System.SHOW_BATTERY_PERCENT;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT_CHARGING;
import static android.provider.Settings.System.QS_SHOW_BATTERY_ESTIMATE;
import static android.provider.Settings.System.STATUS_BAR_BATTERY_STYLE;
import static android.provider.Settings.System.SHOW_BATTERY_PERCENT_INSIDE;

import static com.android.settingslib.flags.Flags.newStatusBarIcons;
import static com.android.systemui.DejankUtils.whitelistIpcs;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.StyleRes;
import androidx.annotation.VisibleForTesting;

import com.android.app.animation.Interpolators;

import com.android.systemui.DualToneHandler;
import com.android.systemui.battery.unified.BatteryColors;
import com.android.systemui.battery.unified.BatteryDrawableState;
import com.android.systemui.battery.unified.BatteryLayersDrawable;
import com.android.systemui.battery.unified.ColorProfile;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.DarkIconDispatcher.DarkReceiver;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BatteryMeterView extends LinearLayout implements DarkReceiver {

    @Retention(SOURCE)
    @IntDef({MODE_DEFAULT, MODE_ON, MODE_OFF, MODE_ESTIMATE})
    public @interface BatteryPercentMode {}
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_ON = 1;
    public static final int MODE_OFF = 2;
    public static final int MODE_ESTIMATE = 3;

    static final String STATUS_BAR_BATTERY_STYLE =
            Settings.System.STATUS_BAR_BATTERY_STYLE;

    public static final int BATTERY_STYLE_PORTRAIT = 0;
    public static final int BATTERY_STYLE_CIRCLE = 1;
    public static final int BATTERY_STYLE_TEXT = 2;
    public static final int BATTERY_STYLE_LANDSCAPE = 3;
    public static final int BATTERY_STYLE_RLANDSCAPE = 4;

    private final CircleBatteryDrawable mCircleDrawable;
    private final LandscapeBatteryDrawable mLandscapeDrawable;
    private final RLandscapeBatteryDrawable mRLandscapeDrawable;
    private final AccessorizedBatteryDrawable mDrawable;
    private AccessorizedBatteryDrawable mThemedDrawable;
    private ImageView mBatteryIconView;
    private TextView mBatteryPercentView;

    private final HashMap<Integer, AccessorizedBatteryDrawable> mStyleMap;

    private final @StyleRes int mPercentageStyleId;
    private int mTextColor;
    private int mLevel;
    private int mShowPercentMode = MODE_DEFAULT;
    private String mEstimateText = null;
    private boolean mPluggedIn;
    private boolean mPowerSaveEnabled;
    private boolean mIsBatteryDefender;
    private boolean mIsIncompatibleCharging;
    private boolean mDisplayShieldEnabled;
    private boolean mPCharging;
    // Error state where we know nothing about the current battery state
    private boolean mBatteryStateUnknown;
    // Lazily-loaded since this is expected to be a rare-if-ever state
    private Drawable mUnknownStateDrawable;

    private int mBatteryStyle;
    private int mForegroundColor = Color.WHITE;
    private int mBackgroundColor = Color.WHITE;
    private int mSingleToneColor = Color.WHITE;

    private DualToneHandler mDualToneHandler;
    private boolean mIsStaticColor = false;

    private BatteryEstimateFetcher mBatteryEstimateFetcher;

    // for Flags.newStatusBarIcons. The unified battery icon can show percent inside
    @Nullable private BatteryLayersDrawable mUnifiedBattery;
    private BatteryColors mUnifiedBatteryColors = BatteryColors.LIGHT_THEME_COLORS;
    private BatteryDrawableState mUnifiedBatteryState =
            BatteryDrawableState.Companion.getDefaultInitialState();

    public BatteryMeterView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BatteryMeterView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.START);

        TypedArray atts = context.obtainStyledAttributes(attrs, R.styleable.BatteryMeterView,
                defStyle, 0);
        final int frameColor = atts.getColor(R.styleable.BatteryMeterView_frameColor,
                context.getColor(com.android.settingslib.R.color.meter_background_color));
        mPercentageStyleId = atts.getResourceId(R.styleable.BatteryMeterView_textAppearance, 0);
        atts.recycle();

        mCircleDrawable = new CircleBatteryDrawable(context, frameColor);
        mLandscapeDrawable = new LandscapeBatteryDrawable(context, frameColor);
        mRLandscapeDrawable = new RLandscapeBatteryDrawable(context, frameColor);
        mDrawable = new AccessorizedBatteryDrawable(context, frameColor);

        mStyleMap = new HashMap<>(Map.of(
            BATTERY_STYLE_PORTRAIT, mDrawable,
            BATTERY_STYLE_CIRCLE, mCircleDrawable,
            BATTERY_STYLE_LANDSCAPE, mLandscapeDrawable,
            BATTERY_STYLE_RLANDSCAPE, mRLandscapeDrawable
        ));

        setupLayoutTransition();

        mBatteryIconView = new ImageView(context);
        if (newStatusBarIcons()) {
            mUnifiedBattery = BatteryLayersDrawable.Companion
                    .newBatteryDrawable(context, mUnifiedBatteryState);
            mBatteryIconView.setImageDrawable(mUnifiedBattery);

            final MarginLayoutParams mlp = new MarginLayoutParams(
                    getResources().getDimensionPixelSize(
                            R.dimen.status_bar_battery_unified_icon_width),
                    getResources().getDimensionPixelSize(
                            R.dimen.status_bar_battery_unified_icon_height));
            addView(mBatteryIconView, mlp);
        } else {
            mBatteryIconView.setImageDrawable(mDrawable);
            final MarginLayoutParams mlp = new MarginLayoutParams(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
            mlp.setMargins(0, 0, 0,
                    getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
            addView(mBatteryIconView, mlp);
        }

        updateShowPercent();
        updateBatteryStyle();
        mDualToneHandler = new DualToneHandler(context);
        // Init to not dark at all.
        onDarkChanged(new ArrayList<Rect>(), 0, DarkIconDispatcher.DEFAULT_ICON_TINT);

        setClipChildren(false);
        setClipToPadding(false);
    }


    private void setBatteryDrawableState(BatteryDrawableState newState) {
        if (!newStatusBarIcons()) return;

        mUnifiedBatteryState = newState;
        mUnifiedBattery.setBatteryState(mUnifiedBatteryState);
    }

    private void setupLayoutTransition() {
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        // Animates appearing/disappearing of the battery percentage text using fade-in/fade-out
        // and disables all other animation types
        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

        transition.setAnimator(LayoutTransition.CHANGE_APPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, null);
        transition.setAnimator(LayoutTransition.CHANGING, null);

        setLayoutTransition(transition);
    }

    public void setForceShowPercent(boolean show) {
        setPercentShowMode(show ? MODE_ON : MODE_DEFAULT);
    }

    /**
     * Force a particular mode of showing percent
     *
     * 0 - No preference
     * 1 - Force on
     * 2 - Force off
     * 3 - Estimate
     * @param mode desired mode (none, on, off)
     */
    public void setPercentShowMode(@BatteryPercentMode int mode) {
        if (mode == mShowPercentMode) return;
        mShowPercentMode = mode;
        updateShowPercent();
        updatePercentText();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updatePercentView();
        if (mThemedDrawable != null)
            mThemedDrawable.notifyDensityChanged();
    }

    public void setColorsFromContext(Context context) {
        if (context == null) {
            return;
        }

        mDualToneHandler.setColorsFromContext(context);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    /**
     * Update battery level
     *
     * @param level     int between 0 and 100 (representing percentage value)
     * @param pluggedIn whether the device is plugged in or not
     */
    public void onBatteryLevelChanged(@IntRange(from = 0, to = 100) int level, boolean pluggedIn) {
        boolean wasCharging = isCharging();
        mPluggedIn = pluggedIn;
        mLevel = level;
        boolean isCharging = isCharging();
        if (mThemedDrawable != null) {
            mThemedDrawable.setCharging(pluggedIn);
            mThemedDrawable.setBatteryLevel(level);
        }
        updateShowPercent(); // required for % text only when charging
        updatePercentText();

        if (newStatusBarIcons()) {
            Drawable attr = mUnifiedBatteryState.getAttribution();
            if (isCharging != wasCharging) {
                attr = getBatteryAttribution(isCharging);
            }

            BatteryDrawableState newState =
                    new BatteryDrawableState(
                            level,
                            mUnifiedBatteryState.getShowPercent(),
                            getCurrentColorProfile(),
                            attr
                    );

            setBatteryDrawableState(newState);
        }
    }

    // Potentially reloads any attribution. Should not be called if the state hasn't changed
    @SuppressLint("UseCompatLoadingForDrawables")
    private Drawable getBatteryAttribution(boolean isCharging) {
        if (!newStatusBarIcons()) return null;

        int resId = 0;
        if (mPowerSaveEnabled) {
            resId = R.drawable.battery_unified_attr_powersave;
        } else if (mIsBatteryDefender && mDisplayShieldEnabled) {
            resId = R.drawable.battery_unified_attr_defend;
        } else if (isCharging) {
            resId = R.drawable.battery_unified_attr_charging;
        }

        Drawable attr = null;
        if (resId > 0) {
            attr = mContext.getDrawable(resId);
        }

        return attr;
    }

    /** Calculate the appropriate color for the current state */
    private ColorProfile getCurrentColorProfile() {
        return getColorProfile(
                mPowerSaveEnabled,
                mIsBatteryDefender && mDisplayShieldEnabled,
                mPluggedIn,
                mLevel <= 20);
    }

    /** pure function to compute the correct color profile for our battery icon */
    private ColorProfile getColorProfile(
            boolean isPowerSave,
            boolean isBatteryDefender,
            boolean isCharging,
            boolean isLowBattery
    ) {
        if (isCharging)  return ColorProfile.Active;
        if (isPowerSave) return ColorProfile.Warning;
        if (isBatteryDefender) return ColorProfile.None;
        if (isLowBattery) return ColorProfile.Error;

        return ColorProfile.None;
    }

    void onPowerSaveChanged(boolean isPowerSave) {
        if (isPowerSave == mPowerSaveEnabled) {
            return;
        }
        mPowerSaveEnabled = isPowerSave;
        if (!newStatusBarIcons()) {
            if (mThemedDrawable != null)
                mThemedDrawable.setPowerSaveEnabled(isPowerSave);
        } else {
            setBatteryDrawableState(
                    new BatteryDrawableState(
                            mUnifiedBatteryState.getLevel(),
                            mUnifiedBatteryState.getShowPercent(),
                            getCurrentColorProfile(),
                            getBatteryAttribution(isCharging())
                    )
            );
        }
    }

    void onIsBatteryDefenderChanged(boolean isBatteryDefender) {
        boolean valueChanged = mIsBatteryDefender != isBatteryDefender;
        mIsBatteryDefender = isBatteryDefender;

        if (!valueChanged) {
            return;
        }

        updateContentDescription();
        if (!newStatusBarIcons()) {
            // The battery drawable is a different size depending on whether it's currently
            // overheated or not, so we need to re-scale the view when overheated changes.
            scaleBatteryMeterViews();
        } else {
            setBatteryDrawableState(
                    new BatteryDrawableState(
                            mUnifiedBatteryState.getLevel(),
                            mUnifiedBatteryState.getShowPercent(),
                            getCurrentColorProfile(),
                            getBatteryAttribution(isCharging())
                    )
            );
        }
    }

    void onIsIncompatibleChargingChanged(boolean isIncompatibleCharging) {
        boolean valueChanged = mIsIncompatibleCharging != isIncompatibleCharging;
        mIsIncompatibleCharging = isIncompatibleCharging;
        if (valueChanged) {
            if (newStatusBarIcons()) {
                setBatteryDrawableState(
                        new BatteryDrawableState(
                                mUnifiedBatteryState.getLevel(),
                                mUnifiedBatteryState.getShowPercent(),
                                getCurrentColorProfile(),
                                getBatteryAttribution(isCharging())
                        )
                );
            } else if (mThemedDrawable != null) {
                mThemedDrawable.setCharging(isCharging());
            }
            updateContentDescription();
        }
    }

    private TextView inflatePercentView() {
        return (TextView) LayoutInflater.from(getContext())
                .inflate(R.layout.battery_percentage_view, null);
    }

    private void addPercentView(TextView inflatedPercentView) {
        mBatteryPercentView = inflatedPercentView;

        if (mPercentageStyleId != 0) { // Only set if specified as attribute
            mBatteryPercentView.setTextAppearance(mPercentageStyleId);
        }
        float fontHeight = mBatteryPercentView.getPaint().getFontMetricsInt(null);
        mBatteryPercentView.setLineHeight(TypedValue.COMPLEX_UNIT_PX, fontHeight);
        if (mTextColor != 0) mBatteryPercentView.setTextColor(mTextColor);
        addView(mBatteryPercentView, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                (int) Math.ceil(fontHeight)));
    }

    /**
     * Updates percent view by removing old one and reinflating if necessary
     */
    public void updatePercentView() {
        if (mBatteryPercentView != null) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
        updateShowPercent();
    }

    /**
     * Sets the fetcher that should be used to get the estimated time remaining for the user's
     * battery.
     */
    void setBatteryEstimateFetcher(BatteryEstimateFetcher fetcher) {
        mBatteryEstimateFetcher = fetcher;
    }

    void setDisplayShieldEnabled(boolean displayShieldEnabled) {
        mDisplayShieldEnabled = displayShieldEnabled;
    }

    void updatePercentText() {
        if (!newStatusBarIcons()) {
            updatePercentTextLegacy();
            return;
        }

        // The unified battery can show the percent inside, so we only need to handle
        // the estimated time remaining case
        if (mShowPercentMode == MODE_ESTIMATE
                && mBatteryEstimateFetcher != null
                && !isCharging()
        ) {
            mBatteryEstimateFetcher.fetchBatteryTimeRemainingEstimate(
                    (String estimate) -> {
                        if (mBatteryPercentView == null) {
                            // Similar to the legacy behavior, inflate and add the view. We will
                            // only use it for the estimate text
                            addPercentView(inflatePercentView());
                        }
                        if (estimate != null && mShowPercentMode == MODE_ESTIMATE) {
                            mEstimateText = estimate;
                            mBatteryPercentView.setText(estimate);
                            updateContentDescription();
                        } else {
                            mEstimateText = null;
                            mBatteryPercentView.setText(null);
                            updateContentDescription();
                        }
                    });
        } else {
            if (mBatteryPercentView != null) {
                mEstimateText = null;
                mBatteryPercentView.setText(null);
            }
            updateContentDescription();
        }
    }

    void updatePercentTextLegacy() {
        if (mBatteryStateUnknown) {
            return;
        }

        if (mBatteryEstimateFetcher == null) {
            setPercentTextAtCurrentLevel();
            return;
        }

        final boolean userShowEstimate = Settings.System.getIntForUser(
                getContext().getContentResolver(), QS_SHOW_BATTERY_ESTIMATE,
                1, UserHandle.USER_CURRENT) == 1;

        if (mBatteryPercentView != null) {
            if (mShowPercentMode == MODE_ESTIMATE && !isCharging() && userShowEstimate) {
                mBatteryEstimateFetcher.fetchBatteryTimeRemainingEstimate(
                        (String estimate) -> {
                    if (mBatteryPercentView == null) {
                        return;
                    }
                    if (estimate != null && mShowPercentMode == MODE_ESTIMATE) {
                        mEstimateText = estimate;
                        mBatteryPercentView.setText(estimate);
                        updateContentDescription();
                    } else {
                        setPercentTextAtCurrentLevel();
                    }
                });
            } else {
                setPercentTextAtCurrentLevel();
            }
        } else {
            updateContentDescription();
        }
    }

    private void setPercentTextAtCurrentLevel() {
        if (mBatteryPercentView != null) {
            mEstimateText = null;
            String percentText = NumberFormat.getPercentInstance().format(mLevel / 100f);
            // Setting text actually triggers a layout pass (because the text view is set to
            // wrap_content width and TextView always relayouts for this). Avoid needless
            // relayout if the text didn't actually change.
            if (!TextUtils.equals(mBatteryPercentView.getText(), percentText) || mPCharging != isCharging()) {
                mPCharging = isCharging();
                // Use the high voltage symbol ⚡ (u26A1 unicode) but prevent the system
                // to load its emoji colored variant with the uFE0E flag
                // only use it when there is no batt icon showing
                String indication = isCharging() && (mBatteryStyle == BATTERY_STYLE_TEXT)
                        ? "\u26A1\uFE0E " : "";
                mBatteryPercentView.setText(indication + percentText);
            }
        }

        updateContentDescription();
    }

    private void updateContentDescription() {
        Context context = getContext();

        String contentDescription;
        if (mBatteryStateUnknown) {
            contentDescription = context.getString(R.string.accessibility_battery_unknown);
        } else if (mShowPercentMode == MODE_ESTIMATE && !TextUtils.isEmpty(mEstimateText)) {
            contentDescription = context.getString(
                    mIsBatteryDefender
                            ? R.string.accessibility_battery_level_charging_paused_with_estimate
                            : R.string.accessibility_battery_level_with_estimate,
                    mLevel,
                    mEstimateText);
        } else if (mIsBatteryDefender) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging_paused, mLevel);
        } else if (isCharging()) {
            contentDescription =
                    context.getString(R.string.accessibility_battery_level_charging, mLevel);
        } else {
            contentDescription = context.getString(R.string.accessibility_battery_level, mLevel);
        }

        setContentDescription(contentDescription);
    }

    void updateShowPercent() {
        if (!newStatusBarIcons()) {
            updateShowPercentLegacy();
            return;
        }

        if (mUnifiedBatteryState == null) return;

        boolean shouldShow = mShowPercentMode == MODE_ON || mShowPercentMode == MODE_ESTIMATE;
        if (!mBatteryStateUnknown && !shouldShow && (mShowPercentMode != MODE_OFF)) {
            // Slow case: fall back to the system setting
            // TODO(b/140051051)
            shouldShow = 0 != whitelistIpcs(() -> Settings.System
                    .getIntForUser(getContext().getContentResolver(),
                    SHOW_BATTERY_PERCENT, getContext().getResources().getBoolean(
                    com.android.internal.R.bool.config_defaultBatteryPercentageSetting)
                    ? 1 : 0, UserHandle.USER_CURRENT));
        }

        setBatteryDrawableState(
                new BatteryDrawableState(
                        mUnifiedBatteryState.getLevel(),
                        shouldShow,
                        mUnifiedBatteryState.getColor(),
                        mUnifiedBatteryState.getAttribution()
                )
        );

        // The legacy impl used the percent view for the estimate and the percent text. The modern
        // version only uses it for estimate. It can be safely removed here
        if (mShowPercentMode != MODE_ESTIMATE) {
            removeView(mBatteryPercentView);
            mBatteryPercentView = null;
        }
    }

    private void updateShowPercentLegacy() {
        final ContentResolver resolver = getContext().getContentResolver();
        final boolean showing = mBatteryPercentView != null;

        // user settings
        final boolean showBatteryPercent = Settings.System.getIntForUser(
                resolver, SHOW_BATTERY_PERCENT, 0, UserHandle.USER_CURRENT) == 1;
        final boolean userDrawPercentInside = Settings.System.getIntForUser(
                resolver, SHOW_BATTERY_PERCENT_INSIDE, 0, UserHandle.USER_CURRENT) == 1;
        final boolean showBatteryPercentCharging = Settings.System.getIntForUser(
                resolver, SHOW_BATTERY_PERCENT_CHARGING, 0, UserHandle.USER_CURRENT) == 1;

        // some boolean algebra, don't freak out
        final boolean chargeForcePercent = showBatteryPercentCharging && isCharging();
        final boolean drawPercent = mShowPercentMode == MODE_DEFAULT
                && (showBatteryPercent || chargeForcePercent);
        final boolean isEstimate = mShowPercentMode == MODE_ESTIMATE;
        final boolean isText = mBatteryStyle == BATTERY_STYLE_TEXT;
        final boolean drawInside = drawPercent && userDrawPercentInside;

        // always draw when we show estimate or in text mode
        // don't show if we're set to draw inside or we disabled % entirely
        if (isEstimate || isText || (drawPercent && (!drawInside || chargeForcePercent))) {
            // draw next to the icon
            if (mThemedDrawable != null)
                mThemedDrawable.setShowPercent(false);
            if (!showing) {
                addPercentView(inflatePercentView());
                updatePercentText();
            }
            int paddingStart = getResources().getDimensionPixelSize(
                    R.dimen.battery_level_padding_start);
            mBatteryPercentView.setPaddingRelative(isText ? 0 : paddingStart, 0, 0, 0);
        } else {
            // maybe draw inside
            if (mThemedDrawable != null)
                mThemedDrawable.setShowPercent(drawInside);
            if (showing) {
                // remove the percentage view
                removeView(mBatteryPercentView);
                mBatteryPercentView = null;
            }
        }
    }

    private Drawable getUnknownStateDrawable() {
        if (mUnknownStateDrawable == null) {
            mUnknownStateDrawable = mContext.getDrawable(R.drawable.ic_battery_unknown);
            mUnknownStateDrawable.setTint(mTextColor);
        }

        return mUnknownStateDrawable;
    }

    void onBatteryUnknownStateChanged(boolean isUnknown) {
        if (mBatteryStateUnknown == isUnknown) {
            return;
        }

        mBatteryStateUnknown = isUnknown;
        updateContentDescription();

        if (mBatteryStateUnknown) {
            mBatteryIconView.setImageDrawable(getUnknownStateDrawable());
        } else {
            updateBatteryStyle();
        }

        updateShowPercent();
    }

    void scaleBatteryMeterViews() {
        if (!newStatusBarIcons()) {
            scaleBatteryMeterViewsLegacy();
            return;
        }

        // For simplicity's sake, copy the general pattern in the legacy method and use the new
        // resources, excluding what we don't need
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        float mainBatteryHeight =
                res.getDimensionPixelSize(
                        R.dimen.status_bar_battery_unified_icon_height) * iconScaleFactor;
        float mainBatteryWidth =
                res.getDimensionPixelSize(
                        R.dimen.status_bar_battery_unified_icon_width) * iconScaleFactor;

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                Math.round(mainBatteryWidth),
                Math.round(mainBatteryHeight));

        mBatteryIconView.setLayoutParams(scaledLayoutParams);
        mBatteryIconView.invalidateDrawable(mUnifiedBattery);
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    void scaleBatteryMeterViewsLegacy() {
        if (mBatteryIconView == null) return;
        Resources res = getContext().getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        float mainBatteryHeight =
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height) * iconScaleFactor;
        float mainBatteryWidth = (
                res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width)) * iconScaleFactor;

        if (mBatteryStyle == BATTERY_STYLE_CIRCLE) {
            mainBatteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_circle_width)
                    * iconScaleFactor;
        } else if (mBatteryStyle == BATTERY_STYLE_LANDSCAPE || mBatteryStyle == BATTERY_STYLE_RLANDSCAPE) {
            mainBatteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height_landscape)
                    * iconScaleFactor;
            mainBatteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width_landscape)
                    * iconScaleFactor;
        }

        boolean displayShield = mDisplayShieldEnabled && mIsBatteryDefender;
        float fullBatteryIconHeight =
                BatterySpecs.getFullBatteryHeight(mainBatteryHeight, displayShield);
        float fullBatteryIconWidth =
                BatterySpecs.getFullBatteryWidth(mainBatteryWidth, displayShield);

        int marginTop;
        if (displayShield) {
            // If the shield is displayed, we need some extra marginTop so that the bottom of the
            // main icon is still aligned with the bottom of all the other system icons.
            int shieldHeightAddition = Math.round(fullBatteryIconHeight - mainBatteryHeight);
            // However, the other system icons have some embedded bottom padding that the battery
            // doesn't have, so we shouldn't move the battery icon down by the full amount.
            // See b/258672854.
            marginTop = shieldHeightAddition
                    - res.getDimensionPixelSize(R.dimen.status_bar_battery_extra_vertical_spacing);
        } else {
            marginTop = 0;
        }

        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                Math.round(fullBatteryIconWidth),
                Math.round(fullBatteryIconHeight));
        scaledLayoutParams.setMargins(0, marginTop, 0, marginBottom);

        if (mThemedDrawable != null) {
            mThemedDrawable.setDisplayShield(displayShield);
            mBatteryIconView.setLayoutParams(scaledLayoutParams);
            mBatteryIconView.invalidateDrawable(mThemedDrawable);
        }
    }

    void updateBatteryStyle() {
        mBatteryStyle = Settings.System.getIntForUser(
                getContext().getContentResolver(), STATUS_BAR_BATTERY_STYLE,
                BATTERY_STYLE_PORTRAIT, UserHandle.USER_CURRENT);

        if (mBatteryIconView != null) {
            removeView(mBatteryIconView);
            mBatteryIconView = null;
        }

        final AccessorizedBatteryDrawable style = mStyleMap.get(mBatteryStyle);
        mThemedDrawable = style;
        if (style != null) {
            mBatteryIconView = new ImageView(getContext());
            mBatteryIconView.setImageDrawable(style);
            final MarginLayoutParams mlp = new MarginLayoutParams(
                    getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_width),
                    getResources().getDimensionPixelSize(R.dimen.status_bar_battery_icon_height));
            mlp.setMargins(0, 0, 0,
                    getResources().getDimensionPixelOffset(R.dimen.battery_margin_bottom));
            addView(mBatteryIconView, 0, mlp);
            scaleBatteryMeterViews();
            updateColors(mForegroundColor, mBackgroundColor, mSingleToneColor);
            onBatteryLevelChanged(mLevel, mPluggedIn);
            onPowerSaveChanged(mPowerSaveEnabled);
        }
        updateShowPercent();
        updatePercentText();
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        if (mIsStaticColor) return;

        if (!newStatusBarIcons()) {
            onDarkChangedLegacy(areas, darkIntensity, tint);
            return;
        }

        if (mUnifiedBattery == null) {
            return;
        }

        if (DarkIconDispatcher.isInAreas(areas, this)) {
            if (darkIntensity < 0.5) {
                mUnifiedBatteryColors = BatteryColors.DARK_THEME_COLORS;
            } else {
                mUnifiedBatteryColors = BatteryColors.LIGHT_THEME_COLORS;
            }

            mUnifiedBattery.setColors(mUnifiedBatteryColors);
        } else  {
            // Same behavior as the legacy code when not isInArea
            mUnifiedBatteryColors = BatteryColors.DARK_THEME_COLORS;
            mUnifiedBattery.setColors(mUnifiedBatteryColors);
        }
    }

    private void onDarkChangedLegacy(ArrayList<Rect> areas, float darkIntensity, int tint) {
        float intensity = DarkIconDispatcher.isInAreas(areas, this) ? darkIntensity : 0;
        int nonAdaptedSingleToneColor = mDualToneHandler.getSingleColor(intensity);
        int nonAdaptedForegroundColor = mDualToneHandler.getFillColor(intensity);
        int nonAdaptedBackgroundColor = mDualToneHandler.getBackgroundColor(intensity);

        updateColors(nonAdaptedForegroundColor, nonAdaptedBackgroundColor,
                nonAdaptedSingleToneColor);
    }

    public void setStaticColor(boolean isStaticColor) {
        mIsStaticColor = isStaticColor;
    }

    /**
     * Sets icon and text colors. This will be overridden by {@code onDarkChanged} events,
     * if registered.
     *
     * @param foregroundColor
     * @param backgroundColor
     * @param singleToneColor
     */
    public void updateColors(int foregroundColor, int backgroundColor, int singleToneColor) {
        mForegroundColor = foregroundColor;
        mBackgroundColor = backgroundColor;
        mSingleToneColor = singleToneColor;

        if (mThemedDrawable != null)
            mThemedDrawable.setColors(foregroundColor, backgroundColor, singleToneColor);
        mTextColor = singleToneColor;
        if (mBatteryPercentView != null) {
            mBatteryPercentView.setTextColor(singleToneColor);
        }

        if (mUnknownStateDrawable != null) {
            mUnknownStateDrawable.setTint(singleToneColor);
        }
    }

    /** For newStatusBarIcons(), we use a BatteryColors object to declare the theme */
    public void setUnifiedBatteryColors(BatteryColors colors) {
        if (!newStatusBarIcons()) return;

        mUnifiedBatteryColors = colors;
        mUnifiedBattery.setColors(mUnifiedBatteryColors);
    }

    @VisibleForTesting
    boolean isCharging() {
        return mPluggedIn && !mIsIncompatibleCharging;
    }

    public void dump(PrintWriter pw, String[] args) {
        String powerSave = mThemedDrawable == null ? null : mThemedDrawable.getPowerSaveEnabled() + "";
        String displayShield = mThemedDrawable == null ? null : mThemedDrawable.getDisplayShield() + "";
        String charging = mThemedDrawable == null ? null : mThemedDrawable.getCharging() + "";
        CharSequence percent = mBatteryPercentView == null ? null : mBatteryPercentView.getText();
        pw.println("  BatteryMeterView:");
        pw.println("    mThemedDrawable.getPowerSave: " + powerSave);
        pw.println("    mThemedDrawable.getDisplayShield: " + displayShield);
        pw.println("    mThemedDrawable.getCharging: " + charging);
        pw.println("    mBatteryPercentView.getText(): " + percent);
        pw.println("    mTextColor: #" + Integer.toHexString(mTextColor));
        pw.println("    mBatteryStateUnknown: " + mBatteryStateUnknown);
        pw.println("    mIsIncompatibleCharging: " + mIsIncompatibleCharging);
        pw.println("    mPluggedIn: " + mPluggedIn);
        pw.println("    mLevel: " + mLevel);
        pw.println("    mMode: " + mShowPercentMode);
        if (newStatusBarIcons()) {
            pw.println("    mUnifiedBatteryState: " + mUnifiedBatteryState);
        }
    }

    @VisibleForTesting
    CharSequence getBatteryPercentViewText() {
        return mBatteryPercentView.getText();
    }

    @VisibleForTesting
    TextView getBatteryPercentView() {
        return mBatteryPercentView;
    }

    @VisibleForTesting
    BatteryDrawableState getUnifiedBatteryState() {
        return mUnifiedBatteryState;
    }

    /** An interface that will fetch the estimated time remaining for the user's battery. */
    public interface BatteryEstimateFetcher {
        void fetchBatteryTimeRemainingEstimate(
                BatteryController.EstimateFetchCompletion completion);
    }
}
