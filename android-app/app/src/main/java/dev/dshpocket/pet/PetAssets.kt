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
 * The source set is a hundred-odd WebM animations of ~240 frames each. Shipping
 * frames individually would add tens of thousands of zip entries to the APK (slow
 * to install, slow to enumerate) and would make `AssetManager.open` the per-frame
 * cost instead of decoding. Instead every clip is one `<id>.bin`: its frames
 * concatenated in order, with the per-frame byte lengths recorded in
 * `pet-manifest.json`. Playing a frame is then a slice of an already-loaded array,
 * which is why [decodeFrame] takes an offset and a length.
 *
 * A whole clip is a few hundred KB, so [PetPlayer] keeps at most two of them
 * resident and decodes frames on demand.
 *
 * ### Manifest shape (version 2)
 *
 * `clips` carries every animation with its display label and menu group;
 * `groups` is the menu order; `roles` maps the handful of semantic slots the app
 * needs (`idle`, `drag`, `click`, `walk`, `workBusy`, ...) onto clip ids. Keeping
 * the Chinese labels out of Kotlin means the packer can rename or regroup
 * animations without a code change.
 *
 * ### Licence
 *
 * The frames derive from `PC2005-cloud/dsh-pet` (code MIT, artwork not for
 * commercial use), re-encoded here as transparent WebP at a reduced size. The
 * notice ships beside the assets in `assets/pet/LICENSE.txt`; see also
 * `THIRD_PARTY_NOTICES.md`.
 */
class PetAssets(private val assets: AssetManager) {

    /** One animation clip: frame count, frame size, and each frame's byte length. */
    @Serializable
    data class Clip(
        val id: String,
        val label: String = "",
        val group: String = "",
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
    }

    /** One menu section: a title and the clips under it, in packer order. */
    @Serializable
    data class Group(val id: String, val clips: List<String> = emptyList())

    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val fps: Int = PetAssets.FPS,
        val width: Int = 0,
        val height: Int = 0,
        val clips: List<Clip> = emptyList(),
        val groups: List<Group> = emptyList(),
        val roles: Map<String, List<String>> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Frame rate declared by the packer. */
    val fps: Int

    /** Clips by id. */
    val clips: Map<String, Clip>

    /** Menu sections, already filtered down to clips that exist. */
    val groups: List<Group>

    /** Semantic slot -> clip ids, already filtered down to clips that exist. */
    private val roles: Map<String, List<String>>

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
        aspect = manifest?.takeIf { it.width > 0 }?.let { it.height.toFloat() / it.width } ?: 0.565f
        groups = manifest?.groups.orEmpty()
            .map { group -> Group(group.id, group.clips.filter { clips.containsKey(it) }) }
            .filter { it.clips.isNotEmpty() }
        roles = manifest?.roles.orEmpty()
            .mapValues { (_, ids) -> ids.filter { clips.containsKey(it) } }
            .filterValues { it.isNotEmpty() }
    }

    fun has(id: String): Boolean = clips.containsKey(id)

    /** How long one pass of [id] takes, used to time one-shot interactions. */
    fun durationMs(id: String?): Long {
        val clip = id?.let { clips[it] } ?: return 2_000L
        return clip.frames * 1000L / fps.coerceAtLeast(1)
    }

    /** Display label of a clip ("优雅女仆舞"), falling back to its id. */
    fun label(id: String): String = clips[id]?.label?.takeIf { it.isNotEmpty() } ?: id

    /** The clips bound to a semantic role, in packer order. */
    fun roleIds(role: String): List<String> = roles[role].orEmpty()

    /** First clip bound to [role], or null when the pack has no such animation. */
    fun roleClip(role: String): String? = roleIds(role).firstOrNull()

    /** All clips under a menu group title. */
    fun groupClips(title: String): List<String> = groups.firstOrNull { it.id == title }?.clips.orEmpty()

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
        /** Fallback frame rate when the manifest does not say. */
        const val FPS = 12
    }
}
