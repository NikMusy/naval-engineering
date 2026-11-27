package dev.navalengineering.simulation;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HullGeometryTest {
    @Test
    void everyProfileHasConsistentVolumeAfterRotation() {
        for (var profile : HullGeometry.Profile.values()) {
            double expected = profile == HullGeometry.Profile.FULL ? 1 : .5;
            for (int angle = 0; angle < 4; angle++) {
                var shape = HullGeometry.of(profile, angle);
                assertEquals(expected, shape.volume(), 1e-10, profile.name());
                double parts = shape.boxes().stream().mapToDouble(b -> 8 * b.sx() * b.sy() * b.sz()).sum();
                assertEquals(shape.volume(), parts, 1e-10, profile.name());
            }
        }
    }

    @Test
    void chineLosesLessVolumeThanRectangularPlateAtTheSameWaterline() {
        var shape = HullGeometry.of(HullGeometry.Profile.CHINE, 0);
        var center = new Vector3d();
        assertEquals(.125, shape.submerged(new Vector3d(0, 1, 0), 0, center), 1e-10);
        assertEquals(-1.0 / 6, center.y, 1e-10);
        assertEquals(-1.0 / 3, center.x, 1e-10);
        assertEquals(.5, shape.submerged(new Vector3d(0, 1, 0), .6, center), 1e-10);
        assertEquals(0, shape.submerged(new Vector3d(0, 1, 0), -.6, center));
    }

    @Test
    void crewCollisionLeavesTheClippedCornerClear() {
        var shape = HullGeometry.of(HullGeometry.Profile.BOW, 0);
        DeckMotion.Space room = new DeckMotion.Space() {
            @Override public boolean solid(int x, int y, int z) { return x == 0 && y == 0 && z == 0; }
            @Override public List<HullGeometry.Box> boxes(int x, int y, int z) {
                return solid(x, y, z) ? shape.boxes() : List.of();
            }
        };
        assertTrue(DeckMotion.isClear(room, .44, -.5, -.44));
        assertFalse(DeckMotion.isClear(room, -.19, -.5, -.44));
    }
}
