package dev.dshpocket.pet

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays one clip at a time by decoding its frames into a single live bitmap slot.
 *
 * ### Why one frame, not a ring buffer
 *
 * The obvious design — pre-decode N frames ahead — costs 276 KB per frame at
 * 288x240 and buys nothing here: decoding one 7-10 KB WebP takes about a
 * millisecond, which fits inside the 41 ms budget of a 24 fps frame with room to
 * spare. So each frame is decoded just in time and the previous bitmap is simply
 * dropped, keeping the whole player at one or two live bitmaps.
 *
 * The clip's compressed bytes *are* cached (about 2.3 MB each, two entries), which
 * is what makes replaying a loop cheap: the asset is read once, not once per pass.
 *
 * ### Threading
 *
 * Decoding runs on [Dispatchers.Default]; only the assignment of the decoded
 * bitmap hops to the main thread, because [frame] is Compose state. A cancelled
 * clip stops decoding at its next frame boundary rather than mid-frame.
 */
class PetPlayer(private val assets: PetAssets) {

    private val _frame = mutableStateOf<ImageBitmap?>(null)

    /** The bitmap to draw right now, or null before the first frame decodes. */
    val frame: State<ImageBitmap?> get() = _frame

    /**
     * Compressed clip bytes, keyed by clip id. Access-ordered so the least
     * recently played clip is evicted; two entries cover "current + the one we
     * just left", which is the common case when an interaction ends.
     */
    private val clipBytes = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean =
            size > 2
    }

    private var job: Job? = null
    private var playing: String? = null

    /**
     * Start [clipId], cancelling whatever was playing.
     *
     * Calling this again with the clip that is already playing is a no-op, so a
     * recomposition that re-runs the caller's effect does not restart the
     * animation from frame zero.
     */
    fun play(scope: CoroutineScope, clipId: String) {
        if (clipId == playing && job?.isActive == true) return
        val clip = assets.clips[clipId] ?: return
        job?.cancel()
        playing = clipId
        job = scope.launch(Dispatchers.Default) {
            val bytes = load(clipId) ?: return@launch
            var index = 0
            while (isActive) {
                val startedAt = System.nanoTime()
                val bitmap = assets.decodeFrame(bytes, clip.offsets[index], clip.sizes[index])
                if (bitmap == null) break
                withContext(Dispatchers.Main) { _frame.value = bitmap }
                index = if (index + 1 >= clip.frames) 0 else index + 1
                val spentMs = (System.nanoTime() - startedAt) / 1_000_000
                delay((1000L / assets.fps - spentMs).coerceAtLeast(8L))
            }
        }
    }

    /** The clip currently being played, for callers that need to avoid restarts. */
    fun playingClip(): String? = playing

    /**
     * Read a clip's bytes, caching them. The map is not thread-safe, so the
     * lookup and the put both happen on the main dispatcher — the file read
     * itself stays off it.
     */
    private suspend fun load(id: String): ByteArray? {
        val cached = withContext(Dispatchers.Main) { clipBytes[id] }
        if (cached != null) return cached
        val bytes = withContext(Dispatchers.IO) { assets.readClip(id) } ?: return null
        withContext(Dispatchers.Main) { clipBytes[id] = bytes }
        return bytes
    }

    /** Stop decoding. The last frame stays on screen until the next [play]. */
    fun stop() {
        job?.cancel()
        job = null
        playing = null
    }

    /** For tests: wait for the current clip's decode loop to finish. */
    suspend fun awaitIdle() {
        job?.join()
    }
}
