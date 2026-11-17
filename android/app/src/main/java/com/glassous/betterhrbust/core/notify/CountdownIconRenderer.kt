package com.glassous.betterhrbust.core.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon

/**
 * 超级岛左侧（`Notification.smallIcon`）单行倒计时图标渲染器。
 *
 * 在小米澎湃 OS 3 的实况通知上岛通道中，摄像头左侧槽位固定渲染通知的 `smallIcon`。
 * 按系统状态栏与超级岛遮罩规范，使用透明背景 + 纯白前景绘制单行「X分」（如「15分」），
 * 避免彩色或实心底图触发系统白块遮罩或前台服务不合规图标提示。
 */
object CountdownIconRenderer {
    private const val CANVAS_PX = 128
    private const val SAFE_WIDTH_PX = 122f

    @Volatile
    private var cached: Pair<Long, Icon>? = null

    /** 纯函数：生成超级岛左侧单行中文简写（如 `15 -> "15分"`），便于单元测试校验。 */
    fun formatLabel(minutes: Long): String = "${minutes.coerceAtLeast(1)}分"

    /**
     * 生成单行「X分」透明底纯白动态 [Icon]；同一分钟内重复调用直接复用缓存，零重复分配。
     */
    fun create(minutes: Long): Icon {
        val display = minutes.coerceAtLeast(1)
        cached?.let { (cachedMin, icon) ->
            if (cachedMin == display) return icon
        }

        val numStr = display.toString()
        val unitStr = "分"
        val bitmap = Bitmap.createBitmap(CANVAS_PX, CANVAS_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = 68f
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 46f
        }

        val gap = 2f
        val rawWidth = numPaint.measureText(numStr) + gap + unitPaint.measureText(unitStr)
        if (rawWidth > SAFE_WIDTH_PX) {
            val scale = SAFE_WIDTH_PX / rawWidth
            numPaint.textSize *= scale
            unitPaint.textSize *= scale
        }

        val numWidth = numPaint.measureText(numStr)
        val unitWidth = unitPaint.measureText(unitStr)
        val totalWidth = numWidth + gap + unitWidth
        val startX = (CANVAS_PX - totalWidth) / 2f

        val fm = numPaint.fontMetrics
        val baselineY = (CANVAS_PX - (fm.bottom - fm.top)) / 2f - fm.top

        canvas.drawText(numStr, startX, baselineY, numPaint)
        canvas.drawText(unitStr, startX + numWidth + gap, baselineY, unitPaint)

        return Icon.createWithBitmap(bitmap).also { cached = display to it }
    }
}
