package dev.dshpocket.pet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.dshpocket.core.ChatItem
import dev.dshpocket.core.ChatState
import dev.dshpocket.core.UiState

/**
 * Picks which clip the pet should loop for the current host state.
 *
 * The ids are resolved through the manifest's `roles` table rather than hard-coded
 * here: the packer decides which animation means "busy" or "waiting", so swapping
 * artwork never means editing Kotlin.
 *
 * Only *stable* states map to a looping clip. The two end-of-turn states (`Done`,
 * `Failed`) are deliberately absent: they are one-shot reactions, handled by
 * [PetSprite], because `ChatState.status` stays `Done` after a turn finishes —
 * looping the success animation forever would be a pet stuck celebrating.
 *
 * Offline is still `idle`: the pet must animate with no desktop paired, which is
 * the whole point of bundling the frames into the APK.
 */
fun petClipFor(state: UiState, assets: PetAssets): String {
    val idle = assets.roleClip("idle")
    if (!state.connection.isConnected) return idle ?: fallback(assets)
    val role = when (state.chat.status) {
        ChatState.Status.Queued -> "workThinking"
        ChatState.Status.AwaitingApproval -> "workWaiting"
        ChatState.Status.Running -> runningRole(state)
        else -> "idle"
    }
    return assets.roleClip(role) ?: idle ?: fallback(assets)
}

/** `workBusy` / `workOrganize` / `workWaiting`, chosen by what the turn is doing. */
private fun runningRole(state: UiState): String {
    val tool = state.chat.items.lastOrNull { it.role == ChatItem.Role.Tool }?.toolName
        ?.lowercase()
        ?: return "workBusy"
    return when (tool) {
        "read", "grep", "glob", "search", "web_search", "web_fetch", "list" -> "workOrganize"
        else -> "workBusy"
    }
}

/** Last resort so a trimmed pack still draws *something* instead of nothing. */
internal fun fallback(assets: PetAssets): String =
    assets.clips.keys.firstOrNull() ?: ""

/**
 * The in-app pet: a floating sprite drawn over whatever screen is showing.
 *
 * Interaction lives in [PetSprite]: tap plays a click reaction, long press a
 * softer one. There is deliberately no drag handler *here* — dragging belongs to
 * the floating window, where the pet is the only thing on screen and a drag
 * cannot steal a scroll from the chat list underneath.
 *
 * @param interactive false while a settings sheet is open. The sprite still draws
 *   above the sheet (so resizing it gives live feedback), but it stops claiming
 *   touches that belong to the controls underneath.
 */
@Composable
fun PetOverlay(state: UiState, modifier: Modifier = Modifier, interactive: Boolean = true) {
    if (!state.settings.petEnabled) return
    val context = LocalContext.current
    val assets = remember(context) { PetAssets(context.assets) }
    PetSprite(
        state = state,
        assets = assets,
        sizeDp = state.settings.petSize,
        modifier = modifier,
        interactive = interactive,
    )
}
