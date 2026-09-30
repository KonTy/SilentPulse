package com.silentpulse.messenger.feature.stocks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.silentpulse.messenger.feature.stocks.data.StockChartPoint

data class ChartPosition(val x: Float, val y: Float)

object StockChartGeometry {
    fun segments(points: List<StockChartPoint>, width: Float, height: Float): List<List<ChartPosition>> {
        val prices = points.mapNotNull { it.price?.takeIf(Double::isFinite) }
        if (prices.size < 2 || points.size < 2 || width <= 0 || height <= 0) return emptyList()
        val start = points.first().timeMillis
        val duration = (points.last().timeMillis - start).toDouble()
        if (duration <= 0) return emptyList()
        val low = prices.minOrNull()!!
        val high = prices.maxOrNull()!!
        val range = high - low
        if (!range.isFinite()) return emptyList()
        val result = mutableListOf<List<ChartPosition>>()
        var segment = mutableListOf<ChartPosition>()
        for (point in points) {
            val price = point.price
            if (price == null || !price.isFinite()) {
                if (segment.size >= 2) result.add(segment)
                segment = mutableListOf()
            } else {
                segment.add(ChartPosition(
                    ((point.timeMillis - start) / duration * width).toFloat(),
                    if (range == 0.0) height / 2 else ((high - price) / range * height).toFloat()
                ))
            }
        }
        if (segment.size >= 2) result.add(segment)
        return result
    }
}

object StockChartRenderer {
    // Collection rows travel over Binder; keep each bitmap small even on high-density launchers.
    private const val WIDTH = 320
    private const val HEIGHT = 80

    fun render(points: List<StockChartPoint>, color: Int, width: Int = WIDTH, height: Int = HEIGHT): Bitmap? {
        val bitmapWidth = width.coerceIn(32, 1600)
        val bitmapHeight = height.coerceIn(16, 800)
        val scale = (bitmapWidth / WIDTH.toFloat()).coerceAtLeast(1f)
        val horizontalInset = scale * 2
        val verticalInset = scale * 3
        val segments = StockChartGeometry.segments(points, bitmapWidth - horizontalInset * 2, bitmapHeight - verticalInset * 2)
        if (segments.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = horizontalInset
            strokeJoin = Paint.Join.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, bitmapHeight.toFloat(),
                (color and 0x00ffffff) or (72 shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        for (segment in segments) {
            val path = Path().apply {
                moveTo(segment.first().x + horizontalInset, segment.first().y + verticalInset)
                segment.drop(1).forEach { lineTo(it.x + horizontalInset, it.y + verticalInset) }
            }
            val area = Path(path).apply {
                lineTo(segment.last().x + horizontalInset, bitmapHeight.toFloat())
                lineTo(segment.first().x + horizontalInset, bitmapHeight.toFloat())
                close()
            }
            canvas.drawPath(area, fill)
            canvas.drawPath(path, line)
        }
        return bitmap
    }
}
