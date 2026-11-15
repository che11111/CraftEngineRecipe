package dev.che1s.craftenginerecipe.api.internal;

import dev.che1s.craftenginerecipe.api.ApiItem;
import dev.che1s.craftenginerecipe.api.ApiRecipe;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CerRecipeApiServiceTest {
    @Test
    void registrationReplacesAndUnregistersTheWholeProviderAtomically() {
        AtomicInteger refreshes = new AtomicInteger();
        CerRecipeApiService service = new CerRecipeApiService(refreshes::incrementAndGet);
        Plugin owner = plugin("Provider");
        ApiRecipe first = recipe("first");
        ApiRecipe second = recipe("second");

        var registration = service.register(owner, "provider", List.of(first));
        assertEquals(1, refreshes.get());
        assertEquals(List.of("cer_api:provider/first"), ids(service));

        registration.replace(List.of(first, second));
        assertEquals(2, refreshes.get());
        assertEquals(List.of("cer_api:provider/first", "cer_api:provider/second"), ids(service));

        registration.unregister();
        registration.unregister();
        assertEquals(3, refreshes.get());
        assertEquals(List.of(), ids(service));
    }

    @Test
    void staleHandlesCannotRemoveANewerRegistrationAndOwnerDisableClearsAll() {
        AtomicInteger refreshes = new AtomicInteger();
        CerRecipeApiService service = new CerRecipeApiService(refreshes::incrementAndGet);
        Plugin owner = plugin("Provider");
        var stale = service.register(owner, "provider", List.of(recipe("old")));
        var current = service.register(owner, "provider", List.of(recipe("new")));

        stale.unregister();
        assertEquals(List.of("cer_api:provider/new"), ids(service));
        assertThrows(IllegalStateException.class, () -> stale.replace(List.of(recipe("stale"))));

        service.removeOwner(owner);
        assertEquals(List.of(), ids(service));
        current.unregister();
        assertEquals(List.of(), ids(service));
    }

    @Test
    void rejectsDuplicateRecipeIdsAndNamespaceOwnershipConflicts() {
        CerRecipeApiService service = new CerRecipeApiService(() -> {});
        Plugin firstOwner = plugin("First");
        Plugin secondOwner = plugin("Second");

        assertThrows(IllegalArgumentException.class,
                () -> service.register(firstOwner, "shared",
                        List.of(recipe("same"), recipe("same"))));
        service.register(firstOwner, "shared", List.of(recipe("recipe")));

        assertThrows(IllegalStateException.class,
                () -> service.register(secondOwner, "shared", List.of(recipe("other"))));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(secondOwner, "Upper Case", List.of(recipe("other"))));
    }

    @Test
    void resolvesLocalizedValuesWithTheLocaleRequestedAtCompileTime() {
        CerRecipeApiService service = new CerRecipeApiService(() -> {});
        Plugin owner = plugin("Provider");
        ApiRecipe recipe = new ApiRecipe("recipe",
                new ApiItem("minecraft:cauldron",
                        dev.che1s.craftenginerecipe.api.LocalizedText.of(
                                java.util.Map.of("zh_CN", "锅", "en_US", "Pot"))),
                List.of(),
                List.of(new ApiItem("minecraft:stone")));
        service.register(owner, "provider", List.of(recipe));

        assertEquals("锅", service.compile("zh_CN").getFirst().display().workstation().name());
        assertEquals("Pot", service.compile("en_US").getFirst().display().workstation().name());
    }

    @Test
    void notifiesOnlyOnRegistrationCountChangesAndUnregistration() {
        AtomicInteger refreshes = new AtomicInteger();
        List<CerRecipeApiService.ApiNotice> notices = new ArrayList<>();
        CerRecipeApiService service = new CerRecipeApiService(
                refreshes::incrementAndGet, notices::add);
        Plugin owner = plugin("Provider");

        service.register(owner, "provider", List.of(recipe("first")));
        assertEquals(1, notices.size());
        var current = service.register(owner, "provider", List.of(recipe("same-count")));
        assertEquals(1, notices.size());
        current.replace(List.of(recipe("first"), recipe("second")));
        current.unregister();

        assertEquals(List.of(
                CerRecipeApiService.NoticeKind.REGISTERED,
                CerRecipeApiService.NoticeKind.UPDATED,
                CerRecipeApiService.NoticeKind.UNREGISTERED),
                notices.stream().map(CerRecipeApiService.ApiNotice::kind).toList());
        assertEquals(List.of(0, 1, 2),
                notices.stream().map(CerRecipeApiService.ApiNotice::previous).toList());
        assertEquals(List.of(1, 2, 0),
                notices.stream().map(CerRecipeApiService.ApiNotice::current).toList());
    }

    private static List<String> ids(CerRecipeApiService service) {
        return service.compile("zh_CN").stream()
                .map(recipe -> recipe.display().id())
                .toList();
    }

    private static ApiRecipe recipe(String id) {
        return new ApiRecipe(id, new ApiItem("minecraft:cauldron"), List.of(),
                List.of(new ApiItem("minecraft:stone")));
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> method.getName().equals("getName") ? name : null);
    }
}
