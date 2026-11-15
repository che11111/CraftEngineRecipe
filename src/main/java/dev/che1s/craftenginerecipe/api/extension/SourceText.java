package dev.che1s.craftenginerecipe.api.extension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Localized text supplied by the core to a source extension.
 *
 * <p>Arguments are passed as alternating key/value pairs. This keeps the extension independent
 * from the core language implementation and lets the core own editable language files.</p>
 */
public interface SourceText {
    String text(String key, String fallback, Object... arguments);

    /** Whether the active integration language contains this key. */
    default boolean has(String key) {
        return false;
    }

    /** Defers localization until a player opens the generated recipe content. */
    default String deferred(String key, String fallback, Object... arguments) {
        return text(key, fallback, arguments);
    }

    /** Formats fallback text without requiring a core language file. */
    static SourceText fallbacks() {
        return new FallbackText();
    }

    final class FallbackText implements SourceText {
        private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");

        private FallbackText() {
        }

        @Override
        public String text(String key, String fallback, Object... arguments) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index + 1 < arguments.length; index += 2) {
                values.put(String.valueOf(arguments[index]), arguments[index + 1] == null
                        ? "" : String.valueOf(arguments[index + 1]));
            }
            Matcher matcher = ARGUMENT.matcher(fallback == null ? "" : fallback);
            StringBuilder result = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(
                        values.getOrDefault(matcher.group(1), matcher.group())));
            }
            matcher.appendTail(result);
            return result.toString();
        }

        @Override
        public boolean has(String key) {
            return false;
        }
    }
}
