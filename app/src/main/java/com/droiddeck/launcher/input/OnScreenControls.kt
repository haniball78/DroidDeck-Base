package com.droiddeck.launcher.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@SuppressLint("ViewConstructor")
class OnScreenControls(
    context: Context,
    private val pad: PadBridge?,
    private val editing: Boolean = false,
    private val onKeyboard: (() -> Unit)? = null,
    private val onKey: ((KeyEvent) -> Unit)? = null,
) : View(context) {

    private class Control(
        val id: String,
        val group: String,
        val stick: Int,
        val radiusDp: Float,
        val offsetXDp: Float,
        val offsetYDp: Float,
    ) {
        var target = id
        var radius = 0f
        var cx = 0f
        var cy = 0f
        var pressedBy = -1
        var kx = 0f
        var ky = 0f
        var ax = 0f
        var ay = 0f
        var dirty = false
        var clicked = false
        var lastUp = 0L
        val wide = id in shoulderIds
        val halfW get() = if (wide) radius * SHOULDER_WIDTH else radius
        val halfH get() = if (wide) radius * SHOULDER_HEIGHT else radius

        fun contains(x: Float, y: Float, r: Float, padding: Float = 0f): Boolean {
            val dx = x - cx
            val dy = y - cy
            if (wide) return abs(dx) <= halfW + r * 0.25f + padding && abs(dy) <= halfH + r * 0.25f + padding
            val hit = r * 1.25f + padding
            return dx * dx + dy * dy <= hit * hit
        }

        fun drag(x: Float, y: Float) {
            var dx = x - ax
            var dy = y - ay
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius) { dx = dx / d * radius; dy = dy / d * radius }
            kx = dx; ky = dy; dirty = true
        }
    }

    private val controls = listOf(
        Control("up", "dpad", -1, 26f, 0f, -52f),
        Control("right", "dpad", -1, 26f, 52f, 0f),
        Control("down", "dpad", -1, 26f, 0f, 52f),
        Control("left", "dpad", -1, 26f, -52f, 0f),
        Control("ls", "ls", 0, 48f, 0f, 0f),
        Control("rs", "rs", 1, 48f, 0f, 0f),
        Control("a", "face", -1, 30f, 0f, 50f),
        Control("b", "face", -1, 30f, 50f, 0f),
        Control("x", "face", -1, 30f, -50f, 0f),
        Control("y", "face", -1, 30f, 0f, -50f),
        Control("lb", "lb", -1, 24f, 0f, 0f),
        Control("rb", "rb", -1, 24f, 0f, 0f),
        Control("lt", "lt", -1, 24f, 0f, 0f),
        Control("rt", "rt", -1, 24f, 0f, 0f),
        Control("select", "select", -1, 20f, 0f, 0f),
        Control("start", "start", -1, 20f, 0f, 0f),
        Control("guide", "guide", -1, 22f, 0f, 0f),
        Control("qam", "qam", -1, 22f, 0f, 0f),
        Control("smart", "smart", -1, 24f, 0f, 0f),
        Control("moveToggle", "smart", -1, 20f, 0f, 0f),
        Control("customKey", "customKey", -1, 24f, 0f, 0f),
    )

    private val groups = controls.map { it.group }.distinct()

    private var buttonsOnly = false
    val isButtonsOnly: Boolean get() = buttonsOnly
    private var quickHidden = false
    private var quickPressedBy = -1
    private var keyboardPressedBy = -1
    private var forwardedPointerBits = 0
    var onTouchMiss: ((MotionEvent) -> Unit)? = null
    var onPointerCaptureChange: ((Boolean) -> Unit)? = null
    private var settings = ControllerPrefs.read(context)
    private var snap = settings.snap
    private var safe = Rect()
    private var selected: String? = null
    private var snapX: Float? = null
    private var snapY: Float? = null
    private var grabX = 0f
    private var grabY = 0f
    private var ignoreSaved = false
    private var fit = 1f

    private val gripSessionStartMs = SystemClock.uptimeMillis()
    internal var gripNow: () -> Long = { SystemClock.uptimeMillis() }
    private val gripSmart = GripScheme.SmartButton()
    private val gripTrackers = HashMap<Int, GripScheme.PressTracker>()
    private val gripLongPressCheck = HashMap<Int, Runnable>()
    private val gripHandler = Handler(Looper.getMainLooper())
    private var customKeys: List<GripScheme.CustomKeyButton> = emptyList()
    var gameKey: String? = null
        set(value) {
            if (field == value) return
            field = value
            loadCustomKeys()
        }
    private var customKeyDrag: Control? = null
    private var customKeyDragMoved = false
    private var customKeyDownX = 0f
    private var customKeyDownY = 0f

    private val gripTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            if (settings.newGrip && !editing && !buttonsOnly) {
                invalidate()
                gripHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun gripSessionElapsedMs() = gripNow() - gripSessionStartMs
    private fun gripStickAlpha() = GripScheme.stickAlpha(gripSessionElapsedMs())
    private fun gripSmartOpen() = gripSmart.isOpen(gripSessionElapsedMs(), gripNow())

    private fun loadCustomKeys() {
        val stored = GripScheme.CustomKeys.load(context, gameKey ?: GripScheme.DEFAULT_GAME_KEY)
        customKeys = stored.ifEmpty {
            listOf(GripScheme.CustomKeyButton("M", KeyEvent.KEYCODE_M, -1f, -1f))
        }
    }

    private fun controlById(id: String) = controls.first { it.id == id }

    private var idleFill = 0
    private var heldFill = 0
    private var idleStroke = 0
    private var heldStroke = 0
    private var idleText = 0
    private var stickFill = 0
    private var knobFill = 0

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()
    private val box = RectF()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        loadCustomKeys()
        applySettings()
    }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun faded(color: Int, factor: Float): Int =
        if (factor == 1f) color else Color.argb(
            (Color.alpha(color) * factor).toInt().coerceIn(0, 255),
            Color.red(color), Color.green(color), Color.blue(color))

    private fun scaled(value: Float) = dp(value) * settings.size / 100f * fit

    // Approved grip preview sizes, new grip only: every grip control 0.75x,
    // shoulders 0.75x then 1.10x, SMART 1.2/1.38x.
    private fun gripFactor(control: Control): Float =
        if (!settings.newGrip || editing) 1f else when (control.id) {
            "lb", "rb", "lt", "rt" -> 0.825f
            "smart" -> 1.2f / 1.38f
            else -> 0.75f
        }

    private fun scaledOffset(control: Control, value: Float) = scaled(value * gripFactor(control))

    private fun fitScale(): Float {
        val metrics = resources.displayMetrics
        val heightMm = (height - safe.top - safe.bottom) / pxPerMm(metrics.ydpi)
        val faceMm = dp(60f) / pxPerMm(metrics.xdpi)
        val floor = (MIN_FACE_MM / faceMm).coerceAtMost(1f)
        return (heightMm / FULL_SIZE_HEIGHT_MM).coerceIn(floor, 1f)
    }

    fun reload() {
        releaseAll()
        settings = ControllerPrefs.read(context)
        loadCustomKeys()
        setSnap(settings.snap)
        applySettings()
        relayout()
        gripHandler.removeCallbacks(gripTick)
        if (settings.newGrip) gripHandler.post(gripTick)
    }

    private fun clearSnapGuides() {
        if (snapX == null && snapY == null) return
        snapX = null
        snapY = null
        invalidate()
    }

    private fun applySettings() {
        for (control in controls) control.target = settings.mapping[control.id] ?: control.id
        val tint = settings.tint
        val alpha = settings.opacity / 100f
        fun shade(a: Int, f: Float) = Color.argb((a * alpha).toInt(), (Color.red(tint) * f).toInt(), (Color.green(tint) * f).toInt(), (Color.blue(tint) * f).toInt())
        fun light(a: Int, f: Float) = Color.argb(
            (a * alpha).toInt(),
            (Color.red(tint) + (255 - Color.red(tint)) * f).toInt(),
            (Color.green(tint) + (255 - Color.green(tint)) * f).toInt(),
            (Color.blue(tint) + (255 - Color.blue(tint)) * f).toInt(),
        )
        idleFill = shade(80, 0.12f)
        heldFill = shade(160, 1f)
        idleStroke = light(150, 0.25f)
        heldStroke = light(230, 0.7f)
        idleText = light(210, 0.75f)
        stickFill = shade(60, 0.12f)
        knobFill = shade(120, 0.35f)
        stroke.strokeWidth = dp(1.5f)
    }

    fun setButtonsOnly(enabled: Boolean) {
        if (buttonsOnly == enabled) return
        releaseAll()
        buttonsOnly = enabled
        quickHidden = false
        relayout()
    }

    fun setQuickHidden(hidden: Boolean) {
        if (buttonsOnly || editing || quickHidden == hidden) return
        if (hidden) releaseAll()
        quickHidden = hidden
        invalidate()
    }

    fun setSnap(on: Boolean) {
        snap = on
        if (!on) clearSnapGuides()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
        val fresh = Rect(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0, cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
        if (fresh != safe) { safe = fresh; relayout() }
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (settings.newGrip) gripHandler.post(gripTick)
    }

    override fun onDetachedFromWindow() {
        gripHandler.removeCallbacks(gripTick)
        gripLongPressCheck.values.forEach { gripHandler.removeCallbacks(it) }
        gripLongPressCheck.clear()
        gripTrackers.clear()
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun relayout() {
        if (width <= 0 || height <= 0) return
        fit = if (buttonsOnly && !editing) 1f else fitScale()
        for (control in controls) control.radius = if (buttonsOnly && !editing && (control.id == "guide" || control.id == "qam")) dp(30f) else scaled(control.radiusDp * gripFactor(control))
        if (buttonsOnly && !editing) {
            val inset = dp(44f)
            put("guide", safe.left + inset, height - safe.bottom - inset)
            put("qam", width - safe.right - inset, height - safe.bottom - inset)
        } else if (settings.newGrip && !editing) {
            placeAuto(width.toFloat(), height.toFloat())
            placeGrip()
        } else {
            placeAuto(width.toFloat(), height.toFloat())
            if (!ignoreSaved) for ((group, pos) in ControllerPrefs.layout(context, width, height)) {
                if (group in groups) put(group, pos.first * width, pos.second * height)
            }
        }
        groups.forEach { clamp(it) }
        invalidate()
    }

    private fun placeGrip() {
        val gap = dp(8f)
        val colX = safe.left + dp(34f)
        val quickY = height - safe.bottom - dp(34f)
        var top = quickY - dp(26f) - gap
        for (id in listOf("smart", "start", "moveToggle", "select", "qam", "guide")) {
            val control = controlById(id)
            control.cx = colX
            control.cy = top - control.radius
            top -= control.radius * 2f + gap
            if (id == "smart" || id == "start") top -= dp(14f)
        }
        val saved = customKeys.firstOrNull()
        val custom = controlById("customKey")
        if (saved != null && isVisible(custom)) {
            if (saved.xFrac < 0f) {
                val (faceX, faceY) = centre("face")
                custom.cx = faceX
                custom.cy = faceY + extent("face") + gap + custom.radius
                customKeys = listOf(saved.copy(xFrac = custom.cx / width, yFrac = custom.cy / height))
            } else {
                custom.cx = saved.xFrac * width
                custom.cy = saved.yFrac * height
            }
        }
    }

    private fun pxPerMm(dpi: Float): Float {
        val nominal = resources.displayMetrics.densityDpi.toFloat()
        return (if (dpi > nominal * 0.6f && dpi < nominal * 1.6f) dpi else nominal) / 25.4f
    }

    private fun placeAuto(w: Float, h: Float) {
        val metrics = resources.displayMetrics
        val mmX = pxPerMm(metrics.xdpi)
        val mmY = pxPerMm(metrics.ydpi)
        val usableW = w - safe.left - safe.right
        val usableH = h - safe.top - safe.bottom
        val inboard = (0.17f * usableW / mmX).coerceIn(24f, 40f) * mmX
        val lift = (0.285f * usableH / mmY).coerceIn(22f, 45f) * mmY
        val gap = dp(12f)
        val floor = BOTTOM_MARGIN_MM * mmY
        val drop = max(drop("dpad", "ls", gap), drop("rs", "face", gap))
        val restY = min(h - safe.bottom - lift, h - safe.bottom - floor - drop)
        put("ls", safe.left + inboard, restY)
        put("face", w - safe.right - inboard, restY)
        besideBelow("dpad", "ls", 1f, gap, floor)
        besideBelow("rs", "face", -1f, gap, floor)
        val phone = usableH / mmY < PHONE_MAX_HEIGHT_MM
        val shoulderY = if (phone) safe.top + floor + halfH("lb")
            else min(centre("ls").second - extent("ls"), centre("face").second - extent("face")) - gap - halfH("lb")
        shoulders("ls", "lb", "lt", 1f, gap, shoulderY)
        shoulders("face", "rb", "rt", -1f, gap, shoulderY)
        val bottom = h - safe.bottom - floor - extent("guide")
        val spread = extent("guide") + gap / 2f
        put("guide", w / 2f - spread, bottom)
        put("qam", w / 2f + spread, bottom)
        val inner = max(centre("dpad").first + extent("dpad"), w - centre("rs").first + extent("rs")) + gap + extent("select")
        put("select", inner, restY)
        put("start", w - inner, restY)
        if (phone) {
            val top = max(centre("lb").first + extent("lb"), w - centre("rb").first + extent("rb")) + gap * 2.5f + extent("select")
            put("select", top, shoulderY)
            put("start", w - top, shoulderY)
        }
        if (crowded("select", gap) || crowded("start", gap)) {
            above("select", "dpad", gap)
            val (sx, sy) = centre("select")
            put("start", w - sx, sy)
            if (crowded("start", gap)) above("start", "rs", gap)
        }
        if (crowded("guide", gap, gap, "qam") || crowded("qam", gap, gap, "guide")) {
            val row = centre("select").second
            put("guide", w / 2f - spread, row)
            put("qam", w / 2f + spread, row)
            if (crowded("guide", gap, gap, "qam") || crowded("qam", gap, gap, "guide")) {
                val top = safe.top + dp(8f) + extent("guide")
                put("guide", w / 2f - spread, top)
                put("qam", w / 2f + spread, top)
            }
        }
    }

    private fun drop(group: String, anchor: String, gap: Float): Float =
        (extent(anchor) + extent(group) + gap) * sin(Math.toRadians(DROP_DEGREES)).toFloat() + extent(group)

    private fun besideBelow(group: String, anchor: String, inward: Float, gap: Float, margin: Float) {
        val (ax, ay) = centre(anchor)
        val reach = extent(anchor) + extent(group) + gap
        val floor = height - safe.bottom - margin - extent(group)
        val dy = min(reach * sin(Math.toRadians(DROP_DEGREES)).toFloat(), floor - ay)
        val dx = sqrt(max(0f, reach * reach - dy * dy))
        put(group, ax + inward * dx, ay + dy)
    }

    private fun shoulders(anchor: String, bumper: String, trigger: String, inward: Float, gap: Float, y: Float) {
        val (ax, _) = centre(anchor)
        val spread = extent(bumper) + gap / 2f
        put(bumper, ax + inward * spread, y)
        put(trigger, ax - inward * spread, y)
    }

    private fun above(group: String, anchor: String, gap: Float) {
        val (ax, ay) = centre(anchor)
        put(group, ax, ay - extent(anchor) - gap - extent(group))
    }

    private fun crowded(group: String, gap: Float, space: Float = gap / 2f, except: String? = null): Boolean {
        val (x, y) = centre(group)
        return groups.any { other ->
            if (other == group || other == except) return@any false
            val (ox, oy) = centre(other)
            val dx = x - ox
            val dy = y - oy
            sqrt(dx * dx + dy * dy) < extent(group) + extent(other) + space
        }
    }

    private fun members(group: String) = controls.filter { it.group == group }

    private fun centre(group: String): Pair<Float, Float> {
        val first = members(group).first()
        return (first.cx - scaledOffset(first, first.offsetXDp)) to (first.cy - scaledOffset(first, first.offsetYDp))
    }

    private fun extent(group: String): Float = members(group).maxOf { control ->
        sqrt(scaledOffset(control, control.offsetXDp).let { it * it } + scaledOffset(control, control.offsetYDp).let { it * it }) + control.halfW
    }

    private fun halfH(group: String): Float = members(group).maxOf { abs(scaledOffset(it, it.offsetYDp)) + it.halfH }

    private fun put(group: String, x: Float, y: Float) {
        for (control in members(group)) {
            control.cx = x + scaledOffset(control, control.offsetXDp)
            control.cy = y + scaledOffset(control, control.offsetYDp)
        }
    }

    private fun clamp(group: String) {
        val list = members(group)
        val margin = dp(4f)
        val minX = list.minOf { it.cx - it.halfW } - safe.left - margin
        val maxX = list.maxOf { it.cx + it.halfW } - (width - safe.right - margin)
        val minY = list.minOf { it.cy - it.halfH } - safe.top - margin
        val maxY = list.maxOf { it.cy + it.halfH } - (height - safe.bottom - margin)
        val dx = if (minX < 0) -minX else if (maxX > 0) -maxX else 0f
        val dy = if (minY < 0) -minY else if (maxY > 0) -maxY else 0f
        if (dx == 0f && dy == 0f) return
        for (control in list) { control.cx += dx; control.cy += dy }
    }

    fun saveLayout() {
        if (width <= 0 || height <= 0) return
        if (ignoreSaved) { ControllerPrefs.resetLayout(context, width, height); return }
        ControllerPrefs.setLayout(context, width, height, groups.associateWith { group ->
            val (x, y) = centre(group)
            x / width to y / height
        })
    }

    fun resetLayout() {
        ControllerPrefs.resetLayout(context, width, height)
        ignoreSaved = true
        selected = null
        relayout()
    }

    private fun label(control: Control): String = when (control.target) {
        "ls" -> "L"
        "rs" -> "R"
        "select" -> "⧉"
        "start" -> "☰"
        "guide" -> if (buttonsOnly && !editing) "Steam" else "◉"
        "qam" -> "⋯"
        "customKey" -> customKeys.firstOrNull()?.label ?: "M"
        else -> control.target.uppercase()
    }

    override fun onDraw(canvas: Canvas) {
        if (!quickHidden) for (control in controls) {
            if (!isVisible(control)) continue
            if (control.stick >= 0 && settings.adaptiveSticks && !editing && control.pressedBy == -1 &&
                !(settings.newGrip && !buttonsOnly && control.id == "ls")) continue
            val held = control.pressedBy != -1
            val radius = control.radius
            if (control.stick >= 0) {
                val fade = if (settings.newGrip && !editing && !buttonsOnly && control.id == "ls") gripStickAlpha() else 1f
                val bx = if (held) control.ax else control.cx
                val by = if (held) control.ay else control.cy
                fill.color = if (held) faded(heldFill, fade) else faded(stickFill, fade)
                canvas.drawCircle(bx, by, radius, fill)
                stroke.color = if (held) faded(heldStroke, fade) else faded(idleStroke, fade)
                canvas.drawCircle(bx, by, radius, stroke)
                val knob = radius * 0.46f
                fill.color = if (held || control.clicked) faded(heldFill, fade) else faded(knobFill, fade)
                canvas.drawCircle(bx + control.kx, by + control.ky, knob, fill)
                stroke.color = if (held) faded(heldStroke, fade) else faded(idleStroke, fade)
                canvas.drawCircle(bx + control.kx, by + control.ky, knob, stroke)
                text.color = if (held) faded(Color.WHITE, fade) else faded(idleText, fade)
                text.textSize = knob * 0.8f
                canvas.drawText(label(control), bx + control.kx, by + control.ky + text.textSize * 0.35f, text)
            } else if (control.wide) {
                box.set(control.cx - control.halfW, control.cy - control.halfH, control.cx + control.halfW, control.cy + control.halfH)
                val corner = control.halfH * 0.55f
                fill.color = if (held) heldFill else idleFill
                canvas.drawRoundRect(box, corner, corner, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawRoundRect(box, corner, corner, stroke)
                text.color = if (held) Color.WHITE else idleText
                text.textSize = control.halfH * 0.8f
                canvas.drawText(label(control), control.cx, control.cy + text.textSize * 0.35f, text)
            } else {
                if (settings.newGrip && !editing && !buttonsOnly && control.id == "smart") {
                    drawSmartButton(canvas, control, held)
                    continue
                }
                if (settings.newGrip && !editing && !buttonsOnly && control.id == "moveToggle") {
                    drawMoveToggle(canvas, control, held)
                    continue
                }
                fill.color = if (held) heldFill else idleFill
                canvas.drawCircle(control.cx, control.cy, radius, fill)
                stroke.color = if (held) heldStroke else idleStroke
                canvas.drawCircle(control.cx, control.cy, radius, stroke)
                text.color = if (held) Color.WHITE else idleText
                val direction = directions.indexOf(control.target)
                if (direction >= 0) {
                    drawArrow(canvas, control.cx, control.cy, radius * 0.34f, direction)
                    continue
                }
                val name = label(control)
                text.textSize = if (name.length > 2) dp(11f) else radius * if (name.length == 2) 0.62f else 0.85f
                canvas.drawText(name, control.cx, control.cy + text.textSize * 0.35f, text)
                if (settings.newGrip && !editing && !buttonsOnly && control.id == "start") {
                    text.color = if (held) Color.WHITE else idleText
                    text.textSize = dp(10f)
                    canvas.drawText("START", control.cx, control.cy + radius + text.textSize, text)
                }
            }
        }
        if (editing) {
            stroke.color = heldStroke
            snapX?.let { canvas.drawLine(it, safe.top.toFloat(), it, (height - safe.bottom).toFloat(), stroke) }
            snapY?.let { canvas.drawLine(safe.left.toFloat(), it, (width - safe.right).toFloat(), it, stroke) }
            selected?.let { group ->
                val (x, y) = centre(group)
                val single = members(group).singleOrNull()
                if (single?.wide == true) {
                    val pad = dp(6f)
                    box.set(x - single.halfW - pad, y - single.halfH - pad, x + single.halfW + pad, y + single.halfH + pad)
                    canvas.drawRoundRect(box, single.halfH, single.halfH, stroke)
                } else canvas.drawCircle(x, y, extent(group) + dp(6f), stroke)
            }
        } else if (!buttonsOnly) {
            val (x, y) = quickCenter()
            val radius = dp(19.8f)
            fill.color = Color.argb(190, Color.red(idleFill), Color.green(idleFill), Color.blue(idleFill))
            canvas.drawCircle(x, y, radius, fill)
            stroke.color = idleStroke
            canvas.drawCircle(x, y, radius, stroke)
            text.color = idleText
            text.textSize = dp(24.2f)
            canvas.drawText(if (quickHidden) "+" else "−", x, y + text.textSize * 0.35f, text)
        }
        if (!editing && settings.keyboardButton && onKeyboard != null) {
            val (x, y) = keyboardCenter()
            fill.color = Color.argb(190, Color.red(idleFill), Color.green(idleFill), Color.blue(idleFill))
            canvas.drawCircle(x, y, dp(19.8f), fill)
            stroke.color = if (keyboardPressedBy == -1) idleStroke else heldStroke
            canvas.drawCircle(x, y, dp(19.8f), stroke)
            box.set(x - dp(11f), y - dp(7.7f), x + dp(11f), y + dp(7.7f))
            canvas.drawRoundRect(box, dp(2.2f), dp(2.2f), stroke)
            for (row in 0..1) for (col in 0..2) {
                val kx = x + dp(-6.6f + col * 5.5f)
                val ky = y + dp(-3.3f + row * 4.4f)
                canvas.drawLine(kx, ky, kx + dp(2.2f), ky, stroke)
            }
            canvas.drawLine(x - dp(5.5f), y + dp(4.4f), x + dp(5.5f), y + dp(4.4f), stroke)
        }
    }

    private fun drawSmartButton(canvas: Canvas, control: Control, held: Boolean) {
        fill.color = if (held) heldFill else idleFill
        canvas.drawCircle(control.cx, control.cy, control.radius, fill)
        stroke.color = if (held) heldStroke else idleStroke
        canvas.drawCircle(control.cx, control.cy, control.radius, stroke)
        fill.color = if (held) Color.WHITE else idleText
        canvas.drawCircle(control.cx, control.cy, control.radius * 0.22f, fill)
        text.color = if (held) Color.WHITE else idleText
        text.textSize = dp(10f)
        canvas.drawText("SMART", control.cx, control.cy + control.radius + text.textSize, text)
    }

    private fun drawMoveToggle(canvas: Canvas, control: Control, held: Boolean) {
        fill.color = if (held) heldFill else idleFill
        canvas.drawCircle(control.cx, control.cy, control.radius, fill)
        stroke.color = if (held) heldStroke else idleStroke
        canvas.drawCircle(control.cx, control.cy, control.radius, stroke)
        val stickMode = settings.gripMovement == GripScheme.Movement.STICK
        val top = control.cy - control.radius * 0.30f
        val bottom = control.cy + control.radius * 0.30f
        stroke.color = if (stickMode) heldStroke else idleText
        canvas.drawCircle(control.cx, top, control.radius * 0.22f, stroke)
        fill.color = if (stickMode) heldStroke else idleText
        canvas.drawCircle(control.cx, top, control.radius * 0.08f, fill)
        stroke.color = if (!stickMode) heldStroke else idleText
        stroke.strokeWidth = dp(2f)
        val arm = control.radius * 0.20f
        canvas.drawLine(control.cx - arm, bottom, control.cx + arm, bottom, stroke)
        canvas.drawLine(control.cx, bottom - arm, control.cx, bottom + arm, stroke)
        stroke.strokeWidth = dp(1.5f)
    }

    private fun quickCenter() = (safe.left + dp(34f)) to (height - safe.bottom - dp(34f))

    private fun keyboardCenter() = if (buttonsOnly)
        width / 2f to (height - safe.bottom - dp(44f))
    else (safe.left + dp(90f)) to (height - safe.bottom - dp(34f))

    private fun keyboardContains(x: Float, y: Float): Boolean {
        if (!settings.keyboardButton || onKeyboard == null) return false
        val (cx, cy) = keyboardCenter()
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= dp(28.6f) * dp(28.6f)
    }

    private fun quickContains(x: Float, y: Float): Boolean {
        val (cx, cy) = quickCenter()
        val radius = dp(28.6f)
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= radius * radius
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, size: Float, direction: Int) {
        val (fx, fy) = when (direction) {
            0 -> 0f to -1f
            1 -> 1f to 0f
            2 -> 0f to 1f
            else -> -1f to 0f
        }
        arrow.reset()
        arrow.moveTo(x + fx * size, y + fy * size)
        arrow.lineTo(x - fx * size * 0.7f - fy * size, y - fy * size * 0.7f + fx * size)
        arrow.lineTo(x - fx * size * 0.7f + fy * size, y - fy * size * 0.7f - fx * size)
        arrow.close()
        fill.color = text.color
        canvas.drawPath(arrow, fill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) return onEditTouch(event)
        if (settings.newGrip && !buttonsOnly) return onGripTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) forwardedPointerBits = 0
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && forwardMiss(event, x, y)) return true
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && keyboardContains(x, y)) {
                    keyboardPressedBy = event.getPointerId(index)
                    invalidate()
                    return true
                }
                if (keyboardPressedBy != -1) return true
                if (!buttonsOnly && event.actionMasked == MotionEvent.ACTION_DOWN && quickContains(x, y)) {
                    quickPressedBy = event.getPointerId(index)
                    return true
                }
                if (quickPressedBy != -1) return true
                if (quickHidden) return false
                val control = controlAt(x, y) ?: adaptiveStickAt(x, y) ?: return false
                if (control.pressedBy != -1) return true
                control.pressedBy = event.getPointerId(index)
                if (control.stick >= 0) {
                    control.clicked = settings.stickClick && control.lastUp > 0L && event.eventTime - control.lastUp < DOUBLE_TAP_MS
                    val adaptive = settings.adaptiveSticks
                    control.ax = if (adaptive) event.getX(index) else control.cx
                    control.ay = if (adaptive) event.getY(index) else control.cy
                    control.drag(event.getX(index), event.getY(index))
                }
                requestUnbufferedDispatch(event)
                apply()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (keyboardPressedBy != -1) return true
                if (quickPressedBy != -1) return true
                var changed = false
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    if (forwardedPointerBits and (1 shl pointer) != 0) continue
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val stick = controls.firstOrNull { it.stick >= 0 && it.pressedBy == pointer }
                    if (stick != null) { stick.drag(x, y); changed = true; continue }
                    val over = controlAt(x, y)?.takeIf { it.stick < 0 }
                    for (control in controls) {
                        if (control.stick < 0 && control.pressedBy == pointer && control !== over) {
                            control.pressedBy = -1
                            changed = true
                        }
                    }
                    if (over != null && over.pressedBy == -1) {
                        over.pressedBy = pointer
                        changed = true
                    }
                }
                if (changed) apply()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointer = event.getPointerId(event.actionIndex)
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    forwardedPointerBits = 0
                    releaseAll()
                    return true
                }
                if (forwardedPointerBits and (1 shl pointer) != 0) {
                    forwardedPointerBits = forwardedPointerBits and (1 shl pointer).inv()
                    return true
                }
                if (keyboardPressedBy == pointer) {
                    val tapped = event.actionMasked == MotionEvent.ACTION_UP && keyboardContains(event.x, event.y)
                    keyboardPressedBy = -1
                    invalidate()
                    if (tapped) { releaseAll(); onKeyboard?.invoke() }
                    return true
                }
                if (quickPressedBy == pointer) {
                    val toggled = event.actionMasked == MotionEvent.ACTION_UP && quickContains(event.x, event.y)
                    quickPressedBy = -1
                    if (toggled) setQuickHidden(!quickHidden)
                    return true
                }
                if (quickHidden) return false
                var changed = false
                for (control in controls) {
                    if (control.pressedBy == pointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (control.pressedBy != -1) changed = true
                        if (control.stick >= 0 && control.pressedBy != -1) control.lastUp = if (control.clicked || event.actionMasked == MotionEvent.ACTION_CANCEL) 0L else event.eventTime
                        control.pressedBy = -1
                        control.clicked = false
                        if (control.stick >= 0 && (control.kx != 0f || control.ky != 0f)) control.dirty = true
                        control.kx = 0f; control.ky = 0f
                    }
                }
                if (changed) apply()
                return true
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onGripTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) forwardedPointerBits = 0
                if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && gripForwardMiss(event, x, y)) return true
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && keyboardContains(x, y)) {
                    keyboardPressedBy = event.getPointerId(index)
                    invalidate()
                    return true
                }
                if (keyboardPressedBy != -1) return true
                if (!buttonsOnly && event.actionMasked == MotionEvent.ACTION_DOWN && quickContains(x, y)) {
                    quickPressedBy = event.getPointerId(index)
                    return true
                }
                if (quickPressedBy != -1) return true
                if (quickHidden) return false
                val pointerId = event.getPointerId(index)
                val keyButton = customKeyAt(x, y)
                if (keyButton != null) {
                    keyButton.pressedBy = pointerId
                    customKeyDrag = keyButton
                    customKeyDragMoved = false
                    customKeyDownX = x
                    customKeyDownY = y
                    requestUnbufferedDispatch(event)
                    apply()
                    return true
                }
                val zone = GripScheme.zone(width.toFloat(), height.toFloat())
                if (zone.contains(x, y)) {
                    val button = controlAt(x, y)
                    if (button != null) {
                        button.pressedBy = pointerId
                        val tracker = GripScheme.PressTracker(dp(GripScheme.DRAG_THRESHOLD_DP))
                        tracker.onDown(x, y, gripNow())
                        gripTrackers[pointerId] = tracker
                        scheduleGripLongPress(pointerId)
                        requestUnbufferedDispatch(event)
                        apply()
                        return true
                    }
                    if (settings.gripMovement == GripScheme.Movement.STICK) {
                        val stick = controlById("ls")
                        if (stick.pressedBy == -1) {
                            stick.pressedBy = pointerId
                            stick.clicked = settings.stickClick && stick.lastUp > 0L && event.eventTime - stick.lastUp < DOUBLE_TAP_MS
                            stick.ax = x
                            stick.ay = y
                            stick.drag(x, y)
                            requestUnbufferedDispatch(event)
                            apply()
                            return true
                        }
                        return true
                    }
                    return gripForwardMiss(event, x, y)
                }
                val control = controlAt(x, y) ?: gripAdaptiveStickAt(x, y) ?: return gripForwardMiss(event, x, y)
                if (control.pressedBy != -1) return true
                control.pressedBy = pointerId
                if (control.stick >= 0) {
                    control.clicked = settings.stickClick && control.lastUp > 0L && event.eventTime - control.lastUp < DOUBLE_TAP_MS
                    val adaptive = settings.adaptiveSticks
                    control.ax = if (adaptive) event.getX(index) else control.cx
                    control.ay = if (adaptive) event.getY(index) else control.cy
                    control.drag(event.getX(index), event.getY(index))
                }
                requestUnbufferedDispatch(event)
                apply()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (keyboardPressedBy != -1) return true
                if (quickPressedBy != -1) return true
                var changed = false
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    if (forwardedPointerBits and (1 shl pointer) != 0) continue
                    val x = event.getX(index)
                    val y = event.getY(index)
                    val drag = customKeyDrag
                    if (drag != null && drag.pressedBy == pointer) {
                        if (!customKeyDragMoved && hypot(x - customKeyDownX, y - customKeyDownY) > dp(GripScheme.DRAG_THRESHOLD_DP)) {
                            customKeyDragMoved = true
                        }
                        if (customKeyDragMoved) {
                            drag.cx = x.coerceIn(drag.radius, width - drag.radius)
                            drag.cy = y.coerceIn(drag.radius, height - drag.radius)
                            changed = true
                        }
                        continue
                    }
                    val tracker = gripTrackers[pointer]
                    if (tracker != null) {
                        val outcome = tracker.onMove(x, y, gripNow())
                        if (settings.gripMovement == GripScheme.Movement.STICK) {
                            if (outcome == GripScheme.PressOutcome.TO_STICK) {
                                convertGripToStick(pointer, x, y)
                                changed = true
                            }
                            continue
                        }
                        if (outcome == GripScheme.PressOutcome.TO_STICK) {
                            gripTrackers.remove(pointer)
                            cancelGripLongPress(pointer)
                        } else {
                            continue
                        }
                    }
                    val stick = controls.firstOrNull { it.stick >= 0 && it.pressedBy == pointer }
                    if (stick != null) { stick.drag(x, y); changed = true; continue }
                    val over = controlAt(x, y)?.takeIf { it.stick < 0 }
                    for (control in controls) {
                        if (control.stick < 0 && control.pressedBy == pointer && control !== over) {
                            control.pressedBy = -1
                            changed = true
                        }
                    }
                    if (over != null && over.pressedBy == -1) {
                        over.pressedBy = pointer
                        changed = true
                    }
                }
                if (changed) apply()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val pointer = event.getPointerId(event.actionIndex)
                if (forwardedPointerBits != 0) forwardPointers(event, forwardedPointerBits)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    forwardedPointerBits = 0
                    cancelGripLongPress(pointer)
                    gripTrackers.remove(pointer)
                    releaseAll()
                    return true
                }
                if (forwardedPointerBits and (1 shl pointer) != 0) {
                    forwardedPointerBits = forwardedPointerBits and (1 shl pointer).inv()
                    return true
                }
                if (keyboardPressedBy == pointer) {
                    keyboardPressedBy = -1
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL && quickHidden) {
                        setQuickHidden(false)
                        invalidate()
                    } else if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        onKeyboard?.invoke()
                    }
                    invalidate()
                    return true
                }
                if (quickPressedBy == pointer) {
                    quickPressedBy = -1
                    if (event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        setQuickHidden(!quickHidden)
                        invalidate()
                    }
                    return true
                }
                if (quickHidden) return false
                val drag = customKeyDrag
                if (drag != null && drag.pressedBy == pointer) {
                    val moved = customKeyDragMoved
                    val button = customKeys.firstOrNull()
                    drag.pressedBy = -1
                    customKeyDrag = null
                    if (moved) {
                        if (button != null) {
                            val movedButton = button.copy(xFrac = drag.cx / width, yFrac = drag.cy / height)
                            GripScheme.CustomKeys.save(context, gameKey ?: GripScheme.DEFAULT_GAME_KEY, listOf(movedButton))
                            customKeys = listOf(movedButton)
                        }
                    } else if (button != null) {
                        sendCustomKeyTap(button)
                    }
                    apply()
                    return true
                }
                val tracker = gripTrackers.remove(pointer)
                cancelGripLongPress(pointer)
                val tapped = controls.firstOrNull { it.pressedBy == pointer }
                val outcome = tracker?.onUp() ?: GripScheme.PressOutcome.NONE
                if (outcome == GripScheme.PressOutcome.TAP && tapped != null) {
                    when (tapped.id) {
                        "smart" -> {
                            gripSmart.toggle(gripSessionElapsedMs(), gripNow())
                            invalidate()
                        }
                        "moveToggle" -> {
                            val next = if (settings.gripMovement == GripScheme.Movement.STICK) GripScheme.Movement.DPAD else GripScheme.Movement.STICK
                            ControllerPrefs.setGripMovement(context, next)
                            reload()
                        }
                    }
                }
                var changed = false
                for (control in controls) {
                    if (control.pressedBy == pointer || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (control.pressedBy != -1) changed = true
                        if (control.stick >= 0 && control.pressedBy != -1) control.lastUp = if (control.clicked || event.actionMasked == MotionEvent.ACTION_CANCEL) 0L else event.eventTime
                        control.pressedBy = -1
                        control.clicked = false
                        if (control.stick >= 0 && (control.kx != 0f || control.ky != 0f)) control.dirty = true
                        control.kx = 0f
                        control.ky = 0f
                    }
                }
                if (changed) apply()
                return true
            }
        }
        return false
    }

    private fun customKeyAt(x: Float, y: Float): Control? {
        val custom = controlById("customKey")
        return if (isVisible(custom) && custom.contains(x, y, custom.radius)) custom else null
    }

    private fun sendCustomKeyTap(button: GripScheme.CustomKeyButton) {
        val send = onKey ?: return
        val now = System.currentTimeMillis()
        send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, button.keyCode, 0))
        send(KeyEvent(now, now, KeyEvent.ACTION_UP, button.keyCode, 0))
    }

    private fun gripForwardMiss(event: MotionEvent, x: Float, y: Float): Boolean {
        if (onTouchMiss == null || keyboardPressedBy != -1 || quickPressedBy != -1) return false
        val zone = GripScheme.zone(width.toFloat(), height.toFloat())
        if (settings.gripMovement == GripScheme.Movement.STICK && zone.contains(x, y)) return false
        if (!quickHidden && (controlAt(x, y) ?: gripAdaptiveStickAt(x, y)) != null) return false
        forwardedPointerBits = forwardedPointerBits or (1 shl event.getPointerId(event.actionIndex))
        forwardPointers(event, forwardedPointerBits)
        return true
    }

    private fun scheduleGripLongPress(pointerId: Int) {
        cancelGripLongPress(pointerId)
        val check = Runnable { onGripLongPressDue(pointerId) }
        gripLongPressCheck[pointerId] = check
        gripHandler.postDelayed(check, GripScheme.LONG_PRESS_MS)
    }

    private fun cancelGripLongPress(pointerId: Int) {
        gripLongPressCheck.remove(pointerId)?.let { gripHandler.removeCallbacks(it) }
    }

    private fun onGripLongPressDue(pointerId: Int) {
        gripLongPressCheck.remove(pointerId)
        val tracker = gripTrackers[pointerId] ?: return
        if (tracker.check(gripNow()) == GripScheme.PressOutcome.LONG_PRESS) invalidate()
    }

    private fun convertGripToStick(pointer: Int, x: Float, y: Float) {
        val tracker = gripTrackers.remove(pointer) ?: return
        cancelGripLongPress(pointer)
        val button = controls.firstOrNull { it.pressedBy == pointer }
        if (button != null) {
            button.pressedBy = -1
            button.clicked = false
        }
        val stick = controlById("ls")
        stick.pressedBy = pointer
        stick.clicked = false
        stick.ax = tracker.downX
        stick.ay = tracker.downY
        stick.drag(x, y)
    }

    override fun onPointerCaptureChange(hasCapture: Boolean) {
        super.onPointerCaptureChange(hasCapture)
        onPointerCaptureChange?.invoke(hasCapture)
    }

    private fun forwardMiss(event: MotionEvent, x: Float, y: Float): Boolean {
        if (onTouchMiss == null || keyboardPressedBy != -1 || quickPressedBy != -1) return false
        if (!quickHidden && (controlAt(x, y) ?: adaptiveStickAt(x, y)) != null) return false
        forwardedPointerBits = forwardedPointerBits or (1 shl event.getPointerId(event.actionIndex))
        forwardPointers(event, forwardedPointerBits)
        return true
    }

    private fun forwardPointers(event: MotionEvent, pointerIdBits: Int) {
        val split = event.pointerSubset(pointerIdBits) ?: return
        try {
            onTouchMiss?.invoke(split)
        } finally {
            split.recycle()
        }
    }

    private fun onEditTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clearSnapGuides()
                val control = controlAt(event.x, event.y)
                selected = control?.group
                val group = selected ?: run { invalidate(); return false }
                val (x, y) = centre(group)
                grabX = x - event.x
                grabY = y - event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val group = selected ?: return true
                ignoreSaved = false
                val rawX = event.x + grabX
                val rawY = event.y + grabY
                val others = groups.filter { it != group && members(it).any(::isVisible) }.map { centre(it) }
                val nextX = if (snap) closestSnap(rawX, others.map { it.first } + width / 2f) else null
                val nextY = if (snap) closestSnap(rawY, others.map { it.second }) else null
                put(group, nextX ?: rawX, nextY ?: rawY)
                clamp(group)
                val (x, y) = centre(group)
                val visibleX = nextX?.takeIf { abs(x - it) <= SNAP_TOLERANCE_PX }
                val visibleY = nextY?.takeIf { abs(y - it) <= SNAP_TOLERANCE_PX }
                if ((visibleX != null && visibleX != snapX) || (visibleY != null && visibleY != snapY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                snapX = visibleX
                snapY = visibleY
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> clearSnapGuides()
        }
        return true
    }

    private fun closestSnap(position: Float, targets: List<Float>): Float? =
        targets.minByOrNull { abs(position - it) }?.takeIf { abs(position - it) <= dp(SNAP_DP) }

    private fun controlAt(x: Float, y: Float): Control? =
        controls.firstOrNull {
            !quickHidden && isVisible(it) && (editing || !settings.adaptiveSticks || it.stick < 0) && it.contains(x, y, it.radius)
        }

    private fun adaptiveStickAt(x: Float, y: Float, onlyId: String? = null): Control? {
        if (!settings.adaptiveSticks || editing || buttonsOnly || quickHidden) return null
        if (x < safe.left || x >= width - safe.right || y < safe.top || y >= height - safe.bottom) return null
        if (controls.any { it.stick < 0 && isVisible(it) && it.contains(x, y, it.radius, 5f) }) return null
        return controls.filter {
            val dx = x - it.cx
            val dy = y - it.cy
            val reach = it.radius * 1.4f * sqrt(1.5f)
            it.stick >= 0 && (onlyId == null || it.id == onlyId) && isVisible(it) && it.pressedBy == -1 && dx * dx + dy * dy <= reach * reach
        }.minByOrNull {
            val dx = x - it.cx
            val dy = y - it.cy
            dx * dx + dy * dy
        }
    }

    private fun gripAdaptiveStickAt(x: Float, y: Float): Control? = adaptiveStickAt(x, y, onlyId = "rs")

    private fun isVisible(control: Control): Boolean = when {
        control.id in gripOnlyIds && !(settings.newGrip && !editing && !buttonsOnly) -> false
        settings.newGrip && !editing && !buttonsOnly -> when (control.id) {
            "guide" -> gripSmartOpen() && settings.steamButton
            "qam" -> gripSmartOpen() && settings.qamButton
            "select", "start", "moveToggle" -> gripSmartOpen()
            "smart" -> true
            "up", "right", "down", "left" -> settings.gripMovement == GripScheme.Movement.DPAD
            "ls" -> settings.gripMovement == GripScheme.Movement.STICK
            "customKey" -> customKeys.isNotEmpty()
            else -> control.target != ControllerPrefs.OFF
        }
        !editing && control.id == "guide" && !settings.steamButton -> false
        !editing && control.id == "qam" && !settings.qamButton -> false
        buttonsOnly && !editing -> control.id == "guide" || control.id == "qam"
        else -> control.target != ControllerPrefs.OFF
    }

    private fun apply() {
        val bridge = pad
        if (bridge == null || editing) { invalidate(); return }
        val qamHeld = controls.any { it.id == "qam" && it.pressedBy != -1 }
        if (buttonsOnly) {
            bridge.setSystemButtons(controls.any { it.id == "guide" && it.pressedBy != -1 }, qamHeld)
        } else {
            bridge.setSystemButtons(false, qamHeld)
            bridge.applyTouch { state ->
                val used = HashSet<String>()
                val held = HashSet<String>()
                for (control in controls) {
                    when {
                        control.stick >= 0 -> {
                            val click = if (control.stick == 0) "l3" else "r3"
                            if (settings.stickClick) used.add(click)
                            if (control.clicked) held.add(click)
                            if (!control.dirty) continue
                            if (control.stick == 0) { state.leftX = control.kx / control.radius; state.leftY = control.ky / control.radius }
                            else { state.rightX = control.kx / control.radius; state.rightY = control.ky / control.radius }
                            control.dirty = false
                        }
                        control.id == "qam" || control.id == "smart" || control.id == "moveToggle" ||
                            control.id == "customKey" || control.target == ControllerPrefs.OFF -> {}
                        else -> {
                            used.add(control.target)
                            if (control.pressedBy != -1) held.add(control.target)
                        }
                    }
                }
                for (target in used) write(state, target, target in held)
            }
        }
        invalidate()
    }

    private fun write(state: PadState, target: String, down: Boolean) {
        when (target) {
            "a" -> state.press(PadState.A, down)
            "b" -> state.press(PadState.B, down)
            "x" -> state.press(PadState.X, down)
            "y" -> state.press(PadState.Y, down)
            "lb" -> state.press(PadState.LB, down)
            "rb" -> state.press(PadState.RB, down)
            "select" -> state.press(PadState.SELECT, down)
            "start" -> state.press(PadState.START, down)
            "l3" -> state.press(PadState.L3, down)
            "r3" -> state.press(PadState.R3, down)
            "guide" -> state.press(PadState.GUIDE, down)
            "lt" -> state.leftTrigger = if (down) 1f else 0f
            "rt" -> state.rightTrigger = if (down) 1f else 0f
            "up" -> state.up = down
            "right" -> state.right = down
            "down" -> state.down = down
            "left" -> state.left = down
        }
    }

    fun releaseAll() {
        quickPressedBy = -1
        keyboardPressedBy = -1
        gripTrackers.values.forEach { it.cancel() }
        gripTrackers.clear()
        gripLongPressCheck.values.forEach { gripHandler.removeCallbacks(it) }
        gripLongPressCheck.clear()
        customKeyDrag = null
        invalidate()
        if (controls.none { it.pressedBy != -1 || it.clicked } && !buttonsOnly) return
        controls.forEach {
            it.pressedBy = -1
            it.clicked = false
            it.lastUp = 0L
            if (it.stick >= 0 && (it.kx != 0f || it.ky != 0f)) it.dirty = true
            it.kx = 0f; it.ky = 0f
        }
        apply()
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
        const val MIN_FACE_MM = 9.5f
        const val FULL_SIZE_HEIGHT_MM = 85f
        const val BOTTOM_MARGIN_MM = 5f
        const val DROP_DEGREES = 35.0
        const val PHONE_MAX_HEIGHT_MM = 90f
        const val SHOULDER_WIDTH = 1.45f
        const val SHOULDER_HEIGHT = 0.8f
        const val SNAP_DP = 9f
        const val SNAP_TOLERANCE_PX = 1f
        val shoulderIds = setOf("lb", "rb", "lt", "rt")
        val gripOnlyIds = setOf("smart", "moveToggle", "customKey")
        val directions = listOf("up", "right", "down", "left")
    }
}
