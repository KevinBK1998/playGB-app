package com.example.playgb

import android.graphics.*
import android.util.Log
import android.widget.ImageView

const val SCREEN_WIDTH = 160
const val SCREEN_HEIGHT = 144
private const val LCD_BACKGROUND_ENABLE = 0
private const val LCD_SPRITE_ENABLE = 1
private const val LCD_SPRITE_SIZE = 2
private const val LCD_BACKGROUND_MAP_SELECT = 3
private const val LCD_BACKGROUND_WINDOW_DATA_SELECT = 4
private const val LCD_WINDOW_ENABLE = 5
private const val LCD_WINDOW_MAP_SELECT = 6
private const val LCD_DISPLAY_ENABLE = 7
private const val STAT_LYC_FLAG = 2
private const val STAT_LYC_ENABLE = 6
private const val OAM_SPRITE_PALETTE = 4
private const val OAM_X_FLIP = 5
private const val OAM_Y_FLIP = 6
private const val OAM_SPRITE_PRIORITY = 7
private const val SPRITES_PER_LINE = 10
/*
LCD&GPU control 0xFF40
 6
 win:tile map
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
    private var lcdCtrl: UByte = 0u

    private var vram = ByteArray(8192)
    private var oam = ByteArray(160)

    private var clock: Short = 0
    private var gameRenderScreen =
        Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private var gameRenderCanvas = Canvas(gameRenderScreen)
    private var painter =
        arrayOf(Paint().apply { color = Color.rgb(0xFF, 0xFF, 0xFF);textSize = 70f },
            Paint().apply { color = Color.rgb(0xAA, 0xAA, 0xAA) },
            Paint().apply { color = Color.rgb(0x55, 0x55, 0x55) },
            Paint().apply { color = Color.rgb(0, 0, 0) })
    private var scanLine = ByteArray(160)
    private var bgLine = ByteArray(160)

    private var spriteData = IntArray(10)
    private var timeToDraw = false
    private var int40 = false
    private var int48 = false
    private var bufferedSpriteIndex = 0
    private var spriteLength = 0
    private var bufferedX = 0

    private fun readRegBit(reg: UByte, bitNo: Int): Boolean {
        return ((reg.toInt() shr bitNo) and 1) != 0
    }

    private fun getStatusMode(): Byte {
        return (status and 3u).toByte()
    }

    fun readFromVRam(address: UShort): UByte {
        //Log.i("GB.mmu", "VRam read $data from $address")
        return if (getStatusMode() < 3 || !readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
            vram[(address and 0x1FFFu).toInt()].toUByte()
        else 0xFF.toUByte()
    }

    fun writeToVRam(address: UShort, data: UByte) {
        //Log.i("GB.mmu", "VRam write $data to $address ")
        if (getStatusMode() < 3 || !readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
            vram[(address and 0x1FFFu).toInt()] = data.toByte()
    }

    fun writeToOAM(address: UShort, data: UByte) {
        //Log.i("GB.mmu", "OAM write $data to $address ")
        if (getStatusMode() < 2 || !readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
            oam[(address and 0xFFu).toInt()] = data.toByte()
    }

    fun dmaTransfer(dataBlock: ByteArray) {
        oam = dataBlock
    }

    fun write(address: UShort, data: UByte) {
        //Log.i("GB.mmu", "GPU write $data to $address")
        when (address.toInt()) {
            0xFF40 -> lcdCtrl = data
            0xFF41 -> {
                if (data and 0x38u > 0u)
                    TODO("Write $data to LCD STAT")
                status = status and 7u
                status = status or (data and 0xF8u)
            }
            0xFF42 -> scrollY = data
            0xFF43 -> scrollX = data
            //0xFF44 -> lineY               //read only
            0xFF45 -> {
                Log.i("GB.mmu", "LYC update to $data")
                lineYCompare = data
            }
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
        gameCanvas.drawText("CPU CRASHED", 100f, 200f, painter[0])
        scrImage.invalidate()
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
        if (time > 0)
            when (getStatusMode().toInt()) {
                0 -> {
                    //Log.i("GB.gpu","HBLANK $clock")
                    //HBLANK Mode
                    if (clock >= 208) {//376-168
                        status++
                        clock = 0
                        lineY++
                        status = if (lineY == lineYCompare)
                            status or 4u
                        else
                            status and 0xFBu
                        if (readRegBit(status, STAT_LYC_ENABLE) && readRegBit(
                                status,
                                STAT_LYC_FLAG
                            )
                        )
                            int48 = true
                        if (lineY == 144.toUByte()) {
                            if (!readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE))
                                clearScreen()
                            drawScreen()
                            int40 = true
                        } else {
                            status++
                            bufferedSpriteIndex = 0
                            spriteLength = 0
                        }
                    }
                }
                1 -> {
                    //Log.i("GB.gpu","VBLANK $clock $lineY")
                    //VBLANK mode
                    if (clock >= 456) {
                        clock = 0
                        lineY++
                        status = if (lineY == lineYCompare)
                            status or 4u
                        else
                            status and 0xFBu
                        if (readRegBit(status, STAT_LYC_ENABLE) && readRegBit(
                                status,
                                STAT_LYC_FLAG
                            )
                        )
                            int48 = true
                        if (lineY == 153.toUByte()) {
                            status++
                            lineY = 0u
                            bufferedSpriteIndex = 0
                            spriteLength = 0
                        }
                    }
                }
                2 -> {
                    //Log.i("GB.gpu","OAM $clock")
                    //OAM Mode
                    if (clock >= 80) {
                        status++
                        clock = 0
                        if (readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE)) {
                            scanLine()
                            timeToDraw = true
                            bufferedX = 0
                        }
                    } else if (readRegBit(lcdCtrl, LCD_SPRITE_ENABLE)) {
                        if (bufferedSpriteIndex < 40 && spriteLength < SPRITES_PER_LINE) {
                            val bufferSize = 5
                            repeat(bufferSize) { spriteSearch(bufferedSpriteIndex + it) }
                            bufferedSpriteIndex += bufferSize
                        }
                    }
                }
                3 -> {
                    //Log.i("GB.gpu","VRAM $clock")
                    //VRAM Mode
                    if (clock >= 168) {
                        status = status and 0xFCu
                        clock = 0
                    } else if (timeToDraw) {
                        val bufferSize = 16
                        if (bufferedX >= 160)
                            timeToDraw = false
                        else {
                            repeat(bufferSize) {
                                gameRenderCanvas.drawPoint(
                                    ((bufferedX + it) % 160).toFloat(),
                                    lineY.toFloat(),
                                    painter[scanLine[((bufferedX + it) % 160)].toInt()]
                                )
                            }
                            bufferedX += bufferSize
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
        gameRenderCanvas.drawRGB(0xFF, 0xFF, 0xFF)
    }

    private fun scanLine() {
        // TODO: 7/8/20 Complete this function to include interrupts
        if (readRegBit(lcdCtrl, LCD_BACKGROUND_ENABLE))
            bgScanLine()
        if (readRegBit(lcdCtrl, LCD_WINDOW_ENABLE))
            windowScanLine()
        if (readRegBit(lcdCtrl, LCD_SPRITE_ENABLE))
            spriteScanLine()
    }

    private fun spriteSearch(ind: Int) {
        if (spriteLength < SPRITES_PER_LINE) {
            val y: UByte = oam[ind * 4].toUByte()
            if (y in 1u until 160u)
                if (readRegBit(lcdCtrl, LCD_SPRITE_SIZE)) TODO("Scan Sprites of 8x16")
                else if (y > 8u)
                    if (lineY in (y - 16u).toUByte() until (y - 8u).toUByte()) {
                        spriteData[spriteLength++] = ind
                    }
        }
    }

    private fun getBGPaletteColor(clr: Byte): Byte {
        return ((bgPalette.toUInt() shr (clr * 2)) and 3u).toByte()
    }

    private fun bgScanLine() {
        // TODO: 18/8/20 Wrap over when background goes out of frame
        val mapOffset =//Assumed lineY+scrollY never overflows
            ((lineY + scrollY) / 8u) * 32u + if (readRegBit(
                    lcdCtrl,
                    LCD_BACKGROUND_MAP_SELECT
                )
            ) 0x1C00u else 0x1800u
        var lineOffset = scrollX / 8u
        val y = ((lineY + scrollY) and 7u)
        var x = (scrollX and 7u).toInt()
        var i = 0
        while (i < 160) {
            val tile = vram[(mapOffset + lineOffset).toInt()].toUByte()
            val tileOffset =
                if (!readRegBit(lcdCtrl, LCD_BACKGROUND_WINDOW_DATA_SELECT) and (tile < 128u)) 256u
                else 0u
            val lowByte = vram[((tile + tileOffset) * 16u + y * 2u).toInt()].toUShort()
            val highByte = vram[((tile + tileOffset) * 16u + y * 2u + 1u).toInt()].toUShort()
            while (x < 8) {
                bgLine[i] =
                    (((lowByte.toUInt() shr (7 - x)) and 1u) + 2u * ((highByte.toUInt() shr (7 - x)) and 1u)).toByte()
                scanLine[i] = getBGPaletteColor(bgLine[i])
                x++
                i++
            }
            x = 0
            lineOffset++
        }
    }

    private fun windowScanLine() {
        TODO("Scan Window")
        /* val mapOffset =//Assumed lineY+scrollY never overflows
            (lineY / 8u) * 32u + if (readRegBit(lcdCtrl, LCD_WINDOW_MAP_SELECT)) 0x1C00u
            else 0x1800u
        var lineOffset = 0u
        val y = (lineY and 7u)
        var x = 0
        var i = (windowX - 7u).toInt()
        while (i < 160) {
            if (windowY <= lineY) {
                val tile = vram[(mapOffset + lineOffset).toInt()].toUByte()
                val tileOffset =
                    if (!readRegBit(
                            lcdCtrl,
                            LCD_BACKGROUND_WINDOW_DATA_SELECT
                        ) and (tile < 128u)
                    ) 256u
                    else 0u
                val lowByte = vram[((tile + tileOffset) * 16u + y * 2u).toInt()].toUShort()
                val highByte = vram[((tile + tileOffset) * 16u + y * 2u + 1u).toInt()].toUShort()
                while (x < 8) {
                    val bgPixel =
                        ((lowByte.toUInt() shr (7 - x) and 1u) + 2u * (highByte.toUInt() shr (7 - x) and 1u)).toInt()
                    scanLine[i] = ((bgPalette.toInt() shr bgPixel * 2) and 3).toByte()
                    x++
                    i++
                }
                x = 0
                lineOffset++
            }
        }*/
    }

    private fun spriteScanLine() {
        while (spriteLength-- > 0) {
            val ind = spriteData[spriteLength]
            val y: UByte = oam[ind * 4].toUByte()
            val flags: UByte = oam[ind * 4 + 3].toUByte()
            val tileNo: UByte = oam[ind * 4 + 2].toUByte()
            val lowByte = if (readRegBit(flags, OAM_Y_FLIP))
                vram[(tileNo * 16u + (y - lineY - 9u) * 2u).toInt()].toUShort()
            else vram[(tileNo * 16u + (lineY - y + 16u) * 2u).toInt()].toUShort()
            val highByte = if (readRegBit(flags, OAM_Y_FLIP))
                vram[(tileNo * 16u + ((y - lineY - 9u) * 2u) + 1u).toInt()].toUShort()
            else vram[(tileNo * 16u + (lineY - y + 16u) * 2u + 1u).toInt()].toUShort()
            val row = ByteArray(8)
            repeat(8) {
                if (readRegBit(flags, OAM_X_FLIP))
                    row[it] =
                        (((lowByte.toUInt() shr it) and 1u) + 2u * ((highByte.toUInt() shr it) and 1u)).toByte()
                else
                    row[it] =
                        (((lowByte.toUInt() shr (7 - it)) and 1u) + 2u * ((highByte.toUInt() shr (7 - it)) and 1u)).toByte()
            }
            val x: UByte = oam[ind * 4 + 1].toUByte()
            val objPal = if (readRegBit(flags, OAM_SPRITE_PALETTE)) objPalette1 else objPalette
            if (x in 1u until 168u) {
                repeat(8) {
                    if (row[it] > 0) {
                        if (readRegBit(flags, OAM_SPRITE_PALETTE)) {//Behind Background
                            if (scanLine[(x - 8u + it.toUInt()).toInt()].toInt() == 0)
                                scanLine[(x - 8u + it.toUInt()).toInt()] =
                                    getObjPaletteColor(objPal, row[it]).toByte()
                        } else  //Foreground*/
                            scanLine[(x - 8u + it.toUInt()).toInt() % 160] =
                                getObjPaletteColor(objPal, row[it]).toByte()
                    }
                }
            }
        }
    }

    private fun getObjPaletteColor(pal: UByte, clr: Byte): Int {
        return (pal.toInt() shr clr * 2) and 3
    }

    fun isVBlank(): Boolean {
        if (int40) {
            int40 = false
            return true
        }
        return false
    }

    fun statInterrupted(): Boolean {
        if (int48) {
            int48 = false
            return true
        }
        return false
    }

    fun dumpOAM(): String {
        var data = "y x tno palno bg flips-y,x"
        repeat(160) {
            if (it % 4 == 0)
                data += "\n" + oam[it].toUByte().toString() + " "
            if (it % 4 == 1)
                data += oam[it].toUByte().toString() + " "
            if (it % 4 == 2)
                data += oam[it].toUByte().toString(16) + " "
            if (it % 4 == 3) {
                data += if (readRegBit(oam[it].toUByte(), OAM_SPRITE_PALETTE)) "1 " else "0 "
                data += if (readRegBit(
                        oam[it].toUByte(),
                        OAM_SPRITE_PRIORITY
                    )
                ) "below " else "above "
                data += if (readRegBit(oam[it].toUByte(), OAM_Y_FLIP)) "v-flip " else "normal "
                data += if (readRegBit(oam[it].toUByte(), OAM_X_FLIP)) "h-flip " else "normal "
            }
        }
        return data
    }
}