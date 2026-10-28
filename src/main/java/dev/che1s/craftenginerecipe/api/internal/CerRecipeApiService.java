package dev.che1s.craftenginerecipe.api.internal;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.ApiRecipe;
import dev.che1s.craftenginerecipe.api.CerRecipeApi;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Bukkit ServicesManager 中发布的有状态 API 实现。 */
public final class CerRecipeApiService implements CerRecipeApi {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private final Runnable refresh;
    // 每次注册生成新 token；旧 Registration 句柄即使泄漏也不能注销/替换新数据。
    private final AtomicLong tokens = new AtomicLong();
    // LinkedHashMap 同时表达来源注册顺序，编译快照时保持该顺序。
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private boolean stopped;

    public CerRecipeApiService(CraftEngineRecipePlugin plugin) {
        this.refresh = () -> {
            if (plugin.isEnabled() && plugin.externalRecipes() != null && plugin.tasks() != null) {
                // 外部插件可能从异步线程调用；真正编译和发布快照必须回到全局调度器。
                plugin.tasks().global(plugin.externalRecipes()::reloadApi);
            }
        };
    }

    CerRecipeApiService(Runnable refresh) {
        this.refresh = refresh;
    }

    @Override
    public synchronized Registration register(Plugin owner, String namespace, Collection<ApiRecipe> recipes) {
        this.requireRunning();
        if (owner == null) throw new IllegalArgumentException("owner plugin must not be null");
        if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "provider namespace must contain only lowercase letters, digits, _, . or -");
        }
        // 一个命名空间只能由一个插件持有，避免两个来源伪造/覆盖彼此配方。
        Provider previous = this.providers.get(namespace);
        if (previous != null && previous.owner() != owner) {
            throw new IllegalStateException("provider namespace '" + namespace + "' is already owned by "
                    + previous.owner().getName());
        }
        List<ApiRecipe> copy = validate(recipes);
        long token = this.tokens.incrementAndGet();
        this.providers.put(namespace, new Provider(owner, namespace, copy, token));
        this.requestRefresh();
        return new RegistrationImpl(owner, namespace, token);
    }

    public synchronized List<ApiRecipeCompiler.CompiledRecipe> compile(String locale) {
        List<ApiRecipeCompiler.CompiledRecipe> recipes = new ArrayList<>();
        for (Provider provider : this.providers.values()) {
            for (ApiRecipe recipe : provider.recipes()) {
                recipes.add(ApiRecipeCompiler.compile(provider.namespace(), recipe, locale));
            }
        }
        return List.copyOf(recipes);
    }

    /** 来源插件禁用时由 ExternalRecipeService 调用。 */
    public void removeOwner(Plugin owner) {
        boolean changed;
        synchronized (this) {
            int before = this.providers.size();
            this.providers.values().removeIf(provider -> provider.owner() == owner);
            changed = before != this.providers.size();
        }
        if (changed) this.requestRefresh();
    }

    public synchronized void shutdown() {
        this.stopped = true;
        this.providers.clear();
    }

    private void replace(String namespace, long token, Collection<ApiRecipe> recipes) {
        synchronized (this) {
            this.requireRunning();
            Provider provider = this.providers.get(namespace);
            if (provider == null || provider.token() != token) {
                throw new IllegalStateException("API registration is no longer active");
            }
            this.providers.put(namespace,
                    new Provider(provider.owner(), namespace, validate(recipes), token));
        }
        this.requestRefresh();
    }

    private static List<ApiRecipe> validate(Collection<ApiRecipe> recipes) {
        if (recipes == null) throw new IllegalArgumentException("recipes must not be null");
        List<ApiRecipe> copy = List.copyOf(recipes);
        // Recipe ID 在单个来源内必须稳定且唯一，否则搜索和问题定位会指向不确定条目。
        Set<String> ids = new LinkedHashSet<>();
        for (ApiRecipe recipe : copy) {
            if (recipe == null) throw new IllegalArgumentException("recipes must not contain null");
            if (!ids.add(recipe.id())) {
                throw new IllegalArgumentException("duplicate recipe id '" + recipe.id() + "'");
            }
        }
        return copy;
    }

    private void requestRefresh() {
        // 注册状态先同步写入，快照刷新异步排入全局线程；读者只会在完整索引发布后看到变化。
        if (!this.stopped) this.refresh.run();
    }

    private void requireRunning() {
        if (this.stopped) throw new IllegalStateException("CraftEngineRecipe API is stopped");
    }

    private record Provider(Plugin owner, String namespace, List<ApiRecipe> recipes, long token) {
    }

    private final class RegistrationImpl implements Registration {
        private final Plugin owner;
        private final String namespace;
        private final long token;

        private RegistrationImpl(Plugin owner, String namespace, long token) {
            this.owner = owner;
            this.namespace = namespace;
            this.token = token;
        }

        @Override
        public void replace(Collection<ApiRecipe> recipes) {
            // token 同时校验来源与注册代次，防止旧句柄操作当前数据。
            CerRecipeApiService.this.replace(this.namespace, this.token, recipes);
        }

        @Override
        public void unregister() {
            CerRecipeApiService.this.removeOwnerIfToken(this.owner, this.namespace, this.token);
        }
    }

    private void removeOwnerIfToken(Plugin owner, String namespace, long token) {
        boolean changed;
        synchronized (this) {
            Provider provider = this.providers.get(namespace);
            changed = provider != null && provider.owner() == owner && provider.token() == token
                    && this.providers.remove(namespace) != null;
        }
        if (changed) this.requestRefresh();
    }
}
