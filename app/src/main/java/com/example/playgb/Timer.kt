package com.example.playgb

import android.util.Log
import java.util.*

private const val TIMER_ENABLE = 0b100

@ExperimentalUnsignedTypes
class Timer {
    private var divReg: UByte = 0u
    private var countReg: UByte = 0u
    private var modReg: UByte = 0u
    private var ctrlReg: UByte = 0u
    private var clockTrigger = 0
    private var int50 = false
    private var divPrevTime = 1
    private var prevTime = 1
    private var rtcFlags: UByte = 0u
    private lateinit var latchedTime: Date

    fun timePassed(time: Int) {
        repeat(time) {
            if ((divPrevTime + it) % 256 == 0)
                divReg++
            if ((ctrlReg.toInt() and TIMER_ENABLE) != 0) {
                if ((prevTime + it) % clockTrigger == 0)
                    if (countReg + 1u > 0xFFu) {
                        int50 = true
                        countReg = modReg
                    } else
                        countReg++
            }
        }
        divPrevTime += time
        if ((ctrlReg.toInt() and TIMER_ENABLE) != 0)
            prevTime += time
    }

    fun write(address: UShort, data: UByte) {
        when ((address and 0xFu).toInt()) {
            4 -> {
                Log.i("TMR", "DIV reset")
                divReg = 0u
                divPrevTime = 1
            }
            5 -> {
                Log.i("TMR", "Counter write $data")
                countReg = data
            }
            6 -> {
                if (data > 0u)
                    Log.i("TMR", "Modulo write $data")
                modReg = data
            }
            7 -> {
                if (data > 0u)
                    Log.i("TMR", "Control write $data")
                ctrlReg = data and 7u
                clockTrigger = when ((data and 3u).toInt()) {
                    1 -> 16
                    2 -> 64
                    3 -> 256
                    else -> 1024
                }
                if (data.toInt() and TIMER_ENABLE != 0)
                    prevTime = 1
            }
        }
    }

    fun hasOverflowOccurred(): Boolean {
        if (int50) {
            int50 = false
            return true
        }
        return false
    }

    fun read(address: UShort): UByte {
        return when (address) {
            0xFF04.toUShort() -> divReg
            0xFF05.toUShort() -> countReg
            0xFF06.toUShort() -> modReg
            else -> ctrlReg
        }
    }

    fun latchClockData() {
        // TODO: 24/8/20 When Day Counter overflows Carry flag should be set
        latchedTime = Date()
    }
}