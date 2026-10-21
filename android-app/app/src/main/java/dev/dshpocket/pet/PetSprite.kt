package dev.dshpocket.pet

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.UiState
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * One transient clip, with a token so tapping the pet twice in a row restarts the
 * animation instead of being swallowed as "same value as before".
 */
data class PetReaction(val clip: String, val token: Int)

/** A one-shot speech bubble, same token trick as [PetReaction]. */
data class PetSay(val text: String, val token: Int)

/**
 * The pet sprite itself — one animation loop plus the interaction layer.
 *
 * Split out of [PetOverlay] because the same drawing now has two hosts: the
 * in-app overlay (composed inside the Activity) and the floating overlay window
 * (composed from [PetOverlayService], where there is no Activity at all). Both
 * must show the *same* pet reacting to the *same* [UiState]; duplicating the
 * decode/reaction logic would let the two drift apart.
 *
 * ### Interaction
 *
 * When [onDrag] is null the sprite behaves like before: tap = `poke`, long press
 * = `head_pat`. When [onDrag] is supplied it switches to a single-gesture
 * recogniser — a finger that moves past the touch slop drags the window, a finger
 * that lifts in place taps. Two independent detectors (`detectTapGestures` plus
 * `detectDragGestures`) cannot coexist here: the tap detector claims the pointer
 * on down, and the drag detector never sees the movement it needs.
 *
 * @param externalReaction a one-shot clip pushed in by the host (the action menu
 *   lives outside this composable), replayed whenever its token changes.
 */
@Composable
fun PetSprite(
    state: UiState,
    sizeDp: Int,
    modifier: Modifier = Modifier,
    externalReaction: PetReaction? = null,
    externalSay: PetSay? = null,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onDrag: ((dx: Float, dy: Float) -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val assets = remember(context) { PetAssets(context.assets) }
    val player = remember(assets) { PetPlayer(assets) }
    val scope = rememberCoroutineScope()

    var reaction by remember { mutableStateOf<PetReaction?>(null) }
    var bubble by remember { mutableStateOf<String?>(null) }
    val base = petClipFor(state)

    fun react(clip: String) {
        if (!assets.has(clip)) return
        reaction = PetReaction(clip, (reaction?.token ?: 0) + 1)
    }

    // A clip requested by the host menu. Replayed whenever the token changes, so
    // asking for the same reaction twice in a row still restarts it.
    LaunchedEffect(externalReaction?.token) {
        val incoming = externalReaction ?: return@LaunchedEffect
        react(incoming.clip)
    }

    // End-of-turn is a one-shot event, not a state: fired once per status change.
    LaunchedEffect(state.chat.status) {
        when (state.chat.status) {
            ChatState.Status.Done -> react("success")
            ChatState.Status.Failed, ChatState.Status.Cancelled -> react("error")
            else -> Unit
        }
    }

    // A speech bubble pushed in by the host menu ("碎碎念"), held for a few
    // seconds. The token comparison keeps a stale timer from clearing a newer line.
    LaunchedEffect(externalSay?.token) {
        val incoming = externalSay ?: return@LaunchedEffect
        bubble = incoming.text
        delay(4_500)
        if (externalSay.token == incoming.token) bubble = null
    }

    // A reaction runs for exactly one pass of its own clip, then releases the pet
    // back to whatever the host state says.
    LaunchedEffect(reaction?.token) {
        val current = reaction ?: return@LaunchedEffect
        delay(assets.clips[current.clip]?.durationMs ?: 2_000L)
        if (reaction?.token == current.token) reaction = null
    }

    // Idle micro-actions: a pet that only breathes looks frozen. Only when the
    // base clip is `idle`, so they never interrupt a working animation.
    LaunchedEffect(base, reaction == null) {
        if (base != "idle" || reaction != null) return@LaunchedEffect
        val candidates = listOf("idle_yawn", "idle_stretch", "tail")
        while (true) {
            delay(Random.nextLong(14_000, 30_000))
            if (reaction == null) react(candidates.random())
        }
    }

    LaunchedEffect(base, reaction?.token) {
        val target = reaction?.clip ?: base
        if (assets.has(target)) player.play(scope, target)
    }

    DisposableEffect(Unit) { onDispose { player.stop() } }

    val bitmap: ImageBitmap? by player.frame
    val width = sizeDp.dp
    val height = (sizeDp * assets.aspect).dp

    if (bitmap != null) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            bubble?.let { line ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .padding(bottom = 6.dp),
                ) {
                    Text(
                        text = line,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Image(
            bitmap = bitmap!!,
            contentDescription = stringResource(dev.dshpocket.R.string.pet_content_description),
            modifier = Modifier
                .size(width, height)
                .alpha(if (state.connection.isConnected) 1f else 0.92f)
                .then(
                    if (onDrag == null) {
                        Modifier.pointerInput(assets) {
                            detectTapGestures(
                                onTap = { onTap?.invoke() ?: react("poke") },
                                onLongPress = { onLongPress?.invoke() ?: react("head_pat") },
                            )
                        }
                    } else {
                        Modifier.pointerInput(assets, onDrag) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var travelled = Offset.Zero
                                var dragging = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val delta = change.positionChange()
                                    travelled += delta
                                    if (!dragging && travelled.getDistance() > viewConfiguration.touchSlop) {
                                        dragging = true
                                        onDragStart?.invoke()
                                    }
                                    if (dragging) {
                                        onDrag(delta.x, delta.y)
                                        change.consume()
                                    }
                                }
                                if (dragging) onDragEnd?.invoke() else onTap?.invoke()
                            }
                        }
                    }
                ),
            )
        }
    }
}
