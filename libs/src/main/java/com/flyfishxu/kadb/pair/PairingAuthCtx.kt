/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.flyfishxu.kadb.pair

import com.flyfish233.crypto.spake2.Spake2Context
import com.flyfish233.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.io.UnsupportedEncodingException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.IllegalCharsetNameException
import java.util.*
import javax.security.auth.Destroyable

private val CLIENT_NAME: ByteArray
    get() = getBytes("adb pair client\u0000", "UTF-8")
private val SERVER_NAME: ByteArray
    get() = getBytes("adb pair server\u0000", "UTF-8")

internal val INFO: ByteArray
    get() = getBytes("adb pairing_auth aes-128-gcm key", "UTF-8")

internal val HKDF_KEY_LENGTH: Int
    get() = 16

private val GCM_IV_LENGTH: Int
    get() = 12

internal class PairingAuthCtx(
    private val mSpake2Ctx: Spake2Context, password: ByteArray
) : Destroyable {
    val msg: ByteArray = mSpake2Ctx.generateMessage(password)
    private val mSecretKey = ByteArray(HKDF_KEY_LENGTH)
    private var mDecIv: Long = 0
    private var mEncIv: Long = 0
    private var mIsDestroyed = false

    fun initCipher(theirMsg: ByteArray?): Boolean {
        if (mIsDestroyed) return false
        val keyMaterial = mSpake2Ctx.processMessage(theirMsg) ?: return false
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(keyMaterial, null, INFO))
        hkdf.generateBytes(mSecretKey, 0, mSecretKey.size)
        return true
    }

    fun encrypt(input: ByteArray): ByteArray? {
        return encryptDecrypt(
            true, input, ByteBuffer.allocate(GCM_IV_LENGTH).order(ByteOrder.LITTLE_ENDIAN).putLong(mEncIv++).array()
        )
    }

    fun decrypt(input: ByteArray): ByteArray? {
        return encryptDecrypt(
            false, input, ByteBuffer.allocate(GCM_IV_LENGTH).order(ByteOrder.LITTLE_ENDIAN).putLong(mDecIv++).array()
        )
    }

    override fun isDestroyed(): Boolean {
        return mIsDestroyed
    }

    override fun destroy() {
        mIsDestroyed = true
        Arrays.fill(mSecretKey, 0.toByte())
        mSpake2Ctx.destroy()
    }

    private fun encryptDecrypt(forEncryption: Boolean, `in`: ByteArray, iv: ByteArray): ByteArray? {
        if (mIsDestroyed) return null
        val spec = AEADParameters(KeyParameter(mSecretKey), mSecretKey.size * 8, iv)
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(forEncryption, spec)
        val out = ByteArray(cipher.getOutputSize(`in`.size))
        val newOffset = cipher.processBytes(`in`, 0, `in`.size, out, 0)
        try {
            cipher.doFinal(out, newOffset)
        } catch (e: InvalidCipherTextException) {
            return null
        }
        return out
    }

    companion object {
        fun createAlice(password: ByteArray): PairingAuthCtx? {
            val spake25519 = Spake2Context(Spake2Role.Alice, CLIENT_NAME, SERVER_NAME)
            return try {
                PairingAuthCtx(spake25519, password)
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IllegalStateException) {
                null
            }
        }
    }
}

internal fun getBytes(text: String, charsetName: String): ByteArray {
    return try {
        text.toByteArray(charset(charsetName))
    } catch (e: UnsupportedEncodingException) {
        throw (IllegalCharsetNameException("Illegal charset $charsetName").initCause(e) as IllegalCharsetNameException)
    }
}
