package com.wevlo.simpleadblocker // আপনার প্যাকেজ নাম দিন

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startAdBlockerService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val startBtn = Button(this).apply {
            text = "Start AdBlocker VPN"
            setOnClickListener {
                val intent = VpnService.prepare(this@MainActivity)
                if (intent != null) {
                    vpnPermissionLauncher.launch(intent)
                } else {
                    startAdBlockerService()
                }
            }
        }

        val stopBtn = Button(this).apply {
            text = "Stop AdBlocker VPN"
            setOnClickListener {
                val intent = Intent(this@MainActivity, AdBlockVpnService::class.java).apply {
                    action = AdBlockVpnService.ACTION_STOP
                }
                startService(intent)
            }
        }

        layout.addView(startBtn)
        layout.addView(stopBtn)
        setContentView(layout)
    }

    private fun startAdBlockerService() {
        val intent = Intent(this, AdBlockVpnService::class.java).apply {
            action = AdBlockVpnService.ACTION_START
        }
        startService(intent)
    }
}