package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Carte de réglage : libellé, grande valeur, boutons − / +, sélection pour le bouton rotatif. */
class ValueCard(c: Context, label: String, icon: Icon.Kind) : LinearLayout(c) {
    private val valueView = Ui.text(c, "", 64f, Ui.TEXT, Ui.LIGHT)
    private val unitView = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
    private val subView = Ui.ellipsize(Ui.text(c, "", 16f, Ui.TEXT3))
    private val iconView = Icon(c, icon, Ui.TEXT2)
    private val minus: View
    private val plus: View
    var onMinus: () -> Unit = {}
    var onPlus: () -> Unit = {}
    var onSelect: () -> Unit = {}
    private var isSel = false

    init {
        orientation = VERTICAL
        setPadding(Ui.u(20), Ui.u(18), Ui.u(20), Ui.u(16))
        val head = Ui.horizontal(c)
        head.addView(iconView, LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(8) })
        head.addView(Ui.text(c, label.uppercase(), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f })
        addView(head)
        val row = Ui.horizontal(c, Gravity.BOTTOM)
        row.addView(valueView)
        row.addView(unitView, Ui.lp(Ui.WRAP, Ui.WRAP, l = 6, b = 10))
        addView(row, Ui.lp(t = 10))
        addView(subView, Ui.lp(t = 2))
        addView(Ui.spacer(c, h = 0, weight = 1f))
        val buttons = Ui.horizontal(c)
        minus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 60) { onSelect(); onMinus() }
        plus = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 60) { onSelect(); onPlus() }
        repeatOnHold(minus) { onMinus() }
        repeatOnHold(plus) { onPlus() }
        buttons.addView(minus, LayoutParams(0, Ui.u(60), 1f).apply { rightMargin = Ui.u(10) })
        buttons.addView(plus, LayoutParams(0, Ui.u(60), 1f))
        addView(buttons, Ui.lp(t = 14))
        isClickable = true
        setOnClickListener { onSelect() }
        refreshBg()
    }

    fun set(value: String, unit: String = "", sub: String = "", color: Int = Ui.TEXT) {
        valueView.text = value
        valueView.setTextColor(color)
        unitView.text = unit
        subView.text = sub
    }

    fun select(sel: Boolean) {
        if (sel == isSel) return
        isSel = sel
        refreshBg()
    }

    fun setEditable(on: Boolean) {
        Ui.setButtonEnabled(minus, on)
        Ui.setButtonEnabled(plus, on)
    }

    private fun refreshBg() {
        background = Ui.round(Ui.SURFACE, 26f, if (isSel) Ui.ACCENT else Ui.STROKE, if (isSel) 2.5f else 1f)
        iconView.color = if (isSel) Ui.ACCENT else Ui.TEXT2
    }

    companion object {
        /** Répète l'action tant que le bouton reste appuyé (après 450 ms). */
        fun repeatOnHold(v: View, action: () -> Unit) {
            val r = object : Runnable {
                override fun run() {
                    action()
                    v.postDelayed(this, 110)
                }
            }
            v.setOnTouchListener { view, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> view.postDelayed(r, 450)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.removeCallbacks(r)
                }
                false
            }
        }
    }
}

/** Anneau de température : valeur mesurée au centre, arc proportionnel, repère de consigne. */
class TempRing(c: Context) : View(c) {
    var actual = 0
    var target = 0
    var rpmLevel = 0
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun set(actual: Int, target: Int, rpmLevel: Int) {
        if (actual == this.actual && target == this.target && rpmLevel == this.rpmLevel) return
        this.actual = actual
        this.target = target
        this.rpmLevel = rpmLevel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val sw = size * 0.07f
        val cx = width / 2f
        val cy = height / 2f
        val rad = size / 2f - sw
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        arc.strokeWidth = sw
        arc.color = Ui.SURFACE3
        canvas.drawArc(r, 135f, 270f, false, arc)
        val frac = (actual / 130f).coerceIn(0f, 1f)
        arc.color = Ui.tempColor(actual)
        if (frac > 0f) canvas.drawArc(r, 135f, 270f * frac, false, arc)
        if (target > 0) {
            val a = Math.toRadians((135 + 270 * (target / 130f).coerceIn(0f, 1f)).toDouble())
            arc.strokeWidth = sw * 0.35f
            arc.color = Ui.TEXT
            val r1 = rad - sw * 0.9f
            val r2 = rad + sw * 0.9f
            canvas.drawLine(cx + (r1 * Math.cos(a)).toFloat(), cy + (r1 * Math.sin(a)).toFloat(),
                cx + (r2 * Math.cos(a)).toFloat(), cy + (r2 * Math.sin(a)).toFloat(), arc)
        }
        text.typeface = Ui.THIN
        text.color = Ui.TEXT
        text.textSize = size * 0.30f
        canvas.drawText("$actual°", cx + size * 0.03f, cy + size * 0.08f, text)
        text.typeface = Ui.REGULAR
        text.color = Ui.TEXT2
        text.textSize = size * 0.075f
        canvas.drawText(if (target > 0) "consigne $target °C" else "mesuré", cx, cy + size * 0.22f, text)
        // barres de vitesse réelle
        val bw = size * 0.035f
        val gap = size * 0.018f
        val total = 10 * bw + 9 * gap
        var x = cx - total / 2
        val base = cy + size * 0.40f
        for (i in 1..10) {
            arc.style = Paint.Style.FILL
            arc.color = if (i <= rpmLevel) Ui.COOL else Ui.SURFACE3
            val h = size * (0.03f + 0.006f * i)
            r.set(x, base - h, x + bw, base)
            canvas.drawRoundRect(r, bw / 3, bw / 3, arc)
            x += bw + gap
        }
        arc.style = Paint.Style.STROKE
    }
}

/** Bouton « maintenir pour Turbo ». */
class HoldButton(c: Context, label: String, private val onDown: () -> Unit, private val onUp: () -> Unit) : LinearLayout(c) {
    private val text: TextView

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER)
        setPadding(Ui.u(22), 0, Ui.u(22), 0)
        minimumHeight = Ui.u(64)
        background = Ui.round(Ui.SURFACE3, 32f)
        addView(Icon(c, Icon.Kind.BOLT, Ui.WARN), LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(10) })
        text = Ui.text(c, label, 20f, Ui.TEXT, Ui.MEDIUM)
        addView(text)
        isClickable = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                background = Ui.round(Ui.WARN, 32f)
                text.setTextColor(0xFF1A1405.toInt())
                onDown()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                background = Ui.round(Ui.SURFACE3, 32f)
                text.setTextColor(Ui.TEXT)
                onUp()
            }
        }
        return true
    }
}
