package io.github.halhadus.quiethud;

import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;

final class EntryScreen extends BaseScreen {
    private final SettingsScreen owner;
    private final Config.Entry original, draft;
    private int tab;
    private final boolean added;
    private long nextPreview;
    private String previewJson = "";
    private net.minecraft.client.gui.components.Button sourceStatus;
    private static final String[] KINDS = {"GPU_LOAD", "GPU_TEMP", "GPU_CLOCK", "CPU_LOAD", "CPU_TEMP", "CPU_CLOCK", "RAM", "VRAM", "FPS", "CLOCK", "FAN", "FILE", "COMMAND"};
    EntryScreen(SettingsScreen owner, Config.Entry entry) { this(owner, entry, false); }
    EntryScreen(SettingsScreen owner, Config.Entry entry, boolean added) {
        super("QuietHUD · Entry", owner); this.owner = owner; this.added = added; original = entry; draft = Config.JSON.fromJson(Config.JSON.toJson(entry), Config.Entry.class);
    }
    @Override protected void init() {
        super.init(); sourceStatus = null; int x = left(), w = contentWidth();
        String[] names = {"Entry", "Source", "Format"};
        for (int i = 0; i < 3; i++) { int t = i; button((i == tab ? "• " : "") + names[i], x + i * w / 3, 32, w / 3 - 2, () -> { if (invalid.isEmpty()) { tab = t; rebuildWidgets(); } }); }
        if (tab == 0) {
            field("Label", draft.label, 60, s -> draft.label = s);
            button("Kind: " + draft.kind, x, 84, w, () -> { draft.kind = SettingsScreen.next(draft.kind, KINDS); draft.source = ""; draft.multiplier = 1; draft.offset = 0; if (draft.kind.equals("COMMAND")) draft.interval = Math.max(1000, draft.interval); rebuildWidgets(); });
            toggle("Visible", 108, () -> draft.enabled, b -> draft.enabled = b);
            field("Refresh interval (ms)", "" + draft.interval, 132, s -> draft.interval = integer(s, draft.kind.equals("COMMAND") ? 1000 : 100, 3600000));
        } else if (tab == 1) {
            field(draft.kind.equals("COMMAND") ? "Shell command" : "Path (empty = Auto)", draft.source, 60, s -> draft.source = s);
            button("Choose detected sensor…", x, 84, w, () -> minecraft.gui.setScreen(new SourceScreen(this, draft)));
            String hint = switch (draft.kind) {
                case "CPU_TEMP", "GPU_TEMP" -> "Temperature paths use millidegrees Celsius (÷1000).";
                case "CPU_CLOCK" -> "Manual CPU clock paths use kHz (÷1000).";
                case "GPU_CLOCK" -> "GPU clock paths use Hz (÷1000000).";
                case "GPU_LOAD" -> "Auto: device load or process DRM time. File: percent.";
                case "VRAM" -> "Auto: process resident DRM memory. File: bytes.";
                case "FILE" -> "Text file; set unit / multiplier to format a numeric value.";
                case "COMMAND" -> "Local /bin/sh -c; first line; 1500 ms; max 4096 bytes.";
                default -> "Auto uses the default system / game source.";
            };
            labels.add(new Label(font.plainSubstrByWidth(hint, w), x, 115));
            button("Use Auto", x, 130, w, () -> { draft.source = ""; rebuildWidgets(); }).active = !Set.of("FILE", "COMMAND").contains(draft.kind);
            sourceStatus = button("Waiting for source selection…", x, 154, w, () -> minecraft.keyboardHandler.setClipboard(QuietHud.sampler.value(draft).source()));
        } else {
            field("Color (RRGGBB)", draft.color, 60, s -> { if (!s.matches("[0-9a-fA-F]{6}")) throw new IllegalArgumentException(); draft.color = s; });
            field("Multiplier", "" + draft.multiplier, 84, s -> draft.multiplier = decimal(s, -1e12, 1e12));
            field("Offset", "" + draft.offset, 108, s -> draft.offset = decimal(s, -1e12, 1e12));
            field("Decimal places (0–4)", "" + draft.precision, 132, s -> draft.precision = integer(s, 0, 4));
            field("Unit (custom FILE)", draft.unit, 156, s -> draft.unit = s);
        }
        button("Save entry", x, height - 30, w / 2 - 2, () -> {
            if (!invalid.isEmpty()) return;
            if (!draft.source.isBlank() && !Set.of("COMMAND", "FPS", "CLOCK").contains(draft.kind) && !draft.source.startsWith("/")) { error = "Use an absolute file path."; return; }
            owner.draft.entries.set(owner.draft.entries.indexOf(original), draft); QuietHud.sampler.configure(owner.draft); minecraft.gui.setScreen(owner);
        });
        button("Cancel", x + w / 2, height - 30, w / 2, this::onClose);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(font, font.plainSubstrByWidth("Current: " + QuietHud.sampler.value(draft).text(), contentWidth()), left(), height - 48, 0xFF9ADAC3);
    }
    @Override public void tick() {
        if (System.nanoTime() < nextPreview) return;
        nextPreview = System.nanoTime() + 200000000L;
        if (invalid.isEmpty()) {
            String json = Config.JSON.toJson(draft);
            if (!json.equals(previewJson)) {
                previewJson = json; Config preview = owner.draft.copy();
                preview.entries.set(owner.draft.entries.indexOf(original), Config.JSON.fromJson(json, Config.Entry.class)); QuietHud.sampler.configure(preview);
            }
        }
        if (sourceStatus != null) {
            Sampler.Value value = QuietHud.sampler.value(draft);
            String text = (draft.source.isBlank() ? "Auto → " : "Manual → ") + value.source();
            sourceStatus.setMessage(net.minecraft.network.chat.Component.literal(font.plainSubstrByWidth(text, contentWidth() - 12)));
            sourceStatus.setTooltip(net.minecraft.client.gui.components.Tooltip.create(net.minecraft.network.chat.Component.literal(text + "\n" + value.error() + "\nClick to copy the selected source.")));
        }
    }
    @Override public void onClose() { if (added) owner.draft.entries.remove(original); QuietHud.sampler.configure(owner.draft); super.onClose(); }

}
