package dev.navalengineering.construction;

import dev.navalengineering.simulation.Hull;
import net.minecraft.block.Block;

public final class ShipBlock extends Block {
    private final Hull.Material material;

    public ShipBlock(Hull.Material material, Settings settings) {
        super(settings);
        this.material = material;
    }

    public Hull.Material material() {
        return material;
    }
}

