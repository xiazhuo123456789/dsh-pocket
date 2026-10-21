package dev.dshpocket.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One in-process flag shared by the Activity and [PetOverlayService].
 *
 * The floating pet is a `TYPE_APPLICATION_OVERLAY` window, so it is composited
 * *above* this app's own settings sheets. When the user drags the size slider to
 * 320 dp, the pet's transparent frame covers a switch they are trying to reach,
 * and the window swallows the tap even where the artwork is empty. The Activity
 * therefore marks the pet as touch-blocked while a sheet is open, and the service
 * mirrors that flag onto the window as `FLAG_NOT_TOUCHABLE`.
 *
 * A [StateFlow] rather than a callback so the service reads the current value the
 * moment it starts — the user can enable floating *while* the sheet is open, and
 * that freshly added window must already be click-through.
 */
object PetTouches {
    private val _blocked = MutableStateFlow(false)
    val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    fun setBlocked(blocked: Boolean) {
        _blocked.value = blocked
    }
}
