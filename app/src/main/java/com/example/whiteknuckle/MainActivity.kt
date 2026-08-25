package com.example.whiteknuckle

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.whiteknuckle.ui.theme.WhiteknuckleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "olcrtc_prefs"
private const val KEY_RAW_YAML = "raw_yaml"
private val DEFAULT_CLIENT_YAML = """
mode: cnc
auth:
  provider: telemost
room:
  id: ""
crypto:
  key: ""
net:
  transport: vp8channel
  dns: "77.88.8.8:53"
socks:
  host: "0.0.0.0"
  port: 8808
  user: "admin"
  pass: "123456"
vp8:
  fps: 15
  batch_size: 32
""".trimIndent()

class MainActivity : ComponentActivity() {
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private var pendingVpnStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && pendingVpnStart) {
                startVpn()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
            pendingVpnStart = false
        }

        enableEdgeToEdge()
        setContent {
            WhiteknuckleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        onStartVpn = { requestAndStartVpn() },
                        onStopVpn = { stopVpn() }
                    )
                }
            }
        }
    }

    private fun requestAndStartVpn() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnStart = true
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning by OlcrtcService.isRunning.collectAsState()
    val isVpnRunning by LocalVpnService.isRunning.collectAsState()
    val sharedPreferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val initialYaml = remember(sharedPreferences) {
        sharedPreferences.getString(KEY_RAW_YAML, DEFAULT_CLIENT_YAML) ?: DEFAULT_CLIENT_YAML
    }

    var rawYaml by remember { mutableStateOf(initialYaml) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = rawYaml,
            onValueChange = {
                rawYaml = it
                sharedPreferences.edit().putString(KEY_RAW_YAML, it).apply()
            },
            label = { Text("client.yaml") },
            minLines = 10,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                scope.launch {
                    val manager = BinaryManager(context)
                    val (binaryFile, configFile) = withContext(Dispatchers.IO) {
                        val binary = manager.getBinaryFile()
                        val config = manager.generateConfigFile(rawYaml)
                        binary to config
                    }

                    val serviceIntent = Intent(context, OlcrtcService::class.java).apply {
                        putExtra("binaryPath", binaryFile.absolutePath)
                        putExtra("configPath", configFile.absolutePath)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start")
        }

        Button(
            onClick = {
                val stopIntent = Intent(context, OlcrtcService::class.java).apply {
                    action = OlcrtcService.ACTION_STOP
                }
                ContextCompat.startForegroundService(context, stopIntent)
            },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop")
        }

        Button(
            onClick = onStartVpn,
            enabled = !isVpnRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start VPN")
        }

        Button(
            onClick = onStopVpn,
            enabled = isVpnRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop VPN")
        }

        Button(
            onClick = {
                context.startActivity(Intent(context, LogsActivity::class.java))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Logs")
        }
    }
}