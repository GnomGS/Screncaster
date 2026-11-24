package com.screencast.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var toggle: Button
    private lateinit var copy: Button
    private lateinit var status: TextView
    private lateinit var url: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = render()
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestCapture() }

    private val capture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK && r.data != null) {
                startStream(r.resultCode, r.data!!)
            } else {
                Toast.makeText(this, "Захват экрана отклонён", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toggle = findViewById(R.id.toggle)
        copy = findViewById(R.id.copy)
        status = findViewById(R.id.status)
        url = findViewById(R.id.url)

        toggle.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            } else if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestCapture()
            }
        }
        copy.setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("url", StreamService.url))
            Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter(StreamService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, f)
        }
        render()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    private fun render() {
        status.text = StreamService.status
        url.text = StreamService.url
        toggle.text = if (StreamService.running) "Стоп" else "Старт"
        copy.visibility = if (StreamService.url.startsWith("rtsp")) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun requestCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        capture.launch(mpm.createScreenCaptureIntent())
    }

    private fun startStream(resultCode: Int, data: Intent) {
        val landscape = findViewById<MaterialSwitch>(R.id.landscape).isChecked
        val (_, short, fps, br) = when (findViewById<RadioGroup>(R.id.quality).checkedRadioButtonId) {
            R.id.q1080 -> Preset(1920, 1080, 30, 8_000_000)
            R.id.q1080_60 -> Preset(1920, 1080, 60, 12_000_000)
            else -> Preset(1280, 720, 30, 4_000_000)
        }
        // Подгоняем размер под реальное соотношение сторон экрана телефона
        val real = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(real)
        val ratio = maxOf(real.x, real.y).toFloat() / minOf(real.x, real.y).toFloat()
        val longSide = (Math.round(short * ratio / 16f) * 16).coerceAtLeast(short)
        val w = if (landscape) longSide else short
        val h = if (landscape) short else longSide

        val i = Intent(this, StreamService::class.java)
            .setAction(StreamService.ACTION_START)
            .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(StreamService.EXTRA_DATA, data)
            .putExtra(StreamService.EXTRA_W, w)
            .putExtra(StreamService.EXTRA_H, h)
            .putExtra(StreamService.EXTRA_FPS, fps)
            .putExtra(StreamService.EXTRA_BITRATE, br)
        startForegroundService(i)
    }

    private data class Preset(val long: Int, val short: Int, val fps: Int, val bitrate: Int)
}
