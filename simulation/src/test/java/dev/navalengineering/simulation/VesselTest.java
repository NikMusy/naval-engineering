package dev.navalengineering.simulation;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static dev.navalengineering.simulation.Hull.Material.*;
import static org.junit.jupiter.api.Assertions.*;

class VesselTest {
    private static final Ocean.Surface STILL_WATER = (x, y, z, result) -> result.set(0, 0, 0, 0);

    @Test
    void enclosesTwoIndependentCompartments() {
        Hull hull = hull(2, true, Map.of());
        assertEquals(2, hull.compartments().size());
        assertEquals(24, hull.interiorVolume());
        assertTrue(hull.boundaries().stream().anyMatch(boundary -> boundary.spaces().equals(java.util.List.of(0, 1))));
    }

    @Test
    void displacedWaterBalancesWeightAtStaticEquilibrium() {
        Hull hull = hull(3, false, Map.of());
        Vessel boat = atWaterline(hull);
        simulate(boat, 12, 0, 0);
        assertEquals(boat.mass() / Ocean.WATER_DENSITY, boat.displaced(), .0001);
        assertEquals(0, boat.velocity().length(), 1e-6);
    }

    @Test
    void loadedVesselDisplacesMoreWaterAndDrawsMoreDraft() {
        Hull light = hull(3, false, Map.of());
        Hull heavy = hull(3, false, Map.of(new Hull.Grid(0, 0, 0), BALLAST));
        var before = Hydrostatics.analyze(light);
        var after = Hydrostatics.analyze(heavy);
        assertTrue(after.draft() > before.draft() + .06);
        Vessel loaded = atWaterline(heavy);
        simulate(loaded, 8, 0, 0);
        assertEquals(loaded.mass() / Ocean.WATER_DENSITY, loaded.displaced(), .001);
    }

    @Test
    void shiftingBallastChangesTheSignOfTheHeel() {
        Vessel port = atWaterline(hull(3, false, Map.of(new Hull.Grid(-2, 1, 0), BALLAST)));
        Vessel starboard = atWaterline(hull(3, false, Map.of(new Hull.Grid(2, 1, 0), BALLAST)));
        simulate(port, 36, 0, 0);
        simulate(starboard, 36, 0, 0);
        double portRoll = port.rotation().transform(new Vector3d(1, 0, 0)).y;
        double starboardRoll = starboard.rotation().transform(new Vector3d(1, 0, 0)).y;
        assertTrue(portRoll > .03, () -> "Port heel: " + portRoll);
        assertEquals(-portRoll, starboardRoll, .002);
    }

    @Test
    void negativeMetacentricHeightCanLeadToCapsize() {
        var weight = new HashMap<Hull.Grid, Hull.Material>();
        for (int z = -2; z <= 2; z++) weight.put(new Hull.Grid(0, 4, z), BALLAST);
        Hull narrow = hull(1, false, weight);
        var report = Hydrostatics.analyze(narrow);
        assertTrue(report.floats());
        assertTrue(report.transverseGm() < 0);
        Vessel unstable = atWaterline(narrow);
        unstable.rotation().rotateZ(.055);
        simulate(unstable, 32, 0, 0);
        assertTrue(unstable.rotation().transform(new Vector3d(0, 1, 0)).y < 0);
    }

    @Test
    void gerstnerElevationIsSharedBetweenVisualAndPhysicalSampling() {
        var frame = new Ocean.Frame(20, .8);
        var sample = new Ocean.Water();
        frame.sample(15.25, -57.2, 62.89, .75, sample);
        assertEquals(sample.height, frame.elevation(15.25, -57.2, 62.89, .75));
        assertTrue(frame.amplitude() > new Ocean.Frame(20, 0).amplitude() * 4);
        assertNotEquals(sample.height, new Ocean.Frame(20.4, .8).elevation(15.25, -57.2, 62.89, .75));
    }

    @Test
    void vesselHeavesAndPitchesUnderPassingWaveFronts() {
        Vessel boat = atWaterline(hull(2, false, Map.of()));
        var origin = new Vector3d();
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double highestPitch = 0;
        for (int i = 0; i < 800; i++) {
            var frame = new Ocean.Frame(i * Vessel.STEP, .85);
            boat.step((x, y, z, sample) -> frame.sample(x, z, -frame.amplitude() - .14, 1, sample),
                    frame.windSpeed(), 0, 0, null);
            boat.origin(origin);
            minY = Math.min(minY, origin.y);
            maxY = Math.max(maxY, origin.y);
            highestPitch = Math.max(highestPitch, Math.abs(boat.rotation().transform(new Vector3d(0, 0, 1)).y));
        }
        assertTrue(maxY - minY > .28);
        assertTrue(highestPitch > .022);
    }

    @Test
    void breachFloodsGraduallyAndBulkheadRestrictsTheFlood() {
        var opening = new Hull.Grid(0, 0, -2);
        Hull divided = hull(2, true, Map.of());
        Hull open = hull(2, false, Map.of());
        Vessel a = atWaterline(divided);
        Vessel b = atWaterline(open);
        assertTrue(a.breach(divided.indexOf(opening)));
        assertTrue(b.breach(open.indexOf(opening)));
        simulate(a, .5, 0, 0);
        assertTrue(a.waterVolume() > 0 && a.waterVolume() < 2);
        assertTrue(a.mass() > divided.dryMass());
        simulate(a, 40, 0, 0);
        simulate(b, 40.5, 0, 0);
        assertEquals(0, a.floodingVolumes()[1], 1e-8);
        assertTrue(b.waterVolume() > a.waterVolume() + 1);
        assertTrue(a.repair(divided.indexOf(opening)));
        double trapped = a.waterVolume();
        simulate(a, 2, 0, 0);
        assertEquals(trapped, a.waterVolume());
    }

    @Test
    void breachedBulkheadTransfersWaterBetweenCompartments() {
        Hull hull = hull(2, true, Map.of());
        Vessel boat = atWaterline(hull);
        assertTrue(boat.breach(hull.indexOf(new Hull.Grid(0, 0, -2))));
        simulate(boat, 12, 0, 0);
        assertEquals(0, boat.floodingVolumes()[1]);
        assertTrue(boat.breach(hull.indexOf(new Hull.Grid(0, 1, 0))));
        simulate(boat, 9, 0, 0);
        assertTrue(boat.floodingVolumes()[1] > .1);
    }

    @Test
    void lossOfReserveBuoyancySinksAnInitiallyFloatingHull() {
        var ballast = new HashMap<Hull.Grid, Hull.Material>();
        for (int z = -5; z <= 5; z++) ballast.put(new Hull.Grid(0, 0, z), BALLAST);
        Hull hull = hull(2, false, ballast);
        assertTrue(Hydrostatics.analyze(hull).floats());
        Vessel boat = atWaterline(hull);
        assertTrue(boat.breach(hull.indexOf(new Hull.Grid(-1, 0, 0))));
        assertTrue(boat.breach(hull.indexOf(new Hull.Grid(1, 0, 0))));
        simulate(boat, 34, 0, 0);
        assertTrue(boat.mass() > 165 * Ocean.WATER_DENSITY);
        assertTrue(boat.origin(new Vector3d()).y < -6);
    }

    @Test
    void workingPumpDrainsRepairedCompartment() {
        Hull hull = hull(2, false, Map.of(new Hull.Grid(0, 1, 2), PUMP, new Hull.Grid(0, 1, 3), TANK));
        Vessel boat = atWaterline(hull);
        int opening = hull.indexOf(new Hull.Grid(0, 0, -2));
        assertTrue(boat.breach(opening));
        simulate(boat, 8, 0, 0);
        assertTrue(boat.waterVolume() > .5);
        assertTrue(boat.repair(opening));
        double before = boat.waterVolume();
        simulate(boat, 5, 0, 0);
        assertEquals(before - .9, boat.waterVolume(), .01);
        assertTrue(boat.fuel() < 250);
    }

    @Test
    void twoDieselsProvideGreaterAccelerationAndCoastWhenCut() {
        var single = Map.of(new Hull.Grid(0, 1, 2), DIESEL, new Hull.Grid(0, 0, 5), PROPELLER,
                new Hull.Grid(0, 1, 1), TANK);
        var twin = new HashMap<>(single);
        twin.put(new Hull.Grid(1, 1, 2), DIESEL);
        twin.put(new Hull.Grid(1, 0, 5), PROPELLER);
        Vessel a = atWaterline(hull(3, false, single));
        Vessel b = atWaterline(hull(3, false, twin));
        simulate(a, 7, 1, 0);
        simulate(b, 7, 1, 0);
        double moving = b.velocity().length();
        assertTrue(moving > a.velocity().length() * 1.3);
        assertTrue(b.fuel() < Propulsion.TANK_LITRES);
        simulate(b, .05, 0, 0);
        assertTrue(b.velocity().length() > moving * .95);
        simulate(b, 18, 0, 0);
        assertTrue(b.velocity().length() < moving * .5);
    }

    @Test
    void savedFloodingAndBodyStateRestoreWithoutMovingTheBlueprintOrigin() {
        Hull hull = hull(2, true, Map.of());
        Vessel original = atWaterline(hull);
        original.breach(hull.indexOf(new Hull.Grid(0, 0, -2)));
        simulate(original, 12, 0, 0);
        Vector3d origin = original.origin(new Vector3d());
        Vessel loaded = new Vessel(hull, new Vector3d());
        loaded.restore(origin, new Quaterniond(original.rotation()), new Vector3d(original.velocity()),
                new Vector3d(original.angularVelocity()), original.breaches(), original.floodingVolumes(),
                original.fuel(), original.rpm(), original.temperature(), original.wear());
        assertEquals(original.breaches(), loaded.breaches());
        assertArrayEquals(original.floodingVolumes(), loaded.floodingVolumes());
        assertEquals(original.mass(), loaded.mass(), .001);
        assertEquals(0, loaded.origin(new Vector3d()).distance(origin), 1e-10);
    }

    private static void simulate(Vessel boat, double seconds, double throttle, double steer) {
        for (int i = 0; i < Math.round(seconds / Vessel.STEP); i++) {
            boat.step(STILL_WATER, 0, throttle, steer, null);
        }
    }

    private static Vessel atWaterline(Hull hull) {
        return new Vessel(hull, new Vector3d(0, -Hydrostatics.analyze(hull).waterline(), 0));
    }

    private static Hull hull(int halfWidth, boolean division, Map<Hull.Grid, Hull.Material> equipment) {
        var cells = new HashMap<Hull.Grid, Hull.Material>();
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -5; z <= 5; z++) {
                    if (Math.abs(x) == halfWidth || y == 0 || y == 2 || Math.abs(z) == 5 || division && z == 0) {
                        cells.put(new Hull.Grid(x, y, z), TIMBER);
                    }
                }
            }
        }
        cells.putAll(equipment);
        return new Hull(Hull.sorted(cells.entrySet().stream()
                .map(entry -> new Hull.Cell(entry.getKey(), entry.getValue())).toList()));
    }
}

