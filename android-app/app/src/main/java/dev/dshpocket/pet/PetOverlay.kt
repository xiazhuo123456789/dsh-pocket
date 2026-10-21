package dev.dshpocket.pet

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.UiState

/**
 * Picks which clip the pet should loop for the current host state.
 *
 * Only *stable* states map to a looping clip here. The two end-of-turn states
 * (`Done`, `Failed`) are deliberately absent: they are one-shot reactions, handled
 * by [PetSprite], because `ChatState.status` stays `Done` after a turn finishes —
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
 * The in-app pet: a floating sprite drawn over whatever screen is showing.
 *
 * Interaction lives in [PetSprite]: tap = `poke`, long press = `head_pat`. There is
 * deliberately no drag handler *here* — dragging belongs to the floating window,
 * where the pet is the only thing on screen and a drag cannot steal a scroll from
 * the chat list underneath.
 */
@Composable
fun PetOverlay(state: UiState, modifier: Modifier = Modifier) {
    if (!state.settings.petEnabled) return
    PetSprite(
        state = state,
        sizeDp = state.settings.petSize,
        modifier = modifier,
    )
}
