package com.roohu.connect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import org.json.JSONObject

private val Purple = Color(0xFF8B5CF6)
private val Bg = Color(0xFF070B1E)
private val CardC = Color(0xFF111736)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Purple, background = Bg, surface = CardC,
                onSurface = Color.White, onBackground = Color.White)) {
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) { delay(1200); splash = false }
                if (splash) Splash() else Home(app)
            }
        }
    }
}

@Composable
fun Splash() = Box(
    Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1B1146), Bg))),
    contentAlignment = Alignment.Center
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("∞", fontSize = 80.sp, color = Purple, fontWeight = FontWeight.Bold)
        Text("ROOHU CONNECT", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Your PC & Android – One Powerful Connection", fontSize = 12.sp, color = Color(0xFFB8BEDC))
    }
}

@Composable
fun Home(app: App) {
    val link = app.link
    val state by link.state.collectAsState()
    val pcs by link.pcs.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val disc = remember { Discovery(ctx) }
    val nearby by disc.found.collectAsState()
    DisposableEffect(Unit) { disc.start(); onDispose { disc.stop() } }

    var pinFor by remember { mutableStateOf<Found?>(null) }
    var manual by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val txt = r.contents
        if (txt != null) try {
            val j = JSONObject(txt)
            link.pair(j.getJSONArray("h").getString(0), j.getInt("p"), j.getString("fp"), j.getString("pin"))
            error = null
        } catch (e: Exception) { error = "This QR code is not from Roohu Connect." }
    }
    LaunchedEffect(Unit) { pcs.firstOrNull()?.let { link.connectSaved(it) } }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ROOHU CONNECT", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        StatusCard(state)
        Button(onClick = { scan.launch(ScanOptions().setBeepEnabled(false).setPrompt("Scan the QR code on your PC")) },
            modifier = Modifier.fillMaxWidth()) { Text("Scan QR code from PC") }
        OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) { Text("Pair with address + code") }
        error?.let { Text(it, color = Color(0xFFFF6B6B)) }

        Text("My PCs", fontWeight = FontWeight.SemiBold)
        if (pcs.isEmpty()) Text("No paired PC yet.", color = Color(0xFFB8BEDC))
        pcs.forEach { pc ->
            Card(colors = CardDefaults.cardColors(containerColor = CardC)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(pc.name, fontWeight = FontWeight.Bold); Text(pc.host, fontSize = 12.sp, color = Color(0xFFB8BEDC)) }
                    TextButton(onClick = { link.connectSaved(pc) }) { Text("Connect") }
                    TextButton(onClick = { link.forget(pc) }) { Text("Forget") }
                }
            }
        }

        Text("PCs on this Wi-Fi", fontWeight = FontWeight.SemiBold)
        if (nearby.isEmpty()) Text("Searching…", color = Color(0xFFB8BEDC))
        nearby.forEach { f ->
            Card(colors = CardDefaults.cardColors(containerColor = CardC), modifier = Modifier.clickable { pinFor = f }) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(f.name, fontWeight = FontWeight.Bold)
                    Text("${f.host} – tap to pair with code", fontSize = 12.sp, color = Color(0xFFB8BEDC))
                }
            }
        }
    }

    pinFor?.let { f ->
        CodeDialog("Pair with ${f.name}", null, { pinFor = null }) { _, _, pin -> link.pair(f.host, f.port, null, pin); pinFor = null }
    }
    if (manual) CodeDialog("Pair with address + code", "", { manual = false }) { host, port, pin ->
        link.pair(host, port, null, pin); manual = false
    }
}

@Composable
fun StatusCard(s: LinkState) {
    val (color, text) = when (s) {
        is LinkState.Connected -> Color(0xFF22C55E) to "Connected to ${s.pcName}"
        is LinkState.Connecting -> Color(0xFFF59E0B) to s.text
        is LinkState.Failed -> Color(0xFFEF4444) to s.message
        LinkState.Idle -> Color(0xFF6B7280) to "Not connected"
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardC)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Text(text)
        }
    }
}

/** host == null: only ask for the code. host != null: ask for address too. */
@Composable
fun CodeDialog(title: String, host: String?, onDismiss: () -> Unit, onOk: (String, Int, String) -> Unit) {
    var h by remember { mutableStateOf(host ?: "") }
    var port by remember { mutableStateOf("47890") }
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (host != null) {
                    OutlinedTextField(h, { h = it.trim() }, label = { Text("PC address (e.g. 192.168.1.5)") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code shown on PC") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length == 6 && (host == null || h.isNotEmpty()) && port.isNotEmpty(),
                onClick = { onOk(h, port.toInt(), pin) }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
