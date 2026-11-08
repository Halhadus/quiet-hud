package io.github.halhadus.quiethud;

import java.io.IOException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class Sampler implements AutoCloseable {
    public record Value(String text, String source, String error, long time) {}
    private final Sensors sensors;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "QuietHUD scheduler"); t.setDaemon(true); return t; });
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private static final Value WAITING = new Value("N/A", "Waiting for source selection", "Waiting for first sample", 0);
    private final BooleanSupplier visible;
    private volatile Session session;
    public volatile List<Sensors.Source> sources = List.of();
    public volatile int fps;
    public volatile long sourceRevision;
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private volatile boolean closed;
    private boolean paused;
    private static final class State {
        final AtomicBoolean busy = new AtomicBoolean();
        final String signature;
        volatile long due;
        volatile boolean reset;
        long drmTime;
        int failures;
        String source = "Auto: no readable source selected";
        Sensors.CpuTimes cpu;
        Map<String, Long> engines = Map.of();
        State(String signature) { this.signature = signature; }
    }
    private static final class Session {
        final Config config;
        final ConcurrentMap<String, Value> values = new ConcurrentHashMap<>();
        final Map<String, State> states = new HashMap<>();
        Session(Config config) { this.config = config; }
    }
    private static final class PendingSample extends IOException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        PendingSample(String message) { super(message); }
    }
    public Sampler(Config c, BooleanSupplier visible) { this(c, visible, new Sensors()); }
    Sampler(Config c, BooleanSupplier visible, Sensors sensors) {
        this.visible = visible; this.sensors = sensors; configure(c);
        rescan(); timer.scheduleWithFixedDelay(this::dispatch, 0, 50, TimeUnit.MILLISECONDS);
    }
    public long revision() { return revision.get(); }
    public synchronized void configure(Config c) {
        Config copy = c.copy(); copy.normalize(); Session next = new Session(copy), previous = session;
        for (Config.Entry e : copy.entries) {
            String signature = Config.JSON.toJson(e) + "|" + copy.cpuMode + "|" + copy.cpuCores + "|" + copy.clockFormat + "|" + copy.commandsAllowed;
            State old = previous == null ? null : previous.states.get(e.id);
            State state = old != null && old.signature.equals(signature) ? old : new State(signature);
            next.states.put(e.id, state);
            if (state == old) { Value value = previous.values.get(e.id); if (value != null) next.values.put(e.id, value); }
        }
        session = next; revision.incrementAndGet();
    }
    public void rescan() {
        if (closed || !scanning.compareAndSet(false, true)) return;
        workers.submit(() -> {
            try {
                sources = sensors.discover(); sourceRevision++; revision.incrementAndGet();
                for (State state : session.states.values()) { state.due = 0; state.reset = true; }
            } finally { scanning.set(false); }
        });
    }
    public Value value(Config.Entry e) {
        Value v = session.values.get(e.id);
        if (v == null) return WAITING;
        if (v.error.isBlank() && System.nanoTime() - v.time > Math.max(5000L, e.interval * 3L) * 1000000L)
            return new Value("N/A", v.source, "Sample is stale", v.time);
        return v;
    }
    private void dispatch() {
        if (closed || scanning.get()) return;
        Session current = session; Config c = current.config;
        if (c.pauseHidden && !visible.getAsBoolean()) { paused = true; return; }
        if (paused) { for (State state : current.states.values()) { state.reset = true; state.due = 0; } paused = false; }
        long now = System.nanoTime();
        for (Config.Entry e : c.entries) {
            if (!e.enabled) continue;
            State state = current.states.get(e.id);
            if (now < state.due || !state.busy.compareAndSet(false, true)) continue;
            state.due = now + e.interval * 1000000L;
            workers.submit(() -> {
                try {
                    if (state.reset) { state.reset = false; state.cpu = null; state.engines = Map.of(); state.drmTime = 0; }
                    Value value = sample(e, c, state); state.failures = 0; publish(current, e.id, state, value);
                } catch (Exception ex) {
                    String message = Objects.toString(ex.getMessage(), ex.getClass().getSimpleName());
                    publish(current, e.id, state, new Value("N/A", state.source, message, System.nanoTime()));
                    if (!(ex instanceof PendingSample)) { state.failures = Math.min(6, state.failures + 1); state.due = System.nanoTime() + Math.max(e.interval, Math.min(30000, 1000L << state.failures)) * 1000000L; }
                } finally { revision.incrementAndGet(); state.busy.set(false); }
            });
        }
    }
    private synchronized void publish(Session origin, String id, State state, Value value) {
        origin.values.put(id, value);
        Session active = session;
        if (active != origin && active.states.get(id) == state) active.values.put(id, value);
    }
    private Value result(String text, String source) { return new Value(text, source, "", System.nanoTime()); }
    private Value numeric(Config.Entry e, double n, String unit, String source) throws IOException {
        n = n * e.multiplier + e.offset;
        if (!Double.isFinite(n)) throw new IOException("Invalid numeric result");
        return result(String.format(Locale.ROOT, "%." + e.precision + "f", n) + (unit.isBlank() ? "" : " " + unit), source);
    }
    private Value sample(Config.Entry e, Config c, State state) throws Exception {
        String file = e.source; state.source = file.isBlank() ? "Auto: no readable source selected" : file;
        switch (e.kind) {
            case "FPS": state.source = "Minecraft.getFps()"; return numeric(e, fps, "", state.source);
            case "CLOCK": state.source = "System local clock: " + ZoneId.systemDefault(); return result(LocalTime.now().format(DateTimeFormatter.ofPattern(c.clockFormat)), state.source);
            case "RAM": {
                state.source = file.isBlank() ? "/proc/meminfo" : file;
                return result(Sensors.ram(sensors.read(state.source)), state.source + " (MemTotal - MemAvailable)");
            }
            case "CPU_LOAD": {
                file = file.isBlank() ? "/proc/stat" : file;
                state.source = file; Sensors.CpuTimes next = Sensors.cpuTimes(sensors.firstLine(file)), old = state.cpu; state.cpu = next;
                if (old == null) throw new PendingSample("Waiting for CPU counter delta");
                return numeric(e, Sensors.cpuLoad(old, next), "%", file + " (system aggregate delta)");
            }
            case "CPU_CLOCK": {
                if (!file.isBlank()) return numeric(e, Sensors.number(sensors.read(file)) * .001, "MHz", file + " (kHz)");
                Set<Integer> selected = new HashSet<>();
                if (!c.cpuCores.isBlank()) for (String core : c.cpuCores.split(",")) selected.add(Integer.parseInt(core.trim()));
                List<Double> frequencies = new ArrayList<>(); List<String> paths = new ArrayList<>();
                for (Sensors.Source s : sources) if (s.category().equals("CPU_CLOCK") && sensors.online(s) && (selected.isEmpty() || selected.contains(Integer.parseInt(s.name().substring(3))))) {
                    try { frequencies.add(Sensors.number(sensors.read(s.path())) * s.multiplier()); paths.add(s.path()); } catch (IOException ex) { /* Offline cores may disappear between discovery and sampling. */ }
                }
                state.source = c.cpuMode + ": " + String.join(", ", paths);
                if (frequencies.isEmpty()) throw new IOException("No readable online CPU frequency sources");
                double n = c.cpuMode.equals("AVERAGE") ? frequencies.stream().mapToDouble(v -> v).average().orElseThrow() : Collections.max(frequencies);
                return numeric(e, n, "MHz", state.source);
            }
            case "CPU_TEMP", "GPU_TEMP", "GPU_CLOCK", "FAN": {
                double factor = e.kind.endsWith("TEMP") ? .001 : e.kind.equals("GPU_CLOCK") ? .000001 : 1;
                String unit = e.kind.endsWith("TEMP") ? "°C" : e.kind.equals("GPU_CLOCK") ? "MHz" : "RPM";
                if (file.isBlank()) {
                    String category = e.kind.endsWith("TEMP") ? "TEMP" : e.kind;
                    List<Sensors.Source> candidates = sources.stream().filter(s -> s.category().equals(category)).filter(s -> {
                        String name = s.name().toLowerCase(Locale.ROOT);
                        return !e.kind.endsWith("TEMP") || (e.kind.equals("GPU_TEMP") ? name.matches(".*(gpu|mali|panthor|panfrost|nvidia|nouveau|radeon).*" ) : (name.matches(".*(cpu|soc|package|coretemp|k10temp).*") && !name.matches(".*(gpu|mali|panthor|panfrost|nvidia|nouveau|radeon).*")));
                    }).toList();
                    IOException failure = null;
                    for (Sensors.Source candidate : candidates) {
                        state.source = candidate.path();
                        try { return numeric(e, Sensors.number(sensors.read(candidate.path())) * candidate.multiplier(), unit, state.source); }
                        catch (IOException ex) { failure = ex; }
                    }
                    if (e.kind.equals("GPU_CLOCK")) {
                        state.source = "/proc/self/fdinfo drm-curfreq (Hz)";
                        long frequency = sensors.drm("").stream().mapToLong(Sensors.DrmClient::frequencyHz).max().orElse(-1);
                        if (frequency >= 0) return numeric(e, frequency / 1000000d, "MHz", state.source);
                    }
                    if (failure != null) throw failure;
                    throw new IOException("No matching readable sensor; select a source or enter a path");
                }
                state.source = file;
                return numeric(e, Sensors.number(sensors.read(file)) * factor, unit, file);
            }
            case "GPU_LOAD": {
                if (file.isBlank()) {
                    for (Sensors.Source source : sources) if (source.category().equals("GPU_LOAD")) {
                        state.source = source.path();
                        try { return deviceLoad(e, source.path()); } catch (IOException ex) { /* Try the next device source, then DRM. */ }
                    }
                }
                if (!file.isBlank() && !file.contains("/fdinfo/")) {
                    state.source = file; return deviceLoad(e, file);
                }
                state.source = file.isBlank() ? "/proc/self/fdinfo (Minecraft process engine time)" : file;
                List<Sensors.DrmClient> clients = sensors.drm(file); Map<String, Long> next = new HashMap<>();
                for (var client : clients) for (var engine : client.engines().entrySet()) next.put(client.id() + ":" + engine.getKey(), engine.getValue());
                long now = System.nanoTime(), elapsed = now - state.drmTime, busy = 0; int matched = 0;
                for (var counter : next.entrySet()) { Long previous = state.engines.get(counter.getKey()); if (previous != null && counter.getValue() >= previous) { busy += counter.getValue() - previous; matched++; } }
                state.engines = Map.copyOf(next); state.drmTime = now;
                if (next.isEmpty()) throw new IOException("DRM engine time unavailable; Panthor needs timestamp profiling (3)");
                if (matched == 0 || elapsed <= 0) throw new PendingSample("Waiting for DRM engine counter delta");
                return numeric(e, Math.clamp(busy * 100d / elapsed, 0, 100), "%", "/proc/self/fdinfo: " + clients.stream().map(Sensors.DrmClient::driver).distinct().toList() + " Minecraft process engine time (not device-wide)");
            }
            case "VRAM": {
                if (!file.isBlank() && !file.contains("/fdinfo/")) return numeric(e, Sensors.number(sensors.read(file)) / 1073741824d, "GiB", file + " (bytes)");
                state.source = file.isBlank() ? "/proc/self/fdinfo (process resident GPU memory)" : file;
                List<Sensors.DrmClient> clients = sensors.drm(file);
                if (clients.stream().noneMatch(Sensors.DrmClient::hasMemory)) throw new IOException("Driver does not expose process resident GPU memory");
                double bytes = clients.stream().mapToLong(Sensors.DrmClient::resident).sum();
                return result(String.format(Locale.ROOT, "%.1f GiB", bytes / 1073741824d), "/proc/self/fdinfo: process resident GPU memory; shared RAM on integrated GPUs");
            }
            case "FILE": {
                if (file.isBlank()) throw new IOException("Enter an absolute file path");
                if (!file.startsWith("/")) throw new IOException("File source must be absolute");
                String text = Sensors.bounded(sensors.path(file), 1024);
                if (!e.unit.isBlank() || e.multiplier != 1 || e.offset != 0) return numeric(e, Sensors.number(text), e.unit, file);
                return result(clean(text), file);
            }
            case "COMMAND": {
                if (!c.commandsAllowed) throw new IOException("Enable local shell commands in System settings first");
                if (file.isBlank()) throw new IOException("Enter a shell command");
                state.source = "/bin/sh -c: " + file; return result(CommandRunner.run(file), state.source);
            }
            default: throw new IOException("Unknown entry kind: " + e.kind);
        }
    }
    static String clean(String text) {
        String line = text.lines().findFirst().orElse("").replaceAll("[\\p{Cntrl}§]", "").strip();
        return line.substring(0, Math.min(160, line.length()));
    }
    private Value deviceLoad(Config.Entry e, String file) throws IOException {
        String raw = sensors.read(file); java.util.regex.Matcher ratio = LOAD_RATIO.matcher(raw); double n = Sensors.number(raw);
        if (ratio.find()) { double denominator = Double.parseDouble(ratio.group(2)); if (denominator <= 0) throw new IOException("Zero devfreq interval"); n = 100d * Double.parseDouble(ratio.group(1)) / denominator; }
        if (n < 0 || n > 100) throw new IOException("GPU load outside 0..100%");
        return numeric(e, n, "%", file + " (device load)");
    }
    private static final java.util.regex.Pattern LOAD_RATIO = java.util.regex.Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    @Override public void close() { closed = true; timer.shutdownNow(); workers.shutdownNow(); }
}
