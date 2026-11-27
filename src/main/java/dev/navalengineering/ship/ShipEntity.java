package dev.navalengineering.ship;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.construction.Blueprint;
import dev.navalengineering.ocean.WorldOcean;
import dev.navalengineering.simulation.Vessel;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

public final class ShipEntity extends Entity {
    private static final TrackedData<NbtCompound> BLUEPRINT = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<NbtCompound> STATUS = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<NbtCompound> DAMAGE = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<Quaternionf> ATTITUDE = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.QUATERNIONF);

    private final Quaternionf previous = new Quaternionf();
    private final Quaternionf rendered = new Quaternionf();
    private final Vector3d origin = new Vector3d();
    private Blueprint blueprint;
    private Vessel body;
    private TerrainCollision terrain;
    private BitSet visibleDamage = new BitSet();
    private Vec3d targetPosition;
    private int interpolation;
    private boolean attitudeReceived;
    private long lastInput = Long.MIN_VALUE;
    private double throttle, steer;

    public ShipEntity(EntityType<? extends ShipEntity> type, World world) {
        super(type, world);
    }

    public static ShipEntity create(ServerWorld world, Blueprint blueprint, Vec3d position) {
        var ship = new ShipEntity(NavalEngineering.SHIP, world);
        ship.setPosition(position);
        ship.install(blueprint);
        return ship;
    }

    private void install(Blueprint data) {
        blueprint = data;
        body = new Vessel(data.hull(), new Vector3d(getX(), getY(), getZ()));
        terrain = new TerrainCollision((ServerWorld) getWorld());
        dataTracker.set(BLUEPRINT, data.encode());
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(BLUEPRINT, new NbtCompound());
        builder.add(DAMAGE, new NbtCompound());
        builder.add(STATUS, new NbtCompound());
        builder.add(ATTITUDE, new Quaternionf());
    }

    public Blueprint blueprint() { return blueprint; }
    public Vessel body() { return body; }
    public NbtCompound telemetry() { return dataTracker.get(STATUS); }
    public boolean isCellDamaged(int index) { return visibleDamage.get(index); }

    public void acceptInput(ServerPlayerEntity player, byte engine, byte rudder) {
        long tick = getWorld().getTime();
        if (getFirstPassenger() != player || lastInput == tick) return;
        if (engine < -1 || engine > 1 || rudder < -1 || rudder > 1) return;
        lastInput = tick;
        throttle = engine;
        steer = rudder;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            previous.set(rendered);
            rendered.slerp(dataTracker.get(ATTITUDE), .48f).normalize();
            if (interpolation > 0) {
                setPosition(getPos().lerp(targetPosition, 1.0 / interpolation));
                interpolation--;
            }
            return;
        }
        if (body == null) {
            discard();
            return;
        }

        if (!hasPassengers() || lastInput == Long.MIN_VALUE || getWorld().getTime() - lastInput > 10) {
            throttle = 0;
            steer = 0;
        }
        var ocean = new WorldOcean(getWorld(), 1);
        terrain.beginTick();
        for (int step = 0; step < 2; step++) {
            body.step(ocean, ocean.frame().windSpeed(), throttle, steer, terrain);
        }
        syncPose();
        if (age % 5 == 0) {
            publishStatus();
            emitWake(ocean);
        }
    }

    void syncPose() {
        body.origin(origin);
        setPosition(origin.x, origin.y, origin.z);
        dataTracker.set(ATTITUDE, new Quaternionf(body.rotation()));
        refreshDamage();
    }

    private void refreshDamage() {
        BitSet current = body.breaches();
        if (current.equals(visibleDamage)) return;
        visibleDamage = current;
        var data = new NbtCompound();
        data.putLongArray("Cells", current.toLongArray());
        dataTracker.set(DAMAGE, data);
    }

    private void publishStatus() {
        var data = new NbtCompound();
        data.putDouble("Knots", Math.hypot(body.velocity().x, body.velocity().z) * 1.943844);
        var forward = body.rotation().transform(new Vector3d(0, 0, -1));
        data.putDouble("Heading", (Math.toDegrees(Math.atan2(forward.x, -forward.z)) + 360) % 360);
        var right = body.rotation().transform(new Vector3d(1, 0, 0));
        var up = body.rotation().transform(new Vector3d(0, 1, 0));
        data.putDouble("Heel", Math.toDegrees(Math.atan2(right.y, up.y)));
        data.putDouble("Mass", body.mass());
        data.putDouble("Displacement", body.displaced());
        data.putDouble("Flood", body.waterVolume());
        data.putDouble("Fuel", body.fuel());
        data.putDouble("Rpm", body.rpm());
        data.putDouble("Heat", body.temperature());
        var bottom = getWorld().raycast(new RaycastContext(getPos(), getPos().subtract(0, 128, 0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this));
        var water = new dev.navalengineering.simulation.Ocean.Water();
        new WorldOcean(getWorld(), 1).sample(getX(), getY(), getZ(), water);
        data.putDouble("Depth", Double.isFinite(water.height) && bottom.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
                ? Math.max(0, water.height - bottom.getPos().y) : -1);
        dataTracker.set(STATUS, data);
    }

    private void emitWake(WorldOcean ocean) {
        if (Math.hypot(body.velocity().x, body.velocity().z) < .8 || !hasPassengers()) return;
        int stern = 0;
        for (var block : blueprint.hull().blocks()) stern = Math.max(stern, block.position().z());
        var point = new Vector3d();
        body.toWorld(new Vector3d(0, -1, stern + .5), point);
        var column = ocean.column((int) Math.floor(point.x), (int) Math.floor(point.z));
        if (column == WorldOcean.DRY) return;
        ((ServerWorld) getWorld()).spawnParticles(ParticleTypes.CLOUD, point.x,
                column.level(ocean.frame(), point.x, point.z) + .06, point.z, 4, .45, .02, .45, .015);
    }

    @Override
    public void updateTrackedPositionAndAngles(double x, double y, double z, float yaw, float pitch, int steps) {
        var target = new Vec3d(x, y, z);
        if (getPos().squaredDistanceTo(target) > 16) {
            setPosition(target);
            interpolation = 0;
        } else {
            targetPosition = target;
            interpolation = 3;
        }
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> key) {
        super.onTrackedDataSet(key);
        if (!getWorld().isClient) return;
        if (key.equals(BLUEPRINT) && dataTracker.get(BLUEPRINT).contains("Cells")) {
            blueprint = Blueprint.decode(dataTracker.get(BLUEPRINT));
        } else if (key.equals(DAMAGE)) {
            visibleDamage = BitSet.valueOf(dataTracker.get(DAMAGE).getLongArray("Cells"));
        } else if (key.equals(ATTITUDE) && !attitudeReceived) {
            attitudeReceived = true;
            rendered.set(dataTracker.get(ATTITUDE));
            previous.set(rendered);
        }
    }

    public Quaternionf renderRotation(float tickDelta) {
        return previous.slerp(rendered, tickDelta, new Quaternionf());
    }

    @Override
    public Box getVisibilityBoundingBox() {
        return getBoundingBox().expand(blueprint == null ? 1 : blueprint.hull().radius());
    }

    @Override
    public boolean shouldRender(double distanceSquared) {
        return distanceSquared < 128 * 128;
    }

    @Override
    public boolean canHit() { return !isRemoved(); }

    @Override
    protected boolean canAddPassenger(Entity passenger) { return !hasPassengers() && passenger instanceof PlayerEntity; }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.shouldCancelInteraction() || hasPassengers() || blueprint == null) return ActionResult.PASS;
        if (getWorld().isClient) return ActionResult.SUCCESS;
        return player.startRiding(this) ? ActionResult.CONSUME : ActionResult.PASS;
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        lastInput = Long.MIN_VALUE;
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        var offset = new Vector3f(0, .58f, 0);
        if (getWorld().isClient) rendered.transform(offset);
        else if (body != null) new Quaternionf(body.rotation()).transform(offset);
        updater.accept(passenger, getX() + offset.x, getY() + offset.y, getZ() + offset.z);
    }

    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        return getPos().add(0, 1, 0);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        if (blueprint == null || body == null) return;
        nbt.put("Blueprint", dataTracker.get(BLUEPRINT));
        nbt.putLongArray("Breaches", body.breaches().toLongArray());
        var rooms = new NbtList();
        for (double volume : body.floodingVolumes()) rooms.add(NbtDouble.of(volume));
        nbt.put("Flooding", rooms);
        var q = body.rotation();
        nbt.putDouble("Qx", q.x);
        nbt.putDouble("Qy", q.y);
        nbt.putDouble("Qz", q.z);
        nbt.putDouble("Qw", q.w);
        putVector(nbt, "Velocity", body.velocity());
        putVector(nbt, "Angular", body.angularVelocity());
        nbt.putDouble("Fuel", body.fuel());
        nbt.putDouble("Rpm", body.rpm());
        nbt.putDouble("Heat", body.temperature());
        nbt.putDouble("Wear", body.wear());
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        try {
            install(Blueprint.decode(nbt.getCompound("Blueprint")));
            var list = nbt.getList("Flooding", NbtElement.DOUBLE_TYPE);
            var rooms = new double[list.size()];
            for (int i = 0; i < rooms.length; i++) rooms[i] = finite(list.getDouble(i), 0);
            var q = new Quaterniond(finite(nbt.getDouble("Qx"), 0), finite(nbt.getDouble("Qy"), 0),
                    finite(nbt.getDouble("Qz"), 0), finite(nbt.getDouble("Qw"), 1));
            if (q.lengthSquared() < 1e-12) q.identity();
            body.restore(new Vector3d(getX(), getY(), getZ()), q, vector(nbt, "Velocity"),
                    vector(nbt, "Angular"), BitSet.valueOf(nbt.getLongArray("Breaches")), rooms,
                    finite(nbt.getDouble("Fuel"), 0), finite(nbt.getDouble("Rpm"), 0),
                    finite(nbt.getDouble("Heat"), 18), finite(nbt.getDouble("Wear"), 0));
            refreshDamage();
            dataTracker.set(ATTITUDE, new Quaternionf(body.rotation()));
        } catch (IllegalArgumentException e) {
            LoggerFactory.getLogger(NavalEngineering.ID).warn("Cannot load vessel {}: {}", getUuid(), e.getMessage());
            body = null;
        }
    }

    private static void putVector(NbtCompound nbt, String key, Vector3d v) {
        nbt.putDouble(key + "X", v.x);
        nbt.putDouble(key + "Y", v.y);
        nbt.putDouble(key + "Z", v.z);
    }

    private static Vector3d vector(NbtCompound nbt, String key) {
        return new Vector3d(finite(nbt.getDouble(key + "X"), 0), finite(nbt.getDouble(key + "Y"), 0),
                finite(nbt.getDouble(key + "Z"), 0));
    }

    private static double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}

