package io.github.bropines.lens.app.ui.gallery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.max

/*
 * A picture flies between its thumbnail and the viewer.
 *
 * Thumbnails say where they are (heroSource); the viewer, which lies over
 * everything in the same window, draws its page through a "window" that it
 * moves between the thumbnail's place and the page's own (HeroFlight,
 * heroFrame). Nothing is shared between two compositions, so the page can also
 * follow a finger, and what is beneath stays live while it does.
 */

/**
 * Where the thumbnails on screen are, by the key of the picture each shows,
 * and which picture the viewer has taken out of its cell.
 */
@Stable
class HeroRegistry {
    private class Place(val owner: Any, val rect: Rect)

    private val places = HashMap<String, Place>()

    /** The picture the viewer shows; its thumbnail is left empty meanwhile. Null while no picture is out. */
    var shown: String? by mutableStateOf(null)

    /** True while the viewer hides all that is beneath it, which then need not be drawn. */
    var covered: Boolean by mutableStateOf(false)

    internal fun place(key: String, owner: Any, rect: Rect) {
        places[key] = Place(owner, rect)
    }

    internal fun remove(key: String, owner: Any) {
        if (places[key]?.owner === owner) places.remove(key)
    }

    /** Where [key]'s thumbnail is, in the window's root coordinates, or null when none is on screen. */
    fun boundsOf(key: String): Rect? = places[key]?.rect
}

/** The app's registry; null where pictures do not fly (previews of a single screen). */
val LocalHeroRegistry = staticCompositionLocalOf<HeroRegistry?> { null }

/**
 * Marks a thumbnail as the place the pictures [keys] fly out of and back
 * into. A stack of pages gives every page's key. While the viewer shows one
 * of them the thumbnail is not drawn: the picture is "out".
 */
fun Modifier.heroSource(registry: HeroRegistry?, keys: List<String>): Modifier =
    if (registry == null || keys.isEmpty()) {
        this
    } else {
        this.then(HeroSourceElement(registry, keys)).graphicsLayer { alpha = if (registry.shown in keys) 0f else 1f }
    }

fun Modifier.heroSource(registry: HeroRegistry?, key: String): Modifier = heroSource(registry, listOf(key))

private data class HeroSourceElement(val registry: HeroRegistry, val keys: List<String>) : ModifierNodeElement<HeroSourceNode>() {
    override fun create() = HeroSourceNode(registry, keys)

    override fun update(node: HeroSourceNode) = node.rebind(registry, keys)
}

private class HeroSourceNode(private var registry: HeroRegistry, private var keys: List<String>) :
    Modifier.Node(), GlobalPositionAwareModifierNode {
    private var last: Rect? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val rect = Rect(coordinates.positionInRoot(), coordinates.size.toSize())
        last = rect
        keys.forEach { registry.place(it, this, rect) }
    }

    override fun onDetach() {
        keys.forEach { registry.remove(it, this) }
    }

    fun rebind(registry: HeroRegistry, keys: List<String>) {
        if (registry === this.registry && keys == this.keys) return
        this.keys.forEach { this.registry.remove(it, this) }
        this.registry = registry
        this.keys = keys
        last?.let { rect -> keys.forEach { registry.place(it, this, rect) } }
    }
}

/**
 * How a page is drawn while only [HeroFlight.window] of the screen shows it:
 * scaled by [scale] about the middle of its picture, moved by [translation],
 * and cut to [clip] (in the page's own, untransformed pixels) with corners
 * rounded by [corner].
 */
data class HeroFrame(val scale: Float, val translation: Offset, val clip: Rect, val corner: Float)

/**
 * The frame that shows the picture resting at [rest] through [window], both
 * in the page's pixels. The picture always covers the window, as a cropped
 * thumbnail does: a window of another shape shows its middle.
 */
fun heroFrame(window: Rect, rest: Rect, corner: Float = 0f): HeroFrame {
    if (rest.width <= 0f || rest.height <= 0f || window.width <= 0f || window.height <= 0f) {
        return HeroFrame(1f, Offset.Zero, rest, 0f)
    }
    val scale = max(window.width / rest.width, window.height / rest.height)
    val half = Offset(window.width / scale / 2, window.height / scale / 2)
    return HeroFrame(scale, window.center - rest.center, Rect(rest.center - half, rest.center + half), corner / scale)
}

/** How far a drag of [drag] has got towards letting go, 0..1; [travel] is the way that counts as all of it. */
fun dragProgress(drag: Offset, travel: Float): Float = if (travel <= 0f) 0f else (drag.y / travel).coerceIn(0f, 1f)

/**
 * The window while a finger that took the page at [grab] has dragged it by
 * [drag]: the point under the finger stays under it, and the picture shrinks
 * as it is pulled down. Pulling up past the start is resisted.
 */
fun dragWindow(rest: Rect, grab: Offset, drag: Offset, travel: Float): Rect {
    val pull = Offset(drag.x, if (drag.y < 0f) drag.y * UPWARD_RESISTANCE else drag.y)
    val scale = 1f - DRAG_SHRINK * dragProgress(pull, travel)
    val center = grab + pull + (rest.center - grab) * scale
    val half = Offset(rest.width * scale / 2, rest.height * scale / 2)
    return Rect(center - half, center + half)
}

/** [this] scaled by [scale] about its own middle. */
fun Rect.scaledAboutCenter(scale: Float): Rect {
    val half = Offset(width * scale / 2, height * scale / 2)
    return Rect(center - half, center + half)
}

private const val DRAG_SHRINK = 0.34f
private const val UPWARD_RESISTANCE = 0.25f

/** A spring without a wobble that still arrives with some life: the picture's way in and out. */
private val FlightSpring = spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = Rect(0.5f, 0.5f, 0.5f, 0.5f))

/**
 * The viewer's page in flight: the window it is seen through, the dark behind
 * it, and its own presence. At rest ([active] false) the page is drawn as it is.
 */
@Stable
class HeroFlight {
    private val window = Animatable(Rect.Zero, Rect.VectorConverter)
    private val backdrop = Animatable(0f)
    private val presence = Animatable(0f)

    /** Whether the page is drawn through [shownWindow] rather than as it rests. */
    var active: Boolean by mutableStateOf(false)
        private set

    /** Whether something is moving by itself: no new gesture is taken meanwhile. */
    var flying: Boolean by mutableStateOf(false)
        private set

    private var held: Rect? by mutableStateOf(null)
    private var heldBackdrop: Float by mutableFloatStateOf(1f)

    /** The corner radius of the window at a thumbnail, in pixels. */
    var corner: Float by mutableFloatStateOf(0f)

    /** The part of the screen the page shows through. */
    val shownWindow: Rect get() = held ?: window.value

    /** How dark it is behind the page: 0 shows what lies beneath, 1 hides it. */
    val shownBackdrop: Float get() = if (held != null) heldBackdrop else backdrop.value

    /** The page's own opacity. */
    val shownPresence: Float get() = presence.value

    /** How rounded the window is now: as a thumbnail when it is the size of one, square once the page has its own. */
    fun shownCorner(rest: Rect): Float {
        if (!active || rest.width <= 0f) return 0f
        val grown = (shownWindow.width / rest.width).coerceIn(0f, 1f)
        return (corner * (1f - grown) * 1.4f).coerceAtMost(corner)
    }

    /** The page stands where it belongs, fully there. */
    suspend fun settle() {
        held = null
        active = false
        flying = false
        backdrop.snapTo(1f)
        presence.snapTo(1f)
    }

    /** Nothing of the page shows (the viewer's grid has the screen). */
    suspend fun clear() {
        held = null
        active = false
        flying = false
        backdrop.snapTo(0f)
        presence.snapTo(0f)
    }

    /** Out of a thumbnail at [from] to the page's place at [rest]. */
    suspend fun open(from: Rect, rest: Rect) = fly {
        window.snapTo(from)
        presence.snapTo(1f)
        active = true
        coroutineScope {
            launch { backdrop.animateTo(1f, tween(BACKDROP_MS)) }
            launch { window.animateTo(rest, FlightSpring) }
        }
        active = false
    }

    /** With no thumbnail to come from: a short step forward out of the dark. */
    suspend fun appear(rest: Rect) = fly {
        window.snapTo(rest.scaledAboutCenter(APPEAR_SCALE))
        active = true
        coroutineScope {
            launch { backdrop.animateTo(1f, tween(BACKDROP_MS)) }
            launch { presence.animateTo(1f, tween(APPEAR_MS)) }
            launch { window.animateTo(rest, FlightSpring) }
        }
        active = false
    }

    /** A finger has the page: it is drawn where [at] says, over a backdrop thinned to [backdrop]. */
    fun hold(at: Rect, backdrop: Float) {
        held = at
        heldBackdrop = backdrop
        active = true
    }

    /** The finger let go without sending the page away: back to [rest], carrying on at [velocity]. */
    suspend fun release(rest: Rect, velocity: Offset = Offset.Zero) = fly {
        letGo(rest)
        coroutineScope {
            launch { backdrop.animateTo(1f, tween(BACKDROP_MS)) }
            launch { window.animateTo(rest, FlightSpring, initialVelocity = velocity.asRectVelocity()) }
        }
        active = false
    }

    /** Into the thumbnail at [to], from wherever the page is, carrying on at [velocity]. */
    suspend fun land(rest: Rect, to: Rect, velocity: Offset = Offset.Zero) = fly {
        letGo(rest)
        coroutineScope {
            launch { backdrop.animateTo(0f, tween(BACKDROP_MS)) }
            launch { window.animateTo(to, FlightSpring, initialVelocity = velocity.asRectVelocity()) }
        }
    }

    /** With no thumbnail to go back into: a step back into the dark, a little the way it was thrown. */
    suspend fun vanish(rest: Rect, velocity: Offset = Offset.Zero) = fly {
        letGo(rest)
        val from = window.value
        val drift = Offset(velocity.x, velocity.y).let { if (it.getDistance() > 1f) it / it.getDistance() * from.height * 0.12f else Offset.Zero }
        val to = from.scaledAboutCenter(VANISH_SCALE).translate(drift)
        val spec: AnimationSpec<Rect> = tween(VANISH_MS)
        coroutineScope {
            launch { backdrop.animateTo(0f, tween(VANISH_MS)) }
            launch { presence.animateTo(0f, tween(VANISH_MS)) }
            launch { window.animateTo(to, spec) }
        }
    }

    /** From being held, or from rest, to moving by itself from the same place. */
    private suspend fun letGo(rest: Rect) {
        val from = held ?: if (active) window.value else rest
        val dark = if (held != null) heldBackdrop else if (active) backdrop.value else 1f
        window.snapTo(from)
        backdrop.snapTo(dark)
        held = null
        active = true
    }

    private suspend inline fun fly(block: () -> Unit) {
        flying = true
        try {
            block()
        } finally {
            flying = false
        }
    }

    private fun Offset.asRectVelocity() = Rect(x, y, x, y)

    private companion object {
        const val BACKDROP_MS = 220
        const val APPEAR_MS = 160
        const val VANISH_MS = 170
        const val APPEAR_SCALE = 0.94f
        const val VANISH_SCALE = 0.9f
    }
}
