package app.lawnchair.metro.notify

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import app.lawnchair.metro.data.MetroUsage
import app.lawnchair.metro.live.LiveTileData
import app.lawnchair.metro.theme.MetroTheme
import kotlin.math.abs

/**
 * Notification panel for a tile (or an app list row): swipe right on it to open.
 *
 * The panel attaches to the tile over a dimmed Start: the tile itself stays bright, and the
 * panel opens from its edge, downward when there's room and upward otherwise, spanning the full
 * width with square edges like the tiles. A thin strip in the app's brand colour marks the edge
 * where it joins the tile, the panel carries a faint brand wash, and actions use a light brand
 * tone.
 *
 * Each card shows one notification with the app's own actions; reply opens a text field above
 * the keyboard. Swipe a card right to dismiss it (from the notification shade too); tap it to
 * open that exact conversation. Tap outside, swipe the panel left, or press back to close it;
 * swipe right on another tile to move the panel there.
 */
@SuppressLint("ViewConstructor")
class NotificationPanel(
    context: Context,
    val target: Target,
    private val callbacks: Callbacks,
) : FrameLayout(context) {

    /** What the panel belongs to. [anchor] is the tile's rectangle in the host's coordinates. */
    class Target(
        val pkg: String,
        val label: CharSequence,
        /** Brand colour (glow tone) of the app, or 0. */
        val brand: Int,
        val anchor: Rect,
        val view: View?,
    )

    interface Callbacks {
        /** The panel finished closing and was removed. [emptied] when its last card went away. */
        fun onClosed(panel: NotificationPanel, emptied: Boolean)

        /** Another target under (x, y) in host coordinates, for swiping onto another tile. */
        fun targetAt(x: Float, y: Float): Target?

        /** Opens a panel for [target] right away (after this one closes). */
        fun switchTo(target: Target)

        /** Top and bottom system insets of the host. */
        fun insets(): Rect
    }

    private val brandTone: Int = when {
        target.brand != 0 -> target.brand
        else -> MetroTheme.accent(context)
    }
    /** Light brand tone for actions and the reply button. */
    private val actionTone = ColorUtils.blendARGB(brandTone, Color.WHITE, 0.35f)
    private val surface = ColorUtils.blendARGB(0xFF1C1C1C.toInt(), brandTone, 0.09f)

    private val sheet = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface)
        isClickable = true
        elevation = dp(6f)
    }
    private val edgeTop = View(context).apply { setBackgroundColor(brandTone) }
    private val edgeBottom = View(context).apply { setBackgroundColor(brandTone) }
    private val header = TextView(context)
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = false }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val footer = LinearLayout(context)
    private val replyBar = LinearLayout(context)
    private val replyField = EditText(context)

    private var opensDown = true
    private var progress = 0f
    private var closing = false
    private var emptied = false
    private var imeBottom = 0

    /** The notification and action being replied to. */
    private var replying: Pair<StatusBarNotification, Notification.Action>? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        clipChildren = false

        header.apply {
            text = target.label.toString().lowercase()
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(16f).toInt(), dp(4f).toInt())
        }
        scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        footer.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), dp(4f).toInt())
            addView(actionText("clear all") { clearAll() })
        }

        replyBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            setPadding(dp(16f).toInt(), dp(4f).toInt(), dp(8f).toInt(), dp(8f).toInt())
        }
        replyField.apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply { setStroke(dp(1f).toInt(), 0x66FFFFFF) }
            setPadding(dp(10f).toInt(), dp(8f).toInt(), dp(10f).toInt(), dp(8f).toInt())
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 4
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) {
                    sendReply()
                    true
                } else {
                    false
                }
            }
        }
        replyBar.addView(replyField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        replyBar.addView(actionText("send") { sendReply() })

        val mw = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        sheet.addView(edgeTop, LinearLayout.LayoutParams(mw, dp(3f).toInt()))
        sheet.addView(header, LinearLayout.LayoutParams(mw, wc))
        sheet.addView(scroll, LinearLayout.LayoutParams(mw, 0, 1f))
        sheet.addView(footer, LinearLayout.LayoutParams(mw, wc))
        sheet.addView(replyBar, LinearLayout.LayoutParams(mw, wc))
        sheet.addView(edgeBottom, LinearLayout.LayoutParams(mw, dp(3f).toInt()))
        addView(sheet, LayoutParams(mw, wc))

        // Keep the reply field above the keyboard as it slides.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
                    readIme(insets)
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimation) {
                    rootWindowInsets?.let(::readIme)
                }
            })
        }

        refresh()
        setProgress(0f)
    }

    // ---- Content ---------------------------------------------------------------------------

    /** Rebuilds the cards from the app's current notifications; closes when none are left. */
    fun refresh() {
        if (closing) return
        val items = LiveTileData.notificationsFor(target.pkg)
        if (items.isEmpty()) {
            emptied = true
            close(animate = true)
            return
        }
        list.removeAllViews()
        items.take(MAX_CARDS).forEach { list.addView(Card(it)) }
        footer.visibility = if (items.any { it.isClearable }) VISIBLE else GONE
        requestLayout()
    }

    private fun clearAll() {
        LiveTileData.notificationsFor(target.pkg).filter { it.isClearable }.forEach { LiveTileData.dismiss(it.key) }
        emptied = true
        close(animate = true)
    }

    /** Opens what the notification points at: the exact chat or email. */
    private fun openNotification(sbn: StatusBarNotification) {
        val pi = sbn.notification.contentIntent
        if (pi == null) {
            context.packageManager.getLaunchIntentForPackage(sbn.packageName)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            send(pi, null)
        }
        MetroUsage.recordLaunch(context, sbn.packageName)
        if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) LiveTileData.dismiss(sbn.key)
        close(animate = false)
    }

    private fun runAction(sbn: StatusBarNotification, action: Notification.Action) {
        val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }.orEmpty()
        if (inputs.isNotEmpty()) {
            startReply(sbn, action)
            return
        }
        send(action.actionIntent ?: return, null)
        // Apps update or remove their notification after an action; show the result.
        postDelayed({ refresh() }, 600)
    }

    private fun send(pi: PendingIntent, fillIn: Intent?) {
        runCatching {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            pi.send(context, 0, fillIn, null, null, null, options)
        }.onFailure { Toast.makeText(context, "Couldn't do that", Toast.LENGTH_SHORT).show() }
    }

    private fun startReply(sbn: StatusBarNotification, action: Notification.Action) {
        replying = sbn to action
        replyField.setText("")
        replyField.hint = action.remoteInputs?.firstOrNull()?.label?.toString()?.lowercase() ?: "reply"
        replyBar.visibility = VISIBLE
        footer.visibility = GONE
        replyField.requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(replyField, InputMethodManager.SHOW_IMPLICIT)
        // Fallback for keyboards that don't animate their insets.
        postDelayed({ rootWindowInsets?.let(::readIme) }, 350)
        requestLayout()
    }

    private fun sendReply() {
        val (sbn, action) = replying ?: return
        val text = replyField.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val inputs = action.remoteInputs ?: return
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, text) }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        send(action.actionIntent ?: return, fill)
        MetroUsage.recordLaunch(context, sbn.packageName)
        hideKeyboard()
        close(animate = true)
    }

    private fun hideKeyboard() {
        replyField.clearFocus()
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    private fun readIme(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (ime != imeBottom) {
            imeBottom = ime
            requestLayout()
        }
    }

    // ---- Open / close ------------------------------------------------------------------------

    /** 0 = hidden, 1 = open. While the finger drags, the panel slides in with it. */
    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        sheet.translationX = -width.coerceAtLeast(resources.displayMetrics.widthPixels) * (1f - progress)
        sheet.alpha = 0.4f + 0.6f * progress
        invalidate()
    }

    fun animateOpen() = animateTo(1f, null)

    /** Slides back out (gesture released early, or closing) and removes the panel. */
    fun close(animate: Boolean) {
        if (closing) return
        closing = true
        if (replying != null) hideKeyboard()
        if (animate) animateTo(0f) { finish() } else finish()
    }

    private var anim: android.animation.ValueAnimator? = null

    private fun animateTo(to: Float, end: (() -> Unit)?) {
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(progress, to).apply {
            duration = (220 * abs(to - progress)).toLong().coerceAtLeast(120)
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            if (end != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = end()
                })
            }
            start()
        }
    }

    private fun finish() {
        (parent as? ViewGroup)?.removeView(this)
        callbacks.onClosed(this, emptied)
    }

    val isClosing: Boolean get() = closing

    // ---- Layout ------------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val ins = callbacks.insets()
        val gap = dp(5f).toInt()
        val a = target.anchor
        val below = h - a.bottom - gap - ins.bottom
        val above = a.top - gap - ins.top
        // Natural height first, then pick the side with room.
        sheet.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
        val natural = naturalSheetHeight(w)
        val maxH = if (imeBottom > 0 && replying != null) {
            h - imeBottom - ins.top - gap * 2
        } else {
            opensDown = natural <= below || below >= above
            if (opensDown) below else above
        }
        edgeTop.visibility = if (opensDown) VISIBLE else GONE
        edgeBottom.visibility = if (opensDown) GONE else VISIBLE
        val sheetH = natural.coerceAtMost(maxH.coerceAtLeast(dp(120f).toInt()))
        sheet.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sheetH, MeasureSpec.EXACTLY))
    }

    /** Height the sheet wants with all cards showing. */
    private fun naturalSheetHeight(w: Int): Int {
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val us = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var total = 0
        for (i in 0 until sheet.childCount) {
            val c = sheet.getChildAt(i)
            if (c.visibility == GONE) continue
            if (c === scroll) {
                list.measure(ws, us)
                total += list.measuredHeight
            } else {
                c.measure(ws, us)
                total += (c.layoutParams as LinearLayout.LayoutParams).height.takeIf { it > 0 } ?: c.measuredHeight
            }
        }
        return total
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = dp(5f).toInt()
        val sh = sheet.measuredHeight
        val top = when {
            imeBottom > 0 && replying != null -> height - imeBottom - gap - sh
            opensDown -> target.anchor.bottom + gap
            else -> target.anchor.top - gap - sh
        }
        sheet.layout(0, top, width, top + sh)
    }

    // ---- Dimmed Start, with the tile kept bright ----------------------------------------------

    private val hole = RectF()

    override fun dispatchDraw(canvas: Canvas) {
        val save = canvas.save()
        hole.set(target.anchor)
        canvas.clipOutRect(hole)
        canvas.drawColor(ColorUtils.setAlphaComponent(Color.BLACK, (0xA6 * progress).toInt()))
        canvas.restoreToCount(save)
        super.dispatchDraw(canvas)
    }

    // ---- Touches outside the sheet -------------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private val flings = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.x - start.x
            if (abs(dx) < abs(e2.y - start.y) * 1.3f || abs(dx) < dp(50f)) return false
            if (dx < 0) {
                close(animate = true) // swipe the panel back left
                return true
            }
            if (downInSheet) return false // a card being swiped away
            // Swipe right on another tile: move the panel there.
            val other = callbacks.targetAt(start.x, start.y)
            if (other != null && other.pkg != target.pkg) {
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                closing = true
                finish()
                callbacks.switchTo(other)
                return true
            }
            return false
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (!downInSheet && !inSheet(e.x, e.y)) {
                close(animate = true)
                return true
            }
            return false
        }
    })

    private fun inSheet(x: Float, y: Float) =
        x >= sheet.left && x < sheet.right && y >= sheet.top + sheet.translationY && y < sheet.bottom + sheet.translationY

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Watch every gesture for the panel-wide flings and taps outside; cards take their own
        // horizontal swipes inside the sheet.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) downInSheet = inSheet(ev.x, ev.y)
        if (flings.onTouchEvent(ev)) {
            val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private var downInSheet = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = true // Start underneath stays untouched

    // ---- One notification ----------------------------------------------------------------------

    /** A notification card: swipe right to dismiss, tap to open. */
    @SuppressLint("ViewConstructor")
    private inner class Card(val sbn: StatusBarNotification) : LinearLayout(context) {
        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var vt: android.view.VelocityTracker? = null

        init {
            orientation = VERTICAL
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            isClickable = true
            setOnClickListener { openNotification(sbn) }

            val n = sbn.notification
            val e = n.extras
            val conversation = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            val title = conversation ?: e.getCharSequence(Notification.EXTRA_TITLE) ?: target.label
            val messages = e.getParcelableArray(Notification.EXTRA_MESSAGES)
            val body: CharSequence? = if (!messages.isNullOrEmpty()) {
                messages.takeLast(3).mapNotNull { m ->
                    val bundle = m as? Bundle ?: return@mapNotNull null
                    val text = bundle.getCharSequence("text") ?: return@mapNotNull null
                    val sender = bundle.getCharSequence("sender")
                        ?: bundle.getParcelable<android.app.Person>("sender_person")?.name
                    if (conversation != null && sender != null) "$sender: $text" else text
                }.joinToString("\n")
            } else {
                e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT)
            }

            val top = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            runCatching { n.getLargeIcon()?.loadDrawable(context) }.getOrNull()?.let { d ->
                top.addView(ImageView(context).apply {
                    setImageDrawable(d)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    clipToOutline = true
                }, LayoutParams(dp(32f).toInt(), dp(32f).toInt()).apply { marginEnd = dp(10f).toInt() })
            }
            top.addView(TextView(context).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(TextView(context).apply {
                text = DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
                setTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            })
            addView(top)

            if (!body.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = body
                    setTextColor(0xD9FFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 4
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4f).toInt(), 0, 0)
                })
            }

            val actions = n.actions.orEmpty().filter { it.title != null && it.actionIntent != null }.take(3)
            if (actions.isNotEmpty()) {
                val row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    setPadding(0, dp(6f).toInt(), 0, 0)
                }
                actions.forEach { a ->
                    row.addView(actionText(a.title.toString().lowercase()) { runAction(sbn, a) })
                }
                addView(row)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX
                    startY = ev.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - startX
                    if (dx > slop && dx > abs(ev.rawY - startY) * 1.3f) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                startX = event.rawX
                startY = event.rawY
                vt?.recycle()
                vt = android.view.VelocityTracker.obtain()
            }
            vt?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (!dragging && dx > slop && dx > abs(event.rawY - startY) * 1.3f) {
                        dragging = true
                        parent.requestDisallowInterceptTouchEvent(true)
                        cancelLongPress()
                        isPressed = false
                    }
                    if (dragging) {
                        translationX = dx.coerceAtLeast(0f)
                        alpha = 1f - (translationX / width).coerceIn(0f, 1f) * 0.8f
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        vt?.computeCurrentVelocity(1000)
                        val v = vt?.xVelocity ?: 0f
                        if (event.actionMasked == MotionEvent.ACTION_UP && (translationX > width * 0.35f || v > dp(900f))) {
                            dismissCard()
                        } else {
                            animate().translationX(0f).alpha(1f).setDuration(180).start()
                        }
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dismissCard() {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            animate().translationX(width.toFloat()).alpha(0f).setDuration(160).withEndAction {
                LiveTileData.dismiss(sbn.key)
                list.removeView(this)
                if (list.childCount == 0) {
                    emptied = true
                    close(animate = true)
                } else {
                    requestLayout()
                }
            }.start()
        }
    }

    private fun actionText(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(actionTone)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(8f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(8f).toInt())
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    companion object {
        private const val MAX_CARDS = 12
    }
}
