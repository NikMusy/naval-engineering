package dev.navalengineering.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.navalengineering.ocean.WorldOcean;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

final class OceanRenderer {
    private enum Quality {
        LOW(12, 2), MEDIUM(24, 2), HIGH(30, 1), ULTRA(42, 1), SIMULATION(0, 1);
        final int radius;
        final int step;
        Quality(int radius, int step) {
            this.radius = radius;
            this.step = step;
        }
    }

    private Quality quality = Quality.MEDIUM;
    private double[] mesh = new double[0];
    private double[] exposure = new double[0];

    Text cycle() {
        Quality[] levels = Quality.values();
        quality = levels[(quality.ordinal() + 1) % levels.length];
        return Text.translatable("naval.ocean." + quality.name().toLowerCase(java.util.Locale.ROOT));
    }

    void render(WorldRenderContext context) {
        if (quality.radius == 0 || context.matrixStack() == null) return;
        var camera = context.camera().getPos();
        int step = quality.step;
        int radius = quality.radius;
        int size = 1 + (2 * radius) / step;
        int ox = MathHelper.floor(camera.x / step) * step - radius;
        int oz = MathHelper.floor(camera.z / step) * step - radius;
        var ocean = new WorldOcean(context.world(), context.tickCounter().getTickDelta(false));
        if (mesh.length != size * size) {
            mesh = new double[size * size];
            exposure = new double[size * size];
        }
        for (int iz = 0; iz < size; iz++) {
            int z = oz + iz * step;
            for (int ix = 0; ix < size; ix++) {
                int x = ox + ix * step;
                double sampleX = x + .5, sampleZ = z + .5;
                var column = ocean.column(x, z);
                int index = iz * size + ix;
                exposure[index] = column.exposure();
                mesh[index] = column != WorldOcean.DRY && column.exposure() > 0
                        && Math.abs(camera.y - column.datum()) < 72
                        ? column.level(ocean.frame(), sampleX, sampleZ) : Double.NaN;
            }
        }

        Matrix4f view = context.matrixStack().peek().getPositionMatrix();
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        double sky = 1 - context.world().getSkyAngle(1);
        for (int iz = 0; iz < size - 1; iz++) {
            for (int ix = 0; ix < size - 1; ix++) {
                int i = iz * size + ix;
                double a = mesh[i], b = mesh[i + 1], c = mesh[i + size + 1], d = mesh[i + size];
                if (!Double.isFinite(a + b + c + d) || Math.max(Math.abs(a - c), Math.abs(b - d)) > 1.45) continue;
                double x = ox + ix * step + .5;
                double z = oz + iz * step + .5;
                double distance = Math.max(Math.abs(x - camera.x), Math.abs(z - camera.z));
                double edge = Math.clamp((radius - distance) / 6, 0, 1);
                double slopeX = (b + c - a - d) / (2 * step);
                double slopeZ = (d + c - a - b) / (2 * step);
                double sparkle = Math.pow(Math.clamp((slopeX * .75 - slopeZ) * 2 + .30, 0, 1), 3);
                double foam = Math.clamp((Math.hypot(slopeX, slopeZ) - .19) * (1 + 4 * ocean.frame().weather()), 0, .8);
                double brightness = .68 + .32 * Math.max(0, Math.cos(sky * Math.PI * 2));
                double deep = exposure[i];
                int red = (int) ((16 + 72 * sparkle + 140 * foam) * brightness);
                int green = (int) ((93 - 40 * deep + 58 * sparkle + 106 * foam) * brightness);
                int blue = (int) ((116 - 14 * deep + 56 * sparkle + 95 * foam) * brightness);
                int alpha = (int) ((172 + 58 * foam) * edge);
                vertex(buffer, view, camera, x, a, z, red, green, blue, alpha);
                vertex(buffer, view, camera, x, d, z + step, red, green, blue, alpha);
                vertex(buffer, view, camera, x + step, c, z + step, red, green, blue, alpha);
                vertex(buffer, view, camera, x + step, b, z, red, green, blue, alpha);
            }
        }

        var built = buffer.endNullable();
        if (built == null) return;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        try {
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferRenderer.drawWithGlobalProgram(built);
        } finally {
            RenderSystem.enableCull();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    private static void vertex(BufferBuilder buffer, Matrix4f view, Vec3d camera, double x, double y, double z,
                               int r, int g, int b, int a) {
        buffer.vertex(view, (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z)).color(r, g, b, a);
    }
}

