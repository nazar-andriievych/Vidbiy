package ua.vidbiy.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.vidbiy.app.ui.theme.VidbiyTheme

/**
 * Захист від випадкового скасування (NFR-1, FR-18, FR-21): «Не дзвонити» й «Вимкнути»
 * спрацьовують лише після утримання 1 с. Сценарії В1, В2, Г1 з docs/testing.md.
 *
 * Інструментальний тест: `.\gradlew.bat connectedDebugAndroidTest` з підключеним телефоном
 * чи емулятором. Годинник Compose крутимо вручну — секунда утримання не займає секунди.
 */
@RunWith(AndroidJUnit4::class)
class HoldButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private var confirmed = 0

    @Before
    fun setUp() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            VidbiyTheme {
                HoldButton(
                    text = "Не дзвонити",
                    hint = "Утримуйте секунду, щоб не дзвонити",
                    onConfirmed = { confirmed++ },
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
    }

    @Test
    fun короткий_натиск_не_спрацьовує_а_показує_підказку() {
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(150)
        rule.onNodeWithTag(TAG).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_500)

        assertEquals(0, confirmed)
        rule.onNodeWithText("Утримуйте секунду, щоб не дзвонити").assertIsDisplayed()
    }

    @Test
    fun відпущена_раніше_за_секунду_не_спрацьовує() {
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithTag(TAG).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(1_000)

        assertEquals(0, confirmed)
    }

    @Test
    fun утримання_секунду_спрацьовує_один_раз() {
        rule.onNodeWithTag(TAG).performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(1_100)
        rule.onNodeWithTag(TAG).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(500)

        assertEquals(1, confirmed)
    }

    private companion object {
        const val TAG = "hold"
    }
}
