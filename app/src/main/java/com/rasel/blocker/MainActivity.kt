package com.rasel.blocker

import android.app.AppOpsManager
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
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────
// HTML Content for Web Tools (Injected directly to avoid asset reading issues)
// ─────────────────────────────────────────────
const val WEB_TOOLS_HTML = """
<!DOCTYPE html><html lang="en"><head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>My Local Toolset</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script src="https://unpkg.com/pdf-lib@1.17.1/dist/pdf-lib.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
    <script src="https://unpkg.com/html5-qrcode" type="text/javascript"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/pdf.js/2.16.105/pdf.min.js"></script>
    <!-- Simplified JS for Android Bridge -->
    <style>
        body { background-color: #f8fafc; font-family: 'Inter', sans-serif; padding: 20px; }
        .tool-card {
            background: white; border-radius: 12px; padding: 15px; margin-bottom: 15px;
            box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); border-top: 4px solid #3b82f6;
        }
        .btn-action {
            background: #2563eb; color: white; padding: 10px; border-radius: 8px;
            width: 100%; margin-top: 10px; font-weight: bold; border: none;
        }
        input, select { width: 100%; border: 1px solid #cbd5e1; padding: 8px; border-radius: 6px; margin-bottom: 5px; margin-top:5px; }
    </style>
</head>
<body>
    <h2 style="text-align:center; font-size: 24px; font-weight: bold; margin-bottom: 20px;">Web Tools & PDF</h2>
    
    <!-- PDF Merge Tool -->
    <div class="tool-card border-t-4 border-gray-600">
        <h3 style="font-weight:bold; font-size: 18px;">Advanced PDF Merger</h3>
        <p style="font-size:12px; color: gray;">Combine multiple PDFs (Select files from Android)</p>
        <button id="android-merge-btn" class="btn-action bg-gray-700" onclick="triggerAndroidFilePicker()">Select PDFs & Merge</button>
        <div id="merge-status" style="margin-top:10px; font-size:12px; color:blue;"></div>
    </div>

    <!-- Job Photo Tool -->
    <div class="tool-card border-t-4 border-blue-500">
        <h3 style="font-weight:bold; font-size: 18px;">Job Photo (300x300)</h3>
        <input type="file" id="job-photo-file" accept="image/*">
        <button onclick="processImage(300, 300, 'job-photo.jpg', 'job-photo-file')" class="btn-action bg-blue-600">Convert Photo</button>
    </div>

    <!-- Signature Tool -->
    <div class="tool-card border-t-4 border-indigo-500">
        <h3 style="font-weight:bold; font-size: 18px;">Signature (300x80)</h3>
        <input type="file" id="sign-photo-file" accept="image/*">
        <button onclick="processImage(300, 80, 'signature.jpg', 'sign-photo-file')" class="btn-action bg-indigo-600">Convert Sign</button>
    </div>

    <!-- Age Calculator -->
    <div class="tool-card border-t-4 border-lime-500">
        <h3 style="font-weight:bold; font-size: 18px;">Age Calculator</h3>
        <label style="font-size:12px;">Date of Birth:</label>
        <input type="date" id="age-dob">
        <button onclick="calculateAge()" class="btn-action bg-lime-600">Calculate</button>
        <div id="age-result" style="margin-top:10px; font-weight:bold; color:green;"></div>
    </div>

    <canvas id="canvas" style="display:none;"></canvas>

    <script>
        const { PDFDocument } = PDFLib;

        // --- Android Bridge Calls ---
        function triggerAndroidFilePicker() {
            if(window.AndroidBridge) {
                document.getElementById('merge-status').innerText = "Select files from Android...";
                window.AndroidBridge.selectPdfFiles();
            } else {
                alert("Android Bridge not found!");
            }
        }

        // Called from Android after files are selected and converted to base64
        async function performMergeFromAndroid(base64ArrayStr) {
            document.getElementById('merge-status').innerText = "Merging PDFs...";
            try {
                const base64Pdfs = JSON.parse(base64ArrayStr);
                if(base64Pdfs.length < 2) {
                     document.getElementById('merge-status').innerText = "Error: Need at least 2 PDFs";
                     return;
                }

                const mergedPdf = await PDFDocument.create();

                for (const base64 of base64Pdfs) {
                    const pdfBytes = Uint8Array.from(atob(base64), c => c.charCodeAt(0));
                    const pdfDoc = await PDFDocument.load(pdfBytes);
                    const copiedPages = await mergedPdf.copyPages(pdfDoc, pdfDoc.getPageIndices());
                    copiedPages.forEach((page) => mergedPdf.addPage(page));
                }

                const mergedPdfBytes = await mergedPdf.saveAsBase64();
                document.getElementById('merge-status').innerText = "Merge Complete! Saving to device...";
                
                if(window.AndroidBridge) {
                    window.AndroidBridge.saveMergedPdf(mergedPdfBytes);
                }
            } catch (error) {
                document.getElementById('merge-status').innerText = "Error: " + error;
            }
        }

        // Image Processing
        function processImage(targetW, targetH, filename, elementId) {
            const fileInput = document.getElementById(elementId);
            if (!fileInput.files[0]) return alert("Select an image");
            const reader = new FileReader();
            reader.onload = function(event) {
                const img = new Image();
                img.onload = function() {
                    const canvas = document.getElementById('canvas');
                    canvas.width = targetW; canvas.height = targetH;
                    const ctx = canvas.getContext('2d');
                    ctx.fillStyle = "#FFFFFF"; ctx.fillRect(0,0, targetW, targetH);
                    ctx.drawImage(img, 0, 0, targetW, targetH);
                    
                    // Convert to Base64 and send to Android to save
                    const base64Data = canvas.toDataURL('image/jpeg', 0.9).split(',')[1];
                    if(window.AndroidBridge) {
                        window.AndroidBridge.saveImage(base64Data, filename);
                        alert("Image saved to Downloads folder!");
                    }
                }
                img.src = event.target.result;
            }
            reader.readAsDataURL(fileInput.files[0]);
        }

        // Age Calc
        function calculateAge() {
            const dobInput = document.getElementById('age-dob').value;
            if(!dobInput) return alert("Please select Date of Birth");
            const dob = new Date(dobInput);
            const target = new Date();
            let years = target.getFullYear() - dob.getFullYear();
            let months = target.getMonth() - dob.getMonth();
            let days = target.getDate() - dob.getDate();
            if (days < 0) {
                months--; const prevMonth = new Date(target.getFullYear(), target.getMonth(), 0); days += prevMonth.getDate();
            }
            if (months < 0) { years--; months += 12; }
            document.getElementById('age-result').innerText = years + " Years, " + months + " Months, " + days + " Days";
        }
    </script>
</body>
</html>
"""

// ─────────────────────────────────────────────
// JavaScript Interface Setup
// ─────────────────────────────────────────────
class WebAppInterface(
    private val context: Context,
    private val onSelectPdfs: () -> Unit,
    private val onSavePdf: (String) -> Unit,
    private val onSaveImage: (String, String) -> Unit
) {
    @JavascriptInterface
    fun selectPdfFiles() {
        onSelectPdfs()
    }

    @JavascriptInterface
    fun saveMergedPdf(base64Data: String) {
        onSavePdf(base64Data)
    }

    @JavascriptInterface
    fun saveImage(base64Data: String, filename: String) {
        onSaveImage(base64Data, filename)
    }
}

// ─────────────────────────────────────────────
// MainActivity
// ─────────────────────────────────────────────
class MainActivity : ComponentActivity() {

    companion object {
        const val PREFS_NAME = "fakelock_prefs"
        const val KEY_OPACITY = "btn_opacity"
        const val KEY_SIZE = "btn_size"
        const val KEY_AUTO_SECONDS = "auto_seconds"
        const val KEY_ENABLE_FLOAT = "enable_float"
        const val KEY_ENABLE_VOLUME = "enable_volume"
        const val KEY_AUTO_HIDE = "auto_hide"
        const val ACTION_UPDATE_SETTINGS = "com.rasel.blocker.UPDATE_SETTINGS"

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            } else {
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }

    // PDF Selection Launcher
    private var webViewRef: WebView? = null
    private val selectPdfLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty() && webViewRef != null) {
            // Convert URIs to Base64 in a background thread to prevent UI freezing
            Thread {
                try {
                    val base64List = mutableListOf<String>()
                    for (uri in uris) {
                        val inputStream = contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        if (bytes != null) {
                            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                            base64List.add(base64)
                        }
                        inputStream?.close()
                    }
                    val jsonArray = org.json.JSONArray(base64List).toString()
                    
                    // Send to JS
                    runOnUiThread {
                        webViewRef?.evaluateJavascript("performMergeFromAndroid('\$jsonArray')", null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── System Status Bar সবসময় Hide ──
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

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
                var showWebTools by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxSize()) {
                    // ── Custom Status Bar ──
                    CustomStatusBar()
                    // ── Main Settings Screen ──
                    SettingsScreen(
                        prefs = prefs,
                        context = this@MainActivity,
                        onOpenWebTools = { showWebTools = true },
                        onStartService = { autoLockSeconds, opacity, size, enFloat, enVol, autoHide ->
                            saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                val intent = Intent(this@MainActivity, FakeLockService::class.java)
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
                            stopService(Intent(this@MainActivity, FakeLockService::class.java))
                        },
                        onUpdateSettings = { opacity, size, autoLockSeconds, enFloat, enVol, autoHide ->
                            saveAndBroadcast(prefs, autoLockSeconds, opacity, size, enFloat, enVol, autoHide)
                        }
                    )
                }

                // Web Tools Full Screen Dialog
                if (showWebTools) {
                    Dialog(
                        onDismissRequest = { showWebTools = false },
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = ComposeColor.White
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                // Top Bar for WebView
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ComposeColor(0xFF2563eb))
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = { showWebTools = false }) {
                                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = ComposeColor.White)
                                    }
                                    Text("Web Tools & PDF", color = ComposeColor.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }

                                // The WebView
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { context ->
                                        WebView(context).apply {
                                            webViewRef = this
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                            settings.apply {
                                                javaScriptEnabled = true
                                                domStorageEnabled = true
                                                allowFileAccess = true
                                                allowContentAccess = true
                                            }
                                            webChromeClient = object : WebChromeClient() {}
                                            
                                            addJavascriptInterface(WebAppInterface(
                                                context = context,
                                                onSelectPdfs = {
                                                    selectPdfLauncher.launch("application/pdf")
                                                },
                                                onSavePdf = { base64Data ->
                                                    saveFileToDownloads(context, base64Data, "Merged_Document.pdf", "application/pdf")
                                                },
                                                onSaveImage = { base64Data, filename ->
                                                     saveFileToDownloads(context, base64Data, filename, "image/jpeg")
                                                }
                                            ), "AndroidBridge")

                                            // Load HTML string
                                            loadDataWithBaseURL(null, WEB_TOOLS_HTML, "text/html", "UTF-8", null)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun saveFileToDownloads(context: Context, base64Data: String, fileName: String, mimeType: String) {
        try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                val outputStream = context.contentResolver.openOutputStream(uri)
                outputStream?.write(bytes)
                outputStream?.close()
                runOnUiThread {
                    android.widget.Toast.makeText(context, "Saved to Downloads: \$fileName", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                android.widget.Toast.makeText(context, "Failed to save file", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.statusBars())

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FakeLockService::class.java)
            startForegroundService(intent)
        }
    }

    private fun saveAndBroadcast(
        prefs: SharedPreferences, autoLockSeconds: Int, opacity: Float, size: Int,
        enFloat: Boolean, enVol: Boolean, autoHide: Boolean
    ) {
        prefs.edit()
            .putInt(KEY_AUTO_SECONDS, autoLockSeconds)
            .putFloat(KEY_OPACITY, opacity)
            .putInt(KEY_SIZE, size)
            .putBoolean(KEY_ENABLE_FLOAT, enFloat)
            .putBoolean(KEY_ENABLE_VOLUME, enVol)
            .putBoolean(KEY_AUTO_HIDE, autoHide)
            .apply()

        val intent = Intent(ACTION_UPDATE_SETTINGS)
        sendBroadcast(intent)
    }
}

// ─────────────────────────────────────────────
// Custom Status Bar Composable
// ─────────────────────────────────────────────
@Composable
fun CustomStatusBar() {
    val context = LocalContext.current

    val batteryLevel = remember { mutableIntStateOf(100) }
    val isCharging = remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                batteryLevel.intValue = (level * 100 / scale)
                isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val currentTime = remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            currentTime.value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000)
        }
    }

    val batteryIcon: ImageVector = when {
        isCharging.value -> Icons.Filled.BatteryChargingFull
        batteryLevel.intValue > 80 -> Icons.Filled.BatteryFull
        batteryLevel.intValue > 60 -> Icons.Filled.Battery6Bar
        batteryLevel.intValue > 40 -> Icons.Filled.Battery4Bar
        batteryLevel.intValue > 20 -> Icons.Filled.Battery2Bar
        else -> Icons.Filled.Battery0Bar
    }

    val batteryColor = when {
        batteryLevel.intValue <= 15 -> ComposeColor(0xFFFF4444)
        batteryLevel.intValue <= 30 -> ComposeColor(0xFFFFAA00)
        else -> ComposeColor.White
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ComposeColor(0xFF0A0A0A))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = currentTime.value,
                color = ComposeColor.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = Icons.Filled.SignalCellularAlt,
                contentDescription = "Signal",
                tint = ComposeColor.White,
                modifier = Modifier.size(15.dp)
            )
            Icon(
                imageVector = Icons.Filled.Wifi,
                contentDescription = "WiFi",
                tint = ComposeColor.White,
                modifier = Modifier.size(15.dp)
            )
        }

        Row(
            modifier = Modifier.padding(end = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "${batteryLevel.intValue}%",
                color = batteryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                imageVector = batteryIcon,
                contentDescription = "Battery",
                tint = batteryColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────
// Compose Settings UI
// ─────────────────────────────────────────────
@Composable
fun SettingsScreen(
    prefs: SharedPreferences,
    context: Context,
    onOpenWebTools: () -> Unit,
    onStartService: (Int, Float, Int, Boolean, Boolean, Boolean) -> Unit,
    onStopService: () -> Unit,
    onUpdateSettings: (Float, Int, Int, Boolean, Boolean, Boolean) -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var selectedSeconds by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 0)) }
    var opacity by remember { mutableStateOf(prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f)) }
    var btnSize by remember { mutableStateOf(prefs.getInt(MainActivity.KEY_SIZE, 110)) }

    var enableFloat by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_ENABLE_FLOAT, true)) }
    var enableVolume by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_ENABLE_VOLUME, true)) }
    var autoHide by remember { mutableStateOf(prefs.getBoolean(MainActivity.KEY_AUTO_HIDE, false)) }

    val autoLockOptions = listOf(0, 15, 30, 60, 120)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0A0A0A))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text("🔒 FakeLock", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        Text("Smart Lock Screen Utility", fontSize = 13.sp, color = ComposeColor(0xFF888888))

        Spacer(modifier = Modifier.height(2.dp))
        
        // ── Web Tools Button ──
        Button(
            onClick = onOpenWebTools,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF2563eb))
        ) {
            Icon(Icons.Filled.Build, contentDescription = "Tools", tint = ComposeColor.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Open PDF & Web Tools", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        }

        // ── New Website Button ──
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://raseledutools.github.io/"))
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF10B981)) // Green Color
        ) {
            Icon(Icons.Filled.Public, contentDescription = "Website", tint = ComposeColor.White, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Visit Rasel Edu Tools", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ComposeColor.White)
        }

        // Smart Settings Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚙️ Smart Settings", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume Button Lock/Unlock", fontSize = 13.sp, color = ComposeColor.White)
                    Switch(checked = enableVolume, onCheckedChange = {
                        enableVolume = it
                        onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                    })
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Floating Button দেখাও", fontSize = 13.sp, color = ComposeColor.White)
                    Switch(checked = enableFloat, onCheckedChange = {
                        enableFloat = it
                        onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                    })
                }

                if (enableFloat) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Auto-Hide Button", fontSize = 13.sp, color = ComposeColor.White)
                            Text("শুধু Home Screen এ দেখাবে", fontSize = 10.sp, color = ComposeColor.Gray)
                        }
                        Switch(checked = autoHide, onCheckedChange = {
                            if (it && !MainActivity.hasUsageStatsPermission(context)) {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } else {
                                autoHide = it
                                onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                            }
                        })
                    }
                }
            }
        }

        // Sliders Card
        if (enableFloat) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🔘 Button Opacity", fontSize = 13.sp, color = ComposeColor.White)
                        Text("${(opacity * 100).toInt()}%", fontSize = 13.sp, color = ComposeColor(0xFF9C27B0))
                    }
                    Slider(
                        value = opacity,
                        onValueChange = { opacity = it; onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide) },
                        valueRange = 0.1f..1.0f
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("📐 Button Size", fontSize = 13.sp, color = ComposeColor.White)
                        Text("${btnSize}px", fontSize = 13.sp, color = ComposeColor(0xFF6200EE))
                    }
                    Slider(
                        value = btnSize.toFloat(),
                        onValueChange = { btnSize = it.toInt(); onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide) },
                        valueRange = 60f..180f
                    )
                }
            }
        }

        // Auto Lock Time
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF1A1A1A))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("⏱ Auto Lock সময় (Off রাখাই ভালো)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ComposeColor.White)
                Spacer(modifier = Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    autoLockOptions.forEach { sec ->
                        val label = if (sec == 0) "Off" else if (sec >= 60) "${sec / 60}m" else "${sec}s"
                        val selected = selectedSeconds == sec
                        Button(
                            onClick = {
                                selectedSeconds = sec
                                onUpdateSettings(opacity, btnSize, selectedSeconds, enableFloat, enableVolume, autoHide)
                            },
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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                if (!isRunning) {
                    onStartService(selectedSeconds, opacity, btnSize, enableFloat, enableVolume, autoHide)
                    isRunning = true
                } else {
                    onStopService()
                    isRunning = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
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

    private var mediaSession: MediaSession? = null

    private var autoLockSeconds = 0
    private var btnOpacity = 0.7f
    private var btnSize = 110
    private var enableFloat = true
    private var enableVolume = true
    private var autoHide = false

    private var isLocked = false
    private var lastInteractionTime = System.currentTimeMillis()
    private var lastVolumeToggleTime = 0L
    private var launcherPackage = ""

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MainActivity.ACTION_UPDATE_SETTINGS) {
                loadSettings()
                applySettings()
                setupVolumeInterception()
            }
        }
    }

    private val backgroundTaskRunnable = object : Runnable {
        override fun run() {
            if (autoLockSeconds > 0 && !isLocked) {
                val elapsed = (System.currentTimeMillis() - lastInteractionTime) / 1000
                if (elapsed >= autoLockSeconds) showLockScreen()
            }

            if (enableFloat && autoHide && !isLocked) {
                if (MainActivity.hasUsageStatsPermission(this@FakeLockService)) {
                    val fgApp = getForegroundApp(this@FakeLockService)
                    if (fgApp == launcherPackage) {
                        floatingButtonView?.visibility = View.VISIBLE
                    } else {
                        floatingButtonView?.visibility = View.GONE
                    }
                }
            }
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

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        launcherPackage = getLauncherPackageName(this)

        loadSettings()

        val filter = IntentFilter(MainActivity.ACTION_UPDATE_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(updateReceiver, filter)
        }

        setupVolumeInterception()
        showFloatingButton()
        applySettings()
        handler.post(backgroundTaskRunnable)
    }

    private fun setupVolumeInterception() {
        if (!enableVolume) {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            return
        }

        if (mediaSession == null) {
            mediaSession = MediaSession(this, "FakeLockVolumeHack")
            val playbackState = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
            mediaSession?.setPlaybackState(playbackState)

            mediaSession?.setPlaybackToRemote(object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    val now = System.currentTimeMillis()
                    if (now - lastVolumeToggleTime > 500) {
                        lastVolumeToggleTime = now
                        Handler(Looper.getMainLooper()).post {
                            if (isLocked) hideLockScreen() else showLockScreen()
                        }
                    }
                }
            })
        }
        mediaSession?.isActive = true
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        autoLockSeconds = prefs.getInt(MainActivity.KEY_AUTO_SECONDS, 0)
        btnOpacity = prefs.getFloat(MainActivity.KEY_OPACITY, 0.7f)
        btnSize = prefs.getInt(MainActivity.KEY_SIZE, 110)
        enableFloat = prefs.getBoolean(MainActivity.KEY_ENABLE_FLOAT, true)
        enableVolume = prefs.getBoolean(MainActivity.KEY_ENABLE_VOLUME, true)
        autoHide = prefs.getBoolean(MainActivity.KEY_AUTO_HIDE, false)
    }

    private fun applySettings() {
        if (!enableFloat) {
            floatingButtonView?.visibility = View.GONE
        } else if (!isLocked) {
            if (!autoHide) floatingButtonView?.visibility = View.VISIBLE
            updateFloatingButton()
        }
    }

    private fun getLauncherPackageName(context: Context): String {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName ?: ""
    }

    private fun getForegroundApp(context: Context): String? {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val time = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time)
        if (stats != null) {
            var latest: android.app.usage.UsageStats? = null
            for (usageStats in stats) {
                if (latest == null || usageStats.lastTimeUsed > latest.lastTimeUsed) {
                    latest = usageStats
                }
            }
            return latest?.packageName
        }
        return null
    }

    private fun startForegroundNotification() {
        val channelId = "fakelock_ch"
        val channel = NotificationChannel(channelId, "FakeLock", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        val notif = Notification.Builder(this, channelId)
            .setContentTitle("FakeLock চালু আছে")
            .setContentText("স্মার্ট লক ব্যাকগ্রাউন্ডে কাজ করছে")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .build()
        startForeground(1, notif)
    }

    private fun showFloatingButton() {
        val ctx = this
        floatingButtonView = android.widget.FrameLayout(ctx).apply {
            val btn = android.widget.TextView(ctx).apply {
                text = "🔒"
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
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

        floatingButtonView!!.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0f; var startY = 0f
            var startRawX = 0f; var startRawY = 0f
            var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                lastInteractionTime = System.currentTimeMillis()
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
                        if (!isDragging) showLockScreen()
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
        catch (e: Exception) { }
    }

    private var clockTextView: android.widget.TextView? = null
    private var dateTextView: android.widget.TextView? = null
    private var lastTapTime = 0L

    private val aggressiveUiFlags = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

    private fun showLockScreen() {
        if (isLocked) return
        isLocked = true
        floatingButtonView?.visibility = View.GONE

        val ctx = this
        lockScreenView = object : android.widget.FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent?): Boolean {
                if (event?.action == MotionEvent.ACTION_UP) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 400) hideLockScreen()
                    lastTapTime = now
                    lastInteractionTime = System.currentTimeMillis()
                }
                return true
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            systemUiVisibility = aggressiveUiFlags

            setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    handler.postDelayed({ systemUiVisibility = aggressiveUiFlags }, 100)
                }
            }

            val center = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                clockTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentTime()
                    textSize = 80f
                    setTextColor(Color.argb(70, 255, 255, 255))
                    typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }

                dateTextView = android.widget.TextView(ctx).apply {
                    text = getCurrentDate()
                    textSize = 16f
                    setTextColor(Color.argb(50, 255, 255, 255))
                    gravity = Gravity.CENTER
                    setPadding(0, 6, 0, 0)
                }

                val hint = android.widget.TextView(ctx).apply {
                    text = "double tap to unlock"
                    textSize = 11f
                    setTextColor(Color.argb(40, 255, 255, 255))
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
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.OPAQUE
        ).apply {
            screenBrightness = 0.0f
            dimAmount = 1.0f
        }

        windowManager.addView(lockScreenView, params)
        handler.post(clockRunnable)
    }

    private fun hideLockScreen() {
        isLocked = false
        handler.removeCallbacks(clockRunnable)
        lockScreenView?.let { windowManager.removeView(it) }
        lockScreenView = null

        lastInteractionTime = System.currentTimeMillis()
        applySettings()
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
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
        floatingButtonView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        lockScreenView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
    }
}
