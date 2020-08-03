package com.example.playgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import com.example.playgb.databinding.FragmentScreenBinding
import java.io.InputStream

class ScreenFragment : Fragment() {
    private lateinit var myCanvas: Canvas
    private val myPaint = Paint()
    private val myTextPaint = Paint().apply { textSize = 70f }

    /*
    private val paint = Paint().apply {
        color = drawColor
        // Smooths out edges of what is drawn without affecting shape.
        isAntiAlias = true
        // Dithering affects how colors with higher-precision than the device are down-sampled.
        isDither = true
        style = Paint.Style.STROKE // default: FILL
        strokeJoin = Paint.Join.ROUND // default: MITER
        strokeCap = Paint.Cap.ROUND // default: BUTT
        strokeWidth = STROKE_WIDTH // default: Hairline-width (really thin)
    }
    */
    private lateinit var myBitmap: Bitmap
    private lateinit var binding: FragmentScreenBinding
    private val myRect = Rect()
    private var blackColour = 0
    private var whiteColour = 0
    private lateinit var inputBios: InputStream
    private lateinit var bios: ByteArray
    private lateinit var cpu: Cpu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blackColour = ResourcesCompat.getColor(resources, R.color.colorBlack, null)
        whiteColour = ResourcesCompat.getColor(resources, R.color.colorWhite, null)
        myPaint.color = whiteColour
        myTextPaint.color = whiteColour
        inputBios = resources.openRawResource(R.raw.bios)
        bios = ByteArray(inputBios.available())
        inputBios.read(bios)
        cpu = Cpu(bios)
        inputBios.close()
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
        binding.screenImage.setOnClickListener { continueGame(it) }
        return binding.root
    }

    private fun continueGame(view: View) {
        myBitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        binding.screenImage.setImageBitmap(myBitmap)
        myCanvas = Canvas(myBitmap)
        myCanvas.drawColor(blackColour)
        myRect.set(
            (view.width - 160) / 2,
            (view.height - 144) / 2,
            (view.width + 160) / 2,
            (view.height + 144) / 2
        )
        myCanvas.drawRect(myRect, myPaint)
        val msg = cpu.execute()
        myCanvas.drawText(msg, 100f, 100f, myTextPaint)
        val log = cpu.log()
        myCanvas.drawText(log, 100f, 200f, myTextPaint)
        view.invalidate()
    }
}