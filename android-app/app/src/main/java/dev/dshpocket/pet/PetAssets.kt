package dev.dshpocket.pet

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the pet's animation frames out of the APK's own assets.
 *
 * ### Why the frames are packed, not stored one file per frame
 *
 * The source set is 3444 WebP files. Shipping them individually would add 3444
 * zip entries to the APK (slow to install, slow to enumerate) and would make
 * `AssetManager.open` the per-frame cost instead of decoding. Instead every clip
 * is one `<clip>.bin`: its frames concatenated in order, with the per-frame byte
 * lengths recorded in `pet-manifest.json`. Playing a frame is then a slice of an
 * already-loaded array, which is why [decodeFrame] takes an offset and a length.
 *
 * A whole clip is roughly 2.3 MB, so [PetPlayer] keeps at most two of them
 * resident and decodes frames on demand — the alternative (decoding all 241
 * frames of a clip up front) would be 66 MB per clip at 288x240.
 *
 * ### Licence
 *
 * The frames derive from `PC2005-cloud/dsh-pet` (MIT, Copyright (c) 2026
 * PC2005-cloud), re-encoded here at 0.7x as transparent WebP. The notice ships
 * beside the assets in `assets/pet/LICENSE.txt`; see also
 * `THIRD_PARTY_NOTICES.md`.
 */
class PetAssets(private val assets: AssetManager) {

    /** One animation clip: frame count, frame size, and each frame's byte length. */
    @Serializable
    data class Clip(
        val id: String,
        val frames: Int,
        val width: Int,
        val height: Int,
        val sizes: List<Int>,
    ) {
        /**
         * Byte offset of each frame inside the clip's `.bin`. Derived rather than
         * stored so the manifest stays small and cannot disagree with [sizes].
         */
        val offsets: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val out = IntArray(frames)
            var acc = 0
            for (i in 0 until frames) {
                out[i] = acc
                acc += sizes[i]
            }
            out
        }

        /** How long one pass takes, used to time one-shot interactions. */
        val durationMs: Long get() = frames * 1000L / PetAssets.FPS
    }

    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val fps: Int = PetAssets.FPS,
        val width: Int = 0,
        val height: Int = 0,
        val clips: List<Clip> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Frame rate declared by the packer. Frames are re-encoded at the source 24 fps. */
    val fps: Int

    /** Clips by id (`idle`, `thinking`, `working`, ...). */
    val clips: Map<String, Clip>

    /** Aspect ratio of one frame, height / width, for laying the pet out. */
    val aspect: Float

    init {
        // A missing or malformed manifest must degrade to "no pet", not crash the
        // app: this runs inside composition, and the frames are decoration.
        val manifest = runCatching {
            val text = assets.open("pet/pet-manifest.json").bufferedReader().use { it.readText() }
            json.decodeFromString<Manifest>(text)
        }.getOrNull()
        fps = manifest?.fps ?: FPS
        clips = manifest?.clips?.associateBy { it.id } ?: emptyMap()
        aspect = manifest?.takeIf { it.width > 0 }?.let { it.height.toFloat() / it.width } ?: 0.833f
    }

    fun has(id: String): Boolean = clips.containsKey(id)

    /**
     * The raw bytes of one clip's frames, or null when the asset is missing.
     *
     * Missing is a real possibility (an older APK, a trimmed build), so callers
     * must handle it instead of assuming the pack is always complete.
     */
    fun readClip(id: String): ByteArray? =
        runCatching { assets.open("pet/$id.bin").use { it.readBytes() } }.getOrNull()

    /** Decode one frame out of [bytes] at [offset]..[offset] + [length]. */
    fun decodeFrame(bytes: ByteArray, offset: Int, length: Int): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, offset, length)?.asImageBitmap() }
            .getOrNull()

    companion object {
        /** Source frame rate of every clip in the pack. */
        const val FPS = 24
    }
}
