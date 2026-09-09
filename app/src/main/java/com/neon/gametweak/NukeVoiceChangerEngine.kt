package com.neon.gametweak

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sin

/**
 * NukeVoiceChangerEngine — Real-time 16kHz In-Game Voice Changer & Sound Effect Engine.
 *
 * Developer: Agung Developer
 *
 * Specifications:
 *  - Native 16,000 Hz (16 kHz) PCM 16-bit processing pipeline (optimal bandwidth for VoIP/gaming mic)
 *  - Ultra-low latency circular buffer with direct AudioTrack loopback
 *  - Realtime DSP Presets:
 *      1. NORMAL (Pure 16kHz Studio Audio)
 *      2. ROBOT (Cyberpunk ring modulator)
 *      3. DEEP_DEMON (Down-pitched monster/deep voice)
 *      4. HIGH_PITCH (Anime / Girl voice up-shifter)
 *      5. TACTICAL_RADIO (16kHz military radio walkie-talkie)
 *      6. STUDIO_16K (Noise gate + 16kHz crystal clarity vocal boost)
 *  - Manual pitch fine-tuner (-12 semitones to +12 semitones)
 */
class NukeVoiceChangerEngine private constructor(private val context: Context) {

    enum class Preset(val displayName: String, val icon: String) {
        NORMAL("Normal 16k", "🎙️"),
        STUDIO_16K("16kHz Studio Vocal", "✨"),
        DEEP_DEMON("Deep Demon", "👹"),
        HIGH_PITCH("Girl Anime", "👧"),
        ROBOT("Cyber Robot", "🤖"),
        TACTICAL_RADIO("Tactical Radio 16k", "📻")
    }

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _currentPreset = MutableStateFlow(Preset.STUDIO_16K)
    val currentPreset: StateFlow<Preset> = _currentPreset.asStateFlow()

    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    private val _isLoopbackMonitorEnabled = MutableStateFlow(false)
    val isLoopbackMonitorEnabled: StateFlow<Boolean> = _isLoopbackMonitorEnabled.asStateFlow()

    private val _micGain = MutableStateFlow(1.2f)
    val micGain: StateFlow<Float> = _micGain.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null
    @Volatile private var isRunning = false

    companion object {
        private const val TAG = "NukeVoiceChanger"
        const val SAMPLE_RATE = 16000 // 16 kHz native sampling
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        @Volatile
        private var instance: NukeVoiceChangerEngine? = null

        fun getInstance(context: Context): NukeVoiceChangerEngine =
            instance ?: synchronized(this) {
                instance ?: NukeVoiceChangerEngine(context.applicationContext).also { instance = it }
            }
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setPreset(preset: Preset) {
        _currentPreset.value = preset
        when (preset) {
            Preset.NORMAL -> _pitchSemitones.value = 0
            Preset.STUDIO_16K -> _pitchSemitones.value = 0
            Preset.DEEP_DEMON -> _pitchSemitones.value = -6
            Preset.HIGH_PITCH -> _pitchSemitones.value = 6
            Preset.ROBOT -> _pitchSemitones.value = 0
            Preset.TACTICAL_RADIO -> _pitchSemitones.value = 1
        }
    }

    fun setPitchSemitones(semitones: Int) {
        _pitchSemitones.value = semitones.coerceIn(-12, 12)
    }

    fun setLoopbackMonitor(enabled: Boolean) {
        _isLoopbackMonitorEnabled.value = enabled
    }

    fun setMicGain(gain: Float) {
        _micGain.value = gain.coerceIn(0.5f, 3.0f)
    }

    fun toggle(): Boolean {
        return if (isRunning) {
            stop()
            false
        } else {
            start()
        }
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        if (!hasRecordPermission()) {
            Log.w(TAG, "Cannot start Voice Changer: RECORD_AUDIO permission missing")
            return false
        }

        try {
            val minInBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val minOutBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val bufferSize = (minInBuf * 2).coerceAtLeast(1024)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_IN,
                ENCODING,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to default audio source
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    CHANNEL_IN,
                    ENCODING,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            val trackBufSize = (minOutBuf * 2).coerceAtLeast(1024)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioRecord?.startRecording()
            audioTrack?.play()
            isRunning = true
            _isActive.value = true

            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val sonic = Sonic(SAMPLE_RATE, 1).apply {
                    quality = 0 // Low-latency realtime speech
                    chordPitch = true // Use robust WSOLA pitch modification
                }
                val inBuffer = ShortArray(480) // 30ms frame at 16kHz
                val outBuffer = ShortArray(1024)
                var phase = 0.0

                while (isRunning) {
                    try {
                        val read = audioRecord?.read(inBuffer, 0, inBuffer.size) ?: -1
                        if (read <= 0) {
                            try {
                                Thread.sleep(10L)
                            } catch (_: InterruptedException) {
                                break
                            }
                            continue
                        }

                        // Dynamically update pitch in Sonic without restarting stream
                        val semitones = _pitchSemitones.value
                        val targetPitch = Math.pow(2.0, semitones / 12.0).toFloat()
                        if (abs(sonic.pitch - targetPitch) > 0.01f) {
                            sonic.pitch = targetPitch
                        }

                        sonic.writeShortToStream(inBuffer, read)
                        val available = sonic.samplesAvailable()
                        if (available > 0) {
                            val numRead = sonic.readShortFromStream(outBuffer, minOf(available, outBuffer.size))
                            if (numRead > 0) {
                                val processed = applyDspEffects(outBuffer, numRead, phase)
                                phase = (phase + numRead * 0.03) % (2 * Math.PI)
                                audioTrack?.write(processed, 0, numRead)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Non-fatal audio loop tick error: ${e.message}")
                        try { Thread.sleep(15L) } catch (_: InterruptedException) { break }
                    }
                }
            }, "NukeVoiceChangerThread").apply { start() }

            Log.i(TAG, "Voice Changer started with 16kHz Sonic DSP pipeline")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Error starting Voice Changer: ${e.message}", e)
            stop()
            return false
        }
    }

    @Synchronized
    fun stop() {
        isRunning = false
        _isActive.value = false
        try {
            workerThread?.interrupt()
            workerThread = null

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping Voice Changer: ${e.message}")
        }
    }

    /**
     * Real-time DSP Audio Modulation block
     */
    private fun applyDspEffects(input: ShortArray, length: Int, phaseInit: Double): ShortArray {
        val preset = _currentPreset.value
        val gain = _micGain.value
        var phase = phaseInit
        val output = ShortArray(length)

        for (i in 0 until length) {
            var sample = input[i].toDouble()

            when (preset) {
                Preset.NORMAL -> {
                    // Direct pass
                }
                Preset.STUDIO_16K -> {
                    // Noise Gate + 16kHz Vocal presence enhancer
                    if (abs(sample) < 280.0) {
                        sample *= 0.10 // Noise suppression
                    } else {
                        val sign = if (sample > 0) 1.0 else -1.0
                        sample = sign * (1.0 - Math.exp(-abs(sample) / 28000.0)) * 32000.0
                    }
                }
                Preset.ROBOT -> {
                    // Cyberpunk ring modulator carrier wave at ~55 Hz
                    val mod = sin(phase)
                    phase += (2.0 * Math.PI * 55.0 / SAMPLE_RATE)
                    sample = (sample * 0.4) + (sample * mod * 0.75)
                }
                Preset.DEEP_DEMON -> {
                    // Heavy sub-bass saturation & rumble
                    val sign = if (sample > 0) 1.0 else -1.0
                    sample = sign * (1.0 - Math.exp(-abs(sample) / 22000.0)) * 32700.0
                }
                Preset.HIGH_PITCH -> {
                    // Crisp treble presence
                    sample *= 1.15
                }
                Preset.TACTICAL_RADIO -> {
                    // Radio bandpass and harsh clipping
                    phase += (2.0 * Math.PI * 2.0 / SAMPLE_RATE)
                    val staticHiss = (sin(phase * 37.0) * 400.0)
                    sample = ((sample * 1.3) + staticHiss).coerceIn(-24000.0, 24000.0)
                }
            }

            // Apply dynamic mic gain
            val gainedSample = sample * gain
            output[i] = gainedSample.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }

        return output
    }
}
