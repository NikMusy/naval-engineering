package dev.navalengineering.simulation;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Convex profiles in coordinates centred on their construction block. */
public final class HullGeometry {
    private static final double EPS = 1e-9;
    private static final int STRIPS = 8;

    public enum Profile { FULL, BOW, STEM, CHINE, SHOULDER, BERTH }

    public record Point(double x, double y, double z) {
        Point rotate(int turns) {
            return switch (turns) {
                case 1 -> new Point(-z, y, x);
                case 2 -> new Point(-x, y, -z);
                case 3 -> new Point(z, y, -x);
                default -> this;
            };
        }

        double dot(Point b) { return x * b.x + y * b.y + z * b.z; }
        double dot(Vector3dc b) { return x * b.x() + y * b.y() + z * b.z(); }
        Point add(Point b) { return new Point(x + b.x, y + b.y, z + b.z); }
        Point sub(Point b) { return new Point(x - b.x, y - b.y, z - b.z); }
        Point mul(double s) { return new Point(x * s, y * s, z * s); }
        Point cross(Point b) { return new Point(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x); }
    }

    public record Face(Point normal, List<Point> vertices) {}

    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
        public double sx() { return (x1 - x0) * .5; }
        public double sy() { return (y1 - y0) * .5; }
        public double sz() { return (z1 - z0) * .5; }
        public double x() { return (x0 + x1) * .5; }
        public double y() { return (y0 + y1) * .5; }
        public double z() { return (z0 + z1) * .5; }
        public boolean contains(double x, double y, double z) {
            return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
        }

        Box rotate(int turns) {
            var a = new Point(x0, y0, z0).rotate(turns);
            var b = new Point(x1, y1, z1).rotate(turns);
            return new Box(Math.min(a.x, b.x), y0, Math.min(a.z, b.z),
                    Math.max(a.x, b.x), y1, Math.max(a.z, b.z));
        }
    }

    private record Plane(Point normal, double offset) {}
    private record Moments(double volume, Point center) {}

    private static final HullGeometry[][] VARIANTS = new HullGeometry[Profile.values().length][4];
    public static final HullGeometry FULL;

    static {
        for (Profile component : Profile.values()) {
            for (int rotation = 0; rotation < 4; rotation++) {
                VARIANTS[component.ordinal()][rotation] = new HullGeometry(component, rotation);
            }
        }
        FULL = of(Profile.FULL, 0);
    }

    private final List<Face> faces;
    private final List<Point> vertices;
    private final List<Box> boxes;
    private final Moments moments;

    public static HullGeometry of(Profile component, int rotation) {
        return VARIANTS[component.ordinal()][Math.floorMod(rotation, 4)];
    }

    private HullGeometry(Profile profile, int rotation) {
        var polyhedron = cubeFaces();
        for (Plane plane : planes(profile)) polyhedron = clip(polyhedron, plane);
        faces = new ArrayList<>();
        for (Face face : polyhedron) {
            faces.add(new Face(face.normal.rotate(rotation), face.vertices.stream().map(v -> v.rotate(rotation)).toList()));
        }
        vertices = faces.stream().flatMap(face -> face.vertices.stream()).distinct().toList();
        boxes = strips(profile).stream().map(box -> box.rotate(rotation)).toList();
        moments = moments(faces);
    }

    public List<Face> faces() { return List.copyOf(faces); }
    public List<Box> boxes() { return boxes; }
    public double volume() { return moments.volume; }
    public Point center() { return moments.center; }

    public double submerged(Vector3dc vertical, double level, Vector3d center) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Point v : vertices) {
            double height = v.dot(vertical);
            min = Math.min(min, height);
            max = Math.max(max, height);
        }
        if (level <= min) return 0;
        Moments wet = level >= max ? moments : moments(clip(faces,
                new Plane(new Point(vertical.x(), vertical.y(), vertical.z()), level)));
        center.set(wet.center.x, wet.center.y, wet.center.z);
        return wet.volume;
    }

    private static List<Plane> planes(Profile profile) {
        return switch (profile) {
            case FULL -> List.of();
            case BOW -> List.of(new Plane(new Point(1, 0, -1), 0));
            case STEM -> List.of(new Plane(new Point(1, 0, -.5), .25),
                    new Plane(new Point(-1, 0, -.5), .25));
            case CHINE -> List.of(new Plane(new Point(1, -1, 0), 0));
            case SHOULDER -> List.of(new Plane(new Point(1, 1, 0), 0));
            case BERTH -> List.of(new Plane(new Point(0, 1, 0), 0));
        };
    }

    private static List<Box> strips(Profile profile) {
        if (profile == Profile.FULL || profile == Profile.BERTH) {
            return List.of(new Box(-.5, -.5, -.5, .5, profile == Profile.BERTH ? 0 : .5, .5));
        }
        var result = new ArrayList<Box>(STRIPS);
        for (int i = 0; i < STRIPS; i++) {
            double lo = -.5 + (double) i / STRIPS;
            double hi = lo + 1.0 / STRIPS;
            double mid = (lo + hi) * .5;
            result.add(switch (profile) {
                case BOW -> new Box(-.5, -.5, lo, mid, .5, hi);
                case STEM -> new Box(-.25 - .5 * mid, -.5, lo, .25 + .5 * mid, .5, hi);
                case CHINE -> new Box(-.5, lo, -.5, mid, hi, .5);
                case SHOULDER -> new Box(-.5, lo, -.5, -mid, hi, .5);
                default -> throw new IllegalStateException("Profile has no strips");
            });
        }
        return result;
    }

    private static List<Face> cubeFaces() {
        var lll = new Point(-.5, -.5, -.5);
        var llh = new Point(-.5, -.5, .5);
        var lhl = new Point(-.5, .5, -.5);
        var lhh = new Point(-.5, .5, .5);
        var hll = new Point(.5, -.5, -.5);
        var hlh = new Point(.5, -.5, .5);
        var hhl = new Point(.5, .5, -.5);
        var hhh = new Point(.5, .5, .5);
        return List.of(new Face(new Point(-1, 0, 0), List.of(lll, llh, lhh, lhl)),
                new Face(new Point(1, 0, 0), List.of(hlh, hll, hhl, hhh)),
                new Face(new Point(0, -1, 0), List.of(llh, lll, hll, hlh)),
                new Face(new Point(0, 1, 0), List.of(lhl, lhh, hhh, hhl)),
                new Face(new Point(0, 0, -1), List.of(hll, lll, lhl, hhl)),
                new Face(new Point(0, 0, 1), List.of(llh, hlh, hhh, lhh)));
    }

    private static List<Face> clip(List<Face> input, Plane plane) {
        var result = new ArrayList<Face>();
        var intersection = new ArrayList<Point>();
        for (Face face : input) {
            var polygon = new ArrayList<Point>();
            Point prev = face.vertices.getLast();
            double distance = prev.dot(plane.normal) - plane.offset;
            for (Point cur : face.vertices) {
                double next = cur.dot(plane.normal) - plane.offset;
                if ((distance > EPS) != (next > EPS)) {
                    Point cut = prev.add(cur.sub(prev).mul(distance / (distance - next)));
                    append(polygon, cut);
                    if (intersection.stream().noneMatch(v -> same(v, cut))) intersection.add(cut);
                }
                if (next <= EPS) append(polygon, cur);
                prev = cur;
                distance = next;
            }
            if (polygon.size() > 1 && same(polygon.getFirst(), polygon.getLast())) polygon.removeLast();
            if (polygon.size() >= 3) result.add(new Face(face.normal, polygon));
        }
        if (intersection.size() >= 3) {
            Point center = intersection.stream().reduce(new Point(0, 0, 0), Point::add).mul(1.0 / intersection.size());
            Point n = plane.normal.mul(1 / Math.sqrt(plane.normal.dot(plane.normal)));
            Point basis = Math.abs(n.x) > .8 ? new Point(0, 1, 0) : new Point(1, 0, 0);
            Point u = n.cross(basis);
            Point v = n.cross(u);
            intersection.sort(Comparator.comparingDouble(p -> Math.atan2(p.sub(center).dot(v), p.sub(center).dot(u))));
            result.add(new Face(n, intersection));
        }
        return result;
    }

    private static Moments moments(List<Face> mesh) {
        double volume = 0;
        Point center = new Point(0, 0, 0);
        for (Face face : mesh) {
            Point a = face.vertices.getFirst();
            for (int i = 1; i < face.vertices.size() - 1; i++) {
                Point b = face.vertices.get(i);
                Point c = face.vertices.get(i + 1);
                double tetra = a.dot(b.cross(c)) / 6;
                volume += tetra;
                center = center.add(a.add(b).add(c).mul(tetra * .25));
            }
        }
        return volume < EPS ? new Moments(0, new Point(0, 0, 0))
                : new Moments(volume, center.mul(1 / volume));
    }

    private static void append(List<Point> polygon, Point p) {
        if (polygon.isEmpty() || !same(polygon.getLast(), p)) polygon.add(p);
    }

    private static boolean same(Point a, Point b) {
        return Math.abs(a.x - b.x) < EPS && Math.abs(a.y - b.y) < EPS && Math.abs(a.z - b.z) < EPS;
    }
}
