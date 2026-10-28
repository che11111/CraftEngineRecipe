package dev.che1s.craftenginerecipe.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通过 CER API 发布的只读展示配方。
 *
 * <p>产物与工作站必须有；原料与备注可以省略。该方法只提供配方书展示，
 * 不会注册可执行合成逻辑。</p>
 */
public record ApiRecipe(String id, ApiItem workstation, List<ApiItem> ingredients,
                        List<ApiItem> results, ApiNote note) {
    private static final Pattern RECIPE_ID = Pattern.compile("[a-z0-9/._-]+");

    public ApiRecipe {
        if (id == null || !RECIPE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "recipe id must contain only lowercase letters, digits, /, _, . or -");
        }
        Objects.requireNonNull(workstation, "workstation");
        // 在注册入口完成防御性复制和必填校验，避免错误数据进入索引后才暴露。
        ingredients = ingredients == null ? List.of() : List.copyOf(ingredients);
        results = results == null ? List.of() : List.copyOf(results);
        if (ingredients.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ingredients must not contain null");
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("at least one result is required");
        }
        if (results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("results must not contain null");
        }
    }

    public ApiRecipe(String id, ApiItem workstation, List<ApiItem> ingredients, List<ApiItem> results) {
        this(id, workstation, ingredients, results, null);
    }
}
