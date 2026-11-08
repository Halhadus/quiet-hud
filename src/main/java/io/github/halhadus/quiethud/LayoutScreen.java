package io.github.halhadus.quiethud;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

final class LayoutScreen extends BaseScreen {
    private final Config config;
    private boolean dragging;
    private double dx, dy;
    LayoutScreen(SettingsScreen parent, Config config) { super("QuietHUD · Position", parent); this.config = config; }
    @Override protected void init() {
        super.init(); button("Done", width / 2 - 50, height - 30, 100, this::onClose);
        QuietHud.sampler.configure(config);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x90303030);
        g.centeredText(font, Component.literal("Drag the HUD as one block · edges snap · Esc / Done"), width / 2, 12, 0xFFFFFFFF);
        HudRenderer.draw(g, config, QuietHud.sampler);
        var b = HudRenderer.bounds(config, QuietHud.sampler, width, height);
        g.fill((int) b.x() - 2, (int) b.y() - 2, (int) (b.x() + b.width()) + 2, (int) b.y() - 1, 0xFFA7F3D0);
        widgets(g, mx, my, delta);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (super.mouseClicked(event, twice)) return true;
        var b = HudRenderer.bounds(config, QuietHud.sampler, width, height);
        if (event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && b.contains(event.x(), event.y())) { // SDL BUTTON_LEFT = 1 in Minecraft 26.3.
            dragging = true; dx = event.x() - b.x(); dy = event.y() - b.y(); return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double movementX, double movementY) {
        if (!dragging) return super.mouseDragged(event, movementX, movementY);
        var b = HudRenderer.bounds(config, QuietHud.sampler, width, height);
        double x = Math.clamp(event.x() - dx, 0, Math.max(0, width - b.width())), y = Math.clamp(event.y() - dy, 0, Math.max(0, height - b.height()));
        if (x < 6) x = 0; if (y < 6) y = 0;
        if (width - x - b.width() < 6) x = width - b.width(); if (height - y - b.height() < 6) y = height - b.height();
        config.x = config.anchor.endsWith("RIGHT") ? width - x - b.width() : x;
        config.y = config.anchor.startsWith("BOTTOM") ? height - y - b.height() : y;
        return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { dragging = false; return super.mouseReleased(event); }
}
