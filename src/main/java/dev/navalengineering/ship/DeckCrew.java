package dev.navalengineering.ship;

import dev.navalengineering.network.CrewInput;
import dev.navalengineering.simulation.DeckMotion;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DeckCrew {
    static final int MAX_CREW = 8;
    private static final int STRIDE = 7;
    private static final double QUANTIZATION = 16384;
    private static final int MAX_REPLAY_TICKS = 4;
    private static final double PREDICTED_HORIZONTAL_RANGE = .82;
    private static final double PREDICTED_VERTICAL_RANGE = .90;
    private final ShipEntity ship;
    private final Map<Integer, Member> members = new HashMap<>();
    private final Map<Integer, Replica> replicas = new HashMap<>();
    private final ArrayDeque<CrewInput> pending = new ArrayDeque<>();
    private int pilot;
    private int predictedActor;
    private CrewInput predictedInput;
    private double throttle;
    private double rudder;

    DeckCrew(ShipEntity ship) {
        this.ship = ship;
    }

    boolean isPilot(int id) { return id != 0 && pilot == id; }
    double throttle() { return throttle; }
    double rudder() { return rudder; }

    DeckMotion.State state(int id) {
        if (ship.getWorld().isClient) {
            Replica replica = replicas.get(id);
            return replica == null ? null : replica.displayed;
        }
        Member member = members.get(id);
        return member == null ? null : member.position;
    }

    DeckMotion.State authoritative(int id) {
        Replica replica = replicas.get(id);
        return replica == null ? null : replica.authoritative;
    }

    double stride(int id) {
        Replica replica = replicas.get(id);
        if (replica == null || replica.previous == null) return 0;
        return Math.hypot(replica.displayed.x() - replica.previous.x(), replica.displayed.z() - replica.previous.z());
    }

    Vec3d localEye(int id, float delta, float eyeHeight) {
        Replica replica = replicas.get(id);
        if (replica == null) return null;
        DeckMotion.State now = replica.displayed;
        DeckMotion.State old = replica.previous == null ? now : replica.previous;
        return new Vec3d(MathHelper.lerp(delta, old.x(), now.x()),
                MathHelper.lerp(delta, old.y(), now.y()) + eyeHeight,
                MathHelper.lerp(delta, old.z(), now.z()));
    }

    void add(PlayerEntity player, DeckMotion.State start) {
        if (start != null) members.put(player.getId(), new Member(start));
    }

    void remove(Entity entity) {
        members.remove(entity.getId());
        replicas.remove(entity.getId());
        if (pilot == entity.getId()) {
            pilot = 0;
            throttle = 0;
            rudder = 0;
        }
        if (predictedActor == entity.getId()) {
            predictedActor = 0;
            predictedInput = null;
            pending.clear();
        }
    }

    void receive(PlayerEntity sender, CrewInput input) {
        Member member = members.get(sender.getId());
        if (member == null || input.shipId() != ship.getId() || !input.valid()
                || input.sequence() <= member.received) return;
        member.received = input.sequence();
        if (member.inputs.size() == 6) member.inputs.removeFirst();
        member.inputs.addLast(input);
    }

    void readInputs() {
        throttle = 0;
        rudder = 0;
        for (var pair : members.entrySet()) {
            int id = pair.getKey();
            Member member = pair.getValue();
            CrewInput input = member.inputs.pollFirst();
            member.active = input;
            if (input == null) continue;
            member.acknowledged = input.sequence();
            if ((input.flags() & CrewInput.HELM) != 0) {
                if (isPilot(id)) {
                    pilot = 0;
                } else if (pilot == 0 && ship.atHelm(member.position)) {
                    pilot = id;
                    member.position = DeckMotion.State.standing(member.position.x(), member.position.y(), member.position.z());
                }
                // A mode switch must not reuse the walking key as throttle.
                member.active = null;
                continue;
            }
            if (isPilot(id)) {
                throttle = input.forward();
                rudder = input.strafe();
            }
        }
    }

    void walk() {
        for (Entity rider : ship.getPassengerList()) {
            Member member = members.get(rider.getId());
            if (member == null) {
                rider.stopRiding();
                continue;
            }
            DeckMotion.State from = member.position;
            DeckMotion.Input action = member.active == null || isPilot(rider.getId())
                    ? DeckMotion.Input.IDLE : member.active.walking();
            DeckMotion.State to = DeckMotion.tick(ship.deckSpace(), from, action);
            Vec3d worldFeet = ship.worldFeet(to);
            if (ship.leftHull(to)) {
                member.position = to;
                ship.exitDeck(rider, worldFeet, false);
            } else if (ship.isWorldClear(rider, worldFeet)) {
                member.position = to;
                if (isPilot(rider.getId()) && !ship.atHelm(to)) pilot = 0;
            } else if (!ship.isWorldClear(rider, ship.worldFeet(from))) {
                ship.exitDeck(rider, rider.getPos(), true);
            }
        }
    }

    boolean wouldTrap(Hull.Cell cell) {
        DeckMotion.Space repaired = new DeckMotion.Space() {
            @Override public boolean solid(int x, int y, int z) { return !boxes(x, y, z).isEmpty(); }
            @Override public List<HullGeometry.Box> boxes(int x, int y, int z) {
                var p = cell.position();
                return x == p.x() && y == p.y() && z == p.z() ? cell.geometry().boxes() : ship.deckSpace().boxes(x, y, z);
            }
        };
        return members.values().stream().anyMatch(member -> {
            DeckMotion.State p = member.position;
            return !DeckMotion.isClear(repaired, p.x(), p.y(), p.z());
        });
    }

    NbtCompound encode() {
        var nbt = new NbtCompound();
        int[] encoded = new int[members.size() * STRIDE];
        int n = 0;
        for (var pair : members.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            Member member = pair.getValue();
            DeckMotion.State p = member.position;
            encoded[n++] = pair.getKey();
            encoded[n++] = member.acknowledged;
            encoded[n++] = pack(p.x());
            encoded[n++] = pack(p.y());
            encoded[n++] = pack(p.z());
            encoded[n++] = pack(p.vy());
            encoded[n++] = p.grounded() ? 1 : 0;
        }
        nbt.putIntArray("Poses", encoded);
        nbt.putInt("Pilot", pilot);
        return nbt;
    }

    void decode(NbtCompound nbt) {
        int[] encoded = nbt.getIntArray("Poses");
        if (encoded.length % STRIDE != 0 || encoded.length > STRIDE * MAX_CREW) return;
        pilot = nbt.getInt("Pilot");
        var live = new java.util.HashSet<Integer>();
        for (int i = 0; i < encoded.length; i += STRIDE) {
            int id = encoded[i];
            live.add(id);
            var state = new DeckMotion.State(unpack(encoded[i + 2]), unpack(encoded[i + 3]),
                    unpack(encoded[i + 4]), unpack(encoded[i + 5]), encoded[i + 6] != 0);
            var replica = replicas.computeIfAbsent(id, ignored -> new Replica(state));
            replica.authoritative = state;
            replica.acknowledged = encoded[i + 1];
        }
        replicas.keySet().retainAll(live);
    }

    void predict(int actor, CrewInput input) {
        if (actor != predictedActor) pending.clear();
        predictedActor = actor;
        predictedInput = input;
    }

    void interpolate() {
        for (var pair : replicas.entrySet()) {
            int id = pair.getKey();
            Replica replica = pair.getValue();
            replica.previous = replica.displayed;
            DeckMotion.State target = replica.authoritative;
            if (id == predictedActor) {
                pending.removeIf(input -> input.sequence() <= replica.acknowledged);
                if (predictedInput != null && predictedInput.sequence() > replica.acknowledged) {
                    if (pending.size() == 32) pending.removeFirst();
                    pending.addLast(predictedInput);
                }
                if (!isPilot(id)) {
                    target = replay(replica.authoritative);
                }
            }
            double distance = replica.displayed.distanceSquared(target);
            double ratio = distance > 1.15 || id == predictedActor ? 1 : .72;
            var blended = new DeckMotion.State(
                    MathHelper.lerp(ratio, replica.displayed.x(), target.x()),
                    MathHelper.lerp(ratio, replica.displayed.y(), target.y()),
                    MathHelper.lerp(ratio, replica.displayed.z(), target.z()), target.vy(), target.grounded());
            replica.displayed = DeckMotion.isClear(ship.deckSpace(), blended.x(), blended.y(), blended.z())
                    ? blended : target;
        }
        predictedInput = null;
    }

    private DeckMotion.State replay(DeckMotion.State confirmed) {
        DeckMotion.State position = confirmed;
        int ticks = 0;
        for (CrewInput input : pending) {
            if (++ticks > MAX_REPLAY_TICKS || (input.flags() & CrewInput.HELM) != 0) break;
            DeckMotion.State next = DeckMotion.tick(ship.deckSpace(), position, input.walking());
            if (Math.abs(confirmed.y() - next.y()) > PREDICTED_VERTICAL_RANGE
                    || Math.hypot(confirmed.x() - next.x(), confirmed.z() - next.z()) > PREDICTED_HORIZONTAL_RANGE) break;
            position = next;
        }
        return position;
    }

    private static int pack(double value) { return (int) Math.round(value * QUANTIZATION); }
    private static double unpack(int value) { return value / QUANTIZATION; }

    private static final class Member {
        private final ArrayDeque<CrewInput> inputs = new ArrayDeque<>();
        private DeckMotion.State position;
        private CrewInput active;
        private int received;
        private int acknowledged;
        Member(DeckMotion.State position) { this.position = position; }
    }

    private static final class Replica {
        private DeckMotion.State authoritative;
        private DeckMotion.State displayed;
        private DeckMotion.State previous;
        private int acknowledged;
        Replica(DeckMotion.State position) {
            authoritative = displayed = position;
        }
    }
}
