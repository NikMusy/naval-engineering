package dev.navalengineering.gametest;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.construction.Blueprint;
import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.Hull;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
        for (int x = -9; x <= 9; x++) {
            for (int z = -12; z <= 14; z++) {
                for (int y = 0; y <= 6; y++) {
                    boolean wall = Math.abs(x) == 9 || z == -12 || z == 14 || y == 0;
                    world.setBlockState(anchor.add(x, y, z), (wall ? Blocks.STONE : Blocks.WATER).getDefaultState(),
                            Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
                }
            }
        }

        command(test, "/naval demo", anchor.up(5));
        ShipEntity ship = find(test, anchor.up(8));
        test.assertEquals(2, ship.blueprint().hull().compartments().size(), "Sealed chambers in the demo hull");
        var player = test.createMockCreativeServerPlayerInWorld();
        player.setPosition(ship.getPos());
        ship.interact(player, Hand.MAIN_HAND);
        test.assertTrue(player.getVehicle() == ship, "The helm did not accept a rider");

        for (int tick = 8; tick < 40; tick++) {
            test.runAtTick(tick, () -> ship.acceptInput(player, (byte) 1, (byte) 0));
        }
        test.runAtTick(40, () -> {
            test.assertTrue(ship.body().displaced() > 60, "Vessel did not displace water");
            test.assertTrue(Math.abs(ship.body().velocity().y) < .85, "Vessel did not stay afloat");
            test.assertTrue(ship.body().rpm() > 400 && ship.body().fuel() < 250, "Helm input did not drive the diesel");
            int damage = ship.blueprint().hull().indexOf(new Hull.Grid(-2, -2, 3));
            test.assertTrue(ship.body().breach(damage), "Could not open outer plating");
        });

        test.runAtTick(130, () -> {
            test.assertTrue(ship.body().waterVolume() > .1, "No water entered through the damaged hull");
            test.assertEquals(0.0, ship.body().floodingVolumes()[0], "Intact bulkhead admitted water");
            test.assertTrue(player.getVehicle() == ship, "Rider detached while the ship was moving");
            test.assertTrue(player.squaredDistanceTo(ship) < 2, "Rider detached from its transformed seat");
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

