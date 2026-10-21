package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Icônes vectorielles dessinées au trait (grille 24×24), sans fichier de ressources. */
class Icon(context: Context, var kind: Kind, color: Int) : View(context) {
    enum class Kind {
        BACK, SEARCH, PLAY, PAUSE, STOP, THERMO, SPEED, CLOCK, REVERSE, SCALE, BOOK, STAR, STAR_FILLED,
        SETTINGS, PLUS, MINUS, CHECK, CLOSE, KNEAD, STEAM, FLAME, BOLT, SYNC, LID, WARNING, HOME, WHISK,
        CHEVRON, LINK, INFO, POT, TIMER,
    }

    var color: Int = color
        set(v) {
            field = v
            stroke.color = v
            fill.color = v
            invalidate()
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val path = Path()
    private val rect = RectF()

    fun set(k: Kind) {
        if (k != kind) {
            kind = k
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height) / 24f
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
        canvas.scale(s, s)
        stroke.strokeWidth = 2f
        val p = path
        p.reset()
        when (kind) {
            Kind.BACK -> { p.moveTo(15f, 5f); p.lineTo(8f, 12f); p.lineTo(15f, 19f); canvas.drawPath(p, stroke) }
            Kind.CHEVRON -> { p.moveTo(9f, 5f); p.lineTo(16f, 12f); p.lineTo(9f, 19f); canvas.drawPath(p, stroke) }
            Kind.SEARCH -> { canvas.drawCircle(10.5f, 10.5f, 6.5f, stroke); canvas.drawLine(15.5f, 15.5f, 20.5f, 20.5f, stroke) }
            Kind.PLAY -> { p.moveTo(7f, 4.5f); p.lineTo(19.5f, 12f); p.lineTo(7f, 19.5f); p.close(); canvas.drawPath(p, fill) }
            Kind.PAUSE -> { rect.set(6f, 5f, 10f, 19f); canvas.drawRoundRect(rect, 1f, 1f, fill); rect.set(14f, 5f, 18f, 19f); canvas.drawRoundRect(rect, 1f, 1f, fill) }
            Kind.STOP -> { rect.set(6f, 6f, 18f, 18f); canvas.drawRoundRect(rect, 2f, 2f, fill) }
            Kind.THERMO -> {
                rect.set(9.5f, 2.5f, 14.5f, 15f); canvas.drawRoundRect(rect, 2.5f, 2.5f, stroke)
                canvas.drawCircle(12f, 17.5f, 4f, fill); canvas.drawLine(12f, 8f, 12f, 16f, stroke)
            }
            Kind.SPEED -> {
                rect.set(3f, 5f, 21f, 23f); canvas.drawArc(rect, 160f, 220f, false, stroke)
                canvas.drawLine(12f, 14f, 16.5f, 9f, stroke); canvas.drawCircle(12f, 14f, 1.6f, fill)
            }
            Kind.CLOCK, Kind.TIMER -> {
                canvas.drawCircle(12f, 13f, 8.5f, stroke); canvas.drawLine(12f, 13f, 12f, 8.5f, stroke); canvas.drawLine(12f, 13f, 15f, 15f, stroke)
                if (kind == Kind.TIMER) canvas.drawLine(10f, 2.5f, 14f, 2.5f, stroke)
            }
            Kind.REVERSE -> {
                rect.set(4f, 4f, 20f, 20f); canvas.drawArc(rect, -60f, -260f, false, stroke)
                p.moveTo(17f, 3f); p.lineTo(16.5f, 6.8f); p.lineTo(20.5f, 7f); canvas.drawPath(p, stroke)
            }
            Kind.SCALE -> {
                rect.set(3f, 7f, 21f, 20f); canvas.drawRoundRect(rect, 3f, 3f, stroke)
                rect.set(7f, 10f, 17f, 18f); canvas.drawArc(rect, 200f, 140f, false, stroke)
                canvas.drawLine(12f, 14f, 14f, 11.5f, stroke); canvas.drawLine(8f, 4f, 16f, 4f, stroke)
            }
            Kind.BOOK -> {
                p.moveTo(12f, 6f); p.cubicTo(9f, 4f, 6f, 4f, 3f, 5f); p.lineTo(3f, 19f); p.cubicTo(6f, 18f, 9f, 18f, 12f, 20f)
                p.cubicTo(15f, 18f, 18f, 18f, 21f, 19f); p.lineTo(21f, 5f); p.cubicTo(18f, 4f, 15f, 4f, 12f, 6f); p.close()
                canvas.drawPath(p, stroke); canvas.drawLine(12f, 6f, 12f, 20f, stroke)
            }
            Kind.STAR, Kind.STAR_FILLED -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 9.5f else 4.2f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = 12f + (r * Math.cos(a)).toFloat()
                    val y = 12.8f + (r * Math.sin(a)).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                canvas.drawPath(p, if (kind == Kind.STAR_FILLED) fill else stroke)
            }
            Kind.SETTINGS -> {
                canvas.drawCircle(12f, 12f, 3.2f, stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians((i * 45).toDouble())
                    canvas.drawLine(12f + 6.2f * Math.cos(a).toFloat(), 12f + 6.2f * Math.sin(a).toFloat(),
                        12f + 9.2f * Math.cos(a).toFloat(), 12f + 9.2f * Math.sin(a).toFloat(), stroke)
                }
                canvas.drawCircle(12f, 12f, 6.2f, stroke)
            }
            Kind.PLUS -> { canvas.drawLine(12f, 5f, 12f, 19f, stroke); canvas.drawLine(5f, 12f, 19f, 12f, stroke) }
            Kind.MINUS -> canvas.drawLine(5f, 12f, 19f, 12f, stroke)
            Kind.CHECK -> { p.moveTo(5f, 12.5f); p.lineTo(10f, 17.5f); p.lineTo(19f, 7f); canvas.drawPath(p, stroke) }
            Kind.CLOSE -> { canvas.drawLine(6f, 6f, 18f, 18f, stroke); canvas.drawLine(18f, 6f, 6f, 18f, stroke) }
            Kind.KNEAD -> {
                p.moveTo(4f, 15f); p.cubicTo(4f, 9f, 9f, 7f, 12f, 9f); p.cubicTo(15f, 7f, 20f, 9f, 20f, 15f)
                p.cubicTo(20f, 19f, 4f, 19f, 4f, 15f); p.close(); canvas.drawPath(p, stroke)
                canvas.drawLine(9f, 12.5f, 11f, 14.5f, stroke); canvas.drawLine(13f, 12.5f, 15f, 14.5f, stroke)
            }
            Kind.STEAM -> {
                for (x in floatArrayOf(7f, 12f, 17f)) {
                    p.reset(); p.moveTo(x, 20f); p.cubicTo(x - 3f, 16f, x + 3f, 13f, x, 9f); p.cubicTo(x - 2f, 7f, x + 1f, 5f, x, 3.5f)
                    canvas.drawPath(p, stroke)
                }
            }
            Kind.FLAME -> {
                p.moveTo(12f, 2.5f); p.cubicTo(13f, 7f, 19f, 9f, 18.5f, 15f); p.cubicTo(18f, 19.5f, 15f, 21.5f, 12f, 21.5f)
                p.cubicTo(9f, 21.5f, 5.5f, 19.5f, 5.5f, 15f); p.cubicTo(5.5f, 12f, 7.5f, 10f, 8.5f, 9f); p.cubicTo(9f, 11.5f, 10f, 12.5f, 11f, 13f)
                p.cubicTo(11f, 9f, 10f, 6f, 12f, 2.5f); p.close(); canvas.drawPath(p, stroke)
            }
            Kind.BOLT -> { p.moveTo(13.5f, 2.5f); p.lineTo(5f, 13.5f); p.lineTo(11.5f, 13.5f); p.lineTo(10.5f, 21.5f); p.lineTo(19f, 10.5f); p.lineTo(12.5f, 10.5f); p.close(); canvas.drawPath(p, fill) }
            Kind.SYNC -> {
                rect.set(4.5f, 4.5f, 19.5f, 19.5f)
                canvas.drawArc(rect, 200f, 140f, false, stroke); canvas.drawArc(rect, 20f, 140f, false, stroke)
                p.moveTo(18.5f, 3.5f); p.lineTo(19f, 8f); p.lineTo(14.5f, 8f); canvas.drawPath(p, stroke)
                p.reset(); p.moveTo(5.5f, 20.5f); p.lineTo(5f, 16f); p.lineTo(9.5f, 16f); canvas.drawPath(p, stroke)
            }
            Kind.LID -> {
                rect.set(3f, 11f, 21f, 21f); canvas.drawRoundRect(rect, 2f, 2f, stroke)
                p.moveTo(5f, 8f); p.lineTo(19f, 4f); canvas.drawPath(p, stroke); canvas.drawLine(10.5f, 6f, 10f, 4.2f, stroke)
            }
            Kind.WARNING -> {
                p.moveTo(12f, 3f); p.lineTo(22f, 20.5f); p.lineTo(2f, 20.5f); p.close(); canvas.drawPath(p, stroke)
                canvas.drawLine(12f, 9.5f, 12f, 14f, stroke); canvas.drawCircle(12f, 17.3f, 1.2f, fill)
            }
            Kind.HOME -> { p.moveTo(3.5f, 11f); p.lineTo(12f, 4f); p.lineTo(20.5f, 11f); canvas.drawPath(p, stroke); rect.set(6f, 10f, 18f, 20f); canvas.drawRect(rect, stroke) }
            Kind.WHISK -> {
                canvas.drawLine(12f, 21.5f, 12f, 15f, stroke)
                rect.set(7f, 2.5f, 17f, 16f); canvas.drawOval(rect, stroke)
                rect.set(9.5f, 2.5f, 14.5f, 16f); canvas.drawOval(rect, stroke)
            }
            Kind.LINK -> { canvas.drawCircle(12f, 12f, 4f, fill); canvas.drawCircle(12f, 12f, 8.5f, stroke) }
            Kind.INFO -> { canvas.drawCircle(12f, 12f, 9f, stroke); canvas.drawLine(12f, 11f, 12f, 16.5f, stroke); canvas.drawCircle(12f, 7.8f, 1.2f, fill) }
            Kind.POT -> {
                rect.set(4f, 9f, 20f, 20f); canvas.drawRoundRect(rect, 3f, 3f, stroke)
                canvas.drawLine(2f, 11f, 4f, 11f, stroke); canvas.drawLine(20f, 11f, 22f, 11f, stroke)
                canvas.drawLine(7f, 6f, 17f, 6f, stroke); canvas.drawLine(12f, 3.5f, 12f, 6f, stroke)
            }
        }
        canvas.restore()
    }
}
