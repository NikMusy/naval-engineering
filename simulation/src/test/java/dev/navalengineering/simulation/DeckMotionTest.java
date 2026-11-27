package dev.navalengineering.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeckMotionTest {
    private static final DeckMotion.Input FORWARD = new DeckMotion.Input(0, 1, 0, false, false, false);
    private static final DeckMotion.Space CABIN = (x, y, z) -> y == -1 || y == 2 || x == -2 || x == 2 || z == 5;

    @Test
    void feetStayOnDeckWhileWalkingAndStopAtCabinWall() {
        var state = repeat(CABIN, DeckMotion.State.standing(0, -.5, 0), FORWARD, 100);
        assertEquals(4.201, state.z(), 1e-8);
        assertEquals(-.5, state.y(), 1e-8);
        assertTrue(state.grounded());
        assertTrue(DeckMotion.isClear(CABIN, state.x(), state.y(), state.z()));
    }

    @Test
    void jumpingStopsAtLowCeiling() {
        var jump = new DeckMotion.Input(0, 0, 0, true, false, false);
        var position = DeckMotion.tick(CABIN, DeckMotion.State.standing(0, -.5, 0), jump);
        assertEquals(-.3, position.y(), 1e-8);
        assertFalse(position.grounded());
        assertEquals(-.5, repeat(CABIN, position, DeckMotion.Input.IDLE, 9).y(), 1e-8);
    }

    @Test
    void stairwellAllowsDescentAndReturnWithoutWallPenetration() {
        DeckMotion.Space staircase = (x, y, z) -> y == -3 || z <= 1 && y == -1 || z == 2 && y == -2;
        var upper = DeckMotion.State.standing(0, -.5, 0);
        var lower = repeat(staircase, upper, FORWARD, 28);
        assertEquals(-2.5, lower.y(), 1e-8);
        var backward = new DeckMotion.Input(0, -1, 0, false, false, false);
        var backAtTop = repeat(staircase, lower, backward, 45);
        assertEquals(-.5, backAtTop.y(), 1e-8);
        assertTrue(backAtTop.z() < 1);
    }

    @Test
    void sneakPreventsSteppingOffAnEdgeButWalkingFalls() {
        DeckMotion.Space plank = (x, y, z) -> x == 0 && z == 0 && y == -1;
        var start = DeckMotion.State.standing(0, -.5, 0);
        var sneak = new DeckMotion.Input(0, 1, 0, false, false, true);
        assertEquals(-.5, repeat(plank, start, sneak, 40).y(), 1e-8);
        assertTrue(repeat(plank, start, FORWARD, 40).y() < -2);
    }

    @Test
    void landingSweepAndBrokenFloor() {
        DeckMotion.Space plank = (x, y, z) -> x == 0 && z == 0 && y == -1;
        var landing = DeckMotion.landing(plank, 0, .4, -1, 0);
        assertEquals(-.5, landing.y(), 1e-8);
        assertTrue(landing.grounded());
        assertNull(DeckMotion.landing(plank, 1, .4, -1, 0));
        assertTrue(DeckMotion.tick((x, y, z) -> false, landing, DeckMotion.Input.IDLE).y() < -.5);
    }

    private static DeckMotion.State repeat(DeckMotion.Space space, DeckMotion.State state, DeckMotion.Input input,
                                           int duration) {
        for (int i = 0; i < duration; i++) {
            state = DeckMotion.tick(space, state, input);
            assertTrue(DeckMotion.isClear(space, state.x(), state.y(), state.z()), state.toString());
        }
        return state;
    }
}

