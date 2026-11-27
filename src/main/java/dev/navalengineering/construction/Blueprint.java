package dev.navalengineering.construction;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.simulation.Hull;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record Blueprint(Hull hull, List<BlockState> states) {
    public Blueprint {
        states = List.copyOf(states);
        if (states.size() != hull.blocks().size()) throw new IllegalArgumentException("Mismatched hull palette");
    }

    public static Hull.Material material(BlockState state) {
        if (state.getBlock() instanceof ShipBlock block) return block.material();
        if (state.isOf(Blocks.IRON_BLOCK)) return Hull.Material.BALLAST;
        if (state.isIn(BlockTags.PLANKS) && !state.hasBlockEntity()) return Hull.Material.TIMBER;
        return null;
    }

    public static Blueprint create(Map<Hull.Grid, BlockState> grid) {
        List<Hull.Cell> cells = Hull.sorted(grid.entrySet().stream().map(entry -> {
            Hull.Material material = material(entry.getValue());
            if (material == null) throw new IllegalArgumentException("Unsupported block in blueprint");
            return new Hull.Cell(entry.getKey(), material);
        }).toList());
        return new Blueprint(new Hull(cells), cells.stream().map(cell -> grid.get(cell.position())).toList());
    }

    public NbtCompound encode() {
        var palette = new NbtList();
        var ids = new HashMap<Block, Integer>();
        int[] data = new int[states.size() * 4];
        for (int i = 0; i < states.size(); i++) {
            Hull.Grid p = hull.blocks().get(i).position();
            Block block = states.get(i).getBlock();
            int paletteId = ids.computeIfAbsent(block, key -> {
                palette.add(NbtString.of(Registries.BLOCK.getId(key).toString()));
                return palette.size() - 1;
            });
            data[4 * i] = p.x();
            data[4 * i + 1] = p.y();
            data[4 * i + 2] = p.z();
            data[4 * i + 3] = paletteId;
        }
        var tag = new NbtCompound();
        tag.putInt("Version", 1);
        tag.put("Palette", palette);
        tag.putIntArray("Cells", data);
        return tag;
    }

    public static Blueprint decode(NbtCompound tag) {
        int[] data = tag.getIntArray("Cells");
        var palette = tag.getList("Palette", NbtElement.STRING_TYPE);
        if (tag.getInt("Version") != 1 || data.length == 0 || data.length % 4 != 0
                || data.length > Hull.MAX_BLOCKS * 4 || palette.size() > Hull.MAX_BLOCKS) {
            throw new IllegalArgumentException("Invalid hull data");
        }
        var states = new ArrayList<BlockState>();
        for (int i = 0; i < palette.size(); i++) {
            Identifier id = Identifier.tryParse(palette.getString(i));
            if (id == null || !Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("Unknown hull block");
            states.add(Registries.BLOCK.get(id).getDefaultState());
        }
        var cells = new ArrayList<Hull.Cell>();
        var ordered = new ArrayList<BlockState>();
        for (int i = 0; i < data.length; i += 4) {
            if (data[i + 3] < 0 || data[i + 3] >= states.size()) throw new IllegalArgumentException("Invalid palette index");
            var p = new Hull.Grid(data[i], data[i + 1], data[i + 2]);
            BlockState state = states.get(data[i + 3]);
            Hull.Material material = material(state);
            if (material == null) throw new IllegalArgumentException("Invalid hull material");
            cells.add(new Hull.Cell(p, material));
            ordered.add(state);
        }
        return new Blueprint(new Hull(cells), ordered);
    }

    public static Blueprint demonstration() {
        var grid = new HashMap<Hull.Grid, BlockState>();
        for (int z = -5; z <= 5; z++) {
            for (int x = -2; x <= 2; x++) {
                for (int y = -3; y <= -1; y++) {
                    if (y != -3 && y != -1 && Math.abs(x) != 2 && Math.abs(z) != 5 && z != 0) continue;
                    grid.put(new Hull.Grid(x, y, z), NavalEngineering.PLATING.getDefaultState());
                }
            }
        }
        grid.put(new Hull.Grid(0, 0, 0), NavalEngineering.HELM.getDefaultState());
        grid.put(new Hull.Grid(0, -2, 3), NavalEngineering.DIESEL.getDefaultState());
        grid.put(new Hull.Grid(1, -2, 3), NavalEngineering.TANK.getDefaultState());
        grid.put(new Hull.Grid(0, -2, 2), NavalEngineering.PUMP.getDefaultState());
        grid.put(new Hull.Grid(0, -3, 5), NavalEngineering.PROPELLER.getDefaultState());
        return create(grid);
    }
}

