package dev.che1s.craftenginerecipe.i18n;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为单个配方来源提供本地化文本，并允许调用处携带稳定的默认文案。
 *
 * <p>模板使用具名占位符，例如 {@code "数量：{amount}"}；调用参数按
 * {@code "amount", value} 成对传入。这样语言文件调整语序时无需改 Java 参数顺序。</p>
 */
public final class IntegrationText {
    private static final Pattern ARGUMENT = Pattern.compile("\\{([a-zA-Z0-9_-]+)}");
    private static final IntegrationText EMPTY = new IntegrationText(key -> null);
    private final Function<String, String> lookup;

    public IntegrationText(Function<String, String> lookup) {
        this.lookup = lookup == null ? key -> null : lookup;
    }

    public static IntegrationText empty() {
        return EMPTY;
    }

    public static IntegrationText of(String source, LanguageManager language) {
        return new IntegrationText(key -> language.integrationText(source, key));
    }

    public String text(String key, String fallback, Object... arguments) {
        String value = this.lookup.apply(key);
        return format(value == null || value.isBlank() ? fallback : value, arguments);
    }

    static String format(String template, Object... arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        // 参数按 key/value 成对读取；末尾落单的参数会被忽略。
        for (int index = 0; index + 1 < arguments.length; index += 2) {
            values.put(String.valueOf(arguments[index]), arguments[index + 1] == null
                    ? "" : String.valueOf(arguments[index + 1]));
        }
        Matcher matcher = ARGUMENT.matcher(template == null ? "" : template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            // 未注册的占位符原样保留，便于发现语言文件与调用处不一致。
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    values.getOrDefault(matcher.group(1), matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
