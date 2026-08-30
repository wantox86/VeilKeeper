package id.quezacolt.veilkeeper.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Compresses a picked image (SPEC-BASE.md Section 17's "Compress if
 * appropriate" step) BEFORE it reaches [id.quezacolt.veilkeeper.crypto.AttachmentCrypto]
 * -- encrypting first and compressing after would be pointless (ciphertext
 * doesn't compress) and would upload/store far more bytes than needed for a
 * vault whose images are mostly screenshots (SPEC-BASE.md Section 17's own
 * examples: "Screenshot VPN", "Screenshot API configuration").
 *
 * Downscales to at most [MAX_DIMENSION_PX] on the longest side (screenshots
 * rarely need to be pixel-perfect for this use case) and re-encodes as JPEG
 * at [JPEG_QUALITY] -- a plain, unconfigurable policy, deliberately: this is
 * a vault for reference screenshots, not a photo editor, so exposing
 * quality/format knobs to the user would be scope creep (SPEC-BASE.md
 * Section 56 Rule 1).
 *
 * NOT unit-testable on the host JVM: [BitmapFactory]/[Bitmap] are part of
 * the Android framework and return null/throw when run outside an Android
 * runtime (no Robolectric dependency exists in this project -- see
 * build.gradle.kts). This is the same category of gap already disclosed for
 * [id.quezacolt.veilkeeper.crypto.Argon2idMasterKeyDeriver] (Sprint 1) and
 * [id.quezacolt.veilkeeper.crypto.KeystoreVdkCipher] (Sprint 3): the actual
 * bitmap decode/compress must be manually verified on a device/emulator
 * (pick a real image in Add Item, confirm the resulting file size is
 * smaller than the original and the decrypted preview still renders
 * correctly).
 */
object ImageCompressor {
    private const val MAX_DIMENSION_PX = 1600
    private const val JPEG_QUALITY = 80
    const val OUTPUT_MIME_TYPE = "image/jpeg"

    /**
     * Reads the image at [uri] via [contentResolver], downscales it if
     * needed, and re-encodes as JPEG. Returns null if the URI can't be
     * decoded as an image (caller should surface an error to the user
     * rather than silently dropping the pick).
     */
    fun compress(contentResolver: ContentResolver, uri: Uri): ByteArray? {
        val original = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
        val scaled = downscaleIfNeeded(original)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        if (scaled !== original) original.recycle()
        scaled.recycle()
        return out.toByteArray()
    }

    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIMENSION_PX) return bitmap

        val scale = MAX_DIMENSION_PX.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
