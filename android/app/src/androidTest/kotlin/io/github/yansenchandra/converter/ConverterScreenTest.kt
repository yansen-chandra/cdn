package io.github.yansenchandra.converter

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Drives the real screen on an emulator (en-US). Each test starts with cleared settings. */
@RunWith(AndroidJUnit4::class)
class ConverterScreenTest {
    @get:Rule(order = 0)
    val cleanSettings = object : ExternalResource() {
        override fun before() {
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("converter", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun type(text: String) = rule.onNodeWithTag("query").performTextInput(text)

    @Test
    fun typedConversionShowsResult() {
        type("2 hours to minutes")
        rule.onNodeWithText("120").assertIsDisplayed()
    }

    @Test
    fun ambiguousPintCanBeSwitched() {
        type("1 pint to ml")
        rule.onNodeWithText("473.176").assertIsDisplayed() // US pint by default in en-US
        rule.onNodeWithTag("variant_pint-imperial").performClick()
        rule.onNodeWithText("568.261").assertIsDisplayed()
    }

    @Test
    fun extremeValuesUseScientificNotation() {
        type("1 ev to j")
        rule.onNodeWithText("1.60218 × 10⁻¹⁹").assertIsDisplayed()
    }

    @Test
    fun categoryGridSwitchesToTime() {
        rule.onNodeWithTag("category_all").performClick()
        rule.onNodeWithTag("category_tile_time").performClick()
        rule.onNodeWithTag("amount").performTextReplacement("2")
        rule.onNodeWithTag("result_main").assert(hasText("120"))
    }

    @Test
    fun favouritePairShowsAsChip() {
        rule.onNodeWithTag("favourite_toggle").performClick()
        rule.onNodeWithTag("pair_favourite").assertIsDisplayed()
    }

    @Test
    fun historyRemembersTypedConversion() {
        type("3 kg to lb")
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag("history_button").performClick()
        rule.onNodeWithText("3 kg to lb").assertIsDisplayed()
    }

    @Test
    fun pickerFindsSwissFranc() {
        rule.onNodeWithTag("from_button").performClick()
        rule.onNodeWithTag("picker_search").performTextInput("swiss")
        rule.onNodeWithTag("choice_CHF").assertIsDisplayed()
    }
}
