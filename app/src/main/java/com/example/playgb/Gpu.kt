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
private const val LCD_BACKGROUND_WINDOW_ADDRESS_MODE_SELECT = 4
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
private const val FRAME_SKIP = 6

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
    private var spriteHeight = 8u
    private var gameRenderScreen =
        Bitmap.createBitmap(SCREEN_WIDTH, SCREEN_HEIGHT, Bitmap.Config.ARGB_8888)
    private var gameRenderCanvas = Canvas(gameRenderScreen)
    private var painter =
        arrayOf(Paint().apply { color = Color.rgb(0xFF, 0xFF, 0xFF);textSize = 70f },
            Paint().apply { color = Color.rgb(0xAA, 0xAA, 0xAA) },
            Paint().apply { color = Color.rgb(0x55, 0x55, 0x55) },
            Paint().apply { color = Color.rgb(0, 0, 0) })
    private var scannedLine = ByteArray(160)
    private var bgLine = ByteArray(160)
/*    private var pathWhite = Path()
    private var pathLightGray = Path()
    private var pathDarkGray = Path()
    private var pathBlack = Path()
    private lateinit var screenPath: Path*/

    private var activeSpriteIndex = IntArray(SPRITES_PER_LINE)

    //private var timeToDraw = false
    private var int40 = false
    private var int48 = false
    private var bufferedSpriteIndex = 0
    private var spriteLength = 0

    //private var bufferedX = 0
    private var frameCounter = 0L

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
            0xFF40 -> {
                if (readRegBit(lcdCtrl, LCD_DISPLAY_ENABLE) && !readRegBit(
                        data,
                        LCD_DISPLAY_ENABLE
                    )
                )
                    lineY = 0u
                lcdCtrl = data
                spriteHeight = if (readRegBit(lcdCtrl, LCD_SPRITE_SIZE)) 16u else 8u
            }
            0xFF41 -> {
                if (data and 0x38u > 0u)
                    TODO("Write $data to LCD STAT->Update Interrupt")
                status = (status and 7u) or (data and 0x78u)
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
            else -> Log.e("GB.mmu", "Write Access ${address}?")
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
            0xFF47 -> bgPalette
            0xFF48 -> objPalette
            0xFF49 -> objPalette1
            0xFF4A -> windowY
            0xFF4B -> windowX
            else -> {
                Log.e("GB.mmu", "Read Access ${address}?")
                0xFFu
            }
        }
    }

    fun log(): String {
        gameCanvas.drawText("CPU CRASHED", 1500f, 90f, painter[0])
        scrImage.invalidate()
        return "CTRL=0x" + String.format("%02X", lcdCtrl.toByte()) + "|STAT=0x" +
                String.format("%02X", status.toByte()) + "|SCY=0x" +
                String.format("%02X", scrollY.toByte()) + "|SCX=0x" +
                String.format("%02X", status.toByte()) + "|WY=0x" +
                String.format("%02X", windowY.toByte()) + "|WX=0x" +
                String.format("%02X", windowX.toByte()) + "|LY=0x" +
                String.format("%02X", lineY.toByte()) + "|BGPAL=0x" +
                String.format("%02X", bgPalette.toByte()) + "|OBJ0=0x" +
                String.format("%02X", objPalette.toByte()) + "|OBJ1=0x" +
                String.format("%02X", objPalette1.toByte())
    }

    fun timePassed(time: Int) {
        // TODO: 20/8/20 Complete STAT Interrupts
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
                            /* pathWhite.rewind()
                             pathLightGray.rewind()
                             pathDarkGray.rewind()
                             pathBlack.rewind()*/
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
                            /*timeToDraw = true
                            bufferedX = 0*/
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
                    }
                    /*else if (timeToDraw) {
                        val bufferSize = 16
                        if (bufferedX >= 160)
                            timeToDraw = false
                        else {
                            repeat(bufferSize) {
                                gameRenderCanvas.drawPoint(
                                    ((bufferedX + it) % 160).toFloat(),
                                    lineY.toFloat(),
                                    painter[scannedLine[((bufferedX + it) % 160)].toInt()]
                                )
                            }
                            bufferedX += bufferSize
                        }
                    }*/
                }
                else -> Log.w("GB.gpu", "invalid mode:${getStatusMode()}")
            }
    }

    private fun drawScreen() {
        /*gameRenderCanvas.drawPath(pathWhite,painter[0])
        gameRenderCanvas.drawPath(pathLightGray,painter[1])
        gameRenderCanvas.drawPath(pathDarkGray,painter[2])
        gameRenderCanvas.drawPath(pathBlack,painter[3])*/
        if (frameCounter % (FRAME_SKIP + 1) == 0L) {
            gameCanvas.drawBitmap(gameRenderScreen, null, drawArea, null)
            scrImage.invalidate()
        }
        frameCounter++
    }

    private fun clearScreen() {
        gameRenderCanvas.drawRGB(0xFF, 0xFF, 0xFF)
    }

    private fun scanLine() {
        if (frameCounter % (FRAME_SKIP + 1) == 0L) {
            if (readRegBit(lcdCtrl, LCD_BACKGROUND_ENABLE)) {
                bgScanLine()
                if (readRegBit(lcdCtrl, LCD_WINDOW_ENABLE))
                    windowScanLine()
            }
            if (readRegBit(lcdCtrl, LCD_SPRITE_ENABLE))
                spriteScanLine()
            repeat(160) {
                gameRenderCanvas.drawPoint(
                    it.toFloat(),
                    lineY.toFloat(),
                    painter[scannedLine[it].toInt()]
                )
            }
        }
        /*var lastPixelColor = scannedLine[0].toInt()
        var startPixel = 0f
        var endPixel = 0f
        repeat(160) {
            if (scannedLine[it].toInt() != lastPixelColor) {
                endPixel = (it - 1).toFloat()
                screenPath = when (lastPixelColor) {
                    1 -> pathLightGray
                    2 -> pathDarkGray
                    3 -> pathBlack
                    else -> pathWhite
                }
                screenPath.moveTo(startPixel, lineY.toFloat())
                screenPath.lineTo(endPixel, lineY.toFloat())
                startPixel=it.toFloat()
                endPixel=startPixel
                lastPixelColor= scannedLine[it].toInt()
            }
        }
        if(endPixel<159){
            endPixel=159f
            screenPath = when (lastPixelColor) {
                1 -> pathLightGray
                2 -> pathDarkGray
                3 -> pathBlack
                else -> pathWhite
            }
            screenPath.moveTo(startPixel, lineY.toFloat())
            screenPath.lineTo(endPixel, lineY.toFloat())
        }*/
    }

    private fun spriteSearch(ind: Int) {
        if (spriteLength < SPRITES_PER_LINE) {
            val y: UByte = oam[ind * 4].toUByte()
            if (y in 1u until 160u)
                if (readRegBit(lcdCtrl, LCD_SPRITE_SIZE) || y > 8u) {
                    if (lineY in (y - 16u).toUByte() until (y - 16u + spriteHeight).toUByte()) {
                        activeSpriteIndex[spriteLength] = ind
                        //Sort in order of x coordinate,while preserving same coordinates order
                        var i = spriteLength++
                        while (i > 0 && oam[activeSpriteIndex[i - 1] * 4 + 1].toUInt() > oam[activeSpriteIndex[i] * 4 + 1].toUInt()) {
                            val tempInd = activeSpriteIndex[i]
                            activeSpriteIndex[i] = activeSpriteIndex[i - 1]
                            activeSpriteIndex[i - 1] = tempInd
                            i--
                        }
                    }
                }
        }
    }

    private fun getBGPaletteColor(clr: Byte): Byte {
        return ((bgPalette.toUInt() shr (clr * 2)) and 3u).toByte()
    }

    private fun bgScanLine() {
        val mapOffset = (((lineY + scrollY) % 256u) / 8u) * 32u +
                if (readRegBit(lcdCtrl, LCD_BACKGROUND_MAP_SELECT))
                    0x1C00u
                else
                    0x1800u
        var lineOffset = scrollX / 8u
        val y = ((lineY + scrollY) and 7u)
        var x = (scrollX and 7u).toInt()
        var i = 0
        var scrollCounter = scrollX.toInt()
        while (i < 160) {
            if (scrollCounter == 256)
                lineOffset = 0u
            val tile = vram[(mapOffset + lineOffset).toInt()].toUByte()
            val tileOffset =
                if (!readRegBit(
                        lcdCtrl,
                        LCD_BACKGROUND_WINDOW_ADDRESS_MODE_SELECT
                    ) and (tile < 128u)
                ) 256u
                else 0u
            val lowByte = vram[((tile + tileOffset) * 16u + y * 2u).toInt()].toUShort()
            val highByte = vram[((tile + tileOffset) * 16u + y * 2u + 1u).toInt()].toUShort()
            while (x < 8 && i < 160) {
                bgLine[i] =
                    (((lowByte.toUInt() shr (7 - x)) and 1u) + 2u * ((highByte.toUInt() shr (7 - x)) and 1u)).toByte()
                scannedLine[i] = getBGPaletteColor(bgLine[i])
                x++
                i++
                scrollCounter++
            }
            x = 0
            lineOffset++
        }
    }

    private fun windowScanLine() {
        if (windowY <= lineY) {
            //Find offset within tile map
            //mapY=lineY - windowY
            val mapOffset = ((lineY - windowY) / 8u) * 32u + if (readRegBit(
                    lcdCtrl,
                    LCD_WINDOW_MAP_SELECT
                )
            ) 0x1C00u else 0x1800u
            var tileMapOffset = 0u
            val tileY = (lineY - windowY) and 7u
            var lineX = 0
            while (lineX < 160) {
                if (windowX <= (lineX.toUInt() + 7u)) {
                    //mapX=lineX+7-windowX
                    var tileX = (lineX + 7 - windowX.toInt()) and 7
                    val tile = vram[(mapOffset + tileMapOffset).toInt()].toUByte()
                    val tileOffset =
                        if (!readRegBit(
                                lcdCtrl,
                                LCD_BACKGROUND_WINDOW_ADDRESS_MODE_SELECT
                            ) and (tile < 128u)
                        )
                            256u
                        else 0u
                    val lowByte =
                        vram[((tile + tileOffset) * 16u + tileY * 2u).toInt()].toUShort()
                    val highByte =
                        vram[((tile + tileOffset) * 16u + tileY * 2u + 1u).toInt()].toUShort()
                    while (tileX < 8 && lineX < 160) {
                        bgLine[lineX] =
                            (((lowByte.toUInt() shr (7 - tileX)) and 1u) + 2u * ((highByte.toUInt() shr (7 - tileX)) and 1u)).toByte()
                        scannedLine[lineX] = getBGPaletteColor(bgLine[lineX])
                        tileX++
                        lineX++
                    }
                    tileMapOffset++
                } else
                    lineX++
            }
        }
    }

    private fun spriteScanLine() {
        while (spriteLength-- > 0) {
            val ind = activeSpriteIndex[spriteLength]
            val y: UByte = oam[ind * 4].toUByte()
            val flags: UByte = oam[ind * 4 + 3].toUByte()
            val tileNo: UByte = oam[ind * 4 + 2].toUByte()
            val lowByte = if (readRegBit(flags, OAM_Y_FLIP))
                vram[(tileNo * 16u + (spriteHeight + y - lineY - 17u) * 2u).toInt()].toUShort()
            else vram[(tileNo * 16u + (lineY - y + 16u) * 2u).toInt()].toUShort()
            val highByte = if (readRegBit(flags, OAM_Y_FLIP))
                vram[(tileNo * 16u + ((spriteHeight + y - lineY - 17u) * 2u) + 1u).toInt()].toUShort()
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
                    if (row[it] > 0 && (x - 8u + it.toUInt()).toInt() in 0 until 160) {
                        if (readRegBit(flags, OAM_SPRITE_PRIORITY)) {//Behind Background
                            if (bgLine[(x - 8u + it.toUInt()).toInt()].toInt() == 0)
                                scannedLine[(x - 8u + it.toUInt()).toInt()] =
                                    getObjPaletteColor(objPal, row[it]).toByte()
                        } else  //Foreground*/
                            scannedLine[(x - 8u + it.toUInt()).toInt()] =
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

    fun dumpOAMasText(): String {
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

    fun dumpVRAM(): ByteArray {
        gameCanvas.drawColor(Color.rgb(0, 0, 0))
        gameCanvas.drawText("DUMPING VRAM", 1500f, 90f, painter[0])
        scrImage.invalidate()
        val log = "CTRL=0x" + String.format("%02X", lcdCtrl.toByte()) + "|STAT=0x" +
                String.format("%02X", status.toByte()) + "|SCY=0x" +
                String.format("%02X", scrollY.toByte()) + "|SCX=0x" +
                String.format("%02X", status.toByte()) + "|WY=0x" +
                String.format("%02X", windowY.toByte()) + "|WX=0x" +
                String.format("%02X", windowX.toByte()) + "|LY=0x" +
                String.format("%02X", lineY.toByte()) + "|BGPAL=0x" +
                String.format("%02X", bgPalette.toByte()) + "|OBJ0=0x" +
                String.format("%02X", objPalette.toByte()) + "|OBJ1=0x" +
                String.format("%02X", objPalette1.toByte())
        Log.i("GPU", log)
        gameCanvas.drawColor(Color.rgb(0, 0, 0))
        return vram
    }

    fun dumpMeta(): ByteArray {
        val meta = ByteArray(11)
        meta[0] = bgPalette.toByte()
        meta[1] = objPalette.toByte()
        meta[2] = objPalette1.toByte()
        meta[3] = spriteHeight.toByte()
        meta[4] = scrollX.toByte()
        meta[5] = scrollY.toByte()
        meta[6] = windowX.toByte()
        meta[7] = windowY.toByte()
        meta[8] = if (readRegBit(lcdCtrl, LCD_BACKGROUND_MAP_SELECT)) 0x1C else 0x18
        meta[9] = if (readRegBit(lcdCtrl, LCD_WINDOW_MAP_SELECT)) 0x1C else 0x18
        meta[10] = if (!readRegBit(lcdCtrl, LCD_BACKGROUND_WINDOW_ADDRESS_MODE_SELECT)) 1 else 0
        return meta
    }

    fun dumpOAM(): ByteArray {
        return oam
    }
}