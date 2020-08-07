package com.example.playgb

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log

const val SCREEN_WIDTH = 160
const val SCREEN_HEIGHT = 144

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
class Gpu {
    private var objPalette: UByte = 0u
    private var objPalette1: UByte = 0u
    private var bgPalette: UByte = 0u
    private var windowX: UByte = 0u
    private var windowY: UByte = 0u
    private var lineYCompare: UByte = 0u
    private var lineY: UByte = 0u
    private var status: UByte = 0u
    private var scrollY: UByte = 0u
    private var scrollX: UByte = 0u
    private var vram = ByteArray(8192)
    private var lcdCtrl: UByte = 0u

    private var clock: Short = 0
    private var gameScreen =
        Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private var gameCanvas = Canvas(gameScreen)
    private var painter = Paint()
    private var bgLine = ByteArray(160)

//    private fun readStatusFlags(name: String): Boolean {
//        // TODO: 7/8/20 Implement to read enable bits
//        return false
//    }

    private fun statusMode(): Byte {
        return (status and 3u).toByte()
    }

    private fun readCtrlBits(name: String): Boolean {
        // TODO: 7/8/20 Implement to read all bits
        return when (name) {
            "lcdDisplayEnable" -> (lcdCtrl and 0x80u).toUInt() != 0u
            "winTileMapSelect" -> (lcdCtrl and 0x40u).toUInt() != 0u
            "winDisplayEnable" -> (lcdCtrl and 0x20u).toUInt() != 0u
            "bg+WinTileSelect" -> (lcdCtrl and 0x10u).toUInt() != 0u
            "bgTileMapSelect" -> (lcdCtrl and 8u).toUInt() != 0u
            "objSize" -> (lcdCtrl and 4u).toUInt() != 0u
            "objEnable" -> (lcdCtrl and 2u).toUInt() != 0u
            "bgEnable" -> (lcdCtrl and 1u).toUInt() != 0u
            else -> false
        }
    }

    fun readFromVRam(address: UShort): UByte {
        Log.i("GB.mmu", "VRam read $address")
        return vram[(address and 0x1FFFu).toInt()].toUByte()
    }

    fun writeToVRam(address: UShort, data: UByte) {
        Log.i("GB.mmu", "VRam write $address")
        vram[(address and 0x1FFFu).toInt()] = data.toByte()
    }

    fun write(address: UShort, data: UByte) {
        Log.i("GB.mmu", "GPU write $data to $address")
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
        Log.i("GB.mmu", "GPU read $address")
        return when (address.toInt()) {
            0xFF40 -> lcdCtrl
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
        return "CTRL=0x" + String.format("%02X", lcdCtrl.toByte()) + "|SCY=0x" +
                String.format("%02X", scrollY.toByte()) + "|SCX=0x" +
                String.format("%02X", scrollX.toByte()) + "|LY=0x" +
                String.format("%02X", lineY.toByte()) + "|BGPAL=0x" +
                String.format("%02X", bgPalette.toByte()) + "|OBJ0=0x" +
                String.format("%02X", objPalette.toByte()) + "|OBJ1=0x" +
                String.format("%02X", objPalette1.toByte())
    }

    fun timePassed(time: Int) {
        clock = (clock.toInt() + time).toShort()
        when (statusMode().toInt()) {
            0 -> {
                //HBLANK Mode
                if (clock >= 208) {//376-168
                    status++
                    clock = 0
                    lineY++
                    if (lineY == 143.toUByte()) {
                        if (!readCtrlBits("lcdDisplayEnable"))
                            clearScreen()
                        drawScreen()
                    } else status++
                }
            }
            1 -> {
            }
            2 -> {
                //OAM Mode
                if (clock >= 80) {
                    status++
                    clock = 0
                }
            }
            3 -> {
                //VRAM Mode
                // TODO: 7/8/20 Add dots as per specifications
                if (clock >= 168) {
                    status = status and 0xFBu
                    clock = 0
                    if (readCtrlBits("lcdDisplayEnable"))
                        scanLine()
                }
            }
            else -> Log.w("GB.gpu", "invalid mode:${statusMode()}")
        }
    }

    private fun drawScreen() {
        // TODO: 7/8/20 Draw the screen here
    }

    private fun clearScreen() {
        gameCanvas.drawRGB(0, 0, 0)
    }

    private fun scanLine() {
        // TODO: 7/8/20 Complete this function to include interrupts
        if (readCtrlBits("bgEnable"))
            bgScanLine()
        if (readCtrlBits("objEnable"))
            spriteScanLine()

    }

    private fun spriteScanLine() {
        // TODO: 7/8/20 scan sprites and overwrite if visible
    }

    private fun getColorNo(pal: UByte, clr: Int): Int {
        return (pal.toInt() shr clr * 2) and 3
    }

    private fun bgScanLine() {
        // TODO: 7/8/20 scan background image
        val mapOffset =//Assumed lineY+scrollY never overflows
            ((lineY + scrollY) / 8u) * 32u + if (readCtrlBits("bgMapSelect")) 0x1C00u else 0x1800u
        var lineOffset = scrollX / 8u
        val y = ((lineY + scrollY) and 7u).toInt()
        var x = (scrollX and 7u).toInt()
        var i = 0
        while (i < 160) {
            var tile: Short = readFromVRam((mapOffset + lineOffset).toUShort()).toShort()
            if (readCtrlBits("bg+WinTileSelect") and (tile < 128))
                tile = (tile + 256).toShort()
            val lowByte = readFromVRam((tile * 16 + y * 2).toUShort())
            val highByte = readFromVRam((tile * 16 + y * 2 + 1).toUShort())
            while (x < 8) {
                bgLine[i] =
                    ((lowByte.toUInt() shr (7 - x) and 1u) + 2u * (highByte.toUInt() shr (7 - x) and 1u)).toByte()
                painter.color = when (getColorNo(bgPalette, bgLine[i].toInt())) {
                    1 -> 0x555555
                    2 -> 0xAAAAAA
                    3 -> 0xFFFFFF
                    else -> 0
                }
                Log.i("GB.gpu", "${bgLine[i]} $i $lineY")
                //gameCanvas.drawPoint(i.toFloat(), lineY.toFloat(), painter)
                gameCanvas.drawRGB(255, 255, 255)
                x++
                i++
            }
            x = 0
            lineOffset++
        }
    }

    fun getScreenBitmap(): Bitmap {
        return gameScreen
    }
}