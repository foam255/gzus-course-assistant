package com.gzus.courseassistant

import com.gzus.courseassistant.data.network.CasCrypto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

class CasCryptoTest {
    @Test
    fun encryptsUsingCasPageBlockFormat() {
        val plain = "secret-123"
        val encrypted = CasCrypto.encrypt(plain)
        val decrypted = decrypt(encrypted)
        assertEquals(plain, decrypted)
    }

    private fun decrypt(encrypted: String): String {
        val plain = ByteArray(128)
        encrypted.split(" ").forEachIndexed { blockIndex, part ->
            val value = BigInteger(part, 16).modPow(PRIVATE_EXPONENT, MODULUS)
            val bytes = value.toByteArray()
            val littleEndian = ByteArray(128)
            val significant = if (bytes.size > 128) bytes.copyOfRange(bytes.size - 128, bytes.size) else bytes
            for (index in significant.indices) {
                val target = significant.size - 1 - index
                if (target in littleEndian.indices) littleEndian[target] = significant[index]
            }
            System.arraycopy(littleEndian, 0, plain, blockIndex * 128, 128)
        }
        return plain.toString(Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    private companion object {
        const val MODULUS_HEX =
            "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
                "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
                "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316" +
                "04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"
        const val PRIVATE_EXPONENT_HEX =
            "413798867d69babed22e0dd3d4031c635f3e9dbca0fa50a32974a0e230787b7f" +
                "7ba78caefbee828a051c690357a8cc31dba8efc738b4db22e887571ef1ec5a5a" +
                "55b6d866f6a67527f6a7d78a127c9f687008bb540228b50aa2d1ca5a4ff71107" +
                "234f936b611ac46432a26da9c302eaa7180820df70593353b3f8c0247fe97a45"
        val MODULUS = BigInteger(MODULUS_HEX, 16)
        val PRIVATE_EXPONENT = BigInteger(PRIVATE_EXPONENT_HEX, 16)
    }
}
