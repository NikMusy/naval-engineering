package dev.navalengineering.gametest;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.construction.Blueprint;
import dev.navalengineering.construction.VesselDesigns;
import dev.navalengineering.network.CrewInput;
import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.DeckMotion;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
import dev.navalengineering.simulation.Hydrostatics;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Map;

public final class VesselGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "naval_assembly", tickLimit = 90)
    public void launchConsumesConstructionBlocksAndRestsOnTerrain(TestContext test) {
        BlockPos helm = test.getAbsolutePos(new BlockPos(0, 5, 0));
        BlockPos hull = helm.down();
        var world = test.getWorld();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) world.setBlockState(helm.add(x, -3, z), Blocks.STONE.getDefaultState());
        }
        world.setBlockState(helm, NavalEngineering.HELM.getDefaultState());
        world.setBlockState(hull, Blocks.OAK_PLANKS.getDefaultState());
        world.setBlockState(hull.north(), Blocks.OAK_PLANKS.getDefaultState());
        world.setBlockState(hull.south(), Blocks.OAK_PLANKS.getDefaultState());
        command(test, "/naval inspect", helm);
        test.assertTrue(world.getBlockState(helm).isOf(NavalEngineering.HELM), "Inspection changed the dock");
        command(test, "/naval launch", helm);
        ShipEntity ship = find(test, helm);
        test.assertEquals(4, ship.blueprint().states().size(), "Hull assembly size");
        test.assertTrue(world.getBlockState(helm).isAir() && world.getBlockState(hull).isAir(),
                "Detached blocks remained in the world");
        test.runAtTick(70, () -> {
            test.assertTrue(ship.getY() > helm.getY() - 1.6, "Vessel fell through its stone support");
            test.assertTrue(ship.getY() < helm.getY() + .1, "Vessel did not fall onto land");
            ship.discard();
            test.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "naval_water", tickLimit = 200)
    public void demoFloatsFloodsAndReloads(TestContext test) {
        BlockPos anchor = test.getAbsolutePos(new BlockPos(0, 8, 0));
        var world = test.getWorld();
        for (int x = -12; x <= 12; x++) {
            for (int z = -22; z <= 22; z++) {
                for (int y = 0; y <= 10; y++) {
                    boolean wall = Math.abs(x) == 12 || Math.abs(z) == 22 || y == 0;
                    world.setBlockState(anchor.add(x, y, z), (wall ? Blocks.STONE : Blocks.WATER).getDefaultState(),
                            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
            }
        }

        command(test, "/naval demo", anchor.up(10));
        ShipEntity ship = find(test, anchor.up(14));
        test.assertTrue(ship.blueprint().hull().compartments().size() >= 2, "Sealed reserve compartments");
        var player = test.createMockCreativeServerPlayerInWorld();
        player.setPosition(ship.getPos());
        test.assertTrue(ship.board(player, DeckMotion.State.standing(0, -.5, 1)), "Could not board the bridge");
        test.assertTrue(player.getVehicle() == ship, "The helm did not accept a rider");

        ship.acceptCrewInput(player, new CrewInput(ship.getId(), 1, (byte) 0, (byte) 0, (short) 0, CrewInput.HELM));

        for (int tick = 8; tick < 40; tick++) {
            int sequence = tick;
            test.runAtTick(tick, () -> ship.acceptCrewInput(player,
                    new CrewInput(ship.getId(), sequence, (byte) 0, (byte) 1, (short) 0, (byte) 0)));
        }
        test.runAtTick(40, () -> {
            test.assertTrue(ship.body().displaced() > 60, "Vessel did not displace water");
            test.assertTrue(Math.abs(ship.body().velocity().y) < .85, "Vessel did not stay afloat");
            test.assertTrue(ship.isPilot(player.getId()) && ship.body().rpm() > 400 && ship.body().fuel() < 500,
                    "Helm input did not drive the diesel");
            int damage = ship.blueprint().hull().indexOf(new Hull.Grid(0, -7, 0));
            test.assertTrue(ship.body().breach(damage), "Could not open outer plating");
        });

        test.runAtTick(130, () -> {
            test.assertTrue(ship.body().waterVolume() > .1, "No water entered through the damaged hull");
            test.assertEquals(1L, java.util.Arrays.stream(ship.body().floodingVolumes()).filter(v -> v > .01).count(),
                    "Intact bulkhead admitted water");
            test.assertTrue(player.getVehicle() == ship, "Rider detached while the ship was moving");
            test.assertTrue(player.getPos().squaredDistanceTo(ship.worldFeet(ship.crewPosition(player.getId()))) < .001,
                    "Rider detached from its transformed deck position");
            player.stopRiding();
            var data = new NbtCompound();
            test.assertTrue(ship.saveNbt(data), "Ship NBT could not be saved");
            var reloaded = new ShipEntity(NavalEngineering.SHIP, world);
            reloaded.readNbt(data);
            test.assertEquals(ship.body().breaches(), reloaded.body().breaches(), "Breach NBT");
            test.assertEquals(ship.body().waterVolume(), reloaded.body().waterVolume(), "Flood NBT");
            test.assertEquals(ship.body().fuel(), reloaded.body().fuel(), "Fuel NBT");
            test.assertEquals(ship.getPos(), reloaded.getPos(), "Saved ship coordinates");
            ship.discard();
            test.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "naval_designs", tickLimit = 10)
    public void fleetHasDryDecksStableHullAndAccessibleStairs(TestContext test) {
        for (var type : VesselDesigns.values()) {
            var design = type.build();
            var report = Hydrostatics.analyze(design.hull());
            test.assertTrue(report.floats() && report.transverseGm() > .3 && report.waterline() < -1.7,
                    type + " must float upright with deck clearance. " + report);
            var space = (DeckMotion.Space) (x, y, z) -> design.hull().indexOf(new Hull.Grid(x, y, z)) >= 0;
            int start = type == VesselDesigns.MERIDIAN ? 5 : 2;
            var state = DeckMotion.State.standing(0, -1.5, start);
            var forward = new DeckMotion.Input(0, 1, 0, false, false, false);
            for (int n = 0; n < 16; n++) state = DeckMotion.tick(space, state, forward);
            test.assertTrue(state.y() < -4.1, type + " hatch did not lead to the machinery deck: " + state);
            var back = new DeckMotion.Input(0, -1, 0, false, false, false);
            for (int n = 0; n < 42; n++) state = DeckMotion.tick(space, state, back);
            test.assertTrue(state.y() > -2, type + " stairs prevent returning to the deck: " + state);
        }
        test.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "naval_profiles", tickLimit = 15)
    public void launchKeepsPlateOrientationAndBothCabinsAreReachable(TestContext test) {
        var world = test.getWorld();
        for (String name : new String[]{"bow_plate", "stem", "chine", "shoulder", "berth", "locker"}) {
            test.assertTrue(world.getServer().getRecipeManager().get(NavalEngineering.id(name)).isPresent(),
                    "Missing registered piece recipe: " + name);
        }
        BlockPos helm = test.getAbsolutePos(new BlockPos(0, 5, 0));
        var plate = NavalEngineering.BOW_PLATE.facing(Direction.WEST);
        world.setBlockState(helm, NavalEngineering.HELM.getDefaultState());
        world.setBlockState(helm.east(), plate);
        command(test, "/naval launch", helm);
        ShipEntity ship = find(test, helm);
        var decoded = Blueprint.decode(ship.blueprint().encode());
        int plateIndex = decoded.hull().indexOf(new Hull.Grid(1, 0, 0));
        test.assertTrue(decoded.states().get(plateIndex) == plate, "The palette lost the plate's facing");
        test.assertTrue(decoded.hull().blocks().get(plateIndex).geometry() == HullGeometry.of(HullGeometry.Profile.BOW, 3),
                "The physical profile lost its facing");
        var legacy = Blueprint.create(Map.of(new Hull.Grid(0, 0, 0), NavalEngineering.HELM.getDefaultState())).encode();
        legacy.putInt("Version", 1);
        test.assertTrue(Blueprint.decode(legacy).hull().blocks().getFirst().geometry() == HullGeometry.FULL,
                "Legacy unshaped hull could not be decoded");
        ship.discard();

        for (int sign : new int[]{-1, 1}) {
            var yacht = ShipEntity.create(world, VesselDesigns.MERIDIAN.build(), Vec3d.ofCenter(helm));
            var crew = yacht.deckSpace();
            var start = DeckMotion.State.standing(0, -.5, 1.15);
            var aisle = walk(start, crew, new DeckMotion.Input(-sign, 0, 0, false, false, false), 5);
            aisle = walk(aisle, crew, new DeckMotion.Input(0, -1, 0, false, false, false), 10);
            var passage = walk(aisle, crew, new DeckMotion.Input(sign, 0, 0, false, false, false), 5);
            passage = walk(passage, crew, new DeckMotion.Input(0, -1, 0, false, false, false), 14);
            var cabin = walk(passage, crew, new DeckMotion.Input(-sign, 0, 0, false, false, false), 11);
            test.assertTrue(cabin.x() * sign > 1.9 && cabin.z() < -3.75 && cabin.grounded(),
                    "Cabin access was obstructed: " + cabin);
        }
        test.complete();
    }

    private static DeckMotion.State walk(DeckMotion.State state, DeckMotion.Space room,
                                         DeckMotion.Input action, int ticks) {
        for (int i = 0; i < ticks; i++) state = DeckMotion.tick(room, state, action);
        return state;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "naval_contacts", tickLimit = 40)
    public void twoHullsExchangeMomentumOnContact(TestContext test) {
        Vec3d start = Vec3d.ofCenter(test.getAbsolutePos(new BlockPos(0, 12, 0)));
        var world = test.getWorld();
        var design = Blueprint.create(Map.of(new Hull.Grid(0, 0, 0), Blocks.OAK_PLANKS.getDefaultState()));
        var first = ShipEntity.create(world, design, start);
        var second = ShipEntity.create(world, design, start.add(1.35, 0, 0));
        first.body().velocity().set(1.5, 0, 0);
        second.body().velocity().set(-1.5, 0, 0);
        world.spawnEntity(first);
        world.spawnEntity(second);
        test.runAtTick(9, () -> {
            test.assertTrue(first.body().velocity().x < .10 && second.body().velocity().x > -.10,
                    "Hull contact did not transfer opposing momentum");
            test.assertTrue(first.getX() < second.getX(), "Hulls passed through one another");
            first.discard();
            second.discard();
            test.complete();
        });
    }

    private static void command(TestContext test, String cmd, BlockPos pos) {
        var server = test.getWorld().getServer();
        server.getCommandManager().executeWithPrefix(server.getCommandSource().withWorld(test.getWorld()),
                cmd + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private static ShipEntity find(TestContext test, BlockPos pos) {
        var center = Vec3d.ofCenter(pos);
        return test.getWorld().getEntitiesByClass(ShipEntity.class, new Box(center, center).expand(5), ship -> true)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Launch command did not create a ship: " + pos));
    }
}
