package io.github.halhadus.quiethud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SettingsScreen extends BaseScreen {
    final Config draft;
    int tab, page;
    private final List<Runnable> liveUpdates = new ArrayList<>();
    private long nextPreview;
    private String previewJson = "";
    public SettingsScreen(Screen parent) { super("QuietHUD", parent); draft = QuietHud.config.copy(); }
    @Override protected void init() {
        super.init(); liveUpdates.clear();
        String[] tabs = {"Entries", "Appearance", "System", "Diagnostics"}; int x = left(), w = contentWidth(), tw = w / 4;
        for (int i = 0; i < tabs.length; i++) { final int t = i; button((tab == i ? "• " : "") + tabs[i], x + i * tw, 30, tw - 2, () -> { if (!invalid.isEmpty()) return; tab = t; page = 0; rebuildWidgets(); }); }
        if (tab == 0) entries(); else if (tab == 1) appearance(); else if (tab == 2) system(); else diagnostics();
        button("Save & close", x, height - 30, w / 2 - 2, () -> { if (invalid.isEmpty()) { QuietHud.apply(draft); minecraft.gui.setScreen(parent); } });
        button("Cancel", x + w / 2, height - 30, w / 2, () -> { QuietHud.sampler.configure(QuietHud.config); minecraft.gui.setScreen(parent); });
    }
    private void entries() {
        int x = left(), w = contentWidth(), count = Math.max(1, (height - 126) / 25), maxPage = Math.max(0, (draft.entries.size() - 1) / count); page = Math.clamp(page, 0, maxPage);
        for (int i = page * count, r = 0; i < Math.min(draft.entries.size(), (page + 1) * count); i++, r++) {
            int index = i, y = 58 + r * 25; Config.Entry e = draft.entries.get(i);
            button(e.enabled ? "✓" : "—", x, y, 24, () -> { e.enabled = !e.enabled; rebuildWidgets(); });
            var row = button("", x + 27, y, w - 111, () -> minecraft.gui.setScreen(new EntryScreen(this, e)));
            liveUpdates.add(() -> {
                Sampler.Value value = QuietHud.sampler.value(e);
                String status = !HudModel.visible(draft, e, value) && e.enabled ? "hidden" : value.text();
                row.setMessage(Component.literal(font.plainSubstrByWidth(e.label + " · " + status, w - 135)));
                row.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal((e.source.isBlank() ? "Auto → " : "Manual → ") + value.source() + (value.error().isBlank() ? "" : "\n" + value.error()))));
            });
            button("↑", x + w - 81, y, 24, () -> { if (index > 0) { Collections.swap(draft.entries, index, index - 1); rebuildWidgets(); } });
            button("↓", x + w - 54, y, 24, () -> { if (index + 1 < draft.entries.size()) { Collections.swap(draft.entries, index, index + 1); rebuildWidgets(); } });
            button("×", x + w - 27, y, 27, () -> { draft.entries.remove(index); rebuildWidgets(); });
        }
        int y = height - 59;
        button("‹", x, y, 28, () -> { page--; rebuildWidgets(); });
        button("›", x + 31, y, 28, () -> { page++; rebuildWidgets(); });
        button("+ Entry", x + 64, y, 82, () -> { if (draft.entries.size() < 64) { Config.Entry e = new Config.Entry(); draft.entries.add(e); minecraft.gui.setScreen(new EntryScreen(this, e, true)); } });
        button("Drag HUD", x + 150, y, w - 150, () -> minecraft.gui.setScreen(new LayoutScreen(this, draft)));
    }
    private void appearance() {
        field("HUD scale (0.5–4)", "" + draft.scale, 57, s -> draft.scale = decimal(s, .5, 4));
        field("Text opacity (0.05–1)", "" + draft.opacity, 80, s -> draft.opacity = decimal(s, .05, 1));
        field("Text backdrop (0–1)", "" + draft.backdrop, 103, s -> draft.backdrop = decimal(s, 0, 1));
        field("Line spacing (0–20)", "" + draft.spacing, 126, s -> draft.spacing = integer(s, 0, 20));
        int y = 149;
        button("Anchor: " + draft.anchor, left(), y, contentWidth() / 2 - 2, () -> { draft.anchor = next(draft.anchor, "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"); rebuildWidgets(); });
        button("Align: " + draft.alignment, left() + contentWidth() / 2, y, contentWidth() / 2, () -> { draft.alignment = next(draft.alignment, "LEFT", "RIGHT"); rebuildWidgets(); });
        toggle("Text shadow", 172, () -> draft.shadow, b -> draft.shadow = b);
    }
    private void system() {
        // Two compact pages keep controls accessible at Minecraft's minimum GUI size.
        if (page == 0) {
            toggle("HUD enabled", 57, () -> draft.enabled, b -> draft.enabled = b);
            toggle("Pause sampling when hidden", 80, () -> draft.pauseHidden, b -> draft.pauseHidden = b);
            toggle("Hide unavailable Auto sensors", 103, () -> draft.hideUnavailable, b -> draft.hideUnavailable = b);
            toggle("Allow local shell commands", 126, () -> draft.commandsAllowed, b -> draft.commandsAllowed = b);
            labels.add(new Label("Commands run as your local user; 1500 ms timeout.", left(), 155));
        } else {
            field("Clock pattern", draft.clockFormat, 57, s -> { java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern(s)); draft.clockFormat = s; });
            field("CPU cores (e.g. 4,5)", draft.cpuCores, 80, s -> { if (!s.isBlank()) for (String n : s.split(",", -1)) integer(n.trim(), 0, 4095); draft.cpuCores = s; });
            button("CPU clock: " + draft.cpuMode, left(), 103, contentWidth(), () -> { draft.cpuMode = next(draft.cpuMode, "MAX", "AVERAGE"); rebuildWidgets(); });
            field("X offset (GUI units)", "" + draft.x, 126, s -> draft.x = decimal(s, 0, 100000));
            field("Y offset (GUI units)", "" + draft.y, 149, s -> draft.y = decimal(s, 0, 100000));
        }
        button(page == 0 ? "Clock / CPU / offsets ›" : "‹ General", left(), height - 59, contentWidth(), () -> { if (invalid.isEmpty()) { page = 1 - page; rebuildWidgets(); } });
    }
    private void diagnostics() {
        page = Math.clamp(page, 0, Math.max(0, draft.entries.size() - 1));
        button("‹", left(), height - 59, 28, () -> { page--; rebuildWidgets(); });
        button("›", left() + 31, height - 59, 28, () -> { page++; rebuildWidgets(); });
        button("Copy details", left() + 63, height - 59, 100, () -> minecraft.keyboardHandler.setClipboard(details()));
        button("Rescan", left() + 167, height - 59, contentWidth() - 167, () -> QuietHud.sampler.rescan());
    }
    static String next(String current, String... values) { for (int i = 0; i < values.length; i++) if (values[i].equals(current)) return values[(i + 1) % values.length]; return values[0]; }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        if (tab == 3) {
            String text = details();
            int y = 58;
            for (String line : text.split("\n")) for (var wrapped : font.split(Component.literal(line), contentWidth())) { if (y > height - 75) break; g.text(font, wrapped, left(), y, 0xFFCED7E3); y += 11; }
        }
    }
    private String details() {
        String text = "Detected sensors: " + QuietHud.sampler.sources.size();
        if (!draft.entries.isEmpty()) {
            Config.Entry e = draft.entries.get(Math.min(page, draft.entries.size() - 1)); Sampler.Value v = QuietHud.sampler.value(e);
            text += "\n" + (page + 1) + "/" + draft.entries.size() + " · " + e.label + " [" + e.kind + "]\nValue: " + v.text()
                + "\nStatus: " + (v.error().isBlank() ? "OK" : v.error()) + "\n" + (e.source.isBlank() ? "Auto selected: " : "Manual: ") + v.source();
        }
        return text;
    }
    @Override public void tick() {
        if (System.nanoTime() < nextPreview) return;
        nextPreview = System.nanoTime() + 200000000L;
        if (invalid.isEmpty()) {
            String json = Config.JSON.toJson(draft);
            if (!json.equals(previewJson)) { previewJson = json; QuietHud.sampler.configure(draft); }
        }
        liveUpdates.forEach(Runnable::run);
    }
    @Override public void onClose() { QuietHud.sampler.configure(QuietHud.config); super.onClose(); }
}
