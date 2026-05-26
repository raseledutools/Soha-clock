package com.rasel.blocker

import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * FakeLockService
 * ─────────────────────────────────────────────────────────────────
 * Floating overlay — screen এর উপরে একটা fake lock screen দেখায়।
 * সব ধরনের ব্যবহার থেকে privacy রক্ষা করে।
 *
 * Features:
 *  - Floating lock button (transparent, custom opacity/size)
 *  - Auto-lock timer
 *  - Volume key দিয়ে dismiss (optional)
 *  - Settings update broadcast receiver
 * ─────────────────────────────────────────────────────────────────
 */
class FakeLockService : Service() {

    companion object {
        private const val CHANNEL_ID = "FakeLockChannel"
        private const val NOTIF_ID   = 88
        private const val TAG        = "FakeLockService"
    }

    private lateinit var windowManager: WindowManager
    private var lockOverlay: View? = null
    private var floatBtn:    View? = null
    private val handler = Handler(Looper.getMainLooper())

    // Settings (updated via broadcast)
    private var opacity:      Float = 0.5f
    private var btnSize:      Int   = 50
    private var autoSeconds:  Int   = 0
    private var enableFloat:  Boolean = true
    private var enableVolume: Boolean = false
    private var autoHide:     Boolean = false

    private var isLocked = false

    // ── Settings update receiver ─────────────────────────────────────
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            loadPrefs()
            updateFloatBtn()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        loadPrefs()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, IntentFilter(MainActivity.ACTION_UPDATE_SETTINGS), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(settingsReceiver, IntentFilter(MainActivity.ACTION_UPDATE_SETTINGS))
        }

        if (enableFloat) showFloatBtn()
        scheduleAutoLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadPrefs()
        updateFloatBtn()
        scheduleAutoLock()
        return START_STICKY
    }

    // ── Load prefs ──────────────────────────────────────────────────
    private fun loadPrefs() {
        val p = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        opacity      = p.getFloat(MainActivity.KEY_OPACITY, 0.5f)
        btnSize      = p.getInt(MainActivity.KEY_SIZE, 50)
        autoSeconds  = p.getInt(MainActivity.KEY_AUTO_SECONDS, 0)
        enableFloat  = p.getBoolean(MainActivity.KEY_ENABLE_FLOAT, true)
        enableVolume = p.getBoolean(MainActivity.KEY_ENABLE_VOLUME, false)
        autoHide     = p.getBoolean(MainActivity.KEY_AUTO_HIDE, false)
    }

    // ── Floating lock button ────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatBtn() {
        if (floatBtn != null) return
        val sizePx = dpToPx(btnSize)

        val btn = TextView(this).apply {
            text = "🔒"
            textSize = (btnSize * 0.4f).coerceIn(12f, 40f)
            gravity = Gravity.CENTER
            alpha   = opacity
            setBackgroundColor(Color.TRANSPARENT)
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 200
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var moved  = false

        btn.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX;  touchY = ev.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (dx * dx + dy * dy > 25) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { windowManager.updateViewLayout(btn, params) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showLockScreen()
                }
            }
            true
        }

        windowManager.addView(btn, params)
        floatBtn = btn
    }

    private fun updateFloatBtn() {
        val btn = floatBtn as? TextView ?: return
        val sizePx = dpToPx(btnSize)
        btn.alpha    = opacity
        btn.textSize = (btnSize * 0.4f).coerceIn(12f, 40f)
        try {
            val params = btn.layoutParams as? WindowManager.LayoutParams ?: return
            params.width  = sizePx
            params.height = sizePx
            windowManager.updateViewLayout(btn, params)
        } catch (_: Exception) {}
    }

    // ── Lock overlay ─────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showLockScreen() {
        if (isLocked) return
        isLocked = true

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#E60A0A0A"))

        // Center content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val icon = TextView(this).apply {
            text = "🔒"
            textSize = 72f
            gravity = Gravity.CENTER
        }

        val label = TextView(this).apply {
            text = "Locked"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val hint = TextView(this).apply {
            text = "Tap to unlock"
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }

        content.addView(icon)
        content.addView(label)
        content.addView(hint)

        root.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        // Volume key dismiss
        if (enableVolume) {
            root.isFocusable = true
            root.isFocusableInTouchMode = true
            root.setOnKeyListener { _, keyCode, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                    dismissLockScreen()
                    true
                } else false
            }
        }

        root.setOnClickListener { dismissLockScreen() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(root, params)
        lockOverlay = root

        // Auto-hide
        if (autoHide && autoSeconds > 0) {
            handler.postDelayed({ dismissLockScreen() }, autoSeconds * 1000L)
        }
    }

    private fun dismissLockScreen() {
        if (!isLocked) return
        isLocked = false
        handler.removeCallbacksAndMessages(null)
        lockOverlay?.let {
            runCatching { windowManager.removeView(it) }
            lockOverlay = null
        }
    }

    // ── Auto lock timer ───────────────────────────────────────────────
    private fun scheduleAutoLock() {
        handler.removeCallbacksAndMessages(null)
        if (autoSeconds > 0) {
            handler.postDelayed({ showLockScreen() }, autoSeconds * 1000L)
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(settingsReceiver) }
        floatBtn?.let { runCatching { windowManager.removeView(it) } }
        lockOverlay?.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔒 FakeLock Active")
            .setContentText("Tap to open settings")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "FakeLock Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()
}
