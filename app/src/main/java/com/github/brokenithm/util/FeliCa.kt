package com.github.brokenithm.util

import android.nfc.Tag
import android.nfc.tech.NfcF
import android.nfc.tech.TagTechnology

@Suppress("Unused", "MemberVisibilityCanBePrivate", "SpellCheckingInspection", "PropertyName")
class FeliCa private constructor(private val nfcF: NfcF) : TagTechnology {
    private lateinit var mTag: Tag

    var IDm: ByteArray? = null
        private set

    var PMm: ByteArray? = null
        private set

    val systemCode: ByteArray
        get() = nfcF.systemCode

    override fun connect() = nfcF.connect()
    override fun isConnected() = nfcF.isConnected
    override fun close() {
        IDm = null
        PMm = null
        nfcF.close()
    }
    override fun getTag() = mTag
    fun getMaxTransceiveLength() = nfcF.maxTransceiveLength
    fun transceive(data: ByteArray): ByteArray = nfcF.transceive(data)
    var timeout: Int
        get() = nfcF.timeout
        set(value) { nfcF.timeout = value }

    private fun checkConnected() {
        if (!nfcF.isConnected)
            throw IllegalStateException("Call connect() first!")
    }

    fun poll(systemCode: Int = 0xFFFF, requestCode: Int = 0x01) {
        checkConnected()

        val buffer = ByteArray(6)
        buffer[0] = 6
        buffer[1] = FELICA_CMD_POLLING
        buffer[2] = ((systemCode shr 8) and 0xff).toByte()
        buffer[3] = (systemCode and 0xff).toByte()
        buffer[4] = requestCode.toByte()
        buffer[5] = 0
        val result = nfcF.transceive(buffer)
        if (result.size != 18 && result.size != 20)
            throw IllegalStateException("Poll FeliCa response incorrect")
        IDm = result.copyOfRange(2, 10)
        PMm = result.copyOfRange(10, 18)
    }

    companion object {
        private const val FELICA_CMD_POLLING: Byte = 0x00
        fun get(tag: Tag): FeliCa? {
            val realTag = NfcF.get(tag) ?: return null
            return FeliCa(realTag).apply { mTag = tag }
        }
    }
}