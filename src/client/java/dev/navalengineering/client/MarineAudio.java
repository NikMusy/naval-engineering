package dev.navalengineering.client;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.ocean.WorldOcean;
import dev.navalengineering.ship.ShipEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;

import java.util.HashMap;
import java.util.Map;

final class MarineAudio {
    private final Map<ShipEntity, Diesel> engines = new HashMap<>();
    private Sea sea;

    void update(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        var sound = client.getSoundManager();
        engines.entrySet().removeIf(entry -> entry.getKey().getWorld() != client.world
                || entry.getValue().isDone() || !sound.isPlaying(entry.getValue()));
        for (var ship : client.world.getEntitiesByClass(ShipEntity.class, client.player.getBoundingBox().expand(24),
                vessel -> Math.abs(vessel.telemetry().getDouble("Rpm")) > 50)) {
            if (engines.containsKey(ship)) continue;
            var loop = new Diesel(ship);
            engines.put(ship, loop);
            sound.play(loop);
        }

        if (sea == null || sea.isDone() || !sound.isPlaying(sea)) {
            sea = new Sea();
            sound.play(sea);
        }
    }

    private static final class Diesel extends MovingSoundInstance {
        private final ShipEntity ship;
        private int idleTicks;

        Diesel(ShipEntity ship) {
            super(NavalEngineering.ENGINE_LOOP, SoundCategory.NEUTRAL, Random.create());
            this.ship = ship;
            repeat = true;
            volume = .02f;
            x = ship.getX();
            y = ship.getY();
            z = ship.getZ();
        }

        @Override
        public void tick() {
            var client = MinecraftClient.getInstance();
            if (ship.isRemoved() || ship.getWorld() != client.world || client.player == null
                    || client.player.squaredDistanceTo(ship) > 32 * 32) {
                setDone();
                return;
            }

            double load = Math.abs(ship.telemetry().getDouble("Rpm")) / 1650;
            idleTicks = load < .02 ? idleTicks + 1 : 0;
            if (idleTicks > 25) {
                setDone();
                return;
            }
            var offset = ship.renderRotation(1).transform(new org.joml.Vector3f(0, -.8f, 1.5f));
            x = ship.getX() + offset.x;
            y = ship.getY() + offset.y;
            z = ship.getZ() + offset.z;
            volume += (.42f * (float) load - volume) * .16f;
            pitch += (.66f + .73f * (float) load - pitch) * .16f;
        }
    }

    private static final class Sea extends MovingSoundInstance {
        Sea() {
            super(NavalEngineering.OCEAN_LOOP, SoundCategory.AMBIENT, Random.create());
            repeat = true;
            relative = true;
            attenuationType = AttenuationType.NONE;
            volume = .001f;
        }

        @Override
        public void tick() {
            var client = MinecraftClient.getInstance();
            var player = client.player;
            if (client.world == null || player == null) {
                setDone();
                return;
            }
            var ocean = new WorldOcean(client.world, 1);
            var column = ocean.column(player.getBlockX(), player.getBlockZ());
            double height = player.getEyeY() - column.datum();
            double near = Math.clamp(1 - Math.abs(height) / 21, 0, 1) * column.exposure();
            double target = near * (.13 + .4 * ocean.frame().weather());
            if (player.isSubmergedInWater()) target *= .32;
            volume += ((float) target - volume) * .10f;
            pitch = player.isSubmergedInWater() ? .76f : 1;
        }

        @Override
        public boolean shouldAlwaysPlay() {
            return true;
        }
    }
}

