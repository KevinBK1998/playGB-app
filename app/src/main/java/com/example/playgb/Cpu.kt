package com.example.playgb

import android.util.Log

private const val NOT_ZERO = 0
private const val ZERO = 1
private const val NO_CARRY = 2
private const val CARRY = 3
private const val ENABLE_INTERRUPTS = 4
const val DIRECTION_RIGHT_BUTTON_A = 0
const val DIRECTION_LEFT_BUTTON_B = 1
const val DIRECTION_UP_BUTTON_SELECT = 2
const val DIRECTION_DOWN_BUTTON_START = 3

@ExperimentalUnsignedTypes
class Cpu(
    private val bios: ByteArray,
    private val rom: ByteArray,
    private var gpu: Gpu,
    private var apu: Apu,
    val joyPad: BooleanArray
) {
    //System Registers
    private var pc: UShort = 0u
    private var sp: UShort = 0u
    private var tmr = Timer()

    //Registers
    private var a: UByte = 0u
    private var b: UByte = 0u
    private var c: UByte = 0u
    private var d: UByte = 0u
    private var e: UByte = 0u
    private var f: UByte = 0u
    private var h: UByte = 0u
    private var l: UByte = 0u

    //Ram
    private var workRam = ByteArray(8192)
    private var extraRam = ByteArray(8192)
    private var highSpeedRam = ByteArray(127)

    //Flags for Programming
    private var cpuCrash = false
    private var readBios = true
    private var interruptsMasterEnabled = true
    private var interruptFlag: UByte = 0u
    private var interruptEnable: UByte = 0u
    private var cartType = 0
    private var romBankNumber: Byte = 0
    private var ramBankNumber: Byte = 0
    private var ramBankMode = false
    private var padButtonMode = false
    private var padDirectionMode = false
    private var m = 0u
    private var time = 0u
    private fun toPadBit(bitNo: Int): Int {
        return (if (joyPad[bitNo + if (padDirectionMode) 0 else 4]) 0 else 1) shl bitNo
    }

    private fun getJoyPad(): UByte {
        return if (padDirectionMode || padButtonMode) {
            (toPadBit(DIRECTION_DOWN_BUTTON_START) or toPadBit(DIRECTION_UP_BUTTON_SELECT) or
                    toPadBit(DIRECTION_LEFT_BUTTON_B) or toPadBit(DIRECTION_RIGHT_BUTTON_A)).toUByte()
        } else
            0xFFu
    }

    private fun fetch(): UByte {
        return read8(pc++)
    }

    private fun fetchTwice(): UShort {
        return (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
    }

    private fun read8(address: UShort): UByte {
        when ((address and 0xF000u).toInt()) {
            0x0000 -> {
                if (readBios && address < 0x0100u)
                    return bios[address.toInt()].toUByte()
                return rom[address.toInt()].toUByte()
            }
            0x1000, 0x2000, 0x3000, 0x4000, 0x5000, 0x6000, 0x7000 -> return rom[address.toInt()].toUByte()
            0xA000, 0xB000 -> if (cartType > 0) {
                Log.i(
                    "External Ram",
                    "Read ${extraRam[(address.toInt() and 0x1FFF)]} from $address"
                )
                return extraRam[(address.toInt() and 0x1FFF)].toUByte()
            } else Log.w("MBC", "Read $address ignored")
            0xC000, 0xD000, 0xE000 -> return workRam[(address.toInt() and 0x1FFF)].toUByte()
            0x8000, 0x9000 -> return gpu.readFromVRam(address)
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xE00 -> Log.w(
                        "GB.mmu",
                        "Read Requested from " + String.format("%04X", address.toShort())
                    )
                    0xF00 -> {
                        when {
                            address == 0xFF00.toUShort() -> return getJoyPad()
                            address in 0xFF04u..0xFF07u -> return tmr.read(address)
                            address == 0xFF0F.toUShort() -> return interruptFlag
                            address == 0xFFFF.toUShort() -> return interruptEnable
                            address < 0xFF80u -> when ((address and 0xF0u).toInt()) {
                                0x10, 0x20, 0x30 -> return apu.read(address)
                                0x40 -> return gpu.read(address)
                                else -> Log.w(
                                    "GB.mmu",
                                    "Read Requested from " + String.format(
                                        "%04X",
                                        address.toShort()
                                    )
                                )
                            }
                            else -> return highSpeedRam[(address and 0x7Fu).toInt()].toUByte()
                        }
                    }
                    else -> return workRam[(address.toInt() and 0x1FFF)].toUByte()
                }
            }
            else -> Log.w(
                "GB.mmu",
                "Read Requested from " + String.format("%04X", address.toShort())
            )
        }
        return 0xFFu
    }

    private fun writeU8(address: UShort, data: UByte) {
        when ((address and 0xF000u).toInt()) {
            0xF000 -> {
                when ((address and 0xF00u).toInt()) {
                    0xE00 -> {
                        if (address < 0xFEA0u)
                            gpu.writeToOAM(address, data)
                        else
                            Log.w("GB.mmu", "Write $data ignored(unused area)")
                    }
                    0xF00 -> {
                        when {
                            address == 0xFFFF.toUShort() -> {
                                interruptEnable = data
                                Log.i(
                                    "GB.mmu",
                                    "Wrote " + String.format(
                                        "%02X",
                                        data.toByte()
                                    ) + " to Interrupt Enable"
                                )
                            }
                            address < 0xFF80u -> when (address.toInt()) {
                                0xFF00 -> {
                                    if ((data and 0x30u) == 0x30.toUByte())
                                        repeat(8) {
                                            joyPad[it] = false
                                        }
                                    else {
                                        padButtonMode = data and 0x20u <= 0u
                                        padDirectionMode = data and 0x10u <= 0u
                                    }
                                }
                                0xFF01 -> Log.i(
                                    "Serial Data",
                                    "Write $data to FF01 has been ignored"
                                )
                                0xFF02 -> Log.i(
                                    "Serial Data",
                                    "Write $data to FF02 has been ignored"
                                )
                                in 0xFF04..0xFF07 -> tmr.write(address, data)
                                0xFF0F -> {
                                    interruptFlag = data
                                    Log.i(
                                        "GB.mmu",
                                        "Wrote " + String.format(
                                            "%02X",
                                            data.toByte()
                                        ) + " to Interrupt Flag"
                                    )
                                }
                                in 0xFF10..0xFF3F -> apu.write(address, data)
                                0xFF46 -> dmaTransfer(data)
                                in 0xFF40..0xFF4B -> gpu.write(address, data)
                                0xFF50 -> readBios = false
                                else -> Log.w(
                                    "GB.mmu",
                                    "Ignored write $data to 0x" + address.toString(16)
                                )
                            }
                            else -> highSpeedRam[(address and 0x7Fu).toInt()] = data.toByte()
                        }
                    }
                    else -> workRam[(address.toInt() and 0x1FFF)] = data.toByte()
                }
            }
            // TODO: 12/8/20 Rom or Ram Banking MBC
            0x0000, 0x1000 -> if (cartType > 0) {
                Log.i("EnableRAM", String.format("%02X", data.toByte()))
            } else Log.w("MBC", "Write $data ignored")
            0x2000, 0x3000 -> if (cartType > 0) {
                romBankNumber =
                    ((romBankNumber.toInt() and 0x60) or (data and 0x1Fu).toInt()).toByte()
                if (romBankNumber.toUByte() % 32u == 0u) romBankNumber++
                Log.w(
                    "GB.mmu",
                    "lRom Bank = 0x" + String.format(
                        "%02X",
                        romBankNumber
                    ) + " $ramBankMode $data $address"
                )
            } else Log.w("MBC", "Write $data ignored")
            0x4000, 0x5000 -> if (cartType > 0) {
                if (ramBankMode) {
                    ramBankNumber = (data and 0x11u).toByte()
                    Log.w("GB.mmu", "Ram Bank = 0x" + String.format("%02X", ramBankNumber))
                } else {
                    romBankNumber = ((romBankNumber.toInt() and 0x1F).toByte())
                    romBankNumber =
                        (romBankNumber.toInt() or ((data and 0x11u).toInt() shl 5)).toByte()
                    Log.w(
                        "GB.mmu",
                        "uRom Bank = 0x" + String.format(
                            "%02X",
                            romBankNumber
                        ) + " $ramBankMode $data $address"
                    )
                }
            } else Log.w("MBC", "Write $data ignored")
            0x6000, 0x7000 -> if (cartType > 0) {
                ramBankMode = (data and 1u) != 0.toUByte()
                Log.w("GB.mmu", "Ram Bank Mode = $ramBankMode")
            } else Log.w("MBC", "Write $data ignored")
            0x8000, 0x9000 -> gpu.writeToVRam(address, data)
            0xA000, 0xB000 -> if (cartType > 0) {
                Log.i("External Ram", "Write $data to $address")
                extraRam[(address.toInt() and 0x1FFF)] = data.toByte()
            } else Log.w("MBC", "Write $data ignored")
            0xC000, 0xD000, 0xE000 -> workRam[(address.toInt() and 0x1FFF)] = data.toByte()
            else -> Log.w(
                "GB.mmu",
                "Write Requested to " + String.format("%04X", address.toShort())
            )
        }
    }

    private fun checkInterrupts() {
        val firedBits = interruptEnable and interruptFlag
        if (firedBits != 0.toUByte()) {
            interruptsMasterEnabled = false
            //hlt = 0
            writeU8(--sp, (pc.toUInt() shr 8).toUByte())
            writeU8(--sp, pc.toUByte())
            if ((firedBits and 1u) > 0u) {  //Bit 0 is VBLANK
                //Log.i("GB.cpu", "VBlank Interrupt fired")
                interruptFlag = interruptFlag and 0xFEu
                pc = 0x40u
            }
            if ((firedBits and 2u) > 0u) {  //Bit 1 is LCD STATUS
                TODO("LCD STAT Interrupt Fired")
            }
            if ((firedBits and 4u) > 0u) {  //Bit 2 is Timer
                TODO("Timer Interrupt Fired")
            }
            if ((firedBits and 8u) > 0u) {  //Bit 3 is Serial so ignore
                Log.w("Interrupt", "Serial somehow fired")
                interruptFlag = interruptFlag and 0xF7u
                pc = 0x58u
            }
            if ((firedBits and 16u) > 0u) {  //Bit 4 is JoyPad
                TODO("JoyPad Interrupt Fired")
            }
            m = 5u
        }
        val t = 4u * m
        time += t
        gpu.timePassed(t.toInt())
        apu.timePassed(t.toInt())
        tmr.timePassed(t.toInt())
    }

    private fun dmaTransfer(data: UByte) {
        //Doubt if code is efficient enough to complete DMA Transfer in 160 m-cycles
        var add = (data * 256u).toUShort()
        //Log.i("DMA", "Transfer Started from $add")
        val dataBlock = ByteArray(160)
        repeat(160) {
            // TODO: 14/8/20 Improve reading time by changing to rom or ram only
            //Log.i("DMA ${it+1}", read8(add).toString())
            dataBlock[it] = read8(add).toByte()
            add++
        }
        gpu.dmaTransfer(dataBlock)
        //Log.i("DMA", "Transfer Complete")
    }

    private fun log(): String {
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
                "|L=0x" + String.format("%02X", l.toByte()) + "|EmuTime="
        log += when {
            time > 120000000u -> "${time / 60000000u} mins"
            time > 10000000u -> "${time / 1000000u}s"
            time > 10000u -> "${time / 1000u}ms"
            else -> "${time}us"
        }
        return log
    }

    private fun execute() {
        val op = fetch()
        when (op.toInt()) {
            0x00 -> m = 1u  //msg+="NOP"
            0x01 -> loadU16toBC() //msg+="LD BC,u16"
            0x03 -> incBC()
            0x04 -> incB()  //msg += "|INC B"
            0x05 -> decB()  //msg += "|DEC B"
            0x06 -> {
                loadToB(fetch())  //msg += "|LD B,u8"
                m++
            }
            0x07 -> rotateLeftAWithoutCarry()
            0x09 -> addHL(getCombinedValue(b, c))
            0x0A -> {
                loadToA(read8(getCombinedValue(b, c)))  //msg += "|LD A,[BC]"
                m++
            }
            0x0B -> decBC()  //msg += "|DEC BC"
            0x0C -> incC()  //msg += "|INC C"
            0x0D -> decC()  //msg += "|DEC C"
            0x0E -> {
                loadToC(fetch()) //msg += "|LD C,u8"
                m++
            }
            0x11 -> loadU16toDE()    //msg += "|LD DE,u16"
            0x12 -> loadToMemory(getCombinedValue(d, e), a)
            0x13 -> incDE() //msg += "|INC DE"
            0x15 -> decD()   //msg += "|DEC D"
            0x16 -> {
                loadToD(fetch()) //msg += "|LD D,u8"
                m++
            }
            0x17 -> rotateLeftA()   //msg += "|RLA"
            0x18 -> jumpRel(-1)  //msg += "|JR i8"
            0x19 -> addHL(getCombinedValue(d, e))
            0x1A -> {
                loadToA(read8(getCombinedValue(d, e)))  //msg += "|LD A,[DE]"
                m++
            }
            0x1C -> incE()
            0x1D -> decE()  //msg += "|DEC E"
            0x1E -> {
                loadToE(fetch()) //msg += "|LD E,u8"
                m++
            }
            0x20 -> jumpRel(NOT_ZERO)   //msg += "|JR NZ,i8"
            0x21 -> loadU16toHL()   //msg += "|LD HL,u16"
            0x22 -> loadAtHLvalueOfAThenIncHL() //msg += "|LDI [HL],A"
            0x23 -> incHL()  //msg += "|INC HL"
            0x24 -> incH() //msg += "|INC H"
            0x26 -> {
                loadToH(fetch())
                m++
            }
            0x28 -> jumpRel(ZERO) //msg += "|JR Z,i8"
            0x2A -> loadValueOfHLtoAThenIncHL()//msg+="LDI A,[HL]"
            0x2B -> decHL()
            0x2C -> incL()
            0x2D -> decL()
            0x2E -> {
                loadToL(fetch())  //msg += "|LD L,u8"
                m++
            }
            0x2F -> compliment()
            0x31 -> loadU16toSP()   //msg += "|LD SP,u16"
            0x32 -> loadAtHLValueOfAThenDecHL()//msg += "|LDD [HL],A"
            0x34 -> incValueAtHL()
            0x35 -> decValueAtHL()
            0x36 -> {
                loadToMemory(getCombinedValue(h, l), fetch())   //msg+="LD [HL],u8"
                m++
            }
            0x38 -> jumpRel(CARRY)
            0x3A -> loadValueOfHLtoAThenDecHL()
            0x3C -> incA()
            0x3D -> decA()   //msg += "|DEC A"
            0x3E -> {
                loadToA(fetch())  //msg += "|LD A,u8"
                m++
            }
            0x40 -> m = 1u  //LD B,B
            0x46 -> {
                loadToB(read8(getCombinedValue(h, l)))
                m++
            }
            0x47 -> loadToB(a)
            0x49 -> m = 1u  //LD C,C
            0x4E -> {
                loadToC(read8(getCombinedValue(h, l)))
                m++
            }
            0x4F -> loadToC(a)   //msg += "|LD C,A"
            0x50 -> loadToD(b)
            0x52 -> m = 1u  //LD D,D
            0x54 -> loadToD(h)
            0x56 -> {
                loadToD(read8(getCombinedValue(h, l)))
                m++
            }
            0x57 -> loadToD(a)   //msg += "|LD D,A"
            0x5B -> m = 1u  //LD E,E
            0x5D -> loadToE(l)
            0x5E -> {
                loadToE(read8(getCombinedValue(h, l)))
                m++
            }
            0x5F -> loadToE(a)
            0x60 -> loadToH(b)
            0x62 -> loadToH(d)
            0x64 -> m = 1u  //LD H,H
            0x67 -> loadToH(a)   //msg += "|LD H,A"
            0x69 -> loadToL(c)
            0x6B -> loadToL(e)
            0x6D -> m = 1u  //LD L,L
            0x6F -> loadToL(a)
            0x71 -> loadToMemory(getCombinedValue(h, l), c)
            0x72 -> loadToMemory(getCombinedValue(h, l), d)
            0x73 -> loadToMemory(getCombinedValue(h, l), e)
            0x77 -> loadToMemory(getCombinedValue(h, l), a)   //msg += "|LD [HL],A"
            0x78 -> loadToA(b)  //msg += "|LD A,B"
            0x79 -> loadToA(c)
            0x7A -> loadToA(d)
            0x7B -> loadToA(e)   //msg += "|LD A,E"
            0x7C -> loadToA(h)  //msg += "|LD A,H"
            0x7D -> loadToA(l)   //msg += "|LD A,L"
            0x7E -> {
                loadToA(read8(getCombinedValue(h, l)))
                m++
            }
            0x7F -> m = 1u  //LD A,A
            0x80 -> addToA(b)
            0x86 -> {
                addToA(read8(getCombinedValue(h, l)))//msg += "|ADD A,[HL]"
                m++
            }
            0x85 -> addToA(l)
            0x87 -> addToA(a)
            0x89 -> addToAWithCarry(c)
            0x90 -> subFromA(b)  //msg += "|SUB A,B"
            0x96 -> {
                subFromB(read8(getCombinedValue(h, l)))
                m++
            }
            0xA1 -> andWithA(c)
            0xA7 -> andA()
            0xA9 -> xorWithA(c)
            0xAF -> xorA()
            0xB0 -> orWithA(b)
            0xB1 -> orWithA(c) //msg+="OR A,C"
            0xBE -> compareValueAtHLandA()  //msg += "|CP A,[HL]"
            0xC0 -> returnSelect(NOT_ZERO)
            0xC1 -> popBC() //msg += "|POP BC"
            0xC2 -> jump(NOT_ZERO)
            0xC3 -> jump(-1)   //msg += "|JP u16"
            0xC5 -> pushBC()    //msg += "|PUSH BC"
            0xC6 -> {
                addToA(fetch())
                m++
            }
            0xC8 -> returnSelect(ZERO)  //msg += "|RET"
            0xC9 -> returnSelect(-1)  //msg += "|RET"
            0xCA -> jump(ZERO)
            0xCB -> {   //msg += "|Prefix CB"
                val op2 = fetch()
                when (op2.toInt()) {
                    0x11 -> rotateLeftC()   //msg += "|RL C"
                    0x27 -> shiftLeftA()
                    0x33 -> swapE()
                    0x37 -> swapA()
                    0x3F -> shiftRightA()
                    0x40 -> bitCheck(b, 0)
                    0x48 -> bitCheck(b, 1)
                    0x50 -> bitCheck(b, 2)
                    0x58 -> bitCheck(b, 3)
                    0x5F -> bitCheck(a, 3)
                    0x60 -> bitCheck(b, 4)
                    0x68 -> bitCheck(b, 5)
                    0x6F -> bitCheck(a, 5)
                    0x77 -> bitCheck(a, 6)
                    0x7C -> bitCheck(h, 7)  //msg += "|BIT 7,H"
                    0x7E -> bitCheck(read8(getCombinedValue(h, l)), 7)
                    0x7F -> bitCheck(a, 7)
                    0x86 -> resetValueAtHL(0)
                    0x87 -> resetA(0)
                    0xBE -> resetValueAtHL(7)
                    0xFE -> setValueAtHL(7)
                    else -> {
                        pc--
                        m = 1u
                        Log.e(
                            "GB.cpu",
                            "0x" + String.format("%02X", op2.toByte()) + "|Unknown Suffix OP"
                        )
                        cpuCrash = true
                    }
                }
            }
            0xCD -> callU16()   //msg += "|CALL u16"
            0xD1 -> popDE()
            0xD5 -> pushDE()  //msg="PUSH DE"
            0xD6 -> {
                subFromA(fetch())
                m++
            }
            0xD9 -> returnSelect(ENABLE_INTERRUPTS)
            0xE0 -> {
                loadToMemory((0xFF00u + fetch()).toUShort(), a)  //msg += "|LD [FF00+u8],A"
                m++
            }
            0xE1 -> popHL()
            0xE2 -> loadToMemory((0xFF00u + c).toUShort(), a)    //msg += "|LD [FF00+C],A"
            0xE5 -> pushHL()
            0xE6 -> {
                andWithA(fetch())
                m++
            }
            0xE9 -> {
                pc = (l.toInt() or (h.toInt() shl 8)).toUShort()
                m = 1u
            }
            0xEA -> {
                loadToMemory(
                    (fetch().toInt() or (fetch().toInt() shl 8)).toUShort(),
                    a
                )  //msg += "|LD [u16],A"
                m += 2u
            }
            0xEE -> {
                xorWithA(fetch())
                m++
            }
            0xEF -> restart(0x28u)
            0xF0 -> {
                loadToA(read8((0xFF00u + fetch()).toUShort())) //msg += "|LD A,[FF+u8]"
                m += 2u
            }
            0xF1 -> popAF()
            0xF3 -> {
                interruptsMasterEnabled = false  //msg+="DI"
                Log.i("GB.cpu", "Interrupts Disabled")
                m = 1u
            }
            0xF5 -> pushAF()//msg+="PUSH AF"
            0xF6 -> {
                orWithA(fetch())
                m++
            }
            0xFA -> {
                loadToA(read8(fetchTwice()))
                m += 3u
            }
            0xFB -> {
                if (read8(pc) != 0xF3.toUByte())
                    interruptsMasterEnabled = true  //msg+="EI"
                Log.i("GB.cpu", "Interrupts Enabled")
                m = 1u
            }
            0xFE -> compareU8andA() //msg += "|CP A,u8"
            else -> {
                pc--
                m = 0u
                Log.e("GB.cpu", "0x" + String.format("%02X", op.toByte()) + "|Unknown OP")
                cpuCrash = true
                return
            }
        }
        val t = 4u * m
        time += t
        gpu.timePassed(t.toInt())
        apu.timePassed(t.toInt())
        tmr.timePassed(t.toInt())
    }

    private fun shiftRightA() {
        f = 0u
        f = f or ((a and 1u).toUInt() shl 4).toUByte()
        a = (a.toInt() shr 1).toUByte()
        if (a <= 0u)
            f = f or 0x80u
        m = 2u
    }

    private fun shiftLeftA() {
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (a <= 0u)
            f = f or 0x80u
        m = 2u
    }

    private fun resetA(bitNo: Int) {
        a = a and (1 shl bitNo).inv().toUByte()
        m = 2u
    }

    private fun resetValueAtHL(bitNo: Int) {
        writeU8(
            getCombinedValue(h, l),
            read8(getCombinedValue(h, l)) and (1 shl bitNo).inv().toUByte()
        )
        m = 2u
    }

    private fun setValueAtHL(bitNo: Int) {
        writeU8(
            getCombinedValue(h, l),
            read8(getCombinedValue(h, l)) or (1 shl bitNo).toUByte()
        )
        m = 2u
    }

    private fun xorWithA(lit: UByte) {
        f = 0u
        a = a xor lit
        if (a <= 0u) f = 0x80u
        m = 1u
    }

    private fun swapA() {
        a = (((a and 0xFu).toInt() shl 4) or ((a and 0xF0u).toInt() shr 4)).toUByte()
        m = 2u
    }

    private fun swapE() {
        e = (((e and 0xFu).toInt() shl 4) or ((e and 0xF0u).toInt() shr 4)).toUByte()
        m = 2u
    }

    private fun compliment() {
        f = f and 0x90u
        f = f or 0x60u
        a = a.inv()
        m = 1u
    }

    private fun addToA(lit: UByte) {
        val result = (a + lit).toInt()
        f = 0u
        if ((result and 0xFF) == 0) f = f or 0x80u
        if ((a and 0xFu) + (lit and 0xFu) > 0xFu) f = f or 0x20u
        if (result > 0xFF) f = f or 0x10u
        a = result.toUByte()
        m = 1u
    }

    private fun addToAWithCarry(lit: UByte) {
        val carry = if (f and 0x10u > 0u) 1u else 0u
        val result = (a + lit + carry).toInt()
        f = 0u
        if ((result and 0xFF) == 0) f = f or 0x80u
        if ((a and 0xFu) + (lit and 0xFu) + carry > 0xFu) f = f or 0x20u
        if (result > 0xFF) f = f or 0x10u
        a = result.toUByte()
        m = 1u
    }

    private fun subFromA(lit: UByte) {
        val res = (a - lit).toInt()
        f = 0u
        f = f or 0x40u
        if (res.toUByte() == 0.toUByte())
            f = f or 0x80u
        if ((a and 0xFu) - (lit and 0xFu) < 0u)
            f = f or 0x20u
        if (res < 0)
            f = f or 0x10u
        a = res.toUByte()
        m = 1u
    }

    private fun subFromB(lit: UByte) {
        val res = (b - lit).toInt()
        f = 0u
        f = f or 0x40u
        if (res.toUByte() == 0.toUByte())
            f = f or 0x80u
        if ((b and 0xFu) - (lit and 0xFu) < 0u)
            f = f or 0x20u
        if (res < 0)
            f = f or 0x10u
        b = res.toUByte()
        m = 1u
    }

    fun runTillCrash() {
        cartType = rom[0x147].toInt()
        Log.i("MBC Type", String.format("%02X", cartType.toByte()))
        while (!cpuCrash) {
            execute()
            m = 0u
            updateInterruptFlag()
            if (interruptsMasterEnabled)
                checkInterrupts()
        }
        Log.i("CPU Log", log())
        Log.i("GPU Log", gpu.log())
    }

    private fun updateInterruptFlag() {
        // TODO: 14/8/20 Complete this function(serial can be ignored)
        if (gpu.isVBlank())
            interruptFlag = interruptFlag or 1u
        if (tmr.hasOverflowOccurred())
            interruptFlag = interruptFlag or 4u
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

    private fun compareValueAtHLandA() {
        f = 0x40u
        val lit = read8(getCombinedValue(h, l))
        val res = (a - lit).toInt()
        if ((res and 0xFF) == 0)
            f = f or 0x80u
        if (res < 0)
            f = f or 0x10u
        if ((a and 0xFu) - (lit and 0xFu) < 0u)
            f = f or 0x20u
        m = 2u
    }

    private fun returnSelect(condition: Int) {
        m = 2u
        when (condition) {
            NOT_ZERO -> {
                if ((f and 0x80u).toUInt() == 0u) {
                    pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                    m += 3u
                }
            }
            ZERO -> {
                if ((f and 0x80u) > 0u) {
                    pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                    m += 3u
                }
            }
            ENABLE_INTERRUPTS -> {
                pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                interruptsMasterEnabled = true
                //Log.i("GB.cpu", "Returned and Interrupts Enabled")
                m += 2u
            }
            -1 -> {
                pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                m += 2u
            }
            else -> TODO("RET $condition")
        }
    }

    private fun rotateLeftA() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (carry)
            a++
        m = 1u
    }

    private fun rotateLeftAWithoutCarry() {
        val carry = (a and 0x80u) > 0u
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (carry)
            a++
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

    private fun popAF() {
        f = read8(sp++)
        a = read8(sp++)
        m = 3u
    }

    private fun popBC() {
        c = read8(sp++)
        b = read8(sp++)
        m = 3u
    }

    private fun popDE() {
        e = read8(sp++)
        d = read8(sp++)
        m = 3u
    }

    private fun popHL() {
        l = read8(sp++)
        h = read8(sp++)
        m = 3u
    }

    private fun pushAF() {
        writeU8(--sp, a)
        writeU8(--sp, f)
        m = 4u
    }

    private fun pushBC() {
        writeU8(--sp, b)
        writeU8(--sp, c)
        m = 4u
    }

    private fun pushDE() {
        writeU8(--sp, d)
        writeU8(--sp, e)
        m = 4u
    }

    private fun pushHL() {
        writeU8(--sp, h)
        writeU8(--sp, l)
        m = 4u
    }

    private fun loadToA(lit: UByte) {
        a = lit
        m = 1u
    }

    private fun loadToB(lit: UByte) {
        b = lit
        m = 1u
    }

    private fun loadToC(lit: UByte) {
        c = lit
        m = 1u
    }

    private fun loadToD(lit: UByte) {
        d = lit
        m = 1u
    }

    private fun loadToE(lit: UByte) {
        e = lit
        m = 1u
    }

    private fun loadToL(lit: UByte) {
        l = lit
        m = 1u
    }

    private fun loadToH(lit: UByte) {
        h = lit
        m = 1u
    }


    private fun restart(address: UByte) {
        writeU8(--sp, (pc.toUInt() shr 8).toUByte())
        writeU8(--sp, pc.toUByte())
        pc = address.toUShort()
        m = 4u
    }

    private fun callU16() {
        val newPC = (fetch().toInt() or (fetch().toInt() shl 8)).toUShort()
        writeU8(--sp, (pc.toUInt() shr 8).toUByte())
        writeU8(--sp, pc.toUByte())
        pc = newPC
        m = 6u
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

    private fun decD() {
        val res: UByte = (d - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((d and 0xFu) - 1u < 0u)
            f = f or 0x20u
        d = res
        m = 1u
    }

    private fun decE() {
        val res: UByte = (e - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((e and 0xFu) - 1u < 0u)
            f = f or 0x20u
        e = res
        m = 1u
    }

    private fun decL() {
        val res: UByte = (l - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((l and 0xFu) - 1u < 0u)
            f = f or 0x20u
        l = res
        m = 1u
    }

    private fun decBC() {
        setBC(getCombinedValue(b, c) - 1u)
        m = 2u
    }

    private fun decHL() {
        setHL(getCombinedValue(h, l) - 1u)
        m = 2u
    }


    private fun incBC() {
        setBC(getCombinedValue(b, c) + 1u)
        m = 2u
    }

    private fun incDE() {
        setDE(getCombinedValue(d, e) + 1u)
        m = 2u
    }

    private fun incHL() {
        setHL(getCombinedValue(h, l) + 1u)
        m = 2u
    }

    private fun incValueAtHL() {
        var res = read8(getCombinedValue(h, l))
        f = f and 0x10u
        if ((res and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        res++
        if (res == 0.toUByte())
            f = f or 0x80u
        writeU8(getCombinedValue(h, l), res)
        m = 3u
    }

    private fun decValueAtHL() {
        val res: UByte = (read8(getCombinedValue(h, l)) - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((read8(getCombinedValue(h, l)) and 0xFu) - 1u < 0u)
            f = f or 0x20u
        writeU8(getCombinedValue(h, l), res)
        m = 3u
    }

    private fun addHL(lit: UShort) {
        f = f and 0x80u
        val res = getCombinedValue(h, l) + lit
        if ((getCombinedValue(h, l) and 0xFFFu) + (lit and 0xFFFu) > 0xFFFu) f = f or 0x20u
        if (res > 0xFFFFu) f = f or 0x10u
        setHL(res)
        m = 2u
    }

    private fun incA() {
        f = f and 0x10u
        if ((a and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        a++
        if (a == 0.toUByte())
            f = f or 0x80u
        m = 1u
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

    private fun incD() {
        f = f and 0x10u
        if ((d and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        d++
        if (d == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun incE() {
        f = f and 0x10u
        if ((e and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        e++
        if (e == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun incH() {
        f = f and 0x10u
        if ((h and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        h++
        if (h == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun incL() {
        f = f and 0x10u
        if ((l and 0xFu) + 1u > 0xFu)
            f = f or 0x20u
        l++
        if (l == 0.toUByte())
            f = f or 0x80u
        m = 1u
    }

    private fun jump(condition: Int) {
        val temp = fetchTwice()
        m = 3u
        when (condition) {
            NOT_ZERO -> {
                if ((f and 0x80u).toUInt() <= 0u) {
                    pc = temp
                    m++
                }
            }
            ZERO -> {
                if ((f and 0x80u).toUInt() != 0u) {
                    pc = temp
                    m++
                }
            }
            -1 -> {
                pc = temp
                m++
            }
            else -> TODO("JP $condition")
        }
    }

    private fun jumpRel(condition: Int) {
        val temp = fetch().toByte()
        m = 2u
        when (condition) {
            NOT_ZERO -> {
                if ((f and 0x80u).toUInt() == 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m++
                }
            }
            ZERO -> {
                if ((f and 0x80u).toUInt() != 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m++
                }
            }
            NO_CARRY -> {
                if ((f and 0x10u).toUInt() == 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m++
                }
            }
            CARRY -> {
                if ((f and 0x10u).toUInt() != 0u) {
                    pc = (pc.toInt() + temp).toUShort()
                    m++
                }
            }
            else -> {
                pc = (pc.toInt() + temp).toUShort()
                m++
            }
        }
    }

    private fun bitCheck(byte: UByte, bitNo: Int) {
        f = f and 0x10u   //preserve carry
        f = f or 0x20u    //set half carry
        if (((byte.toUInt() shr bitNo) and 1u) == 0u)
            f = f or 0x80u    //set zero
        m = 2u
    }

    private fun setBC(bc: UInt) {
        c = bc.toUByte()
        b = (bc shr 8).toUByte()
    }

    private fun setDE(de: UInt) {
        e = de.toUByte()
        d = (de shr 8).toUByte()
    }

    private fun setHL(hl: UInt) {
        l = hl.toUByte()
        h = (hl shr 8).toUByte()
    }

    private fun getCombinedValue(upper: UByte, lower: UByte): UShort {
        return (lower.toInt() or (upper.toInt() shl 8)).toUShort()
    }

    private fun xorA() {
        a = 0u
        f = 0x80.toUByte()
        m = 1u
    }

    private fun andA() {
        f = 2u
        if (a == 0.toUByte()) f = f or 0x80u
        m = 1u
    }

    private fun andWithA(lit: UByte) {
        f = 2u
        a = a and lit
        if (a == 0.toUByte()) f = f or 0x80u
        m = 1u
    }

    private fun orWithA(lit: UByte) {
        a = ((a or lit).toUByte())
        f = 0u
        if (a == 0.toUByte()) f = f or 0x80u
        m = 1u
    }

    private fun loadToMemory(address: UShort, lit: UByte) {
        writeU8(address, lit)
        m = 2u
    }

    private fun loadAtHLvalueOfAThenIncHL() {
        writeU8(getCombinedValue(h, l), a)
        setHL(getCombinedValue(h, l) + 1u)
        m = 2u
    }

    private fun loadValueOfHLtoAThenIncHL() {
        a = read8(getCombinedValue(h, l))
        setHL(getCombinedValue(h, l) + 1u)
        m = 2u
    }

    private fun loadValueOfHLtoAThenDecHL() {
        a = read8(getCombinedValue(h, l))
        setHL(getCombinedValue(h, l) - 1u)
        m = 2u
    }

    private fun loadAtHLValueOfAThenDecHL() {
        writeU8(getCombinedValue(h, l), a)
        setHL(getCombinedValue(h, l) - 1u)
        m = 2u
    }

    private fun loadU16toBC() {
        c = fetch()
        b = fetch()
        m = 3u
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