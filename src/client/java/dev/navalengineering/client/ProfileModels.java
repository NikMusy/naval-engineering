package dev.navalengineering.client;

import dev.navalengineering.construction.ProfileBlock;
import dev.navalengineering.simulation.HullGeometry;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

final class ProfileModels {
    private ProfileModels() {}

    static void register() {
        ModelLoadingPlugin.register(plugin -> plugin.modifyModelAfterBake().register((model, context) -> {
            var resource = context.topLevelId();
            if (model == null || resource == null || !(Registries.BLOCK.get(resource.id()) instanceof ProfileBlock block)) {
                return model;
            }
            int direction = switch (resource.variant()) {
                case "facing=east" -> 1;
                case "facing=south" -> 2;
                case "facing=west" -> 3;
                default -> 0;
            };
            return new PanelModel(block, model, direction);
        }));
    }

    private record PanelModel(BakedModel base, int bakedDirection, List<List<BakedQuad>> variants) implements BakedModel {
        PanelModel(ProfileBlock block, BakedModel base, int defaultDirection) {
            this(base, defaultDirection, makeVariants(block, base));
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction face, Random random) {
            if (face != null) return List.of();
            return variants.get(state != null && state.getBlock() instanceof ProfileBlock
                    ? ProfileBlock.turn(state) : bakedDirection);
        }

        @Override public boolean useAmbientOcclusion() { return false; }
        @Override public boolean hasDepth() { return true; }
        @Override public boolean isSideLit() { return true; }
        @Override public boolean isBuiltin() { return false; }
        @Override public Sprite getParticleSprite() { return base.getParticleSprite(); }
        @Override public ModelTransformation getTransformation() { return base.getTransformation(); }
        @Override public ModelOverrideList getOverrides() { return base.getOverrides(); }
    }

    private static List<List<BakedQuad>> makeVariants(ProfileBlock block, BakedModel base) {
        var result = new ArrayList<List<BakedQuad>>(4);
        for (int direction = 0; direction < 4; direction++) {
            var mesh = new ArrayList<BakedQuad>();
            for (var side : block.geometry(direction).faces()) {
                var n = side.normal();
                var face = Direction.getFacing(n.x(), n.y(), n.z());
                var candidates = base.getQuads(null, face, Random.create(0));
                var sprite = candidates.isEmpty() ? base.getParticleSprite() : candidates.getFirst().getSprite();
                var points = side.vertices();
                if (points.size() == 4) {
                    mesh.add(quad(points.get(0), points.get(1), points.get(2), points.get(3), n, face, sprite));
                } else {
                    for (int i = 1; i < points.size() - 1; i++) {
                        mesh.add(quad(points.getFirst(), points.get(i), points.get(i + 1), points.get(i + 1), n, face, sprite));
                    }
                }
            }
            result.add(List.copyOf(mesh));
        }
        return List.copyOf(result);
    }

    private static BakedQuad quad(HullGeometry.Point a, HullGeometry.Point b, HullGeometry.Point c,
                                  HullGeometry.Point d, HullGeometry.Point normal, Direction face, Sprite sprite) {
        int[] data = new int[32];
        encode(data, 0, a, normal, face, sprite);
        encode(data, 8, b, normal, face, sprite);
        encode(data, 16, c, normal, face, sprite);
        encode(data, 24, d, normal, face, sprite);
        return new BakedQuad(data, -1, face, sprite, true);
    }

    private static void encode(int[] data, int i, HullGeometry.Point p, HullGeometry.Point n, Direction face, Sprite sprite) {
        double u = face.getAxis() == Direction.Axis.X ? p.z() : p.x();
        double v = face.getAxis() == Direction.Axis.Y ? p.z() : -p.y();
        data[i] = Float.floatToRawIntBits((float) (p.x() + .5));
        data[i + 1] = Float.floatToRawIntBits((float) (p.y() + .5));
        data[i + 2] = Float.floatToRawIntBits((float) (p.z() + .5));
        data[i + 3] = -1;
        data[i + 4] = Float.floatToRawIntBits(sprite.getFrameU((float) (u + .5)));
        data[i + 5] = Float.floatToRawIntBits(sprite.getFrameV((float) (v + .5)));
        data[i + 7] = ((int) Math.round(n.x() * 127) & 255)
                | (((int) Math.round(n.y() * 127) & 255) << 8)
                | (((int) Math.round(n.z() * 127) & 255) << 16);
    }
}
