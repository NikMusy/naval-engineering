package dev.navalengineering.client;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.network.HelmInput;
import dev.navalengineering.ship.ShipEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class NavalClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(NavalEngineering.SHIP, ShipRenderer::new);
        HudRenderCallback.EVENT.register(BridgeHud::render);
        var ocean = new OceanRenderer();
        WorldRenderEvents.AFTER_TRANSLUCENT.register(ocean::render);
        var audio = new MarineAudio();
        var quality = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.naval_engineering.ocean",
                GLFW.GLFW_KEY_O, "key.categories.naval_engineering"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            audio.update(client);
            while (quality.wasPressed()) {
                client.inGameHud.setOverlayMessage(Text.translatable("naval.ocean.quality", ocean.cycle()), false);
            }
            if (client.player == null || !(client.player.getVehicle() instanceof ShipEntity)
                    || !ClientPlayNetworking.canSend(HelmInput.ID)) return;
            int throttle = 0;
            int steer = 0;
            if (client.currentScreen == null) {
                throttle = (client.options.forwardKey.isPressed() ? 1 : 0)
                        - (client.options.backKey.isPressed() ? 1 : 0);
                steer = (client.options.rightKey.isPressed() ? 1 : 0)
                        - (client.options.leftKey.isPressed() ? 1 : 0);
            }
            ClientPlayNetworking.send(new HelmInput((byte) throttle, (byte) steer));
        });
    }
}

