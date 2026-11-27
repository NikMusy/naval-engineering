package dev.navalengineering.ship;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
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
        private final Vector3d firstPart = new Vector3d();
        private final Vector3d secondPart = new Vector3d();
        private final Vector3d separation = new Vector3d();
        private final Vector3d normal = new Vector3d();

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
            Vessel.Contact best = null;
            int otherIndex = -1;
            double reach = (VOXEL_DIAMETER + 1) * .5;
            var otherHull = b.hull();
            for (int index : a.hull().exposed()) {
                if (a.breached(index)) continue;
                Hull.Cell aCell = a.hull().blocks().get(index);
                Hull.Grid cell = aCell.position();
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
                            for (HullGeometry.Box aPart : aCell.geometry().boxes()) {
                                place(firstPart, center, aPart, 0);
                                for (HullGeometry.Box bPart : otherHull.blocks().get(candidate).geometry().boxes()) {
                                    place(secondPart, otherCenter, bPart, 3);
                                    firstPart.sub(secondPart, separation);
                                    double depth = penetration(aPart, bPart);
                                    if (depth <= 0 || (best != null && best.depth() >= depth)) continue;
                                    var point = new Vector3d(firstPart).lerp(secondPart, .5)
                                            .fma((halfSpan(normal, 3, bPart) - halfSpan(normal, 0, aPart)) * .5, normal);
                                    best = new Vessel.Contact(point, new Vector3d(normal), depth, index);
                                    otherIndex = candidate;
                                }
                            }
                        }
                    }
                }
            }
            if (best == null) return false;
            a.resolveWith(b, best, otherIndex);
            return true;
        }

        private void place(Vector3d result, Vector3d cell, HullGeometry.Box part, int start) {
            result.set(cell).fma(part.x(), axes[start]).fma(part.y(), axes[start + 1]).fma(part.z(), axes[start + 2]);
        }

        private double penetration(HullGeometry.Box a, HullGeometry.Box b) {
            double depth = Double.POSITIVE_INFINITY;
            for (int i = 0; i < axes.length; i++) {
                if (axes[i].lengthSquared() == 0) continue;
                double distance = axes[i].dot(separation);
                double overlap = halfSpan(axes[i], 0, a) + halfSpan(axes[i], 3, b) - Math.abs(distance);
                if (overlap <= 1e-5) return 0;
                if (overlap < depth) {
                    depth = overlap;
                    normal.set(axes[i]).mul(Math.copySign(1, distance));
                }
            }
            return depth;
        }

        private double halfSpan(Vector3d axis, int start, HullGeometry.Box part) {
            return part.sx() * Math.abs(axis.dot(axes[start])) + part.sy() * Math.abs(axis.dot(axes[start + 1]))
                    + part.sz() * Math.abs(axis.dot(axes[start + 2]));
        }

        private static int low(double value, double hood) {
            return Math.max(1 - Hull.MAX_SPAN, MathHelper.ceil(value - hood));
        }

        private static int high(double value, double hood) {
            return Math.min(Hull.MAX_SPAN - 1, MathHelper.floor(value + hood));
        }
    }
}
