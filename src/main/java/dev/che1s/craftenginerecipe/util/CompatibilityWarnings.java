package dev.che1s.craftenginerecipe.util;

import dev.che1s.craftenginerecipe.i18n.LanguageManager;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 在每个数据版本中，对每个固定调用点只报告第一次兼容性回退。 */
public final class CompatibilityWarnings {
    private static volatile Session active;

    private CompatibilityWarnings() {
    }

    public static void install(Logger logger, LanguageManager language, LongSupplier generation) {
        active = new Session(Objects.requireNonNull(logger), Objects.requireNonNull(language),
                Objects.requireNonNull(generation));
    }

    public static void clear() {
        active = null;
    }

    public static void warn(String operation, Throwable error) {
        Session session = active;
        if (session != null && session.first(operation)) {
            session.logger.log(Level.WARNING, session.language.text("logs.compatibility-fallback",
                    "operation", operation), error);
        }
    }

    private static final class Session {
        private final Logger logger;
        private final LanguageManager language;
        private final LongSupplier generation;
        private final Set<String> reported = new HashSet<>();
        private long current = Long.MIN_VALUE;

        private Session(Logger logger, LanguageManager language, LongSupplier generation) {
            this.logger = logger;
            this.language = language;
            this.generation = generation;
        }

        private synchronized boolean first(String operation) {
            long value = this.generation.getAsLong();
            if (value != this.current) {
                this.reported.clear();
                this.current = value;
            }
            return this.reported.add(operation);
        }
    }
}
