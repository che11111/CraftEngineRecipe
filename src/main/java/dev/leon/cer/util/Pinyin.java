package dev.leon.cer.util;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Pinyin {

    private static final HanyuPinyinOutputFormat FORMAT = new HanyuPinyinOutputFormat();
    private static final Map<Character, String> CHAR_CACHE;

    static {
        FORMAT.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        FORMAT.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        FORMAT.setVCharType(HanyuPinyinVCharType.WITH_V);
        CHAR_CACHE = new ConcurrentHashMap<>(8192);
    }

    private Pinyin() {
    }

    public static String full(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String py = single(c);
            if (py != null) {
                sb.append(py);
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String initials(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String py = single(c);
            if (py != null) {
                if (py.isEmpty()) continue;
                sb.append(py.charAt(0));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String perCharJoined(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String py = single(c);
            sb.append(' ').append(py != null ? py : String.valueOf(Character.toLowerCase(c)));
        }
        return sb.toString().trim();
    }

    private static String single(char c) {
        String cached = CHAR_CACHE.get(c);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String result = "";
        try {
            String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, FORMAT);
            if (arr != null && arr.length > 0 && arr[0] != null && !arr[0].isEmpty()) {
                result = arr[0].toLowerCase(Locale.ROOT);
            }
        } catch (Throwable ignored) {
        }
        CHAR_CACHE.put(c, result);
        return result.isEmpty() ? null : result;
    }
}
