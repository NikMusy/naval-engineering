package dev.navalengineering.simulation;

import org.joml.Vector3d;

final class Propulsion {
    static final double POWER = 185_000;
    static final double EFFICIENCY = .61;
    static final double TANK_LITRES = 250;
    private final int engines, screws, tanks, pumps;
    private final Vector3d screw = new Vector3d();
    private final Vector3d output = new Vector3d();
    private double fuel, rpm, temperature = 18, wear;

    Propulsion(Hull hull) {
        int engineCount = 0, screwCount = 0, tankCount = 0, pumpCount = 0;
        for (Hull.Cell cell : hull.blocks()) {
            switch (cell.material()) {
                case DIESEL -> engineCount++;
                case PROPELLER -> {
                    screwCount++;
                    screw.add(cell.position().x(), cell.position().y(), cell.position().z());
                }
                case TANK -> tankCount++;
                case PUMP -> pumpCount++;
                default -> { }
            }
        }
        engines = engineCount;
        screws = screwCount;
        tanks = tankCount;
        pumps = pumpCount;
        fuel = TANK_LITRES * tanks;
        if (screws > 0) screw.div(screws);
    }

    void apply(Vessel body, Ocean.Surface ocean, double dt, double throttle, double steer) {
        if (engines == 0 || screws == 0) return;
        body.toWorld(screw, output);
        ocean.sample(output.x, output.y, output.z, body.water);
        double wet = Math.clamp(body.water.height - output.y + .5, 0, 1);
        double target = fuel > 0 && temperature < 125 && wear < 1 ? 1650 * throttle : 0;
        rpm += (target - rpm) * (1 - Math.exp(-dt / 1.8));
        double load = Math.min(1, Math.abs(rpm) / 1650);
        double derating = Math.clamp((125 - temperature) / 20, 0, 1) * (1 - .35 * wear);
        double watts = POWER * Math.min(engines, screws) * load * derating;
        fuel = Math.max(0, fuel - watts * dt / (35_800_000 * .38));
        temperature += dt * (load * 1.1 - (temperature - 18) * (.005 + .021 * wet));
        wear = Math.min(1, wear + dt * load * (.000003 + Math.max(0, temperature - 95) * .000001));
        if (wet == 0 || fuel == 0) return;

        // Available shaft power is capped by the screw's bollard pull at near-zero advance.
        output.set(body.water.vx, body.water.vy, body.water.vz).sub(body.velocity());
        body.rotation().transformInverse(output);
        double advance = Math.abs(output.z);
        double thrust = Math.copySign(wet * Math.min(watts * EFFICIENCY / Math.max(2.8, advance),
                32000 * screws * load), rpm);
        double radial = .57 * steer;
        output.set(-Math.sin(radial) * thrust, 0, -Math.cos(radial) * thrust);
        body.rotation().transform(output);
        body.forceAt(output, screw);
    }

    double pumpCapacity() { return fuel > 0 ? .18 * pumps : 0; }
    double fuel() { return fuel; }
    double rpm() { return rpm; }
    double temperature() { return temperature; }
    double wear() { return wear; }

    void consumePumpFuel(double volume) {
        fuel = Math.max(0, fuel - .005 * volume);
    }

    void refuel() {
        fuel = TANK_LITRES * tanks;
    }

    void restore(double available, double speed, double heat, double damage) {
        fuel = Math.clamp(available, 0, TANK_LITRES * tanks);
        rpm = Math.clamp(speed, -1650, 1650);
        temperature = Math.clamp(heat, 0, 140);
        wear = Math.clamp(damage, 0, 1);
    }
}

