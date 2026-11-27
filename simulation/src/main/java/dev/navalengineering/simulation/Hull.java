package dev.navalengineering.simulation;

import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class Hull {
    public static final int MAX_BLOCKS = 1600;
    public static final int MAX_SPAN = 35;
    public static final int MAX_INTERIOR = 2048;
    public static final int OUTSIDE = -1;
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    public enum Material {
        TIMBER(525, 1.0),
        PLATING(705, 2.8),
        BALLAST(7870, 3.2),
        HELM(185, 1.2),
        DIESEL(1320, 2.0),
        PROPELLER(325, 2.0),
        TANK(445, 1.8),
        PUMP(245, 1.6),
        TRIM(310, 1.6),
        GLAZING(145, .55),
        FIXTURE(120, .9);

        private final double mass;
        private final double strength;

        Material(double mass, double strength) {
            this.mass = mass;
            this.strength = strength;
        }

        public double mass() { return mass; }
        public double strength() { return strength; }
    }

    public record Grid(int x, int y, int z) {
        public Grid {
            if (Math.abs((long) x) >= MAX_SPAN || Math.abs((long) y) >= MAX_SPAN
                    || Math.abs((long) z) >= MAX_SPAN) {
                throw new IllegalArgumentException("Cell outside blueprint bounds");
            }
        }

        Vector3d vector(Vector3d out) {
            return out.set(x, y, z);
        }
    }

    public record Cell(Grid position, Material material, HullGeometry geometry) {
        public Cell(Grid position, Material material) { this(position, material, HullGeometry.FULL); }
        public double mass() { return material.mass() * geometry.volume(); }
    }

    public record Boundary(int blockIndex, List<Integer> spaces) {}

    private final List<Cell> blocks;
    private final List<List<Grid>> compartments;
    private final List<Boundary> boundaries;
    private final List<Integer> exposed;
    private final Map<Grid, Integer> index;
    private final int interiorVolume;
    private final double radius;

    public Hull(List<Cell> blocks) {
        if (blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("Expected 1–" + MAX_BLOCKS + " hull blocks");
        }
        this.blocks = List.copyOf(blocks);
        index = new HashMap<>();
        int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
        int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
        double radiusSquared = 0;
        for (int i = 0; i < blocks.size(); i++) {
            var p = blocks.get(i).position;
            if (index.put(p, i) != null) throw new IllegalArgumentException("Duplicate hull cell");
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
            radiusSquared = Math.max(radiusSquared, (double) p.x * p.x + p.y * p.y + p.z * p.z);
        }
        radius = Math.sqrt(radiusSquared) + Math.sqrt(3) / 2;
        int nx = maxX - minX + 3, ny = maxY - minY + 3, nz = maxZ - minZ + 3;
        if (Math.max(nx, Math.max(ny, nz)) > MAX_SPAN + 2) {
            throw new IllegalArgumentException("Hull exceeds " + MAX_SPAN + " blocks on one axis");
        }

        var lattice = new Lattice(minX - 1, minY - 1, minZ - 1, nx, ny, nz);
        for (Cell block : blocks) lattice.set(block.position, Lattice.SOLID);
        lattice.fill(0, OUTSIDE, null);

        var rooms = new ArrayList<List<Grid>>();
        int volume = 0;
        for (int i = 0; i < lattice.spaces.length; i++) {
            if (lattice.spaces[i] != Lattice.UNVISITED) continue;
            var room = new ArrayList<Grid>();
            lattice.fill(i, rooms.size(), room);
            volume += room.size();
            if (volume > MAX_INTERIOR) throw new IllegalArgumentException("Enclosed volume exceeds " + MAX_INTERIOR);
            rooms.add(List.copyOf(room));
        }
        compartments = List.copyOf(rooms);
        interiorVolume = volume;

        var links = new ArrayList<Boundary>();
        var outer = new ArrayList<Integer>();
        for (int i = 0; i < blocks.size(); i++) {
            Grid p = blocks.get(i).position;
            var spaces = new LinkedHashSet<Integer>();
            for (int[] d : DIRECTIONS) {
                int space = lattice.at(p.x + d[0], p.y + d[1], p.z + d[2]);
                if (space != Lattice.SOLID) spaces.add(space);
            }
            if (spaces.contains(OUTSIDE)) outer.add(i);
            if (spaces.size() > 1) links.add(new Boundary(i, spaces.stream().sorted().toList()));
        }
        boundaries = List.copyOf(links);
        exposed = List.copyOf(outer);
    }

    public List<Cell> blocks() { return blocks; }
    public List<List<Grid>> compartments() { return compartments; }
    public List<Boundary> boundaries() { return boundaries; }
    public List<Integer> exposed() { return exposed; }
    public int interiorVolume() { return interiorVolume; }
    public double radius() { return radius; }

    public int indexOf(Grid position) {
        return index.getOrDefault(position, -1);
    }

    public double dryMass() {
        return blocks.stream().mapToDouble(Cell::mass).sum();
    }

    public List<Grid> displacementCells() {
        var result = new ArrayList<Grid>(blocks.size() + interiorVolume);
        for (Cell block : blocks) result.add(block.position);
        for (List<Grid> room : compartments) result.addAll(room);
        return result;
    }

    public static List<Cell> sorted(List<Cell> blocks) {
        return blocks.stream().sorted(Comparator.comparingInt((Cell b) -> b.position.y)
                .thenComparingInt(b -> b.position.z).thenComparingInt(b -> b.position.x)).toList();
    }

    private static final class Lattice {
        private static final int UNVISITED = -3;
        private static final int SOLID = -2;
        private final int ox, oy, oz, nx, ny, nz;
        private final int[] spaces;

        Lattice(int ox, int oy, int oz, int nx, int ny, int nz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            spaces = new int[nx * ny * nz];
            java.util.Arrays.fill(spaces, UNVISITED);
        }

        private int id(int x, int y, int z) {
            return (y * nz + z) * nx + x;
        }

        void set(Grid p, int value) {
            spaces[id(p.x - ox, p.y - oy, p.z - oz)] = value;
        }

        int at(int x, int y, int z) {
            return spaces[id(x - ox, y - oy, z - oz)];
        }

        void fill(int seed, int value, List<Grid> room) {
            var queue = new ArrayDeque<Integer>();
            spaces[seed] = value;
            queue.add(seed);
            while (!queue.isEmpty()) {
                int next = queue.remove();
                int x = next % nx;
                int z = (next / nx) % nz;
                int y = next / (nx * nz);
                if (room != null) room.add(new Grid(x + ox, y + oy, z + oz));
                for (int[] direction : DIRECTIONS) {
                    int dx = x + direction[0], dy = y + direction[1], dz = z + direction[2];
                    if (dx < 0 || dx >= nx || dy < 0 || dy >= ny || dz < 0 || dz >= nz) continue;
                    int candidate = id(dx, dy, dz);
                    if (spaces[candidate] == UNVISITED) {
                        spaces[candidate] = value;
                        queue.add(candidate);
                    }
                }
            }
        }
    }
}
