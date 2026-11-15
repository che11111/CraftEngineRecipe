package dev.che1s.craftenginerecipe.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguageSelectionTest {
    @TempDir Path directory;

    @Test
    void serverLocaleMapsAllSupportedLanguageFamilies() {
        assertEquals("zh_CN", LanguageManager.detectServerLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals("zh_TW", LanguageManager.detectServerLocale(Locale.TRADITIONAL_CHINESE));
        assertEquals("en_US", LanguageManager.detectServerLocale(Locale.US));
        assertEquals("fr_FR", LanguageManager.detectServerLocale(Locale.FRANCE));
        assertEquals("ja_JP", LanguageManager.detectServerLocale(Locale.JAPAN));
        assertEquals("ko_KR", LanguageManager.detectServerLocale(Locale.KOREA));
        assertEquals("de_DE", LanguageManager.detectServerLocale(Locale.GERMANY));
        assertEquals("es_ES", LanguageManager.detectServerLocale(new Locale("es", "ES")));
        assertEquals("ru_RU", LanguageManager.detectServerLocale(new Locale("ru", "RU")));
        assertEquals("zh_TW", LanguageManager.supportedLocale("zh-TW"));
        assertEquals("en_US", LanguageManager.supportedLocale("pt_BR"));
    }

    @Test
    void allLanguagesStayAvailableAndPlayerViewsSwitchWithoutReloadingFiles() {
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("zh_CN");

        assertEquals("zh_CN", language.locale());
        assertEquals("简体中文", language.text("ui.personalization.language-zh"));
        assertEquals("简体中文", language.view("en_US").text("ui.personalization.language-zh"));
        assertEquals("简体中文", language.view("zh_CN").text("ui.personalization.language-zh"));
        assertEquals("日本語", language.view("ja_JP").text("ui.personalization.language-ja"));
        assertEquals("fr_FR", language.view("fr_FR").locale());
    }

    @Test
    void unsupportedServerLanguageFallsBackToEnglish() {
        LanguageManager language = new LanguageManager(this.directory,
                resource -> getClass().getClassLoader().getResourceAsStream(resource),
                Logger.getAnonymousLogger());
        language.load("pt_BR");

        assertEquals("en_US", language.locale());
        assertEquals("Command not found", language.view("en_US").textOr(
                "messages.missing", "Command not found"));
    }
}
