package com.eranaydogan.bluerov2controller

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.eranaydogan.bluerov2controller.databinding.FragmentFirstBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin


class FirstFragment : Fragment() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!


    // ============================================================
    // KINEMATIC REFERENCE VALUES
    // ============================================================

    private val START_X = -152.0f
    private val START_Y = -145.35f
    private val START_Z = 923.0f

    // Absolute vehicle position in the simulation world frame.
    private var wx = START_X
    private var wz = START_Z
    private var height = START_Y

    // Vehicle initially faces the -Z direction.
    private var yaw = 180.0f

    // Packet sequence counter and controller start time.
    private var seq = 0
    private val startTime = System.currentTimeMillis()

    // Approximate 60 Hz control period.
    private val DT = 0.016f

    // Increment applied during each control update.
    private val MOVE_SPEED = 0.3f * DT
    private val YAW_RATE = 10.0f * DT


    // ============================================================
    // NETWORK CONFIGURATION
    // ============================================================

    // Host computer running the Unity/co-simulation receiver.
    // This address corresponds to the development hotspot setup.
    private val WINDOWS_IP = "192.168.137.1"

    private val UNITY_POSE_PORT = 5007
    private val UNITY_EMERGENCY_PORT = 5012


    // ============================================================
    // EMERGENCY CONTROL
    // ============================================================

    private val EMERGENCY_HOLD_MS = 3000L

    private var emergencyActive = false
    private var emergencyLongPressJob: Job? = null
    private var emergencyTriggeredThisPress = false

    private val jobs = mutableMapOf<String, Job?>()


    // ============================================================
    // FRAGMENT LIFECYCLE
    // ============================================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(
            inflater,
            container,
            false
        )

        return binding.root
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        // Translational controls.
        setButtonTouchListener(binding.btn, "btn")     // W: Forward
        setButtonTouchListener(binding.btn4, "btn4")   // S: Backward
        setButtonTouchListener(binding.btn3, "btn3")   // A: Strafe left
        setButtonTouchListener(binding.btn2, "btn2")   // D: Strafe right

        // Rotational controls.
        setButtonTouchListener(binding.btn6, "btn6")   // Yaw right
        setButtonTouchListener(binding.btn7, "btn7")   // Yaw left

        setEmergencyButtonListener()
        updateEmergencyButtonUi()

        setupVLCPlayer()
    }


    // ============================================================
    // MANUAL CONTROL BUTTONS
    // ============================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setButtonTouchListener(
        button: View,
        buttonKey: String
    ) {
        button.setOnTouchListener { view, event ->

            val vibrator = getVibrator(view.context)

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    vibrateMs(
                        vibrator,
                        25
                    )

                    startContinuousCommand(
                        buttonKey
                    )

                    view.setBackgroundResource(
                        R.drawable.pressedarrow
                    )

                    val scaleDownX = ObjectAnimator.ofFloat(
                        view,
                        "scaleX",
                        0.8f
                    )

                    val scaleDownY = ObjectAnimator.ofFloat(
                        view,
                        "scaleY",
                        0.8f
                    )

                    scaleDownX.duration = 100
                    scaleDownY.duration = 100

                    AnimatorSet().apply {
                        playTogether(
                            scaleDownX,
                            scaleDownY
                        )
                        start()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    view.setBackgroundResource(
                        R.drawable.arrowone
                    )

                    val scaleUpX = ObjectAnimator.ofFloat(
                        view,
                        "scaleX",
                        1.0f
                    )

                    val scaleUpY = ObjectAnimator.ofFloat(
                        view,
                        "scaleY",
                        1.0f
                    )

                    scaleUpX.duration = 100
                    scaleUpY.duration = 100

                    AnimatorSet().apply {
                        playTogether(
                            scaleUpX,
                            scaleUpY
                        )
                        start()
                    }

                    jobs[buttonKey]?.cancel()
                    jobs.remove(buttonKey)

                    view.performClick()
                }
            }

            true
        }
    }


    // ============================================================
    // EMERGENCY BUTTON
    // ============================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun setEmergencyButtonListener() {

        binding.btnEmergency.setOnTouchListener { view, event ->

            val vibrator = getVibrator(
                view.context
            )

            when (event.action) {

                MotionEvent.ACTION_DOWN -> {

                    emergencyTriggeredThisPress = false
                    emergencyLongPressJob?.cancel()

                    // Short feedback to confirm the initial press.
                    vibrateMs(
                        vibrator,
                        30
                    )

                    emergencyLongPressJob = lifecycleScope.launch {

                        val feedbackIntervalMs = 700L
                        var elapsedMs = 0L

                        // Provide periodic haptic feedback while the
                        // emergency button is being held.
                        while (
                            elapsedMs + feedbackIntervalMs
                            < EMERGENCY_HOLD_MS
                        ) {
                            delay(
                                feedbackIntervalMs
                            )

                            elapsedMs += feedbackIntervalMs

                            if (!emergencyTriggeredThisPress) {
                                vibrateMs(
                                    vibrator,
                                    18
                                )
                            }
                        }

                        delay(
                            EMERGENCY_HOLD_MS - elapsedMs
                        )

                        emergencyTriggeredThisPress = true
                        emergencyActive = !emergencyActive

                        val message =
                            if (emergencyActive) {
                                "EMERGENCY"
                            } else {
                                "EMERGENCY_OFF"
                            }

                        // Stronger feedback when the emergency state changes.
                        vibrateMs(
                            vibrator,
                            250
                        )

                        updateEmergencyButtonUi()

                        lifecycleScope.launch(
                            Dispatchers.IO
                        ) {
                            sendUdpText(
                                WINDOWS_IP,
                                UNITY_EMERGENCY_PORT,
                                message
                            )
                        }

                        Log.i(
                            "EMERGENCY",
                            "Emergency state message sent: $message"
                        )
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {

                    if (!emergencyTriggeredThisPress) {
                        emergencyLongPressJob?.cancel()
                    }

                    emergencyLongPressJob = null
                    view.performClick()
                }
            }

            true
        }
    }


    private fun updateEmergencyButtonUi() {

        if (emergencyActive) {

            binding.btnEmergency.text = "SOS ON"

            binding.btnEmergency.setBackgroundColor(
                Color.rgb(
                    220,
                    0,
                    0
                )
            )

        } else {

            binding.btnEmergency.text = "SOS"

            binding.btnEmergency.setBackgroundColor(
                Color.rgb(
                    176,
                    0,
                    32
                )
            )
        }
    }


    // ============================================================
    // VIDEO STREAMING
    // ============================================================

    private fun setupVLCPlayer() {

        libVLC = LibVLC(
            requireContext(),
            arrayListOf(
                "--drop-late-frames",
                "--skip-frames",
                "--no-audio",
                "--network-caching=150",
                "--live-caching=150",
                "--clock-jitter=0",
                "--clock-synchro=0"
            )
        )

        mediaPlayer = MediaPlayer(
            libVLC
        )

        mediaPlayer.attachViews(
            binding.vlcVideo,
            null,
            false,
            false
        )

        // Receive the live UDP video stream.
        val media = Media(
            libVLC,
            Uri.parse("udp://@:1234")
        )

        media.setHWDecoderEnabled(
            true,
            false
        )

        mediaPlayer.media = media

        media.release()
        mediaPlayer.play()
    }


    // ============================================================
    // HAPTIC FEEDBACK
    // ============================================================

    private fun getVibrator(
        context: Context
    ): Vibrator {

        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {

            val vibratorManager =
                context.getSystemService(
                    VibratorManager::class.java
                )

            vibratorManager.defaultVibrator

        } else {

            getLegacyVibrator(
                context
            )
        }
    }


    @Suppress("DEPRECATION")
    private fun getLegacyVibrator(
        context: Context
    ): Vibrator {

        return context.getSystemService(
            Context.VIBRATOR_SERVICE
        ) as Vibrator
    }


    private fun vibrateMs(
        vibrator: Vibrator,
        durationMs: Long
    ) {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {

            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )

        } else {

            vibrateLegacy(
                vibrator,
                durationMs
            )
        }
    }


    @Suppress("DEPRECATION")
    private fun vibrateLegacy(
        vibrator: Vibrator,
        durationMs: Long
    ) {

        vibrator.vibrate(
            durationMs
        )
    }


    // ============================================================
    // CONTINUOUS CONTROL LOOP
    // ============================================================

    private fun startContinuousCommand(
        buttonName: String
    ) {

        jobs[buttonName]?.cancel()

        jobs[buttonName] = lifecycleScope.launch(
            Dispatchers.IO
        ) {

            while (true) {

                // Approximately 60 Hz. A shorter interval can cause
                // unnecessary UDP packet accumulation.
                delay(16)

                sendControlCommand(
                    buttonName
                )

                yield()
            }
        }
    }


    // ============================================================
    // VEHICLE KINEMATICS
    // ============================================================

    private suspend fun sendControlCommand(
        buttonName: String
    ) {

        val yawRad =
            Math.toRadians(
                yaw.toDouble()
            ).toFloat()

        // Convert body-relative motion commands to world-frame motion.
        when (buttonName) {

            "btn" -> {
                // W: Forward
                wx += MOVE_SPEED * sin(yawRad)
                wz += MOVE_SPEED * cos(yawRad)
            }

            "btn4" -> {
                // S: Backward
                wx -= MOVE_SPEED * sin(yawRad)
                wz -= MOVE_SPEED * cos(yawRad)
            }

            "btn2" -> {
                // D: Strafe right
                wx += MOVE_SPEED * cos(yawRad)
                wz -= MOVE_SPEED * sin(yawRad)
            }

            "btn3" -> {
                // A: Strafe left
                wx -= MOVE_SPEED * cos(yawRad)
                wz += MOVE_SPEED * sin(yawRad)
            }

            "btn6" -> {
                // Yaw right
                yaw += YAW_RATE
            }

            "btn7" -> {
                // Yaw left
                yaw -= YAW_RATE
            }
        }

        // Wrap heading to [-180, 180) degrees.
        yaw =
            ((yaw + 180.0f) % 360.0f + 360.0f) %
                    360.0f - 180.0f


        // --------------------------------------------------------
        // WORLD-TO-UNITY COORDINATE TRANSFORMATION
        // --------------------------------------------------------

        val unityX =
            wx - START_X

        val unityZ =
            -(wz - START_Z)

        val unityY =
            height - START_Y


        // --------------------------------------------------------
        // PACKET METADATA
        // --------------------------------------------------------

        val elapsedTime =
            (
                    System.currentTimeMillis()
                            - startTime
                    ) / 1000.0f

        seq += 1

        Log.d(
            "UDP_POSE",
            "Unity X=$unityX, Z=$unityZ, yaw=$yaw"
        )


        // --------------------------------------------------------
        // 9-FLOAT POSE PACKET
        // --------------------------------------------------------

        val packetValues = floatArrayOf(
            unityX,
            unityZ,
            unityY,

            0.0f,
            0.0f,
            yaw,

            elapsedTime,
            seq.toFloat(),
            DT
        )

        sendUdpData(
            WINDOWS_IP,
            UNITY_POSE_PORT,
            *packetValues
        )
    }


    // ============================================================
    // UDP TRANSPORT
    // ============================================================

    private fun sendUdpData(
        ip: String,
        port: Int,
        vararg values: Float
    ) {

        require(values.size == 9) {
            "Pose packet requires exactly 9 floats; received ${values.size}."
        }

        // Match the Python/Unity <9f little-endian packet representation.
        val buffer = ByteBuffer.allocate(
            9 * Float.SIZE_BYTES
        )

        buffer.order(
            ByteOrder.LITTLE_ENDIAN
        )

        values.forEach {
            buffer.putFloat(it)
        }

        try {

            val address =
                InetAddress.getByName(ip)

            DatagramSocket().use { socket ->

                val packet = DatagramPacket(
                    buffer.array(),
                    buffer.capacity(),
                    address,
                    port
                )

                socket.send(
                    packet
                )
            }

        } catch (exception: Exception) {

            Log.e(
                "UDP_POSE",
                "Failed to send pose packet.",
                exception
            )
        }
    }


    private fun sendUdpText(
        ip: String,
        port: Int,
        message: String
    ) {

        try {

            val data =
                message.toByteArray(
                    Charsets.UTF_8
                )

            val address =
                InetAddress.getByName(ip)

            DatagramSocket().use { socket ->

                val packet = DatagramPacket(
                    data,
                    data.size,
                    address,
                    port
                )

                socket.send(
                    packet
                )
            }

        } catch (exception: Exception) {

            Log.e(
                "UDP_EMERGENCY",
                "Failed to send emergency message.",
                exception
            )
        }
    }


    // ============================================================
    // CLEANUP
    // ============================================================

    override fun onDestroyView() {

        jobs.values.forEach {
            it?.cancel()
        }

        jobs.clear()

        emergencyLongPressJob?.cancel()
        emergencyLongPressJob = null

        mediaPlayer.release()
        libVLC.release()

        _binding = null

        super.onDestroyView()
    }
}