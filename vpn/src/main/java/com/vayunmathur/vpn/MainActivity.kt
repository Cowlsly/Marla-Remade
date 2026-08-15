package com.vayunmathur.vpn

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.vpn.data.ConnectionLogDao
import com.vayunmathur.vpn.data.VpnConfigDao
import com.vayunmathur.vpn.data.VpnDatabase
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelFactory
import com.vayunmathur.vpn.util.VpnNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var configDao: VpnConfigDao
    private lateinit var logDao: ConnectionLogDao
    private val vm: VpnViewModel by viewModels {
        VpnViewModelFactory(application, configDao, logDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SYSTEM: user-supplied VPN endpoint dynamic host, cannot pin
        NetworkClient.init(this, TrustBundle.SYSTEM)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { VpnNative.init() }
            val db = VpnDatabase.get(this@MainActivity)
            configDao = db.vpnConfigDao()
            logDao = db.connectionLogDao()
            withContext(Dispatchers.Main) {
                ready.value = true
                handleIntent(intent)
            }
        }

        setContent {
            DynamicTheme {
                OfflineAware {
                    if (ready.value) Navigation(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Accept VIEW intents for .conf files from Files / Downloads etc.
        if (intent.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            vm.importFromUri(this, uri)
        }
    }
}
