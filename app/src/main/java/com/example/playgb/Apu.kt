package com.example.playgb

import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

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
    private val offset = 0x15
    private var clock = 0

    open class SquareWave {
        private var duty = 0
        private var length = 0
        private var initialVolume = 0
        private var envelopeIncrease = false
        private var numEnvelopeSweep = 0
        private var frequency = 0
        private var stopOutputAfterLength = false
        var restart = false

        fun setPatternReg(data: UByte) {
            duty = (data.toInt() shr 6) and 3
            length = data.toInt() and 0x3F
        }

        fun getPatternReg(): UByte {
            return (((duty and 3) shl 6) + length).toUByte()
        }

        fun setEnvelopeReg(data: UByte) {
            initialVolume = (data.toInt() shr 4) and 0xF
            envelopeIncrease = (data.toInt() and 8) != 0
            numEnvelopeSweep = data.toInt() and 7
        }

        fun getEnvelopeReg(): UByte {
            return ((initialVolume shl 4) + numEnvelopeSweep + 8 * if (envelopeIncrease) 1 else 0).toUByte()
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
            return (64 * if (envelopeIncrease) 1 else 0).toUByte()
        }

        fun isEnvelopeEnabled(): Boolean {
            return (numEnvelopeSweep > 0)
        }

        fun getEnvelopeSweeps(): Int {
            return numEnvelopeSweep //Envelope Frequency=64Hz
        }

        fun getFrequency(): Double {
            return 131072.0 / (2048 - frequency)//Frequency = 131072/(2048-x) Hz
        }

        fun getInitialVolume(): Int {
            return initialVolume
        }

        fun getEnvelopeDirection(): Int {
            return if (envelopeIncrease) 1 else -1
        }

        fun getDutyTemp(): Double {
            val d = when (duty) {
                1, 2, 3 -> duty * 0.25
                else -> 0.125
            }
            return (1 - d) / 2
        }

        fun isLengthEnabled(): Boolean {
            return stopOutputAfterLength
        }
    }

    class SquareWaveWithSweep : SquareWave() {
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

    private var square1 = SquareWaveWithSweep()

    //private var square2 = SquareWave()
    private var masterVolumeReg: UByte = 0u
    private var mixerReg: UByte = 0u
    private var lengthStatus: UByte = 0u
    private var reg = ByteArray(18)
    private var waveRam = ByteArray(16)
    private var power = false

    private fun getMasterVolumeRight(): Int {
        return (masterVolumeReg and 7u).toInt()
    }

    private fun getMasterVolumeLeft(): Int {
        return (masterVolumeReg.toInt() shr 4) and 7
    }

    private fun getChannelConfig(bit: Int): Int {
        return when ((mixerReg.toInt() shr bit) and 0x11) {
            0x11 -> AudioFormat.CHANNEL_OUT_STEREO
            0x10 -> AudioFormat.CHANNEL_OUT_FRONT_LEFT
            0x01 -> AudioFormat.CHANNEL_OUT_FRONT_RIGHT
            else -> AudioFormat.CHANNEL_INVALID
        }
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
                0x24 -> masterVolumeReg
                0x25 -> mixerReg
                0x26 -> (getPowerStatus() or 0x70u).toUByte()
                in 0x30..0x3F -> {
                    Log.i("GB.mmu", "WaveRAM read $address")
                    waveRam[add and 0xF].toUByte()
                }
                0x16 -> (reg[add - offset].toInt() or 0x3F).toUByte()
                0x17, 0x21, 0x22 -> reg[add - offset].toUByte()
                0x19, 0x1E, 0x23 -> (reg[add - offset].toInt() or 0xBF).toUByte()
                0x1A -> (reg[0x1A - offset].toInt() or 0x7F).toUByte()
                0x1C -> (reg[0x1C - offset].toInt() or 0x9F).toUByte()
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
                0x15, 0x1F, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F
                -> Log.w("GB.mmu", "$address should not be written to")
                0x10 -> {
                    Log.i("GB.apu", "Sq1 Sweep write $data")
                    square1.setSweepReg(data)
                }
                0x11 -> {
                    Log.i("GB.apu", "Sq1 Pattern write $data")
                    square1.setPatternReg(data)
                }
                0x12 -> {
                    Log.i("GB.apu", "Sq1 Envelope write $data")
                    square1.setEnvelopeReg(data)
                }
                0x13 -> {
                    Log.i("GB.apu", "Sq1 lFreq write $data")
                    square1.setLowerFrequency(data)
                }
                0x14 -> {
                    Log.i("GB.apu", "Sq1 Ctrl write $data")
                    square1.setControlReg(data)
                    if (square1.restart)
                        startSq1()
                }
                0x24 -> {
                    Log.i("GB.apu", "Master Volume write $data")
                    masterVolumeReg = data
                }
                0x25 -> {
                    Log.i("GB.apu", "Mixer write $data")
                    mixerReg = data
                }
                0x26 -> {
                    Log.i("GB.apu", "Power write $data")
                    power = (data and 0x80u) != 0.toUByte()
                }
                else -> {
                    Log.i("GB.mmu", "APU write $address")
                    reg[add - offset] = data.toByte()
                }
            }
            address == 0xFF26.toUShort() -> {
                Log.i("GB.apu", "Power write $data")
                power = (data and 0x80u) != 0.toUByte()
            }
            else -> Log.i("GB.mmu", "$address should not be written to while APU is OFF")
        }
    }

    private fun startSq1() {
        val ampStep = 16 //for maxAmp=120
        val offset = -120
        val gain = 5// max is 33.34
        val masterVolume = getMasterVolumeLeft() or getMasterVolumeRight()
        var volume = square1.getInitialVolume()
        val freq = square1.getFrequency()
        var numEnvChange = 0
        var changeVolume = 0
        if (square1.isSweepEnabled()) {
            TODO("11/8/20 Implement Sweep functions")
        }
        val dutyOffset = sin(square1.getDutyTemp() / freq)
        if (square1.isLengthEnabled()) {
            TODO("11/8/20 Implement Length functions")
        }
        if (square1.isEnvelopeEnabled()) {
            numEnvChange = square1.getEnvelopeSweeps()
            changeVolume = square1.getEnvelopeDirection()
        }
        val sr = 44100
        val channelConfiguration = getChannelConfig(0)
        val bufferSize =
            AudioTrack.getMinBufferSize(sr, channelConfiguration, AudioFormat.ENCODING_PCM_16BIT)
        val envelopeTrigger = (numEnvChange * sr) / 64
        val music = ShortArray(bufferSize)
        // Create data to play
        var phi = 0.0
        for (i in 0 until bufferSize) {
            val s = sin(phi) - dutyOffset
            if (s > 0)
                music[i] = ((offset + ampStep * volume) * (masterVolume + 1) * gain).toShort()
            if (s < 0)
                music[i] = (-(offset + ampStep * volume) * (masterVolume + 1) * gain).toShort()
            if (i % envelopeTrigger == 0 && square1.isEnvelopeEnabled() && volume + changeVolume in 0..15)
                volume += changeVolume
            phi += 2 * PI * freq / sr
        }
        // Write the music buffer to the AudioTrack object
        audioTrack.write(music, 0, bufferSize)
    }

//    private fun squareAudio(freq: Double) {
//        val sr = 44100
//        val bufferSize =
//            AudioTrack.getMinBufferSize(
//                sr,
//                AudioFormat.CHANNEL_OUT_STEREO,
//                AudioFormat.ENCODING_PCM_16BIT
//            )
//        val music = ShortArray(bufferSize)
//        val amp: Short = 500
//
//        // Start playback
//        audioTrack.play()
//        // Create data to play
//        var phi = 0.0
//        for (i in 0 until bufferSize) {
//            val s = sin(phi)
//            if (s > 0)
//                music[i] = amp
//            if (s < 0)
//                music[i] = (-amp).toShort()
//            phi += 2 * PI * freq / sr
//        }
//        // Write the music buffer to the AudioTrack object
//        audioTrack.write(music, 0, bufferSize)
//    }

    fun timePassed(time: Int) {
/*            val thread = Thread(Runnable {
                val notes = arrayOf(
                    "B", "E", "G", "F#", "E", "B", "A", "F#", "E", "G", "F#", "Eb", "E", "B", "B",
                    "E", "G", "F#", "E", "B", "D", "Db", "C", "Ab", "C", "B", "Bb", "F#", "G", "E",
                    "G", "B", "G", "B", "G", "C", "B", "Bb", "F#", "G", "B", "Bb", "Bb", "B", "B",
                    "G", "B", "G", "B", "G", "D", "Db", "C", "Ab", "C", "B", "Bb", "F#", "G", "E"
                )
                for (note in notes) {
                    when (note) {
                        "C" -> squareAudio(262.0)
                        "C#", "Db" -> squareAudio(277.0)
                        "D" -> squareAudio(294.0)
                        "D#", "Eb" -> squareAudio(311.0)
                        "E" -> squareAudio(330.0)
                        "F" -> squareAudio(349.0)
                        "F#", "Gb" -> squareAudio(370.0)
                        "G" -> squareAudio(392.0)
                        "G#", "Ab" -> squareAudio(415.0)
                        "A" -> squareAudio(440.0)
                        "A#", "Bb" -> squareAudio(466.0)
                        "B" -> squareAudio(494.0)
                        "C5" -> squareAudio(523.0)
                    }
                }
            })
            thread.start()*/
        clock += time
    }
//    private fun printWaveRam() {
//        repeat(16) {
//            Log.i("WaveRAM DATA $it", "${waveRam[it].toUByte()}")
//        }
//    }
}