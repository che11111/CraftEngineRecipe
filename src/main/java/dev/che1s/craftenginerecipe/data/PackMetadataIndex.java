package dev.che1s.craftenginerecipe.data;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 读取 CraftEngine 资源包 pack.yml 中的 namespace 与 author 元数据。 */
public final class PackMetadataIndex {
    private PackMetadataIndex() {
    }

    public static Path resourcesDirectory(Path pluginDataFolder) {
        Path plugins = pluginDataFolder.toAbsolutePath().getParent();
        return plugins == null ? pluginDataFolder.resolveSibling("CraftEngine/resources")
                : plugins.resolve("CraftEngine/resources");
    }

    public static Map<String, Set<String>> scan(Path resourcesDirectory) {
        if (resourcesDirectory == null || !Files.isDirectory(resourcesDirectory)) return Map.of();
        Map<String, Set<String>> authors = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(resourcesDirectory)) {
            files.filter(path -> path.getFileName().toString().equals("pack.yml"))
                    .forEach(path -> read(path, authors));
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
        LinkedHashMap<String, Set<String>> immutable = new LinkedHashMap<>();
        authors.forEach((namespace, values) ->
                immutable.put(namespace, Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(immutable);
    }

    public static boolean matches(Collection<String> packAuthors, Collection<String> requiredAuthors) {
        if (requiredAuthors == null || requiredAuthors.isEmpty()) return true;
        if (packAuthors == null || packAuthors.isEmpty()) return false;
        Set<String> actual = new LinkedHashSet<>();
        for (String author : packAuthors) {
            for (String candidate : splitAuthors(author)) actual.add(normalize(candidate));
        }
        for (String required : requiredAuthors) {
            String normalized = normalize(required);
            if (!normalized.isEmpty() && actual.contains(normalized)) return true;
        }
        return false;
    }

    public static List<String> configuredAuthors(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        List<String> authors = new ArrayList<>();
        for (Object item : values) {
            if (item == null || item.toString().isBlank()) continue;
            String author = item.toString().trim();
            if (!authors.contains(author)) authors.add(author);
        }
        return List.copyOf(authors);
    }

    static Set<String> splitAuthors(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> authors = new LinkedHashSet<>();
        for (String part : value.split("[,，;；/&、]")) {
            String author = part.trim();
            if (!author.isEmpty()) authors.add(author);
        }
        return Collections.unmodifiableSet(authors);
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static void read(Path path, Map<String, Set<String>> target) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(path.toFile());
        } catch (IOException | InvalidConfigurationException error) {
            return;
        }
        if (!yaml.getBoolean("enable", true)) return;
        String namespace = yaml.getString("namespace", "").trim();
        if (namespace.isEmpty()) return;
        Set<String> authors = splitAuthors(yaml.getString("author", ""));
        if (!authors.isEmpty()) {
            target.computeIfAbsent(namespace, ignored -> new LinkedHashSet<>()).addAll(authors);
        }
    }
}
