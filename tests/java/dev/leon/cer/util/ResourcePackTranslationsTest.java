package dev.leon.cer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePackTranslationsTest {
    @TempDir Path folder;

    @Test
    void preferredLanguageWinsAndFallbackFillsOnlyMissingKeys() throws Exception {
        Path pack = zip(Map.of(
                "assets/food/lang/zh_cn.json", "{\"item.food.pie\":\"馅饼\"}",
                "assets/food/lang/en_us.json", "{\"item.food.pie\":\"Pie\",\"item.food.soup\":\"Soup\"}"));
        var result = ResourcePackTranslations.read(pack, List.of("zh_cn", "en_us"));
        assertEquals(Map.of("item.food.pie", "馅饼", "item.food.soup", "Soup"), result.translations());
        assertEquals(2, result.files());
        assertTrue(result.problems().isEmpty());
        assertEquals("Pie", ResourcePackTranslations.read(pack, List.of("en_us", "zh_cn"))
                .translations().get("item.food.pie"));
    }

    @Test
    void minecraftWinsWithinOneLocaleAndOtherNamespacesMergeInLexicalOrder() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("assets/z_last/lang/zh_cn.json", "{\"shared\":\"last\",\"addon\":\"last\",\"z\":\"Z\"}");
        entries.put("assets/a_first/lang/zh_cn.json", "{\"shared\":\"first\",\"addon\":\"first\",\"a\":\"A\"}");
        entries.put("assets/minecraft/lang/zh_cn.json", "{\"shared\":\"vanilla\"}");
        entries.put("assets/minecraft/lang/en_us.json", "{\"addon\":\"English\"}");
        var result = ResourcePackTranslations.read(zip(entries), List.of("zh_cn", "en_us"));
        assertEquals(Map.of("shared", "vanilla", "addon", "first", "a", "A", "z", "Z"), result.translations());
        assertEquals(4, result.files());
    }

    @Test
    void malformedFilesAreIsolatedAndContributeNoPartialKeys() throws Exception {
        Path pack = zip(Map.of(
                "assets/bad/lang/zh_cn.json", "{\"partial\":\"should not survive\", broken}",
                "assets/food/lang/zh_cn.json", "{\"valid\":\"正常\"}",
                "assets/food/lang/en_us.json", "{\"partial\":\"Fallback\"}",
                "assets/not_object/lang/zh_cn.json", "[\"ignored\"]"));
        var result = ResourcePackTranslations.read(pack, null);
        assertEquals(Map.of("valid", "正常", "partial", "Fallback"), result.translations());
        assertEquals(2, result.files());
        assertEquals(2, result.problems().size());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.startsWith("assets/bad/lang/zh_cn.json:")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("language JSON must be an object")));
    }

    @Test
    void ignoresUnrelatedLocalesAndFilesAndFlattensNestedStrings() throws Exception {
        Path pack = zip(Map.of(
                "assets/food/lang/en_us.json", "{\"item\":{\"food\":{\"pie\":\"Pie\"}},\"flat.key\":\"Flat\","
                        + "\"number\":123,\"boolean\":true,\"empty\":null,\"array\":[\"ignore\",{}]}",
                "assets/food/lang/zh_cn.json", "invalid JSON should be ignored with en_us selected",
                "assets/food/lang/en_us.txt", "ignored",
                "assets/food/other/en_us.json", "ignored",
                "assets/food/lang/deeper/en_us.json", "ignored"));
        var result = ResourcePackTranslations.read(pack, List.of("en_us"));
        assertEquals(Map.of("item.food.pie", "Pie", "flat.key", "Flat"), result.translations());
        assertEquals(1, result.files());
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void rejectsOversizedAndDeepJsonWithoutDiscardingOtherFiles() throws Exception {
        String large = " ".repeat(8 * 1024 * 1024 + 1);
        String deep = "{\"nested\":".repeat(80) + "\"value\"" + "}".repeat(80);
        var result = ResourcePackTranslations.read(zip(Map.of(
                "assets/large/lang/en_us.json", large,
                "assets/deep/lang/en_us.json", deep,
                "assets/valid/lang/en_us.json", "{\"safe\":\"value\"}")), List.of("en_us"));
        assertEquals(Map.of("safe", "value"), result.translations());
        assertEquals(1, result.files());
        assertEquals(2, result.problems().size());
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("uncompressed file exceeds")));
        assertTrue(result.problems().stream().anyMatch(problem -> problem.contains("JSON nesting limit")));
    }

    @Test
    void fileLimitStillRetainsPreferredNamespacesRegardlessOfZipEntryOrder() throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < 1024; index++) {
            entries.put("assets/z" + index + "/lang/zh_cn.json", "{\"shared\":\"addon\"}");
        }
        entries.put("assets/minecraft/lang/zh_cn.json", "{\"shared\":\"vanilla\"}");
        var result = ResourcePackTranslations.read(zip(entries), List.of("zh_cn"));
        assertEquals("vanilla", result.translations().get("shared"));
        assertEquals(1024, result.files());
        assertEquals(1, result.problems().size());
        assertTrue(result.problems().getFirst().contains("Language file limit"));
    }

    @Test
    void invalidUtf8AndTrailingJsonAreSkipped() throws Exception {
        Path pack = Files.createTempFile(folder, "language-", ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(pack))) {
            entry(output, "assets/bad/lang/en_us.json", new byte[]{'{', '"', 'k', '"', ':', '"', (byte) 0xc3, '"', '}'});
            entry(output, "assets/trailing/lang/en_us.json", "{} {}".getBytes(StandardCharsets.UTF_8));
            entry(output, "assets/good/lang/en_us.json", "{}".getBytes(StandardCharsets.UTF_8));
        }
        var result = ResourcePackTranslations.read(pack, List.of("en_us"));
        assertEquals(1, result.files());
        assertEquals(2, result.problems().size());
        assertTrue(result.translations().isEmpty());
    }

    @Test
    void missingOrBrokenZipThrowsIoException() throws Exception {
        assertThrows(IOException.class, () -> ResourcePackTranslations.read(folder.resolve("missing.zip"), List.of()));
        Path broken = folder.resolve("broken.zip");
        Files.writeString(broken, "not a zip");
        assertThrows(IOException.class, () -> ResourcePackTranslations.read(broken, List.of()));
    }

    @Test
    void normalizesDeduplicatesAndRejectsUnsafeLocaleNames() {
        assertEquals(List.of("zh_cn", "en_us"), ResourcePackTranslations.normalizeLanguages(null));
        assertEquals(List.of("zh_cn", "en_us"), ResourcePackTranslations.normalizeLanguages(List.of()));
        assertEquals(List.of("zh_cn", "en_us"), ResourcePackTranslations.normalizeLanguages(List.of("../en_us", " ")));
        assertEquals(List.of("en_us", "zh_cn", "de_de"), ResourcePackTranslations.normalizeLanguages(
                Arrays.asList(" EN-US ", "en_us", null, "ZH_cn", "de-de", "../fr_fr", "es mx")));
        assertThrows(UnsupportedOperationException.class,
                () -> ResourcePackTranslations.normalizeLanguages(List.of("en_us")).add("zh_cn"));
    }

    @Test
    void resultIsImmutableAndDoesNotRetainMutableInputs() throws Exception {
        var result = ResourcePackTranslations.read(zip(Map.of("assets/food/lang/en_us.json", "{\"k\":\"v\"}")), List.of("en_us"));
        assertThrows(UnsupportedOperationException.class, () -> result.translations().put("new", "value"));
        assertThrows(UnsupportedOperationException.class, () -> result.problems().add("new"));
        Map<String, String> source = new LinkedHashMap<>(Map.of("k", "v"));
        var problems = new java.util.ArrayList<>(List.of("problem"));
        var constructed = new ResourcePackTranslations.ReadResult(source, 1, problems);
        source.clear();
        problems.clear();
        assertEquals(Map.of("k", "v"), constructed.translations());
        assertEquals(List.of("problem"), constructed.problems());
    }

    private Path zip(Map<String, String> entries) throws IOException {
        Path pack = Files.createTempFile(folder, "language-", ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(pack))) {
            for (var entry : entries.entrySet()) {
                entry(output, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return pack;
    }

    private void entry(ZipOutputStream output, String path, byte[] contents) throws IOException {
        output.putNextEntry(new ZipEntry(path));
        output.write(contents);
        output.closeEntry();
    }
}
