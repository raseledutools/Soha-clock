package com.rasel.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "fakelock_prefs"
        const val KEY_OPACITY = "btn_opacity"
        const val KEY_SIZE = "btn_size"
        const val KEY_AUTO_SECONDS = "auto_seconds"
        const val ACTION_UPDATE_BUTTON = "com.rasel.blocker.UPDATE_BUTTON"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                SettingsScreen(
                    prefs = prefs,
                    onStartService = { autoLockSeconds, opacity, size ->
                        prefs.edit()
                            .putInt(KEY_AUTO_SECONDS, autoLockSeconds)
                            .putFloat(KEY_OPACITY, opacity)
                            .putInt(KEY_SIZE, size)
                            .apply()
                        if (Settings.canDrawOverlays(this)) {
                            val intent = Intent(this, FakeLockService::class.java).apply {
                                putExtra("auto_lock_seconds", autoLockSeconds)
                                putExtra("btn_opacity", opacity)
                                putExtra("btn_size", size)
                            }
                            startForegroundService(intent)
                        } else {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }
                    },
                    onStopService = {
                        stopService(Intent(this, FakeLockService::class.java))
                    },
                    onUpdateButton = { opacity, size ->
                        prefs.edit()
                            .putFloat(KEY_OPACITY, opacity)
                            .putInt(KEY_SIZE, size)
                            .apply()
                        val intent = Intent(ACTION_UPDATE_BUTTON).apply {
                            putExtra("btn_opacity", opacity)
                            putExtra("btn_size", size)
                        }
                        sendBroadcast(intent)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FakeLockService::class.java).apply {
                putExtra("auto_lock_seconds", prefs.getInt(KEY_AUTO_SECONDS, 30))
                putExtra("btn_opacity", prefs.getFloat(KEY_OPACITY, 0.7f))
                putExtra("btn_size", prefs.getInt(KEY_SIZE, 110))
            }
            startForegroundService(intent)
        }
    }
}

// ─────────────────────────────────────────────
// Compose Settings UI
// ─────────────────────────────────────────────
@Composable
fun SettingsScreen(
    prefs: SharedPreferences,
    onStartService: (Int, Float, Int) -> Unit,
    onStopService: () -> Unit,
    onUpdateButton: (Float, Int) -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var selectedSeconds by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 30)) }
    var opacity by remember { mutableStateOf(prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f)) }
    var btnSize by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_SIZE, 110)) }

    val autoLockOptions = listOf(15, 20, 30, 60, 120)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0A0A0A))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text("🔒 FakeLock", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        Text("Floating button দিয়ে নকল lock screen", fontSize = 13.sp, color = ComposeColor(0xFF888888))

        Spacer(modifier = Modifier.height(4.dp))

        // Opacity Slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("🔘 Button Opacity", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                    Text("${(opacity * 100).toInt()}%", fontSize = 14.sp, color = ComposeColor(0xFF9C27B0), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it; onUpdateButton(opacity, btnSize) },
                    valueRange = 0.1f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = ComposeColor(0xFF9C27B0),
                        activeTrackColor = ComposeColor(0xFF9C27B0),
                        inactiveTrackColor = ComposeColor(0xFF333333)
                    )
                )
            }
        }

        // Size Slider
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📐 Button Size", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                    Text("${btnSize}px", fontSize = 14.sp, color = ComposeColor(0xFF6200EE), fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = btnSize.toFloat(),
                    onValueChange = { btnSize = it.toInt(); onUpdateButton(opacity, btnSize) },
                    valueRange = 60f..180f,
                    colors = SliderDefaults.colors(
                        thumbColor = ComposeColor(0xFF6200EE),
                        activeTrackColor = ComposeColor(0xFF6200EE),
                        inactiveTrackColor = ComposeColor(0xFF333333)
                    )
                )
            }
        }

        // Auto Lock Time
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⏱ Auto Lock সময়", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    autoLockOptions.forEach { sec ->
                        val label = if (sec >= 60) "${sec / 60}m" else "${sec}s"
                        val selected = selectedSeconds == sec
                        Button(
                            onClick = { selectedSeconds = sec },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) ComposeColor(0xFF6200EE) else ComposeColor(0xFF2A2A2A)
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 13.sp, color = ComposeColor.White)
                        }
                    }
                }
            }
        }

        // How to use
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📖 কিভাবে কাজ করে", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                Text("• Screen এ ছোট 🔒 floating button ভাসবে", fontSize = 12.sp, color = ComposeColor(0xFFBBBBBB))
                Text("• Button চাপলে → পুরো কালো screen + ঘড়ি", fontSize = 12.sp, color = ComposeColor(0xFFBBBBBB))
                Text("• ঘড়িতে Double Tap → আবার normal হবে", fontSize = 12.sp, color = ComposeColor(0xFFBBBBBB))
                Text("• ${selectedSeconds}s পরে Auto Lock হবে", fontSize = 12.sp, color = ComposeColor(0xFF9C27B0))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (!isRunning) { onStartService(selectedSeconds, opacity, btnSize); isRunning = true }
                else { onStopService(); isRunning = false }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isRunning) ComposeColor(0xFF6200EE) else ComposeColor(0xFFB00020)
            )
        ) {
            Text(
                text = if (!isRunning) "▶  Service চালু করুন" else "⏹  Service বন্ধ করুন",
                fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ─────────────────────────────────────────────
// FakeLock Overlay Service
// ─────────────────────────────────────────────
class FakeLockService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingButtonView: android.widget.FrameLayout? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var lockScreenView: android.widget.FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoLockSeconds = 30
    private var lastInteractionTime = System.currentTimeMillis()
    private var isLocked = false
    private var btnOpacity = 0.7f
    private var btnSize = 110

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_UPDATE_BUTTON) {
                btnOpacity = intent.getFloatExtra("btn_opacity", btnOpacity)
                btnSize = intent.getIntExtra("btn_size", btnSize)
                updateFloatingButton()
            }
        }
    }

    private val autoLockRunnable = object : Runnable {
        override fun run() {
            val elapsed = (System.currentTimeMillis() - lastInteractionTime) / 1000
            if (!isLocked && elapsed >= autoLockSeconds) showLockScreen()
            handler.postDelayed(this, 1000)
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            autoLockSeconds = it.getIntExtra("auto_lock_seconds", 30)
            btnOpacity = it.getFloatExtra("btn_opacity", 0.7f)
            btnSize = it.getIntExtra("btn_size", 110)
        }
        if (floatingButtonView != null) updateFloatingButton()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter(MainActivity.ACTION_UPDATE_BUTTON)
        // API 33+ এ flag দরকার, নিচে নেই
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }

        showFloatingButton()
        handler.post(autoLockRunnable)
    }

    private fun startForegroundNotification() {
        val channelId = "fakelock_ch"
        val channel = NotificationChannel(channelId, "FakeLock", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("FakeLock চালু আছে")
            .setContentText("Floating 🔒 button screen এ আছে")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, notif)
    }

    private fun showFloatingButton() {
        val ctx = this
        val alphaInt = (btnOpacity * 255).toInt()

        floatingButtonView = android.widget.FrameLayout(ctx).apply {
            val btn = android.widget.TextView(ctx).apply {
                text = "🔒"
                textSize = btnSize * 0.18f
                gravity = Gravity.CENTER
                alpha = btnOpacity
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.argb(alphaInt, 20, 20, 20))
                }
                setPadding(16, 16, 16, 16)
            }
            addView(btn, android.widget.FrameLayout.LayoutParams(btnSize, btnSize))
        }

        floatingButtonParams = WindowManager.LayoutParams(
            btnSize, btnSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 30; y = 200
        }

        floatingButtonView!!.setOnTouchListener(object : android.view.View.OnTouchListener {
            var startX = 0f; var startY = 0f
            var startRawX = 0f; var startRawY = 0f
            var isDragging = false

            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX; startRawY = event.rawY
                        startX = floatingButtonParams!!.x.toFloat()
                        startY = floatingButtonParams!!.y.toFloat()
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) isDragging = true
                        if (isDragging) {
                            floatingButtonParams!!.x = (startX - dx).toInt()
                            floatingButtonParams!!.y = (startY - dy).toInt()
                            windowManager.updateViewLayout(floatingButtonView, floatingButtonParams)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            lastInteractionTime = System.currentTimeMillis()
                            showLockScreen()
                        }
                    }
                }
                return true
            }
        })

        windowManager.addView(floatingButtonView, floatingButtonParams)
    }

    private fun updateFloatingButton() {
        val frame = floatingButtonView ?: return
        val btn = frame.getChildAt(0) as? android.widget.TextView ?: return
        val alphaInt = (btnOpacity * 255).toInt()
        btn.alpha = btnOpacity
        btn.textSize = btnSize * 0.18f
        (btn.background as? android.graphics.drawable.GradientDrawable)
            ?.setColor(Color.argb(alphaInt, 20, 20, 20))

        floatingButtonParams!!.width = btnSize
        floatingButtonParams!!.height = btnSize
        frame.layoutParams.width = btnSize
        frame.layoutParams.height = btnSize
        btn.layoutParams.width = btnSize
        btn.layoutParams.height = btnSize

        try { windowManager.updateViewLayout(floatingButtonView, floatingButtonParams) }
        catch (e: Exception) { /* view not attached yet */ }
    }

    private var clockTextView: android.widget.TextView? = null
    private var dateTextView: android.widget.TextView? = null
    private var lastTapTime = 0L

    private fun showLockScreen() {
        if (isLocked) return
        isLocked = true
        floatingButtonView?.visibility = android.view.View.GONE

        val ctx = this
        lockScreenView = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)

            val center = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                clockTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentTime()
                    textSize = 80f
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }

                dateTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentDate()
                    textSize = 16f
                    setTextColor(Color.argb(160, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 0)
                }

                val hint = android.widget.TextView(ctx).apply {
                    text = "double tap to unlock"
                    textSize = 11f
                    setTextColor(Color.argb(60, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, 48, 0, 0)
                }

                addView(clockTextView)
                addView(dateTextView)
                addView(hint)
            }

            addView(
                center,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 400) hideLockScreen()
                lastTapTime = now
                lastInteractionTime = System.currentTimeMillis()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        )

        windowManager.addView(lockScreenView, params)
        handler.post(clockRunnable)
    }

    private fun hideLockScreen() {
        isLocked = false
        handler.removeCallbacks(clockRunnable)
        lockScreenView?.let { windowManager.removeView(it) }
        lockScreenView = null
        floatingButtonView?.visibility = android.view.View.VISIBLE
        lastInteractionTime = System.currentTimeMillis()
    }

    private fun updateClock() {
        clockTextView?.text = getCurrentTime()
        dateTextView?.text = getCurrentDate()
    }

    private fun getCurrentTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun getCurrentDate(): String =
        SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(Date())

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) { /* already unregistered */ }
        floatingButtonView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        lockScreenView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
    }
}
