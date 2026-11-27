package dev.navalengineering.simulation;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Flooding {
    private static final double DISCHARGE = .62;
    private final Hull hull;
    private final double[] volume;
    private final List<List<Slot>> slots;
    private final double[] head;
    private final Vector3d world = new Vector3d();
    private final Vector3d local = new Vector3d();

    Flooding(Hull hull) {
        this.hull = hull;
        volume = new double[hull.compartments().size()];
        head = new double[volume.length];
        var rooms = new ArrayList<List<Slot>>();
        for (var compartment : hull.compartments()) {
            var room = new ArrayList<Slot>();
            for (Hull.Grid cell : compartment) room.add(new Slot(cell));
            rooms.add(room);
        }
        slots = List.copyOf(rooms);
    }

    void update(Vessel body, Ocean.Surface surface, double dt, Propulsion propulsion) {
        if (body.breachCount() == 0 && total() == 0) return;
        double halfHeight = body.verticalHalfSpan();
        for (int i = 0; i < slots.size(); i++) {
            var room = slots.get(i);
            for (Slot slot : room) {
                body.toWorld(slot.cell.vector(local), world);
                slot.worldY = world.y;
            }
            room.sort(Comparator.comparingDouble(slot -> slot.worldY));
            // Invert the sum of each cell's underwater fraction at a horizontal free surface.
            double low = room.getFirst().worldY - halfHeight;
            double high = room.getLast().worldY + halfHeight;
            for (int iteration = 0; iteration < 12; iteration++) {
                double level = (low + high) * .5;
                double sum = 0;
                for (Slot slot : room) sum += body.submergedFraction(level - slot.worldY);
                if (sum < volume[i]) low = level;
                else high = level;
            }
            head[i] = (low + high) * .5;
        }

        for (Hull.Boundary boundary : hull.boundaries()) {
            if (!body.breached(boundary.blockIndex())) continue;
            body.toWorld(hull.blocks().get(boundary.blockIndex()).position().vector(local), world);
            surface.sample(world.x, world.y, world.z, body.water);
            // The lower part of a one-block opening admits water before its centre is submerged.
            double sill = world.y - .45;
            double area = 1.0 / (boundary.spaces().size() - 1);
            for (int a = 0; a < boundary.spaces().size(); a++) {
                for (int b = a + 1; b < boundary.spaces().size(); b++) {
                    int from = boundary.spaces().get(a);
                    int to = boundary.spaces().get(b);
                    double hA = pressureHead(from, sill, body.water.height);
                    double hB = pressureHead(to, sill, body.water.height);
                    if (hA < hB) {
                        transfer(to, from, flow(hB, hA, area, dt));
                    } else if (hA > hB) {
                        transfer(from, to, flow(hA, hB, area, dt));
                    }
                }
            }
        }

        double total = total();
        if (total == 0) return;
        double pumped = Math.min(total, propulsion.pumpCapacity() * dt);
        for (int i = 0; i < volume.length; i++) volume[i] *= 1 - pumped / total;
        propulsion.consumePumpFuel(pumped);
    }

    private static double flow(double high, double low, double area, double dt) {
        return DISCHARGE * area * Math.min(1, high) * Math.sqrt(2 * Ocean.GRAVITY * (high - low)) * dt;
    }

    private double pressureHead(int id, double sill, double outside) {
        if (id == Hull.OUTSIDE) return Math.max(0, outside - sill);
        return volume[id] == 0 ? 0 : Math.max(0, head[id] - sill);
    }

    private void transfer(int from, int to, double amount) {
        if (from != Hull.OUTSIDE) amount = Math.min(amount, volume[from]);
        if (to != Hull.OUTSIDE) amount = Math.min(amount, slots.get(to).size() - volume[to]);
        if (amount <= 0) return;
        if (from != Hull.OUTSIDE) volume[from] -= amount;
        if (to != Hull.OUTSIDE) volume[to] += amount;
    }

    void addMass(Vessel body, MassProperties mass) {
        body.rotation().transformInverse(local.set(0, 1, 0));
        for (int i = 0; i < volume.length; i++) {
            if (volume[i] <= 0) continue;
            double sum = 0;
            for (Slot slot : slots.get(i)) sum += body.submergedFraction(head[i] - slot.worldY);
            if (sum <= 1e-9) {
                Hull.Grid first = slots.get(i).getFirst().cell;
                mass.add(volume[i] * Ocean.WATER_DENSITY, first.x(), first.y(), first.z(), 1);
                continue;
            }
            double scale = volume[i] / sum;
            for (Slot slot : slots.get(i)) {
                double fraction = body.submergedFraction(head[i] - slot.worldY);
                if (fraction <= 0) continue;
                double offset = .5 * (1 - fraction);
                Hull.Grid p = slot.cell;
                mass.add(scale * fraction * Ocean.WATER_DENSITY,
                        p.x() - local.x * offset, p.y() - local.y * offset, p.z() - local.z * offset, fraction);
            }
        }
    }

    double total() {
        double result = 0;
        for (double v : volume) result += v;
        return result;
    }

    double[] volumes() { return volume.clone(); }

    void restore(double[] values) {
        for (int i = 0; i < Math.min(values.length, volume.length); i++) {
            volume[i] = Math.clamp(values[i], 0, slots.get(i).size());
        }
    }

    private static final class Slot {
        final Hull.Grid cell;
        double worldY;
        Slot(Hull.Grid cell) { this.cell = cell; }
    }
}

