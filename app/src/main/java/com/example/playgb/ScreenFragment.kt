package com.example.playgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.*
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.example.playgb.databinding.FragmentScreenBinding
import java.io.*


private const val SCREEN_WIDTH = 160
private const val SCREEN_HEIGHT = 144

class ScreenFragment : Fragment() {
    //Graphics
    private lateinit var myCanvas: Canvas
    private lateinit var myBitmap: Bitmap
    private val myPaint = Paint().apply { style = Paint.Style.STROKE }
    private val myTextPaint = Paint()
    private val myRect = Rect()
    private var blackColour = 0
    private var whiteColour = 0

    //Audio
    //private val musicLength = 2000000
    //private var music=ShortArray(100)
    //private lateinit var audioTrack: AudioTrack
    private lateinit var binding: FragmentScreenBinding
    private lateinit var cpu: Cpu
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blackColour = ResourcesCompat.getColor(resources, R.color.colorBlack, null)
        whiteColour = ResourcesCompat.getColor(resources, R.color.colorWhite, null)
        myPaint.color = whiteColour
        myTextPaint.color = whiteColour
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
        cpu = Cpu(bios, rom)
//        inputFile = resources.openRawResource(R.raw.violin)
//        val actLentgh=(inputFile.available() / 2)
//        Log.i("GB.apu","${actLentgh}")
//        //val musicLength = (inputFile.available() / 2)
//        //val music = ShortArray(musicLength)
//        bis = BufferedInputStream(inputFile)
//        val dis = DataInputStream(bis)
//        // Read the file into the music array.
//        var i = 0
//        while ((dis.available() > 0 )and (actLentgh-dis.available()>musicLength) ){
//            Log.i("GB.apu","i=${i}")
//            music[i] = dis.readShort()
//            i++
//        }
//        // Close the input streams.
//        dis.close()
//        audioTrack = AudioTrack(
//            AudioManager.STREAM_MUSIC,
//            44100,
//            AudioFormat.CHANNEL_OUT_STEREO,
//            AudioFormat.ENCODING_PCM_16BIT,
//            musicLength,
//            AudioTrack.MODE_STREAM
//        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate<FragmentScreenBinding>(
            inflater,
            R.layout.fragment_screen,
            container,
            false
        )
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        activity?.window?.decorView?.systemUiVisibility = flags
        binding.screenImage.setOnClickListener { continueGame(it) }
        binding.startText.setOnClickListener { recordStart() }
        binding.aText.setOnClickListener { isRecording = false }
        binding.bText.setOnClickListener { playStart() }
        return binding.root
    }

    private fun playStart() {
        val thread = Thread(Runnable { play() })
        thread.start()
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
                music[musicLength - 1 - i] = dis.readShort()
                i++
            }
            // Close the input streams.
            dis.close()


            // Create a new AudioTrack object using the same parameters as the AudioRecord
            // object used to create the file.
            val audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                11025,
                AudioFormat.CHANNEL_CONFIGURATION_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                musicLength,
                AudioTrack.MODE_STREAM
            )
            // Start playback
            audioTrack.play()

            // Write the music buffer to the AudioTrack object
            audioTrack.write(music, 0, musicLength)
        } catch (t: Throwable) {
            Log.e("AudioTrack", "Playback Failed")
        }
    }

    private fun recordStart() {
        isRecording = true
        val thread = Thread(Runnable { record() })
        thread.start()
//        try {
//            thread.join()
//        } catch (e: InterruptedException) {
//        }
    }

    private fun record() {
        val frequency = 11025
        val channelConfiguration = AudioFormat.CHANNEL_CONFIGURATION_MONO
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
            throw IllegalStateException("Failed to create " + file.toString())
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

            var buffer = ShortArray(bufferSize)
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //audioTrack.write(music,0,musicLength)
        repeat(28800) { cpu.execute() }
        //audioTrack.play()
    }

    private fun continueGame(view: View) {
        myBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        binding.screenImage.setImageBitmap(myBitmap)
        myCanvas = Canvas(myBitmap)
        myCanvas.drawColor(blackColour)
        myRect.set(
            (view.width - (SCREEN_WIDTH + 2)) / 2,
            (view.height - (SCREEN_HEIGHT + 2)) / 2,
            (view.width + (SCREEN_WIDTH + 2)) / 2,
            (view.height + (SCREEN_HEIGHT + 2)) / 2
        )
        myCanvas.drawRect(myRect, myPaint)
        var msg = ""
        repeat(1) {
            msg = cpu.execute()
        }
        val log = cpu.log()
        myTextPaint.textSize = 70f
        myCanvas.drawText(msg, 100f, 100f, myTextPaint)
        myTextPaint.textSize = 35f
        myCanvas.drawText(log, 100f, 150f, myTextPaint)
        view.invalidate()
    }
}