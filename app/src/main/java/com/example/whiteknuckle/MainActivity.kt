package com.example.whiteknuckle

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?
)

class MainActivity : ComponentActivity() {

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private var pendingVpnStart = false

    fun getInstalledApps(): List<InstalledApp> {
        val packageManager = packageManager

        return packageManager
            .getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { appInfo ->
                appInfo.packageName != packageName &&
                        packageManager.getLaunchIntentForPackage(appInfo.packageName) != null
            }
            .map { appInfo ->
                InstalledApp(
                    packageName = appInfo.packageName,
                    label = appInfo.loadLabel(packageManager).toString(),
                    icon = appInfo.loadIcon(packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK && pendingVpnStart) {
                startVpn()
            } else {
                Toast.makeText(
                    this,
                    "VPN permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }

            pendingVpnStart = false
        }

        enableEdgeToEdge()

        setContent {
            WhiteknuckleTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    MainScreen(
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        ),
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
    val activity = context as MainActivity

    val installedApps = remember {
        activity.getInstalledApps()
    }

    val routingPreferences = remember(context) {
        context.getSharedPreferences(
            "vpn_routing",
            android.content.Context.MODE_PRIVATE
        )
    }

    var selectedApps by remember {
        mutableStateOf(
            routingPreferences.getStringSet(
                "selected_apps",
                emptySet()
            )?.toSet() ?: emptySet()
        )
    }

    var showRoutingDialog by remember {
        mutableStateOf(false)
    }

    var vpnMode by remember {
        mutableStateOf(
            routingPreferences.getString(
                "mode",
                "all"
            ) ?: "all"
        )
    }

    val isRunning by OlcrtcService.isRunning.collectAsState()
    val isVpnRunning by LocalVpnService.isRunning.collectAsState()

    val sharedPreferences = remember(context) {
        context.getSharedPreferences(
            PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }

    val initialYaml = remember(sharedPreferences) {
        sharedPreferences.getString(
            KEY_RAW_YAML,
            DEFAULT_CLIENT_YAML
        ) ?: DEFAULT_CLIENT_YAML
    }

    var rawYaml by remember {
        mutableStateOf(initialYaml)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        OutlinedTextField(
            value = rawYaml,
            onValueChange = {
                rawYaml = it

                sharedPreferences
                    .edit()
                    .putString(KEY_RAW_YAML, it)
                    .apply()
            },
            label = {
                Text("client.yaml")
            },
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

                    val serviceIntent = Intent(
                        context,
                        OlcrtcService::class.java
                    ).apply {
                        putExtra(
                            "binaryPath",
                            binaryFile.absolutePath
                        )
                        putExtra(
                            "configPath",
                            configFile.absolutePath
                        )
                    }

                    ContextCompat.startForegroundService(
                        context,
                        serviceIntent
                    )
                }
            },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start")
        }

        Button(
            onClick = {
                val stopIntent = Intent(
                    context,
                    OlcrtcService::class.java
                ).apply {
                    action = OlcrtcService.ACTION_STOP
                }

                ContextCompat.startForegroundService(
                    context,
                    stopIntent
                )
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
                showRoutingDialog = true
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when (vpnMode) {
                    "include" -> "VPN Routing: Только выбранные"
                    "exclude" -> "VPN Routing: Все кроме выбранных"
                    else -> "VPN Routing: Все приложения"
                }
            )
        }

        Button(
            onClick = {
                context.startActivity(
                    Intent(
                        context,
                        LogsActivity::class.java
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Logs")
        }
    }

    if (showRoutingDialog) {
        AlertDialog(
            onDismissRequest = {
                showRoutingDialog = false
            },
            title = {
                Text("VPN Routing")
            },
            text = {
                Column {

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = vpnMode == "all",
                            onClick = {
                                vpnMode = "all"

                                routingPreferences.edit()
                                    .putString("mode", "all")
                                    .apply()
                            }
                        )

                        Text("Все приложения")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = vpnMode == "include",
                            onClick = {
                                vpnMode = "include"

                                routingPreferences.edit()
                                    .putString("mode", "include")
                                    .apply()
                            }
                        )

                        Text("Только выбранные")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = vpnMode == "exclude",
                            onClick = {
                                vpnMode = "exclude"

                                routingPreferences.edit()
                                    .putString("mode", "exclude")
                                    .apply()
                            }
                        )

                        Text("Все кроме выбранных")
                    }

                    if (vpnMode != "all") {
                        HorizontalDivider(
                            modifier = Modifier.padding(
                                vertical = 8.dp
                            )
                        )

                        Text("Приложения")

                        LazyColumn(
                            modifier = Modifier.height(400.dp)
                        ) {
                            items(
                                items = installedApps,
                                key = { it.packageName }
                            ) { app ->

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = app.packageName in selectedApps,
                                        onCheckedChange = { checked ->
                                            selectedApps =
                                                if (checked) {
                                                    selectedApps + app.packageName
                                                } else {
                                                    selectedApps - app.packageName
                                                }

                                            routingPreferences.edit()
                                                .putStringSet("selected_apps", selectedApps)
                                                .apply()
                                        }
                                    )

                                    Text(
                                        text = app.label,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRoutingDialog = false
                    }
                ) {
                    Text("Закрыть")
                }
            }
        )
    }
}