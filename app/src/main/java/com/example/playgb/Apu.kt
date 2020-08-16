package com.example.playgb

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/*
Registers
---------
Sound registers are mapped to $FF10-$FF3F in memory. Each channel has
five logical registers, NRx0-NRx4, though some don't use NRx0. The value
written to bits marked with '-' has no effect. Reference to the value in
a register means the last value written to it.

	Name Addr 7654 3210 Function
	- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -
			Square 1
	NR10 FF10 -PPP NSSS	Sweep period, negate, shift
	NR11 FF11 DDLL LLLL	Duty, Length load (64-L)
	NR12 FF12 VVVV APPP	Starting volume, Envelope add mode, period
	NR13 FF13 FFFF FFFF	Frequency LSB
	NR14 FF14 TL-- -FFF	Trigger, Length enable, Frequency MSB

			Square 2
	     FF15 ---- ---- Not used
	NR21 FF16 DDLL LLLL	Duty, Length load (64-L)
	NR22 FF17 VVVV APPP	Starting volume, Envelope add mode, period
	NR23 FF18 FFFF FFFF	Frequency LSB
	NR24 FF19 TL-- -FFF	Trigger, Length enable, Frequency MSB

			Wave
	NR30 FF1A E--- ----	DAC power
	NR31 FF1B LLLL LLLL	Length load (256-L)
	NR32 FF1C -VV- ----	Volume code (00=0%, 01=100%, 10=50%, 11=25%)
	NR33 FF1D FFFF FFFF	Frequency LSB
	NR34 FF1E TL-- -FFF	Trigger, Length enable, Frequency MSB

			Noise
	     FF1F ---- ---- Not used
	NR41 FF20 --LL LLLL	Length load (64-L)
	NR42 FF21 VVVV APPP	Starting volume, Envelope add mode, period
	NR43 FF22 SSSS WDDD	Clock shift, Width mode of LFSR, Divisor code
	NR44 FF23 TL-- ----	Trigger, Length enable

			Control/Status
	NR50 FF24 ALLL BRRR	Vin L enable, Left vol, Vin R enable, Right vol
	NR51 FF25 NW21 NW21	Left enables, Right enables
	NR52 FF26 P--- NW21	Power control/status, Channel length statuses

			Not used
	     FF27 ---- ----
	     .... ---- ----
	     FF2F ---- ----

			Wave Table
	     FF30 0000 1111	Samples 0 and 1
	     ....
	     FF3F 0000 1111	Samples 30 and 31

Register Reading
----------------
Reading NR52 yields the current power status and each channel's enabled
status (from the length counter).

Wave RAM reads back as the last value written.

When an NRxx register is read back, the last written value ORed with the
following is returned:

		  NRx0 NRx1 NRx2 NRx3 NRx4
		 - - - - - - - - - - - - - -
	NR1x  $80  $3F  $00  $FF  $BF
	NR2x  $FF  $3F  $00  $FF  $BF
	NR3x  $7F  $FF  $9F  $FF  $BF
	NR4x  $FF  $FF  $00  $00  $BF
	NR5x  $00  $00  $70

	$FF27-$FF2F always read back as $FF

That is, the channel length counters, frequencies, and unused bits
always read back as set to all 1s.
*/
@ExperimentalUnsignedTypes
class Apu(private var audioTrack: AudioTrack) {
    // TODO: 11/8/20 Complete APU Module
    open class SquareWave {
        private var duty = 0
        private var length = 10
        private var initialVolume = 0
        private var envelopeIncrease = false
        private var numEnvelopeSweep = 0
        private var frequency = 0
        private var stopOutputAfterLengthBit = false
        private var dutySequence = BooleanArray(8)
        private var sequenceIndex = 0
        private var freqTrigger = 0
        private var lengthTrigger = 0
        private var envelopeTrigger = 0
        var prevTime = 0
        var envEnabled = false
        var outputVolume = 0
        var enabled = false
        var restart = false

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

        fun getPatternReg(): UByte {
            return (((duty and 3) shl 6) + length).toUByte()
        }

        fun setEnvelopeReg(data: UByte) {
            initialVolume = (data.toInt() shr 4) and 0xF
            envelopeIncrease = (data.toInt() and 8) != 0
            numEnvelopeSweep = data.toInt() and 7
            envelopeTrigger = 65536 * numEnvelopeSweep
        }

        fun getEnvelopeReg(): UByte {
            return ((initialVolume shl 4) + numEnvelopeSweep + 8 * if (envelopeIncrease) 1 else 0).toUByte()
        }

        fun setLowerFrequency(data: UByte) {
            frequency = data.toInt()
        }

        fun setControlReg(data: UByte) {
            stopOutputAfterLengthBit = (data.toInt() and 0x40) != 0
            frequency += ((data.toInt() and 7) shl 8)
            freqTrigger = 2048 - frequency
            restart = (data.toInt() and 0x80) != 0
        }

        fun getControlReg(): UByte {
            return (64 * if (stopOutputAfterLengthBit) 1 else 0).toUByte()
        }

        fun isEnvelopeEnabled(): Boolean {
            return (numEnvelopeSweep > 0)
        }

        fun getEnvelopeSweeps(): Int {
            return numEnvelopeSweep //Envelope Frequency=64Hz
        }

        fun getFrequency(): Int {
            return 131072 / (2048 - frequency)//Frequency = 131072/(2048-x) Hz
        }

        fun getInitialVolume(): Int {
            return initialVolume
        }

        fun getEnvelopeDirection(): Int {
            return if (envelopeIncrease) 1 else -1
        }

        fun getDutyOffset(): Double {
            return when (duty) {
                1 -> 0.7071
                2 -> 0.0
                3 -> -0.7071
                else -> 0.9238
            }
        }

        fun isLengthEnabled(): Boolean {
            return stopOutputAfterLengthBit
        }

        fun getLength(): Double {
            return (64 - length) / 256.0//Sound Length = (64-t1)*(1/256) seconds
        }

        fun getSquareWave(): Int {
            prevTime++
            if (isEnvelopeEnabled() && envEnabled) {
                if (prevTime % envelopeTrigger == 0) {
                    Log.i("Square Data", "$outputVolume at ${getEnvelopeDirection()}")
                    if ((outputVolume != 0 && outputVolume != 15) || (outputVolume == 0 && envelopeIncrease) || (outputVolume == 15 && !envelopeIncrease))
                        outputVolume += getEnvelopeDirection()
                    else {
                        if (outputVolume == 0 && !envelopeIncrease)
                            enabled = false
                        envEnabled = false
                    }
                }
            }
            var output = if (dutySequence[sequenceIndex]) outputVolume else 0
            if (prevTime % freqTrigger == 0) {
                sequenceIndex = (sequenceIndex + 1) % 8
                if (dutySequence[sequenceIndex])
                    output = outputVolume
            }
            if (isLengthEnabled()) {
                if (prevTime % lengthTrigger == 0)
                    enabled = false
            }
            return output
        }
    }

    class SquareWaveWithSweep : Apu.SquareWave() {
        private var sweepTime = 0
        private var sweepDecrease = false
        private var numSweepShift = 0
        fun setSweepReg(data: UByte) {
            sweepTime = ((data.toInt() shr 4) and 7)
            sweepDecrease = (data.toInt() and 8) != 0
            numSweepShift = data.toInt() and 7
        }

        fun getSweepReg(): UByte {
            return ((sweepTime shl 4) + numSweepShift + 8 * if (sweepDecrease) 1 else 0).toUByte()
        }

        fun isSweepEnabled(): Boolean {
            return (sweepTime > 0)
        }
    }

    class Wave {
        private var length = 0
        private var volume = 0
        private var frequency = 0
        private var stopOutputAfterLength = false
        private var soundEnable = false
        var restart = false
        fun getEnableReg(): UByte {
            return (128 * if (soundEnable) 1 else 0).toUByte()
        }

        fun setEnableReg(data: UByte) {
            soundEnable = (data and 0x80u) != 0.toUByte()
        }

        fun getLengthReg(): UByte {
            return length.toUByte()
        }

        fun setLengthReg(data: UByte) {
            length = data.toInt()
        }

        fun getVolumeReg(): UByte {
            return (volume shl 5).toUByte()
        }

        fun setVolumeReg(data: UByte) {
            volume = data.toInt() shr 5
        }

        fun setLowerFrequency(data: UByte) {
            frequency = data.toInt()
        }

        fun setControlReg(data: UByte) {
            stopOutputAfterLength = (data.toInt() and 0x40) != 0
            frequency += ((data.toInt() and 7) shl 8)
            restart = (data.toInt() and 0x80) != 0
        }

        fun getControlReg(): UByte {
            return (64 * if (stopOutputAfterLength) 1 else 0).toUByte()
        }

        fun getFrequency(): Int {
            return 65536 / (2048 - frequency)//Frequency =65536/(2048-x) Hz
        }

        fun isLengthEnabled(): Boolean {
            return stopOutputAfterLength
        }

        fun getOutputLevel(): Int {
            return if (volume > 0) (volume - 1) else 4
        }

        fun isEnabled(): Boolean {
            return soundEnable
        }

        fun getLength(): Double {
            return (256 - length) / 256.0//Sound Length = (64-t1)*(1/256) seconds
        }
    }


    class NoiseWave {
        private var length = 0
        private var initialVolume = 0
        private var envelopeIncrease = false
        private var numEnvelopeSweep = 0
        private var shiftClockFrequency = 0
        private var counterWidthSelect = false
        private var ratioOfFrequency = 0
        private var stopOutputAfterLength = false
        var restart = false
        fun setLengthReg(data: UByte) {
            length = data.toInt() and 0x3F
        }

        fun setEnvelopeReg(data: UByte) {
            initialVolume = (data.toInt() shr 4) and 0xF
            envelopeIncrease = (data.toInt() and 8) != 0
            numEnvelopeSweep = data.toInt() and 7
        }

        fun getCounterReg(): UByte {
            return ((shiftClockFrequency shl 4) + ratioOfFrequency + 8 * if (counterWidthSelect) 1 else 0).toUByte()
        }

        fun setCounterReg(data: UByte) {
            shiftClockFrequency = (data.toInt() shr 4) and 0xF
            counterWidthSelect = (data.toInt() and 8) != 0
            ratioOfFrequency = data.toInt() and 7
        }

        fun getEnvelopeReg(): UByte {
            return ((initialVolume shl 4) + numEnvelopeSweep + 8 * if (envelopeIncrease) 1 else 0).toUByte()
        }

        fun setControlReg(data: UByte) {
            stopOutputAfterLength = (data.toInt() and 0x40) != 0
            restart = (data.toInt() and 0x80) != 0
        }

        fun getControlReg(): UByte {
            return (64 * if (stopOutputAfterLength) 1 else 0).toUByte()
        }

        fun getInitialVolume(): Int {
            return initialVolume
        }

        fun isEnvelopeEnabled(): Boolean {
            return (numEnvelopeSweep > 0)
        }

        fun getEnvelopeSweeps(): Int {
            return numEnvelopeSweep //Envelope Frequency=64Hz
        }

        fun getEnvelopeDirection(): Int {
            return if (envelopeIncrease) 1 else -1
        }

        fun isLengthEnabled(): Boolean {
            return stopOutputAfterLength
        }

        fun getFrequency(): Int {
            return 524288 / (2.0.pow(shiftClockFrequency) * if (ratioOfFrequency == 0) 8 else (16 * ratioOfFrequency)).toInt()//Frequency = 524288 Hz / r / 2^(s+1) ;For r=0 assume r=0.5 instead
        }

        fun getLength(): Double {
            return (64 - length) / 256.0//Sound Length = (64-t1)*(1/256) seconds
        }
    }

    private var square1 = SquareWaveWithSweep()
    private var square2 = SquareWave()
    private var wave3 = Wave()
    private var noise4 = NoiseWave()
    private var masterVolumeReg: UByte = 0u
    private var mixerReg: UByte = 0u
    private var lengthStatus: UByte = 0u
    private var power = false

    private var waveRam = ByteArray(16)
    private var clock = 0
    fun timePassed(time: Int) {
        var amplitude = 0
        if (mixerReg and 0x11u > 0u) {
            if (square1.restart) {
                startSquare(square1)
                square1.enabled = true
                if (square1.isEnvelopeEnabled())
                    square1.envEnabled = true
                square1.outputVolume = square1.getInitialVolume()
                if (square1.outputVolume == 0 && !square1.isEnvelopeEnabled())
                    square1.enabled = false
                square1.restart = false
                square1.prevTime = 1
            }
            repeat(time) {
                if (square1.enabled) {
                    amplitude += square1.getSquareWave() * if ((clock + it) % 95 == 0) 1 else 0
//                    if ((clock + it) % 95 == 0)
//                        Log.i("Square Data", "$amplitude at ${clock + it}")
//                    audioTrack.write(shortArrayOf((amplitude * 5000).toShort()), 0, 1)
                }
            }
        }
        if (mixerReg and 0x22u > 0u) {
            if (square2.restart) {
                startSquare(square2)
                square2.restart = false
            }
        }
        if (mixerReg and 0x44u > 0u) {
            if (wave3.restart) {
                //startWave()
                wave3.restart = false
            }
        }
        if (mixerReg and 0x88u > 0u) {
            if (noise4.restart) {
                startNoise()
                noise4.restart = false
            }
        }
        //Log.i("MIXER TEST", "$amplitude")
        clock += time
    }

    private fun getMasterVolumeRight(): Int {
        return (masterVolumeReg and 7u).toInt()
    }

    private fun getMasterVolumeLeft(): Int {
        return (masterVolumeReg.toInt() shr 4) and 7
    }

    private fun getPowerStatus(): UByte {
        return (lengthStatus + if (power) 0x80u else 0u).toUByte()
    }

    fun read(address: UShort): UByte {
        when {
            power -> return when (val add = (address and 0xFFu).toInt()) {
                0x10 -> (square1.getSweepReg() or 0x80u).toUByte()
                0x11 -> (square1.getPatternReg() or 0x3Fu).toUByte()
                0x12 -> square1.getEnvelopeReg()
                0x14 -> (square1.getControlReg() or 0xBFu).toUByte()
                0x16 -> (square2.getPatternReg() or 0x3Fu).toUByte()
                0x17 -> square2.getEnvelopeReg()
                0x19 -> (square2.getControlReg() or 0xBFu).toUByte()
                0x1A -> (wave3.getEnableReg().toInt() or 0x7F).toUByte()
                0x1B -> wave3.getLengthReg()
                0x1C -> (wave3.getVolumeReg().toInt() or 0x9F).toUByte()
                0x1E -> (wave3.getControlReg() or 0xBFu).toUByte()
                0x21 -> noise4.getEnvelopeReg()
                0x22 -> noise4.getCounterReg()
                0x23 -> (noise4.getControlReg() or 0xBFu).toUByte()
                0x24 -> masterVolumeReg
                0x25 -> mixerReg
                0x26 -> (getPowerStatus() or 0x70u).toUByte()
                in 0x30..0x3F -> {
                    Log.i("GB.mmu", "WaveRAM read $address")
                    waveRam[add and 0xF].toUByte()
                }
                else -> 0xFF.toUByte()
            }
            address == 0xFF26.toUShort() -> return (getPowerStatus() or 0x70u).toUByte()
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
                    Log.i("GB.apu", "WaveRAM write $address")
                    waveRam[add and 0xF] =
                        data.toByte()
                }
                0x10 -> {
                    Log.i("GB.apu", "Sq1 Sweep write 0x" + String.format("%02X", data.toByte()))
                    square1.setSweepReg(data)
                }
                0x11 -> {
                    Log.i("GB.apu", "Sq1 Pattern write 0x" + String.format("%02X", data.toByte()))
                    square1.setPatternReg(data)
                }
                0x12 -> {
                    Log.i("GB.apu", "Sq1 Envelope write 0x" + String.format("%02X", data.toByte()))
                    square1.setEnvelopeReg(data)
                }
                0x13 -> {
                    Log.i("GB.apu", "Sq1 lFreq write 0x" + String.format("%02X", data.toByte()))
                    square1.setLowerFrequency(data)
                }
                0x14 -> {
                    Log.i("GB.apu", "Sq1 Ctrl write 0x" + String.format("%02X", data.toByte()))
                    square1.setControlReg(data)
                }
                0x16 -> {
                    Log.i("GB.apu", "Sq2 Pattern write 0x" + String.format("%02X", data.toByte()))
                    square2.setPatternReg(data)
                }
                0x17 -> {
                    Log.i("GB.apu", "Sq2 Envelope write 0x" + String.format("%02X", data.toByte()))
                    square2.setEnvelopeReg(data)
                }
                0x18 -> {
                    Log.i("GB.apu", "Sq2 lFreq write 0x" + String.format("%02X", data.toByte()))
                    square2.setLowerFrequency(data)
                }
                0x19 -> {
                    Log.i("GB.apu", "Sq2 Ctrl write 0x" + String.format("%02X", data.toByte()))
                    square2.setControlReg(data)
                }
                0x1A -> {
                    Log.i("GB.apu", "Wave Enable write 0x" + String.format("%02X", data.toByte()))
                    wave3.setEnableReg(data)
                }
                0x1B -> {
                    Log.i("GB.apu", "Wave Length write 0x" + String.format("%02X", data.toByte()))
                    wave3.setLengthReg(data)
                }
                0x1C -> {
                    Log.i("GB.apu", "Wave Volume write 0x" + String.format("%02X", data.toByte()))
                    wave3.setVolumeReg(data)
                }
                0x1D -> {
                    Log.i("GB.apu", "Wave lFreq write 0x" + String.format("%02X", data.toByte()))
                    wave3.setLowerFrequency(data)
                }
                0x1E -> {
                    Log.i("GB.apu", "Wave Ctrl write 0x" + String.format("%02X", data.toByte()))
                    wave3.setControlReg(data)
                }
                0x20 -> {
                    Log.i("GB.apu", "Noise Length write 0x" + String.format("%02X", data.toByte()))
                    noise4.setLengthReg(data)
                }
                0x21 -> {
                    Log.i(
                        "GB.apu",
                        "Noise Envelope write 0x" + String.format("%02X", data.toByte())
                    )
                    noise4.setEnvelopeReg(data)
                }
                0x22 -> {
                    Log.i("GB.apu", "Noise Counter write 0x" + String.format("%02X", data.toByte()))
                    noise4.setCounterReg(data)
                }
                0x23 -> {
                    Log.i("GB.apu", "Noise Ctrl write 0x" + String.format("%02X", data.toByte()))
                    noise4.setControlReg(data)
                }
                0x24 -> {
                    Log.i("GB.apu", "Master Volume = 0x" + String.format("%02X", data.toByte()))
                    masterVolumeReg = data
                }
                0x25 -> {
                    if (mixerReg != data)
                        Log.i("GB.apu", "Mixer = 0x" + String.format("%02X", data.toByte()))
                    mixerReg = data
                }
                0x26 -> {
                    power = (data and 0x80u) != 0.toUByte()
                    if (power)
                        Log.i("GB.apu", "Power ON")
                    else {
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

    private fun startSquare(square: SquareWave) {
        val freq = square.getFrequency()
        var numEnvChange = 0
        var changeVolume = 0
        var length = 0.0
        val dutyOffset = square.getDutyOffset()
        if (square.isLengthEnabled())
            length = square.getLength()
        if (square.isEnvelopeEnabled()) {
            numEnvChange = square.getEnvelopeSweeps()
            changeVolume = square.getEnvelopeDirection()
        }
        val sr = 44100
        val envelopeTrigger = (numEnvChange * sr) / 64
        val lengthTrigger = (length * sr).toInt()
        val gain = 50
        // TODO: 12/8/20 Implement left and right channel separately
        var volume = square.getInitialVolume()
        Log.i("GB.apu", "Frequency=$freq")
        if (square === square1)
            if (square1.isSweepEnabled()) {
                TODO("11/8/20 Implement Sweep functions")
            }
        val bufferSize =
            AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        val music = ShortArray(bufferSize)
        var musicLength = 0
        // Create data to play
        var phi = 0.0
        Log.i("Volume", "$volume")
        for (i in 0 until bufferSize) {
            if (square.isEnvelopeEnabled())
                if (i % envelopeTrigger == 0) {
                    if (changeVolume > 0 && volume < 15)
                        volume++
                    if (changeVolume < 0 && volume > 0)
                        volume--
                }
            val s = sin(phi) - dutyOffset
            if (s > 0) {
                music[i] = (volume * gain).toShort()
            }
            if (s < 0)
                music[i] = 0
            musicLength++
            //Log.i("GB.apu", "Data $i = " + music[i].toString(2))
            phi += 2 * PI * freq / sr

            if (square.isLengthEnabled())
                if (i % lengthTrigger == 0)
                    break
        }
        var left = 0f
        var right = 0f
        if (square === square1) {
            if (mixerReg and 0x10u > 0u) left = (1 + getMasterVolumeLeft()) / 8f
            if (mixerReg and 0x1u > 0u) right = (1 + getMasterVolumeRight()) / 8f
        } else {
            if (mixerReg and 0x20u > 0u) left = (1 + getMasterVolumeLeft()) / 8f
            if (mixerReg and 0x2u > 0u) right = (1 + getMasterVolumeRight()) / 8f
        }
        //if (!(square === square1)) {
        audioTrack.setStereoVolume(left, right)
        //Write the music buffer to the AudioTrack object
        audioTrack.write(music, 0, musicLength)
        //}
    }

    private fun startWave() {
        val gain = 50// max is 33.34
        // TODO: 12/8/20 Implement left and right channel separately
        //val masterVolume = getMasterVolumeLeft() or getMasterVolumeRight()
        val freq = wave3.getFrequency()
        var length = 0.0
        Log.i("GB.apu", "Wave Frequency=$freq")
        if (wave3.isLengthEnabled())
            length = wave3.getLength()
        val volume = wave3.getOutputLevel()
        val sr = 44100
        val bufferSize =
            AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        val music = ShortArray(bufferSize)
        val lengthTrigger = (length * sr).toInt()
        var musicLength = 0
        // Create data to play
        if (wave3.isEnabled()) {
            var phi = 0.0
            for (i in 0 until bufferSize) {
                val s = sin(phi)
                if (s > 0)
                    music[i] =
                        ((((waveRam[i % 16].toInt() and 0xF0) shr 4) shr volume) * gain).toShort()
                if (s < 0)
                    music[i] =
                        (((waveRam[i % 16].toInt() and 0xF) shr volume) * gain).toShort()
                //Log.i("GB.apu", "Data $i = ${music[i]}")
                phi += 2 * PI * freq / sr
                musicLength++
                if (wave3.isLengthEnabled())
                    if (i % lengthTrigger == 0)
                        break
            }
            val left = if (mixerReg and 0x40u > 0u) (1 + getMasterVolumeLeft()) / 8f else 0f
            val right = if (mixerReg and 0x4u > 0u) (1 + getMasterVolumeRight()) / 8f else 0f
            audioTrack.setStereoVolume(left, right)
            // Write the music buffer to the AudioTrack object
            audioTrack.write(music, 0, musicLength)
        }
    }

    private fun startNoise() {
        val freq = noise4.getFrequency()
        Log.i("GB.apu", "Noise Frequency=$freq")
        var volume = noise4.getInitialVolume()
        var numEnvChange = 0
        var changeVolume = 0
        var length = 0.0
        val gain = 50
        //val masterVolume = getMasterVolumeLeft() or getMasterVolumeRight()
        if (noise4.isLengthEnabled()) {
            length = noise4.getLength()
        }
        if (noise4.isEnvelopeEnabled()) {
            numEnvChange = noise4.getEnvelopeSweeps()
            changeVolume = noise4.getEnvelopeDirection()
        }
        val sr = 44100
        val bufferSize =
            AudioTrack.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        val envelopeTrigger = (numEnvChange * sr) / 64
        val lengthTrigger = (length * sr).toInt()
        val music = ShortArray(bufferSize)
        var phi = 0.0
        var musicLength = 0
        var amplitude = Random.nextInt(2)
        Log.i("Noise Volume", "$volume")
        for (i in 0 until bufferSize) {
            val s = sin(phi)
            if (s > 0)
                music[i] = (amplitude * gain * volume).toShort()
            if (s < 0)
                music[i] = 0
            phi += 2 * PI * freq / sr
            musicLength++
            amplitude = Random.nextInt(2)
            if (noise4.isEnvelopeEnabled())
                if (i % envelopeTrigger == 0) {
                    if (changeVolume > 0 && volume < 15)
                        volume++
                    if (changeVolume < 0 && volume > 0)
                        volume--
                }
            if (noise4.isLengthEnabled())
                if (i % lengthTrigger == 0)
                    break
        }
        val left = if (mixerReg and 0x80u > 0u) (1 + getMasterVolumeLeft()) / 8f else 0f
        val right = if (mixerReg and 0x8u > 0u) (1 + getMasterVolumeRight()) / 8f else 0f
        audioTrack.setStereoVolume(left, right)
        //Write the music buffer to the AudioTrack object
        audioTrack.write(music, 0, musicLength)
    }
}