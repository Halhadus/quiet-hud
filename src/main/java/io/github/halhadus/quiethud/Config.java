package io.github.halhadus.quiethud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Config {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int CURRENT_SCHEMA = 2;
    public int schema = CURRENT_SCHEMA;
    public boolean enabled = true, shadow = true, pauseHidden = true, hideUnavailable = true, commandsAllowed = false;
    public double x = 8, y = 8, scale = 2, opacity = 1, backdrop = 0;
    public int spacing = 2;
    public String anchor = "TOP_LEFT", alignment = "LEFT", cpuMode = "MAX", cpuCores = "", clockFormat = "HH:mm:ss";
    public List<Entry> entries = defaults();
    public static final class Entry {
        public String id = UUID.randomUUID().toString(), kind = "FILE", label = "Custom", source = "", unit = "", color = "FFFFFF";
        public boolean enabled = true;
        public int interval = 1000, precision = 0;
        public double multiplier = 1, offset = 0;
        public Entry() {}
        public Entry(String kind, String label, String color, int interval) { this.kind = kind; this.label = label; this.color = color; this.interval = interval; }
    }
    private static final Set<String> AUTO_KINDS = Set.of("GPU_LOAD", "GPU_TEMP", "GPU_CLOCK", "CPU_LOAD", "CPU_TEMP", "CPU_CLOCK", "RAM", "VRAM", "FAN");
    public static boolean automaticSensor(Entry e) {
        return e.source.isBlank() && AUTO_KINDS.contains(e.kind);
    }
    public static List<Entry> defaults() {
        List<Entry> rows = new ArrayList<>();
        rows.add(new Entry("GPU_LOAD", "GPU", "40FF00", 500));
        rows.add(new Entry("GPU_TEMP", "GPU Temp", "40FF00", 2000));
        rows.add(new Entry("GPU_CLOCK", "GPU Clock", "40FF00", 1000));
        rows.add(new Entry("CPU_LOAD", "CPU", "00CCFF", 1000));
        rows.add(new Entry("CPU_TEMP", "CPU Temp", "00CCFF", 2000));
        rows.add(new Entry("CPU_CLOCK", "CPU Clock", "00CCFF", 1000));
        rows.add(new Entry("RAM", "RAM", "FF00FF", 1000));
        rows.add(new Entry("VRAM", "GPU memory (process)", "FF00FF", 1000));
        rows.add(new Entry("FPS", "FPS", "FFFFFF", 500));
        rows.add(new Entry("CLOCK", "Time", "FFFFFF", 1000));
        rows.add(new Entry("FAN", "Fan", "FFFFFF", 2000));
        return rows;
    }
    public Config copy() { return JSON.fromJson(JSON.toJson(this), Config.class); }
    public void normalize() {
        if (schema > CURRENT_SCHEMA) throw new IllegalArgumentException("Configuration schema is newer than this mod");
        schema = CURRENT_SCHEMA;
        x = finite(x, 8); y = finite(y, 8); scale = Math.clamp(finite(scale, 2), .5, 4);
        opacity = Math.clamp(finite(opacity, 1), .05, 1); backdrop = Math.clamp(finite(backdrop, 0), 0, 1); spacing = Math.clamp(spacing, 0, 20);
        if (!Set.of("TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT").contains(Objects.toString(anchor, ""))) anchor = "TOP_LEFT";
        if (!Set.of("LEFT", "RIGHT").contains(Objects.toString(alignment, ""))) alignment = "LEFT";
        if (!Set.of("MAX", "AVERAGE").contains(Objects.toString(cpuMode, ""))) cpuMode = "MAX";
        try { java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(clockFormat)); }
        catch (RuntimeException ex) { clockFormat = "HH:mm:ss"; }
        if (cpuCores == null || !cpuCores.matches("(?:\\s*\\d+\\s*(?:,\\s*\\d+\\s*)*)?")) cpuCores = "";
        if (entries == null) entries = defaults();
        entries.removeIf(Objects::isNull);
        if (entries.size() > 64) entries = new ArrayList<>(entries.subList(0, 64));
        Set<String> ids = new HashSet<>();
        for (Entry e : entries) {
            if (e.id == null || !ids.add(e.id)) { e.id = UUID.randomUUID().toString(); ids.add(e.id); }
            if (e.kind == null) e.kind = "FILE";
            if (e.label == null) e.label = "";
            if (e.source == null) e.source = "";
            if (e.unit == null) e.unit = "";
            if (e.color == null || !e.color.matches("[0-9a-fA-F]{6}")) e.color = "FFFFFF";
            e.interval = Math.clamp(e.interval, e.kind.equals("COMMAND") ? 1000 : 100, 3600000);
            e.precision = Math.clamp(e.precision, 0, 4); e.multiplier = finite(e.multiplier, 1); e.offset = finite(e.offset, 0);
        }
    }
    private static double finite(double n, double fallback) { return Double.isFinite(n) ? n : fallback; }
    public static Config load(Path file) {
        if (!Files.exists(file)) return new Config();
        try { Config c = JSON.fromJson(Files.readString(file), Config.class); if (c == null) throw new IOException("Empty config"); c.normalize(); return c; }
        catch (Exception ex) {
            System.err.println("[QuietHUD] Invalid config: " + ex.getMessage());
            try { Files.copy(file, file.resolveSibling("quiethud.broken-" + System.currentTimeMillis() + ".json")); }
            catch (IOException backup) { System.err.println("[QuietHUD] Could not back up config: " + backup.getMessage()); }
            return new Config();
        }
    }
    public void save(Path file) throws IOException {
        normalize(); Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "quiethud-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(this), StandardCharsets.UTF_8);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
