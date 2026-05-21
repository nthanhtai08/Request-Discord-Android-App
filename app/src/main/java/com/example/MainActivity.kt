package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.example.data.AppDatabase
import com.example.ui.DashboardScreen
import com.example.ui.MainViewModel
import com.example.ui.MainViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import com.example.service.DiscordQuestService

class MainActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }
    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(db) }

    private var questService: DiscordQuestService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DiscordQuestService.LocalBinder
            questService = binder.getService()
            isBound = true
            viewModel.setServiceReference(questService)
            
            // Auto refresh state on screen launch if bound and logged in
            viewModel.currentAccount.value?.let {
                viewModel.refreshQuests(it.token)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            questService = null
            isBound = false
            viewModel.setServiceReference(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Bind Service immediately on launch
        val intent = Intent(this, DiscordQuestService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DashboardScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
            viewModel.setServiceReference(null)
        }
    }
}

