package dev.navalengineering.simulation;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.BitSet;
import java.util.List;

public final class Vessel {
    public static final double STEP = 1.0 / 40;
    private static final double MAX_SPEED = 14;
    private static final double MAX_SPIN = 2.5;
    private final Hull hull;
    private final List<Hull.Grid> displacement;
    private final Flooding flooding;
    private final Propulsion propulsion;
    private final BitSet breaches = new BitSet();
    private final boolean[] equipment;
    private final boolean[] outside;
    private final Vector3d center = new Vector3d();
    private final Vector3d position = new Vector3d();
    private final Vector3d velocity = new Vector3d();
    private final Vector3d angularVelocity = new Vector3d();
    private final Quaterniond rotation = new Quaterniond();
    private final Quaterniond delta = new Quaterniond();
    private final Matrix3d inertia = new Matrix3d();
    private final Matrix3d inverseInertia = new Matrix3d();
    private final Vector3d linearForce = new Vector3d();
    private final Vector3d torque = new Vector3d();
    private final Vector3d local = new Vector3d();
    private final Vector3d point = new Vector3d();
    private final Vector3d arm = new Vector3d();
    private final Vector3d force = new Vector3d();
    private final Vector3d flow = new Vector3d();
    private final Vector3d scratch = new Vector3d();
    private final Vector3d support = new Vector3d();
    private final Vector3d buoyancyPoint = new Vector3d();
    private final Vector3d sliceCenter = new Vector3d();
    private final double[] spans = new double[3];
    private final int[] spanAxes = new int[3];
    private final double[] spanSigns = new double[3];
    private int spanCount;
    private double halfHeight;
    private double mass;
    private double displaced;
    private boolean dirty = true;
    final Ocean.Water water = new Ocean.Water();

    public Vessel(Hull hull, Vector3d origin) {
        this.hull = hull;
        displacement = List.copyOf(hull.displacementCells());
        propulsion = new Propulsion(hull);
        flooding = new Flooding(hull);
        equipment = new boolean[hull.blocks().size()];
        outside = new boolean[equipment.length];
        for (int i : hull.exposed()) outside[i] = true;
        for (int i = 0; i < equipment.length; i++) {
            Hull.Material material = hull.blocks().get(i).material();
            equipment[i] = switch (material) {
                case TIMBER, PLATING, TRIM, GLAZING -> false;
                default -> true;
            };
        }
        updateSpans();
        updateMass(false);
        setOrigin(origin);
    }

    public Hull hull() { return hull; }
    public Quaterniond rotation() { return rotation; }
    public Vector3d velocity() { return velocity; }
    public Vector3d angularVelocity() { return angularVelocity; }
    public double displaced() { return displaced; }
    public double mass() { return mass; }
    public double waterVolume() { return flooding.total(); }
    public double fuel() { return propulsion.fuel(); }
    public double rpm() { return propulsion.rpm(); }
    public double temperature() { return propulsion.temperature(); }
    public double wear() { return propulsion.wear(); }
    public double[] floodingVolumes() { return flooding.volumes(); }
    public BitSet breaches() { return (BitSet) breaches.clone(); }
    public int breachCount() { return breaches.cardinality(); }

    public Vector3d origin(Vector3d out) {
        rotation.transform(center, out).negate().add(position);
        return out;
    }

    public void setOrigin(Vector3d origin) {
        rotation.transform(center, position).add(origin);
    }

    public void toWorld(Vector3d localPoint, Vector3d out) {
        out.set(localPoint).sub(center);
        rotation.transform(out).add(position);
    }

    public void toLocal(Vector3d worldPoint, Vector3d out) {
        out.set(worldPoint).sub(position);
        rotation.transformInverse(out).add(center);
    }

    public Vector3d centerOfMass(Vector3d out) { return out.set(center); }

    public boolean breached(int index) { return index >= 0 && breaches.get(index); }

    public boolean breach(int index) {
        if (index < 0 || index >= equipment.length || equipment[index] || breaches.get(index)
                || breaches.cardinality() + 1 >= equipment.length) return false;
        breaches.set(index);
        dirty = true;
        return true;
    }

    public boolean repair(int index) {
        if (!breached(index)) return false;
        breaches.clear(index);
        dirty = true;
        return true;
    }

    public void refuel() { propulsion.refuel(); }

    public void restore(Vector3d origin, Quaterniond attitude, Vector3d motion, Vector3d spin,
                        BitSet broken, double[] flood, double fuel, double rpm, double heat, double wear) {
        rotation.set(attitude).normalize();
        velocity.set(motion);
        angularVelocity.set(spin);
        broken.stream().forEach(this::breach);
        flooding.restore(flood);
        updateSpans();
        if (flooding.total() > 0) {
            flooding.update(this, (x, y, z, result) -> result.set(Double.NEGATIVE_INFINITY, 0, 0, 0),
                    0, propulsion);
        }
        updateMass(false);
        setOrigin(origin);
        propulsion.restore(fuel, rpm, heat, wear);
    }

    public void step(Ocean.Surface ocean, double wind, double throttle, double steer, Collision collision) {
        updateSpans();
        double before = flooding.total();
        flooding.update(this, ocean, STEP, propulsion);
        if (dirty || before > 0 || flooding.total() > 0) updateMass(true);

        linearForce.set(0, -mass * Ocean.GRAVITY, 0);
        torque.zero();
        displaced = 0;
        for (int i = 0; i < displacement.size(); i++) {
            if (breached(i)) continue;
            displacement.get(i).vector(local);
            toWorld(local, point);
            ocean.sample(point.x, point.y, point.z, water);
            double wet = submergedFraction(water.height - point.y);
            if (wet > 0) {
                displaced += wet;
                force.set(0, wet * Ocean.WATER_DENSITY * Ocean.GRAVITY, 0);
                submergedCenter(water.height - point.y, wet, sliceCenter);
                forceAt(force, buoyancyPoint.set(local).add(sliceCenter));
            }

            if (i >= outside.length || !outside[i]) continue;
            if (wet > 0) {
                point.sub(position, arm);
                angularVelocity.cross(arm, flow).add(velocity).sub(water.vx, water.vy, water.vz);
                rotation.transformInverse(flow);
                force.set(-wet * resistance(flow.x, 95, 260),
                        -wet * resistance(flow.y, 170, 300),
                        -wet * resistance(flow.z, 50, 130));
                rotation.transform(force);
                forceAt(force, local);
            }
            if (wet < 1) {
                double relativeWind = wind - velocity.x;
                force.set(.21 * (1 - wet) * relativeWind * Math.abs(relativeWind), 0, 0);
                forceAt(force, local);
            }
        }
        propulsion.apply(this, ocean, STEP, throttle, steer);
        integrate();
        Contact hit = collision == null ? null : collision.find(this);
        if (hit != null) resolve(hit);
    }

    private static double resistance(double v, double linear, double quadratic) {
        return (linear + quadratic * Math.abs(v)) * v;
    }

    void forceAt(Vector3d worldForce, Vector3d localPoint) {
        rotation.transform(scratch.set(localPoint).sub(center), arm);
        linearForce.add(worldForce);
        torque.add(arm.cross(worldForce));
    }

    private void integrate() {
        velocity.fma(STEP / mass, linearForce);
        cap(velocity, MAX_SPEED);
        position.fma(STEP, velocity);

        rotation.transformInverse(angularVelocity, local);
        inertia.transform(local, scratch);
        local.cross(scratch, flow);
        rotation.transformInverse(torque, force).sub(flow);
        inverseInertia.transform(force);
        rotation.transform(force);
        angularVelocity.fma(STEP, force);
        cap(angularVelocity, MAX_SPIN);
        double speed = angularVelocity.length();
        if (speed > 1e-10) {
            delta.rotationAxis(speed * STEP, angularVelocity.x / speed, angularVelocity.y / speed,
                    angularVelocity.z / speed).mul(rotation, rotation).normalize();
        }
    }

    private void resolve(Contact hit) {
        position.fma(Math.min(.22, hit.depth + .001), hit.normal);
        hit.point.sub(position, support);
        angularVelocity.cross(support, flow).add(velocity);
        double approaching = -flow.dot(hit.normal);
        if (approaching <= 0) return;
        double impulse = 1.05 * approaching / effectiveInverseMass(hit.normal);
        push(hit.normal, impulse);
        angularVelocity.cross(support, flow).add(velocity);
        flow.fma(-flow.dot(hit.normal), hit.normal);
        double slip = flow.length();
        if (slip > 1e-6) {
            flow.div(slip);
            double friction = Math.min(.38 * impulse, slip / effectiveInverseMass(flow));
            push(flow, -friction);
        }
        damageFromImpact(hit.blockIndex, approaching);
    }

    public void resolveWith(Vessel other, Contact hit, int otherBlock) {
        double correction = Math.min(.30, hit.depth + .001) / (mass + other.mass);
        position.fma(correction * other.mass, hit.normal);
        other.position.fma(-correction * mass, hit.normal);
        hit.point.sub(position, support);
        hit.point.sub(other.position, other.support);
        relativeVelocity(other);
        double closing = -flow.dot(hit.normal);
        if (closing <= 0) return;
        double impulse = 1.07 * closing / (effectiveInverseMass(hit.normal) + other.effectiveInverseMass(hit.normal));
        push(hit.normal, impulse);
        other.push(hit.normal, -impulse);
        relativeVelocity(other);
        flow.fma(-flow.dot(hit.normal), hit.normal);
        double slip = flow.length();
        if (slip > 1e-6) {
            flow.div(slip);
            double friction = Math.min(.30 * impulse, slip / (effectiveInverseMass(flow) + other.effectiveInverseMass(flow)));
            push(flow, -friction);
            other.push(flow, friction);
        }
        damageFromImpact(hit.blockIndex, closing);
        other.damageFromImpact(otherBlock, closing);
    }

    private void relativeVelocity(Vessel other) {
        angularVelocity.cross(support, flow).add(velocity);
        other.angularVelocity.cross(other.support, force).add(other.velocity);
        flow.sub(force);
    }

    private void damageFromImpact(int index, double speed) {
        Hull.Material material = hull.blocks().get(index).material();
        if (speed > 2.2 * Math.sqrt(material.strength())) breach(index);
    }

    private double effectiveInverseMass(Vector3d direction) {
        support.cross(direction, arm);
        rotation.transformInverse(arm, scratch);
        inverseInertia.transform(scratch);
        rotation.transform(scratch);
        return 1 / mass + scratch.dot(arm);
    }

    private void push(Vector3d direction, double impulse) {
        velocity.fma(impulse / mass, direction);
        support.cross(direction, arm);
        rotation.transformInverse(arm, scratch);
        inverseInertia.transform(scratch);
        rotation.transform(scratch);
        angularVelocity.fma(impulse, scratch);
    }

    private static void cap(Vector3d v, double max) {
        double square = v.lengthSquared();
        if (square > max * max) v.mul(max / Math.sqrt(square));
    }

    private void updateMass(boolean preserveOrigin) {
        if (preserveOrigin) origin(point);
        var moments = new MassProperties();
        for (int i = 0; i < hull.blocks().size(); i++) {
            if (breached(i)) continue;
            Hull.Cell cell = hull.blocks().get(i);
            Hull.Grid p = cell.position();
            moments.add(cell.material().mass(), p.x(), p.y(), p.z(), 1);
        }
        flooding.addMass(this, moments);
        mass = moments.finish(center, inertia);
        inertia.invert(inverseInertia);
        if (preserveOrigin) setOrigin(point);
        dirty = false;
    }

    private void updateSpans() {
        rotation.transformInverse(local.set(0, 1, 0));
        spanCount = 0;
        halfHeight = 0;
        addSpan(local.x, 0);
        addSpan(local.y, 1);
        addSpan(local.z, 2);
    }

    private void addSpan(double projectedSide, int axis) {
        double span = Math.abs(projectedSide);
        if (span < 1e-4) return;
        spanAxes[spanCount] = axis;
        spanSigns[spanCount] = Math.copySign(1, projectedSide);
        spans[spanCount++] = span;
        halfHeight += .5 * span;
    }

    double verticalHalfSpan() { return halfHeight; }

    // Volume of a cube below a horizontal plane, from the CDF of projected uniform coordinates.
    double submergedFraction(double relativeHeight) {
        if (relativeHeight <= -halfHeight) return 0;
        if (relativeHeight >= halfHeight) return 1;
        double depth = relativeHeight + halfHeight;
        if (spanCount == 1) return depth / spans[0];
        double numerator = power(depth);
        double denominator = spanCount == 2 ? 2 : 6;
        for (int i = 0; i < spanCount; i++) {
            numerator -= power(depth - spans[i]);
            denominator *= spans[i];
            for (int j = i + 1; j < spanCount; j++) numerator += power(depth - spans[i] - spans[j]);
        }
        return Math.clamp(numerator / denominator, 0, 1);
    }

    private double power(double n) {
        if (n <= 0) return 0;
        return spanCount == 2 ? n * n : n * n * n;
    }

    // First moments of the same clipped cube, in blueprint coordinates.
    private void submergedCenter(double height, double fraction, Vector3d out) {
        out.zero();
        if (fraction >= 1) return;
        if (spanCount == 1) {
            out.setComponent(spanAxes[0], -.5 * (1 - fraction) * spanSigns[0]);
            return;
        }
        double depth = halfHeight - Math.abs(height);
        double total = 0;
        double baseMoment = 0;
        for (int mask = 0; mask < (1 << spanCount); mask++) {
            double shifted = depth;
            for (int i = 0; i < spanCount; i++) {
                if ((mask & (1 << i)) != 0) shifted -= spans[i];
            }
            if (shifted <= 0) continue;
            double signedVolume = (Integer.bitCount(mask) % 2 == 0 ? 1 : -1) * power(shifted);
            total += signedVolume;
            baseMoment += signedVolume * shifted / (spanCount + 1);
            for (int i = 0; i < spanCount; i++) {
                if ((mask & (1 << i)) == 0) continue;
                int axis = spanAxes[i];
                out.setComponent(axis, out.get(axis) + signedVolume);
            }
        }
        if (total <= 1e-14) {
            out.zero();
            return;
        }
        double mirror = height > 0 ? (1 - fraction) / fraction : 1;
        for (int i = 0; i < spanCount; i++) {
            int axis = spanAxes[i];
            double centered = (baseMoment / spans[i] + out.get(axis)) / total - .5;
            out.setComponent(axis, Math.clamp(centered, -.5, .5) * mirror * spanSigns[i]);
        }
    }

    public record Contact(Vector3d point, Vector3d normal, double depth, int blockIndex) {}

    @FunctionalInterface
    public interface Collision {
        Contact find(Vessel vessel);
    }
}

