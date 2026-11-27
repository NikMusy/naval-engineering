package dev.navalengineering.simulation;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public final class Hydrostatics {
    private Hydrostatics() {}

    public record Report(double mass, Vector3d centerOfMass, double waterline, double draft,
                         double reserveVolume, double transverseGm, double longitudinalGm,
                         double speedEstimate) {
        public boolean floats() { return reserveVolume > 0; }
    }

    /** Upright waterplane over the same bounded subdivision used for shape contacts. */
    public static Report analyze(Hull hull) {
        var columns = new ArrayList<Column>();
        var moments = new MassProperties();
        int engines = 0, screws = 0, tanks = 0;
        for (Hull.Cell cell : hull.blocks()) {
            moments.add(cell);
            append(columns, cell.position(), cell.geometry());
            switch (cell.material()) {
                case DIESEL -> engines++;
                case PROPELLER -> screws++;
                case TANK -> tanks++;
                default -> { }
            }
        }
        for (var room : hull.compartments()) {
            for (var p : room) append(columns, p, HullGeometry.FULL);
        }

        var center = new Vector3d();
        double mass = moments.finish(center, new Matrix3d());
        double volume = mass / Ocean.WATER_DENSITY;
        double reserve = columns.stream().mapToDouble(c -> c.area * (c.top - c.bottom)).sum() - volume;
        if (reserve <= 0) return new Report(mass, center, Double.NaN, Double.NaN, reserve, Double.NaN, Double.NaN, 0);

        double bottom = columns.stream().mapToDouble(c -> c.bottom).min().orElseThrow();
        double low = bottom;
        double high = columns.stream().mapToDouble(c -> c.top).max().orElseThrow();
        for (int i = 0; i < 45; i++) {
            double level = (low + high) * .5;
            double sum = 0;
            for (Column c : columns) sum += c.area * c.wetDepth(level);
            if (sum < volume) low = level;
            else high = level;
        }
        double waterline = (low + high) * .5;
        double firstY = 0, area = 0, firstX = 0, firstZ = 0, secondX = 0, secondZ = 0;
        double probe = waterline - 1e-8;
        for (Column c : columns) {
            double wet = c.wetDepth(waterline);
            firstY += c.area * wet * (c.bottom + wet * .5);
            if (probe < c.bottom || probe >= c.top) continue;
            area += c.area;
            firstX += c.area * c.x;
            firstZ += c.area * c.z;
            secondX += c.area * (c.x * c.x + c.width * c.width / 12);
            secondZ += c.area * (c.z * c.z + c.length * c.length / 12);
        }

        double kb = firstY / volume;
        double it = area == 0 ? 0 : secondX - firstX * firstX / area;
        double il = area == 0 ? 0 : secondZ - firstZ * firstZ / area;
        double draft = waterline - bottom;
        double frontalArea = Math.max(1, Math.sqrt(area) * draft);
        double speed = tanks == 0 ? 0 : Math.cbrt(Math.min(engines, screws)
                * Propulsion.POWER * Propulsion.EFFICIENCY / (410 * frontalArea));
        return new Report(mass, center, waterline, draft, reserve,
                kb + it / volume - center.y, kb + il / volume - center.y, speed);
    }

    private static void append(List<Column> columns, Hull.Grid p, HullGeometry profile) {
        for (HullGeometry.Box part : profile.boxes()) {
            double width = 2 * part.sx();
            double length = 2 * part.sz();
            columns.add(new Column(p.x() + part.x(), p.z() + part.z(), p.y() + part.y0(), p.y() + part.y1(),
                    width, length, width * length));
        }
    }

    private record Column(double x, double z, double bottom, double top, double width, double length, double area) {
        double wetDepth(double height) { return Math.clamp(height - bottom, 0, top - bottom); }
    }
}
