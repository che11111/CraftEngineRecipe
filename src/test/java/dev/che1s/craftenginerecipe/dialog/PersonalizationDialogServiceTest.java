package dev.che1s.craftenginerecipe.dialog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonalizationDialogServiceTest {
    @Test
    void menuPackLabelsNeverExposeMiniMessageTagsInDialogControls() {
        assertEquals("CER 可爱版界面",
                PersonalizationDialogService.plainMenuName("<pink>CER 可爱版界面", "cer_menu_cute"));
        assertEquals("CER 粗版界面",
                PersonalizationDialogService.plainMenuName("<gray>CER 粗版界面", "cer_menu_rough"));
        assertEquals("cer_menu_unknown",
                PersonalizationDialogService.plainMenuName("<invalid>", "cer_menu_unknown"));
    }

    @Test
    void parsesTheConfiguredLandingPage() {
        assertEquals(dev.che1s.craftenginerecipe.preferences.PlayerPreferences.LandingPage.NAMESPACES,
                PersonalizationDialogService.landingPage("namespaces"));
        assertEquals(dev.che1s.craftenginerecipe.preferences.PlayerPreferences.LandingPage.WORKSTATIONS,
                PersonalizationDialogService.landingPage("workstations"));
        assertNull(PersonalizationDialogService.landingPage("invalid"));
    }

    @Test
    void acceptsAllNinePlayerLanguageOptions() {
        assertEquals("zh_CN", PersonalizationDialogService.language("zh_CN"));
        assertEquals("en_US", PersonalizationDialogService.language("en_US"));
        assertEquals("zh_TW", PersonalizationDialogService.language("zh-TW"));
        assertEquals("ja_JP", PersonalizationDialogService.language("ja_JP"));
        assertEquals("ko_KR", PersonalizationDialogService.language("ko_KR"));
        assertEquals("de_DE", PersonalizationDialogService.language("de_DE"));
        assertEquals("fr_FR", PersonalizationDialogService.language("fr_FR"));
        assertEquals("es_ES", PersonalizationDialogService.language("es_ES"));
        assertEquals("ru_RU", PersonalizationDialogService.language("ru_RU"));
        assertNull(PersonalizationDialogService.language("pt_BR"));
        assertNull(PersonalizationDialogService.language(null));
    }
}
