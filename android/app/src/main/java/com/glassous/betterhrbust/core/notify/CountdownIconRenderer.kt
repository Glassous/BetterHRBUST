package com.glassous.betterhrbust.core.notify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon

/**
 * 超级岛左侧（`Notification.smallIcon`）单行倒计时图标渲染器。
 *
 * 在小米澎湃 OS 3 的实况通知上岛通道中，摄像头左侧槽位将 `smallIcon` 置于 1:1 正方形容器内等比缩放。
 * 为使左侧倒计时文字的视觉高度与右侧精简教室文本（`shortCriticalText`）保持平衡，
 * 这里通过适度收窄字宽比例（`textScaleX`）并按实际字形边界（`getTextBounds`）贴满画布宽高，
 * 最大化单行「X分」的垂直字高占比，同时保持透明底纯白前景以符合系统遮罩规范。
 */
object CountdownIconRenderer {
    private const val CANVAS_PX = 128
    private const val SAFE_WIDTH_PX = 124f
    private const val SAFE_HEIGHT_PX = 104f

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

        // 适度收窄横向字宽，使单行「15分」在 1:1 图标槽内可以放得更大、与右侧文字高度一致
        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textSize = 108f
            textScaleX = 0.78f
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = 84f
            textScaleX = 0.84f
        }

        val gap = 2f
        val numBounds = Rect()
        val unitBounds = Rect()
        numPaint.getTextBounds(numStr, 0, numStr.length, numBounds)
        unitPaint.getTextBounds(unitStr, 0, unitStr.length, unitBounds)

        val rawWidth = numPaint.measureText(numStr) + gap + unitPaint.measureText(unitStr)
        val rawHeight = maxOf(numBounds.height(), unitBounds.height()).toFloat().coerceAtLeast(1f)
        val scale = minOf(1f, SAFE_WIDTH_PX / rawWidth, SAFE_HEIGHT_PX / rawHeight)
        if (scale < 1f) {
            numPaint.textSize *= scale
            unitPaint.textSize *= scale
            numPaint.getTextBounds(numStr, 0, numStr.length, numBounds)
            unitPaint.getTextBounds(unitStr, 0, unitStr.length, unitBounds)
        }

        val numWidth = numPaint.measureText(numStr)
        val unitWidth = unitPaint.measureText(unitStr)
        val totalWidth = numWidth + gap + unitWidth
        val startX = (CANVAS_PX - totalWidth) / 2f

        // 基于数字与「分」字的联合真实字形包络居中，并微幅上移 2px 对齐右侧胶囊文字基线
        val topMin = minOf(numBounds.top, unitBounds.top)
        val bottomMax = maxOf(numBounds.bottom, unitBounds.bottom)
        val baselineY = (CANVAS_PX - (topMin + bottomMax)) / 2f - 2f

        canvas.drawText(numStr, startX, baselineY, numPaint)
        canvas.drawText(unitStr, startX + numWidth + gap, baselineY, unitPaint)

        return Icon.createWithBitmap(bitmap).also { cached = display to it }
    }
}
