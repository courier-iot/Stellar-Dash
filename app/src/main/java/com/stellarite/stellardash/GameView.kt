package com.stellarite.stellardash

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Hosts the game loop on its own thread and forwards touches to [Game].
 * Fixed-step simulation (120 Hz) with rendering as fast as the display allows.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable, GameHost {

    private val prefs = context.getSharedPreferences("stellar_dash", Context.MODE_PRIVATE)
    private val sfx = Sfx(context)
    private val game = Game(this)

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var surfaceReady = false
    private var resumed = false

    init {
        holder.addCallback(this)
        isFocusable = true
        keepScreenOn = true
    }

    // ---- Surface lifecycle ----
    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        game.setScreen(width, height)
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    fun onResumeGame() {
        resumed = true
        startLoop()
    }

    fun onPauseGame() {
        resumed = false
        stopLoop()
        game.pause()
    }

    fun shutdown() {
        stopLoop()
        sfx.release()
    }

    private fun startLoop() {
        if (!surfaceReady || !resumed || running) return
        running = true
        loopThread = Thread(this, "stellar-dash-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        val t = loopThread ?: return
        try {
            t.join(700)
        } catch (e: InterruptedException) {
        }
        loopThread = null
    }

    override fun run() {
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            var dt = (now - last) / 1_000_000_000f
            last = now
            if (dt > 0.05f) dt = 0.05f
            acc += dt
            while (acc >= STEP) {
                game.update(STEP)
                acc -= STEP
            }

            val h = holder
            if (!h.surface.isValid) {
                try { Thread.sleep(16) } catch (e: InterruptedException) {}
                continue
            }
            var canvas: Canvas? = null
            try {
                canvas = h.lockHardwareCanvas()
                if (canvas != null) game.draw(canvas)
            } catch (e: Exception) {
                // Surface went away mid-frame; next loop iteration will recheck.
            } finally {
                if (canvas != null) {
                    try { h.unlockCanvasAndPost(canvas) } catch (e: Exception) {}
                }
            }
        }
    }

    // ---- Input ----
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                game.touch(0, event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    game.touch(1, event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                game.touch(2, event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    game.touch(2, event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
        }
        return true
    }

    // ---- GameHost ----
    override fun playSound(sound: Sfx.Sound) = sfx.play(sound)

    override fun buzz() {
        post { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    }

    override fun loadBest(): Int = prefs.getInt("best", 0)

    override fun saveBest(value: Int) {
        prefs.edit().putInt("best", value).apply()
    }

    override fun loadString(key: String): String? = prefs.getString(key, null)

    override fun saveString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** @return false when the app should close. */
    fun onBack(): Boolean = game.back()

    companion object {
        private const val STEP = 1f / 120f
    }
}
