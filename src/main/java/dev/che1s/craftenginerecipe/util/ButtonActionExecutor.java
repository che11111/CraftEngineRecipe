package dev.che1s.craftenginerecipe.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 顺序动作解释器；所有入口与恢复调用都必须位于玩家所属调度器。 */
final class ButtonActionExecutor {
    private static final int MAX_ACTIONS_PER_TURN = 128;

    interface Environment {
        /** 生命周期与数据版本检查，在玩家线程和全局线程上均安全。 */
        boolean isCurrent();

        /** 在所属线程检查在线状态和原按钮的当前权限。 */
        boolean isPlayerAuthorized();

        /**
         * 在玩家线程完成授权并移交后于全局线程调用。在 Folia 中不得读取玩家状态，
         * 因为授权与稍后的全局指令无法原子完成。
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
        // 保留 null 动作项以兼容旧配置，同时隔离延迟执行逻辑。
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
                // 这里的实体线程授权是 Folia 的线程移交边界。提交后即使用户权限变化，
                // 也无法原子撤回已经接受的指令。
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
            // 对异常庞大的配置同时限制单次区域 tick 的工作量和递归深度。
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
