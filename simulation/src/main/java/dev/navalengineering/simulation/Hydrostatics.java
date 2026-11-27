package dev.navalengineering.simulation;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.TreeMap;

public final class Hydrostatics {
    private Hydrostatics() {}

    public record Report(double mass, Vector3d centerOfMass, double waterline, double draft,
                         double reserveVolume, double transverseGm, double longitudinalGm,
                         double speedEstimate) {
        public boolean floats() { return reserveVolume > 0; }
    }

    /** Upright, still-water calculation using exact volumes of horizontal voxel slices. */
    public static Report analyze(Hull hull) {
        var cells = hull.displacementCells();
        var moments = new MassProperties();
        var layers = new TreeMap<Integer, ArrayList<Hull.Grid>>();
        int engines = 0, screws = 0, tanks = 0;
        for (Hull.Cell cell : hull.blocks()) {
            Hull.Grid p = cell.position();
            moments.add(cell.material().mass(), p.x(), p.y(), p.z(), 1);
            switch (cell.material()) {
                case DIESEL -> engines++;
                case PROPELLER -> screws++;
                case TANK -> tanks++;
                default -> { }
            }
        }
        for (Hull.Grid p : cells) layers.computeIfAbsent(p.y(), k -> new ArrayList<>()).add(p);
        var center = new Vector3d();
        double mass = moments.finish(center, new Matrix3d());
        double volume = mass / Ocean.WATER_DENSITY;
        double reserve = cells.size() - volume;
        if (reserve <= 0) {
            return new Report(mass, center, Double.NaN, Double.NaN, reserve, Double.NaN, Double.NaN, 0);
        }

        double remaining = volume;
        double verticalMoment = 0;
        for (var layer : layers.entrySet()) {
            var slice = layer.getValue();
            double fraction = Math.min(1, remaining / slice.size());
            double bottom = layer.getKey() - .5;
            double displaced = fraction * slice.size();
            verticalMoment += displaced * (bottom + fraction / 2);
            remaining -= displaced;
            if (remaining > 1e-12) continue;

            double meanX = slice.stream().mapToInt(Hull.Grid::x).average().orElseThrow();
            double meanZ = slice.stream().mapToInt(Hull.Grid::z).average().orElseThrow();
            double it = 0, il = 0;
            for (Hull.Grid p : slice) {
                it += square(p.x() - meanX) + 1.0 / 12;
                il += square(p.z() - meanZ) + 1.0 / 12;
            }
            double kb = verticalMoment / volume;
            double draft = bottom + fraction - (layers.firstKey() - .5);
            double frontalArea = Math.max(1, Math.sqrt(slice.size()) * draft);
            double speed = tanks == 0 ? 0 : Math.cbrt(Math.min(engines, screws)
                    * Propulsion.POWER * Propulsion.EFFICIENCY / (410 * frontalArea));
            return new Report(mass, center, bottom + fraction, draft, reserve,
                    kb + it / volume - center.y, kb + il / volume - center.y, speed);
        }
        throw new IllegalStateException("Floating hull has no waterplane");
    }

    private static double square(double n) { return n * n; }
}

