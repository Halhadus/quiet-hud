package io.github.halhadus.quiethud;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class QuietHud implements ClientModInitializer {
    public static Config config;
    public static Sampler sampler;
    public static volatile String saveError = "";
    private static volatile boolean samplingVisible;
    private static Path configFile;
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "QuietHUD config"); t.setDaemon(true); return t; });
    @Override public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("quiethud.json"); config = Config.load(configFile);
        sampler = new Sampler(config, () -> samplingVisible);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            sampler.fps = client.getFps();
            samplingVisible = (config.enabled && client.level != null && !client.gui.hud.isHidden()) || client.gui.screen() instanceof SettingsScreen || client.gui.screen() instanceof EntryScreen || client.gui.screen() instanceof LayoutScreen || client.gui.screen() instanceof SourceScreen;
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("quiethud", "metrics"), (graphics, delta) -> {
            if (config.enabled && Minecraft.getInstance().level != null && !(Minecraft.getInstance().gui.screen() instanceof LayoutScreen)) HudRenderer.draw(graphics, config, sampler);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { sampler.close(); SAVER.shutdown(); try { SAVER.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); } });
    }
    public static void apply(Config next) {
        next.normalize(); config = next.copy(); sampler.configure(config); Config snapshot = config.copy();
        SAVER.submit(() -> { try { snapshot.save(configFile); saveError = ""; } catch (Exception ex) { saveError = "Could not save config: " + ex.getMessage(); System.err.println("[QuietHUD] " + saveError); } });
    }
}
