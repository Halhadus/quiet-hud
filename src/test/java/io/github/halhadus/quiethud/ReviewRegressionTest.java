package io.github.halhadus.quiethud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ReviewRegressionTest {
    @TempDir Path root;
    private static Config config(Config.Entry e) { Config c = new Config(); c.entries.clear(); c.entries.add(e); return c; }
    private void write(String path, String text) throws Exception { Path p = root.resolve(path.replaceFirst("^/", "")); Files.createDirectories(p.getParent()); Files.writeString(p, text); }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertTrue(condition.getAsBoolean());
    }
    @Test void unsupportedAutoRowsAreHiddenButZeroAndManualErrorsRemainVisible() {
        Config.Entry e = new Config.Entry("FAN", "Fan", "FFFFFF", 1000); Config c = config(e);
        var missing = new Sampler.Value("N/A", "Auto", "No readable sensor", 1);
        assertFalse(HudModel.visible(c, e, missing));
        assertTrue(HudModel.visible(c, e, new Sampler.Value("0 RPM", "/sys/fan", "", 1)));
        e.source = "/sys/fan"; assertTrue(HudModel.visible(c, e, missing));
        e.source = ""; c.hideUnavailable = false; assertTrue(HudModel.visible(c, e, missing));
    }
    @Test void autoFallsThroughUnreadableSourcesAndReportsTheSelectedPath() throws Exception {
        Config.Entry e = new Config.Entry("CPU_TEMP", "CPU", "FFFFFF", 100);
        Sensors sensors = new Sensors(root) {
            @Override public List<Source> discover() { return List.of(new Source("TEMP", "cpu missing", "/missing", .001), new Source("TEMP", "soc thermal", "/valid", .001)); }
        };
        write("/valid", "46000");
        try (Sampler sampler = new Sampler(config(e), () -> true, sensors)) {
            await(() -> sampler.value(e).text().equals("46 °C")); assertEquals("/valid", sampler.value(e).source());
        }
    }
    @Test void errorsRetainTheSelectedSourceAndRetryWithBackoff() throws Exception {
        Config.Entry e = new Config.Entry("CPU_TEMP", "CPU", "FFFFFF", 100); e.source = "/missing";
        AtomicInteger reads = new AtomicInteger();
        Sensors sensors = new Sensors(root) {
            @Override String read(String path) throws java.io.IOException { reads.incrementAndGet(); return super.read(path); }
        };
        try (Sampler sampler = new Sampler(config(e), () -> true, sensors)) {
            await(() -> sampler.value(e).error().contains("Not a regular"));
            assertEquals("/missing", sampler.value(e).source()); int count = reads.get(); Thread.sleep(250); assertEquals(count, reads.get());
        }
    }
    @Test void olderInFlightSampleCannotOverwriteAChangedSource() throws Exception {
        Config.Entry e = new Config.Entry("CPU_TEMP", "CPU", "FFFFFF", 100); e.source = "/old";
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Sensors sensors = new Sensors(root) {
            @Override String read(String path) throws java.io.IOException {
                if (path.equals("/old")) {
                    started.countDown();
                    try { if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("Test timeout"); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                    return "10000";
                }
                return "50000";
            }
        };
        Config c = config(e);
        try (Sampler sampler = new Sampler(c, () -> true, sensors)) {
            assertTrue(started.await(2, TimeUnit.SECONDS)); e.source = "/new"; sampler.configure(c);
            await(() -> sampler.value(e).text().equals("50 °C")); release.countDown(); Thread.sleep(150);
            assertEquals("50 °C", sampler.value(e).text()); assertEquals("/new", sampler.value(e).source());
        } finally { release.countDown(); }
    }
    @Test void unchangedInFlightSampleSurvivesAnAppearanceEdit() throws Exception {
        Config.Entry e = new Config.Entry("CPU_TEMP", "CPU", "FFFFFF", 3600000); e.source = "/slow";
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Sensors sensors = new Sensors(root) {
            @Override String read(String path) throws java.io.IOException {
                started.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("Test timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new java.io.IOException(ex); }
                return "43000";
            }
        };
        Config c = config(e);
        try (Sampler sampler = new Sampler(c, () -> true, sensors)) {
            assertTrue(started.await(2, TimeUnit.SECONDS)); c.opacity = .5; sampler.configure(c); release.countDown();
            await(() -> sampler.value(e).text().equals("43 °C"));
        } finally { release.countDown(); }
    }
    @Test void customOutputIsLimitedWithoutBlockingTheCommonPool() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () -> {
            Exception error = assertThrows(Exception.class, () -> CommandRunner.run("yes x"));
            assertTrue(error.getMessage().contains("4096"));
        });
    }
    @Test void versionOneConfigMigratesWithoutRemovedKeybindFields() throws Exception {
        Path p = root.resolve("quiethud.json"); Files.writeString(p, "{\"schema\":1,\"requireRightShift\":true,\"enabled\":false}");
        Config c = Config.load(p); assertEquals(2, c.schema); assertFalse(c.enabled); assertTrue(c.hideUnavailable);
        assertFalse(Config.JSON.toJson(c).contains("requireRightShift"));
    }
    @Test void invalidClockAndCpuSelectionRecoverBeforeSampling() {
        Config c = new Config(); c.clockFormat = "yyyy"; c.cpuCores = "4,invalid"; c.normalize();
        assertEquals("HH:mm:ss", c.clockFormat); assertEquals("", c.cpuCores);
    }
}
