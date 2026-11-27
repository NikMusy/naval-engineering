package dev.navalengineering.mixin;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
abstract class DeckDismountMixin {
    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void naval$keepDeckContact(CallbackInfoReturnable<Boolean> result) {
        if (((PlayerEntity) (Object) this).getVehicle() instanceof ShipEntity) result.setReturnValue(false);
    }
}
