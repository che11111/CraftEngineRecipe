package dev.che1s.craftenginerecipe.api;

import java.util.List;
import java.util.Objects;

/** 配方备注；CER 使用无材质的透明空气载体展示，不占用原料或产物槽。 */
public record ApiNote(LocalizedText name, List<LocalizedText> lore) {
    public ApiNote {
        lore = lore == null ? List.of() : List.copyOf(lore);
        if (lore.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("note lore lines must not be null");
        }
    }

    public ApiNote(LocalizedText name) {
        this(name, List.of());
    }

    public ApiNote(LocalizedText name, LocalizedText... lore) {
        this(name, List.of(lore));
    }

    public boolean isEmpty() {
        return (this.name == null || this.name.isEmpty())
                && this.lore.stream().allMatch(LocalizedText::isEmpty);
    }
}
