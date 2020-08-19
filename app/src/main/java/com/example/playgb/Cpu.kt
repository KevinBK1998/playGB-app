package com.example.playgb

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

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
private const val SERIAL_TIMEOUT = 300000000u

@ExperimentalUnsignedTypes
class Cpu(
    private val bios: ByteArray,
    private val dumpFolder: File,
    private val romFile: File,
    private var gpu: Gpu,
    private var apu: Apu,
    private val joyPad: BooleanArray
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

    //Rom
    private val rom = ByteArray(32768)

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
    private var serialByte: UByte = 0u
    private var serialControl: UByte = 0u
    private var halted = false
    private var extraRamEnabled = false

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
            0xA000, 0xB000 ->
                if (extraRamEnabled) {
                    return if (cartType > 0)
                        when (cartType) {
                            3 -> {
                                Log.i(
                                    "External Ram",
                                    "Read ${extraRam[(address.toInt() and 0x1FFF)]} from $address"
                                )
                                extraRam[(address.toInt() and 0x1FFF)].toUByte()
                            }
                            else -> TODO("Access External Ram")
                        }
                    else {
                        Log.i(
                            "External Ram",
                            "Read ${extraRam[(address.toInt() and 0x1FFF)]} from $address"
                        )
                        extraRam[(address.toInt() and 0x1FFF)].toUByte()
                    }
                }
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
                            address == 0xFF01.toUShort() -> return serialByte
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
                            address == 0xFFFF.toUShort() ->
                                if (interruptEnable != data) {
                                    Log.i(
                                        "GB.mmu",
                                        "Write " + String.format(
                                            "%02X",
                                            data.toByte()
                                        ) + " to Interrupt Enable"
                                    )
                                    interruptEnable = data
                                    if (data and 0x10u > 0u)
                                        TODO("Updating interrupt flags for JoyPad")
                                    if (data and 1u > 0u)
                                        Log.w("GB.mmu", "Vblank interrupt enabled")
                                    if (data and 2u > 0u)
                                        Log.w("GB.mmu", "LCD Status interrupt enabled")
                                    if (data and 4u > 0u)
                                        Log.w("GB.mmu", "Timer interrupt enabled")
                                    if (data and 8u > 0u)
                                        Log.w("GB.mmu", "Serial interrupt enabled")
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
                                0xFF01 -> serialByte = data
                                0xFF02 -> serialControl = data
                                in 0xFF04..0xFF07 -> tmr.write(address, data)
                                0xFF0F -> {
                                    if (interruptFlag != data) {
                                        if (data <= 0u)
                                            Log.i("GB.mmu", "Clear Interrupt Requests")
                                        else
                                            Log.i(
                                                "GB.mmu",
                                                "Write " + String.format(
                                                    "%02X",
                                                    data.toByte()
                                                ) + " to Interrupt Flag(" + interruptFlag.toString(2) + ")"
                                            )
                                        interruptFlag = data
                                    }
                                }
                                in 0xFF10..0xFF3F -> apu.write(address, data)
                                0xFF46 -> dmaTransfer(data)
                                in 0xFF40..0xFF4B -> gpu.write(address, data)
                                0xFF50 -> if (data == 1.toUByte()) readBios = false
                                else {
                                    Log.i("ROM", "EOF")
                                    cpuCrash = true
                                }
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
            0x0000, 0x1000 -> {
                Log.i("EnableRAM", String.format("%02X", data.toByte()))
                extraRamEnabled = data and 0xFu == 0xA.toUByte()
            }
            0x2000, 0x3000 -> {
                romBankNumber =
                    ((romBankNumber.toInt() and 0x60) or (data and 0x1Fu).toInt()).toByte()
                if (romBankNumber.toUByte() % 32u == 0u && cartType < 7) romBankNumber++
                if (romBankNumber.toInt() == 0 && cartType > 14) romBankNumber++
                if (!ramBankMode) {
                    Log.w("GB.mmu", "Switch Rom Bank to 0x" + romBankNumber.toString(16))
                    val ifs = FileInputStream(romFile)
                    val bis = BufferedInputStream(ifs)
                    bis.skip(romBankNumber * 16384L)
                    bis.read(rom, 16384, 16384)
                    bis.close()
                }
            }
            0x4000, 0x5000 -> if (cartType > 0) {
                if (ramBankMode) {
                    ramBankNumber = (data and 0x11u).toByte()
                    Log.w("GB.mmu", "Ram Bank = 0x" + String.format("%02X", ramBankNumber))
                    TODO("Change Ram Bank")
                } else {
                    romBankNumber = ((romBankNumber.toInt() and 0x1F).toByte())
                    romBankNumber =
                        (romBankNumber.toInt() or ((data and 0x11u).toInt() shl 5)).toByte()
                    Log.w("GB.mmu", "Switch Rom Bank to 0x" + romBankNumber.toString(16))
                    val ifs = FileInputStream(romFile)
                    val bis = BufferedInputStream(ifs)
                    bis.skip(romBankNumber * 16384L)
                    bis.read(rom, 16384, 16384)
                    bis.close()
                }
            } else Log.w("MBC", "Write $data ignored")
            0x6000, 0x7000 -> if (cartType > 0) {
                ramBankMode = (data and 1u) != 0.toUByte()
                Log.w("GB.mmu", "Ram Bank Mode = $ramBankMode")
            } else Log.w("MBC", "Write $data ignored")
            0x8000, 0x9000 -> gpu.writeToVRam(address, data)
            0xA000, 0xB000 -> if (extraRamEnabled) {
                if (cartType > 0)
                    when (cartType) {
                        3 -> {
                            Log.i("External Ram", "Write $data to $address")
                            if (extraRamEnabled)
                                extraRam[(address.toInt() and 0x1FFF)] = data.toByte()
                        }
                        else -> TODO("Access External Ram")
                    }
                else {
                    Log.i("External Ram", "Write $data to $address")
                    if (extraRamEnabled)
                        extraRam[(address.toInt() and 0x1FFF)] = data.toByte()
                }
            }
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
            halted = false
            if (interruptsMasterEnabled) {
                interruptsMasterEnabled = false
                writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                writeU8(--sp, pc.toUByte())
                if ((firedBits and 1u) > 0u) {  //Bit 0 is VBLANK
                    //Log.i("GB.cpu", "VBlank Interrupt fired")
                    interruptFlag = interruptFlag and 0xFEu
                    pc = 0x40u
                }
                if ((firedBits and 2u) > 0u) {  //Bit 1 is LCD STATUS
                    Log.w("Interrupt", "STAT Interrupt fired")
                    interruptFlag = interruptFlag and 0xFDu
                    pc = 0x48u
                }
                if ((firedBits and 4u) > 0u) {  //Bit 2 is Timer
                    //Log.w("Interrupt", "Timer Interrupt fired")
                    interruptFlag = interruptFlag and 0xFBu
                    pc = 0x50u
                }
                if ((firedBits and 8u) > 0u) {  //Bit 3 is Serial so ignore
                    Log.w("Interrupt", "Serial Interrupt fired")
                    interruptFlag = interruptFlag and 0xF7u
                    pc = 0x58u
                }
                if ((firedBits and 16u) > 0u) {  //Bit 4 is JoyPad
                    TODO("JoyPad Interrupt Fired")
                    /* interruptFlag = interruptFlag and 0xEFu
                     pc = 0x60u*/
                }
                m += 5u
            }
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

    private fun dumpROM() {
        var data = ""
        repeat(32767) {
            data += rom[it].toUByte().toString(16) + " "
            if ((it + 1) % 16 == 0)
                data += "\n"
        }
        val file = File(dumpFolder, "rom.txt")
        file.writeText(data)
        //val contents = file.readText()
    }

    fun runTillCrash() {
        val ifs = FileInputStream(romFile)
        val bis = BufferedInputStream(ifs)
        bis.read(rom)
        bis.close()
        var titleSize = 0x144
        for (ind in 0x134 until 0x144)
            if (rom[ind].toInt() == 0) {
                titleSize = ind
                break
            }
        val title = String(rom.copyOfRange(0x134, titleSize))
        Log.i("Rom Title", title)
        val romSize = if (rom[0x148] < 9) 32 shl rom[0x148].toInt()
        else when (rom[0x148].toInt()) {
            0x54 -> 1536
            0x53 -> 1229
            else -> 1127
        }
        Log.i("Rom Size", if (romSize > 1000) "${romSize / 1024} MB" else "$romSize KB")
        cartType = rom[0x147].toInt()
        Log.i("MBC Type", cartType.toString(16) + "h")
        //01h  MBC1
        //03h  MBC1+RAM+BATTERY
        //13h  MBC3+RAM+BATTERY
//        dumpROM()
        while (!cpuCrash) {
            if (isAppPaused)
                continue
            if (halted)
                m = 1u
            else
                execute()
            updateInterruptFlag()
            checkInterrupts()
        }
        Log.i("CPU Log", log())
        Log.i("GPU Log", gpu.log())
    }

    private fun updateInterruptFlag() {
        // TODO: 14/8/20 Complete this function(serial can be ignored)
        if (gpu.isVBlank())
            interruptFlag = interruptFlag or 1u
        if (gpu.statInterrupted())
            interruptFlag = interruptFlag or 2u
        if (tmr.hasOverflowOccurred())
            interruptFlag = interruptFlag or 4u
        if (time % SERIAL_TIMEOUT == 0u)
            if (serialControl and 0x80u > 0u) {
                serialByte = 0xFFu
                serialControl = serialControl and 1u
                interruptFlag = interruptFlag or 8u
            }
    }

    private fun execute() {
        //TODO Replace unsigned checks with signed checks
        val op = fetch()
        when (op.toInt()) {
            0x00 -> m = 1u  //NOP
            0x01 -> {//LD BC,u16
                c = fetch()
                b = fetch()
                m = 3u
            }
            0x02 -> loadToMemory(getCombinedValue(b, c), a)
            0x03 -> {//INC BC
                setBC(getCombinedValue(b, c) + 1u)
                m = 2u
            }
            0x04 -> {//INC B
                f = f and 0x10u
                if ((b and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                b++
                if (b.toInt() == 0)
                    f = f or 0x80u
                m = 1u
            }
            0x05 -> {   //DEC B
                val res: UByte = (b - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res.toInt() == 0)
                    f = f or 0x80u
                if ((b and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                b = res
                m = 1u
            }
            0x06 -> {//LD B,u8
                loadToB(fetch())
                m++
            }
            0x07 -> {//RLCA
                val carry = (a and 0x80u) > 0u
                f = 0u
                f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                a = (a.toInt() shl 1).toUByte()
                if (carry)
                    a++
                m = 1u
            }

            0x08 -> {//LD [u16],SP
                val temp = fetchTwice()
                loadToMemory(temp, sp.toUByte())
                loadToMemory((temp + 1u).toUShort(), (sp.toUInt() shr 8).toUByte())
                m = 5u
            }
            0x09 -> addHL(getCombinedValue(b, c))   //ADD HL,SP
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
            0x0F -> nonPrefixRotateRightAWithoutCarry()
            0x10 -> {
                m = 1u
                halted = true
            }
            0x11 -> loadU16toDE()    //msg += "|LD DE,u16"
            0x12 -> loadToMemory(getCombinedValue(d, e), a)
            0x13 -> incDE() //msg += "|INC DE"
            0x14 -> incD()
            0x15 -> decD()   //msg += "|DEC D"
            0x16 -> {
                loadToD(fetch()) //msg += "|LD D,u8"
                m++
            }
            0x17 -> nonPrefixRotateLeftA()   //msg += "|RLA"
            0x18 -> jumpRel(-1)  //msg += "|JR i8"
            0x19 -> addHL(getCombinedValue(d, e))
            0x1A -> {
                loadToA(read8(getCombinedValue(d, e)))  //msg += "|LD A,[DE]"
                m++
            }
            0x1B -> decDE()
            0x1C -> incE()
            0x1D -> decE()  //msg += "|DEC E"
            0x1E -> {
                loadToE(fetch()) //msg += "|LD E,u8"
                m++
            }
            0x1F -> nonPrefixRotateRightA()
            0x20 -> jumpRel(NOT_ZERO)   //msg += "|JR NZ,i8"
            0x21 -> loadU16toHL()   //msg += "|LD HL,u16"
            0x22 -> loadAtHLvalueOfAThenIncHL() //msg += "|LDI [HL],A"
            0x23 -> incHL()  //msg += "|INC HL"
            0x24 -> incH() //msg += "|INC H"
            0x25 -> decH()
            0x26 -> {
                loadToH(fetch())
                m++
            }
            0x27 -> decimalAdjustA()
            0x28 -> jumpRel(ZERO) //msg += "|JR Z,i8"
            0x29 -> addHL(getCombinedValue(h, l))
            0x2A -> loadValueOfHLtoAThenIncHL()//msg+="LDI A,[HL]"
            0x2B -> decHL()
            0x2C -> incL()
            0x2D -> decL()
            0x2E -> {
                loadToL(fetch())  //msg += "|LD L,u8"
                m++
            }
            0x2F -> compliment()
            0x30 -> jumpRel(NO_CARRY)
            0x31 -> loadU16toSP()   //msg += "|LD SP,u16"
            0x32 -> loadAtHLValueOfAThenDecHL()//msg += "|LDD [HL],A"
            0x33 -> {
                sp++
                m = 2u
            }
            0x34 -> incValueAtHL()
            0x35 -> decValueAtHL()
            0x36 -> {
                loadToMemory(getCombinedValue(h, l), fetch())   //msg+="LD [HL],u8"
                m++
            }
            0x37 -> {
                f = f and 0x80u
                f = f or 0x10u
                m = 1u
            }
            0x38 -> jumpRel(CARRY)
            0x39 -> addHL(sp)
            0x3A -> loadValueOfHLtoAThenDecHL()
            0x3C -> incA()
            0x3D -> decA()   //msg += "|DEC A"
            0x3E -> {
                loadToA(fetch())  //msg += "|LD A,u8"
                m++
            }
            0x3F -> {
                f = f and 0x80u
                f = f xor 0x10u
                m = 1u
            }
            0x40 -> m = 1u  //LD B,B
            0x41 -> loadToB(c)
            0x42 -> loadToB(d)
            0x43 -> loadToB(e)
            0x44 -> loadToB(h)
            0x45 -> loadToB(l)
            0x46 -> {
                loadToB(read8(getCombinedValue(h, l)))
                m++
            }
            0x47 -> loadToB(a)
            0x48 -> loadToC(b)
            0x49 -> m = 1u  //LD C,C
            0x4A -> loadToC(d)
            0x4B -> loadToC(e)
            0x4C -> loadToC(h)
            0x4D -> loadToC(l)
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
            0x61 -> loadToH(c)
            0x62 -> loadToH(d)
            0x64 -> m = 1u  //LD H,H
            0x66 -> {
                loadToH(read8(getCombinedValue(h, l)))
                m++
            }
            0x67 -> loadToH(a)   //msg += "|LD H,A"
            0x69 -> loadToL(c)
            0x6B -> loadToL(e)
            0x6D -> m = 1u  //LD L,L
            0x6E -> {
                loadToL(read8(getCombinedValue(h, l)))
                m++
            }
            0x6F -> loadToL(a)
            0x70 -> loadToMemory(getCombinedValue(h, l), b)
            0x71 -> loadToMemory(getCombinedValue(h, l), c)
            0x72 -> loadToMemory(getCombinedValue(h, l), d)
            0x73 -> loadToMemory(getCombinedValue(h, l), e)
            0x76 -> {
                halted = true
                m = 1u
            }
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
            0x81 -> addToA(c)
            0x82 -> addToA(d)
            0x83 -> addToA(e)
            0x86 -> {
                addToA(read8(getCombinedValue(h, l)))//msg += "|ADD A,[HL]"
                m++
            }
            0x85 -> addToA(l)
            0x87 -> addToA(a)
            0x88 -> addToAWithCarry(b)
            0x89 -> addToAWithCarry(c)
            0x8C -> addToAWithCarry(h)
            0x8E -> {
                addToAWithCarry(read8(getCombinedValue(h, l)))
                m++
            }
            0x90 -> subFromA(b)  //msg += "|SUB A,B"
            0x91 -> subFromA(c)
            0x93 -> subFromA(e)
            0x96 -> {
                subFromA(read8(getCombinedValue(h, l)))
                m++
            }
            0x97 -> subFromA(a)
            0xA0 -> andWithA(b)
            0xA1 -> andWithA(c)
            0xA6 -> {
                andWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xA7 -> andWithA(a)
            0xA8 -> xorWithA(b)
            0xA9 -> xorWithA(c)
            0xAE -> {
                xorWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xAF -> xorA()
            0xB0 -> orWithA(b)
            0xB1 -> orWithA(c) //msg+="OR A,C"
            0xB2 -> orWithA(d)
            0xB3 -> orWithA(e)
            0xB5 -> orWithA(l)
            0xB6 -> {
                orWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xB7 -> orWithA(a)
            0xB8 -> compareWithA(b)
            0xB9 -> compareWithA(c)
            0xBA -> compareWithA(d)
            0xBB -> compareWithA(e)
            0xBC -> compareWithA(h)
            0xBE -> {
                compareWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xC0 -> returnSelect(NOT_ZERO)
            0xC1 -> popBC() //msg += "|POP BC"
            0xC2 -> jump(NOT_ZERO)
            0xC3 -> jump(-1)   //msg += "|JP u16"
            0xC4 -> call(NOT_ZERO)
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
                    0x00 -> rotateLeftBWithoutCarry()
                    0x10 -> rotateLeftB()
                    0x11 -> rotateLeftC()   //msg += "|RL C"
                    0x19 -> rotateRightC()
                    0x1A -> rotateRightD()
                    0x20 -> shiftLeftB()
                    0x26 -> shiftLeftValueAtHL()
                    0x27 -> shiftLeftA()
                    0x30 -> swapB()
                    0x33 -> swapE()
                    0x37 -> swapA()
                    0x38 -> shiftRightB()
                    0x3F -> shiftRightA()
                    0x40 -> bitCheck(b, 0)
                    0x41 -> bitCheck(c, 0)
                    0x47 -> bitCheck(a, 0)
                    0x48 -> bitCheck(b, 1)
                    0x49 -> bitCheck(c, 1)
                    0x4F -> bitCheck(a, 1)
                    0x50 -> bitCheck(b, 2)
                    0x57 -> bitCheck(a, 2)
                    0x58 -> bitCheck(b, 3)
                    0x59 -> bitCheck(c, 3)
                    0x5F -> bitCheck(a, 3)
                    0x60 -> bitCheck(b, 4)
                    0x61 -> bitCheck(c, 4)
                    0x67 -> bitCheck(a, 4)
                    0x68 -> bitCheck(b, 5)
                    0x69 -> bitCheck(c, 5)
                    0x6E -> {
                        bitCheck(read8(getCombinedValue(h, l)), 5)
                        m++
                    }
                    0x6F -> bitCheck(a, 5)
                    0x70 -> bitCheck(b, 6)
                    0x76 -> {
                        bitCheck(read8(getCombinedValue(h, l)), 6)
                        m++
                    }
                    0x77 -> bitCheck(a, 6)
                    0x78 -> bitCheck(b, 7)
                    0x79 -> bitCheck(c, 7)
                    0x7C -> bitCheck(h, 7)  //msg += "|BIT 7,H"
                    0x7E -> {
                        bitCheck(read8(getCombinedValue(h, l)), 7)
                        m++
                    }
                    0x7F -> bitCheck(a, 7)
                    0x80 -> resetB(0)
                    0x86 -> resetValueAtHL(0)
                    0x87 -> resetA(0)
                    0x8E -> resetValueAtHL(1)
                    0x90 -> resetB(2)
                    0x96 -> resetValueAtHL(2)
                    0x9E -> resetValueAtHL(3)
                    0xA0 -> resetB(4)
                    0xAE -> resetValueAtHL(5)
                    0xB0 -> resetB(6)
                    0xBE -> resetValueAtHL(7)
                    0xC0 -> setB(0)
                    0xC6 -> setValueAtHL(0)
                    0xC7 -> setA(0)
                    0xCE -> setValueAtHL(1)
                    0xCF -> setA(1)
                    0xD0 -> setB(2)
                    0xD6 -> setValueAtHL(2)
                    0xD7 -> setA(2)
                    0xD8 -> setB(3)
                    0xDE -> setValueAtHL(3)
                    0xE0 -> setB(4)
                    0xEE -> setValueAtHL(5)
                    0xEF -> setA(5)
                    0xF0 -> setB(6)
                    0xF8 -> setB(7)
                    0xFE -> setValueAtHL(7)
                    0xFF -> setA(7)
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
            0xCC -> call(ZERO)
            0xCD -> call(-1)   //msg += "|CALL u16"
            0xCE -> {
                addToAWithCarry(fetch())
                m++
            }
            0xCF -> restart(8u)
            0xD0 -> returnSelect(NO_CARRY)
            0xD1 -> popDE()
            0xD2 -> jump(NO_CARRY)
            0xD5 -> pushDE()  //msg="PUSH DE"
            0xD6 -> {
                subFromA(fetch())
                m++
            }
            0xD7 -> restart(10u)
            0xD8 -> returnSelect(CARRY)
            0xD9 -> returnSelect(ENABLE_INTERRUPTS)
            0xDA -> jump(CARRY)
            0xDF -> restart(0x18u)
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
            0xE8 -> addSignedToSP()
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
                //Log.i("GB.cpu", "Interrupts Disabled")
                m = 1u
            }
            0xF5 -> pushAF()//msg+="PUSH AF"
            0xF6 -> {
                orWithA(fetch())
                m++
            }
            0xF8 -> loadToHLsumOfSPAndI8()
            0xF9 -> {
                sp = getCombinedValue(h, l)
                m = 2u
            }
            0xFA -> {
                loadToA(read8(fetchTwice()))
                m += 3u
            }
            0xFB -> {
                if (read8(pc) != 0xF3.toUByte())
                    interruptsMasterEnabled = true  //msg+="EI"
                //Log.i("GB.cpu", "Interrupts Enabled")
                m = 1u
            }
            0xFE -> {
                compareWithA(fetch())
                m++
            }
            0xFF -> restart(38u)
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

    private fun loadToHLsumOfSPAndI8() {
        val lit = fetch().toByte()
        val res = sp.toInt() + lit
        f = 0u
        if (lit >= 0) {
            if (((sp.toInt() and 0xFF) + lit) > 0xFF)
                f = f or 0x10u
            if ((sp.toInt() and 0xF) + (lit.toInt() and 0xF) > 0xF)
                f = f or 0x20u
        } else {
            if ((res and 0xFF) <= (sp.toInt() and 0xFF))
                f = f or 0x10u
            if (res and 0xF <= sp.toInt() and 0xF)
                f = f or 0x20u
        }
        setHL(res.toUInt())
    }

    private fun decimalAdjustA() {
        val sub = f and 0x40u > 0u
        val half = f and 0x20u > 0u
        val carry = f and 0x10u > 0u
        f = f and 0x40u
        if (sub) {
            if (half)
                a = (a - 6u).toUByte()
            if (carry) {
                a = (a - 0x60u).toUByte()
                f = f or 0x10u
            }
        } else {
            if (half || a and 0xFu > 9u)
                a = (a + 6u).toUByte()
            if (carry || a and 0xF0u > 0x99u) {
                a = (a + 0x60u).toUByte()
                f = f or 0x10u
            }
        }
        if (a <= 0u)
            f = f or 0x80u
        m = 1u
    }

    private fun shiftRightA() {
        f = 0u
        f = f or ((a and 1u).toUInt() shl 4).toUByte()
        a = (a.toInt() shr 1).toUByte()
        if (a <= 0u)
            f = f or 0x80u
        m = 2u
    }

    private fun shiftRightB() {
        f = 0u
        f = f or ((b and 1u).toUInt() shl 4).toUByte()
        b = (b.toInt() shr 1).toUByte()
        if (b <= 0u)
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

    private fun shiftLeftB() {
        f = 0u
        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
        b = (b.toInt() shl 1).toUByte()
        if (b <= 0u)
            f = f or 0x80u
        m = 2u
    }

    private fun shiftLeftValueAtHL() {
        f = 0u
        var lit = read8(getCombinedValue(h, l))
        f = f or ((lit and 0x80u).toUInt() shr 3).toUByte()
        lit = (lit.toInt() shl 1).toUByte()
        if (lit <= 0u)
            f = f or 0x80u
        writeU8(getCombinedValue(h, l), lit)
        m = 4u
    }

    private fun shiftRightArithmeticB() {
        f = 0u
        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
        b = (b.toInt() shr 1 + 0x80).toUByte()
        if (b <= 0u)
            f = f or 0x80u
        m = 2u
    }

    private fun resetA(bitNo: Int) {
        a = a and (1 shl bitNo).inv().toUByte()
        m = 2u
    }

    private fun resetB(bitNo: Int) {
        b = b and (1 shl bitNo).inv().toUByte()
        m = 2u
    }

    private fun resetValueAtHL(bitNo: Int) {
        writeU8(
            getCombinedValue(h, l),
            read8(getCombinedValue(h, l)) and (1 shl bitNo).inv().toUByte()
        )
        m = 4u
    }

    private fun setA(bitNo: Int) {
        a = a or (1 shl bitNo).toUByte()
        m = 2u
    }

    private fun setB(bitNo: Int) {
        b = b or (1 shl bitNo).toUByte()
        m = 2u
    }

    private fun setValueAtHL(bitNo: Int) {
        writeU8(
            getCombinedValue(h, l),
            read8(getCombinedValue(h, l)) or (1 shl bitNo).toUByte()
        )
        m = 4u
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

    private fun swapB() {
        b = (((b and 0xFu).toInt() shl 4) or ((b and 0xF0u).toInt() shr 4)).toUByte()
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
        if (res == 0)
            f = f or 0x80u
        if ((a and 0xFu).toInt() - (lit and 0xFu).toInt() < 0)
            f = f or 0x20u
        if (res < 0)
            f = f or 0x10u
        a = res.toUByte()
        m = 1u
    }

    private fun compareWithA(lit: UByte) {
        f = 0x40u
        val res = (a - lit).toInt()
        if ((res and 0xFF) == 0)
            f = f or 0x80u
        if (res < 0)
            f = f or 0x10u
        if ((a and 0xFu).toInt() - (lit and 0xFu).toInt() < 0)
            f = f or 0x20u
        m = 1u
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
            NO_CARRY -> {
                if ((f and 0x10u).toUInt() == 0u) {
                    pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                    m += 3u
                }
            }
            CARRY -> {
                if ((f and 0x10u).toUInt() != 0u) {
                    pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                    m += 3u
                }
            }
            else -> {
                pc = (read8(sp++).toInt() or (read8(sp++).toInt() shl 8)).toUShort()
                m += 2u
            }
        }
    }

    private fun nonPrefixRotateLeftA() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
        a = (a.toInt() shl 1).toUByte()
        if (carry)
            a++
        m = 1u
    }

    private fun nonPrefixRotateRightA() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((a and 1u).toUInt() shl 4).toUByte()
        a = (a.toInt() shr 1).toUByte()
        if (carry)
            a = a or 0x80u
        m = 1u
    }

    private fun nonPrefixRotateRightAWithoutCarry() {
        val carry = (a and 1u) > 0u
        f = 0u
        f = f or ((a and 1u).toUInt() shl 4).toUByte()
        a = (a.toInt() shr 1).toUByte()
        if (carry)
            a = a or 0x80u
        m = 1u
    }

    private fun rotateLeftB() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
        b = (b.toInt() shl 1).toUByte()
        if (carry)
            b++
        if (b == 0.toUByte())
            f = f or 0x80u
        m = 2u
    }

    private fun rotateLeftC() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        m = 1u
        f = f or ((c and 0x80u).toUInt() shr 3).toUByte()
        c = (c.toInt() shl 1).toUByte()
        if (carry)
            c++
        if (c == 0.toUByte())
            f = f or 0x80u
        m = 2u
    }

    private fun rotateLeftBWithoutCarry() {
        val carry = (b and 0x80u) > 0u
        f = 0u
        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
        b = (b.toInt() shl 1).toUByte()
        if (carry)
            b++
        if (b == 0.toUByte())
            f = f or 0x80u
        m = 2u
    }

    private fun rotateRightC() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((c and 1u).toUInt() shl 4).toUByte()
        c = (c.toInt() shr 1).toUByte()
        if (carry)
            c = c or 0x80u
        if (c == 0.toUByte())
            f = f or 0x80u
        m = 2u
    }

    private fun rotateRightD() {
        val carry = (f and 0x10u) > 0u
        f = 0u
        f = f or ((d and 1u).toUInt() shl 4).toUByte()
        d = (d.toInt() shr 1).toUByte()
        if (carry)
            d = d or 0x80u
        if (d == 0.toUByte())
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

    private fun call(condition: Int) {
        val temp = fetchTwice()
        m = 3u
        when (condition) {
            NOT_ZERO ->
                if ((f and 0x80u).toUInt() == 0u) {
                    writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                    writeU8(--sp, pc.toUByte())
                    pc = temp
                    m += 3u
                }
            ZERO ->
                if ((f and 0x80u).toUInt() != 0u) {
                    writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                    writeU8(--sp, pc.toUByte())
                    pc = temp
                    m += 3u
                }
            NO_CARRY ->
                if ((f and 0x10u).toUInt() == 0u) {
                    writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                    writeU8(--sp, pc.toUByte())
                    pc = temp
                    m += 3u
                }
            CARRY ->
                if ((f and 0x10u).toUInt() != 0u) {
                    writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                    writeU8(--sp, pc.toUByte())
                    pc = temp
                    m += 3u
                }
            else -> {
                writeU8(--sp, (pc.toUInt() shr 8).toUByte())
                writeU8(--sp, pc.toUByte())
                pc = temp
                m += 3u
            }
        }
    }

    private fun decA() {
        val res: UByte = (a - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((a and 0xFu).toInt() - 1 < 0)
            f = f or 0x20u
        a = res
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

    private fun decH() {
        val res: UByte = (h - 1u).toUByte()
        f = f and 0x10u
        f = f or 0x40u
        if (res == 0.toUByte())
            f = f or 0x80u
        if ((h and 0xFu) - 1u < 0u)
            f = f or 0x20u
        h = res
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

    private fun decDE() {
        setHL(getCombinedValue(d, e) - 1u)
        m = 2u
    }

    private fun decHL() {
        setHL(getCombinedValue(h, l) - 1u)
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

    private fun addSignedToSP() {
        f = 0u
        val lit = fetch().toByte()
        val res = sp.toInt() + lit
        if ((sp and 0xFFFu).toInt() + lit > 0xFFF) f = f or 0x20u
        if (res.toUShort() > 0xFFFFu) f = f or 0x10u
        sp = res.toUShort()
        m = 4u
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
            NO_CARRY -> {
                if ((f and 0x10u).toUInt() <= 0u) {
                    pc = temp
                    m++
                }
            }
            CARRY -> {
                if ((f and 0x10u).toUInt() != 0u) {
                    pc = temp
                    m++
                }
            }
            else -> {
                pc = temp
                m++
            }
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

    private fun andWithA(lit: UByte) {
        f = 2u
        a = a and lit
        if (a == 0.toUByte()) f = f or 0x80u
        m = 1u
    }

    private fun orWithA(lit: UByte) {
        a = a or lit
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