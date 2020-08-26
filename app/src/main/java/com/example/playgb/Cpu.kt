package com.example.playgb

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

private const val NOT_ZERO = 0
private const val ZERO = 1
private const val NO_CARRY = 2
private const val CARRY = 3
private const val ENABLE_INTERRUPTS = 4
private const val TO_HL = 4

@ExperimentalUnsignedTypes
private const val SERIAL_TIMEOUT = 300000000u

var int60 = false


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
    private var mbcRomMaskLower: UByte = 0u
    private var mbcRomMaskUpper: UByte = 0u
    private var ramBankNumber: Byte = 0
    private var mbcRamMask: UByte = 0u
    private var ramBankMode = false
    private var padButtonMode = false
    private var padDirectionMode = false
    private var m = 0u
    private var time = 0u
    private var stepCounter = 0
    private var serialByte: UByte = 0u
    private var serialControl: UByte = 0u
    private var halted = false
    private var stopped = false
    private var startLatch = false
    private var extraRamEnabled = false
    private lateinit var extraRamFile: RandomAccessFile

    private fun toPadBit(bitNo: Int): Int {
        return (if (joyPad[bitNo + if (padDirectionMode) 0 else 4]) 0 else 1) shl bitNo
    }

    private fun getJoyPad(): UByte {
        return if (padDirectionMode || padButtonMode) {
            var data: UByte = 0u
            repeat(4) {
                data = data or toPadBit(it).toUByte()
            }
            data
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
                            0x13 -> {
                                if (ramBankMode) {
                                    Log.i(
                                        "External Ram",
                                        "Read ${extraRam[(address.toInt() and 0x1FFF)]} from $address"
                                    )
                                    extraRam[(address.toInt() and 0x1FFF)].toUByte()
                                } else
                                    TODO("Access RTC " + address.toString(16))
                            }
                            else -> TODO("Access External Ram")
                        }
                    else {
                        Log.i(
                            "External Ram",
                            "Read ${extraRam[(address.toInt() and 0x1FFF)]} from $address (ROM ONLY)"
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
                                        Log.w("GB.mmu", "Joypad interrupt enabled")
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
                                    padButtonMode = data and 0x20u <= 0u
                                    padDirectionMode = data and 0x10u <= 0u
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
            0x0000, 0x1000 ->   //Ram Enable (0xXA enable,else disable)
                if (cartType > 0) {
                    Log.i("EnableRAM", String.format("%02X", data.toByte()))
                    extraRamEnabled = data and 0xFu == 0xA.toUByte()
                    if (!extraRamEnabled && !(cartType == 0 || cartType == 1 || cartType == 0x11)) {
                        extraRamFile.seek(ramBankNumber * 8192L)
                        extraRamFile.write(extraRam)
                    }
                } else Log.w("MBC", "Write $data ignored to RamEnable")
            0x2000, 0x3000 ->   //Rom Bank Lower 5 bits (MBC1)/Rom Bank 7 bits (MBC3)
                if (cartType > 0) {
                    val prevBank = romBankNumber
                    if (cartType < 4)
                    //MBC1 has maximum 125 banks (0x20,0x40,0x60 not available)
                    {
                        romBankNumber =
                            ((romBankNumber.toInt() and 0x60) or ((data and mbcRomMaskLower).toInt())).toByte()
                        if ((data and mbcRomMaskLower) <= 0u)
                            romBankNumber++
                        if (!ramBankMode) {
                            if (prevBank != romBankNumber) {
                                Log.w(
                                    "GB.mmu",
                                    "Switch Rom Bank to 0x" + romBankNumber.toString(16)
                                )
                                val ifs = FileInputStream(romFile)
                                val bis = BufferedInputStream(ifs)
                                bis.skip(romBankNumber * 16384L)
                                bis.read(rom, 16384, 16384)
                                bis.close()
                            }
                        } else {
                            Log.w(
                                "GBTest.mmu",
                                "Switch Rom Bank to 0x" + (romBankNumber.toInt() and 0x1F).toString(
                                    16
                                )
                            )
                            val ifs = FileInputStream(romFile)
                            val bis = BufferedInputStream(ifs)
                            bis.skip((romBankNumber.toInt() and 0x1F) * 16384L)
                            bis.read(rom, 16384, 16384)
                            bis.close()
                        }
                    }
                    if (cartType in 0xF..0x13)
                    //MBC3 has maximum 128 banks (Both MBC1 and MBC3 cannot change switchable bank to bank 0)
                    {
                        romBankNumber = ((data and mbcRomMaskLower).toByte())
                        if (romBankNumber.toInt() == 0)
                            romBankNumber++
                        if (prevBank != romBankNumber) {
                            //Log.w("GB.mmu", "Switch Rom Bank to 0x" + romBankNumber.toString(16))
                            val ifs = FileInputStream(romFile)
                            val bis = BufferedInputStream(ifs)
                            bis.skip(romBankNumber * 16384L)
                            bis.read(rom, 16384, 16384)
                            bis.close()
                        }
                    }

                } else Log.w("MBC", "Write $data to RomLowerBank ignored")
            0x4000, 0x5000 ->
                if (cartType > 0) {
                    if (cartType < 4) { //Rom Bank Upper 2 bits OR Ram Bank (MBC1)
                        if (ramBankMode) {
                            val prevBank = ramBankNumber
                            ramBankNumber = (data and mbcRamMask).toByte()
                            if (prevBank != ramBankNumber) {
                                Log.w(
                                    "GB.mmu",
                                    "Switch Ram Bank to 0x" + String.format("%02X", ramBankNumber)
                                )
                                extraRamFile.seek(prevBank * 8192L)
                                extraRamFile.write(extraRam)
                                extraRamFile.seek(ramBankNumber * 8192L)
                                extraRamFile.read(extraRam)
                            }
                        } else {
                            val prevBank = romBankNumber
                            romBankNumber =
                                ((romBankNumber.toInt() and 0x1F) or ((data and mbcRomMaskUpper).toInt() shl 5)).toByte()
                            if (prevBank != romBankNumber) {
                                Log.w(
                                    "GB.mmu",
                                    "Switch Rom Bank to 0x" + romBankNumber.toString(16)
                                )
                                val ifs = FileInputStream(romFile)
                                val bis = BufferedInputStream(ifs)
                                bis.skip(romBankNumber * 16384L)
                                bis.read(rom, 16384, 16384)
                                bis.close()
                            }
                        }
                    }
                    if (cartType in 0xF..0x13) {  //Ram Bank/RTC Select (MBC3)
                        if (data and 0xFu < 8u) {
                            ramBankMode = true
                            val prevBank = ramBankNumber
                            ramBankNumber = (data and mbcRamMask).toByte()
                            if (prevBank != ramBankNumber) {
                                /*Log.w(
                                    "GB.mmu",
                                    "Switch Ram Bank to 0x" + String.format("%02X", ramBankNumber)
                                )*/
                                extraRamFile.seek(prevBank * 8192L)
                                extraRamFile.write(extraRam)
                                extraRamFile.seek(ramBankNumber * 8192L)
                                extraRamFile.read(extraRam)
                            }
                        } else {
                            ramBankMode = false
                            TODO("Write RTC " + data.toString(16))
                        }
                    }
                } else Log.w("MBC", "Write $data to RomUpperBank/RamBank ignored")
            0x6000, 0x7000 -> if (cartType > 0) {
                if (cartType < 4) {
                    ramBankMode = (data and 1u) != 0.toUByte()
                    Log.w("GB.mmu", "Ram Bank Mode = $ramBankMode")
                }
                if (cartType in 0xF..0x13) {  //Latch Clock Data
                    if (data <= 0u)
                        startLatch = true
                    if (data.toInt() == 1 && startLatch) {
                        tmr.latchClockData()
                    }
                }
            } else Log.w("MBC", "Write $data ignored to RamBank/RomUpperBankSelect")
            0x8000, 0x9000 -> {
                if (address in 0x1880u..0x189Fu)
                    Log.i(
                        "GPUTest",
                        "address:" + address.toString(16) + " data:" + data.toString(16)
                    )
                gpu.writeToVRam(address, data)
            }
            0xA000, 0xB000 -> if (extraRamEnabled) {
                if (cartType > 0)
                    when (cartType) {
                        2, 3 -> {
                            Log.i("External Ram", "Write $data to $address")
                            extraRam[(address.toInt() and 0x1FFF)] = data.toByte()
                            //extraRamFile.seek(ramBankNumber * 8192L)
                            //extraRamFile.write(extraRam)
                        }
                        0x13 -> {
                            if (ramBankMode) {
                                Log.i("External Ram", "Write $data to $address")
                                extraRam[(address.toInt() and 0x1FFF)] = data.toByte()
                            } else
                                TODO("Access RTC " + address.toString(16))
                        }
                        else -> TODO("Access External Ram to be verified")
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
                "|L=0x" + String.format(
            "%02X",
            l.toByte()
        ) + "|ROMBK=" + romBankNumber.toString(16) +
                "|RAMBK=$ramBankNumber" + "|RAMBKMD=$ramBankMode" +
                "|EmuTime="
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

    private fun runTillCrash() {
        val ifs = FileInputStream(romFile)
        val bis = BufferedInputStream(ifs)
        bis.read(rom)
        bis.close()
        val romSize = if (rom[0x148] < 9) 32 shl rom[0x148].toInt()
        else when (rom[0x148].toInt()) {
            0x54 -> 1536
            0x53 -> 1229
            else -> 1127
        }
        Log.i("0x148", rom[0x148].toString(16))
        Log.i("0x149", rom[0x149].toString(16))
        mbcRomMaskLower = when (rom[0x148].toInt()) {
            0 -> 1u
            1 -> 3u
            2 -> 7u
            3 -> 0xFu
            else -> 0x1Fu
        }
        mbcRomMaskUpper = when (rom[0x148].toInt()) {
            5 -> 1u
            6, 0x52, 0x53, 0x54 -> 3u
            7 -> {
                TODO("256 Banks")
                7u
            }
            8 -> {
                TODO("512 Banks")
                0xFu
            }
            else -> 0u
        }
        mbcRamMask = when (rom[0x149].toInt()) {
            0 -> 0u
            3 -> 3u
            5 -> 7u
            4 -> {
                TODO("16 Banks")
                0xFu
            }
            else -> 1u
        }
        if (mbcRamMask > 0u) {
            extraRamFile = RandomAccessFile(
                File(romFile.parentFile, romFile.nameWithoutExtension + ".sav"),
                "rwd"
            )
            if (extraRamFile.length() != 0L)
                extraRamFile.read(extraRam)
        }
        Log.i("Rom Size", if (romSize > 1000) "${romSize / 1024} MB" else "$romSize KB")
        cartType = rom[0x147].toInt()
        if (cartType > 0x13 || cartType !in 5..6)
            TODO("MBC " + cartType.toString(16))
        Log.i("MBC Type", cartType.toString(16) + "h")
        //00h  ROM ONLY
        //01h  MBC1
        //03h  MBC1+RAM+BATTERY
        //13h  MBC3+RAM+BATTERY
        while (!cpuCrash) {
            if (!isAppRunning)
                break
            if (isAppPaused)
                continue
            if (startDump) {
                val file = File(dumpFolder, "gpu.dat")
                file.writeBytes(gpu.dumpVRAM())
                file.appendBytes(gpu.dumpOAM())
                file.appendBytes(gpu.dumpMeta())
                val file2 = File(dumpFolder, "oam.txt")
                file2.writeText(gpu.dumpOAMasText())
                startDump = false
            }
            if (startStepping) {
                if (stepRequested) {
                    if (!stopped) {
                        if (halted)
                            m = 1u
                        else
                            execute()
                        updateInterruptFlag()
                        checkInterrupts()

                    }
                    updateINT60()
                    stepCounter++
                    if (stepCounter == 70000) {
                        Log.i("CPU Log", log())
                        stepRequested = false
                        stepCounter = 0
                    }
                }
            } else {
                if (!stopped) {
                    if (halted)
                        m = 1u
                    else
                        execute()
                    updateInterruptFlag()
                    checkInterrupts()

                }
                updateINT60()
            }

        }
        Log.i("CPU Log", log())
        Log.i("GPU Log", gpu.log())
        //dumpROM()
    }

    private fun updateINT60() {
        if (int60) {
            int60 = false
            interruptFlag = interruptFlag or 0x10u
            if (stopped)
                stopped = false
        }
    }

    private fun updateInterruptFlag() {
        if (gpu.isVBlank()) {
            interruptFlag = interruptFlag or 1u
//            val file = File(dumpFolder, "vram.txt")
//            file.writeText(gpu.dumpVRAM())
        }
        if (gpu.statInterrupted())
            interruptFlag = interruptFlag or 2u
        if (tmr.hasOverflowOccurred())
            interruptFlag = interruptFlag or 4u
        if (time % SERIAL_TIMEOUT == 0u && interruptFlag and 8u <= 0u)
            if (serialControl and 0x80u > 0u) {
                serialByte = 0xFFu
                serialControl = serialControl and 1u
                interruptFlag = interruptFlag or 8u
            }
        updateINT60()
    }

    private fun execute() {
        //TODO Replace unsigned checks with signed checks
        val op = fetch()
        when (op.toInt()) {
            //0x0X instructions
            0 ->    //NOP
                m = 1u
            1 ->    //LD BC,u16
            {
                c = fetch()
                b = fetch()
                m = 3u
            }
            2 ->    //LD [BC],A
            {
                writeU8(getCombinedValue(b, c), a)
                m = 2u
            }
            3 ->    //INC BC
            {
                setBC(getCombinedValue(b, c) + 1u)
                m = 2u
            }
            4 ->    //INC B
            {
                f = f and 0x10u
                if ((b and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                b++
                if (b <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            5 ->    //DEC B
            {
                val res = (b - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((b and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                b = res
                m = 1u
            }
            6 ->    //LD B,u8
            {
                b = fetch()
                m = 2u
            }
            7 ->    //RLCA
            {
                f = 0u
                f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                a = (a.toInt() shl 1).toUByte()
                if (f and 0x10u > 0u)
                    a++
                m = 1u
            }
            8 ->    //LD [u16],SP
            {
                val temp = fetchTwice()
                writeU8(temp, sp.toUByte())
                writeU8((temp + 1u).toUShort(), (sp.toUInt() shr 8).toUByte())
                m = 5u
            }
            9 ->    //ADD HL,BC
            {
                addHL(getCombinedValue(b, c))
            }

            0xA ->  //LD A,[BC]
            {
                a = read8(getCombinedValue(b, c))
                m = 2u
            }
            0xB ->  //DEC BC
            {
                setBC(getCombinedValue(b, c) - 1u)
                m = 2u
            }
            0xC ->  //INC C
            {
                f = f and 0x10u
                if ((c and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                c++
                if (c <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0xD ->  //DEC C
            {
                val res = (c - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((c and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                c = res
                m = 1u
            }
            0xE ->  //LD C,u8
            {
                c = fetch()
                m = 2u
            }
            0xF ->  //RRCA
            {
                f = 0u
                f = f or ((a and 1u).toUInt() shl 4).toUByte()
                a = (a.toInt() shr 1).toUByte()
                if (f and 0x10u > 0u)
                    a = a or 0x80u
                m = 1u
            }
            //0x1X instructions
            0x10 -> //STOP
            {
                m = 1u
                fetch()
                stopped = true
            }
            0x11 -> //LD DE,u16
            {
                e = fetch()
                d = fetch()
                m = 3u
            }
            0x12 -> //LD [DE],A
            {
                writeU8(getCombinedValue(d, e), a)
                m = 2u
            }
            0x13 -> //INC DE
            {
                setDE(getCombinedValue(d, e) + 1u)
                m = 2u
            }
            0x14 -> //INC D
            {
                f = f and 0x10u
                if ((d and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                d++
                if (d <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0x15 -> //DEC D
            {
                val res = (d - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((d and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                d = res
                m = 1u
            }
            0x16 -> //LD D,u8
            {
                d = fetch()
                m = 2u
            }
            0x17 -> //RLA (through carry)
            {
                val carry = (f and 0x10u) > 0u
                f = 0u
                f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                a = (a.toInt() shl 1).toUByte()
                if (carry)
                    a++
                m = 1u
            }
            0x18 -> //JR i8
                jumpRel(-1)
            0x19 -> //ADD HL,DE
            {
                addHL(getCombinedValue(d, e))
            }

            0x1A -> //LD A,[DE]
            {
                a = read8(getCombinedValue(d, e))
                m = 2u
            }
            0x1B -> //DEC DE
            {
                setDE(getCombinedValue(d, e) - 1u)
                m = 2u
            }
            0x1C -> //INC E
            {
                f = f and 0x10u
                if ((e and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                e++
                if (e <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0x1D -> //DEC E
            {
                val res = (e - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((e and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                e = res
                m = 1u
            }
            0x1E -> //LD E,u8
            {
                e = fetch()
                m = 2u
            }
            0x1F -> //RRA (through carry)
            {
                val carry = (f and 0x10u) > 0u
                f = 0u
                f = f or ((a and 1u).toUInt() shl 4).toUByte()
                a = (a.toInt() shr 1).toUByte()
                if (carry)
                    a = a or 0x80u
                m = 1u
            }
            //0x2X Instructions
            0x20 -> //JR NZ,i8
                jumpRel(NOT_ZERO)
            0x21 -> //LD HL,u16
            {
                l = fetch()
                h = fetch()
                m = 3u
            }
            0x22 -> //LDI [HL],A
            {
                writeU8(getCombinedValue(h, l), a)
                setHL(getCombinedValue(h, l) + 1u)
                m = 2u
            }
            0x23 -> //INC HL
            {
                setHL(getCombinedValue(h, l) + 1u)
                m = 2u
            }
            0x24 -> //INC H
            {
                f = f and 0x10u
                if ((h and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                h++
                if (h <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0x25 -> //DEC H
            {
                val res = (h - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((h and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                h = res
                m = 1u
            }
            0x26 -> //LD H,u8
            {
                h = fetch()
                m = 2u
            }
            0x27 -> //DAA
            {
                var t = 0
                m = 1u
                f = f and 0x70u
                val half = f and 0x20u > 0u
                val carry = f and 0x10u > 0u
                if (half || a and 0xFu > 9u)
                    t++
                if (carry || a > 0x99u) {
                    t += 2
                    f = f or 0x10u
                }
                val negate = f and 0x40u > 0u
                // builds final H flag
                f = if (negate && !half)
                    f and 0xD0u
                else {
                    if (negate && half)
                        if (a and 0xFu < 6u)
                            f or 0x20u
                        else
                            f and 0xD0u
                    else
                        if (a and 0xFu > 9u)
                            f or 0x20u
                        else
                            f and 0xD0u
                }
                when (t) {
                    1 -> a = (a + if (negate) 0xFAu else 6u).toUByte() // -6:6
                    2 -> a = (a + if (negate) 0xA0u else 0x60u).toUByte() // -0x60:0x60
                    3 -> a = (a + if (negate) 0x9Au else 0x66u).toUByte() // -0x66:0x66
                    else -> {
                    }
                }
                if (a <= 0u)
                    f = f or 0x80u
                /*val half = f and 0x20u > 0u
                val carry = f and 0x10u > 0u
                var log=false
                if(f>0u){
                    log=true
                Log.i("DAATest1",(f.toInt() shr 4).toString(2)+" "+a.toString(16))}
                f = f and 0x40u
                var tmp=a.toInt()
                if (f and 0x40u > 0u) { //Subtraction
                    if (carry) {
                        tmp-=0x60
                        a = (a - 0x60u).toUByte()
                        f = f or 0x10u
                    if (half){
                        tmp-=6
                        a = (a - 6u).toUByte()}
                    }
                } else {                //Addition
                    if (carry || a > 0x99u) {
                        a = (a + 0x60u).toUByte()
                        f = f or 0x10u
                    }
                    if (half || a and 0xFu > 9u)
                        a = (a + 6u).toUByte()
                }
                if (a <= 0u)
                    f = f or 0x80u
                if(log)
                Log.i("DAATest2",(f.toInt() shr 4).toString(2)+" "+a.toString(16))*/
            }
            0x28 -> //JR Z,i8
                jumpRel(ZERO)
            0x29 -> //ADD HL,HL
            {
                addHL(getCombinedValue(h, l))
            }

            0x2A -> //LDI A,[HL]
            {
                a = read8(getCombinedValue(h, l))
                setHL(getCombinedValue(h, l) + 1u)
                m = 2u
            }
            0x2B -> //DEC HL
            {
                setHL(getCombinedValue(h, l) - 1u)
                m = 2u
            }
            0x2C -> //INC L
            {
                f = f and 0x10u
                if ((l and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                l++
                if (l <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0x2D -> //DEC L
            {
                val res = (l - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((l and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                l = res
                m = 1u
            }
            0x2E -> //LD L,u8
            {
                l = fetch()
                m = 2u
            }
            0x2F -> //CPL A
            {
                f = f or 0x60u
                a = a.inv()
                m = 1u
            }
            //0x3X Instructions
            0x30 -> //JR NC,i8
                jumpRel(NO_CARRY)
            0x31 -> //LD SP,u16
            {
                sp = fetchTwice()
                m = 3u
            }
            0x32 -> //LDD [HL],A
            {
                writeU8(getCombinedValue(h, l), a)
                setHL(getCombinedValue(h, l) - 1u)
                m = 2u
            }
            0x33 -> //INC SP
            {
                sp++
                m = 2u
            }
            0x34 -> //INC [HL]
            {
                var res = read8(getCombinedValue(h, l))
                f = f and 0x10u
                if ((res and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                res++
                if (res <= 0u)
                    f = f or 0x80u
                writeU8(getCombinedValue(h, l), res)
                m = 3u
            }
            0x35 -> //DEC [HL]
            {
                val res = (read8(getCombinedValue(h, l)) - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((read8(getCombinedValue(h, l)) and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                writeU8(getCombinedValue(h, l), res)
                m = 3u
            }
            0x36 -> //LD [HL],u8
            {
                writeU8(getCombinedValue(h, l), fetch())
                m = 3u
            }
            0x37 -> //SCF (Set Carry)
            {
                f = (f and 0x80u) or 0x10u
                m = 1u
            }
            0x38 -> //JR C,i8
                jumpRel(CARRY)
            0x39 -> //ADD HL,SP
                addHL(sp)
            0x3A -> //LDD A,[HL]
            {
                a = read8(getCombinedValue(h, l))
                setHL(getCombinedValue(h, l) - 1u)
                m = 2u
            }
            0x3B -> //DEC SP
            {
                sp--
                m = 2u
            }
            0x3C -> //INC A
            {
                f = f and 0x10u
                if ((a and 0xFu) + 1u > 0xFu)
                    f = f or 0x20u
                a++
                if (a <= 0u)
                    f = f or 0x80u
                m = 1u
            }
            0x3D -> //DEC A
            {
                val res = (a - 1u).toUByte()
                f = f and 0x10u
                f = f or 0x40u
                if (res <= 0u)
                    f = f or 0x80u
                if ((a and 0xFu).toInt() - 1 < 0)
                    f = f or 0x20u
                a = res
                m = 1u
            }
            0x3E -> //LD A,u8
            {
                a = fetch()
                m = 2u
            }
            0x3F -> //CCF (Toggle Carry)
            {
                f = (f xor 0x10u) and 0x90u
                m = 1u
            }
            //0x4X Instructions
            0x40 -> //LD B,B
                m = 1u
            0x41 -> //LD B,C
            {
                b = c
                m = 1u
            }
            0x42 -> //LD B,D
            {
                b = d
                m = 1u
            }
            0x43 -> //LD B,E
            {
                b = e
                m = 1u
            }
            0x44 -> //LD B,H
            {
                b = h
                m = 1u
            }
            0x45 -> //LD B,L
            {
                b = l
                m = 1u
            }
            0x46 -> //LD B,[HL]
            {
                b = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x47 -> //LD B,A
            {
                b = a
                m = 1u
            }
            0x48 -> //LD C,B
            {
                c = b
                m = 1u
            }
            0x49 -> //LD C,C
                m = 1u
            0x4A -> //LD C,D
            {
                c = d
                m = 1u
            }
            0x4B -> //LD C,E
            {
                c = e
                m = 1u
            }
            0x4C -> //LD C,H
            {
                c = h
                m = 1u
            }
            0x4D -> //LD C,L
            {
                c = l
                m = 1u
            }
            0x4E -> //LD C,[HL]
            {
                c = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x4F -> //LD C,A
            {
                c = a
                m = 1u
            }
            //0x5X Instructions
            0x50 -> //LD D,B
            {
                d = b
                m = 1u
            }
            0x51 -> //LD D,C
            {
                d = c
                m = 1u
            }
            0x52 -> //LD D,D
                m = 1u
            0x53 -> //LD D,E
            {
                d = e
                m = 1u
            }
            0x54 -> //LD D,H
            {
                d = h
                m = 1u
            }
            0x55 -> //LD D,L
            {
                d = l
                m = 1u
            }
            0x56 -> //LD D,[HL]
            {
                d = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x57 -> //LD D,A
            {
                d = a
                m = 1u
            }
            0x58 -> //LD E,B
            {
                e = b
                m = 1u
            }
            0x59 -> //LD E,C
            {
                e = c
                m = 1u
            }
            0x5A -> //LD E,D
            {
                e = d
                m = 1u
            }
            0x5B -> //LD E,E
                m = 1u
            0x5C -> //LD E,H
            {
                e = h
                m = 1u
            }
            0x5D -> //LD E,L
            {
                e = l
                m = 1u
            }
            0x5E -> //LD E,[HL]
            {
                e = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x5F -> //LD E,A
            {
                e = a
                m = 1u
            }
            //0x6X Instructions
            0x60 -> //LD H,B
            {
                h = b
                m = 1u
            }
            0x61 -> //LD H,C
            {
                h = c
                m = 1u
            }
            0x62 -> //LD H,D
            {
                h = d
                m = 1u
            }
            0x63 -> //LD H,E
            {
                h = e
                m = 1u
            }
            0x64 -> //LD H,H
                m = 1u
            0x65 -> //LD H,L
            {
                h = l
                m = 1u
            }
            0x66 -> //LD H,[HL]
            {
                h = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x67 -> //LD H,A
            {
                h = a
                m = 1u
            }
            0x68 -> //LD L,B
            {
                l = b
                m = 1u
            }
            0x69 -> //LD L,C
            {
                l = c
                m = 1u
            }
            0x6A -> //LD L,D
            {
                l = d
                m = 1u
            }
            0x6B -> //LD L,E
            {
                l = e
                m = 1u
            }
            0x6C -> //LD L,H
            {
                l = h
                m = 1u
            }
            0x6D -> //LD L,L
                m = 1u
            0x6E -> //LD L,[HL]
            {
                l = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x6F -> //LD L,A
            {
                l = a
                m = 1u
            }
            //0x7X Instructions
            0x70 -> //LD [HL],B
            {
                writeU8(getCombinedValue(h, l), b)
                m = 2u
            }
            0x71 -> //LD [HL],C
            {
                writeU8(getCombinedValue(h, l), c)
                m = 2u
            }
            0x72 -> //LD [HL],D
            {
                writeU8(getCombinedValue(h, l), d)
                m = 2u
            }
            0x73 -> //LD [HL],E
            {
                writeU8(getCombinedValue(h, l), e)
                m = 2u
            }
            0x74 -> //LD [HL],H
            {
                writeU8(getCombinedValue(h, l), h)
                m = 2u
            }
            0x75 -> //LD [HL],L
            {
                writeU8(getCombinedValue(h, l), l)
                m = 2u
            }
            0x76 -> //HALT
            {
                halted = true
                m = 1u
            }
            0x77 -> //LD [HL],A
            {
                writeU8(getCombinedValue(h, l), a)
                m = 2u
            }
            0x78 -> //LD A,B
            {
                a = b
                m = 1u
            }
            0x79 -> //LD A,C
            {
                a = c
                m = 1u
            }
            0x7A -> //LD A,D
            {
                a = d
                m = 1u
            }
            0x7B -> //LD A,E
            {
                a = e
                m = 1u
            }
            0x7C -> //LD A,H
            {
                a = h
                m = 1u
            }
            0x7D -> //LD A,L
            {
                a = l
                m = 1u
            }
            0x7E -> //LD A,[HL]
            {
                a = read8(getCombinedValue(h, l))
                m = 2u
            }
            0x7F -> //LD A,A
                m = 1u
            //0x8X Instructions
            0x80 -> //ADD A,B
                addToA(b)
            0x81 -> //ADD A,C
                addToA(c)
            0x82 -> //ADD A,D
                addToA(d)
            0x83 -> //ADD A,E
                addToA(e)
            0x84 -> //ADD A,H
                addToA(h)
            0x85 -> //ADD A,L
                addToA(l)
            0x86 -> //ADD A,[HL]
            {
                addToA(read8(getCombinedValue(h, l)))
                m++
            }
            0x87 -> //ADD A,A
                addToA(a)
            0x88 -> //ADC A,B
                addToAWithCarry(b)
            0x89 -> //ADC A,C
                addToAWithCarry(c)
            0x8A -> //ADC A,D
                addToAWithCarry(d)
            0x8B -> //ADC A,E
                addToAWithCarry(e)
            0x8C -> //ADC A,H
                addToAWithCarry(h)
            0x8D -> //ADC A,L
                addToAWithCarry(l)
            0x8E -> //ADC A,[HL]
            {
                addToAWithCarry(read8(getCombinedValue(h, l)))
                m++
            }
            0x8F -> //ADC A,A
                addToAWithCarry(a)
            //0x9X Instructions
            0x90 -> //SUB A,B
                subFromA(b)
            0x91 -> //SUB A,C
                subFromA(c)
            0x92 -> //SUB A,D
                subFromA(d)
            0x93 -> //SUB A,E
                subFromA(e)
            0x94 -> //SUB A,H
                subFromA(h)
            0x95 -> //SUB A,L
                subFromA(l)
            0x96 -> //SUB A,[HL]
            {
                subFromA(read8(getCombinedValue(h, l)))
                m++
            }
            0x97 -> //SUB A,A
            {
                a = 0u
                f = 0xC0u
                m = 1u
            }
            0x98 -> //SBC A,B
                subFromAWithBorrow(b)
            0x99 -> //SBC A,C
                subFromAWithBorrow(c)
            0x9A -> //SBC A,D
                subFromAWithBorrow(d)
            0x9B -> //SBC A,E
                subFromAWithBorrow(e)
            0x9C -> //SBC A,H
                subFromAWithBorrow(h)
            0x9D -> //SBC A,L
                subFromAWithBorrow(l)
            0x9E -> //SBC A,[HL]
            {
                subFromAWithBorrow(read8(getCombinedValue(h, l)))
                m++
            }
            0x9F -> //SBC A,A
            {
                if (f and 0x10u > 0u) {
                    a = 0xFFu
                    f = 0x70u
                } else {
                    a = 0u
                    f = 0xC0u
                }
                m = 1u
            }
            //0xAX Instructions
            0xA0 -> //AND A,B
                andWithA(b)
            0xA1 -> //AND A,C
                andWithA(c)
            0xA2 -> //AND A,D
                andWithA(d)
            0xA3 -> //AND A,E
                andWithA(e)
            0xA4 -> //AND A,H
                andWithA(h)
            0xA5 -> //AND A,L
                andWithA(l)
            0xA6 -> //AND A,[HL]
            {
                andWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xA7 -> //AND A,A
            {
                f = if (a <= 0u)
                    0xA0u
                else
                    0x20u
                m = 1u
            }
            0xA8 -> //XOR A,B
                xorWithA(b)
            0xA9 -> //XOR A,C
                xorWithA(c)
            0xAA -> //XOR A,D
                xorWithA(d)
            0xAB -> //XOR A,E
                xorWithA(e)
            0xAC -> //XOR A,H
                xorWithA(h)
            0xAD -> //XOR A,L
                xorWithA(l)
            0xAE -> //XOR A,[HL]
            {
                xorWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xAF -> //XOR A,A
            {
                a = 0u
                f = 0x80u
                m = 1u
            }
            //0xBX Instructions
            0xB0 -> //OR A,B
                orWithA(b)
            0xB1 -> //OR A,C
                orWithA(c)
            0xB2 -> //OR A,D
                orWithA(d)
            0xB3 -> //OR A,E
                orWithA(e)
            0xB4 -> //OR A,H
                orWithA(h)
            0xB5 -> //OR A,L
                orWithA(l)
            0xB6 -> //OR A,[HL]
            {
                orWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xB7 -> //OR A,A
            {
                f = if (a <= 0u)
                    0x80u
                else
                    0u
                m = 1u
            }
            0xB8 -> //CP A,B
                compareWithA(b)
            0xB9 -> //CP A,C
                compareWithA(c)
            0xBA -> //CP A,D
                compareWithA(d)
            0xBB -> //CP A,E
                compareWithA(e)
            0xBC -> //CP A,H
                compareWithA(h)
            0xBD -> //CP A,L
                compareWithA(l)
            0xBE -> //CP A,[HL]
            {
                compareWithA(read8(getCombinedValue(h, l)))
                m++
            }
            0xBF -> //CP A,A
            {
                f = 0xC0u
                m = 1u
            }
            //0xCX Instructions
            0xC0 -> //RET NZ
                returnSelect(NOT_ZERO)
            0xC1 -> //POP BC
            {
                c = read8(sp++)
                b = read8(sp++)
                m = 3u
            }
            0xC2 -> //JP NZ,u16
                jump(NOT_ZERO)
            0xC3 -> //JP u16
                jump(-1)
            0xC4 -> //CALL NZ,u16
                call(NOT_ZERO)
            0xC5 -> //PUSH BC
            {
                writeU8(--sp, b)
                writeU8(--sp, c)
                m = 4u
            }
            0xC6 -> //ADD A,u8
            {
                addToA(fetch())
                m++
            }
            0xC7 -> //RST 00h
                restart(0u)
            0xC8 -> //RET Z
                returnSelect(ZERO)
            0xC9 -> //RET
                returnSelect(-1)
            0xCA -> //JP Z,u16
                jump(ZERO)
            0xCB -> //Prefix Instructions incomplete
            {
                val op2 = fetch()
                when (op2.toInt()) {
                    //0xCB 0x0X instructions
                    0 ->    //RLC B
                    {
                        f = 0u
                        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
                        b = (b.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            b++
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    1 ->    //RLC C
                    {
                        f = 0u
                        f = f or ((c and 0x80u).toUInt() shr 3).toUByte()
                        c = (c.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            c++
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    2 ->    //RLC D
                    {
                        f = 0u
                        f = f or ((d and 0x80u).toUInt() shr 3).toUByte()
                        d = (d.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            d++
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    3 ->    //RLC E
                    {
                        f = 0u
                        f = f or ((e and 0x80u).toUInt() shr 3).toUByte()
                        e = (e.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            e++
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    4 ->    //RLC H
                    {
                        f = 0u
                        f = f or ((h and 0x80u).toUInt() shr 3).toUByte()
                        h = (h.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            h++
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    5 ->    //RLC L
                    {
                        f = 0u
                        f = f or ((l and 0x80u).toUInt() shr 3).toUByte()
                        l = (l.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            l++
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    6 ->    //RLC [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        f = 0u
                        f = f or ((lit and 0x80u).toUInt() shr 3).toUByte()
                        lit = (lit.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            lit++
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    7 ->    //RLC A
                    {
                        f = 0u
                        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                        a = (a.toInt() shl 1).toUByte()
                        if (f and 0x10u > 0u)
                            a++
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    8 ->    //RRC B
                    {
                        f = 0u
                        f = f or ((b and 1u).toUInt() shl 4).toUByte()
                        b = (b.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            b = b or 0x80u
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    9 ->    //RRC C
                    {
                        f = 0u
                        f = f or ((c and 1u).toUInt() shl 4).toUByte()
                        c = (c.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            c = c or 0x80u
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0xA ->  //RLC D
                    {
                        f = 0u
                        f = f or ((d and 1u).toUInt() shl 4).toUByte()
                        d = (d.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            d = d or 0x80u
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0xB ->  //RRC E
                    {
                        f = 0u
                        f = f or ((e and 1u).toUInt() shl 4).toUByte()
                        e = (e.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            e = e or 0x80u
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0xC ->  //RRC H
                    {
                        f = 0u
                        f = f or ((h and 1u).toUInt() shl 4).toUByte()
                        h = (h.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            h = h or 0x80u
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0xD ->  //RRC L
                    {
                        f = 0u
                        f = f or ((l and 1u).toUInt() shl 4).toUByte()
                        l = (l.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            l = l or 0x80u
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0xE ->  //RRC [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        f = 0u
                        f = f or ((lit and 1u).toUInt() shl 4).toUByte()
                        lit = (lit.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            lit = lit or 0x80u
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0xF -> //RRC A
                    {
                        f = 0u
                        f = f or ((a and 1u).toUInt() shl 4).toUByte()
                        a = (a.toInt() shr 1).toUByte()
                        if (f and 0x10u > 0u)
                            a = a or 0x80u
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    //0xCB 0x1X instructions
                    0x10 -> //RL B
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
                        b = (b.toInt() shl 1).toUByte()
                        if (carry)
                            b++
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x11 -> //RL C
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((c and 0x80u).toUInt() shr 3).toUByte()
                        c = (c.toInt() shl 1).toUByte()
                        if (carry)
                            c++
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x12 -> //RL D
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((d and 0x80u).toUInt() shr 3).toUByte()
                        d = (d.toInt() shl 1).toUByte()
                        if (carry)
                            d++
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x13 -> //RL E
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((e and 0x80u).toUInt() shr 3).toUByte()
                        e = (e.toInt() shl 1).toUByte()
                        if (carry)
                            e++
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x14 -> //RL H
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((h and 0x80u).toUInt() shr 3).toUByte()
                        h = (h.toInt() shl 1).toUByte()
                        if (carry)
                            h++
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x15 -> //RL L
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((l and 0x80u).toUInt() shr 3).toUByte()
                        l = (l.toInt() shl 1).toUByte()
                        if (carry)
                            l++
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x16 -> //RL [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((lit and 0x80u).toUInt() shr 3).toUByte()
                        lit = (lit.toInt() shl 1).toUByte()
                        if (carry)
                            lit++
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x17 -> //RL A
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                        a = (a.toInt() shl 1).toUByte()
                        if (carry)
                            a++
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x18 -> //RR B
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((b and 1u).toUInt() shl 4).toUByte()
                        b = (b.toInt() shr 1).toUByte()
                        if (carry)
                            b = b or 0x80u
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x19 -> //RR C
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((c and 1u).toUInt() shl 4).toUByte()
                        c = (c.toInt() shr 1).toUByte()
                        if (carry)
                            c = c or 0x80u
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x1A -> //RR D
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((d and 1u).toUInt() shl 4).toUByte()
                        d = (d.toInt() shr 1).toUByte()
                        if (carry)
                            d = d or 0x80u
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x1B -> //RR E
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((e and 1u).toUInt() shl 4).toUByte()
                        e = (e.toInt() shr 1).toUByte()
                        if (carry)
                            e = e or 0x80u
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x1C -> //RR H
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((h and 1u).toUInt() shl 4).toUByte()
                        h = (h.toInt() shr 1).toUByte()
                        if (carry)
                            h = h or 0x80u
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x1D -> //RR L
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((l and 1u).toUInt() shl 4).toUByte()
                        l = (l.toInt() shr 1).toUByte()
                        if (carry)
                            l = l or 0x80u
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x1E -> //RR [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((lit and 1u).toUInt() shl 4).toUByte()
                        lit = (lit.toInt() shr 1).toUByte()
                        if (carry)
                            lit = lit or 0x80u
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x1F -> //RR A
                    {
                        val carry = (f and 0x10u) > 0u
                        f = 0u
                        f = f or ((a and 1u).toUInt() shl 4).toUByte()
                        a = (a.toInt() shr 1).toUByte()
                        if (carry)
                            a = a or 0x80u
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    //0xCB 0x2X instructions
                    0x20 -> //SLA B
                    {
                        f = 0u
                        f = f or ((b and 0x80u).toUInt() shr 3).toUByte()
                        b = (b.toInt() shl 1).toUByte()
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x21 -> //SLA C
                    {
                        f = 0u
                        f = f or ((c and 0x80u).toUInt() shr 3).toUByte()
                        c = (c.toInt() shl 1).toUByte()
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x22 -> //SLA D
                    {
                        f = 0u
                        f = f or ((d and 0x80u).toUInt() shr 3).toUByte()
                        d = (d.toInt() shl 1).toUByte()
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x23 -> //SLA E
                    {
                        f = 0u
                        f = f or ((e and 0x80u).toUInt() shr 3).toUByte()
                        e = (e.toInt() shl 1).toUByte()
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x24 -> //SLA H
                    {
                        f = 0u
                        f = f or ((h and 0x80u).toUInt() shr 3).toUByte()
                        h = (h.toInt() shl 1).toUByte()
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x25 -> //SLA L
                    {
                        f = 0u
                        f = f or ((l and 0x80u).toUInt() shr 3).toUByte()
                        l = (l.toInt() shl 1).toUByte()
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x26 -> //SLA [HL]
                    {
                        f = 0u
                        var lit = read8(getCombinedValue(h, l))
                        f = f or ((lit and 0x80u).toUInt() shr 3).toUByte()
                        lit = (lit.toInt() shl 1).toUByte()
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x27 -> //SLA A
                    {
                        f = 0u
                        f = f or ((a and 0x80u).toUInt() shr 3).toUByte()
                        a = (a.toInt() shl 1).toUByte()
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x28 -> //SRA B
                    {
                        f = 0u
                        f = f or (((b and 1u).toInt() shl 4).toUByte())
                        b = (b.toInt() shr 1 or (b and 0x80u).toInt()).toUByte()
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x29 -> //SRA C
                    {
                        f = 0u
                        f = f or ((c and 1u).toUInt() shl 4).toUByte()
                        c = (c.toInt() shr 1 or (c and 0x80u).toInt()).toUByte()
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x2A -> //SRA D
                    {
                        f = 0u
                        f = f or ((d and 1u).toUInt() shl 4).toUByte()
                        d = (d.toInt() shr 1 or (d and 0x80u).toInt()).toUByte()
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x2B -> //SRA E
                    {
                        f = 0u
                        f = f or ((e and 1u).toUInt() shl 4).toUByte()
                        e = (e.toInt() shr 1 or (e and 0x80u).toInt()).toUByte()
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x2C -> //SRA H
                    {
                        f = 0u
                        f = f or ((h and 1u).toUInt() shl 4).toUByte()
                        h = (h.toInt() shr 1 or (h and 0x80u).toInt()).toUByte()
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x2D -> //SRA L
                    {
                        f = 0u
                        f = f or ((l and 1u).toUInt() shl 4).toUByte()
                        l = (l.toInt() shr 1 or (l and 0x80u).toInt()).toUByte()
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x2E -> //SRA [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        f = 0u
                        f = f or ((lit and 1u).toUInt() shl 4).toUByte()
                        lit = ((lit.toUInt() shr 1) or ((lit and 0x80u).toUInt())).toUByte()
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x2F -> //SRA A
                    {
                        f = 0u
                        f = f or ((a and 1u).toUInt() shl 4).toUByte()
                        a = (a.toInt() shr 1 or (a and 0x80u).toInt()).toUByte()
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    //0xCB 0x3X instructions
                    0x30 -> //SWAP B
                    {
                        b =
                            (((b and 0xFu).toInt() shl 4) or ((b and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (b <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x31 -> //SWAP C
                    {
                        c =
                            (((c and 0xFu).toInt() shl 4) or ((c and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (c <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x32 -> //SWAP D
                    {
                        d =
                            (((d and 0xFu).toInt() shl 4) or ((d and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (d <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x33 -> //SWAP E
                    {
                        e =
                            (((e and 0xFu).toInt() shl 4) or ((e and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (e <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x34 -> //SWAP H
                    {
                        h =
                            (((h and 0xFu).toInt() shl 4) or ((h and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (h <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x35 -> //SWAP L
                    {
                        l =
                            (((l and 0xFu).toInt() shl 4) or ((l and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (l <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x36 -> //SWAP [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        lit =
                            (((lit and 0xFu).toInt() shl 4) or ((lit and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (lit <= 0u)
                            0x80u
                        else
                            0u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x37 -> //SWAP A
                    {
                        a =
                            (((a and 0xFu).toInt() shl 4) or ((a and 0xF0u).toInt() shr 4)).toUByte()
                        f = if (a <= 0u)
                            0x80u
                        else
                            0u
                        m = 2u
                    }
                    0x38 -> //SRL B
                    {
                        f = 0u
                        f = f or ((b and 1u).toUInt() shl 4).toUByte()
                        b = (b.toInt() shr 1).toUByte()
                        if (b <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x39 -> //SRL C
                    {
                        f = 0u
                        f = f or ((c and 1u).toUInt() shl 4).toUByte()
                        c = (c.toInt() shr 1).toUByte()
                        if (c <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x3A -> //SRL D
                    {
                        f = 0u
                        f = f or ((d and 1u).toUInt() shl 4).toUByte()
                        d = (d.toInt() shr 1).toUByte()
                        if (d <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x3B -> //SRL E
                    {
                        f = 0u
                        f = f or ((e and 1u).toUInt() shl 4).toUByte()
                        e = (e.toInt() shr 1).toUByte()
                        if (e <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x3C -> //SRL H
                    {
                        f = 0u
                        f = f or ((h and 1u).toUInt() shl 4).toUByte()
                        h = (h.toInt() shr 1).toUByte()
                        if (h <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x3D -> //SRL L
                    {
                        f = 0u
                        f = f or ((l and 1u).toUInt() shl 4).toUByte()
                        l = (l.toInt() shr 1).toUByte()
                        if (l <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    0x3E -> //SRL [HL]
                    {
                        var lit = read8(getCombinedValue(h, l))
                        f = 0u
                        f = f or ((lit and 1u).toUInt() shl 4).toUByte()
                        lit = (lit.toInt() shr 1).toUByte()
                        if (lit <= 0u)
                            f = f or 0x80u
                        writeU8(getCombinedValue(h, l), lit)
                        m = 4u
                    }
                    0x3F -> //SRL A
                    {
                        f = 0u
                        f = f or ((a and 1u).toUInt() shl 4).toUByte()
                        a = (a.toInt() shr 1).toUByte()
                        if (a <= 0u)
                            f = f or 0x80u
                        m = 2u
                    }
                    //0xCB 0x4X instructions
                    0x40 -> //BIT 0,B
                        bitCheck(b, 0)
                    0x41 -> //BIT 0,C
                        bitCheck(c, 0)
                    0x42 -> //BIT 0,D
                        bitCheck(d, 0)
                    0x43 -> //BIT 0,E
                        bitCheck(e, 0)
                    0x44 -> //BIT 0,H
                        bitCheck(h, 0)
                    0x45 -> //BIT 0,L
                        bitCheck(l, 0)
                    0x46 -> //BIT 0,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 0)
                        m++
                    }
                    0x47 -> //BIT 0,A
                        bitCheck(a, 0)
                    0x48 -> //BIT 1,B
                        bitCheck(b, 1)
                    0x49 -> //BIT 1,C
                        bitCheck(c, 1)
                    0x4A -> //BIT 1,D
                        bitCheck(d, 1)
                    0x4B -> //BIT 1,E
                        bitCheck(e, 1)
                    0x4C -> //BIT 1,H
                        bitCheck(h, 1)
                    0x4D -> //BIT 1,L
                        bitCheck(l, 1)
                    0x4E -> //BIT 1,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 1)
                        m++
                    }
                    0x4F -> //BIT 1,A
                        bitCheck(a, 1)
                    //0xCB 0x5X instructions
                    0x50 -> //BIT 2,B
                        bitCheck(b, 2)
                    0x51 -> //BIT 2,C
                        bitCheck(c, 2)
                    0x52 -> //BIT 2,D
                        bitCheck(d, 2)
                    0x53 -> //BIT 2,E
                        bitCheck(e, 2)
                    0x54 -> //BIT 2,H
                        bitCheck(h, 2)
                    0x55 -> //BIT 2,L
                        bitCheck(l, 2)
                    0x56 -> //BIT 2,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 2)
                        m++
                    }
                    0x57 -> //BIT 2,A
                        bitCheck(a, 2)
                    0x58 -> //BIT 3,B
                        bitCheck(b, 3)
                    0x59 -> //BIT 3,C
                        bitCheck(c, 3)
                    0x5A -> //BIT 3,D
                        bitCheck(d, 3)
                    0x5B -> //BIT 3,E
                        bitCheck(e, 3)
                    0x5C -> //BIT 3,H
                        bitCheck(h, 3)
                    0x5D -> //BIT 3,L
                        bitCheck(l, 3)
                    0x5E -> //BIT 3,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 3)
                        m++
                    }
                    0x5F -> //BIT 3,A
                        bitCheck(a, 3)
                    //0xCB 0x6X instructions
                    0x60 -> //BIT 4,B
                        bitCheck(b, 4)
                    0x61 -> //BIT 4,C
                        bitCheck(c, 4)
                    0x62 -> //BIT 4,D
                        bitCheck(d, 4)
                    0x63 -> //BIT 4,E
                        bitCheck(e, 4)
                    0x64 -> //BIT 4,H
                        bitCheck(h, 4)
                    0x65 -> //BIT 4,L
                        bitCheck(l, 4)
                    0x66 -> //BIT 4,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 4)
                        m++
                    }
                    0x67 -> //BIT 4,A
                        bitCheck(a, 4)
                    0x68 -> //BIT 5,B
                        bitCheck(b, 5)
                    0x69 -> //BIT 5,C
                        bitCheck(c, 5)
                    0x6A -> //BIT 5,D
                        bitCheck(d, 5)
                    0x6B -> //BIT 5,E
                        bitCheck(e, 5)
                    0x6C -> //BIT 5,H
                        bitCheck(h, 5)
                    0x6D -> //BIT 5,L
                        bitCheck(l, 5)
                    0x6E -> //BIT 5,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 5)
                        m++
                    }
                    0x6F -> //BIT 5,A
                        bitCheck(a, 5)
                    //0xCB 0x7X instructions
                    0x70 -> //BIT 6,B
                        bitCheck(b, 6)
                    0x71 -> //BIT 6,C
                        bitCheck(c, 6)
                    0x72 -> //BIT 6,D
                        bitCheck(d, 6)
                    0x73 -> //BIT 6,E
                        bitCheck(e, 6)
                    0x74 -> //BIT 6,H
                        bitCheck(h, 6)
                    0x75 -> //BIT 6,L
                        bitCheck(l, 6)
                    0x76 -> //BIT 6,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 6)
                        m++
                    }
                    0x77 -> //BIT 6,A
                        bitCheck(a, 6)
                    0x78 -> //BIT 7,B
                        bitCheck(b, 7)
                    0x79 -> //BIT 7,C
                        bitCheck(c, 7)
                    0x7A -> //BIT 7,D
                        bitCheck(d, 7)
                    0x7B -> //BIT 7,E
                        bitCheck(e, 7)
                    0x7C -> //BIT 7,H
                        bitCheck(h, 7)
                    0x7D -> //BIT 7,L
                        bitCheck(l, 7)
                    0x7E -> //BIT 7,[HL]
                    {
                        bitCheck(read8(getCombinedValue(h, l)), 7)
                        m++
                    }
                    0x7F -> //BIT 7,A
                        bitCheck(a, 7)
                    //0xCB 0x8X instructions
                    0x80 -> //RES 0,B
                    {
                        b = b and 0xFEu
                        m = 2u
                    }
                    0x81 -> //RES 0,C
                    {
                        c = c and 0xFEu
                        m = 2u
                    }
                    0x82 -> //RES 0,D
                    {
                        d = d and 0xFEu
                        m = 2u
                    }
                    0x83 -> //RES 0,E
                    {
                        e = e and 0xFEu
                        m = 2u
                    }
                    0x84 -> //RES 0,H
                    {
                        h = h and 0xFEu
                        m = 2u
                    }
                    0x85 -> //RES 0,L
                    {
                        l = l and 0xFEu
                        m = 2u
                    }
                    0x86 -> //RES 0,[HL]
                        resetValueAtHL(0)
                    0x87 -> //RES 0,A
                    {
                        a = a and 0xFEu
                        m = 2u
                    }
                    0x88 -> //RES 1,B
                    {
                        b = b and 0xFDu
                        m = 2u
                    }
                    0x89 -> //RES 1,C
                    {
                        c = c and 0xFDu
                        m = 2u
                    }
                    0x8A -> //RES 1,D
                    {
                        d = d and 0xFDu
                        m = 2u
                    }
                    0x8B -> //RES 1,E
                    {
                        e = e and 0xFDu
                        m = 2u
                    }
                    0x8C -> //RES 1,H
                    {
                        h = h and 0xFDu
                        m = 2u
                    }
                    0x8D -> //RES 1,L
                    {
                        l = l and 0xFDu
                        m = 2u
                    }
                    0x8E -> //RES 1,[HL]
                        resetValueAtHL(1)
                    0x8F -> //RES 1,A
                    {
                        a = a and 0xFDu
                        m = 2u
                    }
                    //0xCB 0x9X instructions
                    0x90 -> //RES 2,B
                    {
                        b = b and 0xFBu
                        m = 2u
                    }
                    0x91 -> //RES 2,C
                    {
                        c = c and 0xFBu
                        m = 2u
                    }
                    0x92 -> //RES 2,D
                    {
                        d = d and 0xFBu
                        m = 2u
                    }
                    0x93 -> //RES 2,E
                    {
                        e = e and 0xFBu
                        m = 2u
                    }
                    0x94 -> //RES 2,H
                    {
                        h = h and 0xFBu
                        m = 2u
                    }
                    0x95 -> //RES 2,L
                    {
                        l = l and 0xFBu
                        m = 2u
                    }
                    0x96 -> //RES 2,[HL]
                        resetValueAtHL(2)
                    0x97 -> //RES 2,A
                    {
                        a = a and 0xFBu
                        m = 2u
                    }
                    0x98 -> //RES 3,B
                    {
                        b = b and 0xF7u
                        m = 2u
                    }
                    0x99 -> //RES 3,C
                    {
                        c = c and 0xF7u
                        m = 2u
                    }
                    0x9A -> //RES 3,D
                    {
                        d = d and 0xF7u
                        m = 2u
                    }
                    0x9B -> //RES 3,E
                    {
                        e = e and 0xF7u
                        m = 2u
                    }
                    0x9C -> //RES 3,H
                    {
                        h = h and 0xF7u
                        m = 2u
                    }
                    0x9D -> //RES 3,L
                    {
                        l = l and 0xF7u
                        m = 2u
                    }
                    0x9E -> //RES 3,[HL]
                        resetValueAtHL(3)
                    0x9F -> //RES 3,A
                    {
                        a = a and 0xF7u
                        m = 2u
                    }
                    //0xCB 0xAX instructions
                    0xA0 -> //RES 4,B
                    {
                        b = b and 0xEFu
                        m = 2u
                    }
                    0xA1 -> //RES 4,C
                    {
                        c = c and 0xEFu
                        m = 2u
                    }
                    0xA2 -> //RES 4,D
                    {
                        d = d and 0xEFu
                        m = 2u
                    }
                    0xA3 -> //RES 4,E
                    {
                        e = e and 0xEFu
                        m = 2u
                    }
                    0xA4 -> //RES 4,H
                    {
                        h = h and 0xEFu
                        m = 2u
                    }
                    0xA5 -> //RES 4,L
                    {
                        l = l and 0xEFu
                        m = 2u
                    }
                    0xA6 -> //RES 4,[HL]
                        resetValueAtHL(4)
                    0xA7 -> //RES 4,A
                    {
                        a = a and 0xEFu
                        m = 2u
                    }
                    0xA8 -> //RES 5,B
                    {
                        b = b and 0xDFu
                        m = 2u
                    }
                    0xA9 -> //RES 5,C
                    {
                        c = c and 0xDFu
                        m = 2u
                    }
                    0xAA -> //RES 5,D
                    {
                        d = d and 0xDFu
                        m = 2u
                    }
                    0xAB -> //RES 5,E
                    {
                        e = e and 0xDFu
                        m = 2u
                    }
                    0xAC -> //RES 5,H
                    {
                        h = h and 0xDFu
                        m = 2u
                    }
                    0xAD -> //RES 5,L
                    {
                        l = l and 0xDFu
                        m = 2u
                    }
                    0xAE -> //RES 5,[HL]
                        resetValueAtHL(5)
                    0xAF -> //RES 5,A
                    {
                        a = a and 0xDFu
                        m = 2u
                    }
                    //0xCB 0xBX instructions
                    0xB0 -> //RES 6,B
                    {
                        b = b and 0xBFu
                        m = 2u
                    }
                    0xB1 -> //RES 6,C
                    {
                        c = c and 0xBFu
                        m = 2u
                    }
                    0xB2 -> //RES 6,D
                    {
                        d = d and 0xBFu
                        m = 2u
                    }
                    0xB3 -> //RES 6,E
                    {
                        e = e and 0xBFu
                        m = 2u
                    }
                    0xB4 -> //RES 6,H
                    {
                        h = h and 0xBFu
                        m = 2u
                    }
                    0xB5 -> //RES 6,L
                    {
                        l = l and 0xBFu
                        m = 2u
                    }
                    0xB6 -> //RES 6,[HL]
                        resetValueAtHL(6)
                    0xB7 -> //RES 6,A
                    {
                        a = a and 0xBFu
                        m = 2u
                    }
                    0xB8 -> //RES 7,B
                    {
                        b = b and 0x7Fu
                        m = 2u
                    }
                    0xB9 -> //RES 7,C
                    {
                        c = c and 0x7Fu
                        m = 2u
                    }
                    0xBA -> //RES 7,D
                    {
                        d = d and 0x7Fu
                        m = 2u
                    }
                    0xBB -> //RES 7,E
                    {
                        e = e and 0x7Fu
                        m = 2u
                    }
                    0xBC -> //RES 7,H
                    {
                        h = h and 0x7Fu
                        m = 2u
                    }
                    0xBD -> //RES 7,L
                    {
                        l = l and 0x7Fu
                        m = 2u
                    }
                    0xBE -> //RES 7,[HL]
                        resetValueAtHL(7)
                    0xBF -> //RES 7,A
                    {
                        a = a and 0x7Fu
                        m = 2u
                    }
                    //0xCB 0xCX instructions
                    0xC0 -> //SET 0,B
                    {
                        b = b or 1u
                        m = 2u
                    }
                    0xC1 -> //SET 0,C
                    {
                        c = c or 1u
                        m = 2u
                    }
                    0xC2 -> //SET 0,D
                    {
                        d = d or 1u
                        m = 2u
                    }
                    0xC3 -> //SET 0,E
                    {
                        e = e or 1u
                        m = 2u
                    }
                    0xC4 -> //SET 0,H
                    {
                        h = h or 1u
                        m = 2u
                    }
                    0xC5 -> //SET 0,L
                    {
                        l = l or 1u
                        m = 2u
                    }
                    0xC6 -> //SET 0,[HL]
                        setValueAtHL(0)
                    0xC7 -> //SET 0,A
                    {
                        a = a or 1u
                        m = 2u
                    }
                    0xC8 -> //SET 1,B
                    {
                        b = b or 2u
                        m = 2u
                    }
                    0xC9 -> //SET 1,C
                    {
                        c = c or 2u
                        m = 2u
                    }
                    0xCA -> //SET 1,D
                    {
                        d = d or 2u
                        m = 2u
                    }
                    0xCB -> //SET 1,E
                    {
                        e = e or 2u
                        m = 2u
                    }
                    0xCC -> //SET 1,H
                    {
                        h = h or 2u
                        m = 2u
                    }
                    0xCD -> //SET 1,L
                    {
                        l = l or 2u
                        m = 2u
                    }
                    0xCE -> //SET 1,[HL]
                        setValueAtHL(1)
                    0xCF -> //SET 1,A
                    {
                        a = a or 2u
                        m = 2u
                    }
                    //0xCB 0xDX instructions
                    0xD0 -> //SET 2,B
                    {
                        b = b or 4u
                        m = 2u
                    }
                    0xD1 -> //SET 2,C
                    {
                        c = c or 4u
                        m = 2u
                    }
                    0xD2 -> //SET 2,D
                    {
                        d = d or 4u
                        m = 2u
                    }
                    0xD3 -> //SET 2,E
                    {
                        e = e or 4u
                        m = 2u
                    }
                    0xD4 -> //SET 2,H
                    {
                        h = h or 4u
                        m = 2u
                    }
                    0xD5 -> //SET 2,L
                    {
                        l = l or 4u
                        m = 2u
                    }
                    0xD6 -> //SET 2,[HL]
                        setValueAtHL(2)
                    0xD7 -> //SET 2,A
                    {
                        a = a or 4u
                        m = 2u
                    }
                    0xD8 -> //SET 3,B
                    {
                        b = b or 8u
                        m = 2u
                    }
                    0xD9 -> //SET 3,C
                    {
                        c = c or 8u
                        m = 2u
                    }
                    0xDA -> //SET 3,D
                    {
                        d = d or 8u
                        m = 2u
                    }
                    0xDB -> //SET 3,E
                    {
                        e = e or 8u
                        m = 2u
                    }
                    0xDC -> //SET 3,H
                    {
                        h = h or 8u
                        m = 2u
                    }
                    0xDD -> //SET 3,L
                    {
                        l = l or 8u
                        m = 2u
                    }
                    0xDE -> //SET 3,[HL]
                        setValueAtHL(3)
                    0xDF -> //SET 3,A
                    {
                        a = a or 8u
                        m = 2u
                    }
                    //0xCB 0xEX instructions
                    0xE0 -> //SET 4,B
                    {
                        b = b or 0x10u
                        m = 2u
                    }
                    0xE1 -> //SET 4,C
                    {
                        c = c or 0x10u
                        m = 2u
                    }
                    0xE2 -> //SET 4,D
                    {
                        d = d or 0x10u
                        m = 2u
                    }
                    0xE3 -> //SET 4,E
                    {
                        e = e or 0x10u
                        m = 2u
                    }
                    0xE4 -> //SET 4,H
                    {
                        h = h or 0x10u
                        m = 2u
                    }
                    0xE5 -> //SET 4,L
                    {
                        l = l or 0x10u
                        m = 2u
                    }
                    0xE6 -> //SET 4,[HL]
                        setValueAtHL(4)
                    0xE7 -> //SET 4,A
                    {
                        a = a or 0x10u
                        m = 2u
                    }
                    0xE8 -> //SET 5,B
                    {
                        b = b or 0x20u
                        m = 2u
                    }
                    0xE9 -> //SET 5,C
                    {
                        c = c or 0x20u
                        m = 2u
                    }
                    0xEA -> //SET 5,D
                    {
                        d = d or 0x20u
                        m = 2u
                    }
                    0xEB -> //SET 5,E
                    {
                        e = e or 0x20u
                        m = 2u
                    }
                    0xEC -> //SET 5,H
                    {
                        h = h or 0x20u
                        m = 2u
                    }
                    0xED -> //SET 5,L
                    {
                        l = l or 0x20u
                        m = 2u
                    }
                    0xEE -> //SET 5,[HL]
                        setValueAtHL(5)
                    0xEF -> //SET 5,A
                    {
                        a = a or 0x20u
                        m = 2u
                    }
                    //0xCB 0xFX instructions
                    0xF0 -> //SET 6,B
                    {
                        b = b or 0x40u
                        m = 2u
                    }
                    0xF1 -> //SET 6,C
                    {
                        c = c or 0x40u
                        m = 2u
                    }
                    0xF2 -> //SET 6,D
                    {
                        d = d or 0x40u
                        m = 2u
                    }
                    0xF3 -> //SET 6,E
                    {
                        e = e or 0x40u
                        m = 2u
                    }
                    0xF4 -> //SET 6,H
                    {
                        h = h or 0x40u
                        m = 2u
                    }
                    0xF5 -> //SET 6,L
                    {
                        l = l or 0x40u
                        m = 2u
                    }
                    0xF6 -> //SET 6,[HL]
                        setValueAtHL(6)
                    0xF7 -> //SET 6,A
                    {
                        a = a or 0x40u
                        m = 2u
                    }
                    0xF8 -> //SET 7,B
                    {
                        b = b or 0x80u
                        m = 2u
                    }
                    0xF9 -> //SET 7,C
                    {
                        c = c or 0x80u
                        m = 2u
                    }
                    0xFA -> //SET 7,D
                    {
                        d = d or 0x80u
                        m = 2u
                    }
                    0xFB -> //SET 7,E
                    {
                        e = e or 0x80u
                        m = 2u
                    }
                    0xFC -> //SET 7,H
                    {
                        h = h or 0x80u
                        m = 2u
                    }
                    0xFD -> //SET 7,L
                    {
                        l = l or 0x80u
                        m = 2u
                    }
                    0xFE -> //SET 7,[HL]
                        setValueAtHL(7)
                    0xFF -> //SET 7,A
                    {
                        a = a or 0x80u
                        m = 2u
                    }
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
            0xCC -> //CALL Z,u16
                call(ZERO)
            0xCD -> //CALL u16
                call(-1)
            0xCE -> //ADC A,u8
            {
                addToAWithCarry(fetch())
                m++
            }
            0xCF -> //RST 08h
                restart(8u)
            //0xDX Instructions
            0xD0 -> //RET NC
                returnSelect(NO_CARRY)
            0xD1 -> //POP DE
            {
                e = read8(sp++)
                d = read8(sp++)
                m = 3u
            }
            0xD2 -> //JP NC,u16
                jump(NO_CARRY)
            //0xD3 does not exist
            0xD4 -> //CALL NC,u16
                call(NO_CARRY)
            0xD5 -> //PUSH DE
            {
                writeU8(--sp, d)
                writeU8(--sp, e)
                m = 4u
            }
            0xD6 -> //SUB A,u8
            {
                subFromA(fetch())
                m++
            }
            0xD7 -> //RST 10h
                restart(10u)
            0xD8 -> //RET C
                returnSelect(CARRY)
            0xD9 -> //RET I
                returnSelect(ENABLE_INTERRUPTS)
            0xDA -> //JP C,u16
                jump(CARRY)
            //0xDB does not exist
            0xDC -> //CALL C,u16
                call(CARRY)
            //0xDD does not exist
            0xDE -> //SBC A,u8
            {
                subFromAWithBorrow(fetch())
                m++
            }
            0xDF -> //RST 18h
                restart(0x18u)
            //0xEX Instructions
            0xE0 -> //LDH [u8],A
            {
                writeU8((0xFF00u + fetch()).toUShort(), a)
                m = 3u
            }
            0xE1 -> //POP HL
            {
                l = read8(sp++)
                h = read8(sp++)
                m = 3u
            }
            0xE2 -> //LDH [C],A
            {
                writeU8((0xFF00u + c).toUShort(), a)
                m = 2u
            }
            //0xE3,0xE4 does not exist
            0xE5 -> //PUSH HL
            {
                writeU8(--sp, h)
                writeU8(--sp, l)
                m = 4u
            }
            0xE6 -> //AND A,u8
            {
                andWithA(fetch())
                m++
            }
            0xE7 -> //RST 20h
                restart(20u)
            0xE8 -> //ADD SP,i8
            {
                f = 0u
                val lit = fetch().toByte()
                val res = sp.toInt() + lit
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
                sp = res.toUShort()
                m = 4u
            }
            0xE9 -> //JP HL
                jump(TO_HL)
            0xEA -> //LD [u16],A
            {
                writeU8(fetchTwice(), a)
                m = 4u
            }
            //0xEB,0xEC,0xED does not exist
            0xEE -> //XOR A,u8
            {
                xorWithA(fetch())
                m++
            }
            0xEF -> //RST 28h
                restart(0x28u)
            //0xFX Instructions
            0xF0 -> //LDH A,[u8]
            {
                a = read8((0xFF00u + fetch()).toUShort())
                m = 3u
            }
            0xF1 -> //POP AF
            {
                f = read8(sp++) and 0xF0u
                a = read8(sp++)
                m = 3u
            }
            0xF2 -> //LDH [C],A
            {
                a = read8((0xFF00u + c).toUShort())
                m = 2u
            }
            0xF3 -> //DI
            {
                interruptsMasterEnabled = false
                //Log.i("GB.cpu", "Interrupts Disabled")
                m = 1u
            }
            //0xF4 does not exist
            0xF5 -> //PUSH AF
            {
                writeU8(--sp, a)
                writeU8(--sp, f)
                m = 4u
            }
            0xF6 -> //OR A,u8
            {
                orWithA(fetch())
                m++
            }
            0xF7 -> //RST 30h
                restart(30u)
            0xF8 -> //LD HL,SP+i8
            {
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
            0xF9 -> //LD SP,HL
            {
                sp = getCombinedValue(h, l)
                m = 2u
            }
            0xFA -> //LD A,[u16]
            {
                a = read8(fetchTwice())
                m = 4u
            }
            0xFB -> //EI
            {
                if (read8(pc) != 0xF3.toUByte())
                    interruptsMasterEnabled = true
                //Log.i("GB.cpu", "Interrupts Enabled")
                m = 1u
            }
            //0xFC,0xFD does not exist
            0xFE -> //CP A,u8
            {
                compareWithA(fetch())
                m++
            }
            0xFF -> //RST 38h
                restart(38u)
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

    private fun resetValueAtHL(bitNo: Int) {
        writeU8(
            getCombinedValue(h, l),
            read8(getCombinedValue(h, l)) and (1 shl bitNo).inv().toUByte()
        )
        m = 4u
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
        f = 0x40u
        if ((a and 0xFu).toInt() - (lit and 0xFu).toInt() < 0)
            f = f or 0x20u
        if (res < 0)
            f = f or 0x10u
        a = res.toUByte()
        if (a <= 0u)
            f = f or 0x80u
        m = 1u
    }

    private fun subFromAWithBorrow(lit: UByte) {
        val carry = if (f and 0x10u > 0u) 1u else 0u
        val res = (a - lit - carry).toInt()
        f = 0x40u
        if (((a and 0xFu) - (lit and 0xFu) - carry).toInt() < 0)
            f = f or 0x20u
        if (res < 0)
            f = f or 0x10u
        a = res.toUByte()
        if (a <= 0u)
            f = f or 0x80u
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

    private fun addHL(lit: UShort) {
        f = f and 0x80u
        val res = getCombinedValue(h, l) + lit
        if ((getCombinedValue(h, l) and 0xFFFu) + (lit and 0xFFFu) > 0xFFFu) f = f or 0x20u
        if (res > 0xFFFFu) f = f or 0x10u
        setHL(res)
        m = 2u
    }

    private fun jump(condition: Int) {
        if (condition == TO_HL) {
            pc = (l.toInt() or (h.toInt() shl 8)).toUShort()
            m = 1u
            return
        }
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
        f = f and 0x10u
        f = f or 0x20u
        if (((byte.toUInt() shr bitNo) and 1u) == 0u)
            f = f or 0x80u
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

    private fun andWithA(lit: UByte) {
        f = 0x20u
        a = a and lit
        if (a <= 0u) f = f or 0x80u
        m = 1u
    }

    private fun orWithA(lit: UByte) {
        a = a or lit
        f = 0u
        if (a <= 0u) f = f or 0x80u
        m = 1u
    }

    fun start() {
        runTillCrash()
    }
}