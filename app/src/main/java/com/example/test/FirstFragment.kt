package com.example.test

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.test.databinding.FragmentFirstBinding
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
import android.graphics.Color


class FirstFragment : Fragment() {

    private lateinit var libVLC: LibVLC
    private lateinit var mediaPlayer: MediaPlayer
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    // ==========================================
    // PYTHON SCRIPT'TEN ALINAN KINEMATIK DEĞERLER
    // ==========================================
    private val START_X = -152.0f
    private val START_Y = -145.35f
    private val START_Z = 923.0f

    // Aracın dünya üzerindeki mutlak konumu
    private var wx = START_X
    private var wz = START_Z
    private var height = START_Y
    private var yaw = 180.0f // Başlangıçta -Z yönüne bakıyor

    // Paket sekans numarası ve başlangıç zamanı
    private var seq = 0
    private val startTime = System.currentTimeMillis()

    // 60 Hz için delta time (yaklaşık 16ms = 0.016 saniye)
    private val DT = 0.016f
    private val MOVE_SPEED = 0.3f * DT  // dt ile çarpılmış birim adım hızı
    private val YAW_RATE = 10.0f * DT   // dt ile çarpılmış açısal hız
    // ==========================================
    private val WINDOWS_IP = "192.168.137.1"   // Hotspot modu
    private val UNITY_POSE_PORT = 5007
    private val UNITY_EMERGENCY_PORT = 5012

    private val EMERGENCY_HOLD_MS = 3000L
    private var emergencyActive = false
    private var emergencyLongPressJob: Job? = null
    private var emergencyTriggeredThisPress = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    private val jobs = mutableMapOf<String, Job?>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setButtonTouchListener(binding.btn, "btn")   // W - İleri
        setButtonTouchListener(binding.btn4, "btn4") // S - Geri
        setButtonTouchListener(binding.btn3, "btn3") // A - Sola Kayma
        setButtonTouchListener(binding.btn2, "btn2") // D - Sağa Kayma

        // Pitch tuşlarını kullanmıyorsan bu butonlara yaw bağlayabilir veya iptal edebilirsin
        // Şimdilik Sol/Sağ dönüş için btn6 ve btn7'yi kullanıyoruz

        setButtonTouchListener(binding.btn6, "btn6") // Yaw Sağ
        setButtonTouchListener(binding.btn7, "btn7") // Yaw Sol
        setEmergencyButtonListener()
        updateEmergencyButtonUi()


        setupVLCPlayer()
    }

    private fun setButtonTouchListener(button: View, buttonKey: String) {
        button.setOnTouchListener { v, event ->
            val vibrator = v.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(25)
                    }

                    senddatawithloop(buttonKey)

                    v.setBackgroundResource(R.drawable.pressedarrow)
                    val scaleDownX = ObjectAnimator.ofFloat(v, "scaleX", 0.8f)
                    val scaleDownY = ObjectAnimator.ofFloat(v, "scaleY", 0.8f)
                    scaleDownX.duration = 100
                    scaleDownY.duration = 100

                    AnimatorSet().apply {
                        playTogether(scaleDownX, scaleDownY)
                        start()
                    }
                }

                MotionEvent.ACTION_UP -> {
                    v.setBackgroundResource(R.drawable.arrowone)

                    val scaleUpX = ObjectAnimator.ofFloat(v, "scaleX", 1f)
                    val scaleUpY = ObjectAnimator.ofFloat(v, "scaleY", 1f)
                    scaleUpX.duration = 100
                    scaleUpY.duration = 100

                    AnimatorSet().apply {
                        playTogether(scaleUpX, scaleUpY)
                        start()
                    }

                    jobs[buttonKey]?.cancel()
                    jobs.remove(buttonKey)
                    v.performClick()
                }
            }
            true
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setEmergencyButtonListener() {
        binding.btnEmergency.setOnTouchListener { v, event ->
            val vibrator = v.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    emergencyTriggeredThisPress = false
                    emergencyLongPressJob?.cancel()

                    // Basıldığını hissettirmek için ilk küçük titreşim
                    vibrateMs(vibrator, 30)

                    emergencyLongPressJob = lifecycleScope.launch {
                        val feedbackIntervalMs = 700L
                        var elapsedMs = 0L

                        // 3 saniyelik bekleme boyunca küçük aralıklı titreşimler
                        while (elapsedMs + feedbackIntervalMs < EMERGENCY_HOLD_MS) {
                            delay(feedbackIntervalMs)
                            elapsedMs += feedbackIntervalMs

                            if (!emergencyTriggeredThisPress) {
                                vibrateMs(vibrator, 18)
                            }
                        }

                        delay(EMERGENCY_HOLD_MS - elapsedMs)

                        emergencyTriggeredThisPress = true
                        emergencyActive = !emergencyActive

                        val msg = if (emergencyActive) "EMERGENCY" else "EMERGENCY_OFF"

                        // SOS ON/OFF gerçekleşince daha belirgin titreşim
                        vibrateMs(vibrator, 250)

                        updateEmergencyButtonUi()

                        lifecycleScope.launch(Dispatchers.IO) {
                            sendUdpText(WINDOWS_IP, UNITY_EMERGENCY_PORT, msg)
                        }

                        Log.i("EMERGENCY", "Sent: $msg")
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!emergencyTriggeredThisPress) {
                        emergencyLongPressJob?.cancel()
                    }

                    emergencyLongPressJob = null
                    v.performClick()
                }
            }

            true
        }
    }
    private fun updateEmergencyButtonUi() {
        if (emergencyActive) {
            binding.btnEmergency.text = "SOS ON"
            binding.btnEmergency.setBackgroundColor(Color.rgb(220, 0, 0))
        } else {
            binding.btnEmergency.text = "SOS"
            binding.btnEmergency.setBackgroundColor(Color.rgb(176, 0, 32))
        }
    }

    private fun setupVLCPlayer() {
        libVLC = LibVLC(requireContext(), arrayListOf(
            "--drop-late-frames",
            "--skip-frames",
            "--no-audio",
            "--network-caching=150",
            "--live-caching=150",
            "--clock-jitter=0",
            "--clock-synchro=0"
        ))

        mediaPlayer = MediaPlayer(libVLC)
        mediaPlayer.attachViews(binding.vlcVideo, null, false, false)

        val media = Media(libVLC, Uri.parse("udp://@:1234"))

        media.setHWDecoderEnabled(true, false)

        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }
    private fun vibrateMs(vibrator: Vibrator, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            vibrator.vibrate(durationMs)
        }
    }

    fun senddatawithloop(buttonname: String) {
        jobs[buttonname]?.cancel()
        jobs[buttonname] = lifecycleScope.launch(Dispatchers.IO) {
            while(true){
                delay(16) // ~60Hz hızına sabitledik (10ms çok hızlı paket biriktirebilir)
                senddata(buttonname)
                yield()
            }
        }
    }

    suspend fun senddata(buttonname: String){

        // Açıyı radyana çevir
        val yawRad = Math.toRadians(yaw.toDouble()).toFloat()

        // 1. LOKAL HAREKET HESABI (Yönlere Göre Trigonometrik İzdüşüm)
        when(buttonname) {
            "btn" -> { // W: İleri
                wx += MOVE_SPEED * sin(yawRad)
                wz += MOVE_SPEED * cos(yawRad)
            }
            "btn4" -> { // S: Geri
                wx -= MOVE_SPEED * sin(yawRad)
                wz -= MOVE_SPEED * cos(yawRad)
            }
            "btn2" -> { // D: Sağa Yengeç Kayması (Strafing)
                wx += MOVE_SPEED * cos(yawRad)
                wz -= MOVE_SPEED * sin(yawRad)
            }
            "btn3" -> { // A: Sola Yengeç Kayması
                wx -= MOVE_SPEED * cos(yawRad)
                wz += MOVE_SPEED * sin(yawRad)
            }
            "btn6" -> { // Yaw Sağ
                yaw += YAW_RATE
            }
            "btn7" -> { // Yaw Sol
                yaw -= YAW_RATE
            }
            // btn5 ve btn8 kullanılmadığı için boş bırakıldı (Pitch iptal)
        }

        // Açıyı -180 ile +180 derece arasında sıkıştır (Python wrap_deg mantığı)
        yaw = ((yaw + 180.0f) % 360.0f + 360.0f) % 360.0f - 180.0f

        // 2. UNITY EKSEN DÖNÜŞÜMLERİ VE OFFSET
        val unityX = wx - START_X
        val unityZ = -(wz - START_Z) // İleri ekseni terslenir
        val unityY = height - START_Y

        // Zaman hesaplaması
        val t = (System.currentTimeMillis() - startTime) / 1000f
        seq += 1

        Log.i("UDP_SEND", "Unity X: $unityX, Z: $unityZ | Yaw: $yaw")

        // 3. PYTHON SCRIPT ILE AYNI 9-FLOAT PAKETININ OLUSTURULMASI
        val packetValues = floatArrayOf(
            unityX, unityZ, unityY, // Pozisyon
            0f, 0f, yaw,            // Pitch, Roll, Yaw (Pitch ve Roll 0 sabittir)
            t, seq.toFloat(), DT    // Zaman, Sıra, DeltaTime
        )

        // UDP Hedef IP adresini bilgisayarının IP'si ile değiştir
        sendUdpData(WINDOWS_IP, UNITY_POSE_PORT, *packetValues)
    }

    fun sendUdpData(ip: String, port: Int, vararg values: Float) {
        // Python'daki <9f yapısına uyması için sınırlandırıldı
        if (values.size != 9) {
            throw IllegalArgumentException("Python server 9 float bekliyor, ${values.size} girildi.")
        }

        val buffer = ByteBuffer.allocate(9 * 4) // 9 float * 4 byte
        buffer.order(ByteOrder.LITTLE_ENDIAN) // Unity ile veri uyuşmazlığını önlemek için
        values.forEach { buffer.putFloat(it) }

        try {
            val address = InetAddress.getByName(ip)
            val socket = DatagramSocket()
            val packet = DatagramPacket(buffer.array(), buffer.capacity(), address, port)

            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e("UDP_HATA", "Paket gönderilemedi: ${e.message}")
        }
    }
    fun sendUdpText(ip: String, port: Int, message: String) {
        try {
            val data = message.toByteArray(Charsets.UTF_8)
            val address = InetAddress.getByName(ip)
            val socket = DatagramSocket()
            val packet = DatagramPacket(data, data.size, address, port)

            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e("UDP_TEXT_HATA", "Text paket gönderilemedi: ${e.message}")
        }
    }

    override fun onDestroyView() {
        mediaPlayer.release()
        libVLC.release()
        super.onDestroyView()
        _binding = null
    }
}