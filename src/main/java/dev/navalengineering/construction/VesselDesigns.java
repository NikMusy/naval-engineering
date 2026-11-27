package dev.navalengineering.construction;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.simulation.Hull;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Direction;

import java.util.HashMap;
import java.util.Map;

public enum VesselDesigns {
    MERIDIAN("meridian", -16, 16, 6, -8, 3, NavalEngineering.IVORY_PANEL, NavalEngineering.NAVY_PANEL),
    SQUALL("squall", -10, 9, 3, -4, 1, Blocks.YELLOW_CONCRETE, NavalEngineering.RED_PRIMER);

    private final String id;
    private final int bow, stern, hatch, bridgeBow, bridgeStern;
    private final Block superstructure, stripe;

    VesselDesigns(String id, int bow, int stern, int hatch, int bridgeBow, int bridgeStern,
                  Block superstructure, Block stripe) {
        this.id = id;
        this.bow = bow;
        this.stern = stern;
        this.hatch = hatch;
        this.bridgeBow = bridgeBow;
        this.bridgeStern = bridgeStern;
        this.superstructure = superstructure;
        this.stripe = stripe;
    }

    public String id() { return id; }

    public Blueprint build() {
        var g = new HashMap<Hull.Grid, BlockState>();
        buildHull(g);
        buildBridge(g);
        buildCabins(g);
        buildMachinery(g);
        buildUpperWorks(g);
        return Blueprint.create(g);
    }

    private void buildHull(Map<Hull.Grid, BlockState> g) {
        for (int z = bow; z <= stern; z++) {
            for (int y = -7; y <= -2; y++) {
                int w = width(z, y);
                for (int x = -w; x <= w; x++) {
                    boolean shell = Math.abs(x) == w || z == stem(y) || z == stern
                            || Math.abs(x) > width(z, y - 1)
                            || Math.abs(x) > Math.min(width(z - 1, y), width(z + 1, y));
                    if (y == -7 || y == -5 || y == -2 || shell || (y == -6 && (z == -3 || z == 4))) {
                        Block material = y == -2 ? (shell ? stripe : Blocks.SPRUCE_PLANKS)
                                : y == -5 && !shell ? Blocks.SPRUCE_PLANKS
                                : y == -4 && shell ? stripe
                                : y == -3 && shell ? superstructure
                                : y < -5 && x != 0 ? NavalEngineering.RED_PRIMER
                                : NavalEngineering.PLATING;
                        g.put(new Hull.Grid(x, y, z), plating(x, y, z, w, material));
                    }
                }
            }
            int side = width(z, -2);
            if (side >= 2 && z != stern - 1 && (z < bridgeBow || z > bridgeStern)) {
                for (int x : new int[]{-side, side}) {
                    g.put(new Hull.Grid(x, -1, z), plating(x, -2, z, side, stripe));
                }
            }
        }
        for (int step = 0; step < 3; step++) {
            int z = hatch + step;
            for (int y = -2; y > -3 - step; y--) g.remove(new Hull.Grid(0, y, z));
            put(g, 0, -3 - step, z, Blocks.SPRUCE_PLANKS);
        }
        put(g, -1, -1, hatch, Blocks.YELLOW_CONCRETE);
        put(g, 1, -1, hatch, Blocks.YELLOW_CONCRETE);
        put(g, -1, -7, 1, Blocks.IRON_BLOCK);
        put(g, 1, -7, 1, Blocks.IRON_BLOCK);
        if (this == SQUALL) {
            for (int z = -5; z <= 4; z++) put(g, 0, -8, z, Blocks.IRON_BLOCK);
        }
    }

    private BlockState plating(int x, int y, int z, int width, Block material) {
        if (z == stem(y) && x == 0) return NavalEngineering.STEM.facing(Direction.NORTH);
        if (x != 0 && Math.abs(x) == width) {
            if (width > width(z - 1, y)) {
                return NavalEngineering.BOW_PLATE.facing(x > 0 ? Direction.NORTH : Direction.WEST);
            }
            if (width > width(z + 1, y) && z < stern) {
                return NavalEngineering.BOW_PLATE.facing(x > 0 ? Direction.EAST : Direction.SOUTH);
            }
            if (y == -6 && width > width(z, y - 1)) {
                return NavalEngineering.CHINE.facing(x > 0 ? Direction.NORTH : Direction.SOUTH);
            }
        }
        return material.getDefaultState();
    }

    private void buildBridge(Map<Hull.Grid, BlockState> g) {
        for (int z = bridgeBow; z <= bridgeStern; z++) {
            int side = this == MERIDIAN && z <= -3 ? 4 : 2;
            for (int x = -side; x <= side; x++) {
                put(g, x, -1, z, Blocks.SPRUCE_PLANKS);
                if (Math.abs(x) == side && z != 2) {
                    oriented(g, x, 2, z, NavalEngineering.SHOULDER, x > 0 ? Direction.NORTH : Direction.SOUTH);
                } else {
                    put(g, x, 2, z, this == MERIDIAN && Math.abs(x) < side && z > bridgeBow && z < bridgeStern
                            ? NavalEngineering.IVORY_PANEL : stripe);
                }
                boolean rim = Math.abs(x) == side || z == bridgeBow || z == bridgeStern;
                boolean wing = this == MERIDIAN && z == -3 && Math.abs(x) > 2;
                boolean doorway = (x == 0 && z == bridgeStern) || (Math.abs(x) == side && z == -1);
                if ((rim || wing) && !doorway) {
                    put(g, x, 0, z, Math.abs(x) == side && z % 2 == 0 ? superstructure : Blocks.LIGHT_BLUE_STAINED_GLASS);
                    put(g, x, 1, z, Blocks.LIGHT_BLUE_STAINED_GLASS);
                    if (Math.abs(x) == side && (z == bridgeBow || wing)) {
                        Direction cut = z == bridgeBow ? (x > 0 ? Direction.NORTH : Direction.WEST)
                                : (x > 0 ? Direction.EAST : Direction.SOUTH);
                        oriented(g, x, 0, z, NavalEngineering.BOW_PLATE, cut);
                    }
                }
            }
        }
        put(g, 0, 0, 0, NavalEngineering.HELM);
        put(g, -2, 1, bridgeBow, Blocks.RED_CONCRETE);
        put(g, 2, 1, bridgeBow, Blocks.SEA_LANTERN);
        put(g, 0, 2, bridgeBow, Blocks.SEA_LANTERN);
    }

    private void buildCabins(Map<Hull.Grid, BlockState> g) {
        if (this == MERIDIAN) {
            for (int side : new int[]{-1, 1}) {
                for (int z = -7; z <= -3; z++) {
                    if (z == -4) continue;
                    for (int y = 0; y <= 1; y++) put(g, side, y, z, NavalEngineering.IVORY_PANEL);
                }
                oriented(g, side * 3, 0, -7, NavalEngineering.BERTH, Direction.NORTH);
                oriented(g, side * 3, 0, -6, NavalEngineering.BERTH, Direction.SOUTH);
                put(g, side * 3, 0, -5, NavalEngineering.LOCKER);
                put(g, side * 3, 0, -3, NavalEngineering.NAVY_PANEL);
                put(g, side * 2, 2, -5, Blocks.SEA_LANTERN);
            }
            put(g, 0, 2, -5, Blocks.SEA_LANTERN);
            for (int y = 0; y <= 1; y++) {
                for (int x : new int[]{-2, -1, 1, 2}) put(g, x, y, -2, NavalEngineering.IVORY_PANEL);
            }
            for (int level = 0; level < 4; level++) put(g, -3, -1 + level, 5 - level, NavalEngineering.NAVY_PANEL);
        } else {
            for (int x : new int[]{-1, 1}) {
                for (int y = -4; y <= -3; y++) put(g, x, y, -2, superstructure);
                oriented(g, x, -4, -5, NavalEngineering.BERTH, Direction.NORTH);
                put(g, x, -4, -4, NavalEngineering.LOCKER);
                put(g, x, -2, -3, Blocks.SEA_LANTERN);
            }
        }
    }

    private void buildMachinery(Map<Hull.Grid, BlockState> g) {
        for (int x : new int[]{-2, 2}) {
            for (int z = hatch - 3; z <= hatch - 1; z++) put(g, x, -4, z, NavalEngineering.DIESEL);
            put(g, x, -4, hatch - 4, NavalEngineering.TANK);
            put(g, x, -4, hatch + 1, NavalEngineering.PUMP);
            put(g, x, -2, hatch - 2, Blocks.SEA_LANTERN);
        }
        for (int x : new int[]{-1, 1}) {
            for (int z = stern - 4; z <= stern - 2; z++) put(g, x, -7, z, NavalEngineering.PROPELLER);
        }
    }

    private void buildUpperWorks(Map<Hull.Grid, BlockState> g) {
        put(g, 0, -1, bow + 2, Blocks.GRAY_CONCRETE);
        put(g, 0, -1, bow + 3, NavalEngineering.PUMP);
        if (this == MERIDIAN) {
            for (int y = 3; y <= 5; y++) put(g, 0, y, -2, Blocks.GRAY_CONCRETE);
            for (int x = -2; x <= 2; x++) put(g, x, 4, -2, x == 0 ? Blocks.GRAY_CONCRETE : NavalEngineering.IVORY_PANEL);
            put(g, 0, 6, -2, Blocks.SEA_LANTERN);
            for (int y = -1; y <= 2; y++) put(g, 2, y, stern - 4, Blocks.YELLOW_CONCRETE);
            for (int x = -1; x <= 2; x++) put(g, x, 2, stern - 4, Blocks.YELLOW_CONCRETE);
            put(g, -1, 1, stern - 4, Blocks.GRAY_CONCRETE);
            for (int x : new int[]{-2, 2}) put(g, x, -1, stern - 2, Blocks.SEA_LANTERN);
            put(g, 2, 3, -5, NavalEngineering.LOCKER);
        } else {
            for (int x : new int[]{-1, 1}) {
                put(g, x, 3, 0, Blocks.YELLOW_CONCRETE);
                put(g, x, 4, 0, Blocks.BLACK_CONCRETE);
                put(g, x, -1, stern - 2, Blocks.GRAY_CONCRETE);
            }
            put(g, 0, 3, -2, NavalEngineering.IVORY_PANEL);
            put(g, 0, 4, -2, Blocks.SEA_LANTERN);
        }
    }

    private int stem(int y) {
        return bow + switch (y) { case -7 -> 3; case -6 -> 2; case -5, -4 -> 1; default -> 0; };
    }

    private int width(int z, int y) {
        int prow = stem(y);
        if (z < prow || z > stern || y < -7) return -1;
        if (z == prow) return 0;
        int beam = this == MERIDIAN ? 4 : 3;
        int deck = Math.min(beam, Math.min(1 + (z - prow) / 2, 1 + (stern - z) / 2));
        return Math.max(0, deck - (y == -7 ? 2 : y == -6 ? 1 : 0));
    }

    private static void put(Map<Hull.Grid, BlockState> g, int x, int y, int z, Block block) {
        g.put(new Hull.Grid(x, y, z), block.getDefaultState());
    }

    private static void oriented(Map<Hull.Grid, BlockState> g, int x, int y, int z, ProfileBlock block, Direction facing) {
        g.put(new Hull.Grid(x, y, z), block.facing(facing));
    }
}
