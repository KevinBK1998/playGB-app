package com.example.playgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.TextPaint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.example.playgb.databinding.FragmentScreenBinding
import java.io.BufferedInputStream
import java.io.File

private const val SCALE_FACTOR = 6

@ExperimentalUnsignedTypes
class ScreenFragment : Fragment() {
    private lateinit var binding: FragmentScreenBinding
    private lateinit var cpu: Cpu

    //Graphics
    private lateinit var myCanvas: Canvas
    private lateinit var myBitmap: Bitmap
    private val myTextPaint = TextPaint()
    private val myRect = Rect()
    private var startX = 0
    private var startY = 0
    private val blackCOLOR = Color.rgb(0, 0, 0)
    private val joyPad = booleanArrayOf(false, false, false, false, false, false, false, false)

    //Audio
    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setAudioFormat(
            AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        )
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        myTextPaint.apply {
            color = Color.rgb(255, 255, 255)
            textSize = 70f
        }
    }

    @Suppress("deprecation", "ClickableViewAccessibility")
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
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        activity?.window?.decorView?.systemUiVisibility = flags
        binding.screenImage.addOnLayoutChangeListener { view: View, l: Int, t: Int, r: Int, b: Int, _, _, _, _ ->
            if (!(l == 0 && t == 0 && r == 0 && b == 0 && !::cpu.isInitialized)) {
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
                val thread = Thread { startCpu() }
                thread.start()
            }
        }
        binding.rightText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> joyPad[DIRECTION_RIGHT_BUTTON_A] = true
                MotionEvent.ACTION_MOVE -> joyPad[DIRECTION_RIGHT_BUTTON_A] = true
                MotionEvent.ACTION_UP -> joyPad[DIRECTION_RIGHT_BUTTON_A] = false
            }
            return@setOnTouchListener true
        }
        binding.leftText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> joyPad[DIRECTION_LEFT_BUTTON_B] = true
                MotionEvent.ACTION_MOVE -> joyPad[DIRECTION_LEFT_BUTTON_B] = true
                MotionEvent.ACTION_UP -> joyPad[DIRECTION_LEFT_BUTTON_B] = false
            }
            return@setOnTouchListener true
        }
        binding.upText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> joyPad[DIRECTION_UP_BUTTON_SELECT] = true
                MotionEvent.ACTION_MOVE -> joyPad[DIRECTION_UP_BUTTON_SELECT] = true
                MotionEvent.ACTION_UP -> joyPad[DIRECTION_UP_BUTTON_SELECT] = false
            }
            return@setOnTouchListener true
        }
        binding.downText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> joyPad[DIRECTION_DOWN_BUTTON_START] = true
                MotionEvent.ACTION_MOVE -> joyPad[DIRECTION_DOWN_BUTTON_START] = true
                MotionEvent.ACTION_UP -> joyPad[DIRECTION_DOWN_BUTTON_START] = false
            }
            return@setOnTouchListener true
        }
        binding.aText.setOnClickListener { joyPad[DIRECTION_RIGHT_BUTTON_A + 4] = true }
        binding.bText.setOnClickListener { joyPad[DIRECTION_LEFT_BUTTON_B + 4] = true }
        binding.selectText.setOnClickListener { joyPad[DIRECTION_UP_BUTTON_SELECT + 4] = true }
        binding.startText.setOnClickListener { joyPad[DIRECTION_DOWN_BUTTON_START + 4] = true }
        return binding.root
    }

    private fun startCpu() {
        val gpu = Gpu(myCanvas, myRect, binding.screenImage)
        val apu = Apu(audioTrack)
        val inputFile = resources.openRawResource(R.raw.bios)
        val bis = BufferedInputStream(inputFile)
        val bios = ByteArray(bis.available())
        bis.read(bios)
        bis.close()
        val path = context?.getExternalFilesDir(null)
        val newDumpDirectory = File(path, "dump")
        if (!newDumpDirectory.exists())
            newDumpDirectory.mkdirs()
        val newRomDirectory = File(path, "roms")
        if (!newRomDirectory.exists())
            newRomDirectory.mkdirs()
        val romFile: File   // = File(newRomDirectory, "rom.gb")
        val list = newRomDirectory.listFiles()
        if (list!!.isNotEmpty())
            romFile = list.random()
        else {
            romFile = File(newRomDirectory, "rom.gb")
            if (!romFile.exists())
                romFile.writeBytes(ByteArray(512) { 0xFF.toByte() })
        }
        cpu = Cpu(bios, newDumpDirectory, romFile, gpu, apu, joyPad)
        cpu.runTillCrash()
    }

    override fun onResume() {
        super.onResume()
        @Suppress("deprecation")
        val flags =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        @Suppress("deprecation")
        activity?.window?.decorView?.systemUiVisibility = flags
    }
}

