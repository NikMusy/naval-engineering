package dev.navalengineering.client.mixin;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
abstract class DeckWeatherMixin {
    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
    private void naval$coverPrecipitation(LightmapTextureManager light, float tickDelta,
                                          double x, double y, double z, CallbackInfo info) {
        var camera = MinecraftClient.getInstance().getCameraEntity();
        if (camera == null || !(camera.getVehicle() instanceof ShipEntity ship)) return;
        var bounds = ship.getVisibilityBoundingBox();
        if (!bounds.contains(x, y, z)) return;
        Vec3d below = ship.toLocal(new Vec3d(x, y, z));
        Vector3f up = ship.renderRotation(1).transformInverse(new Vector3f(0, .25f, 0));
        for (double above = y + .25; above < bounds.maxY; above += .25) {
            below = below.add(up.x, up.y, up.z);
            if (ship.solidForCrew(MathHelper.floor(below.x + .5), MathHelper.floor(below.y + .5),
                    MathHelper.floor(below.z + .5))) {
                info.cancel();
                return;
            }
        }
    }
}
