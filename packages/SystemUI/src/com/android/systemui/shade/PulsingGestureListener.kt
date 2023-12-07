/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

import android.content.Context
import android.graphics.Point
import android.hardware.display.AmbientDisplayConfiguration
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dock.DockManager
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.domain.interactor.DozeInteractor
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.FalsingManager.LOW_PENALTY
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.settings.UserTracker
import com.android.systemui.tuner.TunerService
import com.android.systemui.tuner.TunerService.Tunable
import java.io.PrintWriter
import javax.inject.Inject

/**
 * If tap and/or double tap to wake is enabled, this gestureListener will wake the display on
 * tap/double tap when the device is pulsing (AoD2) or transitioning to AoD. Taps are gated by the
 * proximity sensor and falsing manager.
 *
 * Touches go through the [NotificationShadeWindowViewController] when the device is dozing but the
 * screen is still ON and not in the true AoD display state. When the device is in the true AoD
 * display state, wake-ups are handled by [com.android.systemui.doze.DozeSensors].
 */
@SysUISingleton
class PulsingGestureListener @Inject constructor(
        private val context: Context,
        private val falsingManager: FalsingManager,
        private val dockManager: DockManager,
        private val powerInteractor: PowerInteractor,
        private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
        private val statusBarStateController: StatusBarStateController,
        private val shadeLogger: ShadeLogger,
        private val dozeInteractor: DozeInteractor,
        userTracker: UserTracker,
        tunerService: TunerService,
        dumpManager: DumpManager
) : GestureDetector.SimpleOnGestureListener(), Dumpable {
    private val vibrator: Vibrator
    private var doubleTapEnabled = false
    private var singleTapEnabled = false
    private var doubleTapEnabledNative = false
    private var singleTapAmbientEnabled = false
    private var doubleTapAmbientEnabled = false
    private var singleTapAmbientAllowed = true
    private var doubleTapAmbientAllowed = true
    private var doubleTapVibrate = false
    private var singleTapVibrate = false

    init {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val tunable = Tunable { key: String?, _: String? ->
            when (key) {
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE ->
                    doubleTapEnabled = ambientDisplayConfiguration.doubleTapGestureEnabled(
                            userTracker.userId)
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE ->
                    singleTapEnabled = ambientDisplayConfiguration.tapGestureEnabled(
                            userTracker.userId)
                Settings.Secure.DOUBLE_TAP_TO_WAKE ->
                    doubleTapEnabledNative = Settings.Secure.getIntForUser(
                            context.getContentResolver(),
                            Settings.Secure.DOUBLE_TAP_TO_WAKE, 0, userTracker.userId) == 1
                Settings.Secure.DOZE_TAP_GESTURE_AMBIENT ->
                    singleTapAmbientEnabled = ambientDisplayConfiguration.tapGestureAmbient(
                            userTracker.userId)
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE_AMBIENT ->
                    doubleTapAmbientEnabled = ambientDisplayConfiguration.doubleTapGestureAmbient(
                            userTracker.userId)
                Settings.Secure.DOZE_TAP_GESTURE_ALLOW_AMBIENT ->
                    singleTapAmbientAllowed = ambientDisplayConfiguration.tapGestureOnAmbient(
                            userTracker.userId)
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE_ALLOW_AMBIENT ->
                    doubleTapAmbientAllowed = ambientDisplayConfiguration.doubleTapGestureOnAmbient(
                            userTracker.userId)
                Settings.Secure.DOZE_TAP_GESTURE_VIBRATE ->
                    doubleTapVibrate = ambientDisplayConfiguration.tapGestureVibrate(
                            userTracker.userId)
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE_VIBRATE ->
                    singleTapVibrate = ambientDisplayConfiguration.doubleTapGestureVibrate(
                            userTracker.userId)
            }
        }
        tunerService.addTunable(tunable,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                Settings.Secure.DOZE_TAP_GESTURE_AMBIENT,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE_AMBIENT,
                Settings.Secure.DOZE_TAP_GESTURE_ALLOW_AMBIENT,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE_ALLOW_AMBIENT)

        dumpManager.registerDumpable(this)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val isNotDocked = !dockManager.isDocked
        shadeLogger.logSingleTapUp(statusBarStateController.isDozing, singleTapEnabled, isNotDocked)
        if (statusBarStateController.isDozing && singleTapEnabled && isNotDocked
                && !singleTapAmbientEnabled && singleTapAmbientAllowed) {
            val proximityIsNotNear = !falsingManager.isProximityNear
            val isNotAFalseTap = !falsingManager.isFalseTap(LOW_PENALTY)
            shadeLogger.logSingleTapUpFalsingState(proximityIsNotNear, isNotAFalseTap)
            if (proximityIsNotNear && isNotAFalseTap) {
                shadeLogger.d("Single tap handled, requesting centralSurfaces.wakeUpIfDozing")
                if (singleTapVibrate) wakeVibrate()
                dozeInteractor.setLastTapToWakePosition(Point(e.x.toInt(), e.y.toInt()))
                powerInteractor.wakeUpIfDozing("PULSING_SINGLE_TAP", PowerManager.WAKE_REASON_TAP)
            }
            return true
        }
        shadeLogger.d("onSingleTapUp event ignored")
        return false
    }

    /**
     * Receives [MotionEvent.ACTION_DOWN], [MotionEvent.ACTION_MOVE], and [MotionEvent.ACTION_UP]
     * motion events for a double tap.
     */
    override fun onDoubleTapEvent(e: MotionEvent): Boolean {
        // React to the [MotionEvent.ACTION_UP] event after double tap is detected. Falsing
        // checks MUST be on the ACTION_UP event.
        if (e.actionMasked == MotionEvent.ACTION_UP &&
                statusBarStateController.isDozing &&
                (doubleTapEnabled || singleTapEnabled || doubleTapEnabledNative) &&
                !doubleTapAmbientEnabled && doubleTapAmbientAllowed &&
                !falsingManager.isProximityNear &&
                !falsingManager.isFalseDoubleTap
        ) {
            if (doubleTapVibrate) wakeVibrate()
            powerInteractor.wakeUpIfDozing("PULSING_DOUBLE_TAP", PowerManager.WAKE_REASON_TAP)
            return true
        }
        return false
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("singleTapEnabled=$singleTapEnabled")
        pw.println("doubleTapEnabled=$doubleTapEnabled")
        pw.println("isDocked=${dockManager.isDocked}")
        pw.println("isProxCovered=${falsingManager.isProximityNear}")
    }

    private fun wakeVibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        var effect = VibrationEffect.createWaveform(longArrayOf(0, 100), -1)
        if (vibrator.areAllEffectsSupported(VibrationEffect.EFFECT_CLICK) ==
                Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
            effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        vibrator.vibrate(effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK))
    }
}
