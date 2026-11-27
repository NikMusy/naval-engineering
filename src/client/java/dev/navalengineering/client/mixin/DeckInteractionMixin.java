package dev.navalengineering.client.mixin;

import dev.navalengineering.network.BoardRequest;
import dev.navalengineering.ship.DeckBoarding;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
abstract class DeckInteractionMixin {
    @Shadow public ClientPlayerEntity player;

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void naval$boardHull(CallbackInfo info) {
        if (player == null || player.hasVehicle() || player.isSpectator() || player.isSneaking()
                || !ClientPlayNetworking.canSend(BoardRequest.ID)) return;
        var hit = DeckBoarding.trace(player);
        if (hit == null) return;
        ClientPlayNetworking.send(new BoardRequest(hit.ship().getId()));
        info.cancel();
    }
}

