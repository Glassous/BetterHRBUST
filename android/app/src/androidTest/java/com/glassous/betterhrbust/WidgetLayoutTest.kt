package com.glassous.betterhrbust

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glassous.betterhrbust.widget.TimetableWidgetProvider
import com.glassous.betterhrbust.widget.WidgetCourse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetLayoutTest {
    @Test fun cachedReapplicationAlwaysRestoresOpaqueBackgrounds() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val base=instrumentation.targetContext
        instrumentation.runOnMainSync {
            var root: View?=null
            for (night in listOf(false,true,false,true)) {
                val config=Configuration(base.resources.configuration).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if(night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val context=base.createConfigurationContext(config)
                val views=TimetableWidgetProvider.render(context,LocalDate.parse("2026-10-10"),LocalTime.NOON,6,emptyList(),"",300)
                if(root==null) root=views.apply(context,android.appwidget.AppWidgetHostView(context))
                else {
                    // 模拟宿主缓存留下的透明底板；一次更新必须恢复三块背景。
                    for(id in listOf(android.R.id.background,R.id.widget_today_panel,R.id.widget_tomorrow_panel))
                        root!!.findViewById<View>(id).background=null
                    views.reapply(context,root!!)
                }
                for(id in listOf(android.R.id.background,R.id.widget_today_panel,R.id.widget_tomorrow_panel)) {
                    val drawable=root!!.findViewById<View>(id).background
                    assertNotNull("背景必须存在",drawable)
                    val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
                    drawable!!.setBounds(0,0,100,100);drawable.draw(Canvas(bitmap))
                    assertEquals("底板中心必须完全不透明",255,android.graphics.Color.alpha(bitmap.getPixel(50,50)))
                    bitmap.recycle()
                }
                val panel=root!!.findViewById<View>(R.id.widget_today_panel).background as android.graphics.drawable.GradientDrawable
                assertEquals(if(night) android.graphics.Color.parseColor("#2A2E36") else android.graphics.Color.parseColor("#ECECEF"),panel.color!!.defaultColor)
            }
        }
    }

    @Test fun resizingSelectsTwoDaysTodayAndNextCourse() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val scenario=androidx.test.core.app.ActivityScenario.launch<com.glassous.betterhrbust.widget.WidgetPreviewHostActivity>(android.content.Intent(context,com.glassous.betterhrbust.widget.WidgetPreviewHostActivity::class.java))
        try {
            for((width,height,count) in listOf(360 to 180 to 3,180 to 180 to 3,180 to 100 to 1).map { Triple(it.first.first,it.first.second,it.second) }) {
                var root: View?=null
                scenario.onActivity { activity ->
                    val courses=(1..3).map { WidgetCourse("课程$it","D$it","10:10–11:50",false) }
                    val views=TimetableWidgetProvider.render(activity,LocalDate.parse("2026-10-10"),LocalTime.parse("08:00"),6,courses,"",height,courses,width)
                    val host=android.appwidget.AppWidgetHostView(activity)
                    val component=android.content.ComponentName(activity,TimetableWidgetProvider::class.java)
                    host.setAppWidget(0,android.appwidget.AppWidgetManager.getInstance(activity).installedProviders.single { it.provider==component })
                    root=views.apply(activity,host)
                    activity.setContentView(root,android.view.ViewGroup.LayoutParams((width*activity.resources.displayMetrics.density).toInt(),(height*activity.resources.displayMetrics.density).toInt()))
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertEquals(if(width>=260) View.VISIBLE else View.GONE,root!!.findViewById<View>(R.id.widget_tomorrow_panel).visibility)
                    assertEquals(count,root!!.findViewById<android.widget.ListView>(R.id.widget_courses).count)
                    assertTrue(root!!.findViewById<android.widget.TextView>(R.id.widget_date).text.toString().contains("10/10"))
                    assertEquals("今天 10/10",root!!.findViewById<android.widget.TextView>(R.id.widget_today_label).text.toString())
                    assertEquals("明天 10/11",root!!.findViewById<android.widget.TextView>(R.id.widget_tomorrow_label).text.toString())
                    assertEquals(if(height<140) View.GONE else View.VISIBLE,root!!.findViewById<View>(R.id.widget_done).visibility)
                    val bitmap=Bitmap.createBitmap(root!!.width,root!!.height,Bitmap.Config.ARGB_8888)
                    root!!.draw(Canvas(bitmap))
                    val output=File(context.getExternalFilesDir(null),"widget-previews").apply { mkdirs() }
                    File(output,"adaptive-${width}x${height}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                    if(height<140) {
                        val list=root!!.findViewById<android.widget.ListView>(R.id.widget_courses)
                        assertTrue("下一节课应完整容纳在单行组件",list.getChildAt(0).height<=list.height)
                    }
                }
            }
        } finally { scenario.close() }
    }

    @Test fun bothDayListsRetainAllCoursesAndScrollIndependently() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val scenario=androidx.test.core.app.ActivityScenario.launch<com.glassous.betterhrbust.widget.WidgetPreviewHostActivity>(android.content.Intent(context,com.glassous.betterhrbust.widget.WidgetPreviewHostActivity::class.java))
        try {
            var left: android.widget.ListView?=null;var right: android.widget.ListView?=null
            scenario.onActivity { activity ->
                val items=(1..12).map { WidgetCourse("课程${it}","D${it}","08:10–09:50",false) }
                val views=TimetableWidgetProvider.render(activity,LocalDate.parse("2026-10-10"),LocalTime.parse("07:00"),6,items,"",300,items,360)
                val parent=android.appwidget.AppWidgetHostView(activity)
                val provider=android.content.ComponentName(activity,TimetableWidgetProvider::class.java)
                val info=android.appwidget.AppWidgetManager.getInstance(activity).installedProviders.single { it.provider==provider }
                parent.setAppWidget(0,info)
                val root=views.apply(activity,parent)
                activity.setContentView(root,android.view.ViewGroup.LayoutParams((360*activity.resources.displayMetrics.density).toInt(),(300*activity.resources.displayMetrics.density).toInt()))
                left=root.findViewById(R.id.widget_courses);right=root.findViewById(R.id.widget_tomorrow_courses)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(12,left!!.count);assertEquals(12,right!!.count)
                assertTrue(left!!.canScrollVertically(1));assertTrue(right!!.canScrollVertically(1))
                left!!.setSelection(8)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(left!!.firstVisiblePosition>=8);assertEquals(0,right!!.firstVisiblePosition);right!!.setSelection(6) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertTrue(right!!.firstVisiblePosition>=6);assertTrue(left!!.firstVisiblePosition>=8) }
        } finally { scenario.close() }
    }

    @Test fun actualWidgetProviderBindsAndRendersItsOfflineLoginState() = kotlinx.coroutines.runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val app=context.applicationContext as BetterHrbustApp
        org.junit.Assume.assumeTrue(context.packageName.endsWith(".widgetpreview"))
        org.junit.Assume.assumeTrue(app.preferencesManager.preferencesFlow.first().studentId.isBlank())
        val manager=android.appwidget.AppWidgetManager.getInstance(context)
        val provider=android.content.ComponentName(context,TimetableWidgetProvider::class.java)
        val info=manager.installedProviders.single { it.provider==provider }
        val host=android.appwidget.AppWidgetHost(context,20261010)
        val id=host.allocateAppWidgetId()
        var view: android.appwidget.AppWidgetHostView?=null
        try {
            val options=android.os.Bundle().apply {
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360)
                putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,400)
            }
            assertTrue("隔离模拟器需通过 appwidget grantbind 授予测试包绑定权限",manager.bindAppWidgetIdIfAllowed(id,provider,options))
            instrumentation.runOnMainSync {
                host.startListening()
                view=host.createView(context,id,info)
            }
            TimetableWidgetProvider.refreshAll(context)
            val deadline=android.os.SystemClock.elapsedRealtime()+10000
            var rendered=false
            while (!rendered && android.os.SystemClock.elapsedRealtime()<deadline) {
                instrumentation.runOnMainSync {
                    rendered=view?.findViewById<android.widget.TextView>(R.id.widget_today_empty_title)?.text?.toString()=="打开应用登录"
                }
                if (!rendered) kotlinx.coroutines.delay(50)
            }
            assertTrue("真实 AppWidgetHost 必须接收到 provider 的离线视图",rendered)
        } finally {
            instrumentation.runOnMainSync { host.deleteAppWidgetId(id);host.stopListening() }
        }
    }

    @Test fun actualRemoteViewsFitCompactWideTallAndLargeFontsInBothThemes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        val output=File(target.getExternalFilesDir(null), "widget-previews").apply { mkdirs() }
        for (night in listOf(false,true)) for (size in listOf(300 to 180, 360 to 240, 360 to 400)) for (scale in listOf(1f,1.3f,2f)) for (hasTomorrow in listOf(true,false)) {
            instrumentation.runOnMainSync {
                val configuration=Configuration(target.resources.configuration).apply {
                    uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    fontScale=scale
                }
                val context=target.createConfigurationContext(configuration)
                for (surface in listOf(R.color.widget_today_surface,R.color.widget_tomorrow_surface)) {
                    for (ink in listOf(R.color.widget_text,R.color.widget_secondary)) {
                        assertTrue("明暗正文对比度至少4.5",androidx.core.graphics.ColorUtils.calculateContrast(context.getColor(ink),context.getColor(surface)) >= 4.5)
                    }
                }
                assertTrue("时间文字对比度至少4.5",androidx.core.graphics.ColorUtils.calculateContrast(context.getColor(R.color.widget_accent),context.getColor(R.color.widget_time_surface)) >= 4.5)
                val density=context.resources.displayMetrics.density
                val width=(size.first*density).toInt(); val height=(size.second*density).toInt()
                val views=TimetableWidgetProvider.render(context, LocalDate.parse("2026-10-09"), LocalTime.parse("09:00"), 6,
                    listOf(WidgetCourse("软件体系结构与设计模式", "西-新D510", "08:10–09:50", true), WidgetCourse("数据库系统", "西-新D502", "10:10–11:50", false), WidgetCourse("软件项目管理", "西-新D305", "13:30–15:10", false)), "", size.second, tomorrowCourses=if (hasTomorrow) listOf(WidgetCourse("计算机网络", "西-新D302", "10:10–11:50", false), WidgetCourse("操作系统", "西-新D405", "13:30–15:10", false)) else emptyList(), width=size.first)
                val parent=android.appwidget.AppWidgetHostView(context)
                val provider=android.content.ComponentName(context,TimetableWidgetProvider::class.java)
                val info=android.appwidget.AppWidgetManager.getInstance(context).installedProviders.single { it.provider==provider }
                parent.setAppWidget(0,info)
                val root=views.apply(context,parent)
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
                root.layout(0,0,width,height)
                for (id in listOf(R.id.widget_courses,R.id.widget_tomorrow_courses)) {
                    val list=root.findViewById<android.widget.ListView>(id)
                    for (i in 0 until list.childCount) {
                        val row=list.getChildAt(i)
                        fun checkRow(view: View) {
                            if (view is android.view.ViewGroup) for (j in 0 until view.childCount) {
                                val child=view.getChildAt(j)
                                if(child.visibility==View.VISIBLE) assertTrue("课程行内部不得裁切",child.bottom<=view.height)
                                checkRow(child)
                            }
                        }
                        checkRow(row)
                    }
                }
                val left=root.findViewById<android.widget.ListView>(R.id.widget_courses)
                val right=root.findViewById<android.widget.ListView>(R.id.widget_tomorrow_courses)
                assertEquals(3,left.count)
                assertEquals(if(hasTomorrow) 2 else 0,right.count)
                assertTrue(root.findViewById<android.widget.TextView>(R.id.widget_done).text.toString().contains("0/3"))
                val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                File(output,"${if(night) "dark" else "light"}-${size.first}x${size.second}-$scale${if(hasTomorrow) "" else "-empty"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
    }
}
