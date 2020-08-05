package com.example.playgb

import android.util.Log

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
class Gpu {
    private var objPalette2: UByte = 0u
    private var objPalette1: UByte = 0u
    private var bgPalette: UByte = 0u
    private var windowX: UByte = 0u
    private var windowY: UByte = 0u
    private var lineYcompare: UByte = 0u
    private var lineY: UByte = 0u
    private var status: UByte = 0u
    private var scrollY: UByte = 0u
    private var scrollX: UByte = 0u
    private var vram = ByteArray(8192)
    private var lcdCtrl: UByte = 0u//(11)
    fun readFromVRam(address: UShort): UByte {
        Log.i("GB.mmu", "VRam read ${address}")
        return vram[(address and 0x1FFFu).toInt()].toUByte()
    }

    fun writeToVRam(address: UShort, data: UByte) {
        Log.i("GB.mmu", "VRam write ${address}")
        vram[(address and 0x1FFFu).toInt()] = data.toByte()
    }

    fun write(address: UShort, data: UByte) {
        Log.i("GB.mmu", "GPU write ${address}")
        when (address.toInt()) {
            0xFF40 -> lcdCtrl = data
            0xFF41 -> status = data
            0xFF42 -> scrollY = data
            0xFF43 -> scrollX = data
            //0xFF44 -> lineY//read-only
            0xFF45 -> lineYcompare = data
            0xFF47 -> bgPalette = data//writeonly
            0xFF48 -> objPalette1 = data//writeonly
            0xFF49 -> objPalette2 = data//writeonly
            0xFF4A -> windowY = data
            0xFF4B -> windowX = data
            else -> Log.e("GB.mmu", "Access ${address}?")
        }
    }

    fun read(address: UShort): UByte {
        Log.i("GB.mmu", "GPU read ${address}")
        return when (address.toInt()) {
            0xFF40 -> lcdCtrl
            0xFF41 -> status
            0xFF42 -> scrollY
            0xFF43 -> scrollX
            0xFF44 -> lineY//read-only
            0xFF45 -> lineYcompare
            //0xFF47->bgPalette//writeonly
            //0xFF48->objPalette1//writeonly
            //0xFF49->objPalette2//writeonly
            0xFF4A -> windowY
            0xFF4B -> windowX
            else -> {
                Log.e("GB.mmu", "Access ${address}?")
                0u
            }
        }
    }
}