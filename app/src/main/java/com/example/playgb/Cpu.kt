package com.example.playgb

import android.util.Log

class Cpu(val bios: ByteArray, val rom: ByteArray) {
    private var pc: UShort = 0u
    private var sp: UShort = 0u
    private var a: UByte = 0u
    private var c: UByte = 0u
    private var d: UByte = 0u
    private var e: UByte = 0u
    private var f: UByte = 0u
    private var h: UByte = 0u
    private var l: UByte = 0u

    private var biosFlag = true
    private var m = 0u
    private var no = 0u
    private var time = 0u

    // TODO: 4/8/20 Replace vram and gpuCtrl with GPU module for graphics management
    private var vram = ByteArray(8192)
    private var gpuCtrl = ByteArray(11)

    // TODO: 4/8/20 Replace apuCtrl with APU module for sound management
    private var apuCtrl = ByteArray(48)

    private fun fetch(): UByte {
        return read8(pc++)
    }

    private fun fetch2(): UShort {
        return (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
    }

    private fun read8(address: UShort): UByte {
        when ((address and 0xF000u).toInt()) {
            0x0000 -> {
                if (biosFlag and (address < 0x0100u))
                    return bios[address.toInt()].toUByte()
                return rom[address.toInt()].toUByte()
            }
            0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x7000 -> return rom[address.toInt()].toUByte()
            0x8000, 0x9000 -> return vram[(address and 0x1FFFu).toInt()].toUByte()
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xF00 -> {
                        if (address < 0xFF80u)
                            when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> return apuCtrl[(address and 0xFFu).toInt()].toUByte()
                                0x40 -> return gpuCtrl[(address and 0xFu).toInt()].toUByte()
                                else -> Log.w("GB.mmu", "Access Requested to ${address}")
                            }
                        else Log.w("GB.mmu", "Access Requested to ${address}")
                    }
                    else -> Log.w("GB.mmu", "Access Requested to ${address}")
                }
            }
            else -> Log.w("GB.mmu", "Access Requested to ${address}")
        }
        return 0u
    }

    private fun writeu8(address: UShort, data: UByte) {
        when ((address and 0xF000u).toInt()) {
            0x8000, 0x9000 -> vram[(address and 0x1FFFu).toInt()] = data.toByte()
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xF00 -> {
                        if (address < 0xFF80u)
                            when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> apuCtrl[(address and 0xFFu).toInt()] =
                                    data.toByte()
                                0x40 -> gpuCtrl[(address and 0xFu).toInt()] = data.toByte()
                                else -> Log.w("GB.mmu", "Access Requested to ${address}")
                            }
                        else Log.w("GB.mmu", "Access Requested to ${address}")
                    }
                    else -> Log.w("GB.mmu", "Access Requested to ${address}")
                }
            }
            else -> Log.w("GB.mmu", "Access Requested to ${address}")
        }
    }

    fun log(): String {
        var log = "PC=0x" + String.format("%02X", (pc.toUInt() shr 8).toByte()) +
                String.format("%02X", pc.toByte()) + "|SP=0x" +
                String.format("%02X", (sp.toUInt() shr 8).toByte()) +
                String.format("%02X", sp.toByte()) + "|A=0x" +
                String.format("%02X", a.toByte()) + "|C=0x" +
                String.format("%02X", c.toByte()) + "|D=0x" +
                String.format("%02X", d.toByte()) + "|E=0x" +
                String.format("%02X", e.toByte()) + "|F=0x" +
                String.format("%02X", f.toByte()) + "|H=0x" + String.format("%02X", h.toByte()) +
                "|L=0x" + String.format("%02X", l.toByte()) + "|Stepped ${no} times|EmuTime="
        if (time > 10000u)
            log += "${time / 1000u}ms"
        else
            log += "${time}us"
        return log
    }

    // TODO: 4/8/20  Add looping for instructions

    fun execute(): String {
        val op = fetch()
        var msg = "0x" + String.format("%02X", op.toByte())
        no++
        when (op.toInt()) {
            0x0C -> {
                incc()
                msg += "|INC C"
            }
            0x0E -> {
                loadc_u8()
                msg += "|LD C,u8"
            }
            0x11 -> {
                loadde_u16()
                msg += "|LD DE,u16"
            }
            0x1A -> {
                loada_DE()
                msg += "|LD A,[DE]"
            }
            0x20 -> {
                jumpRel("nz")
                msg += "|JR NZ,i8"
            }
            0x21 -> {
                loadhl_u16()
                msg += "|LD HL,u16"
            }
            0x3E -> {
                loada_u8()
                msg += "|LD A,u8"
            }
            0x31 -> {
                loadsp_u16()
                msg += "|LD SP,u16"
            }
            0x32 -> {
                loadDecHL_a()
                msg += "|LDD [HL],A"
            }
            0x77 -> {
                loadHL_a()
                msg += "|LD [HL],A"
            }
            0xAF -> {
                xorA()
                msg += "|XOR A"
            }
            0xCB -> {
                msg += "|Prefix CB"
                val op2 = fetch()
                msg += "|0x" + String.format("%02X", op2.toByte())
                when (op2.toInt()) {
                    0x7C -> {
                        bitCheck(h, 7)
                        msg += "|BIT 7,H"
                    }
                    else -> {
                        pc--
                        msg += "|Unknown Suffix OP"
                    }
                }
            }
            0xCD -> {
                callu16()
                msg += "|CALL u16"
            }
            0xE0 -> {
                loadFFu8_a()
                msg += "|LD [FF00+u8],A"
            }
            0xE2 -> {
                loadFFC_a()
                msg += "|LD [FF00+C],A"
            }
            else -> {
                pc--
                msg += "|Unknown OP"
                return msg
            }
        }
        time += m * 4u
        return msg
    }

    private fun callu16() {
        val newpc = (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
        writeu8(--sp, pc.toUByte())
        writeu8(--sp, (pc.toUInt() shr 8).toUByte())
        pc = newpc
        m = 6u
    }

    private fun loada_DE() {
        a = read8(getde())
        m = 2u
    }

    private fun getde(): UShort {
        return (e.toInt() or (d.toInt() shl 8)).toUShort()
    }

    private fun loadde_u16() {
        e = fetch()
        d = fetch()
        m = 3u
    }

    private fun loadFFu8_a() {
        writeu8((0xFF00u + fetch()).toUShort(), a)
        m = 3u
    }

    private fun loadHL_a() {
        writeu8(gethl(), a)
        m = 2u
    }

    private fun incc() {
        f = f and 0x10u
        if ((c and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        c++
        if (c.equals(0u))
            f = f or 0x80u
        m = 1u
    }

    private fun loadFFC_a() {
        writeu8((0xFF00u + c).toUShort(), a)
        m = 2u
    }

    private fun loada_u8() {
        a = fetch()
        m = 2u
    }

    private fun loadc_u8() {
        c = fetch()
        m = 2u
    }

    private fun jumpRel(s: String) {
        when (s) {
            "nz" -> {
                val temp = bios[pc++.toInt()]
                m = 2u
                if ((f and 0x80u).toUInt() == 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m += 1u
                }
            }
        }
    }

    private fun bitCheck(byte: UByte, i: Int) {
        f = f and 0x10u   //preserve carry
        f = f or 0x20u    //set half carry
        if (((byte.toUInt() shr i) and 1u) == 0u)
            f = f or 0x80u    //set zero
        m = 2u
    }

    private fun loadDecHL_a() {
        writeu8(gethl(), a)
        sethl(gethl() - 1u)
        m = 2u
    }

    private fun sethl(hl: UInt) {
        l = hl.toUByte()
        h = (hl shr 8).toUByte()
    }


    private fun gethl(): UShort {
        return (l.toInt() or (h.toInt() shl 8)).toUShort()
    }

    private fun loadhl_u16() {
        l = fetch()
        h = fetch()
        m = 3u
    }

    private fun xorA() {
        a = 0u
        f = 0x80.toUByte()
        m = 1u
    }

    private fun loadsp_u16() {
        sp = fetch2()
        m = 3u
    }
}