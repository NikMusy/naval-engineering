package dev.navalengineering.ship;

import dev.navalengineering.simulation.DeckMotion;
import dev.navalengineering.simulation.Hull;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

public final class DeckBoarding {
    private static final double SEARCH_RADIUS = Math.sqrt(3) * Hull.MAX_SPAN + 6;
    private static final Map<ServerWorld, Tracking> TRACKING = new WeakHashMap<>();

    private DeckBoarding() {}

    /** Collects the nearest physical hull face on either side, with vanilla terrain occlusion. */
    public static Hit trace(PlayerEntity player) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1).multiply(player.getBlockInteractionRange()));
        var block = player.getWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        double nearest = block.getType() == HitResult.Type.MISS ? eye.squaredDistanceTo(end)
                : eye.squaredDistanceTo(block.getPos());
        Hit result = null;
        for (ShipEntity ship : player.getWorld().getEntitiesByClass(ShipEntity.class,
                new Box(eye, end).expand(SEARCH_RADIUS), s -> s.blueprint() != null && s.canBoard())) {
            if (!ship.getVisibilityBoundingBox().expand(.15).contains(eye)
                    && ship.getVisibilityBoundingBox().raycast(eye, end).isEmpty()) continue;
            Vec3d startLocal = ship.toLocal(eye);
            Vec3d endLocal = ship.toLocal(end);
            var hull = ship.blueprint().hull();
            for (int i = 0; i < hull.blocks().size(); i++) {
                if (ship.isCellDamaged(i)) continue;
                Hull.Grid p = hull.blocks().get(i).position();
                var face = new Box(p.x() - .5, p.y() - .5, p.z() - .5, p.x() + .5, p.y() + .5, p.z() + .5)
                        .raycast(startLocal, endLocal);
                if (face.isEmpty() || face.get().squaredDistanceTo(startLocal) >= nearest) continue;
                nearest = face.get().squaredDistanceTo(startLocal);
                result = new Hit(ship, face.get());
            }
        }
        return result;
    }

    public static void accept(ServerPlayerEntity player, int shipId) {
        if (player.hasVehicle() || player.isSpectator() || player.isSneaking()) return;
        var found = trace(player);
        if (found != null && found.ship.getId() == shipId) found.ship.boardNear(player, found.localPoint);
    }

    public static void trackLandings(ServerWorld world) {
        Tracking tracking = TRACKING.computeIfAbsent(world, ignored -> new Tracking());
        var live = new HashSet<UUID>();
        for (var player : world.getPlayers()) {
            UUID id = player.getUuid();
            live.add(id);
            Vec3d last = tracking.previous.put(id, player.getPos());
            if (last == null || player.hasVehicle() || player.isSpectator() || !player.isAlive()
                    || player.getAbilities().flying || tracking.waitUntil.getOrDefault(id, 0L) > world.getTime()
                    || last.squaredDistanceTo(player.getPos()) > 8) continue;

            Vec3d now = player.getPos();
            for (ShipEntity ship : world.getEntitiesByClass(ShipEntity.class, new Box(last, now).expand(SEARCH_RADIUS),
                    candidate -> candidate.blueprint() != null && candidate.canBoard())) {
                if (!ship.getVisibilityBoundingBox().expand(1).contains(now)) continue;
                Vec3d localLast = ship.toLocalLastTick(last);
                Vec3d localNow = ship.toLocal(now);
                if (localLast.y - localNow.y < .002) continue;
                DeckMotion.State footing = DeckMotion.landing(ship::solidForCrew,
                        localNow.x, localLast.y + .035, localNow.y - .03, localNow.z);
                if (footing != null && ship.worldFeet(footing).squaredDistanceTo(now) < 3
                        && ship.board(player, footing)) break;
            }
        }
        tracking.previous.keySet().retainAll(live);
        tracking.waitUntil.keySet().retainAll(live);
    }

    static void suppress(PlayerEntity player) {
        if (player.getWorld() instanceof ServerWorld world) {
            TRACKING.computeIfAbsent(world, ignored -> new Tracking()).waitUntil.put(player.getUuid(), world.getTime() + 12);
        }
    }

    public record Hit(ShipEntity ship, Vec3d localPoint) {}

    private static final class Tracking {
        private final Map<UUID, Vec3d> previous = new HashMap<>();
        private final Map<UUID, Long> waitUntil = new HashMap<>();
    }
}

