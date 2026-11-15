package dev.che1s.craftenginerecipe.gui;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;

/** Calls Paper's dialog closer only when the running API exposes it. */
final class DialogCompatibility {
    private DialogCompatibility() {
    }

    static void closeDialog(Player player) {
        if (player == null) return;
        try {
            Player.class.getMethod("closeDialog").invoke(player);
        } catch (NoSuchMethodException ignored) {
            // Paper versions before dialogs have no open dialog to close.
        } catch (IllegalAccessException | InvocationTargetException error) {
            if (error instanceof InvocationTargetException invocation
                    && invocation.getCause() != null) {
                throw new IllegalStateException("Cannot close player dialog", invocation.getCause());
            }
            throw new IllegalStateException("Cannot close player dialog", error);
        }
    }
}
