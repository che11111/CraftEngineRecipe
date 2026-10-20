package dev.che1s.craftenginerecipe.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Sequential action interpreter; all entry/resume calls belong to the player's scheduler. */
final class ButtonActionExecutor {
    private static final int MAX_ACTIONS_PER_TURN = 128;

    interface Environment {
        /** Lifecycle/generation check, safe on both player and global threads. */
        boolean isCurrent();

        /** Checks online state and the originating button's current permission on its owner thread. */
        boolean isPlayerAuthorized();

        /**
         * Called globally after an authorized player-thread handoff. On Folia this must not
         * read player state: authorization and a later global command cannot be atomic.
         */
        boolean mayExecuteConsole();

        boolean isFolia();
        void later(Runnable action, long ticks);
        void player(Runnable action);
        void global(Runnable action);
        void sound(String sound);
        void close();
        void consoleCommand(String command);
        void playerCommand(String command);
        boolean isOp();
        void setOp(boolean op);
        void rejectFoliaOp();
    }

    private final Environment environment;
    private final List<String> actions;

    private ButtonActionExecutor(Environment environment, List<String> actions) {
        this.environment = environment;
        // Keep null entries compatible with older configurations while isolating delayed execution.
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
    }

    static void execute(Environment environment, List<String> actions) {
        if (actions == null || actions.isEmpty()) return;
        new ButtonActionExecutor(environment, actions).resume(0);
    }

    private boolean mayContinue() {
        return this.environment.isCurrent() && this.environment.isPlayerAuthorized();
    }

    private void resume(int start) {
        int index = start;
        int processed = 0;
        while (index < this.actions.size() && processed++ < MAX_ACTIONS_PER_TURN) {
            if (!this.mayContinue()) return;
            String action = this.actions.get(index++);
            if (action == null || action.isBlank()) continue;
            action = action.trim();
            String lower = action.toLowerCase(Locale.ROOT);
            int next = index;
            if (lower.equals("close")) {
                this.environment.close();
            } else if (lower.startsWith("delay:")) {
                long ticks = parseDelayTicks(action.substring("delay:".length()).trim());
                this.environment.later(() -> this.resume(next), ticks);
                return;
            } else if (lower.startsWith("sound:")) {
                this.environment.sound(payload(action));
            } else if (lower.startsWith("console:")) {
                String command = payload(action);
                if (command.isEmpty()) continue;
                // This entity-thread authorization is the Folia handoff boundary. A permission
                // change after submission cannot atomically retract the already accepted command.
                if (!this.mayContinue()) return;
                this.environment.global(() -> {
                    if (!this.environment.isCurrent() || !this.environment.mayExecuteConsole()) return;
                    this.environment.consoleCommand(command);
                    if (this.environment.isCurrent()) this.environment.player(() -> this.resume(next));
                });
                return;
            } else if (lower.startsWith("command:") || lower.startsWith("player:")) {
                String command = payload(action);
                if (!command.isEmpty()) this.environment.playerCommand(command);
            } else if (lower.startsWith("op:")) {
                if (this.environment.isFolia()) {
                    this.environment.rejectFoliaOp();
                    return;
                }
                String command = payload(action);
                if (command.isEmpty()) continue;
                if (!this.mayContinue()) return;
                boolean wasOp = this.environment.isOp();
                try {
                    this.environment.setOp(true);
                    this.environment.playerCommand(command);
                } finally {
                    this.environment.setOp(wasOp);
                }
            }
        }
        if (index < this.actions.size() && this.mayContinue()) {
            int next = index;
            // Bound work on a region tick as well as stack depth for unusually large configurations.
            this.environment.player(() -> this.resume(next));
        }
    }

    private static String payload(String action) {
        return action.substring(action.indexOf(':') + 1).trim();
    }

    private static long parseDelayTicks(String value) {
        value = value.trim();
        try {
            if (value.endsWith("t") || value.endsWith("T")) {
                return Math.max(1, (long) Double.parseDouble(value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("s") || value.endsWith("S")) value = value.substring(0, value.length() - 1);
            return Math.max(1, Math.round(Double.parseDouble(value) * 20.0));
        } catch (NumberFormatException error) {
            return 20L;
        }
    }
}
