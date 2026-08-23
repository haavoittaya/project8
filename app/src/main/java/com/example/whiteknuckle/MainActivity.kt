package com.example.whiteknuckle

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WhiteknuckleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isRunning by OlcrtcService.isRunning.collectAsState()
    val logs by OlcrtcService.logs.collectAsState()
    val listState = rememberLazyListState()
    val sharedPreferences = remember(context) {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }
    val initialYaml = remember(sharedPreferences) {
        sharedPreferences.getString(KEY_RAW_YAML, DEFAULT_CLIENT_YAML) ?: DEFAULT_CLIENT_YAML
    }

    var rawYaml by remember { mutableStateOf(initialYaml) }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

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
                val stopIntent = Intent(context, OlcrtcService::class.java)
                context.stopService(stopIntent)
            },
            enabled = isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Stop")
        }

        Button(
            onClick = {
                val logsText = OlcrtcService.logs.value.joinToString("\n")
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Olcrtc logs", logsText))
                Toast.makeText(context, "Логи скопированы", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Logs")
        }

        HorizontalDivider()

        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(logs) { index, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
                    if (index < logs.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}