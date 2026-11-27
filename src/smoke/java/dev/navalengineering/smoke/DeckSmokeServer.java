package dev.navalengineering.smoke;

import dev.navalengineering.construction.VesselDesigns;
import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.DeckMotion;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class DeckSmokeServer implements DedicatedServerModInitializer {
    private int tick = -1;
    private ShipEntity meridian;
    private ShipEntity squall;
    private boolean landed, belowDeck, returned, insideBridge, atWall, outsideBridge, reentered, hadPilot, pilotWalked;
    private double maxOnDeckError, speedWhenBelow, peakSpeed, maxTurn;
    private int wallTicks, samples;

    @Override
    public void onInitializeServer() {
        ServerTickEvents.END_SERVER_TICK.register(this::test);
    }

    private void test(MinecraftServer server) {
        var pilot = server.getPlayerManager().getPlayer("DeckPilot");
        var walker = server.getPlayerManager().getPlayer("DeckWalker");
        if (pilot == null || walker == null || server.isStopping()) return;
        tick++;
        try {
            runScenario(server, pilot, walker);
        } catch (Exception e) {
            e.printStackTrace();
            report(server, "FAIL " + e);
        }
    }

    private void runScenario(MinecraftServer server, ServerPlayerEntity pilot, ServerPlayerEntity walker) {
        var world = server.getOverworld();
        if (tick == 6) {
            // The ocean fixture supplies 35 blocks of clear water below both ships.
            for (int x = -4; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) world.setChunkForced(x, z, true);
            }
        }
        if (tick == 42) {
            world.setTimeOfDay(5900);
            world.setWeather(12000, 0, false, false);
            meridian = demo(server, VesselDesigns.MERIDIAN, 0);
            squall = demo(server, VesselDesigns.SQUALL, -40);
            view(pilot, meridian, new Vec3d(16, 8, -15));
            view(walker, squall, new Vec3d(12, 7, -10));
        }
        if (tick == 120) {
            Vec3d bridge = meridian.worldFeet(DeckMotion.State.standing(-.8, -.5, 1.2));
            pilot.teleport(world, bridge.x, bridge.y, bridge.z, 180, 0);
            pilot.getAbilities().flying = false;
            pilot.sendAbilitiesUpdate();
            if (!meridian.board(pilot, DeckMotion.State.standing(-.8, -.5, 1.2))) {
                throw new IllegalStateException("Pilot could not board");
            }
            var floor = meridian.worldFeet(DeckMotion.State.standing(0, -1.5, 4.8));
            walker.teleport(world, floor.x, floor.y + 1.6, floor.z, 0, 35);
        }
        if (tick == 128) {
            walker.getAbilities().flying = false;
            walker.sendAbilitiesUpdate();
            world.setWeather(0, 12000, true, true);
        }
        if (tick < 121) return;

        DeckMotion.State p = meridian.crewPosition(pilot.getId());
        DeckMotion.State w = meridian.crewPosition(walker.getId());
        if (w != null) landed = true;
        double speed = Math.hypot(meridian.body().velocity().x, meridian.body().velocity().z);
        peakSpeed = Math.max(peakSpeed, speed);
        double heading = meridian.telemetry().getDouble("Heading");
        maxTurn = Math.max(maxTurn, Math.min(heading, 360 - heading));
        if (p != null) {
            checkFoot(pilot, p);
            hadPilot |= meridian.isPilot(pilot.getId());
            pilotWalked |= hadPilot && !meridian.isPilot(pilot.getId()) && p.z() > 1.95;
        }
        if (w != null) {
            checkFoot(walker, w);
            if (w.y() < -4.35 && w.z() > 8.4) {
                belowDeck = true;
                speedWhenBelow = Math.max(speedWhenBelow, speed);
            }
            if (belowDeck && w.y() > -1.65 && w.z() < 5.8) returned = true;
            if (returned && w.y() > -.55 && w.z() < 2.85) insideBridge = true;
            if (insideBridge && w.x() > 1.17 && w.x() <= 1.205 && w.z() > 1.6 && w.z() < 2.8) {
                wallTicks++;
                if (wallTicks >= 12) atWall = true;
            }
            if (atWall && w.y() < -1.3 && w.z() > 3.8) outsideBridge = true;
            if (outsideBridge && w.y() > -.6 && w.z() < 2.7) reentered = true;
        }
        if (tick % 40 == 0) System.out.printf(Locale.ROOT,
                "DECK_SMOKE tick=%d pilot=%s walking=%s speed=%.3f heading=%.2f below=%s returned=%s wall=%s reentered=%s%n",
                tick, meridian.isPilot(pilot.getId()), w, speed, heading, belowDeck, returned, atWall, reentered);
        if (tick >= 365 && landed && belowDeck && returned && insideBridge && atWall && reentered && pilotWalked
                && peakSpeed > .55 && speedWhenBelow > .15 && maxTurn > 5 && maxOnDeckError < .03 && samples > 300) {
            report(server, summary("PASS"));
        } else if (tick >= 640) {
            report(server, summary("FAIL"));
        }
    }

    private String summary(String status) {
        return String.format(Locale.ROOT,
                "%s landing=%s stairsDown=%s stairsUp=%s bridge=%s wall=%s reentered=%s pilotWalked=%s " +
                        "samples=%d peakSpeed=%.3f_m/s speedInside=%.3f_m/s turn=%.2f_deg maxServerFootError=%.6f_m " +
                        "meridianBlocks=%d squallBlocks=%d",
                status, landed, belowDeck, returned, insideBridge, atWall, reentered, pilotWalked, samples, peakSpeed,
                speedWhenBelow, maxTurn, maxOnDeckError, meridian.blueprint().states().size(),
                squall.blueprint().states().size());
    }

    private void checkFoot(ServerPlayerEntity player, DeckMotion.State foot) {
        if (player.getVehicle() != meridian) throw new IllegalStateException("Lost vehicle association");
        if (!DeckMotion.isClear(meridian::solidForCrew, foot.x(), foot.y(), foot.z())) {
            throw new IllegalStateException("Crew penetrated hull: " + foot);
        }
        maxOnDeckError = Math.max(maxOnDeckError, player.getPos().distanceTo(meridian.worldFeet(foot)));
        samples++;
    }

    private static ShipEntity demo(MinecraftServer server, VesselDesigns design, int x) {
        var world = server.getOverworld();
        var source = server.getCommandSource().withWorld(world);
        int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, 0);
        server.getCommandManager().executeWithPrefix(source, "naval demo " + x + " " + y + " 0 " + design.id());
        var center = new Vec3d(x + .5, y + 3, .5);
        return world.getEntitiesByClass(ShipEntity.class, new Box(center, center).expand(6), s -> true)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Failed to launch " + design));
    }

    private static void view(ServerPlayerEntity player, ShipEntity ship, Vec3d offset) {
        Vec3d at = ship.getPos().add(offset);
        Vec3d aim = ship.getPos().subtract(at).add(0, -1.2 - 1.62, 0);
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z)));
        player.changeGameMode(GameMode.CREATIVE);
        player.getAbilities().flying = true;
        player.sendAbilitiesUpdate();
        player.teleport(player.getServerWorld(), at.x, at.y, at.z, yaw, pitch);
    }

    private void report(MinecraftServer server, String result) {
        System.out.println("DECK_SMOKE_SERVER " + result);
        try {
            Files.writeString(Path.of("deck-result.txt"), result + System.lineSeparator());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            server.stop(false);
        }
    }
}
