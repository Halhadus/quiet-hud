package io.github.halhadus.quiethud;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Sensors {
    public record Source(String category, String name, String path, double multiplier) {
        @Override public String toString() { return name + " — " + path; }
    }
    private static final Pattern NUMBER = Pattern.compile("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)");
    public final Path root;
    private List<DrmClient> drmCache = List.of();
    private long drmCacheTime;
    public Sensors() { this(Path.of("/")); }
    public Sensors(Path root) { this.root = root; }
    Path path(String path) { return root.resolve(path.replaceFirst("^/", "")); }
    String read(String path) throws IOException { return bounded(path(path), 65536).trim(); }
    static String bounded(Path file, int limit) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular readable sensor file: " + file);
        try (java.io.Reader reader = Files.newBufferedReader(file)) {
            char[] buffer = new char[Math.min(limit, 4096)]; StringBuilder text = new StringBuilder(); int count;
            while (text.length() < limit && (count = reader.read(buffer, 0, Math.min(buffer.length, limit - text.length()))) > 0) text.append(buffer, 0, count);
            return text.toString();
        }
    }
    static double number(String text) throws IOException {
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) throw new IOException("No numeric value");
        double n = Double.parseDouble(matcher.group());
        if (!Double.isFinite(n)) throw new IOException("Non-finite sensor value");
        return n;
    }
    String firstLine(String source) throws IOException {
        Path file = path(source);
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular sensor file: " + file);
        try (var reader = Files.newBufferedReader(file)) {
            StringBuilder line = new StringBuilder(); int c;
            while (line.length() < 65536 && (c = reader.read()) >= 0 && c != '\n') line.append((char) c);
            return line.toString();
        }
    }
    boolean online(Source source) {
        Path cpu = path(source.path()).getParent().getParent();
        return !optional(cpu.resolve("online")).equals("0");
    }
    List<Path> list(String dir) {
        try (Stream<Path> stream = Files.list(path(dir))) { return stream.sorted().toList(); }
        catch (IOException | SecurityException ex) { return List.of(); }
    }
    String absolute(Path p) { return "/" + root.relativize(p).toString(); }
    String optional(Path p) { try { return bounded(p, 65536).trim(); } catch (IOException | SecurityException ex) { return ""; } }
    public List<Source> discover() {
        List<Source> found = new ArrayList<>();
        for (Path zone : list("/sys/class/thermal")) {
            if (!zone.getFileName().toString().startsWith("thermal_zone")) continue;
            String type = optional(zone.resolve("type"));
            if (Files.isReadable(zone.resolve("temp"))) found.add(new Source("TEMP", type, absolute(zone.resolve("temp")), .001));
        }
        for (Path hw : list("/sys/class/hwmon")) {
            String name = optional(hw.resolve("name"));
            for (Path p : list(absolute(hw))) {
                String n = p.getFileName().toString();
                if (!n.matches("(?:temp|fan)[0-9]+_input")) continue;
                String label = optional(hw.resolve(n.replace("_input", "_label")));
                found.add(new Source(n.startsWith("fan") ? "FAN" : "TEMP", name + (label.isBlank() ? " " + n : " " + label), absolute(p), n.startsWith("temp") ? .001 : 1));
            }
        }
        for (Path dev : list("/sys/class/devfreq")) {
            String info = (dev.getFileName() + " " + optional(dev.resolve("name")) + " " + real(dev)).toLowerCase(Locale.ROOT);
            if (info.matches(".*(gpu|mali|panthor|panfrost).*")) {
                if (Files.isReadable(dev.resolve("cur_freq"))) found.add(new Source("GPU_CLOCK", info, absolute(dev.resolve("cur_freq")), .000001));
                if (Files.isReadable(dev.resolve("load"))) found.add(new Source("GPU_LOAD", info, absolute(dev.resolve("load")), 1));
            }
        }
        for (Path cpu : list("/sys/devices/system/cpu")) {
            if (cpu.getFileName().toString().matches("cpu[0-9]+") && !optional(cpu.resolve("online")).equals("0") && Files.isReadable(cpu.resolve("cpufreq/scaling_cur_freq")))
                found.add(new Source("CPU_CLOCK", cpu.getFileName().toString(), absolute(cpu.resolve("cpufreq/scaling_cur_freq")), .001));
        }
        for (Path drm : list("/sys/class/drm")) {
            if (!drm.getFileName().toString().matches("card[0-9]+")) continue;
            Path busy = drm.resolve("device/gpu_busy_percent");
            if (Files.isReadable(busy)) found.add(new Source("GPU_LOAD", "Device GPU busy", absolute(busy), 1));
        }
        return List.copyOf(found);
    }
    private String real(Path p) { try { return p.toRealPath().toString(); } catch (IOException e) { return ""; } }
    public record CpuTimes(long total, long idle) {}
    public static CpuTimes cpuTimes(String text) throws IOException {
        String line = text.lines().filter(s -> s.startsWith("cpu ")).findFirst().orElseThrow(() -> new IOException("No aggregate CPU counters"));
        String[] parts = line.trim().split("\\s+"); long total = 0, idle = 0;
        for (int i = 1; i < Math.min(parts.length, 9); i++) { long n = Long.parseLong(parts[i]); total += n; if (i == 4 || i == 5) idle += n; }
        return new CpuTimes(total, idle);
    }
    public static double cpuLoad(CpuTimes old, CpuTimes now) throws IOException {
        if (old == null || now.total <= old.total || now.idle < old.idle) throw new IOException("Waiting for CPU counter delta");
        return Math.clamp(100d * ((now.total - old.total) - (now.idle - old.idle)) / (now.total - old.total), 0, 100);
    }
    public static String ram(String text) throws IOException {
        Map<String, Long> m = new HashMap<>();
        for (String line : text.split("\\R")) { String[] p = line.split(":", 2); if (p.length == 2) m.put(p[0], (long) number(p[1])); }
        if (!m.containsKey("MemTotal") || !m.containsKey("MemAvailable")) throw new IOException("MemTotal/MemAvailable unavailable");
        return String.format(Locale.ROOT, "%.1f / %.1f GiB", (m.get("MemTotal") - m.get("MemAvailable")) / 1048576d, m.get("MemTotal") / 1048576d);
    }
    public record DrmClient(String id, Map<String, Long> engines, long resident, boolean hasMemory, String driver, long frequencyHz) {}
    public static DrmClient parseFdinfo(String text) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\\R")) { String[] p = line.split(":", 2); if (p.length == 2) fields.put(p[0].trim(), p[1].trim()); }
        if (!fields.containsKey("drm-client-id") || !fields.containsKey("drm-driver")) throw new IOException("Not a DRM client");
        Map<String, Long> engines = new HashMap<>(); long resident = 0, frequency = -1; boolean memory = false;
        for (var f : fields.entrySet()) {
            if (f.getKey().startsWith("drm-engine-") && f.getValue().endsWith(" ns")) engines.put(f.getKey(), Long.parseLong(f.getValue().split("\\s+")[0]));
            if (f.getKey().startsWith("drm-curfreq-") && f.getValue().endsWith(" Hz")) frequency = Math.max(frequency, Math.round(number(f.getValue())));
            if (f.getKey().startsWith("drm-resident-") || f.getKey().startsWith(fields.get("drm-driver") + "-resident-")) {
                double unit = f.getValue().contains("GiB") ? 1073741824d : f.getValue().contains("MiB") ? 1048576d : f.getValue().contains("KiB") ? 1024d : 1;
                resident += Math.round(number(f.getValue()) * unit); memory = true;
            }
        }
        return new DrmClient(fields.getOrDefault("drm-pdev", "platform") + ":" + fields.get("drm-driver") + ":" + fields.get("drm-client-id"), Map.copyOf(engines), resident, memory, fields.get("drm-driver"), frequency);
    }
    public synchronized List<DrmClient> drm(String source) throws IOException {
        long now = System.nanoTime();
        if (source.isBlank() && !drmCache.isEmpty() && now - drmCacheTime < 100000000L) return drmCache;
        Map<String, DrmClient> clients = new LinkedHashMap<>();
        List<Path> files = source.isBlank() ? list("/proc/self/fdinfo") : List.of(path(source));
        boolean filterDescriptors = source.isBlank() && Files.isDirectory(path("/proc/self/fd"));
        for (Path file : files) {
            if (filterDescriptors) {
                try {
                    String target = Files.readSymbolicLink(path("/proc/self/fd/" + file.getFileName())).toString();
                    if (!target.startsWith("/dev/dri/")) continue;
                } catch (IOException | SecurityException ex) { continue; }
            }
            String text = optional(file); if (!text.contains("drm-driver:")) continue;
            try { DrmClient client = parseFdinfo(text); clients.putIfAbsent(client.id, client); }
            catch (IOException | NumberFormatException ex) { /* A closing or unsupported client must not hide other clients. */ }
        }
        if (clients.isEmpty()) throw new IOException("No readable DRM fdinfo for Minecraft");
        List<DrmClient> preferred = clients.values().stream().filter(c -> c.driver.matches("panthor|panfrost")).toList();
        List<DrmClient> result = preferred.isEmpty() ? List.copyOf(clients.values()) : preferred;
        if (source.isBlank()) { drmCache = result; drmCacheTime = System.nanoTime(); }
        return result;
    }
}
