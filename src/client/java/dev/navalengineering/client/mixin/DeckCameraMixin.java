package dev.navalengineering.client.mixin;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
abstract class DeckCameraMixin {
    @Shadow private float cameraY;
    @Shadow private float lastCameraY;
    @Shadow protected abstract void setPos(Vec3d pos);

    @Inject(method = "update", at = @At("TAIL"))
    private void naval$alignFirstPersonWithDeck(BlockView area, Entity focus, boolean thirdPerson,
                                                boolean inverseView, float tickDelta, CallbackInfo ci) {
        if (thirdPerson || !(focus.getVehicle() instanceof ShipEntity ship)) return;
        float eyeHeight = MathHelper.lerp(tickDelta, lastCameraY, cameraY);
        Vec3d eye = ship.crewEyePosition(focus.getId(), tickDelta, eyeHeight);
        if (eye == null) return;
        setPos(eye);

        // The player already tracks the deck's yaw for steering and movement. Only add its tilt here.
        Camera camera = (Camera) (Object) this;
        var tilt = ship.renderRotation(tickDelta);
        var forward = tilt.transform(new Vector3f(0, 0, 1));
        tilt.rotateY((float) -Math.atan2(forward.x, forward.z));
        tilt.mul(camera.getRotation(), camera.getRotation());
        tilt.transform(camera.getHorizontalPlane());
        tilt.transform(camera.getVerticalPlane());
        tilt.transform(camera.getDiagonalPlane());
    }
}
