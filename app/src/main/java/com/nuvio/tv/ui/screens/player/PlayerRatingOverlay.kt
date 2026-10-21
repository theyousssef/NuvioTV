package com.nuvio.tv.ui.screens.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val RatingGold = Color(0xFFF5C518)
private val RatingGoldDeep = Color(0xFFE0A800)
private const val REACTION_HOLD_MS = 2_000L

/**
 * Full-screen rating window: five big outlined numbers (6..10) over a blurred still of
 * the video. The picked number fills with gold, bursts, and a short reaction follows.
 * Back is routed by the caller ([onBack]); numbers never close the window themselves.
 */
@Composable
internal fun PlayerRatingOverlay(
    state: PlayerRatingUiState,
    onNumberPressed: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep the last visible content while the exit fade runs.
    var shown by remember { mutableStateOf(state) }
    if (state.isVisible) shown = state

    AnimatedVisibility(
        visible = state.isVisible,
        enter = fadeIn(tween(260)),
        exit = fadeOut(tween(200)),
        modifier = modifier
    ) {
        RatingWindow(state = shown, onNumberPressed = onNumberPressed, onBack = onBack)
    }
}

@Composable
private fun RatingWindow(
    state: PlayerRatingUiState,
    onNumberPressed: (Int) -> Unit,
    onBack: () -> Unit
) {
    val values = PlayerRatingPolicy.DISPLAY_VALUES
    val rootFocusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var focusedIndex by remember {
        mutableIntStateOf(values.indexOf(state.selected).takeIf { it >= 0 } ?: values.size / 2)
    }
    var confirmDown by remember { mutableStateOf(false) }
    var backDown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Controls / panels may still be settling; keep asking until focus lands here.
        repeat(20) {
            if (hasFocus) return@LaunchedEffect
            runCatching { rootFocusRequester.requestFocus() }
            delay(50)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusable()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                val down = native.action == KeyEvent.ACTION_DOWN
                val up = native.action == KeyEvent.ACTION_UP
                when (native.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        if (down) backDown = true
                        if (up && backDown) {
                            backDown = false
                            onBack()
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) focusedIndex = (focusedIndex + 1).coerceAtMost(values.lastIndex)
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        // Only a press that started here counts (not the release of the key that opened us).
                        if (down && native.repeatCount == 0) confirmDown = true
                        if (up && confirmDown) {
                            confirmDown = false
                            onNumberPressed(values[focusedIndex])
                        }
                    }
                }
                true
            }
    ) {
        BlurredBackdrop(state)

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(state)
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                values.forEachIndexed { index, value ->
                    RatingNumber(
                        value = value,
                        focused = hasFocus && index == focusedIndex,
                        selected = state.selected == value,
                        selectionNonce = state.selectionNonce
                    )
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().height(132.dp),
                contentAlignment = Alignment.Center
            ) {
                ReactionMessage(state)
            }
            Spacer(Modifier.weight(0.6f))
            Footer(state)
        }
    }
}

@Composable
private fun BlurredBackdrop(state: PlayerRatingUiState) {
    val frame = state.backdrop
    val frameAlpha by animateFloatAsState(
        targetValue = if (frame != null) 1f else 0f,
        animationSpec = tween(320),
        label = "ratingBackdropAlpha"
    )
    if (frame != null) {
        val image = remember(frame) { frame.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = frameAlpha; scaleX = 1.08f; scaleY = 1.08f }
                // RenderEffect blur on API 31+ smooths the upscaled still further; a no-op below.
                .blur(18.dp)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.38f), Color.Black.copy(alpha = 0.78f)),
                    radius = 1400f
                )
            )
    )
}

@Composable
private fun Header(state: PlayerRatingUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(
                if (state.isEpisode) R.string.player_rating_label_episode else R.string.player_rating_label_movie
            ).uppercase(),
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
            color = RatingGold.copy(alpha = 0.9f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.title,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        state.subtitle?.let {
            Text(
                text = it,
                style = TextStyle(fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.66f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * One number, drawn like a rating star: hollow outline when empty, solid gold when picked.
 * Picking it pours gold in from the bottom with a wavy edge, pulses and throws sparks.
 */
@Composable
private fun RatingNumber(
    value: Int,
    focused: Boolean,
    selected: Boolean,
    selectionNonce: Int
) {
    val fill = remember { Animatable(if (selected) 1f else 0f) }
    val burst = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }

    LaunchedEffect(selected, selectionNonce) {
        if (selected) {
            if (selectionNonce == 0) {
                fill.snapTo(1f)
                return@LaunchedEffect
            }
            // A fresh pick: pour, pulse and burst together.
            launch {
                fill.snapTo(0f)
                fill.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
            }
            launch {
                burst.snapTo(0f)
                delay(380)
                burst.animateTo(1f, tween(650, easing = LinearEasing))
                burst.snapTo(0f)
            }
            launch {
                pulse.snapTo(0.86f)
                delay(380)
                pulse.animateTo(1.28f, tween(170, easing = FastOutSlowInEasing))
                pulse.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
            }
        } else {
            burst.snapTo(0f)
            pulse.animateTo(1f, tween(120))
            fill.animateTo(0f, tween(260))
        }
    }

    val focusScale by animateFloatAsState(
        targetValue = if (focused) 1.16f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "ratingFocusScale"
    )
    val focusGlow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(180),
        label = "ratingFocusGlow"
    )
    val strokeWidthPx = with(LocalDensity.current) { (2.5f + focusGlow * 1.5f).dp.toPx() }
    val cd = stringResource(R.string.player_rating_cd_number, value)

    Box(
        modifier = Modifier
            .widthIn(min = 136.dp)
            .height(210.dp)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) {
        // Halo: white when focused, gold when filled; sparks and shock ring on a fresh pick.
        // Drawn past its bounds on purpose so the halo is wider than the tile.
        Canvas(modifier = Modifier.matchParentSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = 125.dp.toPx()
            if (focusGlow > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.20f * focusGlow), Color.Transparent),
                        center = center,
                        radius = base * 0.85f
                    ),
                    radius = base * 0.85f,
                    center = center
                )
            }
            if (fill.value > 0f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(RatingGold.copy(alpha = 0.38f * fill.value), Color.Transparent),
                        center = center,
                        radius = base
                    ),
                    radius = base,
                    center = center
                )
            }
            val b = burst.value
            if (b > 0f) {
                drawCircle(
                    color = RatingGold.copy(alpha = (1f - b) * 0.9f),
                    radius = base * (0.35f + 0.65f * b),
                    center = center,
                    style = Stroke(width = 6f * (1f - b) + 1f)
                )
                val sparks = 14
                for (i in 0 until sparks) {
                    val angle = (2 * PI * i / sparks + i * 0.17).toFloat()
                    val reach = base * (0.30f + 0.70f * b) * (if (i % 2 == 0) 1f else 0.78f)
                    val p = Offset(center.x + cos(angle) * reach, center.y + sin(angle) * reach)
                    drawCircle(
                        color = (if (i % 3 == 0) Color.White else RatingGold).copy(alpha = 1f - b),
                        radius = (7f * (1f - b)).coerceAtLeast(0.5f) + 1f,
                        center = p
                    )
                }
            }
        }

        // Focus marker under the number.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = focusGlow; scaleX = 0.4f + 0.6f * focusGlow }
                .width(44.dp)
                .height(4.dp)
                .background(if (selected) RatingGold else Color.White, RoundedCornerShape(2.dp))
        )

        val glyph = rememberNumberGlyph(value)
        val outlineColor = Color.White.copy(alpha = 0.55f + 0.45f * focusGlow)
        val goldBrush = remember(glyph) {
            Brush.verticalGradient(
                listOf(Color(0xFFFFE27A), RatingGold, RatingGoldDeep),
                startY = glyph.bounds.top,
                endY = glyph.bounds.bottom
            )
        }
        val density = LocalDensity.current
        val boxWidth = with(density) { (glyph.bounds.width + NUMBER_PAD_PX * 2).toDp() }
        val boxHeight = with(density) { (glyph.bounds.height + NUMBER_PAD_PX * 2).toDp() }
        Canvas(
            modifier = Modifier
                .size(boxWidth, boxHeight)
                .graphicsLayer {
                    val s = focusScale * pulse.value
                    scaleX = s
                    scaleY = s
                }
        ) {
            // Glyph path is in its own coordinates; centre it on its bounds.
            val dx = size.width / 2f - glyph.bounds.center.x
            val dy = size.height / 2f - glyph.bounds.center.y
            translate(dx, dy) {
                // Empty outline (the "unfilled star"); outer half stays visible around the gold.
                drawPath(glyph.path, color = outlineColor, style = Stroke(width = strokeWidthPx))
                val level = fill.value
                if (level >= 0.999f) {
                    drawPath(glyph.path, brush = goldBrush)
                } else if (level > 0f) {
                    // Gold pour, clipped to a rising wave.
                    val top = glyph.bounds.top
                    val bottom = glyph.bounds.bottom
                    val left = glyph.bounds.left - NUMBER_PAD_PX
                    val right = glyph.bounds.right + NUMBER_PAD_PX
                    val surface = bottom - (bottom - top) * level
                    val amplitude = 7f * sin(PI.toFloat() * level)
                    val phase = level * 4f * PI.toFloat()
                    val wave = Path().apply {
                        moveTo(left, bottom + NUMBER_PAD_PX)
                        lineTo(left, surface)
                        val steps = 24
                        for (i in 0..steps) {
                            val x = left + (right - left) * i / steps
                            val y = surface + amplitude * sin(phase + i * (2f * PI.toFloat() / steps) * 1.5f)
                            lineTo(x, y)
                        }
                        lineTo(right, bottom + NUMBER_PAD_PX)
                        close()
                    }
                    clipPath(wave) { drawPath(glyph.path, brush = goldBrush) }
                }
            }
        }
    }
}

private const val NUMBER_PAD_PX = 24f

/** Extra squeeze between the digits of "10", as a fraction of the font size. */
private const val TWO_DIGIT_TIGHTEN_EM = 0.06f

private class NumberGlyph(val path: Path, val bounds: Rect)

/**
 * The number's outline as a single clean path. Font glyphs (Roboto's 6, 8, 9...) are built
 * from overlapping contours, so stroking them directly shows the seams; a UNION of the outline
 * with itself merges those contours into one non-overlapping shape before we stroke/fill it.
 */
@Composable
private fun rememberNumberGlyph(value: Int): NumberGlyph {
    val density = LocalDensity.current
    val textSizePx = with(density) { 140.sp.toPx() }
    return remember(value, textSizePx) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, 900, false)
            } else {
                android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
        }
        val text = value.toString()
        val raw = android.graphics.Path()
        var x = 0f
        text.forEachIndexed { i, ch ->
            val single = android.graphics.Path()
            paint.getTextPath(ch.toString(), 0, 1, x, 0f, single)
            raw.addPath(single)
            x += paint.measureText(ch.toString())
            if (i < text.lastIndex) x -= textSizePx * TWO_DIGIT_TIGHTEN_EM
        }
        val merged = android.graphics.Path().apply {
            op(raw, android.graphics.Path(), android.graphics.Path.Op.UNION)
        }
        val r = android.graphics.RectF()
        @Suppress("DEPRECATION")
        merged.computeBounds(r, true)
        NumberGlyph(merged.asComposePath(), Rect(r.left, r.top, r.right, r.bottom))
    }
}

@Composable
private fun ReactionMessage(state: PlayerRatingUiState) {
    var visibleReaction by remember { mutableStateOf<PlayerRatingReaction?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(state.selectionNonce) {
        val selected = state.selected
        if (state.selectionNonce == 0 || selected == null) {
            visible = false
            return@LaunchedEffect
        }
        visible = false
        delay(420) // let the number fill first
        visibleReaction = PlayerRatingPolicy.reactionFor(selected)
        visible = true
        delay(REACTION_HOLD_MS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible && visibleReaction != null,
        enter = fadeIn(tween(180)) +
            scaleIn(initialScale = 0.5f, animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) +
            slideInVertically(tween(260)) { it / 2 },
        exit = fadeOut(tween(450)) + scaleOut(targetScale = 0.92f, animationSpec = tween(450))
    ) {
        val reaction = visibleReaction ?: return@AnimatedVisibility
        val text = stringResource(
            when (reaction) {
                PlayerRatingReaction.MEH -> R.string.player_rating_reaction_meh
                PlayerRatingReaction.OKAY -> R.string.player_rating_reaction_okay
                PlayerRatingReaction.GREAT -> R.string.player_rating_reaction_great
                PlayerRatingReaction.SUPERB -> R.string.player_rating_reaction_superb
                PlayerRatingReaction.NINJA -> R.string.player_rating_reaction_ninja
            }
        )
        val hot = reaction == PlayerRatingReaction.GREAT ||
            reaction == PlayerRatingReaction.SUPERB ||
            reaction == PlayerRatingReaction.NINJA
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = if (reaction == PlayerRatingReaction.NINJA) 46.sp else 42.sp,
                    fontWeight = if (hot) FontWeight.Black else FontWeight.SemiBold,
                    letterSpacing = if (hot) 1.5.sp else 0.sp,
                    brush = if (hot) Brush.verticalGradient(listOf(Color(0xFFFFE27A), RatingGold)) else null
                ),
                color = if (hot) Color.Unspecified else Color.White.copy(alpha = 0.88f)
            )
            reaction.emoji?.let { emoji ->
                Spacer(Modifier.width(18.dp))
                AnimatedEmoji(emoji = emoji, reaction = reaction)
            }
        }
    }
}

/** Per-reaction emoji motion: drop-and-wobble, thumbs pump, clapping, and head-explode shake. */
@Composable
private fun AnimatedEmoji(emoji: String, reaction: PlayerRatingReaction) {
    val scale = remember { Animatable(0f) }
    val rotation = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    LaunchedEffect(reaction) {
        when (reaction) {
            PlayerRatingReaction.MEH -> {
                scale.snapTo(1f)
                offsetY.snapTo(-90f)
                offsetY.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
                repeat(2) {
                    rotation.animateTo(-14f, tween(180))
                    rotation.animateTo(10f, tween(220))
                }
                rotation.animateTo(0f, tween(160))
            }
            PlayerRatingReaction.GREAT -> {
                rotation.snapTo(-35f)
                launch { rotation.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow)) }
                scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
                repeat(2) {
                    offsetY.animateTo(-18f, tween(140))
                    offsetY.animateTo(0f, spring(dampingRatio = 0.4f))
                }
            }
            PlayerRatingReaction.SUPERB -> {
                scale.animateTo(1f, spring(dampingRatio = 0.5f))
                repeat(7) {
                    scale.animateTo(0.78f, tween(85))
                    rotation.animateTo(if (it % 2 == 0) -8f else 8f, tween(1))
                    scale.animateTo(1.08f, tween(95))
                }
                rotation.animateTo(0f, tween(120))
                scale.animateTo(1f, tween(120))
            }
            PlayerRatingReaction.NINJA -> {
                scale.animateTo(1.45f, tween(220, easing = FastOutSlowInEasing))
                launch { scale.animateTo(1f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessLow)) }
                repeat(6) {
                    rotation.animateTo(if (it % 2 == 0) -12f else 12f, tween(55))
                }
                rotation.animateTo(0f, spring(dampingRatio = 0.4f))
                repeat(2) {
                    scale.animateTo(1.18f, tween(160))
                    scale.animateTo(1f, tween(200))
                }
            }
            PlayerRatingReaction.OKAY -> scale.snapTo(1f)
        }
    }

    Text(
        text = emoji,
        style = TextStyle(fontSize = 54.sp),
        modifier = Modifier.graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
            rotationZ = rotation.value
            translationY = offsetY.value
        }
    )
}

@Composable
private fun Footer(state: PlayerRatingUiState) {
    val backHint = when (state.source) {
        PlayerRatingPromptSource.EXIT -> R.string.player_rating_hint_back_exit
        PlayerRatingPromptSource.NEXT_EPISODE -> R.string.player_rating_hint_back_next
        PlayerRatingPromptSource.EPISODE_PICK -> R.string.player_rating_hint_back_episode
        PlayerRatingPromptSource.PAUSE_BUTTON, null -> R.string.player_rating_hint_back_player
    }
    val status = when (state.syncStatus) {
        PlayerRatingSyncStatus.SYNCING -> R.string.player_rating_status_syncing
        PlayerRatingSyncStatus.SYNCED -> R.string.player_rating_status_synced
        PlayerRatingSyncStatus.NOT_CONNECTED -> R.string.player_rating_status_not_connected
        PlayerRatingSyncStatus.FAILED -> R.string.player_rating_status_failed
        PlayerRatingSyncStatus.IDLE -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            if (status != null) {
                Text(
                    text = stringResource(status),
                    style = TextStyle(fontSize = 13.sp),
                    color = when (state.syncStatus) {
                        PlayerRatingSyncStatus.FAILED -> Color(0xFFFF9E80)
                        PlayerRatingSyncStatus.NOT_CONNECTED -> Color.White.copy(alpha = 0.75f)
                        else -> RatingGold.copy(alpha = 0.85f)
                    }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(backHint) +
                if (state.selected != null) "   ·   " + stringResource(R.string.player_rating_hint_clear) else "",
            style = TextStyle(fontSize = 13.sp),
            color = Color.White.copy(alpha = 0.5f)
        )
    }
}
