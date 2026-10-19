package dev.leon.cer.i18n;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The default UI palette; applying it never changes arbitrary HEX colors or message text. */
final class DefaultPalette {
    private static final Map<String, String> COLORS = Map.ofEntries(
            Map.entry("black", "#292D35"), Map.entry("dark_blue", "#3F5F91"),
            Map.entry("dark_green", "#437B5A"), Map.entry("dark_aqua", "#287F82"),
            Map.entry("dark_red", "#A64F5A"), Map.entry("dark_purple", "#755481"),
            Map.entry("gold", "#D8B278"), Map.entry("gray", "#B8C0CC"), Map.entry("grey", "#B8C0CC"),
            Map.entry("dark_gray", "#8793A5"), Map.entry("dark_grey", "#8793A5"),
            Map.entry("blue", "#819ED8"), Map.entry("green", "#98C69A"), Map.entry("aqua", "#79CFC9"),
            Map.entry("red", "#E58B8B"), Map.entry("light_purple", "#C7A2D8"),
            Map.entry("yellow", "#E4D397"), Map.entry("white", "#EEF2F6"));
    private static final Pattern NAMED_COLOR = Pattern.compile("<(/?)(" + String.join("|", COLORS.keySet()) + ")>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_PREFIX = Pattern.compile("^(?:<[^>]+>)*<image:[^>]+>(?:<shift:[^>]+>)*",
            Pattern.CASE_INSENSITIVE);

    private DefaultPalette() {}

    static String apply(String text) {
        int imageEnd = imagePrefixEnd(text);
        Matcher matcher = NAMED_COLOR.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(2).toLowerCase(Locale.ROOT);
            String color = name.equals("white") && matcher.start() < imageEnd ? "#FFFFFF" : COLORS.get(name);
            matcher.appendReplacement(result, Matcher.quoteReplacement("<" + matcher.group(1) + color + ">"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static int imagePrefixEnd(String text) {
        Matcher matcher = IMAGE_PREFIX.matcher(text);
        return matcher.find() ? matcher.end() : 0;
    }
}
