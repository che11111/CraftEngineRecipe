package dev.che1s.craftenginerecipe.api;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 箱子布局。槽位从 0 开始；-1 表示禁用控件或使用自动内容布局。 */
public record RecipeLayout(int rows, String title, int workstationSlot, int informationSlot,
                           int previousSlot, int backSlot, int nextSlot, List<String> pattern, boolean nativeUi,
                           String page) {
    /** 标记包含序列化 Adventure 组件（图标、字体、翻译）的标题字符串。 */
    public static final String JSON_TITLE_PREFIX = "\u0000json:";

    public static String jsonTitle(Component component) {
        return component == null ? null : JSON_TITLE_PREFIX + GsonComponentSerializer.gson().serialize(component);
    }

    public RecipeLayout(int rows, String title, int workstationSlot, int informationSlot,
                        int previousSlot, int backSlot, int nextSlot) {
        this(rows, title, workstationSlot, informationSlot, previousSlot, backSlot, nextSlot, List.of(), false);
    }

    public RecipeLayout(int rows, String title, int workstationSlot, int informationSlot,
                        int previousSlot, int backSlot, int nextSlot, List<String> pattern, boolean nativeUi) {
        this(rows, title, workstationSlot, informationSlot, previousSlot, backSlot, nextSlot, pattern, nativeUi, null);
    }

    public RecipeLayout {
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("rows must be between 1 and 6");
        pattern = pattern == null ? List.of() : List.copyOf(pattern);
        page = page == null || page.isBlank() ? null : page.toLowerCase(java.util.Locale.ROOT);
        Set<Integer> occupied = new HashSet<>();
        int[] controls = { workstationSlot, informationSlot, previousSlot, backSlot, nextSlot };
        String[] labels = { "workstation slot", "information slot", "previous slot", "back slot", "next slot" };
        for (int i = 0; i < controls.length; i++) {
            RecipeValidation.slot(controls[i], rows * 9, labels[i]);
            if (controls[i] >= 0 && !occupied.add(controls[i])) {
                throw new IllegalArgumentException(labels[i] + " overlaps another layout control at " + controls[i]);
            }
        }
        if (nativeUi && !occupied.isEmpty()) {
            throw new IllegalArgumentException("native UI cannot contain CER controls");
        }
        if (!pattern.isEmpty()) {
            if (pattern.size() != rows) throw new IllegalArgumentException("pattern must match rows");
            for (String row : pattern) {
                if (row.length() != 9) throw new IllegalArgumentException("each pattern row must contain exactly 9 characters");
                for (char symbol : row.toCharArray()) {
                    if (Character.isISOControl(symbol) || Character.isWhitespace(symbol) && symbol != ' ') {
                        throw new IllegalArgumentException("pattern symbols must be printable");
                    }
                }
            }
        }
    }

    public static RecipeLayout defaults() {
        return new RecipeLayout(6, null, 4, 49, 45, 48, 53);
    }

    public static RecipeLayout nativeUi(String title) {
        return new RecipeLayout(6, title, -1, -1, -1, -1, -1, List.of(), true);
    }

    public static RecipeLayout page(String title, String page) {
        return new RecipeLayout(6, title, -1, -1, -1, -1, -1, List.of(), true, page);
    }

    public static RecipeLayout patterned(List<String> pattern, String title) {
        List<String> copy = List.copyOf(pattern);
        return new RecipeLayout(copy.size(), title, slotOf(copy, 'T'), slotOf(copy, 'I'),
                slotOf(copy, 'P'), slotOf(copy, 'R'), slotOf(copy, 'N'), copy, false);
    }

    public char symbolAt(int slot) {
        return this.pattern.isEmpty() ? ' ' : this.pattern.get(slot / 9).charAt(slot % 9);
    }

    private static int slotOf(List<String> pattern, char symbol) {
        for (int row = 0; row < pattern.size(); row++) {
            int column = pattern.get(row).indexOf(symbol);
            if (column >= 0) return row * 9 + column;
        }
        return -1;
    }
}
