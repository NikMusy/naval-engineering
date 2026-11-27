package dev.navalengineering.simulation;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Mass moments accumulated in blueprint coordinates and translated to the centre of mass. */
final class MassProperties {
    private double mass, x, y, z, xx, yy, zz, xy, xz, yz;

    void add(double m, double px, double py, double pz, double fraction) {
        add(m, px, py, pz, 1, fraction, 1);
    }

    void add(Hull.Cell cell) {
        var p = cell.position();
        for (HullGeometry.Box part : cell.geometry().boxes()) {
            add(cell.material().mass() * 8 * part.sx() * part.sy() * part.sz(),
                    p.x() + part.x(), p.y() + part.y(), p.z() + part.z(),
                    2 * part.sx(), 2 * part.sy(), 2 * part.sz());
        }
    }

    private void add(double m, double px, double py, double pz, double w, double h, double l) {
        mass += m;
        x += m * px;
        y += m * py;
        z += m * pz;
        xx += m * (py * py + pz * pz + (h * h + l * l) / 12);
        yy += m * (px * px + pz * pz + (w * w + l * l) / 12);
        zz += m * (px * px + py * py + (w * w + h * h) / 12);
        xy -= m * px * py;
        xz -= m * px * pz;
        yz -= m * py * pz;
    }

    double finish(Vector3d center, Matrix3d inertia) {
        center.set(x / mass, y / mass, z / mass);
        double cx = center.x, cy = center.y, cz = center.z;
        double ixy = xy + mass * cx * cy;
        double ixz = xz + mass * cx * cz;
        double iyz = yz + mass * cy * cz;
        inertia.set(
                xx - mass * (cy * cy + cz * cz), ixy, ixz,
                ixy, yy - mass * (cx * cx + cz * cz), iyz,
                ixz, iyz, zz - mass * (cx * cx + cy * cy));
        return mass;
    }
}
