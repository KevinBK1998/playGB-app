package com.example.playgb

import android.util.Log

@ExperimentalUnsignedTypes
class Cpu(private val bios: ByteArray, private val rom: ByteArray, private var gpu: Gpu) {
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

    //Flags for Programming
    private var cpuCrash = false
    private var biosFlag = true
    private var m = 0u
    private var no = 0u
    private var time = 0u

    // TODO: 8/8/20 Fix GPU module
    //private var gpu = Gpu()

    // TODO: 7/8/20 Study APU working and implement it
    private var apu = Apu()

    private fun fetch(): UByte {
        return read8(pc++)
    }

    private fun fetchTwice(): UShort {
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
                                else -> Log.w("GB.mmu", "Access Requested to $address")
                            }
                        else
                            return hram[(address and 0x7Fu).toInt()].toUByte()
                    }
                    else -> Log.w("GB.mmu", "Access Requested to $address")
                }
            }
            else -> Log.w("GB.mmu", "Access Requested to $address")
        }
        return 0u
    }

    private fun writeU8(address: UShort, data: UByte) {
        when ((address and 0xF000u).toInt()) {
            0x8000, 0x9000 -> gpu.writeToVRam(address, data)
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xF00 -> {
                        when {
                            address == 0xFF46.toUShort() -> dmaTransfer(data)
                            address < 0xFF80u -> when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> apu.write(address, data)
                                0x40 -> gpu.write(address, data)
                                else -> Log.w("GB.mmu", "Access Requested to $address")
                            }
                            else -> hram[(address and 0x7Fu).toInt()] = data.toByte()
                        }
                    }
                    else -> Log.w("GB.mmu", "Access Requested to $address")
                }
            }
            else -> Log.w("GB.mmu", "Access Requested to $address")
        }
    }

    private fun dmaTransfer(data: UByte) {
        TODO("Implement dma transfer $data from rom to oam")
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
                "|L=0x" + String.format("%02X", l.toByte()) + "|Stepped $no times|EmuTime="
        log += if (time > 10000u) "${time / 1000u}ms" else "${time}us"
        return log
    }
    fun execute(): String {
        var msg = "Last OP"
        val op = fetch()
        msg += "|0x" + String.format("%02X", op.toByte())
        no++
        when (op.toInt()) {
            0x04 -> {
                incB()
                msg += "|INC B"
            }
            0x05 -> {
                decB()
                msg += "|DEC B"
            }
            0x06 -> {
                loadU8toB()
                msg += "|LD B,u8"
            }
            0x0C -> {
                incC()
                msg += "|INC C"
            }
            0x0D -> {
                decC()
                msg += "|DEC C"
            }
            0x0E -> {
                loadU8toC()
                msg += "|LD C,u8"
            }
            0x11 -> {
                loadU16toDE()
                msg += "|LD DE,u16"
            }
            0x13 -> {
                incDE()
                msg += "|INC DE"
            }
            0x17 -> {
                rotateLeftA()
                msg += "|RLA"
            }
            0x18 -> {
                jumpRel("")
                msg += "|JR i8"
            }
            0x1A -> {
                loadValueAtDEtoA()
                msg += "|LD A,[DE]"
            }
            0x1E -> {
                loadU8toE()
                msg += "|LD E,u8"
            }
            0x20 -> {
                jumpRel("nz")
                msg += "|JR NZ,i8"
            }
            0x21 -> {
                loadU16toHL()
                msg += "|LD HL,u16"
            }
            0x22 -> {
                loadAtHLvalueOfAThenIncHL()
                msg += "|LDI [HL],A"
            }
            0x23 -> {
                incHL()
                msg += "|INC HL"
            }
            0x28 -> {
                jumpRel("z")
                msg += "|JR Z,i8"
            }
            0x2E -> {
                loadU8toL()
                msg += "|LD L,u8"
            }
            0x31 -> {
                loadU16toSP()
                msg += "|LD SP,u16"
            }
            0x32 -> {
                loadAtHLValueOfAThenDecHL()
                msg += "|LDD [HL],A"
            }
            0x3D -> {
                decA()
                msg += "|DEC A"
            }
            0x3E -> {
                loadU8toA()
                msg += "|LD A,u8"
            }
            0x4F -> {
                loadAtoC()
                msg += "|LD C,A"
            }
            0x57 -> {
                loadAtoD()
                msg += "|LD D,A"
            }
            0x67 -> {
                loadAtoH()
                msg += "|LD H,A"
            }
            0x7B -> {
                loadEtoA()
                msg += "|LD A,E"
            }
            0x77 -> {
                loadAtoAddressHL()
                msg += "|LD [HL],A"
            }
            0xAF -> {
                xorA()
                msg += "|XOR A"
            }
            0xC1 -> {
                popBC()
                msg += "|POP BC"
            }
            0xC5 -> {
                pushBC()
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
                        rotateLeftC()
                        msg += "|RL C"
                    }
                    0x7C -> {
                        bitCheck(h, 7)
                        msg += "|BIT 7,H"
                    }
                    else -> {
                        pc--
                        msg += "|Unknown Suffix OP"
                        cpuCrash = true
                    }
                }
            }
            0xCD -> {
                callU16()
                msg += "|CALL u16"
            }
            0xE0 -> {
                loadAtoAddressFFU8()
                msg += "|LD [FF00+u8],A"
            }
            0xE2 -> {
                loadAtoAddressFFCc()
                msg += "|LD [FF00+C],A"
            }
            0xEA -> {
                loadAtU16ValueOfA()
                msg += "|LD [u16],A"
            }
            0xF0 -> {
                loadValueAtFFU8toA()
                msg += "|LD A,[FF+u8]"
            }
            0xFE -> {
                compareU8andA()
                msg += "|CP A,u8"
            }
            else -> {
                pc--
                msg += "|Unknown OP"
                cpuCrash = true
                return msg
            }
        }
        val t = 4u * m
        time += t
        gpu.timePassed(t.toInt())
        return msg
    }

    //    fun hasNotCrashed(): Boolean {
//        return !cpuCrash
//    }
    private fun loadU8toL() {
        l = fetch()
        m = 2u
    }

    private fun loadAtU16ValueOfA() {
        writeU8((fetch().toInt() or (fetch().toInt() shl 8)).toUShort(), a)
        m = 4u
    }

    private fun compareU8andA() {
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

    private fun ret() {
        pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
        m = 4u
    }

    private fun rotateLeftA() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (carry)
            a++
        //Doubt if m=2
        m = 1u
    }

    private fun rotateLeftC() {
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

    private fun popBC() {
        c = read8(sp++)
        b = read8(sp++)
        m = 3u
    }

    private fun pushBC() {
        writeU8(--sp, b)
        writeU8(--sp, c)
        m = 4u
    }

    private fun loadAtoH() {
        h = a
        m = 1u
    }

    private fun loadEtoA() {
        a = e
        m = 1u
    }

    private fun loadAtoC() {
        c = a
        m = 1u
    }

    private fun loadAtoD() {
        d = a
        m = 1u
    }

    private fun callU16() {
        val newPC = (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
        writeU8(--sp, (pc.toUInt() shr 8).toUByte())
        writeU8(--sp, pc.toUByte())
        pc = newPC
        m = 6u
    }

    private fun decB() {
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

    private fun decC() {
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

    private fun decA() {
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

    private fun incDE() {
        setDE(getDE() + 1u)
        m = 2u
    }

    private fun incHL() {
        setHL(getHL() + 1u)
        m = 2u
    }

    private fun incB() {
        f = f and 0x10u
        if ((b and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        b++
        if (b == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun incC() {
        f = f and 0x10u
        if ((c and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        c++
        if (c == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun loadAtoAddressFFU8() {
        writeU8((0xFF00u + fetch()).toUShort(), a)
        m = 3u
    }

    private fun loadAtoAddressFFCc() {
        writeU8((0xFF00u + c).toUShort(), a)
        m = 2u
    }

    private fun loadValueAtFFU8toA() {
        a = read8((0xFF00u + fetch()).toUShort())
        m = 3u
    }

    private fun loadU8toB() {
        b = fetch()
        m = 2u
    }

    private fun loadU8toA() {
        a = fetch()
        m = 2u
    }

    private fun loadU8toC() {
        c = fetch()
        m = 2u
    }

    private fun loadU8toE() {
        e = fetch()
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

    private fun setDE(de: UInt) {
        e = de.toUByte()
        d = (de shr 8).toUByte()
    }

    private fun setHL(hl: UInt) {
        l = hl.toUByte()
        h = (hl shr 8).toUByte()
    }

    private fun getHL(): UShort {
        return (l.toInt() or (h.toInt() shl 8)).toUShort()
    }

    private fun xorA() {
        a = 0u
        f = 0x80.toUByte()
        m = 1u
    }

    private fun getDE(): UShort {
        return (e.toInt() or (d.toInt() shl 8)).toUShort()
    }

    private fun loadValueAtDEtoA() {
        a = read8(getDE())
        m = 2u
    }

    private fun loadAtoAddressHL() {
        writeU8(getHL(), a)
        m = 2u
    }

    private fun loadAtHLvalueOfAThenIncHL() {
        writeU8(getHL(), a)
        setHL(getHL() + 1u)
        m = 2u
    }

    private fun loadAtHLValueOfAThenDecHL() {
        writeU8(getHL(), a)
        setHL(getHL() - 1u)
        m = 2u
    }

    private fun loadU16toDE() {
        e = fetch()
        d = fetch()
        m = 3u
    }

    private fun loadU16toHL() {
        l = fetch()
        h = fetch()
        m = 3u
    }

    private fun loadU16toSP() {
        sp = fetchTwice()
        m = 3u
    }
}