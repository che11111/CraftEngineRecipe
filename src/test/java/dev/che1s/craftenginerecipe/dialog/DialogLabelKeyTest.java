package dev.che1s.craftenginerecipe.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogLabelKeyTest {
    @Test
    void visibilityOptionLabelsUseTheActualLanguageKeys() {
        assertEquals("ui.button-editor.visibility-player",
                ButtonEditorDialogService.optionKey("visibility.player"));
        assertEquals("ui.unapplied-editor.visibility-permission",
                UnappliedCategoryDialogService.optionKey("visibility.permission"));
    }
}
