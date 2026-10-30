package com.gzus.courseassistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 迷你小组件（2×1）：单行显示下一节课。 */
class MiniCourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetViews.update(
            context,
            MiniCourseWidgetProvider::class.java,
            appWidgetIds,
            WidgetViews.buildMini(context),
        )
    }

    override fun onEnabled(context: Context) {
        WidgetViews.refreshAll(context)
    }
}
