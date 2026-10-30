package com.gzus.courseassistant.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 大号小组件（4×3）：今日全部课程。 */
class TodayCoursesWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetViews.update(
            context,
            TodayCoursesWidgetProvider::class.java,
            appWidgetIds,
            WidgetViews.buildToday(context),
        )
    }

    override fun onEnabled(context: Context) {
        WidgetViews.refreshAll(context)
    }
}
