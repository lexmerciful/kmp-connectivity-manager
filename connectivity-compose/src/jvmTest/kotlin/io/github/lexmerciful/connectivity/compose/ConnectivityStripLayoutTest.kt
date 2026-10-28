package io.github.lexmerciful.connectivity.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class ConnectivityStripLayoutTest {

    private val contentTag = "content"

    @Test
    fun offlineShowsTheStripAndKeepsTheContent() = runComposeUiTest {
        setContent {
            MaterialTheme { ConnectivityStripLayout(isConnected = false) { BasicText("App", Modifier.testTag(contentTag)) } }
        }
        onNodeWithText(ConnectivityStripDefaults.OfflineText).assertIsDisplayed()
        onNodeWithTag(contentTag).assertExists()
    }

    @Test
    fun onlineShowsNoStrip() = runComposeUiTest {
        setContent {
            MaterialTheme { ConnectivityStripLayout(isConnected = true) { BasicText("App", Modifier.testTag(contentTag)) } }
        }
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
        onNodeWithTag(contentTag).assertExists()
    }

    @Test
    fun toggling() = runComposeUiTest {
        var connected by mutableStateOf(true)
        setContent { MaterialTheme { ConnectivityStripLayout(isConnected = connected) { BasicText("App", Modifier.testTag(contentTag)) } } }
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
        connected = false
        waitForIdle()
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertIsDisplayed()
        connected = true
        waitForIdle()
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
        onNodeWithTag(contentTag).assertExists()
    }

    @Test
    fun disabledHidesTheStrip() = runComposeUiTest {
        setContent { MaterialTheme { ConnectivityStripLayout(isConnected = false, enabled = false) { BasicText("App") } } }
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
    }

    @Test
    fun backOnlineShowsForItsDurationThenHides() = runComposeUiTest {
        mainClock.autoAdvance = false
        var connected by mutableStateOf(false)
        setContent {
            MaterialTheme {
                ConnectivityStripLayout(isConnected = connected, backOnline = BackOnlineConfig(2.seconds)) { BasicText("App") }
            }
        }
        mainClock.advanceTimeBy(1_000)
        onNodeWithText(ConnectivityStripDefaults.OfflineText).assertExists()
        connected = true
        mainClock.advanceTimeBy(1_000)
        onNodeWithText(ConnectivityStripDefaults.BackOnlineText).assertExists()
        mainClock.advanceTimeBy(3_000)
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
    }

    @Test
    fun noBackOnlineByDefault() = runComposeUiTest {
        var connected by mutableStateOf(false)
        setContent { MaterialTheme { ConnectivityStripLayout(isConnected = connected) { BasicText("App") } } }
        connected = true
        waitForIdle()
        onNodeWithText(ConnectivityStripDefaults.BackOnlineText).assertDoesNotExist()
    }

    @Test
    fun customSlotAndCustomisedDefaultStrip() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConnectivityStripLayout(
                    isConnected = false,
                    strip = { state -> Text("Custom: $state", Modifier.testTag("custom")) },
                ) { BasicText("App") }
            }
        }
        onNodeWithText("Custom: Offline").assertIsDisplayed()
        onNodeWithTag(ConnectivityStripDefaults.TestTag).assertDoesNotExist()
    }

    @Test
    fun customTextAndAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConnectivityStripLayout(
                    isConnected = false,
                    strip = { state ->
                        ConnectivityStrip(
                            state = state,
                            offlineText = "Sin conexión",
                            colors = ConnectivityStripDefaults.colors(offlineContainerColor = androidx.compose.ui.graphics.Color.Red),
                            action = { Text("Retry") },
                        )
                    },
                ) { BasicText("App") }
            }
        }
        onNodeWithText("Sin conexión").assertIsDisplayed()
        onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun theStripIsAPoliteLiveRegion() = runComposeUiTest {
        setContent { MaterialTheme { ConnectivityStripLayout(isConnected = false) { BasicText("App") } } }
        onNodeWithTag(ConnectivityStripDefaults.TestTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun pushContentMovesTheContentButOverlayDoesNot() = runComposeUiTest {
        var mode by mutableStateOf(StripMode.PushContent)
        setContent {
            MaterialTheme {
                ConnectivityStripLayout(isConnected = false, mode = mode) {
                    Box(Modifier.fillMaxSize().testTag(contentTag))
                }
            }
        }
        val stripHeight = onNodeWithTag(ConnectivityStripDefaults.TestTag).getBoundsInRoot().let { it.bottom - it.top }
        assertTrue(stripHeight > 0.dp)
        assertEquals(stripHeight, onNodeWithTag(contentTag).getBoundsInRoot().top, "push: content starts below the strip")

        mode = StripMode.Overlay
        waitForIdle()
        assertEquals(0.dp, onNodeWithTag(contentTag).getBoundsInRoot().top, "overlay: content stays in place")
    }

    @Test
    fun bottomPositionPlacesTheStripBelowTheContent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ConnectivityStripLayout(isConnected = false, position = StripPosition.Bottom) {
                    Box(Modifier.fillMaxSize().testTag(contentTag))
                }
            }
        }
        val strip = onNodeWithTag(ConnectivityStripDefaults.TestTag).getBoundsInRoot()
        val content = onNodeWithTag(contentTag).getBoundsInRoot()
        assertEquals(0.dp, content.top)
        assertEquals(content.bottom, strip.top)
    }
}
