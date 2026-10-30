package com.gzus.courseassistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 中号小组件（2×2）：正在上课 / 下一节课。 */
class NextCourseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetViews.update(
            context,
            NextCourseWidgetProvider::class.java,
            appWidgetIds,
            WidgetViews.buildNext(context),
        )
    }

    override fun onEnabled(context: Context) {
        WidgetViews.refreshAll(context)
    }
}
