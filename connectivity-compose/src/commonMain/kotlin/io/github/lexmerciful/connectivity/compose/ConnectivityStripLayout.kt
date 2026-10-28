package io.github.lexmerciful.connectivity.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lexmerciful.connectivity.ConnectivityMonitor
import kotlinx.coroutines.delay

/**
 * Wraps the app's root content and shows the connectivity strip while [monitor] reports offline.
 * Add it once, around the root navigation host; no screen needs to change.
 *
 * Collection is lifecycle-aware, so monitoring stops shortly after the app goes to the background.
 *
 * @param enabled `false` hides the strip, e.g. on the splash screen.
 * @param scrimColor colour of the system-bar spacer next to the strip in [StripMode.PushContent] (status
 *   bar for [StripPosition.Top], navigation bar for [StripPosition.Bottom]). It keeps dark status-bar icons
 *   readable in an edge-to-edge app. [Color.Unspecified] leaves it transparent.
 * @param backOnline shows a short "Back online" confirmation after an offline period; `null` (default) hides at once.
 * @param strip the strip itself. Replace it with any composable; it receives what to show.
 */
@Composable
public fun ConnectivityStripLayout(
    monitor: ConnectivityMonitor,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: StripPosition = StripPosition.Top,
    mode: StripMode = StripMode.PushContent,
    scrimColor: Color = MaterialTheme.colorScheme.surface,
    enter: EnterTransition = ConnectivityStripDefaults.enter(position),
    exit: ExitTransition = ConnectivityStripDefaults.exit(position),
    backOnline: BackOnlineConfig? = null,
    strip: @Composable (ConnectivityStripState) -> Unit = { ConnectivityStrip(it) },
    content: @Composable () -> Unit,
) {
    val isConnected by monitor.isConnected.collectAsStateWithLifecycle()
    ConnectivityStripLayout(
        isConnected = isConnected,
        modifier = modifier,
        enabled = enabled,
        position = position,
        mode = mode,
        scrimColor = scrimColor,
        enter = enter,
        exit = exit,
        backOnline = backOnline,
        strip = strip,
        content = content,
    )
}

/**
 * Like the `monitor` overload, driven by a plain [isConnected] value (from your own state holder,
 * a ViewModel, or a test).
 */
@Composable
public fun ConnectivityStripLayout(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: StripPosition = StripPosition.Top,
    mode: StripMode = StripMode.PushContent,
    scrimColor: Color = MaterialTheme.colorScheme.surface,
    enter: EnterTransition = ConnectivityStripDefaults.enter(position),
    exit: ExitTransition = ConnectivityStripDefaults.exit(position),
    backOnline: BackOnlineConfig? = null,
    strip: @Composable (ConnectivityStripState) -> Unit = { ConnectivityStrip(it) },
    content: @Composable () -> Unit,
) {
    var stripState by remember { mutableStateOf(ConnectivityStripState.Offline) }
    var showing by remember { mutableStateOf(!isConnected) }
    LaunchedEffect(isConnected, backOnline) {
        when {
            !isConnected -> {
                stripState = ConnectivityStripState.Offline
                showing = true
            }
            backOnline != null && showing && enabled && stripState == ConnectivityStripState.Offline -> {
                stripState = ConnectivityStripState.BackOnline
                delay(backOnline.duration)
                showing = false
            }
            else -> showing = false
        }
    }

    val transition = remember { MutableTransitionState(false) }
    transition.targetState = enabled && showing
    // True while visible or animating: the spacer and the inset consumption follow the transition, not the
    // target, so the content never jumps by the system-bar height when the animation starts or ends.
    val stripPresent = transition.currentState || transition.targetState

    when (mode) {
        StripMode.PushContent -> PushContentLayout(
            position = position,
            stripPresent = stripPresent,
            scrimColor = scrimColor,
            modifier = modifier,
            strip = {
                AnimatedVisibility(visibleState = transition, enter = enter, exit = exit) {
                    StripContainer { strip(stripState) }
                }
            },
            content = content,
        )
        StripMode.Overlay -> Box(modifier.fillMaxSize()) {
            content()
            AnimatedVisibility(
                visibleState = transition,
                modifier = Modifier
                    .align(if (position == StripPosition.Top) Alignment.TopCenter else Alignment.BottomCenter)
                    .windowInsetsPadding(if (position == StripPosition.Top) WindowInsets.statusBars else bottomInsets()),
                enter = enter,
                exit = exit,
            ) {
                StripContainer { strip(stripState) }
            }
        }
    }
}

/**
 * The strip, a system-bar spacer and the content in a column. While the strip is present, the spacer
 * replaces the system-bar padding the content would apply itself (the content consumes that inset).
 */
@Composable
private fun PushContentLayout(
    position: StripPosition,
    stripPresent: Boolean,
    scrimColor: Color,
    modifier: Modifier,
    strip: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val scrim = if (scrimColor.isSpecified) Modifier.background(scrimColor) else Modifier
    Column(modifier.fillMaxSize()) {
        if (position == StripPosition.Top) {
            if (stripPresent) Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).then(scrim))
            strip()
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (stripPresent) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) { content() }
        } else {
            val bottom = bottomInsets()
            Box(
                Modifier.weight(1f).fillMaxWidth()
                    .then(if (stripPresent) Modifier.consumeWindowInsets(bottom) else Modifier),
            ) { content() }
            strip()
            if (stripPresent) Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(bottom).then(scrim))
        }
    }
}

@Composable
private fun StripContainer(strip: @Composable () -> Unit) {
    // Keeps the strip's content clear of a landscape display cutout.
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
        strip()
    }
}

@Composable
private fun bottomInsets(): WindowInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
