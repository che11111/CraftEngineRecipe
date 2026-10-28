package dev.che1s.craftenginerecipe.api;

import org.bukkit.plugin.Plugin;

import java.util.Collection;

/**
 * CER 提供给其他 Bukkit 插件的配方展示 API。
 *
 * <p>通过 {@code Bukkit.getServicesManager().load(CerRecipeApi.class)} 获取。
 * 每个来源插件使用稳定命名空间发布完整配方集合；再次注册同一命名空间会替换旧集合。
 * 来源插件禁用或注册句柄注销后，其配方会自动从索引中移除。</p>
 */
public interface CerRecipeApi {
    /** ABI/语义版本；来源插件注册前可据此拒绝不兼容的 CER 版本。 */
    int API_VERSION = 1;

    /**
     * 发布或完整替换一个来源的配方集合。可在任意线程调用，索引刷新会在全局调度器完成。
     *
     * @param owner 来源插件，用于禁用时自动清理
     * @param namespace 来源稳定命名空间，例如 {@code my_plugin}
     * @param recipes 完整的展示配方集合
     */
    Registration register(Plugin owner, String namespace, Collection<ApiRecipe> recipes);

    /** 某来源当前有效的一次注册。 */
    interface Registration extends AutoCloseable {
        /**
         * 完整替换此句柄所属来源的配方集合。
         *
         * <p>同一个来源重新 {@link CerRecipeApi#register} 后，旧句柄会失效，不能覆盖新注册。</p>
         */
        void replace(Collection<ApiRecipe> recipes);

        /** 注销此句柄所属来源；重复调用不会产生额外影响。 */
        void unregister();

        @Override
        default void close() {
            this.unregister();
        }
    }
}
