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
import com.sevtinge.hyperceiler.libhook.utils.api.DeviceHelper.System.isMoreAndroidVersion
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

/**
 * Hook rule to display real-time battery detail info (temperature, current, wattage) in status bar.
 *
 * 本次改动（只动"状态归属"，不改绘制外观）：
 *
 * 1. 渲染状态复位从 setVisibleState 里抽成 resetRenderState()，并挂到灵动岛（焦点通知）的
 *    各个事件入口 + 每次 tick 兜底。原来的复位只挂在 setVisibleState 上，而灵动岛走的不是
 *    那条链路，所以第二个岛通知（如充电动画）插进来时残留的状态永远不会被清掉 -> 必糊。
 *
 * 2. 实例唯一化：注入时同容器内去重，tick 时淘汰已经脱离视图树的实例。灵动岛切换期间状态栏
 *    布局可能被重建/重新插入，两份指示器叠在一起看起来就是"糊/重影"。
 *
 * 3. 诊断日志（DIAG_LOG）：打印实例数、attached 状态、layer/alpha/scale/translation、父链、
 *    坐标，以及只读的 MIUI 材质状态和目标 View 树里带标记的节点数，用来判定故障属于
 *    "多实例 / 动画中间态 / 遮挡材质" 中的哪一类。修好后把 DIAG_LOG 改成 false 即可。
 *
 * 第二轮改动（基于第一份 LSPosed 日志的分析结论）：
 *
 * 4. 修掉自激死循环：原实现在 setVisibleState 里“同步复位 + 挂 5 个延时复位”，
 *    实测与系统回调互相激发，达到 ~165 次/秒、7 秒打印 4.5 万行日志（LSPosed 日志环形缓冲被刷掉 4 次），
 *    并反复重启系统图标动画，表现就是指示器“鬼畜左右跳”。现在：
 *      - setVisibleState 里默认不再复位（RESET_ON_VISIBLE_STATE）、不再 requestLayout（REQUEST_LAYOUT_ON_VISIBLE_STATE）；
 *      - 复位入口 requestRenderReset() 做了重入保护和 300ms 冷却，批次合并 500ms；
 *      - 增加频率监控 noteVisibleStateBurst()，超阀值会打一次调用栈（用来找出真正的高频来源）。
 *
 * 5. 日志结论：所有采样里 layer=0 / alpha=1.0 / scale=1,1 / trans=0,0，即“渲染状态停在中间态”
 *    这个假设基本被排除；“糊”更可能是遮挡/几何/多份实例叠加。因此灵动岛事件现在会额外打印
 *    岛视图和指示器的屏幕矩形及是否重叠（onIslandEvent），下一轮日志就能直接定性。
 *
 * 关于日志：HyperCeiler 的日志级别有个坑——release 变体下 LogLevelManager.getEffectiveLogLevel()
 * 会把「详细日志」强制降级成「一般日志」，此时 XposedLog.d / w / i 全部不会输出，只有 XposedLog.e 能出来。
 * 所以诊断输出统一走 diagLog()，默认（DIAG_FORCE_OUTPUT = true）使用 e 通道，
 * 无论 release 还是 debug 包，只要日志等级不是「禁用日志输出」就能看到。
 */
object BatteryDetailIndicator : BaseHook() {

    private const val HOOK_TAG = "BatteryDetailIndicator"
    private const val SLOT_NAME = "battery_info"
    private const val SLOT_NETWORK_SPEED = "network_speed"
    private const val ICON_TYPE = 91
    private const val MSG_DATA_UPDATE = 100021
    private const val MSG_WORKER_TICK = 200021

    // ------------------------------------------------------------------ 调试开关

    /** 事件级诊断日志（灵动岛事件、注入、tick 复位）。定位期间保持 true，修好后改 false。 */
    private const val DIAG_LOG = true

    /** 每 tick 都 dump 一次会刷屏，默认关。需要连续曲线数据时再打开。 */
    private const val DIAG_LOG_TICK = false

    /**
     * tick（每 2 秒）的兜底复位是否也做"强化版"（HARDWARE -> NONE 强制重栅格化 + 逐级 invalidate）。
     * 定位阶段建议设成 true：这样"事件复位漏掉的那一次"最多 2 秒就会被强行清掉，
     * 如果此时糊掉会在 2 秒内自愈，就直接证明原因是"渲染状态停在中间态"；
     * 如果 2 秒后依然糊，则原因是多实例或遮挡，需要看 dump 日志里的实例数与坐标。
     */
    private const val STRONG_RESET_ON_TICK = false

    /**
     * 诊断输出是否走“一定看得见”的通道。
     *
     * true  -> 用 XposedLog.e（日志等级 ≥ 一般日志即可见，release 包也能看到）
     * false -> 用 XposedLog.d（仅 debug 包 + 「详细日志」可见）
     *
     * 因为 release 变体会把「详细日志」降级成「一般日志」，定位阶段请保持 true，
     * 否则你会看到日志里什么都没有，误以为代码没生效。
     */
    private const val DIAG_FORCE_OUTPUT = true

    /** 诊断日志统一出口，见 DIAG_FORCE_OUTPUT 说明 */
    private fun diagLog(msg: String) {
        if (DIAG_FORCE_OUTPUT) {
            XposedLog.e(HOOK_TAG, lpparam.packageName, msg)
        } else {
            XposedLog.d(HOOK_TAG, lpparam.packageName, msg)
        }
    }

    /**
     * 灵动岛动画时长不确定，事件发生后在若干时间点补做复位：
     * - 0ms：立刻清掉上一轮事件可能留下的陈旧状态
     * - 120/320ms：动画进行中，只做"轻复位"，不和系统的动画抢属性
     * - 700/1500ms：动画应当已经结束，做"强复位"（含强制重栅格化）并抓一次现场
     */
    private val RESET_DELAYS = longArrayOf(0L, 120L, 320L, 700L, 1500L)

    /**
     * 复位批次的合并冷却时间。
     * 实测（LSPosed 日志）setVisibleState 在高频回调时会和复位互相激发：7 秒内打出 4.5 万行日志，
     * LSPosed 日志环形缓冲被刷掉 4 次，同时系统图标进出场动画被反复重启 -> 指示器鬼畜左右跳。
     */
    private const val SCHEDULE_COOLDOWN_MS = 500L

    /** 单次复位的最小间隔，防止“复位 -> 触发系统回调 -> 再复位”自激 */
    private const val RESET_COOLDOWN_MS = 300L

    /** setVisibleState 每秒调用次数超过该值判定为疑似自激/死循环，并打一次调用栈 */
    private const val VISIBLE_STATE_BURST_LIMIT = 30

    /**
     * 是否还在 setVisibleState 里做复位。
     * 日志实测结论：这个入口不是“糊”的原因（所有采样里 layer/alpha/scale/trans 全是干净值），
     * 但它调用频率极高，在这里复位只会制造自激。默认关掉做对比实验时再打。
     */
    private const val RESET_ON_VISIBLE_STATE = false

    /**
     * 是否在 setVisibleState 里手动 requestLayout。
     * 请求布局会诱发下一轮 setVisibleState 回调，是指示器“鬼畜左右跳”的高度嫌疑人，默认关。
     */
    private const val REQUEST_LAYOUT_ON_VISIBLE_STATE = false

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

    // 灵动岛（焦点通知）相关入口
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

    // ---- 防自激 / 频率监控 ----
    private var resetting = false
    private var resetCooldownUntil = 0L
    private var scheduleCooldownUntil = 0L
    private var burstWindowStart = 0L
    private var burstCount = 0
    private var lastBurstStackAt = 0L

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
        setupIslandEventHooks()
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

                    // 注意：这里默认不做复位（见 RESET_ON_VISIBLE_STATE 注释）。
                    // 原实现在这里同步复位 + 挂 5 个延时复位，实测会与 setVisibleState 互相激发，
                    // 形成 ~165 次/秒的回调死循环，日志刷爆、指示器左右跳。
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
                    // 字体/主题/密度变化会重建绘制资源，顺手把渲染状态拉回中性
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

    // ==================================================================
    // 灵动岛（焦点通知）事件 -> 主动复位
    // ==================================================================

    /**
     * 灵动岛是唯一会重排状态栏几何、切换整片可见性、并且会播放"会被第二个通知打断的动画"的流程。
     * 指示器是系统不认识的编外 View，系统收尾动画时不会照顾它，所以每个岛事件之后都要复位一次。
     *
     * 这里挂的入口和 HideFakeStatusBar.kt 里已经验证过的一致：
     * - FocusedNotifPromptController.notifyNotifBeanChanged：焦点通知内容更新（例如音乐 -> 充电）
     * - FocusedNotifPromptView.setData：岛的数据刷新（会重播动画）
     * - MiuiCollapsedStatusBarFragment.updateStatusBarVisibilities：整片可见性状态机
     * - LauncherProxyService / OverviewProxyService.onFocusedNotifUpdate：岛动画的目标矩形（几何重排）
     */
    private fun setupIslandEventHooks() {
        hookIslandEvent(CLS_FOCUS_NOTIF_PROMPT_CONTROLLER, METHOD_NOTIFY_NOTIF_BEAN_CHANGED, "island.notifyChanged")
        hookIslandEvent(CLS_FOCUS_NOTIF_PROMPT_VIEW, METHOD_SET_DATA, "island.setData")
        hookIslandEvent(CLS_MIUI_COLLAPSED_STATUS_BAR, METHOD_UPDATE_STATUS_BAR_VISIBILITIES, "island.visibilities")
        val recentsCls = if (isMoreAndroidVersion(36)) CLS_RECENTS_PROXY_NEW else CLS_RECENTS_PROXY_OLD
        hookIslandEvent(recentsCls, METHOD_ON_FOCUSED_NOTIF_UPDATE, "island.animTarget")
    }

    private fun hookIslandEvent(className: String, methodName: String, reason: String) {
        runCatching {
            val cls = loadClassOrNull(className, lpparam.classLoader)
            if (cls == null) {
                if (DIAG_LOG) diagLog("island hook miss(class): $className")
                return
            }
            val methods = (cls.declaredMethods.toList() + cls.methods.toList())
                .distinct()
                .filter { it.name == methodName }
            if (methods.isEmpty()) {
                if (DIAG_LOG) diagLog("island hook miss(method): $className#$methodName")
                return
            }
            methods.createBeforeHooks { param ->
                onIslandEvent(reason, param.thisObject as? View)
            }
        }.onFailure {
            XposedLog.e(HOOK_TAG, lpparam.packageName, "island hook failed: $className#$methodName: ${it.message}")
        }
    }

    /**
     * 灵动岛事件现场快照：重点是对比“岛”和“我们的指示器”在屏幕上的矩形是否重叠。
     * 这是判定“糊”到底是“被岛/材质遮住”还是“主状态机相关”的关键数据。
     */
    private fun onIslandEvent(reason: String, source: View?) {
        if (!DIAG_LOG) {
            scheduleRenderReset(reason)
            return
        }
        diagLog("island event: $reason")
        if (source != null) {
            val island = IntArray(2)
            runCatching { source.getLocationOnScreen(island) }
            val iw = source.width
            val ih = source.height
            diagLog(
                "  island view=${source.javaClass.simpleName} onScreen=[${island[0]},${island[1]}," +
                    "${island[0] + iw},${island[1] + ih}] size=${iw}x$ih vis=${source.visibility} " +
                    "alpha=${source.alpha} scale=${source.scaleX},${source.scaleY} layer=${source.layerType}"
            )
            for (view in mStatusbarTextIcons) {
                if (!view.isAttachedToWindow) continue
                val loc = IntArray(2)
                runCatching { view.getLocationOnScreen(loc) }
                val w = view.width
                val h = view.height
                val overlapX = w > 0 && iw > 0 && loc[0] < island[0] + iw && island[0] < loc[0] + w
                val overlapY = h > 0 && ih > 0 && loc[1] < island[1] + ih && island[1] < loc[1] + h
                diagLog(
                    "  ours host=${hostName(view)} onScreen=[${loc[0]},${loc[1]},${loc[0] + w},${loc[1] + h}] " +
                        "shown=${runCatching { view.isShown }.getOrDefault(false)} overlapX=$overlapX overlapY=$overlapY"
                )
            }
        }
        scheduleRenderReset(reason)
    }

    // ==================================================================
    // 渲染状态复位
    // ==================================================================

    /**
     * 把指示器拉回"中性渲染状态"，不改变可见性策略（可见性仍由 setVisibilityByController /
     * 系统状态机决定），只清掉缩放、位移、透明度、图层这些"被系统动画留在中间态"的属性。
     *
     * @param strong 额外做一次 HARDWARE -> NONE 强制重栅格化，并逐级 invalidate 父容器
     *               （用于动画被打断、图层里留着陈旧位图的情况）
     */
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
                    // 强制重新栅格化：某些情况下图层里留的是被缩放过的陈旧内容，直接 invalidate 不会重画
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
        // 日志改到 resetAllIcons() 里一次性输出摘要，避免每个视图一行把日志刷爆
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

    /** 一行摘要（场 -> 属性），比整段 describeView 省很多日志量 */
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

    /**
     * 合并 + 防自激的复位入口：同一时刻只允许一个复位在跑，且 RESET_COOLDOWN_MS 内不重复。
     * 这样即使某个系统回调在复位过程中被再次触发，也不会形成无限循环。
     */
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

    /**
     * setVisibleState 高频回调检测。
     * 日志实测出现过 ~165 次/秒的调用（相当于每帧都在回调），此时系统图标进出场动画会被反复重启，
     * 表现就是指示器“鬼畜左右跳”。这里只做检测 + 打一次调用栈，不触发任何复位。
     */
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

    /** 事件发生后按 RESET_DELAYS 在多个时间点补复位（带合并冷却，防止自激） */
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
                // 动画进行中的时间点只做轻复位，避免和系统的动画抢属性
                val strong = delay == 0L || delay >= 700L
                requestRenderReset("$reason+${delay}ms", strong)
                if (delay >= 700L) dumpIcons("$reason+${delay}ms")
            }, delay)
        }
    }

    // ==================================================================
    // 实例唯一化（防止多份指示器叠加导致的"糊/重影"）
    // ==================================================================

    /** 清掉已经脱离视图树的实例（parent 为 null 的实例永远不可能再显示） */
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

    /**
     * 同一个容器里只允许存在一份指示器。
     * 灵动岛切换期间状态栏布局可能被重建或重新插入，两份文字叠在一起看起来就是"糊/重影"，
     * 而且这类问题不会因为改文字渲染方式而消失（这也是之前一直修不好的原因之一）。
     */
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
            // 先抓现场再移除，日志里的 parent 才是有用的（移除后就变成 null 了）
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

    // ==================================================================
    // 诊断（定位糊掉属于哪一类）
    // ==================================================================

    private fun dumpIcons(reason: String) {
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

    /**
     * 最外层宿主类名（例如 MiuiNotificationStatusContainer / ControlCenterFakeStatusIcons）。
     * MIUI 会在每个状态栏宿主里各放一份我们的图标（下拉控制中心的“假状态栏图标区”也是一个宿主），
     * 所以“同一时刻到底有几份可见、哪一份在跳”必须靠宿主名区分。
     */
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

    /** 只读取 MIUI 的 View 材质状态（方法不存在就跳过），用于判断"糊"是否由材质引起 */
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
        // 每 tick 先做一次"卫生"：淘汰失效实例 + 容器内去重，避免多份实例叠加
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
            // 兜底复位：任何一个 tick 都把渲染状态拉回中性，
            // 这样"事件复位漏掉的那一次"最多 2 秒后也会自愈。
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
                // 同一容器里如果已经有别的指示器（例如上一轮布局重建残留的），清掉
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
                // 布局重建后同一容器里可能残留多份指示器，这里直接清掉多余的那份
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
                    // 整片图标区重新出现，系统的淡入/位移动画刚结束，补一次复位
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
