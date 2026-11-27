package dev.navalengineering.simulation;

import org.joml.Matrix3d;
import org.joml.Vector3d;

/** Mass moments accumulated in blueprint coordinates and translated to the centre of mass. */
final class MassProperties {
    private double mass, x, y, z, xx, yy, zz, xy, xz, yz;

    void add(double m, double px, double py, double pz, double fraction) {
        mass += m;
        x += m * px;
        y += m * py;
        z += m * pz;
        double horizontalMoment = (1 + fraction * fraction) / 12;
        xx += m * (py * py + pz * pz + horizontalMoment);
        yy += m * (px * px + pz * pz + 1.0 / 6);
        zz += m * (px * px + py * py + horizontalMoment);
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

