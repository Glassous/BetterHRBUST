package com.glassous.betterhrbust.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Glance 课表组件的统一入口。
 *
 * 周期刷新按 vivo 原子组件规范放宽到 12 小时后，课程结束与午夜必须依靠
 * [WidgetRefreshScheduler] 的闹钟；闹钟只在桌面仍有组件时排程，因此在添加 / 移除时维护。
 */
abstract class TimetableGlanceReceiver : GlanceAppWidgetReceiver() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 首次添加：立即刷新一次并排课程边界闹钟
        TimetableWidgetUpdater.refreshAsync()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // 仅在桌面已无任何课表组件时停掉闹钟
        if (!TimetableWidgetUpdater.hasAnyWidget(context)) WidgetRefreshScheduler.cancel(context)
    }
}
