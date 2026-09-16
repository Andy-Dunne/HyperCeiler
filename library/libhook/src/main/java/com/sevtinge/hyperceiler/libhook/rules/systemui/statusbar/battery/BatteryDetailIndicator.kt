/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 */

package com.sevtinge.hyperceiler.libhook.rules.systemui.statusbar.battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.sevtinge.hyperceiler.common.log.XposedLog
import com.sevtinge.hyperceiler.common.utils.PrefsBridge
import com.sevtinge.hyperceiler.libhook.base.BaseHook
import com.sevtinge.hyperceiler.libhook.utils.hookapi.blur.MiBlurUtils
import com.sevtinge.hyperceiler.libhook.utils.api.DisplayUtils.dp2px
import io.github.lingqiqi5211.ezhooktool.core.callMethod
import io.github.lingqiqi5211.ezhooktool.core.callStaticMethod
import io.github.lingqiqi5211.ezhooktool.core.java.Constructors
import io.github.lingqiqi5211.ezhooktool.core.loadClassOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createAfterHooks
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.createBeforeHooks
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getIntField
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectField
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNull
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.getObjectFieldOrNullAs
import io.github.lingqiqi5211.ezhooktool.xposed.dsl.setObjectField
import java.io.FileInputStream
import java.util.Locale
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

object BatteryDetailIndicator : BaseHook() {

    private const val HOOK_TAG = "BatteryDetailIndicator"
    private const val SLOT_NAME = "battery_info"
    private const val SLOT_NETWORK_SPEED = "network_speed"
    private const val ICON_TYPE = 91
    private const val MSG_DATA_UPDATE = 100021
    private const val MSG_WORKER_TICK = 200021

    private const val DIAG_LOG = false

    private const val DIAG_LOG_TICK = false

    private const val STRONG_RESET_ON_TICK = false

    private const val DIAG_FORCE_OUTPUT = true

    private fun diagLog(msg: String) {
        if (DIAG_FORCE_OUTPUT) {
            XposedLog.e(HOOK_TAG, lpparam.packageName, msg)
        } else {
            XposedLog.d(HOOK_TAG, lpparam.packageName, msg)
        }
    }

    private val RESET_DELAYS = longArrayOf(0L, 120L, 320L, 700L, 1500L)

    private const val SCHEDULE_COOLDOWN_MS = 1000L

    private const val RESET_COOLDOWN_MS = 300L

    private const val VISIBLE_STATE_BURST_LIMIT = 30

    private const val RESET_ON_VISIBLE_STATE = false

    private const val REQUEST_LAYOUT_ON_VISIBLE_STATE = false

    private const val ISLAND_EVENT_COOLDOWN_MS = 800L

    private const val PROBE_LINE_CAP = 60

    private const val DUMP_COOLDOWN_MS = 5000L

    private const val ISLAND_FAST_POLL_MS = 200L

    private const val ISLAND_FAST_POLL_MAX_MS = 30000L

    private const val CLEAR_OWN_BLUR = false

    private const val MIRROR_REFERENCE_MATERIAL = false

    private const val REATTACH_ON_SETTLE = false

    private const val FORCE_WINDOW_REDRAW_ON_SETTLE = false
    private const val TAG_SLOT_TEXT_ICON = "slot_text_icon"
    private const val TAG_NETWORK_SPEED_NUMBER = "network_speed_number"
    private const val TAG_NETWORK_SPEED_UNIT = "network_speed_unit"
    private const val FIELD_CONTAINER = "mContainer"
    private const val FIELD_NETWORK_SPEED_NUMBER_TEXT = "mNetworkSpeedNumberText"
    private const val FIELD_NETWORK_SPEED_UNIT_TEXT = "mNetworkSpeedUnitText"
    private const val FIELD_VISIBLE_BY_CONTROLLER = "mVisibleByController"
    private const val FIELD_TYPE = "type"
    private const val FIELD_M_TYPE = "mType"
    private const val FIELD_M_CONTEXT = "mContext"
    private const val FIELD_M_GROUP = "mGroup"
    private const val FIELD_M_CLOCK_VIEW = "mClockView"
    private const val FIELD_S_BATTERY_STATUS = "sBatteryStatus"

    private const val METHOD_SET_VISIBILITY_BY_CONTROLLER = "setVisibilityByController"
    private const val METHOD_SET_ICON = "setIcon"
    private const val METHOD_GET_SLOT_INDEX = "getSlotIndex"
    private const val METHOD_ON_CREATE_LAYOUT_PARAMS = "onCreateLayoutParams"
    private const val METHOD_SET_BLOCKED = "setBlocked"
    private const val METHOD_SET_NETWORK_SPEED = "setNetworkSpeed"
    private const val METHOD_IS_CHARGING = "isCharging"
    private const val METHOD_ADD_DARK_RECEIVER = "addDarkReceiver"

    private const val CLS_FOCUS_NOTIF_PROMPT_CONTROLLER = "com.android.systemui.statusbar.phone.FocusedNotifPromptController"
    private const val CLS_FOCUS_NOTIF_PROMPT_VIEW = "com.android.systemui.statusbar.phone.FocusedNotifPromptView"
    private const val CLS_MIUI_COLLAPSED_STATUS_BAR = "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment"
    private const val CLS_RECENTS_PROXY_NEW = "com.android.systemui.recents.LauncherProxyService"
    private const val CLS_RECENTS_PROXY_OLD = "com.android.systemui.recents.OverviewProxyService"
    private const val METHOD_NOTIFY_NOTIF_BEAN_CHANGED = "notifyNotifBeanChanged"
    private const val METHOD_SET_DATA = "setData"
    private const val METHOD_UPDATE_STATUS_BAR_VISIBILITIES = "updateStatusBarVisibilities"
    private const val METHOD_ON_FOCUSED_NOTIF_UPDATE = "onFocusedNotifUpdate"

    private const val PKG_SYSTEMUI = "com.android.systemui"
    private const val PROP_POWER_SUPPLY_TEMP = "POWER_SUPPLY_TEMP"
    private const val PROP_POWER_SUPPLY_CURRENT_NOW = "POWER_SUPPLY_CURRENT_NOW"
    private const val PROP_POWER_SUPPLY_VOLTAGE_NOW = "POWER_SUPPLY_VOLTAGE_NOW"
    private const val PROP_POWER_SUPPLY_STATUS = "POWER_SUPPLY_STATUS"
    private const val BATTERY_UEVENT_PATH = "/sys/class/power_supply/battery/uevent"
    private const val ID_CLOCK = "clock"
    private const val STYLE_NETWORK_SPEED_NUMBER = "TextAppearance.StatusBar.NetWorkSpeedNumber"
    private const val STYLE_CLOCK = "TextAppearance.StatusBar.Clock"
    private const val FONT_MIPRO_BOLD = "mipro-bold"
    private const val FONT_MIPRO_MEDIUM = "mipro-medium"
    private const val FONT_MISANS = "misans"
    private const val UNIT_CELSIUS = "℃"
    private const val UNIT_WATT = "W"
    private const val UNIT_MA = "mA"
    private const val UNIT_A = "A"

    private val isBatteryAtRight by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_at_right")
    }
    private val content by lazy {
        PrefsBridge.getStringAsInt("system_ui_statusbar_battery_detail_content", 1)
    }
    private val hideUnit by lazy {
        PrefsBridge.getStringAsInt("system_ui_statusbar_battery_detail_hide_unit", 0)
    }
    private val tempDecimal by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_temp_decimal")
    }
    private val positive by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_positive")
    }
    private val fixCurrentRatio by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_fix_current_ratio")
    }
    private val singleRow by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_single_row")
    }
    private val reverseOrder by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_reverse_order")
    }
    private val inCharge by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_in_charge")
    }
    private val updateSpacing by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_update_spacing", 2).coerceIn(1, 10)
    }
    private val fontSize by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_font_size", 16)
    }
    private val bold by lazy {
        PrefsBridge.getBoolean("system_ui_statusbar_battery_detail_bold")
    }
    private val align by lazy {
        PrefsBridge.getStringAsInt("system_ui_statusbar_battery_detail_align", 1)
    }
    private val fixedWidth by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_fixed_width", 10)
    }
    private val leftMargin by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_left_margin", 8)
    }
    private val rightMargin by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_right_margin", 8)
    }
    private val verticalOffset by lazy {
        PrefsBridge.getInt("system_ui_statusbar_battery_detail_vertical_offset", 8)
    }

    private val textIconTagId = getFakeResId("battery_text_icon_tag")
    private val mStatusbarTextIcons = CopyOnWriteArrayList<View>()

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var mainHandler: Handler? = null

    private var resetting = false
    private var resetCooldownUntil = 0L
    private var scheduleCooldownUntil = 0L
    private var burstWindowStart = 0L
    private var burstCount = 0
    private var lastBurstStackAt = 0L

    private var islandRootRef: java.lang.ref.WeakReference<View>? = null
    private var islandWindowTitle: String = ""
    private var lastIslandVisible = false
    private var lastIslandEventAt = 0L
    private var probeLinesLogged = 0
    private var lastDumpAt = 0L
    private var islandFastPolling = false
    private data class TextIconInfo(
        var iconShow: Boolean = true,
        var iconText: String = ""
    )

    override fun init() {
        val nsvCls = loadClassOrNull("com.android.systemui.statusbar.views.NetworkSpeedView", lpparam.classLoader)
        if (nsvCls == null) {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "NetworkSpeedView class not found")
            return
        }

        setupNetworkSpeedViewHooks(nsvCls)

        if (isBatteryAtRight) {
            RightSideHookHelper.setup(nsvCls)
        } else {
            LeftSideHookHelper.setup(nsvCls)
        }

        startDataCollection()
        setupIslandMonitor()
        setupHotReloadCleanup()
    }

    private fun setupHotReloadCleanup() {
        registerHotReloadCleanup {
            workerHandler?.removeCallbacksAndMessages(null)
            workerThread?.quitSafely()
            workerThread = null
            workerHandler = null
            mainHandler?.removeCallbacksAndMessages(null)
            mainHandler = null
            mStatusbarTextIcons.clear()
        }
    }

    private fun setupNetworkSpeedViewHooks(nsvCls: Class<*>) {
        runCatching {
            nsvCls.declaredMethods.filter { it.name == "getSlot" && it.parameterCount == 0 }.createBeforeHooks { param ->
                val nsView = param.thisObject as? View
                if (nsView != null && ViewHelper.isCustomTextIcon(nsView)) {
                    param.result = SLOT_NAME
                }
            }
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook NetworkSpeedView.getSlot: ${it.message}")
        }

        runCatching {
            nsvCls.declaredMethods.filter { it.name == "setVisibleState" }.createBeforeHooks { param ->
                val nsView = param.thisObject as? View
                if (nsView != null && ViewHelper.isCustomTextIcon(nsView)) {
                    val state = param.args.getOrNull(0) as? Int ?: 0
                    val visible = state != 2

                    val number = nsView.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT)
                        ?: (nsView as? TextView)
                    val unit = nsView.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_UNIT_TEXT)

                    val v = if (visible) View.VISIBLE else View.GONE
                    number?.visibility = v
                    unit?.visibility = v
                    nsView.visibility = v

                    noteVisibleStateBurst()

                    nsView.invalidate()
                    if (REQUEST_LAYOUT_ON_VISIBLE_STATE) nsView.requestLayout()

                    if (RESET_ON_VISIBLE_STATE) requestRenderReset("setVisibleState")
                }
            }
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook NetworkSpeedView.setVisibleState: ${it.message}")
        }

        runCatching {
            nsvCls.declaredMethods.filter {
                it.name in listOf("onDensityOrFontScaleChanged", "onMiuiThemeChanged") || it.name.startsWith("updateResources")
            }.createAfterHooks { param ->
                val nsView = param.thisObject as? View
                if (nsView != null && ViewHelper.isCustomTextIcon(nsView)) {
                    val lp = nsView.layoutParams ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    ViewHelper.initStatusbarTextIcon(nsView.context, lp, nsView, false)

                    resetRenderState(nsView, strong = true, reason = "resourcesChanged")
                }
            }
        }

        runCatching {
            nsvCls.declaredMethods.filter {
                it.name in listOf("onDarkChanged", "onLightDarkTintChanged", "onDarkChangedWithContrast")
            }.createAfterHooks { param ->
                val nsView = param.thisObject as? View
                if (nsView != null && ViewHelper.isCustomTextIcon(nsView)) {
                    syncColorWithClock(nsView)
                }
            }
        }
    }

    private fun setupIslandMonitor() {
        hookIslandWindowCreation()
    }

    private fun hookIslandWindowCreation() {
        runCatching {
            val vri = loadClassOrNull("android.view.ViewRootImpl", lpparam.classLoader) ?: return
            vri.declaredMethods.filter { it.name == "setView" }.createBeforeHooks { param ->
                runCatching {
                    val args = param.args
                    val attrs = args.firstOrNull {
                        it != null && it.javaClass.name.endsWith("WindowManager.LayoutParams")
                    }
                    val title = (runCatching { attrs?.callMethod("getTitle") }.getOrNull()
                        ?: runCatching { attrs?.getObjectFieldOrNull("title") }.getOrNull())?.toString() ?: ""
                    val views = args.filterIsInstance<View>()
                    val root = views.firstOrNull { it.javaClass.name.contains("island", ignoreCase = true) }
                        ?: views.firstOrNull().takeIf { title.contains("island", ignoreCase = true) }
                        ?: return@runCatching
                    islandRootRef = java.lang.ref.WeakReference(root)
                    islandWindowTitle = title
                    diagLog("island window attached: $title root=" + root.javaClass.simpleName)
                }
            }
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "hook ViewRootImpl.setView failed: " + it.message)
        }
    }

    private fun pollIslandState() {
        val root = islandRootRef?.get() ?: return
        if (checkIslandVisible(root) == lastIslandVisible) return
        lastIslandVisible = !lastIslandVisible
        onIslandStateChanged(lastIslandVisible)
        if (lastIslandVisible) startIslandFastPoll()
    }

    private fun checkIslandVisible(root: View): Boolean =
        runCatching { root.isShown && root.visibility == View.VISIBLE }.getOrDefault(false)

    private fun startIslandFastPoll() {
        if (islandFastPolling) return
        islandFastPolling = true
        var elapsed = 0L
        val step = object : Runnable {
            override fun run() {
                elapsed += ISLAND_FAST_POLL_MS
                val root = islandRootRef?.get()
                val visible = root != null && checkIslandVisible(root)
                if (visible != lastIslandVisible) {
                    lastIslandVisible = visible
                    onIslandStateChanged(visible)
                }
                if (elapsed < ISLAND_FAST_POLL_MAX_MS && lastIslandVisible) {
                    mainHandler?.postDelayed(this, ISLAND_FAST_POLL_MS)
                } else {
                    islandFastPolling = false
                }
            }
        }
        mainHandler?.postDelayed(step, ISLAND_FAST_POLL_MS)
    }

    private fun onIslandStateChanged(visible: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastIslandEventAt < ISLAND_EVENT_COOLDOWN_MS) return
        lastIslandEventAt = now
        val reason = if (visible) "island.show" else "island.hide"
        if (DIAG_LOG) {
            diagLog("island state changed: visible=$visible title=$islandWindowTitle")
            dumpIslandAndOurs()
        }
        scheduleRenderReset(reason)
        islandRepair(reason, settle = true)
    }

    private fun shownIndicator(): View? = mStatusbarTextIcons.firstOrNull {
        it.isAttachedToWindow && runCatching { it.isShown }.getOrDefault(false)
    }

    private fun dumpIslandAndOurs() {
        if (probeLinesLogged >= PROBE_LINE_CAP) return
        val ours = shownIndicator() ?: return
        islandRootRef?.get()?.let { island ->
            val loc = IntArray(2)
            runCatching { island.getLocationOnScreen(loc) }
            diagLog(
                "  island root=" + island.javaClass.simpleName +
                    " onScreen=[" + loc[0] + "," + loc[1] + "," + (loc[0] + island.width) + "," + (loc[1] + island.height) + "]" +
                    " vis=" + island.visibility +
                    " shown=" + runCatching { island.isShown }.getOrDefault(false)
            )
            probeLinesLogged += 2
            diagLog(materialProbe(island, "island"))
            probeLinesLogged += 6
        }
        diagLog(materialProbe(ours, "ours@" + hostName(ours)))
        probeLinesLogged += 6
        findClockOf(ours)?.let {
            diagLog(materialProbe(it, "clock-ref"))
            probeLinesLogged += 6
        }
    }

    private fun islandRepair(reason: String, settle: Boolean) {
        if (!settle) return
        val view = shownIndicator() ?: return
        if (CLEAR_OWN_BLUR) clearOwnBlur(view, reason)
        if (MIRROR_REFERENCE_MATERIAL) mirrorReferenceMaterial(view, reason)
        if (REATTACH_ON_SETTLE) reattachIndicator(view, reason)
        if (FORCE_WINDOW_REDRAW_ON_SETTLE) forceWindowRedraw(view, reason)
    }

    private fun clearOwnBlur(view: View, reason: String) {
        runCatching {
            MiBlurUtils.clearContainerPassBlur(view)
            MiBlurUtils.clearMemberBlendColor(view)
            miCall(view, "removeBackgroundBlurDrawable")
            miCall(view, "setSelfBlurRadius", 0f)
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "clearOwnBlur failed(" + reason + "): " + it.message)
        }
    }

    private fun mirrorReferenceMaterial(view: View, reason: String) {
        val clock = findClockOf(view) ?: return
        val mode = miInt(clock, "getMiViewBlurMode")
        val bgMode = miInt(clock, "getMiBackgroundBlurMode")
        val pass = miBool(clock, "getPassWindowBlurEnabled")
        val color = runCatching { clock.currentTextColor }.getOrDefault(android.graphics.Color.WHITE)
        miCall(view, "setMiViewBlurMode", mode ?: 0)
        miCall(view, "setMiBackgroundBlurMode", bgMode ?: 0)
        miCall(view, "setPassWindowBlurEnabled", pass ?: false)
        runCatching { MiBlurUtils.setMemberBlendColor(view, false, color) }
        diagLog("mirror(" + reason + "): clock mode=" + mode + " bgMode=" + bgMode + " pass=" + pass)
    }

    private fun reattachIndicator(view: View, reason: String) {
        val parent = view.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view)
        if (index < 0) return
        val lp = view.layoutParams
        runCatching {
            parent.removeView(view)
            parent.addView(view, index.coerceAtMost(parent.childCount), lp)
            diagLog("reattach(" + reason + "): host=" + hostName(view))
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "reattach failed(" + reason + "): " + it.message)
        }
    }

    private fun forceWindowRedraw(view: View, reason: String) {
        runCatching {
            val root = view.rootView
            root.invalidate()
            (root as? ViewGroup)?.requestLayout()
            diagLog("windowRedraw(" + reason + "): root=" + root.javaClass.simpleName)
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "forceWindowRedraw failed(" + reason + "): " + it.message)
        }
    }

    private fun materialProbe(view: View, label: String): String {
        val sb = StringBuilder("  material[" + label + "]")
        var current: View? = view
        var depth = 0
        while (current != null && depth < 6) {
            val cur = current
            sb.append("\n    ").append(if (depth == 0) "self" else "anc" + depth)
                .append(' ').append(cur.javaClass.simpleName)
                .append(" blurMode=").append(miInt(cur, "getMiViewBlurMode"))
                .append(" bgMode=").append(miInt(cur, "getMiBackgroundBlurMode"))
                .append(" bgRadius=").append(miInt(cur, "getMiBackgroundBlurRadius"))
                .append(" blurRatio=").append(miFloat(cur, "getMiBackgroundBlurScaleRatio"))
                .append(" passBlur=").append(miBool(cur, "getPassWindowBlurEnabled"))
                .append(" selfBlur=").append(miFloat(cur, "getSelfBlurRadius"))
                .append(" layer=").append(cur.layerType)
            if (cur is ViewGroup && cur.childCount > 0) sb.append(" children=").append(cur.childCount)
            current = cur.parent as? View
            depth++
        }
        return sb.toString()
    }

    private fun findClockOf(view: View): TextView? {
        val container = view.parent as? ViewGroup ?: return null
        val clockId = container.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
        return if (clockId != 0) container.findViewById(clockId) else null
    }

    private fun miInt(view: View, name: String): Int? = runCatching { view.callMethod(name) as? Int }.getOrNull()

    private fun miBool(view: View, name: String): Boolean? =
        runCatching { view.callMethod(name) as? Boolean }.getOrNull()

    private fun miFloat(view: View, name: String): Float? =
        runCatching { view.callMethod(name) as? Float }.getOrNull()

    private fun miCall(view: View, name: String, vararg args: Any?): Boolean =
        runCatching { view.callMethod(name, *args) }.isSuccess

    private fun resetRenderState(view: View, strong: Boolean, reason: String) {
        runCatching {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.invalidate()

            val number = view.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT) ?: (view as? TextView)
            if (number != null) {
                number.setLayerType(View.LAYER_TYPE_NONE, null)
                number.alpha = 1f
                number.scaleX = 1f
                number.scaleY = 1f
                number.translationX = 0f
                number.translationY = 0f
                if (strong) {

                    number.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    number.invalidate()
                    number.setLayerType(View.LAYER_TYPE_NONE, null)
                }
                number.invalidate()
            }

            if (strong) {
                var parent: View? = view.parent as? View
                var depth = 0
                while (parent != null && depth < 3) {
                    parent.invalidate()
                    parent = parent.parent as? View
                    depth++
                }
            }
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "resetRenderState failed($reason): ${it.message}")
        }

    }

    private fun resetAllIcons(reason: String, strong: Boolean) {
        pruneTrackedIcons(reason)
        var skipped = 0
        val summary = StringBuilder()
        for (view in mStatusbarTextIcons) {
            if (!view.isAttachedToWindow) {
                skipped++
                continue
            }
            resetRenderState(view, strong, reason)
            if (DIAG_LOG) summary.append(resetSummaryLine(view))
        }
        if (DIAG_LOG && (summary.isNotEmpty() || skipped > 0)) {
            diagLog(
                "reset($reason,strong=$strong) attached=${mStatusbarTextIcons.size - skipped} " +
                    "skipped=$skipped$summary"
            )
        }
    }

    private fun resetSummaryLine(view: View): String {
        val number = view.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT) ?: (view as? TextView)
        val tv = if (number != null) {
            " tv[layer=${number.layerType},alpha=${number.alpha},scale=${number.scaleX},${number.scaleY},trans=${number.translationX},${number.translationY}]"
        } else {
            ""
        }
        return "\n    ${hostName(view)}[vis=${view.visibility},shown=${runCatching { view.isShown }.getOrDefault(false)}" +
            ",layer=${view.layerType},alpha=${view.alpha},scale=${view.scaleX},${view.scaleY}" +
            ",trans=${view.translationX},${view.translationY}]$tv"
    }

    private fun requestRenderReset(reason: String, strong: Boolean = true) {
        if (resetting) return
        val now = SystemClock.uptimeMillis()
        if (now < resetCooldownUntil) return
        resetCooldownUntil = now + RESET_COOLDOWN_MS
        resetting = true
        try {
            resetAllIcons(reason, strong)
        } finally {
            resetting = false
        }
    }

    private fun noteVisibleStateBurst() {
        val now = SystemClock.uptimeMillis()
        if (now - burstWindowStart > 1000L) {
            burstWindowStart = now
            burstCount = 0
        }
        burstCount++
        if (burstCount == VISIBLE_STATE_BURST_LIMIT) {
            diagLog("!! setVisibleState 调用频率 > $VISIBLE_STATE_BURST_LIMIT 次/秒（疑似回调自激），已停止在该入口做复位")
        }
        if (burstCount >= VISIBLE_STATE_BURST_LIMIT && now - lastBurstStackAt > 5000L) {
            lastBurstStackAt = now
            val frames = Log.getStackTraceString(Throwable()).split('\n').take(16).joinToString("\n")
            diagLog("---- setVisibleState 调用栈（截断 16 帧）----\n$frames")
        }
    }

    private fun scheduleRenderReset(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (resetting || now < scheduleCooldownUntil) return
        scheduleCooldownUntil = now + SCHEDULE_COOLDOWN_MS
        val handler = mainHandler
        if (handler == null) {
            requestRenderReset("$reason:sync")
            return
        }
        for (delay in RESET_DELAYS) {
            handler.postDelayed({

                val strong = delay == 0L || delay >= 700L
                requestRenderReset("$reason+${delay}ms", strong)

                islandRepair(reason, settle = delay >= 700L)
                if (delay >= 700L) dumpIcons("$reason+${delay}ms")
            }, delay)
        }
    }

    private fun pruneTrackedIcons(reason: String) {
        if (mStatusbarTextIcons.isEmpty()) return
        var removed = 0
        for (view in mStatusbarTextIcons) {
            if (view.parent == null && mStatusbarTextIcons.remove(view)) {
                removed++
            }
        }
        if (DIAG_LOG && removed > 0) {
            diagLog("prune($reason) removed=$removed left=${mStatusbarTextIcons.size}")
        }
    }

    private fun dedupeInContainer(container: ViewGroup, keep: View?, reason: String) {
        if (container.childCount <= 1) return
        val duplicates = ArrayList<View>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child !== keep && ViewHelper.isCustomTextIcon(child)) {
                duplicates.add(child)
            }
        }
        if (duplicates.isEmpty()) return
        for (child in duplicates) {

            val snapshot = if (DIAG_LOG) describeView(child) else null
            runCatching { container.removeView(child) }
            mStatusbarTextIcons.remove(child)
            if (snapshot != null) {
                diagLog("dedupe($reason) removed duplicate: $snapshot")
            }
        }
    }

    private fun dedupeAll(reason: String) {
        for (view in mStatusbarTextIcons) {
            if (!view.isAttachedToWindow) continue
            val parent = view.parent as? ViewGroup ?: continue
            dedupeInContainer(parent, view, reason)
        }
    }

    private fun dumpIcons(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDumpAt < DUMP_COOLDOWN_MS) return
        lastDumpAt = now
        if (!DIAG_LOG) return
        val tracked = mStatusbarTextIcons.size
        val attached = mStatusbarTextIcons.count { it.isAttachedToWindow }
        diagLog("==== dump[$reason] tracked=$tracked attached=$attached ====")
        for (view in mStatusbarTextIcons) {
            diagLog("  tracked: ${describeView(view)}")
        }
        val root = mStatusbarTextIcons.firstOrNull { it.isAttachedToWindow }?.rootView
        if (root != null) {
            val found = ArrayList<View>()
            collectTaggedIcons(root, found)
            diagLog("  taggedInRoot=${found.size} root=${root.javaClass.name}")
            found.forEachIndexed { index, view ->
                diagLog("    #$index ${describeView(view)}")
            }
        }
        diagLog("==== dump end[$reason] ====")
    }

    private fun describeView(view: View): String = buildString {
        append("cls=").append(view.javaClass.simpleName)
        append('@').append(java.lang.Integer.toHexString(java.lang.System.identityHashCode(view)))
        append(" vis=").append(view.visibility)
        append(" attached=").append(view.isAttachedToWindow)
        append(" shown=").append(runCatching { view.isShown }.getOrDefault(false))
        append(" layer=").append(view.layerType)
        append(" alpha=").append(view.alpha)
        append(" scale=").append(view.scaleX).append(',').append(view.scaleY)
        append(" trans=").append(view.translationX).append(',').append(view.translationY)
        append(" bounds=[").append(view.left).append(',').append(view.top).append(',')
        append(view.right).append(',').append(view.bottom).append(']')
        append(" size=").append(view.width).append('x').append(view.height)
        append(" host=").append(hostName(view))
        append(" parent=").append(parentChain(view))
        append(' ').append(miuiBlurState(view))
    }

    private fun parentChain(view: View, depth: Int = 4): String {
        val sb = StringBuilder()
        var current: View? = view.parent as? View
        var level = 0
        while (current != null && level < depth) {
            if (level > 0) sb.append(" < ")
            sb.append(current.javaClass.simpleName)
            current = current.parent as? View
            level++
        }
        return sb.toString()
    }

    private fun hostName(view: View): String {
        var current: View? = view.parent as? View
        var last = view.javaClass.simpleName
        var level = 0
        while (current != null && level < 10) {
            last = current.javaClass.simpleName
            current = current.parent as? View
            level++
        }
        return last
    }

    private fun miuiBlurState(view: View): String {
        val viewMode = runCatching { view.callMethod("getMiViewBlurMode") as? Int }.getOrNull()
        val bgMode = runCatching { view.callMethod("getMiBackgroundBlurMode") as? Int }.getOrNull()
        val passWindow = runCatching { view.callMethod("getPassWindowBlurEnabled") as? Boolean }.getOrNull()
        return "miBlur(viewMode=$viewMode,bgMode=$bgMode,passWindow=$passWindow)"
    }

    private fun collectTaggedIcons(view: View, out: MutableList<View>) {
        if (ViewHelper.isCustomTextIcon(view)) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTaggedIcons(view.getChildAt(i), out)
            }
        }
    }

    private fun syncColorWithClock(iconView: View, clockView: TextView? = null) {
        val clock = clockView
            ?: (iconView.parent as? ViewGroup)?.let { container ->
                val clockId = container.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                if (clockId != 0) container.findViewById<TextView>(clockId) else null
            }
        val number = iconView.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT)
            ?: (iconView as? TextView)
        if (number != null && clock != null) {
            val colors = clock.textColors
            if (colors != null) {
                number.setTextColor(colors)
            }
        }
    }

    private fun startDataCollection() {
        mainHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == MSG_DATA_UPDATE) {
                    val tii = msg.obj as? TextIconInfo
                    if (tii != null) {
                        updateStatusbarViews(tii)
                    }
                }
            }
        }

        val thread = HandlerThread("BatteryDetailWorker").apply { start() }
        workerThread = thread
        val handler = BatteryWorkerHandler(thread.looper)
        workerHandler = handler
        handler.sendEmptyMessage(MSG_WORKER_TICK)
    }

    private fun updateStatusbarViews(tii: TextIconInfo) {

        pollIslandState()

        pruneTrackedIcons("tick")
        dedupeAll("tick")

        for (tv in mStatusbarTextIcons) {
            runCatching { tv.callMethod(METHOD_SET_VISIBILITY_BY_CONTROLLER, tii.iconShow) }
                .onFailure { tv.visibility = if (tii.iconShow) View.VISIBLE else View.GONE }
            if (tii.iconShow) {
                runCatching { tv.callMethod(METHOD_SET_NETWORK_SPEED, tii.iconText, "") }
                    .onFailure {
                        val number = tv.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT)
                            ?: (tv as? TextView)
                        number?.text = tii.iconText
                    }
                syncColorWithClock(tv)
            }

            if (tv.isAttachedToWindow) {
                resetRenderState(tv, strong = STRONG_RESET_ON_TICK, reason = "tick")
            }
        }

        if (DIAG_LOG_TICK) dumpIcons("tick")
    }

    private object RightSideHookHelper {
        fun setup(nsvCls: Class<*>) {
            setupStatusBarIconList()
            setupNetworkSpeedController()
            setupStatusBarIconControllerImpl()
            setupIconManager(nsvCls)
        }

        private fun setupStatusBarIconList() {
            val sbiListCls = loadClassOrNull("com.android.systemui.statusbar.phone.ui.StatusBarIconList", lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.StatusBarIconList", lpparam.classLoader)
                ?: return

            runCatching {
                Constructors.find(sbiListCls).filter { it.parameterTypes.size == 1 && it.parameterTypes[0] == Array<String>::class.java }
                    .toList().createBeforeHooks { param ->
                        @Suppress("UNCHECKED_CAST")
                        val slots = param.args[0] as? Array<String>
                        if (slots != null) {
                            val slotList = ArrayList(slots.toList())
                            if (!slotList.contains(SLOT_NAME)) {
                                val netSpeedIndex = slotList.indexOf(SLOT_NETWORK_SPEED)
                                if (netSpeedIndex >= 0) {
                                    slotList.add(netSpeedIndex + 1, SLOT_NAME)
                                } else {
                                    slotList.add(SLOT_NAME)
                                }
                                param.args[0] = slotList.toTypedArray()
                            }
                        }
                    }
            }.onFailure {
                XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook StatusBarIconList constructor: ${it.message}")
            }
        }

        private fun setupNetworkSpeedController() {
            val nscCls = loadClassOrNull("com.android.systemui.statusbar.policy.NetworkSpeedController", lpparam.classLoader) ?: return
            runCatching {
                Constructors.find(nscCls).toList().createAfterHooks { param ->
                    val iconController = param.thisObject.getObjectFieldOrNull("mStatusBarIconController")
                    if (iconController != null) {
                        registerIconToController(iconController)
                    }
                }
            }.onFailure {
                XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook NetworkSpeedController constructor: ${it.message}")
            }
        }

        private fun registerIconToController(iconController: Any) {
            runCatching {
                iconController.callMethod(METHOD_SET_ICON, null, SLOT_NAME, 0)
            }.onFailure {
                runCatching {
                    val slotIndex = iconController.callMethod(METHOD_GET_SLOT_INDEX, SLOT_NAME) as? Int ?: 0
                    val sbHolderCls = loadClassOrNull("com.android.systemui.statusbar.phone.StatusBarIconHolder", lpparam.classLoader)
                    if (sbHolderCls != null) {
                        val holder = Constructors.find(sbHolderCls).toList().firstOrNull()?.newInstance()
                        if (holder != null) {
                            runCatching { holder.setObjectField(FIELD_TYPE, ICON_TYPE) }
                            runCatching { holder.setObjectField(FIELD_M_TYPE, ICON_TYPE) }
                            iconController.callMethod(METHOD_SET_ICON, slotIndex, holder)
                        }
                    }
                }
            }
        }

        private fun setupStatusBarIconControllerImpl() {
            val sbicImplCls = loadClassOrNull("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl", lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.StatusBarIconControllerImpl", lpparam.classLoader)
                ?: return

            runCatching {
                sbicImplCls.declaredMethods.filter { it.name == METHOD_SET_ICON && it.parameterCount == 2 }.createBeforeHooks { param ->
                    val slotName = param.args[0] as? String
                    if (slotName == SLOT_NAME) {
                        val iconHolder = param.args[1]
                        if (iconHolder != null) {
                            runCatching { iconHolder.setObjectField(FIELD_TYPE, ICON_TYPE) }
                            runCatching { iconHolder.setObjectField(FIELD_M_TYPE, ICON_TYPE) }
                        }
                    }
                }
            }.onFailure {
                XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook StatusBarIconControllerImpl.setIcon: ${it.message}")
            }
        }

        private fun setupIconManager(nsvCls: Class<*>) {
            val iconManagerCls = loadClassOrNull("com.android.systemui.statusbar.phone.ui.IconManager", lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.ui.IconManager", lpparam.classLoader)
                ?: return

            runCatching {
                iconManagerCls.declaredMethods.filter { it.name == "addHolder" && it.parameterCount == 4 }.createBeforeHooks { param ->
                    val iconHolder = param.args[3]
                    if (iconHolder != null) {
                        val type = runCatching { iconHolder.getIntField(FIELD_TYPE) }
                            .getOrElse { runCatching { iconHolder.getIntField(FIELD_M_TYPE) }.getOrDefault(-1) }
                        if (type == ICON_TYPE) {
                            handleIconManagerAddHolder(nsvCls, param)
                        }
                    }
                }
            }.onFailure {
                XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook IconManager.addHolder: ${it.message}")
            }
        }

        private fun handleIconManagerAddHolder(nsvCls: Class<*>, param: io.github.lingqiqi5211.ezhooktool.xposed.common.HookParam) {
            val mContext = param.thisObject.getObjectField(FIELD_M_CONTEXT) as Context
            val lp = runCatching { param.thisObject.callMethod(METHOD_ON_CREATE_LAYOUT_PARAMS) as LinearLayout.LayoutParams }
                .getOrElse { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT) }
            val mGroup = param.thisObject.getObjectField(FIELD_M_GROUP) as ViewGroup
            val existing = mGroup.findViewWithTag<View>(TAG_SLOT_TEXT_ICON)
            if (existing != null) {
                if (!mStatusbarTextIcons.contains(existing)) {
                    mStatusbarTextIcons.add(existing)
                }

                dedupeInContainer(mGroup, existing, "right.existing")
                param.result = existing
            } else {
                val iconView = ViewHelper.createStatusbarTextIcon(nsvCls, mContext, lp, true)
                val index = (param.args[0] as? Int ?: 0).coerceAtLeast(0).coerceAtMost(mGroup.childCount)
                mGroup.addView(iconView, index)
                mStatusbarTextIcons.add(iconView)
                dedupeInContainer(mGroup, iconView, "right.new")
                param.result = iconView
            }
            if (DIAG_LOG) dumpIcons("right.addHolder")
        }
    }

    private object LeftSideHookHelper {
        fun setup(nsvCls: Class<*>) {
            setupCollapsedStatusBar(nsvCls)
            setupStatusBarViewController(nsvCls)
            setupSystemIconAreaVisibility()
        }

        private fun setupCollapsedStatusBar(nsvCls: Class<*>) {
            val mcsbFragmentCls = loadClassOrNull(CLS_MIUI_COLLAPSED_STATUS_BAR, lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.CollapsedStatusBarFragment", lpparam.classLoader)
                ?: return

            runCatching {
                mcsbFragmentCls.declaredMethods.filter {
                    (it.name == "initMiuiViewsOnViewCreated" || it.name == "onViewCreated") && it.parameterCount in 1..2
                }.createAfterHooks { param ->
                    val mContext = runCatching { param.thisObject.callMethod("getContext") as? Context }.getOrNull()
                        ?: (param.args[0] as? View)?.context
                    if (mContext != null) {
                        injectToCollapsedStatusBar(nsvCls, param.thisObject, param.args[0] as? View, mContext)
                    }
                }
            }.onFailure {
                XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook MiuiCollapsedStatusBarFragment onViewCreated: ${it.message}")
            }
        }

        private fun setupStatusBarViewController(nsvCls: Class<*>) {
            val controllerCls = loadClassOrNull("com.android.systemui.statusbar.phone.PhoneStatusBarViewController", lpparam.classLoader)
            if (controllerCls != null) {
                runCatching {
                    controllerCls.declaredMethods.filter { it.name == "onViewAttached" && it.parameterCount == 0 }.createAfterHooks { param ->
                        val controller = param.thisObject
                        val clockView = controller.getObjectFieldOrNullAs<View>("clock")
                            ?: controller.getObjectFieldOrNullAs<View>(FIELD_M_CLOCK_VIEW)
                        val startSideContainer = controller.getObjectFieldOrNullAs<ViewGroup>("startSideContainer")
                        val darkDispatcher = controller.getObjectFieldOrNull("darkIconDispatcher")
                        val context = clockView?.context ?: startSideContainer?.context
                        if (context != null) {
                            injectToContainer(nsvCls, context, clockView, startSideContainer, darkDispatcher)
                        }
                    }
                }.onFailure {
                    XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook PhoneStatusBarViewController.onViewAttached: ${it.message}")
                }
            }

            val miuiStatusBarViewCls = loadClassOrNull("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.PhoneStatusBarView", lpparam.classLoader)
            if (miuiStatusBarViewCls != null) {
                runCatching {
                    miuiStatusBarViewCls.declaredMethods.filter {
                        (it.name == "onFinishInflate" || it.name == "onAttachedToWindow") && it.parameterCount == 0
                    }.createAfterHooks { param ->
                        val view = param.thisObject as? ViewGroup
                        if (view != null) {
                            val clockId = view.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                            val clockView = if (clockId != 0) view.findViewById<View>(clockId) else null
                            val container = (clockView?.parent as? ViewGroup)
                            if (container != null) {
                                injectToContainer(nsvCls, view.context, clockView, container, null)
                            }
                        }
                    }
                }.onFailure {
                    XposedLog.e(HOOK_TAG, lpparam.packageName, "Failed to hook MiuiPhoneStatusBarView: ${it.message}")
                }
            }

            val miuiClockCls = loadClassOrNull("com.android.systemui.statusbar.views.MiuiClock", lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.views.MiuiStatusBarClock", lpparam.classLoader)
            if (miuiClockCls != null) {
                runCatching {
                    miuiClockCls.declaredMethods.filter {
                        it.name in listOf("setTextColor", "setTextColorDark", "updateClockColor", "onDarkChanged", "setTextDark", "updateTime")
                    }.createAfterHooks { param ->
                        val clock = param.thisObject as? TextView
                        if (clock != null) {
                            for (tv in mStatusbarTextIcons) {
                                syncColorWithClock(tv, clock)
                            }
                        }
                    }
                }
            }
        }

        private fun injectToCollapsedStatusBar(nsvCls: Class<*>, fragment: Any, rootView: View?, context: Context) {
            val clockView = fragment.getObjectFieldOrNullAs<View>(FIELD_M_CLOCK_VIEW)
                ?: rootView?.let { root ->
                    val clockId = root.resources.getIdentifier(ID_CLOCK, "id", PKG_SYSTEMUI)
                    if (clockId != 0) root.findViewById(clockId) else null
                }
            val container = clockView?.parent as? ViewGroup
            injectToContainer(nsvCls, context, clockView, container, null)
        }

        private fun injectToContainer(
            nsvCls: Class<*>,
            context: Context,
            clockView: View?,
            targetContainer: ViewGroup?,
            providedDarkDispatcher: Any?
        ) {
            val container = targetContainer ?: (clockView?.parent as? ViewGroup) ?: return
            pruneTrackedIcons("inject")
            val existing = container.findViewWithTag<View>(TAG_SLOT_TEXT_ICON)
            if (existing != null) {
                if (!mStatusbarTextIcons.contains(existing)) {
                    mStatusbarTextIcons.add(existing)
                }

                dedupeInContainer(container, existing, "left.existing")
                syncColorWithClock(existing, clockView as? TextView)
                resetRenderState(existing, strong = true, reason = "inject.existing")
                if (DIAG_LOG) dumpIcons("left.inject.existing")
                return
            }

            val darkDispatcher = providedDarkDispatcher ?: loadClassOrNull("com.android.systemui.plugins.DarkIconDispatcher", lpparam.classLoader)?.let { darkCls ->
                loadClassOrNull("com.android.systemui.Dependency", lpparam.classLoader)?.let { depCls ->
                    runCatching { depCls.callStaticMethod("get", darkCls) }.getOrNull()
                }
            }

            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val iconView = ViewHelper.createStatusbarTextIcon(nsvCls, context, lp, false)
            val index = if (clockView != null) {
                (container.indexOfChild(clockView) + 1).coerceAtLeast(0).coerceAtMost(container.childCount)
            } else {
                container.childCount
            }
            container.addView(iconView, index)
            mStatusbarTextIcons.add(iconView)
            dedupeInContainer(container, iconView, "left.new")
            syncColorWithClock(iconView, clockView as? TextView)
            if (darkDispatcher != null) {
                runCatching { darkDispatcher.callMethod(METHOD_ADD_DARK_RECEIVER, iconView) }
            }
            resetRenderState(iconView, strong = true, reason = "inject.new")
            if (DIAG_LOG) dumpIcons("left.inject.new")
        }

        private fun setupSystemIconAreaVisibility() {
            val mcsbFragmentCls = loadClassOrNull(CLS_MIUI_COLLAPSED_STATUS_BAR, lpparam.classLoader)
                ?: loadClassOrNull("com.android.systemui.statusbar.phone.CollapsedStatusBarFragment", lpparam.classLoader)
                ?: return

            runCatching {
                mcsbFragmentCls.declaredMethods.filter { it.name == "showSystemIconArea" && it.parameterCount == 1 }.createAfterHooks {
                    for (v in mStatusbarTextIcons) {
                        runCatching { v.callMethod(METHOD_SET_VISIBILITY_BY_CONTROLLER, true) }
                            .onFailure { v.visibility = View.VISIBLE }
                    }

                    scheduleRenderReset("showSystemIconArea")
                }
            }

            runCatching {
                mcsbFragmentCls.declaredMethods.filter { it.name == "hideSystemIconArea" && it.parameterCount == 1 }.createAfterHooks {
                    for (v in mStatusbarTextIcons) {
                        runCatching { v.callMethod(METHOD_SET_VISIBILITY_BY_CONTROLLER, false) }
                            .onFailure { v.visibility = View.GONE }
                    }
                    scheduleRenderReset("hideSystemIconArea")
                }
            }
        }
    }

    private class BatteryWorkerHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_WORKER_TICK) {
                processBatteryUpdate()
                removeMessages(MSG_WORKER_TICK)
                sendEmptyMessageDelayed(MSG_WORKER_TICK, updateSpacing * 1000L)
            }
        }

        private fun processBatteryUpdate() {
            val context = mStatusbarTextIcons.firstOrNull()?.context
            val powerMgr = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isScreenOn = powerMgr?.isInteractive ?: true
            if (!isScreenOn) {
                return
            }

            val props = MetricsHelper.readBatteryUeventProps()
            val showBatteryInfo = shouldShowBatteryInfo(props)
            val batteryInfo = if (showBatteryInfo && props != null) MetricsHelper.buildBatteryInfoText(props) else ""

            val tii = TextIconInfo(
                iconShow = showBatteryInfo && batteryInfo.isNotEmpty(),
                iconText = batteryInfo
            )
            mainHandler?.obtainMessage(MSG_DATA_UPDATE, tii)?.sendToTarget()
        }

        private fun shouldShowBatteryInfo(props: Properties?): Boolean {
            if (!inCharge) {
                return true
            }
            var charging = checkChargeUtilsState()
            if (!charging && props != null) {
                val status = props.getProperty(PROP_POWER_SUPPLY_STATUS)
                charging = "Charging".equals(status, ignoreCase = true)
            }
            return charging
        }

        private fun checkChargeUtilsState(): Boolean {
            val chargeUtilsClass = loadClassOrNull("com.miui.charge.ChargeUtils", lpparam.classLoader)
                ?: loadClassOrNull("com.android.keyguard.charge.ChargeUtils", lpparam.classLoader)
                ?: return false
            val sBatteryStatus = runCatching { chargeUtilsClass.getObjectFieldOrNull(FIELD_S_BATTERY_STATUS) }.getOrNull() ?: return false
            return runCatching { sBatteryStatus.callMethod(METHOD_IS_CHARGING) as Boolean }.getOrDefault(false)
        }
    }

    private object MetricsHelper {
        fun readBatteryUeventProps(): Properties? {
            return try {
                FileInputStream(BATTERY_UEVENT_PATH).use { fis ->
                    Properties().apply { load(fis) }
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun computeTemperature(props: Properties): String {
            val tempProp = props.getProperty(PROP_POWER_SUPPLY_TEMP)
            val tempVal = if (!TextUtils.isEmpty(tempProp)) tempProp.toIntOrNull() ?: 0 else 0
            return if (tempDecimal) {
                String.format(Locale.getDefault(), "%.1f", tempVal / 10f)
            } else {
                if (tempVal % 10 == 0) (tempVal / 10).toString() else (tempVal / 10f).toString()
            }
        }

        private fun computeCurrent(props: Properties): Pair<String, Int> {
            val currentRatio = if (fixCurrentRatio) 1f else 1000f
            val curProp = props.getProperty(PROP_POWER_SUPPLY_CURRENT_NOW)
            val curReadVal = if (!TextUtils.isEmpty(curProp)) curProp.toIntOrNull() ?: 0 else 0
            var rawCurr = -1 * Math.round(curReadVal / currentRatio)
            if (positive) {
                rawCurr = Math.abs(rawCurr)
            }
            val currVal = if (Math.abs(rawCurr) > 999) {
                String.format(Locale.getDefault(), "%.2f", rawCurr / 1000f)
            } else {
                rawCurr.toString()
            }
            return Pair(currVal, rawCurr)
        }

        private fun computeWattage(props: Properties, rawCurr: Int): String {
            val voltProp = props.getProperty(PROP_POWER_SUPPLY_VOLTAGE_NOW)
            val voltVal = if (!TextUtils.isEmpty(voltProp)) (voltProp.toFloatOrNull() ?: 0f) / 1000000f else 0f
            return String.format(Locale.getDefault(), "%.2f", Math.abs(voltVal * rawCurr) / 1000f)
        }

        fun buildBatteryInfoText(props: Properties): String {
            val tempStr = computeTemperature(props) + if (hideUnit == 1 || hideUnit == 2) "" else UNIT_CELSIUS
            val (currValue, rawCurr) = computeCurrent(props)
            val preferred = if (Math.abs(rawCurr) > 999) UNIT_A else UNIT_MA
            val currStr = currValue + if (hideUnit == 1 || hideUnit == 3) "" else preferred
            val wattStr = computeWattage(props, rawCurr) + if (hideUnit == 1 || hideUnit == 3) "" else UNIT_WATT
            val splitChar = if (singleRow) " " else "\n"

            return when (content) {
                1 -> if (reverseOrder) currStr + splitChar + tempStr else tempStr + splitChar + currStr
                2 -> wattStr
                3 -> currStr
                4 -> if (reverseOrder) wattStr + splitChar + tempStr else tempStr + splitChar + wattStr
                5 -> if (reverseOrder) wattStr + splitChar + currStr else currStr + splitChar + wattStr
                else -> currStr
            }
        }
    }

    private object ViewHelper {
        fun isCustomTextIcon(view: View): Boolean {
            return view.getTag(textIconTagId) == ICON_TYPE || TAG_SLOT_TEXT_ICON == view.tag
        }

        fun createStatusbarTextIcon(
            nsvCls: Class<*>,
            mContext: Context,
            lp: ViewGroup.LayoutParams,
            fromController: Boolean
        ): View {
            val constructors = Constructors.find(nsvCls).toList()
            val constructor = constructors.firstOrNull { it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java }
                ?: constructors.firstOrNull { it.parameterCount == 2 }
                ?: constructors.first()

            val iconView = if (constructor.parameterCount == 1) {
                constructor.newInstance(mContext) as ViewGroup
            } else {
                constructor.newInstance(mContext, null) as ViewGroup
            }

            iconView.tag = TAG_SLOT_TEXT_ICON
            iconView.setTag(textIconTagId, ICON_TYPE)
            iconView.layoutParams = lp

            val number = TextView(mContext).apply {
                tag = TAG_NETWORK_SPEED_NUMBER
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                isSingleLine = false
            }
            iconView.addView(number)

            val unit = TextView(mContext).apply {
                tag = TAG_NETWORK_SPEED_UNIT
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0)
                visibility = View.GONE
                isSingleLine = true
            }
            iconView.addView(unit)

            runCatching { iconView.setObjectField(FIELD_CONTAINER, number) }
            runCatching { iconView.setObjectField(FIELD_NETWORK_SPEED_NUMBER_TEXT, number) }
            runCatching { iconView.setObjectField(FIELD_NETWORK_SPEED_UNIT_TEXT, unit) }
            runCatching { iconView.setObjectField(FIELD_VISIBLE_BY_CONTROLLER, true) }
            runCatching {
                iconView.javaClass.declaredMethods.filter { it.name.startsWith("updateResources") }.forEach {
                    it.isAccessible = true
                    it.invoke(iconView)
                }
            }

            initStatusbarTextIcon(mContext, lp, iconView, fromController)
            return iconView
        }

        @SuppressLint("DiscouragedApi")
        fun initStatusbarTextIcon(
            mContext: Context,
            lp: ViewGroup.LayoutParams,
            iconView: View,
            fromController: Boolean
        ) {
            if (!fromController) {
                runCatching { iconView.callMethod(METHOD_SET_BLOCKED, false) }
            }
            val iconTextView = iconView.getObjectFieldOrNullAs<TextView>(FIELD_NETWORK_SPEED_NUMBER_TEXT)
                ?: (iconView as? TextView) ?: return

            val res = mContext.resources
            val styleId = res.getIdentifier(STYLE_NETWORK_SPEED_NUMBER, "style", PKG_SYSTEMUI)
                .takeIf { it != 0 }
                ?: res.getIdentifier(STYLE_CLOCK, "style", PKG_SYSTEMUI)
            if (styleId != 0) {
                iconTextView.setTextAppearance(styleId)
            }
            syncColorWithClock(iconView)

            val familyName = if (bold) FONT_MIPRO_BOLD else FONT_MIPRO_MEDIUM
            val tf = runCatching { Typeface.create(familyName, if (bold) Typeface.BOLD else Typeface.NORMAL) }.getOrNull()
                ?: runCatching { Typeface.create(FONT_MISANS, if (bold) Typeface.BOLD else Typeface.NORMAL) }.getOrNull()
                ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            iconTextView.typeface = tf

            val fSize = fontSize * 0.5f
            if (isMultiLineContent(content) && !singleRow) {
                iconTextView.isSingleLine = false
                iconTextView.maxLines = 2
                val lineSpacing = if (fSize > 8.5f) 0.85f else 0.9f
                iconTextView.setLineSpacing(0f, lineSpacing)
            } else {
                iconTextView.isSingleLine = true
            }

            iconTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fSize)

            val leftMarginPx = dp2px(leftMargin * 0.5f)
            val rightMarginPx = dp2px(rightMargin * 0.5f)
            val topMarginPx = if (verticalOffset != 8) dp2px((verticalOffset - 8) * 0.5f) else 0

            iconTextView.setPaddingRelative(leftMarginPx, topMarginPx, rightMarginPx, 0)

            if (fixedWidth > 10) {
                lp.width = dp2px(fixedWidth.toFloat())
                iconView.layoutParams = lp
            }

            when (align) {
                2 -> iconTextView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                3 -> iconTextView.gravity = Gravity.CENTER
                4 -> iconTextView.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                else -> iconTextView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
        }

        private fun isMultiLineContent(contentMode: Int): Boolean {
            return contentMode == 1 || contentMode == 4 || contentMode == 5
        }
    }
}
