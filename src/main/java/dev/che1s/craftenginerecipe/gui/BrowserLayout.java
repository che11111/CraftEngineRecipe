package dev.che1s.craftenginerecipe.gui;

import dev.che1s.craftenginerecipe.config.CerConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 菜单构建和分类栏刷新共用的纯布局规则。 */
final class BrowserLayout {
    static final int WIDTH = 9;
    static final int ROWS = 6;
    static final char EMPTY_CHAR = '#';

    private BrowserLayout() {
    }

    static String[] normalize(CerConfig config) {
        return normalize(config.layoutRows(), config.categoryIconChar(), config.contentAreaChar(), List.of(
                config.prevCategory().symbol(), config.nextCategory().symbol(),
                config.prevPage().symbol(), config.nextPage().symbol(),
                config.search().symbol(), config.back().symbol(),
                config.hand().symbol(), config.info().symbol(), config.workstations().symbol(),
                config.glasses().symbol(), config.query().symbol(), config.close().symbol()));
    }

    static String[] normalize(List<String> raw, char category, char content, List<Character> buttons) {
        Set<Character> known = new HashSet<>();
        known.add(category);
        known.add(content);
        for (char symbol : buttons) {
            if (symbol != '\u0000') known.add(symbol);
        }
        String[] out = new String[ROWS];
        for (int row = 0; row < ROWS; row++) {
            String line = row < raw.size() ? raw.get(row) : "";
            StringBuilder normalized = new StringBuilder(WIDTH);
            for (int i = 0; i < line.length() && normalized.length() < WIDTH; i++) {
                char symbol = line.charAt(i);
                if (symbol == ' ' || symbol == '\t') continue;
                normalized.append(known.contains(symbol) ? symbol : EMPTY_CHAR);
            }
            while (normalized.length() < WIDTH) normalized.append(EMPTY_CHAR);
            out[row] = normalized.toString();
        }
        return out;
    }

    static String[] workstations(CerConfig config) {
        // 合成器页面使用与 CE 浏览页面相同的分类槽位和导航按钮。
        return normalize(config);
    }

    static int categoryOffset(int requested, int entries, int slots) {
        if (slots <= 0) return 0;
        return Math.max(0, Math.min(requested, Math.max(0, entries - slots)));
    }

    static List<Integer> slotsOf(String[] structure, char symbol) {
        List<Integer> slots = new ArrayList<>();
        if (symbol == '\u0000') return slots;
        for (int row = 0; row < structure.length; row++) {
            String line = structure[row];
            for (int column = 0; column < line.length(); column++) {
                if (line.charAt(column) == symbol) slots.add(row * WIDTH + column);
            }
        }
        return slots;
    }

    static Binding binding(char symbol, char category, char content) {
        if (symbol == '\u0000') return Binding.DISABLED;
        if (symbol == EMPTY_CHAR) return Binding.RESERVED;
        if (symbol == category || symbol == content) return Binding.CONFLICT;
        return Binding.ALLOWED;
    }

    enum Binding { ALLOWED, DISABLED, RESERVED, CONFLICT }
}
