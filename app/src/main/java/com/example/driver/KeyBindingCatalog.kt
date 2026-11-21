package com.example.driver

import android.view.KeyEvent
import com.example.koda.engine.KodaEngineClient

data class SelectableKeyEntry(
    val label: String,
    val keyCode: Int,
    val category: String
)

data class ParsedBindingAction(
    val displayLabel: String,
    val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN,
    val metaState: Int = 0,
    val isDisabled: Boolean = false,
    val isRightClick: Boolean = false,
    val isMiddleClick: Boolean = false,
    val isEraserToggle: Boolean = false
)

/**
 * Complete catalog & real Android KeyEvent dispatcher for Pen Barrel Buttons and Tablet Express Keys.
 * Supports custom modifier combos (Ctrl + Shift + Alt + Super), virtual keyboard selection,
 * physical keyboard capture (`onPreviewKeyEvent`), and pointer actions.
 */
object KeyBindingCatalog {

    val quickPresets: List<String> = listOf(
        "Ctrl + Z (Desfazer)",
        "Ctrl + Y (Refazer)",
        "Ctrl + Shift + Z (Refazer)",
        "B (Pincel)",
        "E (Borracha)",
        "Espaço (Pan / Mover Tela)",
        "Alt (Conta-gotas)",
        "[ (Diminuir Pincel)",
        "] (Aumentar Pincel)",
        "Ctrl + + (Zoom +)",
        "Ctrl + - (Zoom -)",
        "Ctrl + S (Salvar)",
        "Tab (Ocultar Painéis)",
        "Delete (Limpar Camada)",
        "Botão Direito / Apagar",
        "Botão do Meio (Scroll/Pan)",
        "Borracha / Pan",
        "Desativado"
    )

    val selectableKeys: List<SelectableKeyEntry> = buildList {
        for (c in 'A'..'Z') {
            add(SelectableKeyEntry(c.toString(), KeyEvent.KEYCODE_A + (c - 'A'), "Letras"))
        }
        for (n in 0..9) {
            add(SelectableKeyEntry(n.toString(), KeyEvent.KEYCODE_0 + n, "Números"))
        }
        for (f in 1..12) {
            add(SelectableKeyEntry("F$f", KeyEvent.KEYCODE_F1 + (f - 1), "Função"))
        }
        addAll(
            listOf(
                SelectableKeyEntry("Espaço", KeyEvent.KEYCODE_SPACE, "Especiais"),
                SelectableKeyEntry("Enter", KeyEvent.KEYCODE_ENTER, "Especiais"),
                SelectableKeyEntry("Esc", KeyEvent.KEYCODE_ESCAPE, "Especiais"),
                SelectableKeyEntry("Tab", KeyEvent.KEYCODE_TAB, "Especiais"),
                SelectableKeyEntry("Backspace", KeyEvent.KEYCODE_DEL, "Especiais"),
                SelectableKeyEntry("Delete", KeyEvent.KEYCODE_FORWARD_DEL, "Especiais"),
                SelectableKeyEntry("[", KeyEvent.KEYCODE_LEFT_BRACKET, "Símbolos"),
                SelectableKeyEntry("]", KeyEvent.KEYCODE_RIGHT_BRACKET, "Símbolos"),
                SelectableKeyEntry("+", KeyEvent.KEYCODE_EQUALS, "Símbolos"),
                SelectableKeyEntry("-", KeyEvent.KEYCODE_MINUS, "Símbolos"),
                SelectableKeyEntry("/", KeyEvent.KEYCODE_SLASH, "Símbolos"),
                SelectableKeyEntry("↑", KeyEvent.KEYCODE_DPAD_UP, "Setas"),
                SelectableKeyEntry("↓", KeyEvent.KEYCODE_DPAD_DOWN, "Setas"),
                SelectableKeyEntry("←", KeyEvent.KEYCODE_DPAD_LEFT, "Setas"),
                SelectableKeyEntry("→", KeyEvent.KEYCODE_DPAD_RIGHT, "Setas"),
                SelectableKeyEntry("PgUp", KeyEvent.KEYCODE_PAGE_UP, "Navegação"),
                SelectableKeyEntry("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, "Navegação")
            )
        )
    }

    fun formatCustomShortcut(
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
        meta: Boolean,
        keyLabel: String
    ): String {
        val parts = ArrayList<String>(5)
        if (ctrl) parts.add("Ctrl")
        if (shift) parts.add("Shift")
        if (alt) parts.add("Alt")
        if (meta) parts.add("Super")
        parts.add(keyLabel)
        return parts.joinToString(" + ")
    }

    fun formatFromKeyEvent(keyCode: Int, isCtrl: Boolean, isShift: Boolean, isAlt: Boolean, isMeta: Boolean): String? {
        val entry = selectableKeys.firstOrNull { it.keyCode == keyCode }
        val keyName = entry?.label ?: KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .takeIf { it.isNotBlank() && it != "UNKNOWN" } ?: return null
        return formatCustomShortcut(isCtrl, isShift, isAlt, isMeta, keyName)
    }

    fun parseAction(rawAction: String): ParsedBindingAction {
        val trimmed = rawAction.trim()
        if (trimmed.isEmpty() || trimmed.equals("Desativado", ignoreCase = true) || trimmed.equals("Sem ação", ignoreCase = true)) {
            return ParsedBindingAction(displayLabel = trimmed, isDisabled = true)
        }
        if (trimmed.contains("Botão Direito", ignoreCase = true)) {
            return ParsedBindingAction(displayLabel = trimmed, isRightClick = true)
        }
        if (trimmed.contains("Botão do Meio", ignoreCase = true)) {
            return ParsedBindingAction(displayLabel = trimmed, isMiddleClick = true)
        }
        if (trimmed.startsWith("Borracha / Pan", ignoreCase = true)) {
            return ParsedBindingAction(displayLabel = trimmed, isEraserToggle = true)
        }

        // Strip trailing description like " (Desfazer)"
        val cleanCombo = trimmed.substringBefore(" (").trim()
        val tokens = cleanCombo.split("+").map { it.trim() }.filter { it.isNotEmpty() }

        var meta = 0
        var keyCode = KeyEvent.KEYCODE_UNKNOWN
        for (tok in tokens) {
            when {
                tok.equals("Ctrl", ignoreCase = true) || tok.equals("Control", ignoreCase = true) ->
                    meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                tok.equals("Shift", ignoreCase = true) ->
                    meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                tok.equals("Alt", ignoreCase = true) -> {
                    meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                    if (tokens.size == 1) keyCode = KeyEvent.KEYCODE_ALT_LEFT
                }
                tok.equals("Super", ignoreCase = true) || tok.equals("Win", ignoreCase = true) || tok.equals("Meta", ignoreCase = true) ->
                    meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                else -> {
                    val matched = selectableKeys.firstOrNull { it.label.equals(tok, ignoreCase = true) }
                    keyCode = matched?.keyCode ?: when (tok) {
                        "+" -> KeyEvent.KEYCODE_EQUALS
                        "-" -> KeyEvent.KEYCODE_MINUS
                        else -> KeyEvent.keyCodeFromString("KEYCODE_${tok.uppercase()}")
                    }
                }
            }
        }
        return ParsedBindingAction(
            displayLabel = trimmed,
            keyCode = keyCode,
            metaState = meta
        )
    }

    fun dispatchBindingPress(
        engineClient: KodaEngineClient,
        deviceId: Int,
        actionString: String,
        isDown: Boolean
    ): Boolean {
        val parsed = parseAction(actionString)
        if (parsed.isDisabled || parsed.keyCode == KeyEvent.KEYCODE_UNKNOWN) return false
        val keyAction = if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP

        if (isDown) {
            if ((parsed.metaState and KeyEvent.META_CTRL_ON) != 0) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, parsed.metaState)
            }
            if ((parsed.metaState and KeyEvent.META_SHIFT_ON) != 0) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, parsed.metaState)
            }
            if ((parsed.metaState and KeyEvent.META_ALT_ON) != 0 && parsed.keyCode != KeyEvent.KEYCODE_ALT_LEFT) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ALT_LEFT, parsed.metaState)
            }
            return engineClient.sendKeyEvent(deviceId, keyAction, parsed.keyCode, parsed.metaState)
        } else {
            val res = engineClient.sendKeyEvent(deviceId, keyAction, parsed.keyCode, parsed.metaState)
            if ((parsed.metaState and KeyEvent.META_ALT_ON) != 0 && parsed.keyCode != KeyEvent.KEYCODE_ALT_LEFT) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ALT_LEFT, 0)
            }
            if ((parsed.metaState and KeyEvent.META_SHIFT_ON) != 0) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
            }
            if ((parsed.metaState and KeyEvent.META_CTRL_ON) != 0) {
                engineClient.sendKeyEvent(deviceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)
            }
            return res
        }
    }
}
