package dev.navalengineering.simulation;

import java.util.List;

/** Upright player collision in blueprint coordinates. A block at (x,y,z) spans the centred unit cube. */
public final class DeckMotion {
    private static final double RADIUS = .299;
    private static final double HEIGHT = 1.80;
    private static final double EPS = 1e-6;
    private static final double STEP_HEIGHT = 1.0001;

    private DeckMotion() {}

    @FunctionalInterface
    public interface Space {
        boolean solid(int x, int y, int z);

        default List<HullGeometry.Box> boxes(int x, int y, int z) {
            return solid(x, y, z) ? HullGeometry.FULL.boxes() : List.of();
        }
    }

    public record State(double x, double y, double z, double vy, boolean grounded) {
        public static State standing(double x, double y, double z) {
            return new State(x, y, z, 0, true);
        }

        public double distanceSquared(State other) {
            return square(x - other.x) + square(y - other.y) + square(z - other.z);
        }
    }

    public record Input(int strafe, int forward, double yaw, boolean jump, boolean sprint, boolean sneak) {
        public static final Input IDLE = new Input(0, 0, 0, false, false, false);
    }

    public static State tick(Space space, State state, Input input) {
        double metres = input.sneak ? .075 : input.sprint ? .281 : .216;
        double speed = metres / Math.max(1, Math.hypot(input.strafe, input.forward));
        double s = Math.sin(input.yaw), c = Math.cos(input.yaw);
        double dx = (-s * input.forward - c * input.strafe) * speed;
        double dz = (c * input.forward - s * input.strafe) * speed;

        // Recheck support when a plank is destroyed underneath a grounded player.
        boolean grounded = Math.abs(clip(space, state.x, state.y, state.z, 1, -.02)) < .02;
        boolean jump = grounded && input.jump;
        double dy = jump ? .42 : Math.max(-.88, (state.vy - .08) * .98);
        State level = horizontal(space, state, dx, dz);
        double directProgress = horizontalDistance(state, level);
        if (input.sneak && grounded && !jump && !hasFloor(space, level)) level = state;
        if (grounded && !jump && !input.sneak && directProgress + EPS < square(dx) + square(dz)) {
            double lift = clip(space, state.x, state.y, state.z, 1, STEP_HEIGHT);
            if (lift > .49) {
                State raised = horizontal(space, new State(state.x, state.y + lift, state.z, 0, false), dx, dz);
                double down = clip(space, raised.x, raised.y, raised.z, 1, -lift - .02);
                if (lift + down > .02 && horizontalDistance(state, raised) > directProgress + EPS) {
                    // Enter the same swept jump used by Space. A full block is climbed over several ticks.
                    dy = .42;
                }
            }
        }

        double movedY = clip(space, level.x, level.y, level.z, 1, dy);
        boolean blocked = Math.abs(dy - movedY) > EPS;
        return new State(level.x, level.y + movedY, level.z, blocked ? 0 : dy, blocked && dy < 0);
    }

    private static State horizontal(Space space, State start, double dx, double dz) {
        // Check the larger component first to slide along walls at diagonals.
        if (Math.abs(dx) >= Math.abs(dz)) {
            double x = start.x + clip(space, start.x, start.y, start.z, 0, dx);
            return new State(x, start.y, start.z + clip(space, x, start.y, start.z, 2, dz), 0, false);
        }
        double z = start.z + clip(space, start.x, start.y, start.z, 2, dz);
        return new State(start.x + clip(space, start.x, start.y, z, 0, dx), start.y, z, 0, false);
    }

    public static boolean isClear(Space space, double x, double y, double z) {
        for (int ix = cell(x - RADIUS + EPS); ix <= cell(x + RADIUS - EPS); ix++) {
            for (int iy = cell(y + EPS); iy <= cell(y + HEIGHT - EPS); iy++) {
                for (int iz = cell(z - RADIUS + EPS); iz <= cell(z + RADIUS - EPS); iz++) {
                    for (HullGeometry.Box box : space.boxes(ix, iy, iz)) {
                        if (overlap(x - RADIUS, x + RADIUS, ix + box.x0(), ix + box.x1())
                                && overlap(y, y + HEIGHT, iy + box.y0(), iy + box.y1())
                                && overlap(z - RADIUS, z + RADIUS, iz + box.z0(), iz + box.z1())) return false;
                    }
                }
            }
        }
        return true;
    }

    public static boolean hasFloor(Space space, State state) {
        return clip(space, state.x, state.y, state.z, 1, -.055) > -.055;
    }

    /** First floor crossed by the feet at the final horizontal position. */
    public static State landing(Space space, double x, double fromY, double toY, double z) {
        if (toY >= fromY || fromY - toY > 2.5 || !isClear(space, x, fromY, z)) return null;
        double moved = clip(space, x, fromY, z, 1, toY - fromY);
        if (fromY + moved <= toY + EPS || !isClear(space, x, fromY + moved, z)) return null;
        return State.standing(x, fromY + moved, z);
    }

    private static double clip(Space space, double x, double y, double z, int axis, double delta) {
        if (Math.abs(delta) < EPS) return 0;
        double x0 = x - RADIUS, x1 = x + RADIUS;
        double y0 = y, y1 = y + HEIGHT;
        double z0 = z - RADIUS, z1 = z + RADIUS;
        int minX = cell(x0 + (axis == 0 ? Math.min(0, delta) : 0) + EPS);
        int maxX = cell(x1 + (axis == 0 ? Math.max(0, delta) : 0) - EPS);
        int minY = cell(y0 + (axis == 1 ? Math.min(0, delta) : 0) + EPS);
        int maxY = cell(y1 + (axis == 1 ? Math.max(0, delta) : 0) - EPS);
        int minZ = cell(z0 + (axis == 2 ? Math.min(0, delta) : 0) + EPS);
        int maxZ = cell(z1 + (axis == 2 ? Math.max(0, delta) : 0) - EPS);

        double near = switch (axis) { case 0 -> x0; case 1 -> y0; default -> z0; };
        double far = switch (axis) { case 0 -> x1; case 1 -> y1; default -> z1; };
        for (int ix = minX; ix <= maxX; ix++) {
            for (int iy = minY; iy <= maxY; iy++) {
                for (int iz = minZ; iz <= maxZ; iz++) {
                    for (HullGeometry.Box box : space.boxes(ix, iy, iz)) {
                        if (axis != 0 && !overlap(x0, x1, ix + box.x0(), ix + box.x1())) continue;
                        if (axis != 1 && !overlap(y0, y1, iy + box.y0(), iy + box.y1())) continue;
                        if (axis != 2 && !overlap(z0, z1, iz + box.z0(), iz + box.z1())) continue;
                        double min = switch (axis) { case 0 -> ix + box.x0(); case 1 -> iy + box.y0(); default -> iz + box.z0(); };
                        double max = switch (axis) { case 0 -> ix + box.x1(); case 1 -> iy + box.y1(); default -> iz + box.z1(); };
                        if (delta > 0 && min >= far - EPS) {
                            delta = Math.min(delta, Math.max(0, min - far));
                        } else if (delta < 0 && max <= near + EPS) {
                            delta = Math.max(delta, Math.min(0, max - near));
                        }
                    }
                }
            }
        }
        return delta;
    }

    private static boolean overlap(double minA, double maxA, double minB, double maxB) {
        return maxA > minB + EPS && minA < maxB - EPS;
    }

    private static int cell(double n) { return (int) Math.floor(n + .5); }
    private static double square(double n) { return n * n; }
    private static double horizontalDistance(State a, State b) { return square(a.x - b.x) + square(a.z - b.z); }
}
