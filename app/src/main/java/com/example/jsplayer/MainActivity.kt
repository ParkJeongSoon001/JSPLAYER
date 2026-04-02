package com.example.jsplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.jsplayer.ui.theme.JSPLAYERTheme
import org.jupnp.model.meta.Device

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<Device<*, *, *>>()
    private lateinit var dlnaManager: DLNAManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        dlnaManager = DLNAManager(
            context = this,
            onDeviceAdded = { device ->
                runOnUiThread {
                    if (devices.none { it.identity.udn == device.identity.udn }) {
                        devices.add(device)
                    }
                }
            },
            onDeviceRemoved = { device ->
                runOnUiThread {
                    devices.removeIf { it.identity.udn == device.identity.udn }
                }
            }
        )

        enableEdgeToEdge()
        setContent {
            JSPLAYERTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                        Button(onClick = { 
                            devices.clear()
                            dlnaManager.search() 
                        }) {
                            Text("DLNA 서버 재검색")
                        }
                        
                        Text(
                            text = "발견된 장치: ${devices.size}개",
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        
                        LazyColumn {
                            items(devices) { device ->
                                DeviceItem(device)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dlnaManager.start()
    }

    override fun onStop() {
        super.onStop()
        dlnaManager.stop()
    }
}

@Composable
fun DeviceItem(device: Device<*, *, *>) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(text = device.details.friendlyName ?: "알 수 없는 장치", style = MaterialTheme.typography.titleMedium)
        Text(text = device.displayString, style = MaterialTheme.typography.bodySmall)
    }
}
