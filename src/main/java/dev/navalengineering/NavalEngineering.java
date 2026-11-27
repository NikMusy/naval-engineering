package dev.navalengineering;

import dev.navalengineering.construction.ShipBlock;
import dev.navalengineering.construction.ProfileBlock;
import dev.navalengineering.construction.ShipCommands;
import dev.navalengineering.network.BoardRequest;
import dev.navalengineering.network.CrewInput;
import dev.navalengineering.ship.DeckBoarding;
import dev.navalengineering.ship.ShipContacts;
import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public final class NavalEngineering implements ModInitializer {
    public static final String ID = "naval_engineering";
    public static final EntityType<ShipEntity> SHIP = Registry.register(Registries.ENTITY_TYPE, id("ship"),
            EntityType.Builder.create(ShipEntity::new, SpawnGroup.MISC)
                    .dimensions(1.15f, 1.45f).maxTrackingRange(10).trackingTickInterval(1).build("ship"));
    public static final ShipBlock PLATING = block("plating", Hull.Material.PLATING, Blocks.IRON_BLOCK);
    public static final ShipBlock HELM = block("helm", Hull.Material.HELM, Blocks.OAK_PLANKS);
    public static final ShipBlock DIESEL = block("diesel", Hull.Material.DIESEL, Blocks.IRON_BLOCK);
    public static final ShipBlock PROPELLER = block("propeller", Hull.Material.PROPELLER, Blocks.IRON_BLOCK);
    public static final ShipBlock TANK = block("tank", Hull.Material.TANK, Blocks.IRON_BLOCK);
    public static final ShipBlock PUMP = block("pump", Hull.Material.PUMP, Blocks.IRON_BLOCK);
    public static final ShipBlock NAVY_PANEL = block("navy_panel", Hull.Material.TRIM, Blocks.IRON_BLOCK);
    public static final ShipBlock IVORY_PANEL = block("ivory_panel", Hull.Material.TRIM, Blocks.IRON_BLOCK);
    public static final ShipBlock RED_PRIMER = block("red_primer", Hull.Material.TRIM, Blocks.IRON_BLOCK);
    public static final ProfileBlock BOW_PLATE = profile("bow_plate", Hull.Material.TRIM, HullGeometry.Profile.BOW, Blocks.IRON_BLOCK);
    public static final ProfileBlock STEM = profile("stem", Hull.Material.TRIM, HullGeometry.Profile.STEM, Blocks.IRON_BLOCK);
    public static final ProfileBlock CHINE = profile("chine", Hull.Material.PLATING, HullGeometry.Profile.CHINE, Blocks.IRON_BLOCK);
    public static final ProfileBlock SHOULDER = profile("shoulder", Hull.Material.TRIM, HullGeometry.Profile.SHOULDER, Blocks.IRON_BLOCK);
    public static final ProfileBlock BERTH = profile("berth", Hull.Material.FIXTURE, HullGeometry.Profile.BERTH, Blocks.SPRUCE_PLANKS);
    public static final ShipBlock LOCKER = block("locker", Hull.Material.FIXTURE, Blocks.SPRUCE_PLANKS);
    public static final SoundEvent ENGINE_LOOP = sound("engine_loop");
    public static final SoundEvent OCEAN_LOOP = sound("ocean_loop");

    public static Identifier id(String path) {
        return Identifier.of(ID, path);
    }

    private static ShipBlock block(String name, Hull.Material material, Block sample) {
        var block = new ShipBlock(material, AbstractBlock.Settings.copy(sample));
        registerBlock(name, block);
        return block;
    }

    private static ProfileBlock profile(String name, Hull.Material material, HullGeometry.Profile profile, Block sample) {
        var block = new ProfileBlock(material, profile, AbstractBlock.Settings.copy(sample));
        registerBlock(name, block);
        return block;
    }

    private static void registerBlock(String name, Block block) {
        Registry.register(Registries.BLOCK, id(name), block);
        Registry.register(Registries.ITEM, id(name), new BlockItem(block, new Item.Settings()));
    }

    private static SoundEvent sound(String name) {
        Identifier id = id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS)
                .register(entries -> {
                    for (ShipBlock block : new ShipBlock[]{PLATING, HELM, DIESEL, PROPELLER, TANK, PUMP,
                            NAVY_PANEL, IVORY_PANEL, RED_PRIMER, BOW_PLATE, STEM, CHINE, SHOULDER, BERTH, LOCKER}) {
                        entries.add(block);
                    }
                });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> ShipCommands.register(dispatcher));
        ServerTickEvents.END_WORLD_TICK.register(ShipContacts::solve);
        ServerTickEvents.END_WORLD_TICK.register(DeckBoarding::trackLandings);
        PayloadTypeRegistry.playC2S().register(CrewInput.ID, CrewInput.CODEC);
        PayloadTypeRegistry.playC2S().register(BoardRequest.ID, BoardRequest.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CrewInput.ID, (input, context) -> {
            if (context.player().getVehicle() instanceof ShipEntity ship) {
                ship.acceptCrewInput(context.player(), input);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BoardRequest.ID, (input, context) ->
                DeckBoarding.accept(context.player(), input.shipId()));
    }
}
