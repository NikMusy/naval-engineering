package dev.navalengineering.client.mixin;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LivingEntityRenderer.class)
abstract class DeckPlayerRendererMixin {
    @Redirect(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;hasVehicle()Z"))
    private boolean naval$useMountedModel(LivingEntity living) {
        return living.hasVehicle() && !(living.getVehicle() instanceof ShipEntity);
    }
}

