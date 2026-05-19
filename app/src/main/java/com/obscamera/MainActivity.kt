package com.obscamera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.obscamera.databinding.ActivityMainBinding
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2

class MainActivity : AppCompatActivity(), ConnectCheckerRtmp {

    private lateinit var binding: ActivityMainBinding
    private var rtmpCamera: RtmpCamera2? = null

    private var isStreaming = false
    private var isMuted = false
    private var isFrontCamera = false

    private val handler = Handler(Looper.getMainLooper())
    private val bitrateUpdater = object : Runnable {
        override fun run() {
            if (isStreaming) {
                val bitrate = rtmpCamera?.getBitrate() ?: 0
                binding.bitrateDisplay.text = "${bitrate / 1000} kbps"
                handler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        private const val TAG = "OBSCamera"
        private const val PERMISSION_REQUEST_CODE = 101
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (allPermissionsGranted()) {
            setupCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        setupUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (rtmpCamera?.isStreaming == true) rtmpCamera?.stopStream()
        if (rtmpCamera?.isOnPreview == true) rtmpCamera?.stopPreview()
        handler.removeCallbacks(bitrateUpdater)
    }

    private fun setupCamera() {
        rtmpCamera = RtmpCamera2(binding.cameraPreview, this)
        try {
            val facing = if (isFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
            rtmpCamera!!.startPreview(facing)
        } catch (e: Exception) {
            Log.e(TAG, "Preview error: ${e.message}")
            showToast("Camera preview failed")
        }
    }

    private fun setupUI() {
        binding.btnStartStop.setOnClickListener {
            if (isStreaming) stopStreaming() else startStreaming()
        }

        binding.btnFlipCamera.setOnClickListener {
            isFrontCamera = !isFrontCamera
            rtmpCamera?.switchCamera()
        }

        binding.btnMute.setOnClickListener {
            isMuted = !isMuted
            if (isMuted) {
                rtmpCamera?.disableAudio()
                binding.btnMute.alpha = 0.4f
                showToast("Mic muted")
            } else {
                rtmpCamera?.enableAudio()
                binding.btnMute.alpha = 1.0f
                showToast("Mic unmuted")
            }
        }
    }

    private fun startStreaming() {
        val ip  = binding.etIpAddress.text.toString().trim()
        val port = binding.etPort.text.toString().trim()
        val key  = binding.etStreamKey.text.toString().trim()

        if (ip.isEmpty()) { showToast("Enter PC IP address"); return }
        if (port.isEmpty()) { showToast("Enter port"); return }
        if (key.isEmpty()) { showToast("Enter stream key"); return }

        val rtmpUrl = "rtmp://$ip:$port/$key"
        val (width, height, bitrate) = getQualitySettings()

        Log.d(TAG, "Connecting: $rtmpUrl  ${width}x${height} @ ${bitrate}kbps")

        try {
            val ok = rtmpCamera!!.prepareVideo(
                width, height, 30, bitrate * 1000, 2,
                CameraHelper.getCameraOrientation(this)
            ) && rtmpCamera!!.prepareAudio(128 * 1000, 44100, true)

            if (ok) {
                rtmpCamera!!.startStream(rtmpUrl)
                setStatus("Connecting…", StatusState.CONNECTING)
            } else {
                showToast("Failed to prepare encoder")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream error: ${e.message}")
            showToast("Error: ${e.message}")
        }
    }

    private fun stopStreaming() {
        if (rtmpCamera?.isStreaming == true) rtmpCamera!!.stopStream()
        isStreaming = false
        handler.removeCallbacks(bitrateUpdater)
        runOnUiThread {
            setStatus("Idle", StatusState.IDLE)
            binding.btnStartStop.text = "▶ GO LIVE"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
            binding.bitrateDisplay.text = ""
        }
    }

    private fun getQualitySettings(): Triple<Int, Int, Int> = when (
        binding.qualityGroup.checkedRadioButtonId
    ) {
        R.id.rb480  -> Triple(854,  480,  1500)
        R.id.rb1080 -> Triple(1920, 1080, 5000)
        else        -> Triple(1280, 720,  2500)
    }

    // ── ConnectCheckerRtmp ────────────────────────────────────────────────────

    override fun onConnectionSuccessRtmp() {
        isStreaming = true
        runOnUiThread {
            setStatus("🔴 LIVE", StatusState.LIVE)
            binding.btnStartStop.text = "⏹ STOP"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_red_dark)
            handler.post(bitrateUpdater)
        }
    }

    override fun onConnectionFailedRtmp(reason: String) {
        isStreaming = false
        runOnUiThread {
            setStatus("Failed", StatusState.ERROR)
            binding.btnStartStop.text = "▶ GO LIVE"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
            showToast("Failed: $reason")
        }
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        runOnUiThread { binding.bitrateDisplay.text = "${bitrate / 1000} kbps" }
    }

    override fun onDisconnectRtmp() {
        isStreaming = false
        runOnUiThread {
            setStatus("Disconnected", StatusState.IDLE)
            binding.btnStartStop.text = "▶ GO LIVE"
            binding.btnStartStop.backgroundTintList =
                ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
            binding.bitrateDisplay.text = ""
            showToast("Disconnected")
        }
    }

    override fun onAuthErrorRtmp() {
        runOnUiThread { showToast("Auth error") }
    }

    override fun onAuthSuccessRtmp() {
        runOnUiThread { showToast("Auth success") }
    }

    // ── Status ────────────────────────────────────────────────────────────────

    enum class StatusState { IDLE, CONNECTING, LIVE, ERROR }

    private fun setStatus(msg: String, state: StatusState) {
        binding.statusText.text = msg
        binding.statusDot.setBackgroundResource(
            when (state) {
                StatusState.IDLE       -> R.drawable.dot_idle
                StatusState.CONNECTING -> R.drawable.dot_connecting
                StatusState.LIVE       -> R.drawable.dot_live
                StatusState.ERROR      -> R.drawable.dot_error
            }
        )
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (allPermissionsGranted()) setupCamera()
            else { showToast("Camera & mic permissions required"); finish() }
        }
    }

    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
