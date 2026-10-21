package dev.dshpocket.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.dshpocket.PocketApp
import dev.dshpocket.R
import dev.dshpocket.core.UiState
import dev.dshpocket.ui.DshPocketTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Keeps the pet on screen while other apps are in front.
 *
 * ### Why a foreground service and an overlay window
 *
 * An in-app composable is destroyed the moment the Activity goes off screen, so
 * "the pet should still be there when I open WeChat" cannot be built inside the
 * Activity at all. Two Android mechanisms are required and both are mandatory:
 *
 *  * **A foreground service** so the process is not frozen/collected while the
 *    user is elsewhere. The ongoing notification is the price of that.
 *  * **`TYPE_APPLICATION_OVERLAY`** so the window is composited above other
 *    apps. This needs `SYSTEM_ALERT_WINDOW`, granted from a system settings page
 *    rather than a runtime dialog — hence the permission check in the UI.
 *
 * The window is `WRAP_CONTENT` and follows the sprite's size, so touches outside
 * the pet go to whatever is underneath (`FLAG_NOT_TOUCH_MODAL`).
 *
 * ### Why the pet's state comes from the app singleton
 *
 * [PetOverlayService] has no Activity and no ViewModel of its own. It reads the
 * same [UiState] flow the UI renders, through [PocketApp]. That is what keeps the
 * floating pet and the chat screen from disagreeing about whether a turn is
 * running — the failure mode where the phone says "working" while the computer
 * already finished.
 *
 * ### Movement, and why it is not a teleport
 *
 * The window's `x`/`y` are owned by the window manager, so every move is an
 * `updateViewLayout`. Three ways to move, all of them continuous:
 *
 *  * **Drag** follows the finger 1:1.
 *  * **Fling** (lifting a finger while still moving) carries the velocity, decays
 *    it, and reflects it off the screen edges — the pet bounces instead of
 *    stopping dead at the border, which is what the desktop plugin does.
 *  * **Roam** picks a nearby point and *walks* there a few pixels per frame while
 *    the sprite plays the walk animation and flips to face the direction of
 *    travel. A straight-line slide with a frozen sprite is what the previous
 *    version did, and it looked like the pet was being dragged by a rope.
 *
 * On its own the pet wanders only while idle — a working turn pins it, so the
 * animation that says "I am busy" is never sliding off somewhere else.
 */
class PetOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var owner: OverlayLifecycleOwner
    private val petAssets by lazy { PetAssets(applicationContext.assets) }

    private var petView: ComposeView? = null
    private var menuView: ComposeView? = null
    private var petParams: WindowManager.LayoutParams? = null

    private var petWidth = 0
    private var petHeight = 0
    private var x = 0
    private var y = 0
    private var menuOpen = false
    private var moving = false

    /** True while a finger is on the pet, drives the drag animation. */
    private val dragState = mutableStateOf(false)

    /** Which way the pet last jumped, so the sprite can face where it is going. */
    private val facingLeft = mutableStateOf(false)

    /** Flipped for a moment on touchdown, drives the landing squash. */
    private val landing = mutableStateOf(false)

    private var velocityX = 0f
    private var velocityY = 0f
    private var lastDragAt = 0L

    private val reaction = mutableStateOf<PetReaction?>(null)
    private val say = mutableStateOf<PetSay?>(null)

    private val state: kotlinx.coroutines.flow.StateFlow<UiState>
        get() = PocketApp.instance.viewModel.state

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        owner = OverlayLifecycleOwner().also { it.attach() }
        createChannel()
        startInForeground()
        addPetWindow()
        startRoaming()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        closeMenu()
        petView?.let { runCatching { windowManager.removeView(it) } }
        petView = null
        owner.detach()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pet window

    private fun addPetWindow() {
        val view = ComposeView(this).also { it.bindOwner(owner) }
        view.setContent {
            DshPocketTheme {
                val snapshot by state.collectAsState()
                PetSprite(
                    state = snapshot,
                    assets = petAssets,
                    sizeDp = snapshot.settings.petSize,
                    externalReaction = reaction.value,
                    externalSay = say.value,
                    dragging = dragState.value,
                    landing = landing.value,
                    flipHorizontally = facingLeft.value,
                    // A tap opens the action sheet; dragging is handled by the
                    // gesture recogniser inside PetSprite.
                    onTap = { openMenu() },
                    onDrag = { dx, dy -> moveBy(dx, dy) },
                    onDragStart = {
                        dragState.value = true
                        velocityX = 0f
                        velocityY = 0f
                        lastDragAt = System.currentTimeMillis()
                    },
                    onDragEnd = {
                        dragState.value = false
                        flingIfNeeded()
                    },
                )
            }
        }

        applyPetSize(state.value.settings.petSize)
        val bounds = screenBounds()
        x = (bounds.width() - petWidth - dpToPx(20)).coerceAtLeast(0)
        y = (bounds.height() - petHeight - dpToPx(160)).coerceAtLeast(0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = this@PetOverlayService.x
            this.y = this@PetOverlayService.y
        }

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                // Losing SYSTEM_ALERT_WINDOW between the check and this moment is
                // possible (the user can revoke it from the same settings page).
                // A window that cannot be added must not leave a stray service —
                // and it must not leave the switch claiming "on" either, or the
                // only feedback the user gets is a toggle that does nothing.
                runCatching { PocketApp.instance.viewModel.setPetFloating(false) }
                stopSelf()
                return
            }
        petView = view
        petParams = params

        scope.launch {
            state.collect { snapshot ->
                val changed = applyPetSize(snapshot.settings.petSize)
                if (changed) clampIntoBounds()
            }
        }
    }

    /** Recomputes the window footprint; returns true when it actually changed. */
    private fun applyPetSize(sizeDp: Int): Boolean {
        val width = dpToPx(sizeDp)
        val height = dpToPx((sizeDp * petAssets.aspect).roundToInt().coerceAtLeast(1))
        if (width == petWidth && height == petHeight) return false
        petWidth = width
        petHeight = height
        return true
    }

    private fun moveBy(dx: Float, dy: Float) {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastDragAt).coerceAtLeast(1L)
        // Exponential smoothing: the last few moves matter more than the first
        // one, so a flick that ends in a pause does not fling at all.
        val blend = (elapsed / 40f).coerceIn(0.2f, 1f)
        velocityX = velocityX * (1 - blend) + (dx / elapsed * 16f) * blend
        velocityY = velocityY * (1 - blend) + (dy / elapsed * 16f) * blend
        lastDragAt = now

        x += dx.roundToInt()
        y += dy.roundToInt()
        clampIntoBounds()
    }

    /** Continue the drag's velocity after the finger leaves. */
    private fun flingIfNeeded() {
        val speed = kotlin.math.hypot(velocityX, velocityY)
        if (speed < 1.5f) {
            velocityX = 0f
            velocityY = 0f
            return
        }
        scope.launch {
            moving = true
            try {
                var vx = velocityX
                var vy = velocityY
                while (isActive) {
                    val gain = kotlin.math.hypot(vx, vy)
                    if (gain < 0.6f) break
                    val bounds = screenBounds()
                    val inset = dpToPx(8)
                    val maxX = (bounds.width() - petWidth - inset).coerceAtLeast(inset)
                    val maxY = (bounds.height() - petHeight - inset).coerceAtLeast(inset)

                    var px = x + vx
                    var py = y + vy
                    if (px <= inset) {
                        px = inset.toFloat()
                        vx = abs(vx) * BOUNCE
                    } else if (px >= maxX) {
                        px = maxX.toFloat()
                        vx = -abs(vx) * BOUNCE
                    }
                    if (py <= inset) {
                        py = inset.toFloat()
                        vy = abs(vy) * BOUNCE
                    } else if (py >= maxY) {
                        py = maxY.toFloat()
                        vy = -abs(vy) * BOUNCE
                    }

                    facingLeft.value = vx < 0
                    x = px.roundToInt()
                    y = py.roundToInt()
                    applyPosition()

                    vx *= FRICTION
                    vy *= FRICTION
                    delay(16)
                }
            } finally {
                moving = false
            }
        }
    }

    private fun clampIntoBounds() {
        val bounds = screenBounds()
        val inset = dpToPx(8)
        val maxX = (bounds.width() - petWidth - inset).coerceAtLeast(0)
        val maxY = (bounds.height() - petHeight - inset).coerceAtLeast(0)
        x = x.coerceIn(inset, maxX)
        y = y.coerceIn(inset, maxY)
        applyPosition()
    }

    private fun applyPosition() {
        val params = petParams ?: return
        val view = petView ?: return
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun resetPosition() {
        val bounds = screenBounds()
        x = (bounds.width() - petWidth - dpToPx(20)).coerceAtLeast(0)
        y = (bounds.height() - petHeight - dpToPx(160)).coerceAtLeast(0)
        clampIntoBounds()
    }

    // -------------------------------------------------------------------- roaming

    /**
     * Wanders every 10–22 s, but only when the pet is idle and not in the middle of
     * something else: a turn in flight owns the sprite, and walking off mid-turn
     * would read as "it ran away" rather than "it is working".
     */
    private fun startRoaming() {
        scope.launch {
            while (isActive) {
                delay(Random.nextLong(9_000, 20_000))
                val snapshot = state.value
                if (dragState.value || menuOpen || moving) continue
                if (!snapshot.settings.petRoam) continue
                if (petClipFor(snapshot, petAssets) != petAssets.roleClip("idle")) continue
                hopSomewhere()
            }
        }
    }

    /**
     * Hops to a nearby spot, one step at a time. Every few seconds the pet picks a
     * ledge within reach — mostly sideways, sometimes up or down a level — and
     * jumps there on an arc, landing with a short squash. Small hops instead of a
     * continuous slide: a sprite that glides while its feet are planted reads as a
     * bug, while a hop reads as movement even without a dedicated walk cycle.
     */
    private suspend fun hopSomewhere() {
        val bounds = screenBounds()
        val inset = dpToPx(12)
        val maxX = (bounds.width() - petWidth - inset).coerceAtLeast(inset)
        val maxY = (bounds.height() - petHeight - inset).coerceAtLeast(inset)

        val direction = if (Random.nextBoolean()) 1 else -1
        val reachX = Random.nextInt(dpToPx(56), dpToPx(150))
        // Down more often than up: "hopping down a step" is the natural read.
        val reachY = Random.nextInt(-dpToPx(20), dpToPx(64))
        val targetX = (x + direction * reachX).coerceIn(inset, maxX)
        val targetY = (y + reachY).coerceIn(inset, maxY)

        val startX = x
        val startY = y
        val deltaX = targetX - startX
        val deltaY = targetY - startY
        val durationMs = (360 + (abs(deltaX) + abs(deltaY)) / 2).coerceIn(360, 620)
        val arc = dpToPx(Random.nextInt(18, 36))

        facingLeft.value = deltaX < 0
        moving = true
        try {
            val startedAt = System.currentTimeMillis()
            while (currentCoroutineContext().isActive) {
                if (dragState.value || menuOpen) break
                val t = ((System.currentTimeMillis() - startedAt).toFloat() / durationMs)
                    .coerceIn(0f, 1f)
                val eased = t * t * (3f - 2f * t)
                x = (startX + deltaX * eased).roundToInt()
                y = (startY + deltaY * eased - arc * kotlin.math.sin(Math.PI * t)).roundToInt()
                applyPosition()
                if (t >= 1f) break
                delay(16)
            }
            x = targetX
            y = targetY
            applyPosition()
            landing.value = true
            delay(130)
            landing.value = false
        } finally {
            moving = false
        }
    }

    // ----------------------------------------------------------------------- menu

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true

        val view = ComposeView(this).also { it.bindOwner(owner) }
        view.setContent {
            DshPocketTheme {
                val snapshot by state.collectAsState()
                PetActionMenu(
                    assets = petAssets,
                    roam = snapshot.settings.petRoam,
                    size = snapshot.settings.petSize,
                    onReaction = { clip ->
                        val next = (reaction.value?.token ?: 0) + 1
                        reaction.value = PetReaction(clip, next)
                        closeMenu()
                    },
                    onSay = { line ->
                        val next = (say.value?.token ?: 0) + 1
                        say.value = PetSay(line, next)
                        closeMenu()
                    },
                    onRoamChange = { PocketApp.instance.viewModel.setPetRoam(it) },
                    onSizeChange = { PocketApp.instance.viewModel.setPetSize(it) },
                    onResetPosition = {
                        resetPosition()
                        closeMenu()
                    },
                    onOpenApp = {
                        openApp()
                        closeMenu()
                    },
                    onClosePet = {
                        PocketApp.instance.viewModel.setPetFloating(false)
                        stopSelf()
                    },
                    onDismiss = { closeMenu() },
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                menuOpen = false
                return
            }
        menuView = view
    }

    private fun closeMenu() {
        val view = menuView ?: return
        menuView = null
        menuOpen = false
        runCatching { windowManager.removeView(view) }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Android 10+ blocks background activity starts. The menu is usually
        // opened while this app is not foreground, so a refusal is expected and
        // must not crash the service.
        runCatching { startActivity(launch) }
    }

    // ------------------------------------------------------------------ plumbing

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.pet_notif_channel),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.pet_notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.pet_notif_title))
            .setContentText(getString(R.string.pet_notif_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun screenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Rect(0, 0, bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).roundToInt()

    private fun ComposeView.bindOwner(owner: OverlayLifecycleOwner) {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
    }

    companion object {
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 0x0FE7
        private const val BOUNCE = 0.62f
        private const val FRICTION = 0.94f

        /** True while the overlay window is on screen, for the in-app UI to hide its twin. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PetOverlayService::class.java))
        }
    }
}

/**
 * The lifecycle a ComposeView needs when there is no Activity.
 *
 * `ComposeView` refuses to compose without a `ViewTreeLifecycleOwner`, and
 * `rememberSaveable` additionally needs a `ViewTreeSavedStateRegistryOwner`. A
 * service is neither, so this adapter supplies both: restored once on attach and
 * driven straight to `RESUMED` (an overlay has no concept of pause), then
 * `DESTROYED` so the composition is disposed and its coroutines stop.
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry

    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    fun attach() {
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun detach() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
