package dev.leon.cer.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class ButtonActionExecutorTest {
    private final FakeEnvironment environment = new FakeEnvironment();

    @Test
    void revokedButtonCannotExecuteFromAnAlreadyOpenMenu() {
        environment.allowed = false;
        execute("sound: click", "console: reward", "op: reward");
        assertTrue(environment.events.isEmpty());
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void delayedActionsRecheckButtonPermissionBeforeContinuing() {
        execute("sound: before", "delay: 0.1", "console: reward", "sound: after");
        assertEquals(List.of("sound:before"), environment.events);
        assertEquals(2, environment.delays.peek().ticks);
        environment.allowed = false;
        environment.runDelayed();
        assertEquals(List.of("sound:before"), environment.events);
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void delayedActionsStopAfterReloadOfflineOrDisable() {
        for (String reason : List.of("generation", "ready", "online", "enabled")) {
            FakeEnvironment current = new FakeEnvironment();
            ButtonActionExecutor.execute(current, List.of("delay: 1", "console: reward"));
            switch (reason) {
                case "generation" -> current.generation++;
                case "ready" -> current.ready = false;
                case "online" -> current.online = false;
                case "enabled" -> current.enabled = false;
            }
            current.runDelayed();
            assertTrue(current.events.isEmpty(), reason);
            assertTrue(current.global.isEmpty(), reason);
        }
    }

    @Test
    void eachImmediateActionChecksForPermissionChangesCausedByAnEarlierAction() {
        environment.afterSound = () -> environment.allowed = false;
        execute("sound: first", "console: denied", "op: denied");
        assertEquals(List.of("sound:first"), environment.events);
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void paperRechecksPermissionAtActualGlobalDispatch() {
        execute("console: reward", "sound: after");
        environment.allowed = false;
        environment.runGlobal();
        assertTrue(environment.events.isEmpty());
        assertTrue(environment.player.isEmpty());
    }

    @Test
    void queuedGlobalCommandIsDiscardedAfterReload() {
        execute("console: reward", "sound: after");
        environment.generation++;
        environment.runGlobal();
        assertTrue(environment.events.isEmpty());
        assertTrue(environment.player.isEmpty());
    }

    @Test
    void foliaGlobalHandoffNeverReadsPlayerAndRechecksPermissionWhenReturning() {
        environment.folia = true;
        execute("console: accepted", "sound: denied", "console: denied");
        // The first command was authorized on the owner thread before submission. Revocation
        // after this boundary cannot atomically retract a command on another Folia thread.
        environment.allowed = false;
        environment.runGlobal();
        assertEquals(List.of("console:accepted"), environment.events);
        environment.runPlayer();
        assertEquals(List.of("console:accepted"), environment.events);
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void consoleReturnsToPlayerAndPreservesActionOrder() {
        execute("sound: first", "close", "delay: 2t", "console: server:command", "player: spawn", "op: reward", "sound: last");
        assertEquals(List.of("sound:first", "close"), environment.events);
        environment.runDelayed();
        assertEquals(List.of("sound:first", "close"), environment.events);
        environment.runGlobal();
        assertEquals(List.of("sound:first", "close", "console:server:command"), environment.events);
        environment.runPlayer();
        assertEquals(List.of("sound:first", "close", "console:server:command", "player:spawn:false",
                "op:true", "player:reward:true", "op:false", "sound:last"), environment.events);
        assertFalse(environment.op);
    }

    @Test
    void returningFromConsoleDoesNotContinueForDisconnectedPlayer() {
        environment.folia = true;
        execute("console: first", "player: second");
        environment.runGlobal();
        environment.online = false;
        environment.runPlayer();
        assertEquals(List.of("console:first"), environment.events);
    }

    @Test
    void paperRestoresOriginalOpEvenWhenCommandThrows() {
        for (boolean originallyOp : List.of(false, true)) {
            FakeEnvironment current = new FakeEnvironment();
            current.op = originallyOp;
            current.throwCommand = true;
            assertThrows(IllegalStateException.class,
                    () -> ButtonActionExecutor.execute(current, List.of("op: broken", "sound: skipped")));
            assertEquals(originallyOp, current.op);
            assertEquals(List.of("op:true", "player:broken:true", "op:" + originallyOp), current.events);
        }
    }

    @Test
    void foliaRejectsOpAndStopsTheRemainingChain() {
        environment.folia = true;
        execute("sound: before", "op: reward", "console: skipped", "sound: skipped");
        assertEquals(List.of("sound:before", "rejected-op"), environment.events);
        assertTrue(environment.global.isEmpty());
        assertFalse(environment.op);
    }

    @Test
    void veryLargeActionListsYieldWithoutRecursionAndKeepOrder() {
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) actions.add(i % 2 == 0 ? null : "unknown");
        actions.add("sound: completed");
        ButtonActionExecutor.execute(environment, actions);
        assertTrue(environment.events.isEmpty());
        assertEquals(1, environment.player.size());
        int turns = 0;
        while (!environment.player.isEmpty()) {
            assertTrue(turns++ < 1000, "unexpected runaway continuation");
            environment.runPlayer();
        }
        assertEquals(List.of("sound:completed"), environment.events);
    }

    @Test
    void yieldedLongChainRechecksPermission() {
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < 200; i++) actions.add("unknown");
        actions.add("console: denied");
        ButtonActionExecutor.execute(environment, actions);
        environment.allowed = false;
        environment.runPlayer();
        assertTrue(environment.events.isEmpty());
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void delayedExecutionUsesAnIsolatedSnapshotOfItsActions() {
        List<String> actions = new ArrayList<>(List.of("delay: 1", "sound: original"));
        ButtonActionExecutor.execute(environment, actions);
        actions.set(1, "console: replacement");
        environment.runDelayed();
        assertEquals(List.of("sound:original"), environment.events);
        assertTrue(environment.global.isEmpty());
    }

    @Test
    void acceptedDelayFormatsAndBlankActionsRemainCompatible() {
        execute(null, " ", "console: ", "player:", "op:", "delay: .25s", "delay: 3t", "delay: invalid", "command: spawn");
        assertEquals(5, environment.delays.peek().ticks);
        environment.runDelayed();
        assertEquals(3, environment.delays.peek().ticks);
        environment.runDelayed();
        assertEquals(20, environment.delays.peek().ticks);
        environment.runDelayed();
        assertEquals(List.of("player:spawn:false"), environment.events);
    }

    private void execute(String... actions) {
        ButtonActionExecutor.execute(environment, Arrays.asList(actions));
    }

    private static final class FakeEnvironment implements ButtonActionExecutor.Environment {
        private enum Lane { PLAYER, GLOBAL }

        private record Delayed(Runnable action, long ticks) {}

        private final List<String> events = new ArrayList<>();
        private final Queue<Runnable> player = new ArrayDeque<>();
        private final Queue<Runnable> global = new ArrayDeque<>();
        private final Queue<Delayed> delays = new ArrayDeque<>();
        private Lane lane = Lane.PLAYER;
        private boolean enabled = true, ready = true, online = true, allowed = true, folia, op, throwCommand;
        private long generation = 1;
        private Runnable afterSound = () -> {};

        @Override
        public boolean isCurrent() { return enabled && ready && generation == 1; }

        @Override
        public boolean isPlayerAuthorized() {
            assertTrue(lane == Lane.PLAYER || !folia, "player state accessed from global Folia thread");
            return online && allowed;
        }

        @Override
        public boolean mayExecuteConsole() {
            assertEquals(Lane.GLOBAL, lane);
            return folia || isPlayerAuthorized();
        }

        @Override
        public boolean isFolia() { return folia; }

        @Override
        public void later(Runnable action, long ticks) {
            assertEquals(Lane.PLAYER, lane);
            delays.add(new Delayed(action, ticks));
        }

        @Override
        public void player(Runnable action) { player.add(action); }

        @Override
        public void global(Runnable action) {
            assertEquals(Lane.PLAYER, lane);
            global.add(action);
        }

        @Override
        public void sound(String sound) {
            assertEquals(Lane.PLAYER, lane);
            events.add("sound:" + sound);
            afterSound.run();
        }

        @Override
        public void close() {
            assertEquals(Lane.PLAYER, lane);
            events.add("close");
        }

        @Override
        public void consoleCommand(String command) {
            assertEquals(Lane.GLOBAL, lane);
            events.add("console:" + command);
        }

        @Override
        public void playerCommand(String command) {
            assertEquals(Lane.PLAYER, lane);
            events.add("player:" + command + ":" + op);
            if (throwCommand) throw new IllegalStateException("command failed");
        }

        @Override
        public boolean isOp() {
            assertEquals(Lane.PLAYER, lane);
            return op;
        }

        @Override
        public void setOp(boolean value) {
            assertEquals(Lane.PLAYER, lane);
            op = value;
            events.add("op:" + value);
        }

        @Override
        public void rejectFoliaOp() {
            assertEquals(Lane.PLAYER, lane);
            events.add("rejected-op");
        }

        private void runDelayed() { run(Lane.PLAYER, delays.remove().action); }
        private void runPlayer() { run(Lane.PLAYER, player.remove()); }
        private void runGlobal() { run(Lane.GLOBAL, global.remove()); }

        private void run(Lane destination, Runnable action) {
            Lane previous = lane;
            lane = destination;
            try { action.run(); }
            finally { lane = previous; }
        }
    }
}
