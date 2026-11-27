package dev.navalengineering.client;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.network.CrewInput;
import dev.navalengineering.ship.ShipEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

public final class NavalClient implements ClientModInitializer {
    private ShipEntity vessel;
    private KeyBinding helm;
    private float previousDeckYaw;
    private int sequence;

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(NavalEngineering.SHIP, ShipRenderer::new);
        HudRenderCallback.EVENT.register(BridgeHud::render);
        var ocean = new OceanRenderer();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ocean::render);
        var audio = new MarineAudio();
        var quality = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naval_engineering.ocean",
                GLFW.GLFW_KEY_O, "key.categories.naval_engineering"));
        helm = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naval_engineering.helm",
                GLFW.GLFW_KEY_R, "key.categories.naval_engineering"));
        ClientTickEvents.START_WORLD_TICK.register(world -> submitControls(MinecraftClient.getInstance()));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            audio.update(client);
            while (quality.wasPressed()) {
                client.inGameHud.setOverlayMessage(Text.translatable("naval.ocean.quality", ocean.cycle()), false);
            }
        });
    }

    private void submitControls(MinecraftClient client) {
        boolean switchHelm = false;
        while (helm.wasPressed()) switchHelm = true;
        if (client.player == null || !(client.player.getVehicle() instanceof ShipEntity ship)
                || !ClientPlayNetworking.canSend(CrewInput.ID)) {
            vessel = null;
            return;
        }

        float yaw = ship.deckYaw();
        if (ship != vessel) {
            vessel = ship;
            sequence = 0;
            client.inGameHud.setOverlayMessage(Text.translatable("naval.hud.boarded"), false);
        } else {
            client.player.setYaw(client.player.getYaw() + MathHelper.wrapDegrees(yaw - previousDeckYaw));
        }
        previousDeckYaw = yaw;

        byte forward = 0, strafe = 0, flags = 0;
        if (client.currentScreen == null) {
            var o = client.options;
            forward = (byte) ((o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0));
            strafe = (byte) ((o.rightKey.isPressed() ? 1 : 0) - (o.leftKey.isPressed() ? 1 : 0));
            if (o.jumpKey.isPressed()) flags |= CrewInput.JUMP;
            if (o.sprintKey.isPressed()) flags |= CrewInput.SPRINT;
            if (o.sneakKey.isPressed()) flags |= CrewInput.SNEAK;
            if (switchHelm) flags |= CrewInput.HELM;
        }

        short localYaw = (short) Math.round(MathHelper.wrapDegrees(client.player.getYaw() - yaw) * (32768.0 / 180));
        var input = new CrewInput(ship.getId(), ++sequence, strafe, forward, localYaw, flags);
        ship.predictCrewInput(client.player.getId(), input);
        ClientPlayNetworking.send(input);
    }
}

