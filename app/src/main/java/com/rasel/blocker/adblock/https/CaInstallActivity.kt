package com.rasel.blocker.adblock.https

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CaInstallActivity
 * ─────────────────────────────────────────────────────────────────
 * CA Certificate install করার step-by-step guide।
 * User এই screen থেকে:
 *  1. নতুন CA generate করতে পারবে
 *  2. System এ install করতে পারবে
 *  3. HTTPS filtering চালু করতে পারবে
 * ─────────────────────────────────────────────────────────────────
 */
class CaInstallActivity : ComponentActivity() {

    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "✅ CA Certificate ইনস্টল সফল!", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "⚠️ Install বাতিল হয়েছে", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CaInstallScreen(
                    hasCa = CaManager.hasCa(this),
                    onGenerateCa = {
                        val ok = CaManager.generateCa(this)
                        Toast.makeText(
                            this,
                            if (ok) "CA তৈরি হয়েছে!" else "CA তৈরি ব্যর্থ!",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onInstallCa = {
                        val intent = CaManager.promptInstallCa(this)
                        if (intent != null) {
                            installLauncher.launch(intent)
                        } else {
                            Toast.makeText(this, "আগে CA তৈরি করুন", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun CaInstallScreen(
    hasCa: Boolean,
    onGenerateCa: () -> Unit,
    onInstallCa: () -> Unit,
    onClose: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .padding(20.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                "🔒 HTTPS Filter Setup",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
            )
        }

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("কেন CA Certificate দরকার?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "HTTPS traffic encrypted। এটা block করতে হলে আমাদের " +
                    "একটা Custom CA Certificate লাগে যা device এ trust করা হবে। " +
                    "এটা আপনার নিজের device এর জন্য — কোনো data বাইরে যায় না।",
                    color = Color(0xFF94A3B8), fontSize = 13.sp
                )
            }
        }

        // Steps
        Text("ধাপগুলো:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

        StepCard(
            step = "১",
            title = "CA Certificate তৈরি করুন",
            desc = "আপনার device এর জন্য একটি unique CA key তৈরি হবে",
            icon = Icons.Filled.Key,
            done = hasCa,
            buttonText = if (hasCa) "✅ CA আছে" else "CA তৈরি করুন",
            onAction = onGenerateCa,
            enabled = !hasCa
        )

        StepCard(
            step = "২",
            title = "System এ Install করুন",
            desc = "Android Settings এ Certificate install হবে। " +
                   "\"CA certificate\" → \"Install anyway\" তে tap করুন",
            icon = Icons.Filled.InstallMobile,
            done = false,
            buttonText = "Install করুন",
            onAction = onInstallCa,
            enabled = hasCa
        )

        StepCard(
            step = "৩",
            title = "HTTPS Filter চালু করুন",
            desc = "Main screen এ ফিরে HTTPS Filtering switch ON করুন",
            icon = Icons.Filled.Shield,
            done = false,
            buttonText = "Main Screen এ যান",
            onAction = onClose,
            enabled = true
        )

        // Warning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF7C2D12)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFFFA500))
                Column {
                    Text("সীমাবদ্ধতা", color = Color.White, fontWeight = FontWeight.Bold)
                    Text(
                        "• Root ছাড়া System CA install হয় না\n" +
                        "• User CA শুধু Browser এ কাজ করে\n" +
                        "• Certificate pinning করা apps (Gmail, Banking) তে কাজ করবে না\n" +
                        "• Android 7+ এ app কে network_security_config দিতে হয়",
                        color = Color(0xFFFFD0B0), fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StepCard(
    step: String, title: String, desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    done: Boolean, buttonText: String, onAction: () -> Unit, enabled: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done) Color(0xFF14532D) else Color(0xFF1E293B)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (done) Color(0xFF16A34A) else Color(0xFF334155)
                ) {
                    Text(
                        step, color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(desc, color = Color(0xFF94A3B8), fontSize = 12.sp)
            Button(
                onClick = onAction,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (done) Color(0xFF16A34A) else Color(0xFF2563EB)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(buttonText, fontWeight = FontWeight.Bold)
            }
        }
    }
}
