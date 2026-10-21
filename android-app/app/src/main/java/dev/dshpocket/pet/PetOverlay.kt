package dev.dshpocket.pet

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.UiState
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Picks which clip the pet should loop for the current host state.
 *
 * Only *stable* states map to a looping clip here. The two end-of-turn states
 * (`Done`, `Failed`) are deliberately absent: they are one-shot reactions, handled
 * by [PetOverlay], because `ChatState.status` stays `Done` after a turn finishes —
 * looping the success animation forever would be a pet stuck celebrating.
 *
 * Offline is `idle`: the pet must still animate with no desktop paired, which is
 * the whole point of bundling the frames into the APK.
 */
fun petClipFor(state: UiState): String {
    if (!state.connection.isConnected) return "idle"
    return when (state.chat.status) {
        ChatState.Status.Queued -> "thinking"
        ChatState.Status.AwaitingApproval -> "waiting"
        ChatState.Status.Running -> runningClip(state)
        else -> "idle"
    }
}

/**
 * `working` has three flavours in the pack, chosen by what the running turn is
 * doing. Falls back to plain `working` whenever the last tool is unknown, which is
 * also the state during plain text generation.
 */
private fun runningClip(state: UiState): String {
    val tool = state.chat.items.lastOrNull { it.role == ChatItem.Role.Tool }?.toolName
        ?.lowercase()
        ?: return "working"
    return when (tool) {
        "bash", "pwsh", "shell", "command", "run_command" -> "working_command"
        "read", "grep", "glob", "search", "web_search", "web_fetch", "list" -> "working_search"
        else -> "working"
    }
}

/**
 * One transient clip, with a token so tapping the pet twice in a row restarts the
 * animation instead of being swallowed as "same value as before".
 */
private data class Reaction(val clip: String, val token: Int)

/**
 * The pet itself: a single floating sprite drawn over whatever screen is showing.
 *
 * ### Interaction
 *
 * Tap = `poke`, long press = `head_pat`. Deliberately no drag handler: the pet
 * floats above a scrolling list and the chat composer, and a drag gesture on top
 * of those would either steal their touches or need a hit-test negotiation far
 * beyond what a decoration justifies.
 *
 * ### Where the state comes from
 *
 * Nothing here talks to the desktop. [UiState] is the same snapshot the rest of
 * the UI renders, so the pet cannot show "working" while the screen says
 * disconnected — that would be the app lying twice about the same fact.
 */
@Composable
fun PetOverlay(state: UiState, modifier: Modifier = Modifier) {
    if (!state.settings.petEnabled) return

    val context = LocalContext.current
    val assets = remember(context) { PetAssets(context.assets) }
    val player = remember(assets) { PetPlayer(assets) }
    val scope = rememberCoroutineScope()

    var reaction by remember { mutableStateOf<Reaction?>(null) }
    val base = petClipFor(state)

    fun react(clip: String) {
        if (!assets.has(clip)) return
        reaction = Reaction(clip, (reaction?.token ?: 0) + 1)
    }

    // End-of-turn is a one-shot event, not a state: fired once per status change.
    LaunchedEffect(state.chat.status) {
        when (state.chat.status) {
            ChatState.Status.Done -> react("success")
            ChatState.Status.Failed, ChatState.Status.Cancelled -> react("error")
            else -> Unit
        }
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
    val width = state.settings.petSize.dp
    val height = (state.settings.petSize * assets.aspect).dp

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = stringResource(dev.dshpocket.R.string.pet_content_description),
            modifier = modifier
                .size(width, height)
                .alpha(if (state.connection.isConnected) 1f else 0.92f)
                .pointerInput(assets) {
                    detectTapGestures(
                        onTap = { react("poke") },
                        onLongPress = { react("head_pat") },
                    )
                },
        )
    }
}
