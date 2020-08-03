package com.example.playgb

class Cpu(val rom: ByteArray) {
    private var pc: UShort = 0u
    private var sp: UShort = 0u
    private var m = 0u
    private var time = 0u
    private var a: UByte = 0u
    private var f: UByte = 0u
    private var h: UByte = 0u
    private var l: UByte = 0u
    private var vram: ByteArray = ByteArray(8192)

    fun fetch(): UByte {
        return rom[pc++.toInt()].toUByte()
    }

    fun fetch2(): UShort {
        return (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
    }

    fun log(): String {
        return "PC=${pc}|SP=${sp}|A=0x" +
                String.format("%02X", a.toByte()) + "|F=0x" + String.format("%02X", f.toByte()) +
                "|H=0x" + String.format("%02X", h.toByte()) + "|L=0x" + String.format(
            "%02X",
            l.toByte()
        ) + "|EmuTime=${time} us"
    }

    // TODO: 4/8/20  Add looping for instructions

    fun execute(): String {
        val op = fetch()
        var msg = "0x" + String.format("%02X", op.toByte())
        when (op.toInt()) {
            0x20 -> {
                jumpRel("nz")
                msg += "|JR NZ,8"
            }
            0x21 -> {
                loadhlu16()
                msg += "|LD HL,u16"
            }
            0x31 -> {
                loadSPu16()
                msg += "|LD SP,u16"
            }
            0x32 -> {
                loadDecHLa()
                msg += "|LDD [HL],A"
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
                    else -> msg += "|Unknown Suffix OP"
                }
            }
            else -> msg += "|Unknown OP"
        }
        time += m * 4u
        return msg
    }

    private fun jumpRel(s: String) {
        when (s) {
            "nz" -> {
                val temp = rom[pc++.toInt()]
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

    private fun loadDecHLa() {
        writeu8(gethl(), a)
        sethl(gethl() - 1u)
        m = 2u
    }

    private fun writeu8(address: UShort, data: UByte) {
        when ((address and 0xF000u).toUInt()) {
            0x8000u, 0x9000u -> vram[(address and 0x1FFFu).toInt()]
        }
    }

    private fun sethl(hl: UInt) {
        l = hl.toUByte()
        h = (hl shr 8).toUByte()
    }


    private fun gethl(): UShort {
        return (l.toInt() or (h.toInt() shl 8)).toUShort()
    }

    private fun loadhlu16() {
        l = fetch()
        h = fetch()
        m = 3u
    }

    private fun xorA() {
        a = 0u
        f = 0x80.toUByte()
        m = 1u
    }

    private fun loadSPu16() {
        sp = fetch2()
        m = 3u
    }
}