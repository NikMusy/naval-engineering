package dev.navalengineering.client;

import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.model.BakedModelManager;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

final class ShipRenderer extends EntityRenderer<ShipEntity> {
    private final net.minecraft.client.render.block.BlockRenderManager blocks;

    ShipRenderer(EntityRendererFactory.Context context) {
        super(context);
        blocks = context.getBlockRenderManager();
        shadowRadius = 0;
    }

    @Override
    public void render(ShipEntity ship, float yaw, float tickDelta, MatrixStack matrices,
                       VertexConsumerProvider consumers, int light) {
        var blueprint = ship.blueprint();
        if (blueprint == null) return;
        matrices.push();
        matrices.multiply(ship.renderRotation(tickDelta));
        if (dispatcher.getSquaredDistanceToCamera(ship) > 36 * 36) {
            for (int i : blueprint.hull().exposed()) renderCell(ship, matrices, consumers, light, i);
        } else {
            for (int i = 0; i < blueprint.states().size(); i++) renderCell(ship, matrices, consumers, light, i);
        }
        matrices.pop();
        super.render(ship, yaw, tickDelta, matrices, consumers, light);
    }

    private void renderCell(ShipEntity ship, MatrixStack matrices, VertexConsumerProvider consumers, int light, int i) {
        if (ship.isCellDamaged(i)) return;
        var blueprint = ship.blueprint();
        var p = blueprint.hull().blocks().get(i).position();
        matrices.push();
        matrices.translate(p.x() - .5, p.y() - .5, p.z() - .5);
        blocks.renderBlockAsEntity(blueprint.states().get(i), matrices, consumers, light,
                net.minecraft.client.render.OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }

    @Override
    public Identifier getTexture(ShipEntity ship) {
        return SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE;
    }
}

