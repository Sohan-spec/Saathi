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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioHelper(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private var rawData = ByteArrayOutputStream()

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
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) rawData.write(buffer, 0, read)
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

    suspend fun playBase64Audio(base64Audio: String, onComplete: () -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val decoded = Base64.decode(base64Audio, Base64.DEFAULT)
                val tempFile = File(context.cacheDir, "tts_output.wav")
                tempFile.writeBytes(decoded)

                withContext(Dispatchers.Main) {
                    val player = MediaPlayer()
                    player.setDataSource(tempFile.absolutePath)
                    player.prepare()
                    player.setOnCompletionListener {
                        it.release()
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
