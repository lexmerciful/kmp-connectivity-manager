package io.github.lexmerciful.connectivity.compose

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the strip is currently showing. */
public enum class ConnectivityStripState {
    /** No internet connection. */
    Offline,

    /** The connection came back; shown briefly when [BackOnlineConfig] is set. */
    BackOnline,
}

/** Where the strip sits. */
public enum class StripPosition { Top, Bottom }

/** How the strip relates to the content. */
public enum class StripMode {
    /** Default. The content moves to make room: nothing is ever covered. */
    PushContent,

    /** The strip floats over the content (top bar or bottom actions are covered while it shows). */
    Overlay,
}

/** Shows a short "Back online" confirmation for [duration] after an offline period. Off unless passed. */
public class BackOnlineConfig(public val duration: Duration = 2.seconds) {
    init {
        require(duration.isPositive() && duration.isFinite()) { "BackOnlineConfig.duration must be positive, was $duration" }
    }
}

/** Container and content colours for both strip states. */
@Immutable
public class ConnectivityStripColors(
    public val offlineContainerColor: Color,
    public val offlineContentColor: Color,
    public val backOnlineContainerColor: Color,
    public val backOnlineContentColor: Color,
) {
    public fun containerColor(state: ConnectivityStripState): Color =
        if (state == ConnectivityStripState.Offline) offlineContainerColor else backOnlineContainerColor

    public fun contentColor(state: ConnectivityStripState): Color =
        if (state == ConnectivityStripState.Offline) offlineContentColor else backOnlineContentColor

    public fun copy(
        offlineContainerColor: Color = this.offlineContainerColor,
        offlineContentColor: Color = this.offlineContentColor,
        backOnlineContainerColor: Color = this.backOnlineContainerColor,
        backOnlineContentColor: Color = this.backOnlineContentColor,
    ): ConnectivityStripColors =
        ConnectivityStripColors(offlineContainerColor, offlineContentColor, backOnlineContainerColor, backOnlineContentColor)

    override fun equals(other: Any?): Boolean = other is ConnectivityStripColors &&
        offlineContainerColor == other.offlineContainerColor &&
        offlineContentColor == other.offlineContentColor &&
        backOnlineContainerColor == other.backOnlineContainerColor &&
        backOnlineContentColor == other.backOnlineContentColor

    override fun hashCode(): Int {
        var result = offlineContainerColor.hashCode()
        result = 31 * result + offlineContentColor.hashCode()
        result = 31 * result + backOnlineContainerColor.hashCode()
        result = 31 * result + backOnlineContentColor.hashCode()
        return result
    }
}

/** Defaults of the strip: Material 3 snackbar tokens (neutral "status", not "error"). */
public object ConnectivityStripDefaults {

    /** Test tag of the default [ConnectivityStrip]. */
    public const val TestTag: String = "ConnectivityStrip"

    public const val OfflineText: String = "No internet connection"
    public const val BackOnlineText: String = "Back online"

    public val ContentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

    @Composable
    public fun colors(
        offlineContainerColor: Color = SnackbarDefaults.color,
        offlineContentColor: Color = SnackbarDefaults.contentColor,
        backOnlineContainerColor: Color = BackOnlineGreen,
        backOnlineContentColor: Color = Color.White,
    ): ConnectivityStripColors =
        ConnectivityStripColors(offlineContainerColor, offlineContentColor, backOnlineContainerColor, backOnlineContentColor)

    public fun enter(position: StripPosition): EnterTransition =
        expandVertically(expandFrom = if (position == StripPosition.Top) Alignment.Top else Alignment.Bottom) + fadeIn()

    public fun exit(position: StripPosition): ExitTransition =
        shrinkVertically(shrinkTowards = if (position == StripPosition.Top) Alignment.Top else Alignment.Bottom) + fadeOut()

    private val BackOnlineGreen = Color(0xFF2E7D32)
}

/**
 * The default strip: a full-width bar with centred text, announced politely by screen readers.
 *
 * Every visual is a parameter; for anything else, pass your own composable as the `strip` slot of
 * [ConnectivityStripLayout].
 *
 * @param icon optional leading content, e.g. a wifi-off icon.
 * @param action optional trailing content, e.g. a "Retry" button.
 */
@Composable
public fun ConnectivityStrip(
    state: ConnectivityStripState = ConnectivityStripState.Offline,
    modifier: Modifier = Modifier,
    offlineText: String = ConnectivityStripDefaults.OfflineText,
    backOnlineText: String = ConnectivityStripDefaults.BackOnlineText,
    colors: ConnectivityStripColors = ConnectivityStripDefaults.colors(),
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = RectangleShape,
    contentPadding: PaddingValues = ConnectivityStripDefaults.ContentPadding,
    textAlign: TextAlign = TextAlign.Center,
    icon: (@Composable (ConnectivityStripState) -> Unit)? = null,
    action: (@Composable (ConnectivityStripState) -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ConnectivityStripDefaults.TestTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = shape,
        color = colors.containerColor(state),
        contentColor = colors.contentColor(state),
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            icon?.invoke(state)
            Text(
                text = if (state == ConnectivityStripState.Offline) offlineText else backOnlineText,
                modifier = Modifier.weight(1f),
                style = textStyle,
                textAlign = textAlign,
            )
            action?.invoke(state)
        }
    }
}
