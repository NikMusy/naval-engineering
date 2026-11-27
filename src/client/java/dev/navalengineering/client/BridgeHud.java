package dev.navalengineering.client;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;

import java.util.Locale;

final class BridgeHud {
    private static final int INK = 0xffe5eff3;
    private static final int MUTED = 0xff8eacbb;
    private static final int ACCENT = 0xff79dbc8;

    private BridgeHud() {}

    static void render(DrawContext draw, RenderTickCounter counter) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.options.hudHidden || client.currentScreen != null
                || !(client.player.getVehicle() instanceof ShipEntity ship)) return;
        NbtCompound data = ship.telemetry();
        var font = client.textRenderer;
        int x = 11, y = 11;
        int width = Math.min(233, draw.getScaledWindowWidth() - 22);
        if (width < 125) return;

        draw.fill(x, y, x + width, y + 102, 0xdc10232d);
        draw.fill(x, y, x + width, y + 2, ACCENT);
        draw.drawText(font, Text.translatable("naval.hud.bridge"), x + 9, y + 9, ACCENT, false);
        int left = x + 9;
        int right = x + width / 2;
        metric(draw, client, left, y + 24, "speed", number(data, "Knots", 1) + " kn", INK);
        metric(draw, client, right, y + 24, "heading", number(data, "Heading", 0) + "°", INK);
        metric(draw, client, left, y + 40, "heel", number(data, "Heel", 1) + "°", INK);
        metric(draw, client, right, y + 40, "depth",
                data.getDouble("Depth") < 0 ? "—" : number(data, "Depth", 1) + " m", INK);
        metric(draw, client, left, y + 56, "fuel", number(data, "Fuel", 1) + " L", INK);
        metric(draw, client, right, y + 56, "rpm", number(data, "Rpm", 0), INK);
        metric(draw, client, left, y + 72, "flood", number(data, "Flood", 2) + " m³",
                data.getDouble("Flood") >= .5 ? 0xffff866f : INK);
        metric(draw, client, right, y + 72, "heat", number(data, "Heat", 0) + " °C",
                data.getDouble("Heat") >= 95 ? 0xffff866f : INK);
        var coordinates = String.format(Locale.ROOT, "X %.0f   Y %.0f   Z %.0f", ship.getX(), ship.getY(), ship.getZ());
        draw.drawText(font, coordinates, left, y + 89, MUTED, false);
    }

    private static void metric(DrawContext draw, MinecraftClient client, int x, int y, String label, String value, int color) {
        Text text = Text.translatable("naval.hud." + label);
        draw.drawText(client.textRenderer, text, x, y, MUTED, false);
        draw.drawText(client.textRenderer, value, x + client.textRenderer.getWidth(text) + 5, y, color, false);
    }

    private static String number(NbtCompound data, String key, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", data.getDouble(key));
    }
}

