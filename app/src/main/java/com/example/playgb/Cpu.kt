package com.example.playgb

import android.util.Log

class Cpu(val bios: ByteArray, val rom: ByteArray) {
    private var pc: UShort = 0u
    private var sp: UShort = 0u

    //Registers
    private var a: UByte = 0u
    private var b: UByte = 0u
    private var c: UByte = 0u
    private var d: UByte = 0u
    private var e: UByte = 0u
    private var f: UByte = 0u
    private var h: UByte = 0u
    private var l: UByte = 0u

    //Additional Ram
    private var hram = ByteArray(128)

    private var biosFlag = true
    private var m = 0u
    private var no = 0u
    private var time = 0u

    // TODO: 4/8/20 Improve GPU module for graphics management
    private var gpu = Gpu()

    // TODO: 4/8/20 Improve APU module for sound management
    private var apu = Apu()

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
            0x8000, 0x9000 -> return gpu.readFromVRam(address)
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xF00 -> {
                        if (address < 0xFF80u)
                            when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> return apu.read(address)
                                0x40 -> return gpu.read(address)
                                else -> Log.w("GB.mmu", "Access Requested to ${address}")
                            }
                        else
                            return hram[(address and 0x7Fu).toInt()].toUByte()
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
            0x8000, 0x9000 -> gpu.writeToVRam(address, data)
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xF00 -> {
                        if (address == 0xFF46.toUShort())
                            dmaTransfer(data)
                        else if (address < 0xFF80u)
                            when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> apu.write(address, data)
                                0x40 -> gpu.write(address, data)
                                else -> Log.w("GB.mmu", "Access Requested to ${address}")
                            }
                        else hram[(address and 0x7Fu).toInt()] = data.toByte()
                    }
                    else -> Log.w("GB.mmu", "Access Requested to ${address}")
                }
            }
            else -> Log.w("GB.mmu", "Access Requested to ${address}")
        }
    }

    private fun dmaTransfer(data: UByte) {
        TODO("Implement dma transfer from rom to oam")
    }

    fun log(): String {
        var log = "PC=0x" + String.format("%02X", (pc.toUInt() shr 8).toByte()) +
                String.format("%02X", pc.toByte()) + "|SP=0x" +
                String.format("%02X", (sp.toUInt() shr 8).toByte()) +
                String.format("%02X", sp.toByte()) + "|A=0x" +
                String.format("%02X", a.toByte()) + "|B=0x" +
                String.format("%02X", b.toByte()) + "|C=0x" +
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
            0x05 -> {
                decb()
                msg += "|DEC B"
            }
            0x06 -> {
                loadb_u8()
                msg += "|LD B,u8"
            }
            0x0C -> {
                incc()
                msg += "|INC C"
            }
            0x0D -> {
                decc()
                msg += "|DEC C"
            }
            0x0E -> {
                loadc_u8()
                msg += "|LD C,u8"
            }
            0x11 -> {
                loadde_u16()
                msg += "|LD DE,u16"
            }
            0x13 -> {
                incde()
                msg += "|INC DE"
            }
            0x17 -> {
                rotateLefta()
                msg += "|RLA"
            }
            0x18 -> {
                jumpRel("")
                msg += "|JR i8"
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
            0x22 -> {
                loadIncHL_a()
                msg += "|LDI [HL],A"
            }
            0x23 -> {
                inchl()
                msg += "|INC HL"
            }
            0x28 -> {
                jumpRel("z")
                msg += "|JR Z,i8"
            }
            0x2E -> {
                loadl_u8()
                msg += "|LD L,u8"
            }
            0x31 -> {
                loadsp_u16()
                msg += "|LD SP,u16"
            }
            0x32 -> {
                loadDecHL_a()
                msg += "|LDD [HL],A"
            }
            0x3D -> {
                deca()
                msg += "|DEC A"
            }
            0x3E -> {
                loada_u8()
                msg += "|LD A,u8"
            }
            0x4F -> {
                loadc_a()
                msg += "|LD C,A"
            }
            0x7B -> {
                loada_e()
                msg += "|LD A,E"
            }
            0x77 -> {
                loadHL_a()
                msg += "|LD [HL],A"
            }
            0xAF -> {
                xorA()
                msg += "|XOR A"
            }
            0xC1 -> {
                popbc()
                msg += "|POP BC"
            }
            0xC5 -> {
                pushbc()
                msg += "|PUSH BC"
            }
            0xC9 -> {
                ret()
                msg += "|RET"
            }
            0xCB -> {
                msg += "|Prefix CB"
                val op2 = fetch()
                msg += "|0x" + String.format("%02X", op2.toByte())
                when (op2.toInt()) {
                    0x11 -> {
                        rotateLeftc()
                        msg += "|RL C"
                    }
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
            0xEA -> {
                loadAtu16_a()
                msg += "|LD [u16],A"
            }
            0xFE -> {
                comparea_u8()
                msg += "|CP A,u8"
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

    private fun loadl_u8() {
        l = fetch()
        m = 2u
    }

    private fun decc() {
        val res: UByte = (c - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((c and 0xFu) - 1u < 0u)
            f = f or 0x20u
        c = res
        m = 1u
    }

    private fun deca() {
        val res: UByte = (a - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((a and 0xFu) - 1u < 0u)
            f = f or 0x20u
        a = res
        m = 1u
    }

    private fun loadAtu16_a() {
        writeu8((fetch().toInt() or (fetch().toInt() shl 8)).toUShort(), a)
        m = 4u
    }

    private fun comparea_u8() {
        f = 0x40u
        val lit = fetch()
        val res = (a - lit).toInt()
        if ((res and 0xFF) == 0)
            f = f or 0x80u
        if (res < 0)
            f = f or 0x10u
        if ((a and 0xFu) - (lit and 0xFu) < 0u)
            f = f or 0x20u
        m = 2u
    }

    private fun loada_e() {
        a = e
        m = 1u
    }

    private fun incde() {
        setde(getde() + 1u)
        m = 2u
    }

    private fun setde(de: UInt) {
        e = de.toUByte()
        d = (de shr 8).toUByte()
    }

    private fun ret() {
        pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
        m = 4u
    }

    private fun inchl() {
        sethl(gethl() + 1u)
        m = 2u
    }

    private fun loadIncHL_a() {
        writeu8(gethl(), a)
        sethl(gethl() + 1u)
        m = 2u
    }

    private fun decb() {
        val res: UByte = (b - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((b and 0xFu) - 1u < 0u)
            f = f or 0x20u
        b = res
        m = 1u
    }

    private fun popbc() {
        c = read8(sp++)
        b = read8(sp++)
        m = 3u
    }

    private fun rotateLefta() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (carry)
            a++
        //Doubt if m=2
        m = 1u
    }

    private fun rotateLeftc() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((c and 0x80u).toUInt() shr 3).toUByte()
        c = (c.toInt() shl 1).toUByte()
        if (carry)
            c++
        if (c == 0.toUByte())
            f = f or 0x80u
        m = 2u
    }

    private fun pushbc() {
        writeu8(--sp, b)
        writeu8(--sp, c)
        m = 4u
    }

    private fun loadb_u8() {
        b = fetch()
        m = 2u
    }

    private fun loadc_a() {
        c = a
        m = 1u
    }

    private fun callu16() {
        val newpc = (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
        writeu8(--sp, (pc.toUInt() shr 8).toUByte())
        writeu8(--sp, pc.toUByte())
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
        if (c == 0.toUByte())
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
        val temp = when (biosFlag) {
            true -> bios[pc++.toInt()]
            false -> rom[pc++.toInt()]
        }
        m = 2u
        when (s) {
            "nz" -> {
                if ((f and 0x80u).toUInt() == 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m += 1u
                }
            }
            "z" -> {
                if ((f and 0x80u).toUInt() != 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m += 1u
                }
            }
            "" -> {
                pc = (pc.toInt() + temp).toUShort()
                m += 1u
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