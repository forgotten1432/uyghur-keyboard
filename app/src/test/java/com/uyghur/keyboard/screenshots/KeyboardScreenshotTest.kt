package com.uyghur.keyboard.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.uyghur.keyboard.keyboard.KeyboardTheme
import com.uyghur.keyboard.keyboard.KeyboardView
import com.uyghur.keyboard.keyboard.Language
import com.uyghur.keyboard.keyboard.Layouts
import com.uyghur.keyboard.keyboard.ShiftState
import org.junit.Rule
import org.junit.Test

/**
 * Renders the keyboard on the JVM so its look can be checked without a phone.
 * Images: ./gradlew recordPaparazziDebug → app/src/test/snapshots/images/
 */
class KeyboardScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
        renderingMode = SessionParams.RenderingMode.SHRINK,
        showSystemUi = false,
    )

    private fun keyboard(
        theme: KeyboardTheme = KeyboardTheme.LIGHT,
        layout: com.uyghur.keyboard.keyboard.Layout = Layouts.uyghur(ShiftState.OFF, false),
        slots: List<String> = listOf("", "", ""),
        shift: ShiftState = ShiftState.OFF,
        enter: String? = null,
    ) = KeyboardView(paparazzi.context).apply {
        haptics = false
        this.theme = theme
        setLayout(layout)
        shiftState = shift
        enterLabel = enter
        setSuggestions(slots)
    }

    @Test fun uyghurLight() = paparazzi.snapshot(keyboard(slots = listOf("ياخ", "ياخشى", "ياخشىمۇ")))

    @Test fun uyghurDark() = paparazzi.snapshot(keyboard(theme = KeyboardTheme.DARK, slots = listOf("يىل", "خىل", "قېتىمقى")))

    @Test fun uyghurShift() = paparazzi.snapshot(keyboard(layout = Layouts.uyghur(ShiftState.ON, false), shift = ShiftState.ON))

    @Test fun uyghurNumberRow() = paparazzi.snapshot(keyboard(layout = Layouts.uyghur(ShiftState.OFF, true), enter = "ئىزدە"))

    @Test fun englishLight() = paparazzi.snapshot(keyboard(
        layout = Layouts.english(ShiftState.LOCKED, false), shift = ShiftState.LOCKED, slots = listOf("teh", "the", "they"), enter = "Send"))

    @Test fun symbolsDark() = paparazzi.snapshot(keyboard(theme = KeyboardTheme.DARK, layout = Layouts.symbols(1, Language.UYGHUR)))

    @Test fun keyPreview() = paparazzi.snapshot(keyboard().apply { debugPress("ت", popup = false) })

    @Test fun longPressPopup() = paparazzi.snapshot(keyboard().apply { debugPress(".", popup = true) })

    @Test fun longPressTopRow() = paparazzi.snapshot(keyboard(theme = KeyboardTheme.DARK).apply { debugPress("ۋ", popup = true) })

    @Test fun dictating() = paparazzi.snapshot(keyboard().apply { dictation = KeyboardView.Dictation.LISTENING })
}
