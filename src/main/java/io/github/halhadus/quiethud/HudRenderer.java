package io.github.halhadus.quiethud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class HudRenderer {
    public record Bounds(double x, double y, double width, double height) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + width && py < y + height; }
    }
    private static Config cachedConfig;
    private static long cachedRevision = -1, cachedSecond = -1;
    private static Object cachedFont;
    private static HudModel.Frame cachedFrame;
    private static HudModel.Frame frame(Config c, Sampler sampler) {
        var font = Minecraft.getInstance().font; long revision = sampler.revision(), second = System.nanoTime() / 1000000000L;
        if (c != cachedConfig || revision != cachedRevision || second != cachedSecond || font != cachedFont) {
            cachedFrame = HudModel.build(c, sampler, font.lineHeight, font::width);
            cachedConfig = c; cachedRevision = revision; cachedSecond = second; cachedFont = font;
        }
        return cachedFrame;
    }
    private static double scale(Config c, int width, int height, HudModel.Frame frame) {
        double gui = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
        return Math.max(.001, Math.min(c.scale / gui, Math.min(Math.max(1, width - 4d) / frame.width(), Math.max(1, height - 4d) / frame.height())));
    }
    private static Bounds bounds(Config c, HudModel.Frame frame, int width, int height) {
        double scale = scale(c, width, height, frame), w = frame.width() * scale, h = frame.height() * scale;
        double x = c.anchor.endsWith("RIGHT") ? width - w - c.x : c.x, y = c.anchor.startsWith("BOTTOM") ? height - h - c.y : c.y;
        return new Bounds(Math.clamp(x, 0, Math.max(0, width - w)), Math.clamp(y, 0, Math.max(0, height - h)), w, h);
    }
    public static Bounds bounds(Config c, Sampler sampler, int width, int height) { return bounds(c, frame(c, sampler), width, height); }
    public static void draw(GuiGraphicsExtractor g, Config c, Sampler sampler) {
        var font = Minecraft.getInstance().font; HudModel.Frame frame = frame(c, sampler);
        Bounds b = bounds(c, frame, g.guiWidth(), g.guiHeight());
        float s = (float) scale(c, g.guiWidth(), g.guiHeight(), frame);
        g.pose().pushMatrix();
        try {
            g.pose().translate((float) b.x, (float) b.y); g.pose().scale(s, s); int y = 0;
            for (HudModel.Row row : frame.rows()) {
                int x = c.alignment.equals("RIGHT") ? frame.width() - row.width() - 2 : 2;
                if (c.backdrop > 0) g.fill(x - 1, y - 1, x + row.width() + 1, y + font.lineHeight, ((int) (c.backdrop * c.opacity * 255) << 24));
                g.text(font, row.text(), x, y, ((int) (c.opacity * 255) << 24) | row.color(), c.shadow); y += font.lineHeight + c.spacing;
            }
        } finally { g.pose().popMatrix(); }
    }
}
