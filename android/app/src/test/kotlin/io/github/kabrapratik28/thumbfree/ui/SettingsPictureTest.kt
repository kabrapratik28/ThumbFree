package io.github.kabrapratik28.thumbfree.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

// The bubble step's Android Settings picture, on its timeline.
class SettingsPictureTest {
    // Each play: the list for 2 s, ThumbFree's page (Use turns on, the shortcut's cross pulses), then Android's question
    // from 6.4 s; twice, then it rests on the page with its two switches.
    @Test
    fun itPlaysTwiceThenRestsOnTheTwoSwitches() {
        for (play in listOf(0f, SETTINGS_PLAY_MS.toFloat())) {
            assertThat(settingsScreen(play)).isEqualTo(SettingsScreen.LIST)
            assertThat(settingsScreen(play + 1_999)).isEqualTo(SettingsScreen.LIST)
            assertThat(settingsScreen(play + 2_000)).isEqualTo(SettingsScreen.PAGE)
            assertThat(settingsScreen(play + 6_399)).isEqualTo(SettingsScreen.PAGE)
            assertThat(settingsScreen(play + 6_400)).isEqualTo(SettingsScreen.ALLOW)
            assertThat(settingsScreen(play + SETTINGS_PLAY_MS - 1)).isEqualTo(SettingsScreen.ALLOW)
        }
        assertThat(settingsScreen(2f * SETTINGS_PLAY_MS)).isEqualTo(SettingsScreen.PAGE)
        assertThat(settingsScreen(10f * SETTINGS_PLAY_MS)).isEqualTo(SettingsScreen.PAGE)
    }
}
