package dev.che1s.craftenginerecipe.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationLanguageManagerTest {
    @TempDir Path directory;

    @Test
    void integrationExportsFollowConfiguredLanguageAndAllowLocalOverrides() throws Exception {
        Map<String, String> extensionLanguage = Map.of(
                "lang/integrations/customcrops/en_US.yml",
                "title:\n  station: \"CustomCrops · {pot}\"\n",
                "lang/integrations/cultivation/en_US.yml",
                "station: \"Vanilla · {name}\"\n",
                "lang/integrations/cultivation/zh_CN.yml",
                "station: 原版·耕地\n");
        LanguageManager language = new LanguageManager(this.directory,
                resource -> {
                    String bundled = extensionLanguage.get(resource);
                    return bundled == null
                            ? getClass().getClassLoader().getResourceAsStream(resource)
                            : new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8));
                },
                Logger.getAnonymousLogger());

        language.load("en_US");
        assertEquals("CustomCrops · Cabbage Pot",
                language.integrationText("customcrops", "title.station", "pot", "Cabbage Pot"));

        Path override = this.directory.resolve("lang/integrations/customcrops/en_US.yml");
        Files.writeString(override, "title:\n  station: \"Custom · {pot}\"\n");
        language.load("en_US");

        assertEquals("Custom · Cabbage Pot",
                language.integrationText("customcrops", "title.station", "pot", "Cabbage Pot"));
        language.load("zh_CN");
        assertEquals("原版·耕地", language.integrationText("cultivation", "station"));
    }

    @Test
    void thirdPartyLanguageSourcesCanBeRegisteredDynamically() {
        Map<String, String> resources = Map.of(
                "lang/integrations/my_source/en_US.yml", "station: Private Station\n");
        LanguageManager language = new LanguageManager(this.directory,
                resource -> {
                    String bundled = resources.get(resource);
                    return bundled == null ? getClass().getClassLoader().getResourceAsStream(resource)
                            : new ByteArrayInputStream(bundled.getBytes(StandardCharsets.UTF_8));
                },
                Logger.getAnonymousLogger());

        language.setIntegrationSources(List.of("my_source"));
        language.load("en_US");

        assertEquals("Private Station", language.integrationText("my_source", "station"));
    }
}
