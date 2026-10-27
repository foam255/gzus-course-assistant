package com.gzus.courseassistant.data.network

import java.math.BigInteger

object CasCrypto {
    private const val MODULUS_HEX =
        "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
            "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
            "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316" +
            "04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"
    private val modulus = BigInteger(MODULUS_HEX, 16)
    private val exponent = BigInteger("010001", 16)
    private val keySize = (modulus.bitLength() + 7) / 8

    fun encrypt(value: String): String {
        require(value.all { it.code <= 255 }) { "Unsupported password character" }
        val raw = value.toByteArray(Charsets.ISO_8859_1)
        val blocks = ArrayList<String>((raw.size + keySize - 1) / keySize)
        var offset = 0
        while (offset < raw.size || blocks.isEmpty()) {
            val length = minOf(keySize, raw.size - offset)
            val block = ByteArray(keySize)
            if (length > 0) {
                System.arraycopy(raw, offset, block, 0, length)
            }
            val number = BigInteger(1, block.reversedArray())
            blocks += number.modPow(exponent, modulus).toString(16)
            offset += keySize
        }
        return blocks.joinToString(" ")
    }
}
