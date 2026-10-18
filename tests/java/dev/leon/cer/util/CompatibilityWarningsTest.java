package dev.leon.cer.util;

import dev.leon.cer.i18n.LanguageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityWarningsTest {
    @TempDir Path folder;

    @AfterEach
    void removeSession() {
        CompatibilityWarnings.clear();
    }

    @Test
    void concurrentFailuresLogOncePerCallSiteAndGenerationAndStopAfterShutdown() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        LanguageManager language = new LanguageManager(folder,
                path -> getClass().getClassLoader().getResourceAsStream(path), logger);
        AtomicLong generation = new AtomicLong(1);
        RuntimeException error = new IllegalStateException("external API failure");
        CompatibilityWarnings.install(logger, language, generation::get);

        IntStream.range(0, 32).parallel().forEach(i -> CompatibilityWarnings.warn("ItemBridge.build", error));
        assertEquals(1, records.size());
        assertSame(error, records.getFirst().getThrown());
        assertTrue(records.getFirst().getMessage().contains("ItemBridge.build"));

        CompatibilityWarnings.warn("DisplayNames.processors", error);
        assertEquals(2, records.size());
        generation.incrementAndGet();
        CompatibilityWarnings.warn("ItemBridge.build", error);
        assertEquals(3, records.size());

        CompatibilityWarnings.clear();
        CompatibilityWarnings.warn("ItemBridge.platformItem", error);
        assertEquals(3, records.size());
    }
}
