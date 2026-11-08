package io.github.halhadus.quiethud;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

abstract class BaseScreen extends Screen {
    final Screen parent;
    final List<Label> labels = new ArrayList<>();
    final Set<EditBox> invalid = new HashSet<>();
    String error = "";
    record Label(String text, int x, int y) {}
    BaseScreen(String title, Screen parent) { super(Component.literal(title)); this.parent = parent; }
    int left() { return Math.max(12, (width - 580) / 2); }
    int contentWidth() { return width - 2 * left(); }
    Button button(String text, int x, int y, int w, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> action.run()).bounds(x, y, Math.max(16, w), 20).build());
    }
    void toggle(String label, int y, BooleanSupplier getter, Consumer<Boolean> setter) {
        button(label + ": " + (getter.getAsBoolean() ? "ON" : "OFF"), left(), y, contentWidth(), () -> { setter.accept(!getter.getAsBoolean()); rebuildWidgets(); });
    }
    EditBox field(String label, String value, int y, Consumer<String> setter) {
        int x = left(), labelWidth = Math.min(135, contentWidth() / 2);
        labels.add(new Label(font.plainSubstrByWidth(label, labelWidth - 4), x, y + 6));
        EditBox box = new EditBox(font, x + labelWidth, y, contentWidth() - labelWidth, 20, Component.literal(label));
        box.setMaxLength(4096); box.setValue(value); box.setResponder(text -> {
            try { setter.accept(text); invalid.remove(box); box.setTextColor(0xFFE5E7EB); }
            catch (RuntimeException ex) { invalid.add(box); box.setTextColor(0xFFFF7777); }
        });
        return addRenderableWidget(box);
    }
    static double decimal(String text, double min, double max) {
        double n = Double.parseDouble(text); if (!Double.isFinite(n) || n < min || n > max) throw new IllegalArgumentException(); return n;
    }
    static int integer(String text, int min, int max) { int n = Integer.parseInt(text); if (n < min || n > max) throw new IllegalArgumentException(); return n; }
    @Override protected void init() { labels.clear(); invalid.clear(); }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0xF014171D);
        g.centeredText(font, title, width / 2, 12, 0xFFF1F5F9);
        for (Label l : labels) g.text(font, font.plainSubstrByWidth(l.text, width - l.x - 12), l.x, l.y, 0xFFB9C5D4);
        super.extractRenderState(g, mx, my, delta);
        String message = invalid.isEmpty() ? error : "Fix the red fields before saving.";
        if (!QuietHud.saveError.isBlank()) message = QuietHud.saveError;
        if (!message.isBlank()) g.text(font, font.plainSubstrByWidth(message, width - 24), 12, height - 12, 0xFFFF8888);
    }
    void widgets(GuiGraphicsExtractor g, int mx, int my, float delta) { super.extractRenderState(g, mx, my, delta); }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
