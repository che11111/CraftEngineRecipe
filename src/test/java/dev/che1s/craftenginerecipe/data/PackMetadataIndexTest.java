package dev.che1s.craftenginerecipe.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackMetadataIndexTest {
    @TempDir Path directory;

    @Test
    void namespaceAuthorMetadataSupportsCaseAndMultiAuthorFields() throws Exception {
        Files.createDirectories(this.directory.resolve("farmersdelight"));
        Files.writeString(this.directory.resolve("farmersdelight/pack.yml"), """
                namespace: farmersdelight
                author: HuiDu_OwO
                """);
        Files.createDirectories(this.directory.resolve("ultramarine"));
        Files.writeString(this.directory.resolve("ultramarine/pack.yml"), """
                namespace: ultramarine
                author: 未雨屏,Xun,CraftEngine各位群友
                """);

        var metadata = PackMetadataIndex.scan(this.directory);

        assertTrue(PackMetadataIndex.matches(
                metadata.get("farmersdelight"), List.of("huidu_owo")));
        assertTrue(PackMetadataIndex.matches(
                metadata.get("ultramarine"), List.of("missing", "Xun")));
        assertFalse(PackMetadataIndex.matches(
                metadata.get("ultramarine"), List.of("someone_else")));
        assertTrue(PackMetadataIndex.matches(Set.of(), List.of()));
    }

    @Test
    void disabledPacksAreIgnored() throws Exception {
        Files.createDirectories(this.directory.resolve("disabled"));
        Files.writeString(this.directory.resolve("disabled/pack.yml"), """
                namespace: example
                author: example_author
                enable: false
                """);

        assertFalse(PackMetadataIndex.scan(this.directory).containsKey("example"));
    }
}
