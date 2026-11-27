package dev.navalengineering.ship;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.Vessel;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3d;

public final class ShipContacts {
    private static final double MAX_RADIUS = Math.sqrt(3) * (Hull.MAX_SPAN - .5);
    private static final double VOXEL_DIAMETER = Math.sqrt(3);

    private ShipContacts() {}

    public static void solve(ServerWorld world) {
        var ships = world.getEntitiesByType(NavalEngineering.SHIP, ship -> ship.body() != null);
        if (ships.size() < 2) return;
        var solver = new Pair();
        for (ShipEntity ship : ships) {
            double reach = ship.blueprint().hull().radius() + MAX_RADIUS;
            var candidates = world.getEntitiesByClass(ShipEntity.class, ship.getBoundingBox().expand(reach),
                    candidate -> candidate.getId() > ship.getId() && candidate.body() != null);
            for (ShipEntity other : candidates) {
                double radius = ship.blueprint().hull().radius() + other.blueprint().hull().radius();
                if (ship.squaredDistanceTo(other) > radius * radius) continue;
                if (solver.solve(ship.body(), other.body())) {
                    ship.syncPose();
                    other.syncPose();
                }
            }
        }
    }

    private static final class Pair {
        private final Vector3d[] axes = new Vector3d[15];
        private final Vector3d local = new Vector3d();
        private final Vector3d inOther = new Vector3d();
        private final Vector3d center = new Vector3d();
        private final Vector3d otherCenter = new Vector3d();
        private final Vector3d separation = new Vector3d();
        private final Vector3d normal = new Vector3d();
        private final double[] span = new double[15];

        Pair() {
            for (int i = 0; i < axes.length; i++) axes[i] = new Vector3d();
        }

        boolean solve(Vessel a, Vessel b) {
            for (int i = 0; i < 3; i++) {
                local.zero().setComponent(i, 1);
                a.rotation().transform(local, axes[i]);
                b.rotation().transform(local, axes[i + 3]);
            }
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    Vector3d axis = axes[6 + i * 3 + j];
                    axes[i].cross(axes[3 + j], axis);
                    if (axis.lengthSquared() > 1e-10) axis.normalize();
                    else axis.zero();
                }
            }
            for (int i = 0; i < axes.length; i++) {
                Vector3d axis = axes[i];
                span[i] = halfSpan(axis, 0) + halfSpan(axis, 3);
            }
            Vessel.Contact best = null;
            int otherIndex = -1;
            double reach = (VOXEL_DIAMETER + 1) * .5;
            var otherHull = b.hull();
            for (int index : a.hull().exposed()) {
                if (a.breached(index)) continue;
                Hull.Grid cell = a.hull().blocks().get(index).position();
                a.toWorld(local.set(cell.x(), cell.y(), cell.z()), center);
                b.toLocal(center, inOther);
                for (int x = low(inOther.x, reach); x <= high(inOther.x, reach); x++) {
                    for (int y = low(inOther.y, reach); y <= high(inOther.y, reach); y++) {
                        for (int z = low(inOther.z, reach); z <= high(inOther.z, reach); z++) {
                            int candidate = otherHull.indexOf(new Hull.Grid(x, y, z));
                            if (candidate < 0 || b.breached(candidate)) continue;
                            b.toWorld(local.set(x, y, z), otherCenter);
                            center.sub(otherCenter, separation);
                            if (separation.lengthSquared() > 3) continue;
                            double depth = penetration();
                            if (depth <= 0 || (best != null && best.depth() >= depth)) continue;
                            // Midpoint of the support points, with a common world-space normal.
                            var point = new Vector3d(center).lerp(otherCenter, .5)
                                    .fma((halfSpan(normal, 3) - halfSpan(normal, 0)) * .5, normal);
                            best = new Vessel.Contact(point, new Vector3d(normal), depth, index);
                            otherIndex = candidate;
                        }
                    }
                }
            }
            if (best == null) return false;
            a.resolveWith(b, best, otherIndex);
            return true;
        }

        private double penetration() {
            double depth = Double.POSITIVE_INFINITY;
            for (int i = 0; i < axes.length; i++) {
                if (span[i] == 0) continue;
                double distance = axes[i].dot(separation);
                double overlap = span[i] - Math.abs(distance);
                if (overlap <= 1e-5) return 0;
                if (overlap < depth) {
                    depth = overlap;
                    normal.set(axes[i]).mul(Math.copySign(1, distance));
                }
            }
            return depth;
        }

        private double halfSpan(Vector3d axis, int start) {
            return .5 * (Math.abs(axis.dot(axes[start])) + Math.abs(axis.dot(axes[start + 1]))
                    + Math.abs(axis.dot(axes[start + 2])));
        }

        private static int low(double value, double hood) {
            return Math.max(1 - Hull.MAX_SPAN, MathHelper.ceil(value - hood));
        }

        private static int high(double value, double hood) {
            return Math.min(Hull.MAX_SPAN - 1, MathHelper.floor(value + hood));
        }
    }
}

