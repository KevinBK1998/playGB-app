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
var startStepping = false
var stepRequested = false

enum class JOYPAD {
    DIRECTION_RIGHT,
    DIRECTION_LEFT,
    DIRECTION_UP,
    DIRECTION_DOWN,
    BUTTON_A,
    BUTTON_B,
    BUTTON_SELECT,
    BUTTON_START
}

var startDump = false

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
        /* binding.screenImage.setOnClickListener {
             if(!startStepping)
             startStepping=true
             else
             stepRequested=true
         }*/
        binding.rightText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.DIRECTION_RIGHT.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.DIRECTION_RIGHT.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.leftText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.DIRECTION_LEFT.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.DIRECTION_LEFT.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.upText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.DIRECTION_UP.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.DIRECTION_UP.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.downText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.DIRECTION_DOWN.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.DIRECTION_DOWN.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.aText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.BUTTON_A.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.BUTTON_A.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.bText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.BUTTON_B.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.BUTTON_B.ordinal] = false
            }
            return@setOnTouchListener true
        }
        binding.selectText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.BUTTON_SELECT.ordinal)
                MotionEvent.ACTION_UP -> {
                    startDump = true
                    joyPad[JOYPAD.BUTTON_SELECT.ordinal] = false
                }
            }
            return@setOnTouchListener true
        }
        binding.startText.setOnTouchListener { _, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    updateKey(JOYPAD.BUTTON_START.ordinal)
                MotionEvent.ACTION_UP -> joyPad[JOYPAD.BUTTON_START.ordinal] = false
            }
            return@setOnTouchListener true
        }
        return binding.root
    }

    private fun updateKey(pressedButton: Int) {
        if (!joyPad[pressedButton])
            int60 = true
        joyPad[pressedButton] = true
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
        val list = newRomDirectory.listFiles { _, s -> s.endsWith(".gb") }
        if (list!!.isNotEmpty())
            romFile = list.random()
        else {
            romFile = File(newRomDirectory, "rom.gb")
            if (!romFile.exists())
                romFile.writeBytes(ByteArray(512) { 0xFF.toByte() })
        }
        val title = romFile.name
        myCanvas.drawText(title, 100f, 90f, myTextPaint)
        cpu = Cpu(bios, newDumpDirectory, romFile, gpu, apu, joyPad)
        cpu.start()
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

