package com.example.playgb

import android.media.AudioTrack
import android.util.Log
import kotlin.random.Random

// TODO: 17/8/20 Remove audio stuttering ->Fine tune buffer size
private const val BUFFER_SIZE = 850
private const val SKIP_FRAMES = 85

@ExperimentalUnsignedTypes
class Apu(private var audioTrack: AudioTrack) {
    open class SquareWave {
        //Registers
        private var duty = 0
        private var length = 10
        private var initialVolume = 0
        private var envelopeIncrease = false
        private var numEnvelopeSweep = 0
        protected var frequencyReg = 0
        private var lengthEnabled = false
        private var restart = false

        //Derived values
        private var dutySequence = BooleanArray(8)
        private var sequenceIndex = 0
        protected var freqTrigger = 0
        private var lengthTrigger = 0
        private var envelopeTrigger = 0
        protected var timePassed = 0
        private var envEnabled = false
        private var outputVolume = 0

        //Channel is playing
        var enabled = false

        fun setPatternReg(data: UByte) {
            duty = (data.toInt() shr 6) and 3
            length = data.toInt() and 0x3F
            lengthTrigger = 16384 * (64 - length)
            dutySequence = when (duty) {
                2 -> booleanArrayOf(true, false, false, false, false, true, true, true)
                3 -> booleanArrayOf(false, true, true, true, true, true, true, false)
                1 -> booleanArrayOf(true, false, false, false, false, false, false, true)
                else -> booleanArrayOf(false, false, false, false, false, false, false, true)
            }
            sequenceIndex = 0
        }

        fun setEnvelopeReg(data: UByte) {
            initialVolume = (data.toInt() shr 4) and 0xF
            outputVolume = initialVolume
            envelopeIncrease = (data.toInt() and 8) != 0
            numEnvelopeSweep = data.toInt() and 7
            if (numEnvelopeSweep > 0) {
                envEnabled = true
                envelopeTrigger = 65536 * numEnvelopeSweep
            }
        }

        fun setLowerFrequency(data: UByte) {
            frequencyReg = data.toInt()
        }

        fun setControlReg(data: UByte) {
            lengthEnabled = (data.toInt() and 0x40) != 0
            frequencyReg += ((data.toInt() and 7) shl 8)
            freqTrigger = (2048 - frequencyReg) * 4
            timePassed = 0
            restart = (data.toInt() and 0x80) != 0
        }

        fun getPatternReg(): UByte {
            return (((duty and 3) shl 6) or 0x3F).toUByte()
        }

        fun getEnvelopeReg(): UByte {
            return ((initialVolume shl 4) + numEnvelopeSweep + 8 * if (envelopeIncrease) 1 else 0).toUByte()
        }

        fun getControlReg(): UByte {
            return if (lengthEnabled) 0xFFu else 0xBFu
        }

        open fun step() {
            if (restart) {
                enabled = true
                restart = false
            }
            if (enabled) {
                timePassed++
                if (numEnvelopeSweep > 0 && envEnabled) {
                    if (timePassed % envelopeTrigger == 0) {
                        if ((outputVolume != 0 && outputVolume != 15) || (outputVolume == 0 && envelopeIncrease) || (outputVolume == 15 && !envelopeIncrease))
                            if (envelopeIncrease) outputVolume++ else outputVolume--
                        else
                            envEnabled = false
                    }
                }
                if (timePassed % freqTrigger == 0)
                    sequenceIndex = (sequenceIndex + 1) % 8
                if (lengthEnabled) {
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
                }
            }
        }

        open fun getSquareWave(): Int {
            if (restart) {
                enabled = true
                restart = false
            }
            if (enabled) {
                timePassed++
                if (numEnvelopeSweep > 0 && envEnabled) {
                    if (timePassed % envelopeTrigger == 0) {
                        if ((outputVolume != 0 && outputVolume != 15) || (outputVolume == 0 && envelopeIncrease) || (outputVolume == 15 && !envelopeIncrease))
                            if (envelopeIncrease) outputVolume++ else outputVolume--
                        else
                            envEnabled = false
                    }
                }
                if (timePassed % freqTrigger == 0)
                    sequenceIndex = (sequenceIndex + 1) % 8
                if (lengthEnabled) {
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
                }
                return if (dutySequence[sequenceIndex]) outputVolume else 0
            }
            return 0
        }
    }

    class SquareWaveWithSweep : Apu.SquareWave() {
        //Registers
        private var sweepTime = 0
        private var sweepDecrease = false
        private var numSweepShift = 0

        //Sweep specific registers
        private var swpEnabled = false
        private var shadowFrequencyReg = 0

        //Derived value
        private var sweepTrigger = 0

        fun setSweepReg(data: UByte) {
            sweepTime = ((data.toInt() shr 4) and 7)
            sweepDecrease = (data.toInt() and 8) != 0
            numSweepShift = data.toInt() and 7
            if (sweepTime > 0) {
                swpEnabled = true
                sweepTrigger = 32768 * sweepTime
            }
        }

        fun getSweepReg(): UByte {
            return (0x80 + (sweepTime shl 4) + numSweepShift + 8 * if (sweepDecrease) 1 else 0).toUByte()
        }

        override fun step() {
            if (sweepTime > 0 && swpEnabled)
                if ((timePassed + 1) % sweepTrigger == 0) {
                    shadowFrequencyReg = frequencyReg
                    if (sweepTime == 0 && numSweepShift == 0)
                        swpEnabled = false
                    if (numSweepShift > 0) {
                        val newFrequencyReg =
                            shadowFrequencyReg + (shadowFrequencyReg shr numSweepShift) * (if (sweepDecrease) -1 else 1)
                        if (newFrequencyReg > 2047) {
                            enabled = false
                            return
                        }
                        frequencyReg = newFrequencyReg
                        shadowFrequencyReg = frequencyReg
                        if ((shadowFrequencyReg + (shadowFrequencyReg shr numSweepShift) * (if (sweepDecrease) -1 else 1)) > 2047) {
                            enabled = false
                            return
                        }
                        freqTrigger = (2048 - frequencyReg) * 4
                    }
                }
            super.step()
        }

        override fun getSquareWave(): Int {
            if (sweepTime > 0 && swpEnabled)
                if ((timePassed + 1) % sweepTrigger == 0) {
                    shadowFrequencyReg = frequencyReg
                    if (sweepTime == 0 && numSweepShift == 0)
                        swpEnabled = false
                    if (numSweepShift > 0) {
                        val newFrequencyReg =
                            shadowFrequencyReg + (shadowFrequencyReg shr numSweepShift) * (if (sweepDecrease) -1 else 1)
                        if (newFrequencyReg > 2047) {
                            enabled = false
                            return 0
                        }
                        frequencyReg = newFrequencyReg
                        shadowFrequencyReg = frequencyReg
                        if ((shadowFrequencyReg + (shadowFrequencyReg shr numSweepShift) * (if (sweepDecrease) -1 else 1)) > 2047) {
                            enabled = false
                            return 0
                        }
                        freqTrigger = (2048 - frequencyReg) * 4
                    }
                }
            return super.getSquareWave()
        }
    }

    class Wave(private var waveRam: ByteArray) {
        //Registers
        private var length = 0
        private var volume = 0
        private var frequencyReg = 0
        private var lengthEnabled = false
        private var soundEnable = false
        private var restart = false

        //Derived values
        private var timePassed = 0
        private var freqTrigger = 0
        private var positionIndex = 0
        private var lengthTrigger = 0

        //Channel is playing
        var enabled = false

        fun setEnableReg(data: UByte) {
            soundEnable = (data and 0x80u) != 0.toUByte()
        }

        fun setLengthReg(data: UByte) {
            length = data.toInt()
            lengthTrigger = 16384 * (256 - length)//Sound Length = (256-t1)*(1/256) seconds
        }

        fun setVolumeReg(data: UByte) {
            volume = data.toInt() shr 5
        }

        fun setLowerFrequency(data: UByte) {
            frequencyReg = data.toInt()
        }

        fun setControlReg(data: UByte) {
            lengthEnabled = (data.toInt() and 0x40) != 0
            frequencyReg += ((data.toInt() and 7) shl 8)
            freqTrigger = (2048 - frequencyReg) * 2
            restart = (data.toInt() and 0x80) != 0
        }

        fun getEnableReg(): UByte {
            return if (soundEnable) 0xFFu else 0x7Fu
        }

        fun getLengthReg(): UByte {
            return length.toUByte()
        }

        fun getVolumeReg(): UByte {
            return ((volume shl 5) or 0x9F).toUByte()
        }

        fun getControlReg(): UByte {
            return if (lengthEnabled) 0xFFu else 0xBFu
        }

        fun step() {
            if (restart) {
                enabled = true
                timePassed = 0
                positionIndex = 0
                restart = false
            }
            if (enabled) {
                timePassed++
                if (lengthEnabled)
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
                if (soundEnable)
                    if (timePassed % freqTrigger == 0)
                        positionIndex = (positionIndex + 1) % 32

            }
        }

        fun getWaveData(): Int {
            if (restart) {
                enabled = true
                timePassed = 0
                positionIndex = 0
                restart = false
            }
            if (enabled) {
                timePassed++
                if (lengthEnabled)
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
                if (soundEnable) {
                    var output = waveRam[positionIndex / 2].toInt()
                    output = if (positionIndex % 2 == 0) output and 0xF else (output and 0xF) shr 4
                    if (timePassed % freqTrigger == 0)
                        positionIndex = (positionIndex + 1) % 32
                    return if (volume > 0) output shr (volume - 1) else 0
                }
            }
            return 0
        }
    }

    class NoiseWave {
        //Registers
        private var length = 0
        private var initialVolume = 0
        private var envelopeIncrease = false
        private var numEnvelopeSweep = 0
        private var shiftClockFrequency = 0
        private var counterWidthSelect7 = false
        private var ratioOfFrequency = 0
        private var lengthEnabled = false
        private var restart = false

        //Derived values
        private var timePassed = 0
        private var envEnabled = false
        private var envelopeTrigger = 0
        private var outputVolume = 0
        private var lengthTrigger = 0
        private var pRBG: Short = 0
        private var freqTrigger = 8

        //Channel is playing
        var enabled = false

        fun setLengthReg(data: UByte) {
            length = data.toInt() and 0x3F
            lengthTrigger = 16384 * (64 - length)
        }

        fun setEnvelopeReg(data: UByte) {
            initialVolume = (data.toInt() shr 4) and 0xF
            outputVolume = initialVolume
            envelopeIncrease = (data.toInt() and 8) != 0
            numEnvelopeSweep = data.toInt() and 7
            if (numEnvelopeSweep > 0) {
                envEnabled = true
                envelopeTrigger = 65536 * numEnvelopeSweep
            }
        }

        fun setCounterReg(data: UByte) {
            shiftClockFrequency = (data.toInt() shr 4) and 0xF
            counterWidthSelect7 = (data.toInt() and 8) != 0
            ratioOfFrequency = data.toInt() and 7
            freqTrigger = if (ratioOfFrequency > 0) 16 * ratioOfFrequency else 8
            freqTrigger = freqTrigger shl shiftClockFrequency
        }

        fun setControlReg(data: UByte) {
            lengthEnabled = (data.toInt() and 0x40) != 0
            restart = (data.toInt() and 0x80) != 0
            pRBG = Random.nextInt(256).toShort()
            if (pRBG.toInt() == 0)
                pRBG++
        }

        fun getCounterReg(): UByte {
            return ((shiftClockFrequency shl 4) + ratioOfFrequency + 8 * if (counterWidthSelect7) 1 else 0).toUByte()
        }

        fun getEnvelopeReg(): UByte {
            return ((initialVolume shl 4) + numEnvelopeSweep + 8 * if (envelopeIncrease) 1 else 0).toUByte()
        }

        fun getControlReg(): UByte {
            return if (lengthEnabled) 0xFFu else 0xBFu
        }

        fun step() {
            if (restart) {
                enabled = true
                timePassed = 0
                restart = false
            }
            if (enabled) {
                timePassed++
                if (numEnvelopeSweep > 0 && envEnabled)
                    if (timePassed % envelopeTrigger == 0)
                        if ((outputVolume != 0 && outputVolume != 15) || (outputVolume == 0 && envelopeIncrease) || (outputVolume == 15 && !envelopeIncrease))
                            if (envelopeIncrease) outputVolume++ else outputVolume--
                        else
                            envEnabled = false
                if (timePassed % freqTrigger == 0) {
                    val currValue = pRBG.toInt()
                    val msb = (currValue shl 14) xor (currValue shl 13)
                    pRBG = (((currValue shr 1) or msb).toShort())
                    if (counterWidthSelect7)
                        pRBG = ((pRBG.toInt() and 0xFFBF) or (msb shr 8)).toShort()
                }
                if (lengthEnabled)
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
            }
        }

        fun getNoiseData(): Int {
            if (restart) {
                enabled = true
                timePassed = 0
                restart = false
            }
            if (enabled) {
                timePassed++
                if (numEnvelopeSweep > 0 && envEnabled)
                    if (timePassed % envelopeTrigger == 0)
                        if ((outputVolume != 0 && outputVolume != 15) || (outputVolume == 0 && envelopeIncrease) || (outputVolume == 15 && !envelopeIncrease))
                            if (envelopeIncrease) outputVolume++ else outputVolume--
                        else
                            envEnabled = false

                if (timePassed % freqTrigger == 0) {
                    val currValue = pRBG.toInt()
                    val msb = (currValue shl 14) xor (currValue shl 13)
                    pRBG = (((currValue shr 1) or msb).toShort())
                    if (counterWidthSelect7)
                        pRBG = ((pRBG.toInt() and 0xFFBF) or (msb shr 8)).toShort()
                }
                if (lengthEnabled)
                    if (timePassed % lengthTrigger == 0)
                        enabled = false
                return if (pRBG.toInt() and 1 > 0) 0 else outputVolume
            }
            return 0
        }
    }

    private var square1 = SquareWaveWithSweep()
    private var square2 = SquareWave()
    private var waveRam = ByteArray(16)
    private var wave3 = Wave(waveRam)
    private var noise4 = NoiseWave()
    private var masterVolumeReg: UByte = 0u
    private var mixerReg: UByte = 0u
    private var lengthStatus: UByte = 0u
    private var power = false

    private var audioBufferIndex = 0
    private var audioBuffer = ShortArray(BUFFER_SIZE)

    private var clock = 0
    fun timePassed(time: Int) {
        if (power) {
            repeat(time) {
                var amplitude = 0
                if ((clock + it) % SKIP_FRAMES == 0) {
                    if (mixerReg and 0x10u > 0u)
                        amplitude += square1.getSquareWave()
                    if (mixerReg and 0x20u > 0u)
                        amplitude += square2.getSquareWave()
                    if (mixerReg and 0x40u > 0u)
                        amplitude += wave3.getWaveData()
                    if (mixerReg and 0x80u > 0u)
                        amplitude += noise4.getNoiseData()
                    audioBuffer[audioBufferIndex++] = (amplitude * 540).toShort()
                } else {
                    if (mixerReg and 0x10u > 0u)
                        square1.step()
                    if (mixerReg and 0x20u > 0u)
                        square2.step()
                    if (mixerReg and 0x40u > 0u)
                        wave3.step()
                    if (mixerReg and 0x80u > 0u)
                        noise4.step()
                }
                if (audioBufferIndex == BUFFER_SIZE) {
                    audioTrack.write(audioBuffer, 0, BUFFER_SIZE)
                    audioBufferIndex = 0
                }
                if (!square1.enabled && lengthStatus and 1u > 0u)
                    lengthStatus = lengthStatus and 0xFEu
                if (!square2.enabled && lengthStatus and 2u > 0u)
                    lengthStatus = lengthStatus and 0xFDu
                if (!wave3.enabled && lengthStatus and 4u > 0u)
                    lengthStatus = lengthStatus and 0xFBu
                if (!noise4.enabled && lengthStatus and 8u > 0u)
                    lengthStatus = lengthStatus and 0xF7u
            }
            clock += time
        }
    }

    private fun getMasterVolumeMono(): Int {
        return (masterVolumeReg.toInt() shr 4) and 7
    }

    private fun getPowerStatus(): UByte {
        return (lengthStatus + if (power) 0x80u else 0u).toUByte()
    }

    fun read(address: UShort): UByte {
        when {
            power -> return when (val add = (address and 0xFFu).toInt()) {
                0x10 -> square1.getSweepReg()
                0x11 -> square1.getPatternReg()
                0x12 -> square1.getEnvelopeReg()
                0x14 -> square1.getControlReg()
                0x16 -> square2.getPatternReg()
                0x17 -> square2.getEnvelopeReg()
                0x19 -> square2.getControlReg()
                0x1A -> wave3.getEnableReg()
                0x1B -> wave3.getLengthReg()
                0x1C -> wave3.getVolumeReg()
                0x1E -> wave3.getControlReg()
                0x21 -> noise4.getEnvelopeReg()
                0x22 -> noise4.getCounterReg()
                0x23 -> noise4.getControlReg()
                0x24 -> masterVolumeReg
                0x25 -> mixerReg
                0x26 -> getPowerStatus() or 0x70u
                in 0x30..0x3F -> {
                    Log.i("GB.mmu", "WaveRAM read $address")
                    waveRam[add and 0xF].toUByte()
                }
                else -> 0xFF.toUByte()
            }
            address == 0xFF26.toUShort() -> return getPowerStatus() or 0x70u
            else -> {
                Log.w("GB.mmu", "$address should not be read while APU is OFF")
                return 0xFFu
            }
        }
    }

    fun write(address: UShort, data: UByte) {
        when {
            power -> when (val add = (address and 0xFFu).toInt()) {
                in 0x30..0x3F -> {
                    //Log.i("GB.apu", "WaveRAM write "+data.toString(2))
                    waveRam[add and 0xF] =
                        data.toByte()
                }
                0x10 -> {
                    //Log.i("GB.apu", "Sq1 Sweep write 0x" + String.format("%02X", data.toByte()))
                    square1.setSweepReg(data)
                }
                0x11 -> {
                    //Log.i("GB.apu", "Sq1 Pattern write 0x" + String.format("%02X", data.toByte()))
                    square1.setPatternReg(data)
                }
                0x12 -> {
                    //Log.i("GB.apu", "Sq1 Envelope write 0x" + String.format("%02X", data.toByte()))
                    square1.setEnvelopeReg(data)
                }
                0x13 -> {
                    //Log.i("GB.apu", "Sq1 lFreq write 0x" + String.format("%02X", data.toByte()))
                    square1.setLowerFrequency(data)
                }
                0x14 -> {
                    //Log.i("GB.apu", "Sq1 Ctrl write 0x" + String.format("%02X", data.toByte()))
                    square1.setControlReg(data)
                    if ((data and 0x80u) > 0u)
                        lengthStatus = lengthStatus or 1u
                }
                0x16 -> {
                    //Log.i("GB.apu", "Sq2 Pattern write 0x" + String.format("%02X", data.toByte()))
                    square2.setPatternReg(data)
                }
                0x17 -> {
                    //Log.i("GB.apu", "Sq2 Envelope write 0x" + String.format("%02X", data.toByte()))
                    square2.setEnvelopeReg(data)
                }
                0x18 -> {
                    //Log.i("GB.apu", "Sq2 lFreq write 0x" + String.format("%02X", data.toByte()))
                    square2.setLowerFrequency(data)
                }
                0x19 -> {
                    //Log.i("GB.apu", "Sq2 Ctrl write 0x" + String.format("%02X", data.toByte()))
                    square2.setControlReg(data)
                    if ((data and 0x80u) > 0u)
                        lengthStatus = lengthStatus or 2u
                }
                0x1A -> {
                    //Log.i("GB.apu", "Wave Enable write 0x" + String.format("%02X", data.toByte()))
                    wave3.setEnableReg(data)
                }
                0x1B -> {
                    //Log.i("GB.apu", "Wave Length write 0x" + String.format("%02X", data.toByte()))
                    wave3.setLengthReg(data)
                }
                0x1C -> {
                    //Log.i("GB.apu", "Wave Volume write 0x" + String.format("%02X", data.toByte()))
                    wave3.setVolumeReg(data)
                }
                0x1D -> {
                    //Log.i("GB.apu", "Wave lFreq write 0x" + String.format("%02X", data.toByte()))
                    wave3.setLowerFrequency(data)
                }
                0x1E -> {
                    //Log.i("GB.apu", "Wave Ctrl write 0x" + String.format("%02X", data.toByte()))
                    wave3.setControlReg(data)
                    if ((data and 0x80u) > 0u)
                        lengthStatus = lengthStatus or 4u
                }
                0x20 -> {
                    //Log.i("GB.apu", "Noise Length write 0x" + String.format("%02X", data.toByte()))
                    noise4.setLengthReg(data)
                }
                0x21 -> {
                    //Log.i("GB.apu", "Noise Envelope write 0x" + String.format("%02X", data.toByte()))
                    noise4.setEnvelopeReg(data)
                }
                0x22 -> {
                    //Log.i("GB.apu", "Noise Counter write 0x" + String.format("%02X", data.toByte()))
                    noise4.setCounterReg(data)
                }
                0x23 -> {
                    //Log.i("GB.apu", "Noise Ctrl write 0x" + String.format("%02X", data.toByte()))
                    noise4.setControlReg(data)
                    if ((data and 0x80u) > 0u)
                        lengthStatus = lengthStatus or 8u
                }
                0x24 -> {
                    //Log.i("GB.apu", "Master Volume = 0x" + String.format("%02X", data.toByte()))
                    masterVolumeReg = data
                    audioTrack.setVolume((1 + getMasterVolumeMono()) / 8f)  //MONO audio
                }
                0x25 -> {
                    if (mixerReg != data)
                        Log.i("GB.apu", "Mixer = 0x" + String.format("%02X", data.toByte()))
                    mixerReg = data
                }
                0x26 -> {
                    power = (data and 0x80u) != 0.toUByte()
                    if (!power) {
                        Log.i("GB.apu", "Power OFF")
                        audioTrack.stop()
                    }
                }
                else -> Log.e("GB.mmu", "$address should not be written to")
            }
            address == 0xFF26.toUShort() -> {
                power = (data and 0x80u) != 0.toUByte()
                if (power) {
                    Log.i("GB.apu", "Power ON")
                    audioTrack.play()
                } else {
                    Log.i("GB.apu", "Power OFF")
                    audioTrack.stop()
                }
            }
            else -> Log.w("GB.mmu", "$address should not be written to while APU is OFF")
        }
    }
}