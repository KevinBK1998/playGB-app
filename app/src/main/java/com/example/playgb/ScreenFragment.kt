package com.example.playgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.*
import android.os.Bundle
import android.text.TextPaint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.example.playgb.databinding.FragmentScreenBinding
import java.io.*

private const val SCALE_FACTOR = 4

@ExperimentalUnsignedTypes
class ScreenFragment : Fragment() {
    private lateinit var binding: FragmentScreenBinding
    private lateinit var cpu: Cpu
    private var isRecording = false
    private var notStarted = true

    //Graphics
    private lateinit var gpu: Gpu
    private lateinit var myCanvas: Canvas
    private lateinit var myBitmap: Bitmap
    private val myTextPaint = TextPaint()
    private val myRect = Rect()
    private var startX = 0
    private var startY = 0
    private val blackCOLOR = Color.rgb(0, 0, 0)

    //Audio
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        )
        .build()
    private val apu = Apu(audioTrack)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myTextPaint.apply {
            color = Color.rgb(255, 255, 255)
            textSize = 70f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_screen,
            container,
            false
        )
        @Suppress("deprecation")
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("deprecation")
        activity?.window?.decorView?.systemUiVisibility = flags
        binding.screenImage.addOnLayoutChangeListener { view: View, l: Int, t: Int, r: Int, b: Int, _, _, _, _ ->
            if (!(l == 0 && t == 0 && r == 0 && b == 0 && notStarted)) {
                notStarted = false
                myBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                binding.screenImage.setImageBitmap(myBitmap)
                startX = (view.width - SCREEN_WIDTH * SCALE_FACTOR) / 2
                startY = (view.height - SCREEN_HEIGHT * SCALE_FACTOR) / 2
                myCanvas = Canvas(myBitmap)
                myCanvas.drawColor(blackCOLOR)
                myRect.set(
                    startX, startY,
                    startX + SCREEN_WIDTH * SCALE_FACTOR,
                    startY + SCREEN_HEIGHT * SCALE_FACTOR

                )
                val thread = Thread(Runnable { loadRom() })
                thread.start()
            }
        }
        binding.startText.setOnClickListener {
            isRecording = true
            val thread = Thread(Runnable { record() })
            thread.start()
        }
        binding.aText.setOnClickListener { isRecording = false }
        binding.bText.setOnClickListener {
            val thread = Thread(Runnable { play() })
            thread.start()
        }
        return binding.root
    }

    private fun loadRom() {
        gpu = Gpu(myCanvas, myRect, binding.screenImage)
        var inputFile = resources.openRawResource(R.raw.bios)
        var bis = BufferedInputStream(inputFile)
        val bios = ByteArray(bis.available())
        bis.read(bios)
        bis.close()
        inputFile = resources.openRawResource(R.raw.tetris)
        bis = BufferedInputStream(inputFile)
        val rom = ByteArray(bis.available())
        bis.read(rom)
        bis.close()
        cpu = Cpu(bios, rom, gpu, apu)
        audioTrack.play()
        val thread = Thread(Runnable { runTillCrash() })
        thread.start()
    }

//    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
//        super.onViewCreated(view, savedInstanceState)
//        audioTrack.write(music,0,musicLength)
//        audioTrack.play()
//    }

    override fun onResume() {
        super.onResume()
        @Suppress("deprecation")
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("deprecation")
        activity?.window?.decorView?.systemUiVisibility = flags
    }

    private fun play() {
        // Get the file we want to playback.
        val file = File(
            context?.filesDir?.absolutePath + "/reverseme.pcm"
        )
        // Get the length of the audio stored in the file (16 bit so 2 bytes per short)
        // and create a short array to store the recorded audio.
        val musicLength = (file.length() / 2).toInt()
        val music = ShortArray(musicLength)

        try {
            // Create a DataInputStream to read the audio data back from the saved file.
            val `is`: InputStream = FileInputStream(file)
            val bis = BufferedInputStream(`is`)
            val dis = DataInputStream(bis)

            // Read the file into the music array.
            var i = 0
            while (dis.available() > 0) {
                music[i] = dis.readShort()
                i++
            }
            // Close the input streams.
            dis.close()


            // Create a new AudioTrack object using the same parameters as the AudioRecord
            // object used to create the file.
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .build()
            // Start playback
            audioTrack.play()

            // Write the music buffer to the AudioTrack object
            audioTrack.write(music, 0, musicLength)
            audioTrack.stop()
            audioTrack.release()
        } catch (t: Throwable) {
            t.printStackTrace()
            Log.e("AudioTrack", "Playback Failed")
        }
    }

    private fun record() {
        val frequency = 44100
        val channelConfiguration = AudioFormat.CHANNEL_OUT_STEREO
        val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

        val file = File(
            context?.filesDir?.absolutePath + "/reverseme.pcm"
        )
        // Delete any previous recording.
        if (file.exists())
            file.delete()
        // Create the new file.
        try {
            file.createNewFile()
        } catch (e: IOException) {
            e.printStackTrace()
            throw IllegalStateException("Failed to create $file")
        }
        try {
            // Create a DataOuputStream to write the audio data into the saved file.
            val os = FileOutputStream(file)
            val bos = BufferedOutputStream(os)
            val dos = DataOutputStream(bos)
            // Create a new AudioRecord object to record the audio.
            val bufferSize =
                AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                frequency, channelConfiguration,
                audioEncoding, bufferSize
            )

            val buffer = ShortArray(bufferSize)
            Log.i("GB.apu", "Recording Started")
            audioRecord.startRecording()
            while (isRecording) {
                val bufferReadResult = audioRecord.read(buffer, 0, bufferSize)
                var i = 0
                repeat(bufferReadResult) {
                    dos.writeShort(buffer[i].toInt())
                    i++
                }
            }
            audioRecord.stop()
            Log.i("GB.apu", "Recording Ended")
            dos.close()

        } catch (t: Throwable) {
            t.printStackTrace()
            Log.e("AudioRecord", "Recording Failed")
        }

    }

    private fun runTillCrash() {
        while (cpu.hasNotCrashed())
            cpu.execute()
        //binding.screenImage.invalidate()
        var log = cpu.log()
        myTextPaint.textSize = 35f
        myCanvas.drawText(log, 100f, 100f, myTextPaint)
        log = gpu.log()
        myTextPaint.textSize = 50f
        myCanvas.drawText(log, 100f, 150f, myTextPaint)
        binding.screenImage.invalidate()
    }
}

