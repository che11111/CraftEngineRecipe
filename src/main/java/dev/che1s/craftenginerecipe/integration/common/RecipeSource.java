package dev.che1s.craftenginerecipe.integration.common;

import dev.che1s.craftenginerecipe.api.DisplayRecipe;
import java.util.List;

/** 仅在来源发布完整数据版本后，于全局调度器读取。 */
public interface RecipeSource {
    String id();
    boolean ready();
    long revision();
    List<DisplayRecipe> capture();
}
