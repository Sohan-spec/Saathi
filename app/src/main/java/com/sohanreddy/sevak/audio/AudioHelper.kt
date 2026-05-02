package com.sohanreddy.sevak.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioHelper(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var rawData = ByteArrayOutputStream()
    private var mediaPlayer: MediaPlayer? = null

    /** Callback invoked on the recording thread with normalised RMS amplitude [0..1] */
    var onAmplitudeUpdate: ((Float) -> Unit)? = null

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun startRecording() {
        if (!hasPermission()) return
        rawData = ByteArrayOutputStream()
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )
        audioRecord?.startRecording()
        isRecording = true
        recordingThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            val byteBuffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    // Compute RMS amplitude
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toDouble()
                        sum += sample * sample
                    }
                    val rms = sqrt(sum / read).toFloat()
                    val normalised = (rms / Short.MAX_VALUE).coerceIn(0f, 1f)
                    onAmplitudeUpdate?.invoke(normalised)

                    // Write raw bytes for WAV
                    val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until read) bb.putShort(buffer[i])
                    rawData.write(bb.array())
                }
            }
        }
        recordingThread?.start()
    }

    fun stopRecording(): File? {
        isRecording = false
        recordingThread?.join(2000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val pcmData = rawData.toByteArray()
        if (pcmData.isEmpty()) return null

        val wavFile = File(context.cacheDir, "recording.wav")
        writeWav(wavFile, pcmData)
        return wavFile
    }

    private fun writeWav(file: File, pcmData: ByteArray) {
        val totalDataLen = pcmData.size + 36
        val channels = 1
        val byteRate = sampleRate * channels * 2

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(totalDataLen)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort())
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(pcmData.size)
            fos.write(header.array())
            fos.write(pcmData)
        }
    }

    /** Stop any currently playing TTS audio. */
    fun stopPlayback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
            // ignore
        }
        mediaPlayer = null
    }

    suspend fun playBase64Audio(base64Audio: String, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val decoded = Base64.decode(base64Audio, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "tts_output.wav")
                tempFile.writeBytes(decoded)

                withContext(Dispatchers.Main) {
                    stopPlayback()
                    val player = MediaPlayer()
                    mediaPlayer = player
                    player.setDataSource(tempFile.absolutePath)
                    player.prepare()
                    player.setOnCompletionListener {
                        it.release()
                        mediaPlayer = null
                        tempFile.delete()
                        onComplete()
                    }
                    player.start()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }
}
