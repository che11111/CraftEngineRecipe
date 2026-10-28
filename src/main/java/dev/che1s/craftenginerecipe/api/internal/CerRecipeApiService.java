package dev.che1s.craftenginerecipe.api.internal;

import dev.che1s.craftenginerecipe.CraftEngineRecipePlugin;
import dev.che1s.craftenginerecipe.api.ApiRecipe;
import dev.che1s.craftenginerecipe.api.CerRecipeApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Bukkit ServicesManager 中发布的有状态 API 实现。 */
public final class CerRecipeApiService implements CerRecipeApi {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private final Consumer<Runnable> refresh;
    private final Consumer<ApiNotice> notifications;
    // 每次注册生成新 token；旧 Registration 句柄即使泄漏也不能注销/替换新数据。
    private final AtomicLong tokens = new AtomicLong();
    // LinkedHashMap 同时表达来源注册顺序，编译快照时保持该顺序。
    private final Map<String, Provider> providers = new LinkedHashMap<>();
    private boolean stopped;

    public CerRecipeApiService(CraftEngineRecipePlugin plugin) {
        this.refresh = completion -> {
            if (plugin.isEnabled() && plugin.externalRecipes() != null && plugin.tasks() != null) {
                // 外部插件可能从异步线程调用；真正编译和发布快照必须回到全局调度器。
                plugin.tasks().global(() -> {
                    plugin.externalRecipes().reloadApi();
                    completion.run();
                });
            }
        };
        this.notifications = notice -> {
            if (!plugin.isEnabled() || plugin.tasks() == null) return;
            plugin.tasks().global(() -> notifyAdmins(plugin, notice));
        };
    }

    CerRecipeApiService(Runnable refresh) {
        this(refresh, ignored -> {});
    }

    CerRecipeApiService(Runnable refresh, Consumer<ApiNotice> notifications) {
        this.refresh = completion -> {
            refresh.run();
            completion.run();
        };
        this.notifications = notifications;
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
        int previousCount = previous == null ? -1 : previous.recipes().size();
        this.requestRefresh(() -> {
            if (previousCount < 0) {
                this.notify(new ApiNotice(NoticeKind.REGISTERED, owner.getName(), namespace, 0, copy.size()));
            } else if (previousCount != copy.size()) {
                this.notify(new ApiNotice(NoticeKind.UPDATED, owner.getName(), namespace,
                        previousCount, copy.size()));
            }
        });
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
        List<Provider> removed = List.of();
        synchronized (this) {
            removed = this.providers.values().stream().filter(provider -> provider.owner() == owner).toList();
            if (!removed.isEmpty()) {
                this.providers.values().removeIf(provider -> provider.owner() == owner);
            }
        }
        if (!removed.isEmpty()) {
            List<Provider> unregistered = removed;
            this.requestRefresh(() -> unregistered.forEach(provider ->
                    this.notify(new ApiNotice(NoticeKind.UNREGISTERED, owner.getName(), provider.namespace(),
                            provider.recipes().size(), 0))));
        }
    }

    public synchronized void shutdown() {
        this.stopped = true;
        this.providers.clear();
    }

    private void replace(String namespace, long token, Collection<ApiRecipe> recipes) {
        int previousCount;
        int nextCount;
        synchronized (this) {
            this.requireRunning();
            Provider provider = this.providers.get(namespace);
            if (provider == null || provider.token() != token) {
                throw new IllegalStateException("API registration is no longer active");
            }
            previousCount = provider.recipes().size();
            this.providers.put(namespace,
                    new Provider(provider.owner(), namespace, validate(recipes), token));
            nextCount = this.providers.get(namespace).recipes().size();
        }
        this.requestRefresh(() -> {
            if (previousCount != nextCount) {
                String pluginName;
                synchronized (this) {
                    Provider provider = this.providers.get(namespace);
                    pluginName = provider == null ? namespace : provider.owner().getName();
                }
                this.notify(new ApiNotice(NoticeKind.UPDATED, pluginName, namespace, previousCount, nextCount));
            }
        });
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

    private void requestRefresh(Runnable completion) {
        // 注册状态先同步写入，快照刷新异步排入全局线程；读者只会在完整索引发布后看到变化。
        if (!this.stopped) this.refresh.accept(completion);
    }

    private void notify(ApiNotice notice) {
        if (!this.stopped) this.notifications.accept(notice);
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
        Provider removed = null;
        synchronized (this) {
            Provider provider = this.providers.get(namespace);
            if (provider != null && provider.owner() == owner && provider.token() == token) {
                removed = this.providers.remove(namespace);
            }
        }
        if (removed != null) {
            Provider unregistered = removed;
            this.requestRefresh(() -> this.notify(new ApiNotice(NoticeKind.UNREGISTERED, owner.getName(),
                    namespace, unregistered.recipes().size(), 0)));
        }
    }

    private static void notifyAdmins(CraftEngineRecipePlugin plugin, ApiNotice notice) {
        if (!plugin.getConfig().getBoolean("indexing.notify-admins-on-api-register", true)) return;
        for (var player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("craftenginerecipe.admin")) continue;
            plugin.tasks().player(player, () -> {
                if (!player.isOnline()) return;
                switch (notice.kind()) {
                    case REGISTERED -> {
                        player.sendMessage(plugin.lang().message(
                                "messages.api-integration-detected", "plugin", notice.plugin()));
                        player.sendMessage(plugin.lang().component(
                                "messages.api-integration-namespace", "namespace", notice.namespace()));
                        player.sendMessage(plugin.lang().component(
                                "messages.api-integration-ready", "recipes", String.valueOf(notice.current())));
                    }
                    case UPDATED -> player.sendMessage(plugin.lang().message(
                            "messages.api-integration-updated",
                            "plugin", notice.plugin(),
                            "namespace", notice.namespace(),
                            "previous", String.valueOf(notice.previous()),
                            "current", String.valueOf(notice.current())));
                    case UNREGISTERED -> player.sendMessage(plugin.lang().message(
                            "messages.api-integration-unregistered",
                            "plugin", notice.plugin(),
                            "namespace", notice.namespace(),
                            "recipes", String.valueOf(notice.previous())));
                }
            });
        }
    }

    enum NoticeKind { REGISTERED, UPDATED, UNREGISTERED }

    record ApiNotice(NoticeKind kind, String plugin, String namespace, int previous, int current) {}
}
