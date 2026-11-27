package dev.navalengineering.ship;

import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
import dev.navalengineering.simulation.Vessel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3d;

import java.util.List;

final class TerrainCollision implements Vessel.Collision {
    private static final Vector3d[] WORLD_AXES = {
            new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)
    };

    private final ServerWorld world;
    private final Long2ObjectOpenHashMap<List<Box>> solids = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable probe = new BlockPos.Mutable();
    private final Vector3d local = new Vector3d();
    private final Vector3d center = new Vector3d();
    private final Vector3d partCenter = new Vector3d();
    private final Vector3d normal = new Vector3d();
    private final Vector3d separation = new Vector3d();
    private final Vector3d[] axes = new Vector3d[15];

    TerrainCollision(ServerWorld world) {
        this.world = world;
        for (int i = 0; i < axes.length; i++) axes[i] = new Vector3d();
    }

    void beginTick() {
        solids.clear();
    }

    @Override
    public Vessel.Contact find(Vessel vessel) {
        for (int i = 0; i < 3; i++) {
            axes[i].set(WORLD_AXES[i]);
            vessel.rotation().transform(WORLD_AXES[i], axes[i + 3]);
        }
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                Vector3d test = axes[6 + a * 3 + b];
                axes[a].cross(axes[3 + b], test);
                double squared = test.lengthSquared();
                if (squared > 1e-10) test.mul(1 / Math.sqrt(squared));
                else test.zero();
            }
        }
        double dx = halfExtent(axes[0]);
        double dy = halfExtent(axes[1]);
        double dz = halfExtent(axes[2]);
        Vessel.Contact best = null;
        for (int index : vessel.hull().exposed()) {
            if (vessel.breached(index)) continue;
            Hull.Cell cell = vessel.hull().blocks().get(index);
            Hull.Grid voxel = cell.position();
            vessel.toWorld(local.set(voxel.x(), voxel.y(), voxel.z()), center);
            for (int x = MathHelper.floor(center.x - dx); x <= MathHelper.floor(center.x + dx); x++) {
                for (int z = MathHelper.floor(center.z - dz); z <= MathHelper.floor(center.z + dz); z++) {
                    for (int y = MathHelper.floor(center.y - dy); y <= MathHelper.floor(center.y + dy); y++) {
                        long key = BlockPos.asLong(x, y, z);
                        List<Box> boxes = solids.computeIfAbsent(key, this::boxes);
                        for (Box box : boxes) {
                            for (HullGeometry.Box part : cell.geometry().boxes()) {
                                partCenter.set(center).fma(part.x(), axes[3]).fma(part.y(), axes[4]).fma(part.z(), axes[5]);
                                double depth = penetration(box, x, y, z, part);
                                if (depth <= 0 || (best != null && depth <= best.depth())) continue;
                                Vector3d point = new Vector3d(partCenter).fma(-halfExtent(normal, part), normal);
                                best = new Vessel.Contact(point, new Vector3d(normal), depth, index);
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private List<Box> boxes(long key) {
        probe.set(key);
        if (probe.getY() >= world.getTopY()) return List.of();
        if (probe.getY() < world.getBottomY() || !world.getWorldBorder().contains(probe)
                || !world.getChunkManager().isChunkLoaded(probe.getX() >> 4, probe.getZ() >> 4)) {
            return List.of(new Box(0, 0, 0, 1, 1, 1));
        }
        return world.getBlockState(probe).getCollisionShape(world, probe).getBoundingBoxes();
    }

    private double penetration(Box box, int x, int y, int z, HullGeometry.Box part) {
        double rx = (box.maxX - box.minX) * .5;
        double ry = (box.maxY - box.minY) * .5;
        double rz = (box.maxZ - box.minZ) * .5;
        separation.set(partCenter.x - (x + box.minX + rx), partCenter.y - (y + box.minY + ry),
                partCenter.z - (z + box.minZ + rz));
        double depth = Double.POSITIVE_INFINITY;
        for (Vector3d axis : axes) {
            if (axis.lengthSquared() == 0) continue;
            double projection = separation.dot(axis);
            double overlap = halfExtent(axis, part) + rx * Math.abs(axis.x) + ry * Math.abs(axis.y)
                    + rz * Math.abs(axis.z) - Math.abs(projection);
            if (overlap <= 1e-5) return 0;
            if (overlap < depth) {
                depth = overlap;
                normal.set(axis).mul(Math.copySign(1, projection));
            }
        }
        return depth;
    }

    private double halfExtent(Vector3d axis) {
        return halfExtent(axis, HullGeometry.FULL.boxes().getFirst());
    }

    private double halfExtent(Vector3d axis, HullGeometry.Box part) {
        return part.sx() * Math.abs(axis.dot(axes[3])) + part.sy() * Math.abs(axis.dot(axes[4]))
                + part.sz() * Math.abs(axis.dot(axes[5]));
    }
}
