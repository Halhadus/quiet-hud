package io.github.halhadus.quiethud;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class SodiumIntegration implements ConfigEntryPoint {
    @Override public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions().addPage(builder.createOptionPage().setName(Component.literal("QuietHUD"))
            .addOption(builder.createExternalButtonOption(net.minecraft.resources.Identifier.fromNamespaceAndPath("quiethud", "settings"))
                .setName(Component.translatable("quiethud.open_settings")).setTooltip(Component.translatable("quiethud.open_settings.tooltip"))
                .setScreenConsumer(parent -> Minecraft.getInstance().gui.setScreen(new SettingsScreen(parent)))));
    }
}
