package com.example.playgb

import android.util.Log

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
class Apu {
    private var reg = ByteArray(23)
    private var waveRam = ByteArray(16)
    fun read(address: UShort): UByte {
        val add = (address and 0xFFu).toInt()
        Log.i("GB.mmu", "APU read $address")
        return when (add) {
            0x10 -> (reg[0].toInt() or 0x80).toUByte()
            0x11, 0x16 -> (reg[add - 0x10].toInt() or 0x3F).toUByte()
            0x12, 0x17, 0x21, 0x22, 0x24, 0x25 -> reg[add - 0x10].toUByte()
            0x14, 0x19, 0x1E, 0x23 -> (reg[add - 0x10].toInt() or 0xBF).toUByte()
            0x1A -> (reg[0xA].toInt() or 0x7F).toUByte()
            0x1C -> (reg[0xC].toInt() or 0x9F).toUByte()
            0x26 -> (reg[0x16].toInt() or 0x70).toUByte()
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F -> waveRam[add - 0x10].toUByte()
            else -> 0xFF.toUByte()
        }
    }

    fun write(address: UShort, data: UByte) {
        val add = (address and 0xFFu).toInt()
        Log.i("GB.mmu", "APU write $address")
        when (add) {
            0x15, 0x1F, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F -> Log.w(
                "GB.mmu",
                "$address should not be written to"
            )
            0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F -> waveRam[add and 0xF] =
                data.toByte()
            else -> reg[add - 0x10] = data.toByte()
        }
    }
}