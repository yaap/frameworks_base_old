/*
 * Copyright (C) 2020-2024 crDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settingslib.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.util.PathParser

import com.android.settingslib.R

/**
 * A battery meter drawable that respects paths configured in
 * frameworks/base/core/res/res/values/config.xml to allow for an easily overrideable battery icon
 */
open class RLandscapeBatteryDrawable(context: Context, frameColor: Int) :
        LandscapeBatteryDrawable(context, frameColor) {

    override fun drawLevelRect(fillFraction: Float) {
        val fillTop =
                if (getBatteryLevel() >= 95)
                    fillRect.right
                else
                    fillRect.right - (fillRect.width() * (1 - fillFraction))

        levelRect.right = Math.floor(fillTop.toDouble()).toFloat()
        //levelPath.addRect(levelRect, Path.Direction.CCW)
        levelPath.addRoundRect(levelRect,
        floatArrayOf(2.0f,
                     2.0f,
                     2.0f,
                     2.0f,
                     2.0f,
                     2.0f,
                     2.0f,
                     2.0f), Path.Direction.CCW)
    }

    override fun drawClipedRectDual(c: Canvas, fillFraction: Float) {
        c.clipRect(
                bounds.left.toFloat(),
                0f,
                bounds.right + bounds.width() * fillFraction,
                bounds.left.toFloat())
    }

    override fun drawClipedRect(c: Canvas, fillFraction: Float) {
        c.clipRect(fillRect.left,
                fillRect.top ,
                fillRect.right - (fillRect.width() * (1 - fillFraction)),
                fillRect.bottom)
    }

    override fun getPathString(): String {
        return context.resources.getString(
                com.android.internal.R.string.config_batterymeterRLandPerimeterPath)
    }

    override fun getErrorPathString(): String {
        return context.resources.getString(
                com.android.internal.R.string.config_batterymeterRLandErrorPerimeterPath)
    }

    override fun getFillMaskString(): String {
        return context.resources.getString(
                com.android.internal.R.string.config_batterymeterRLandFillMask)
    }

    override fun getBoltPathString(): String {
        return context.resources.getString(
                com.android.internal.R.string.config_batterymeterRLandBoltPath)
    }

    override fun getPlusPathString(): String {
        return context.resources.getString(
                com.android.internal.R.string.config_batterymeterRLandPowersavePath)
    }

    override fun getTextXRatio(): Float {
        return 0.8f
    }

    override fun getTag(): String {
        return TAG;
    }

    companion object {
        private const val TAG = "RLandscapeBatteryDrawable"
    }
}
