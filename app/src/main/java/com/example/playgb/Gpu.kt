package com.example.playgb

import android.graphics.*
import android.util.Log
import android.widget.ImageView

const val SCREEN_WIDTH = 160
const val SCREEN_HEIGHT = 144
private const val LCD_BACKGROUND_ENABLE = 0
private const val LCD_BACKGROUND_MAP = 3
private const val LCD_BACKGROUND_WINDOW_MAP = 4
private const val LCD_DISPLAY_ENABLE = 7
/*
struct GPU
{
    //LCD&GPU control 0xFF40
    //        7      |6           |5     |4          |3          |2                         |1      |0
    //        Display|win:tile map|window|BG tile set|BG tile map|Sprites:size(0-8x8,1-8x16)|Sprites|BG
    //Scroll Y        0xFF42
    //Scroll X        0xFF43
    //Current scnline 0xFF44
    //BG palette      0xFF47
}
*/

@ExperimentalUnsignedTypes
class Gpu(
    private var gameCanvas: Canvas,
    private var drawArea: Rect,
    private var scrImage: ImageView
) {
    // TODO: 9/8/20 Complete GPU module
    private var objPalette: UByte = 0u
    private var objPalette1: UByte = 0u
    private var bgPalette: UByte = 0u
    private var windowX: UByte = 0u
    private var windowY: UByte = 0u
    private var lineYCompare: UByte = 0u
    private var lineY: UByte = 0u
    private var status: UByte = 2u
    private var scrollY: UByte = 0u
    private var scrollX: UByte = 0u
    private var vram = ByteArray(8192)
    private var lcdCtrl: UByte = 0u

    private var clock: Short = 0
    private var gameRenderScreen =
        Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private var gameRenderCanvas = Canvas(gameRenderScreen)
    private var painter = arrayOf(Paint().apply { color = Color.rgb(0xFF, 0xFF, 0xFF) },
        Paint().apply { color = Color.rgb(0xAA, 0xAA, 0xAA) },
        Paint().apply { color = Color.rgb(0x55, 0x55, 0x55) },
        Paint().apply { color = Color.rgb(0, 0, 0) })
    private var bgLine = ByteArray(160)
    private var timeToDraw = false

    private fun readRegBit(reg: UByte, bitNo: Int): Boolean {
        return ((reg.toInt() shr bitNo) and 1) != 0
    }

    private fun getStatusMode(): Byte {
        return (status and 3u).toByte()
    }

    fun readFromVRam(address: UShort): UByte {
        //Log.i("GB.mmu", "VRam read $data from $address")
        return vram[(address and 0x1FFFu).toInt()].toUByte()
    }

    fun writeToVRam(address: UShort, data: UByte) {
        //Log.i("GB.mmu", "VRam write $data to $address ")
        vram[(address and 0x1FFFu).toInt()] = data.toByte()
    }

    fun write(address: UShort, data: UByte) {
        //Log.i("GB.mmu", "GPU write $data to $address")
        when (address.toInt()) {
            0xFF40 -> lcdCtrl = data
            0xFF41 -> status = data
            0xFF42 -> scrollY = data
            0xFF43 -> scrollX = data
            //0xFF44 -> lineY               //read only
            0xFF45 -> lineYCompare = data
            0xFF47 -> bgPalette = data      //write only
            0xFF48 -> objPalette = data    //write only
            0xFF49 -> objPalette1 = data    //write only
            0xFF4A -> windowY = data
            0xFF4B -> windowX = data
            else -> Log.e("GB.mmu", "Access ${address}?")
        }
    }

    fun read(address: UShort): UByte {
        //Log.i("GB.mmu", "GPU read $address")
        return when (address.toInt()) {
            0xFF40 -> lcdCtrl.toUByte()
            0xFF41 -> status
            0xFF42 -> scrollY
            0xFF43 -> scrollX
            0xFF44 -> lineY         //read only
            0xFF45 -> lineYCompare
            //0xFF47->bgPalette     //write only
            //0xFF48->objPalette   //write only
            //0xFF49->objPalette1   //write only
            0xFF4A -> windowY
            0xFF4B -> windowX
            else -> {
                Log.e("GB.mmu", "Access ${address}?")
                0u
            }
        }
    }

    fun log(): String {
        return "CTRL=0x" + String.format("%02X", lcdCtrl.toByte()) + "|STAT=0x" +
                String.format("%02X", status.toByte()) + "|SCY=0x" +
                String.format("%02X", scrollY.toByte()) + "|SCX=0x" +
                String.format("%02X", scrollX.toByte()) + "|LY=0x" +
                String.format("%02X", lineY.toByte()) + "|BGPAL=0x" +
                String.format("%02X", bgPalette.toByte()) + "|OBJ0=0x" +
                String.format("%02X", objPalette.toByte()) + "|OBJ1=0x" +
                String.format("%02X", objPalette1.toByte())
    }

    fun timePassed(time: Int) {
        clock = (clock.toInt() + time).toShort()
        when (getStatusMode().toInt()) {
            0 -> {
                //Log.i("GB.gpu","HBLANK $clock")
                //HBLANK Mode
                if (clock >= 208) {//376-168
                    status++
                    clock = 0
                    lineY++
                    if (lineY == 144.toUByte()) {
                        if (!readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
                            clearScreen()
                        drawScreen()
                    } else status++
                }
            }
            1 -> {
                //Log.i("GB.gpu","VBLANK $clock $lineY")
                if (clock >= 456) {
                    clock = 0
                    lineY++
                    if (lineY == 153.toUByte()) {
                        status++
                        lineY = 0u
                    }
                }
            }
            2 -> {
                //Log.i("GB.gpu","OAM $clock")
                //OAM Mode
                if (clock >= 80) {
                    status++
                    clock = 0
                    if (readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
                        scanLine()
                    timeToDraw = true
                }
            }
            3 -> {
                //Log.i("GB.gpu","VRAM $clock")
                //VRAM Mode
                if (clock >= 168) {
                    status = status and 0xFCu
                    clock = 0
                } else if (timeToDraw) {
                    timeToDraw = false
                    repeat(160) {
                        gameRenderCanvas.drawPoint(
                            it.toFloat(),
                            lineY.toFloat(),
                            painter[getPaletteColor(bgPalette, bgLine[it].toInt())]
                        )
                    }
                }
            }
            else -> Log.w("GB.gpu", "invalid mode:${getStatusMode()}")
        }
    }

    private fun drawScreen() {
        gameCanvas.drawBitmap(gameRenderScreen, null, drawArea, null)
        scrImage.invalidate()
    }

    private fun clearScreen() {
        gameRenderCanvas.drawRGB(0, 0, 0)
    }

    private fun scanLine() {
        // TODO: 7/8/20 Complete this function to include interrupts
        if (readRegBit(lcdCtrl, LCD_BACKGROUND_ENABLE))
            bgScanLine()
    }

    private fun getPaletteColor(pal: UByte, clr: Int): Int {
        return (pal.toInt() shr clr * 2) and 3
    }

//    private fun printVRam() {
//        repeat(8192) {
//            Log.i("VRAM DATA $it", "${vram[it].toUByte()}")
//        }
//    }

    private fun bgScanLine() {
        val mapOffset =//Assumed lineY+scrollY never overflows
            ((lineY + scrollY) / 8u) * 32u + if (readRegBit(
                    lcdCtrl,
                    LCD_BACKGROUND_MAP
                )
            ) 0x1C00u else 0x1800u
        var lineOffset = scrollX / 8u
        val y = ((lineY + scrollY) and 7u)
        var x = (scrollX and 7u).toInt()
        var i = 0
        while (i < 160) {
            //val address = (mapOffset + lineOffset).toInt()
            var tile: UShort = vram[(mapOffset + lineOffset).toInt()].toUShort()
            //Log.i("Tile","$address")
            if (!readRegBit(lcdCtrl, LCD_BACKGROUND_WINDOW_MAP) and (tile < 128u))
                tile = (tile + 256u).toUShort()
            val lowByte = vram[(tile * 16u + y * 2u).toInt()].toUShort()
            val highByte = vram[(tile * 16u + y * 2u + 1u).toInt()].toUShort()
            while (x < 8) {
                bgLine[i] =
                    ((lowByte.toUInt() shr (7 - x) and 1u) + 2u * (highByte.toUInt() shr (7 - x) and 1u)).toByte()
                x++
                i++
            }
            x = 0
            lineOffset++
        }
    }
}