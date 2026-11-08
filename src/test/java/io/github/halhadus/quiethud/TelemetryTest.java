package io.github.halhadus.quiethud;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryTest {
    @TempDir Path root;
    private void write(String p, String content) throws Exception { Path file = root.resolve(p.replaceFirst("^/", "")); Files.createDirectories(file.getParent()); Files.writeString(file, content); }
    private static String fdinfo(long time, int kib) { return "drm-driver: panthor\ndrm-client-id: 7\ndrm-engine-panthor: " + time + " ns\ndrm-resident-memory: " + kib + " KiB\n"; }
    @Test void cpuUsesDeltaAndDoesNotDoubleCountGuestTime() throws Exception {
        var a = Sensors.cpuTimes("cpu  100 0 100 800 0 0 0 0 90 0\ncpu0 1 2 3");
        var b = Sensors.cpuTimes("cpu  130 0 120 850 0 0 0 0 120 0\n");
        assertEquals(1000, a.total()); assertEquals(50, Sensors.cpuLoad(a, b), .001);
        assertThrows(Exception.class, () -> Sensors.cpuLoad(null, b)); assertThrows(Exception.class, () -> Sensors.cpuLoad(b, a));
    }
    @Test void ramUsesAvailableMemoryAndBinaryUnits() throws Exception {
        assertEquals("2.0 / 8.0 GiB", Sensors.ram("MemTotal: 8388608 kB\nMemFree: 100 kB\nMemAvailable: 6291456 kB\n"));
        assertThrows(Exception.class, () -> Sensors.ram("MemTotal: 1000 kB\n"));
    }
    @Test void discoversThermalHwmonFanAndClusterSources() throws Exception {
        write("/sys/class/thermal/thermal_zone0/type", "soc-thermal"); write("/sys/class/thermal/thermal_zone0/temp", "45000");
        write("/sys/class/thermal/thermal_zone1/type", "gpu-thermal"); write("/sys/class/thermal/thermal_zone1/temp", "47000");
        write("/sys/class/hwmon/hwmon0/name", "pwmfan"); write("/sys/class/hwmon/hwmon0/fan1_input", "1200");
        write("/sys/class/devfreq/fb000000.gpu/cur_freq", "800000000"); write("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "1800000");
        var sources = new Sensors(root).discover(); assertEquals(5, sources.size());
        assertTrue(sources.stream().anyMatch(s -> s.category().equals("GPU_CLOCK") && s.multiplier() == .000001));
        assertTrue(sources.stream().anyMatch(s -> s.category().equals("FAN")));
    }
    @Test void drmDeduplicatesDescriptorsAndKeepsResidentAllocation() throws Exception {
        write("/proc/self/fdinfo/10", fdinfo(100, 1048576)); write("/proc/self/fdinfo/11", fdinfo(100, 1048576));
        var clients = new Sensors(root).drm(""); assertEquals(1, clients.size()); assertEquals(1073741824L, clients.getFirst().resident());
        assertEquals(100, clients.getFirst().engines().get("drm-engine-panthor"));
        assertThrows(Exception.class, () -> Sensors.parseFdinfo("pos: 1\nflags: 0"));
    }
    @Test void supportsPanthorPrefixedMemoryAndMiBUnits() throws Exception {
        var client = Sensors.parseFdinfo("drm-driver: panthor\ndrm-client-id: 1\npanthor-resident-memory: 1024 MiB\ndrm-curfreq-panthor: 800000000 Hz\n");
        assertTrue(client.hasMemory()); assertEquals(1073741824L, client.resident()); assertEquals(800000000, client.frequencyHz());
    }
    @Test void malformedConfigurationIsBackedUpAndFutureSchemaPreserved() throws Exception {
        Path file = root.resolve("config/quiethud.json"); write("/config/quiethud.json", "{broken");
        assertEquals(11, Config.load(file).entries.size());
        try (var files = Files.list(file.getParent())) { assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("quiethud.broken-"))); }
        write("/config/quiethud.json", "{\"schema\":99}"); assertEquals(Config.CURRENT_SCHEMA, Config.load(file).schema); assertEquals("{\"schema\":99}", Files.readString(file));
    }
    @Test void normalizesAndAtomicallyRoundTripsConfig() throws Exception {
        Config c = new Config(); c.schema = 0; c.scale = Double.NaN; c.entries.getFirst().interval = 0;
        c.entries.get(1).id = c.entries.getFirst().id; c.save(root.resolve("config/quiethud.json"));
        Config loaded = Config.load(root.resolve("config/quiethud.json"));
        assertEquals(Config.CURRENT_SCHEMA, loaded.schema); assertEquals(2, loaded.scale); assertEquals(100, loaded.entries.getFirst().interval);
        assertEquals(loaded.entries.size(), loaded.entries.stream().map(e -> e.id).distinct().count());
        try (var files = Files.list(root.resolve("config"))) { assertEquals(1, files.count()); }
    }
    @Test void formatsAndBoundsCustomCommandOutput() throws Exception {
        assertEquals("hello", CommandRunner.run("printf 'hello\\nworld\\n'"));
        assertEquals(160, Sampler.clean("x".repeat(200)).length());
        assertEquals("abc", Sampler.clean("a§b\tc"));
        assertThrows(Exception.class, () -> CommandRunner.run("exit 7"));
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> assertThrows(Exception.class, () -> CommandRunner.run("sleep 5")));
    }
    @Test void disabledCommandsNeverRun() throws Exception {
        Config c = new Config(); c.entries.clear(); Config.Entry e = new Config.Entry("COMMAND", "Command", "FFFFFF", 1000); e.source = "touch '" + root.resolve("must-not-exist") + "'"; c.entries.add(e);
        try (Sampler sampler = new Sampler(c, () -> true, new Sensors(root))) {
            waitFor(() -> !sampler.value(e).error().equals("Waiting for first sample"));
            assertTrue(sampler.value(e).error().contains("Enable local shell commands")); assertFalse(Files.exists(root.resolve("must-not-exist")));
        }
    }
    @Test void samplingPausesWhenHiddenAndReadsManualSensor() throws Exception {
        write("/sensor", "42000"); Config c = new Config(); c.entries.clear(); Config.Entry e = new Config.Entry("CPU_TEMP", "Temp", "FFFFFF", 100); e.source = "/sensor"; c.entries.add(e);
        AtomicBoolean visible = new AtomicBoolean(false);
        try (Sampler sampler = new Sampler(c, visible::get, new Sensors(root))) {
            Thread.sleep(150); assertEquals("N/A", sampler.value(e).text()); visible.set(true);
            waitFor(() -> sampler.value(e).text().equals("42 °C")); visible.set(false); Thread.sleep(150); write("/sensor", "48000"); Thread.sleep(150);
            assertEquals("42 °C", sampler.value(e).text()); visible.set(true); waitFor(() -> sampler.value(e).text().equals("48 °C"));
        }
    }
    @Test void ignoresOfflineCpuAndBoundsFileReads() throws Exception {
        write("/sys/devices/system/cpu/cpu1/cpufreq/scaling_cur_freq", "2400000"); write("/sys/devices/system/cpu/cpu1/online", "0");
        assertTrue(new Sensors(root).discover().isEmpty()); write("/long", "x".repeat(5000));
        assertEquals(1024, Sensors.bounded(root.resolve("long"), 1024).length());
        assertThrows(Exception.class, () -> Sensors.bounded(root, 1024));
    }
    @Test void gpuClockFallsBackToDrmCurrentFrequency() throws Exception {
        write("/proc/self/fdinfo/10", fdinfo(100, 1) + "drm-curfreq-panthor: 800000000 Hz\n");
        Config c = new Config(); c.entries.clear(); Config.Entry e = new Config.Entry("GPU_CLOCK", "Clock", "FFFFFF", 100); c.entries.add(e);
        try (Sampler sampler = new Sampler(c, () -> true, new Sensors(root))) { waitFor(() -> sampler.value(e).text().equals("800 MHz")); }
    }
    @Test void missingGpuNeverProducesInventedNumbers() throws Exception {
        Config c = new Config(); c.entries.clear(); Config.Entry e = new Config.Entry("GPU_LOAD", "GPU", "40FF00", 100); c.entries.add(e);
        try (Sampler sampler = new Sampler(c, () -> true, new Sensors(root))) {
            waitFor(() -> sampler.value(e).error().contains("No readable DRM")); assertEquals("N/A", sampler.value(e).text());
        }
    }
    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long limit = System.nanoTime() + 3000000000L;
        while (!condition.getAsBoolean() && System.nanoTime() < limit) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Expected background sample did not arrive");
    }
}
