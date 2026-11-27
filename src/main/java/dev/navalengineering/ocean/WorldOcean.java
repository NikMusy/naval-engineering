package dev.navalengineering.ocean;

import dev.navalengineering.simulation.Ocean;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

public final class WorldOcean implements Ocean.Surface {
    public static final Column DRY = new Column(Double.NEGATIVE_INFINITY, 0);
    private final World world;
    private final Ocean.Frame frame;
    private final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable probe = new BlockPos.Mutable();

    public WorldOcean(World world, float tickDelta) {
        this.world = world;
        frame = new Ocean.Frame((world.getTime() + tickDelta) / 20.0,
                Math.min(1, .52 * world.getRainGradient(tickDelta) + .48 * world.getThunderGradient(tickDelta)));
    }

    public Ocean.Frame frame() { return frame; }

    @Override
    public void sample(double x, double y, double z, Ocean.Water out) {
        Column column = column(MathHelper.floor(x), MathHelper.floor(z));
        if (column == DRY) {
            out.set(Double.NEGATIVE_INFINITY, 0, 0, 0);
        } else {
            frame.sample(x, z, column.datum, column.exposure, out);
        }
    }

    public Column column(int x, int z) {
        long key = ((long) x << 32) | (z & 0xffffffffL);
        return columns.computeIfAbsent(key, ignored -> find(x, z));
    }

    private Column find(int x, int z) {
        if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return DRY;
        int surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        probe.set(x, surface, z);
        FluidState fluid = world.getFluidState(probe);
        if (!fluid.isIn(FluidTags.WATER)) {
            // Recover water under low bridges and exposed underwater foliage.
            int bottom = Math.max(world.getBottomY(), surface - 6);
            for (int y = surface - 1; y >= bottom; y--) {
                probe.setY(y);
                fluid = world.getFluidState(probe);
                if (fluid.isIn(FluidTags.WATER)) return new Column(y + fluid.getHeight(world, probe), 0);
            }
            return DRY;
        }
        double datum = surface + fluid.getHeight(world, probe);
        int depth = 0;
        for (int y = surface - 1; y >= Math.max(world.getBottomY(), surface - 4); y--) {
            probe.setY(y);
            if (!world.getFluidState(probe).isIn(FluidTags.WATER)) break;
            depth++;
        }
        return new Column(datum, depth / 4.0);
    }

    public record Column(double datum, double exposure) {
        public double level(Ocean.Frame frame, double x, double z) {
            return frame.elevation(x, z, datum, exposure);
        }
    }
}

