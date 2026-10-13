package ua.vidbiy.app.ui

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.vidbiy.app.ui.theme.VidbiyTheme

/**
 * Enter у діалозі назви місця (FR-26a) — те саме, що «Зберегти»: порожню назву не зберігає,
 * пробіли на краях обрізає. Сценарій Е8 з docs/testing.md.
 *
 * Інструментальний тест: `.\gradlew.bat connectedDebugAndroidTest` з підключеним телефоном
 * чи емулятором.
 */
@RunWith(AndroidJUnit4::class)
class PlaceNameDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val saved = mutableListOf<String>()

    @Before
    fun setUp() {
        rule.setContent {
            VidbiyTheme {
                PlaceNameDialog(
                    title = "Назва місця",
                    initialName = "",
                    onSave = { saved += it },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun enter_зберігає_назву_без_пробілів_на_краях() {
        rule.onNode(hasSetTextAction()).performTextInput("  Дім ")
        rule.onNode(hasSetTextAction()).performImeAction()

        assertEquals(listOf("Дім"), saved)
    }

    @Test
    fun enter_з_порожнім_полем_нічого_не_зберігає() {
        rule.onNode(hasSetTextAction()).performImeAction()

        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun enter_з_самими_пробілами_нічого_не_зберігає() {
        rule.onNode(hasSetTextAction()).performTextInput("   ")
        rule.onNode(hasSetTextAction()).performImeAction()

        assertEquals(emptyList<String>(), saved)
    }
}
