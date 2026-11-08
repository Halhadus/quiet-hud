package io.github.halhadus.quiethud;

import java.util.List;

final class SourceScreen extends BaseScreen {
    private final Config.Entry entry;
    private int page;
    private long revision;
    SourceScreen(EntryScreen parent, Config.Entry entry) { super("QuietHUD · Sensors", parent); this.entry = entry; }
    @Override protected void init() {
        super.init(); revision = QuietHud.sampler.sourceRevision; String category = switch (entry.kind) { case "CPU_TEMP", "GPU_TEMP" -> "TEMP"; case "FILE" -> ""; default -> entry.kind; };
        List<Sensors.Source> sources = QuietHud.sampler.sources.stream().filter(s -> category.isBlank() || s.category().equals(category)).toList();
        int count = Math.max(1, (height - 120) / 25), maxPage = Math.max(0, (sources.size() - 1) / count); page = Math.clamp(page, 0, maxPage);
        button("Auto", left(), 35, contentWidth() / 2 - 2, () -> { entry.source = ""; minecraft.gui.setScreen(parent); });
        button("Rescan", left() + contentWidth() / 2, 35, contentWidth() / 2, () -> QuietHud.sampler.rescan());
        for (int i = page * count, r = 0; i < Math.min(sources.size(), (page + 1) * count); i++, r++) {
            Sensors.Source s = sources.get(i);
            var b = button(font.plainSubstrByWidth(s.toString(), contentWidth() - 12), left(), 62 + r * 25, contentWidth(), () -> {
                entry.source = s.path();
                if (entry.kind.equals("FILE")) { entry.multiplier = s.multiplier(); entry.unit = s.category().equals("TEMP") ? "°C" : s.category().equals("FAN") ? "RPM" : s.category().endsWith("CLOCK") ? "MHz" : "%"; }
                minecraft.gui.setScreen(parent);
            });
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(s.toString())));
        }
        if (sources.isEmpty()) labels.add(new Label("No matching sensors. You can enter a path manually.", left(), 68));
        int w = contentWidth(); button("‹", left(), height - 57, 32, () -> { page--; rebuildWidgets(); });
        button((page + 1) + " / " + (maxPage + 1), left() + 35, height - 57, w - 70, () -> rebuildWidgets());
        button("›", left() + w - 32, height - 57, 32, () -> { page++; rebuildWidgets(); });
        button("Back", left(), height - 30, w, this::onClose);
    }
    @Override public void tick() { if (revision != QuietHud.sampler.sourceRevision) rebuildWidgets(); }

}
