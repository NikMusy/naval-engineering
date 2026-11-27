package dev.navalengineering.ship;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.construction.Blueprint;
import dev.navalengineering.network.CrewInput;
import dev.navalengineering.ocean.WorldOcean;
import dev.navalengineering.simulation.DeckMotion;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
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
import java.util.List;

public final class ShipEntity extends Entity {
    private static final TrackedData<NbtCompound> BLUEPRINT = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<NbtCompound> STATUS = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<NbtCompound> DAMAGE = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);
    private static final TrackedData<Quaternionf> ATTITUDE = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.QUATERNIONF);
    private static final TrackedData<NbtCompound> CREW = DataTracker.registerData(ShipEntity.class,
            TrackedDataHandlerRegistry.NBT_COMPOUND);

    private final Quaternionf previous = new Quaternionf();
    private final Quaternionf rendered = new Quaternionf();
    private final Quaterniond previousServerRotation = new Quaterniond();
    private final Vector3d origin = new Vector3d();
    private final DeckCrew crew = new DeckCrew(this);
    private final DeckMotion.Space deckSpace = new DeckMotion.Space() {
        @Override public boolean solid(int x, int y, int z) { return solidForCrew(x, y, z); }
        @Override public List<HullGeometry.Box> boxes(int x, int y, int z) {
            int index = crewCell(x, y, z);
            return index < 0 ? List.of() : blueprint.hull().blocks().get(index).geometry().boxes();
        }
    };
    private Blueprint blueprint;
    private Vessel body;
    private TerrainCollision terrain;
    private BitSet visibleDamage = new BitSet();
    private Vec3d targetPosition;
    private int interpolation;
    private boolean attitudeReceived;
    private Vec3d previousServerOrigin;
    private DeckMotion.State requestedFooting;
    private Vec3d dismountPosition;
    private int minX, minY, minZ, maxX, maxZ;

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
        setBlueprint(data);
        body = new Vessel(data.hull(), new Vector3d(getX(), getY(), getZ()));
        terrain = new TerrainCollision((ServerWorld) getWorld());
        dataTracker.set(BLUEPRINT, data.encode());
    }

    private void setBlueprint(Blueprint data) {
        blueprint = data;
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxZ = Integer.MIN_VALUE;
        for (Hull.Cell cell : data.hull().blocks()) {
            Hull.Grid p = cell.position();
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxZ = Math.max(maxZ, p.z());
        }
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(BLUEPRINT, new NbtCompound());
        builder.add(DAMAGE, new NbtCompound());
        builder.add(STATUS, new NbtCompound());
        builder.add(ATTITUDE, new Quaternionf());
        builder.add(CREW, new NbtCompound());
    }

    public Blueprint blueprint() { return blueprint; }
    public Vessel body() { return body; }
    public NbtCompound telemetry() { return dataTracker.get(STATUS); }
    public boolean isCellDamaged(int index) { return visibleDamage.get(index); }

    public void acceptCrewInput(PlayerEntity player, CrewInput input) { crew.receive(player, input); }
    public void predictCrewInput(int id, CrewInput input) { crew.predict(id, input); }
    public boolean isPilot(int id) { return crew.isPilot(id); }
    public DeckMotion.State crewPosition(int id) { return crew.state(id); }
    public DeckMotion.State authoritativeCrewPosition(int id) { return crew.authoritative(id); }
    public double crewStride(int id) { return crew.stride(id); }

    public boolean canTakeHelm(int id) { return atHelm(crew.state(id)); }

    boolean atHelm(DeckMotion.State p) {
        return p != null && p.grounded() && p.x() * p.x() + p.z() * p.z() < 2.65
                && p.y() >= -1.5 && p.y() <= .55;
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
            if (blueprint != null) crew.interpolate();
            return;
        }
        if (body == null) {
            discard();
            return;
        }

        previousServerOrigin = getPos();
        previousServerRotation.set(body.rotation());
        crew.readInputs();
        var ocean = new WorldOcean(getWorld(), 1);
        terrain.beginTick();
        for (int step = 0; step < 2; step++) {
            body.step(ocean, ocean.frame().windSpeed(), crew.throttle(), crew.rudder(), terrain);
        }
        syncPose();
        crew.walk();
        if (hasPassengers() || dataTracker.get(CREW).getIntArray("Poses").length > 0) {
            dataTracker.set(CREW, crew.encode());
        }
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
        for (Entity passenger : getPassengerList()) updatePassengerPosition(passenger);
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
            setBlueprint(Blueprint.decode(dataTracker.get(BLUEPRINT)));
        } else if (key.equals(DAMAGE)) {
            visibleDamage = BitSet.valueOf(dataTracker.get(DAMAGE).getLongArray("Cells"));
        } else if (key.equals(ATTITUDE) && !attitudeReceived) {
            attitudeReceived = true;
            rendered.set(dataTracker.get(ATTITUDE));
            previous.set(rendered);
        } else if (key.equals(CREW)) {
            crew.decode(dataTracker.get(CREW));
        }
    }

    public Quaternionf renderRotation(float tickDelta) {
        return previous.slerp(rendered, tickDelta, new Quaternionf());
    }

    public Vec3d crewEyePosition(int id, float tickDelta, float height) {
        Vec3d eye = crew.localEye(id, tickDelta, height);
        if (eye == null) return null;
        var offset = new Quaterniond(renderRotation(tickDelta)).transform(new Vector3d(eye.x, eye.y, eye.z));
        return getLerpedPos(tickDelta).add(offset.x, offset.y, offset.z);
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
    protected boolean canAddPassenger(Entity passenger) { return passenger instanceof PlayerEntity && canBoard(); }

    public boolean canBoard() { return getPassengerList().size() < DeckCrew.MAX_CREW && !isRemoved(); }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (player.shouldCancelInteraction() || player.hasVehicle() || !canBoard() || blueprint == null) return ActionResult.PASS;
        if (getWorld().isClient) return ActionResult.SUCCESS;
        return boardNear(player, toLocal(player.getPos())) ? ActionResult.CONSUME : ActionResult.PASS;
    }

    boolean boardNear(PlayerEntity player, Vec3d focus) {
        DeckMotion.State start = footing(focus, player, player.getBlockInteractionRange() + .35);
        return start != null && board(player, start);
    }

    public boolean board(PlayerEntity player, DeckMotion.State position) {
        if (getWorld().isClient || body == null || player.hasVehicle() || player.isSpectator()
                || !canBoard() || player.getWorld() != getWorld()
                || !DeckMotion.isClear(deckSpace, position.x(), position.y(), position.z())
                || !DeckMotion.hasFloor(deckSpace, position)
                || !isWorldClear(player, worldFeet(position))) return false;
        requestedFooting = position;
        try {
            return player.startRiding(this);
        } finally {
            requestedFooting = null;
        }
    }

    private DeckMotion.State footing(Vec3d focus, PlayerEntity player, double maximum) {
        if (blueprint == null) return null;
        double best = Double.POSITIVE_INFINITY;
        DeckMotion.State result = null;
        for (int i = 0; i < blueprint.hull().blocks().size(); i++) {
            if (isCellDamaged(i)) continue;
            Hull.Grid p = blueprint.hull().blocks().get(i).position();
            double y = p.y() + .5;
            double cost = focus.squaredDistanceTo(p.x(), y, p.z());
            if (cost >= best || cost > 20 || !DeckMotion.isClear(deckSpace, p.x(), y, p.z())) continue;
            var stand = DeckMotion.State.standing(p.x(), y, p.z());
            if (!DeckMotion.hasFloor(deckSpace, stand)) continue;
            Vec3d world = worldFeet(stand);
            if (world.squaredDistanceTo(player.getPos()) > maximum * maximum || !isWorldClear(player, world)) continue;
            result = stand;
            best = cost;
        }
        return result;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (!getWorld().isClient && body != null && passenger instanceof PlayerEntity player
                && getPassengerList().size() <= DeckCrew.MAX_CREW) {
            DeckMotion.State start = requestedFooting;
            if (start == null) start = footing(toLocal(player.getPos()), player, Double.POSITIVE_INFINITY);
            if (start == null) start = footing(new Vec3d(0, -.5, 1), player, Double.POSITIVE_INFINITY);
            crew.add(player, start);
            dataTracker.set(CREW, crew.encode());
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        crew.remove(passenger);
        if (!getWorld().isClient) {
            dataTracker.set(CREW, crew.encode());
            if (passenger instanceof PlayerEntity player) DeckBoarding.suppress(player);
        }
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        DeckMotion.State state = crew.state(passenger.getId());
        if (state == null) return;
        Vec3d feet = worldFeet(state);
        updater.accept(passenger, feet.x, feet.y, feet.z);
        if (getWorld().isClient && passenger instanceof LivingEntity living) {
            living.limbAnimator.updateLimbs((float) Math.min(1, crew.stride(passenger.getId()) * 3.5), .5f);
        }
    }

    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        return dismountPosition == null ? passenger.getPos() : dismountPosition;
    }

    void exitDeck(Entity rider, Vec3d feet, boolean obstructed) {
        dismountPosition = obstructed ? unobstructedExit(rider, feet) : feet;
        try {
            rider.stopRiding();
            rider.setPosition(dismountPosition);
            rider.setOnGround(false);
            var v = body.velocity();
            rider.setVelocity(v.x * .05, Math.min(0, v.y * .05), v.z * .05);
            rider.velocityModified = true;
        } finally {
            dismountPosition = null;
        }
    }

    private Vec3d unobstructedExit(Entity rider, Vec3d feet) {
        for (int i = 0; i <= 4; i++) {
            Vec3d candidate = feet.add(0, i * .5, 0);
            if (isWorldClear(rider, candidate)) return candidate;
        }
        return feet;
    }

    public Vec3d worldFeet(DeckMotion.State p) {
        Vector3d local = new Vector3d(p.x(), p.y(), p.z());
        if (body == null) new Quaterniond(rendered).transform(local);
        else body.rotation().transform(local);
        return getPos().add(local.x, local.y, local.z);
    }

    public Vec3d toLocal(Vec3d world) {
        return transformInverse(world, getPos(), body == null ? new Quaterniond(rendered) : body.rotation());
    }

    Vec3d toLocalLastTick(Vec3d world) {
        return previousServerOrigin == null ? toLocal(world)
                : transformInverse(world, previousServerOrigin, previousServerRotation);
    }

    private static Vec3d transformInverse(Vec3d world, Vec3d at, Quaterniond rotation) {
        var relative = rotation.transformInverse(new Vector3d(world.x - at.x, world.y - at.y, world.z - at.z));
        return new Vec3d(relative.x, relative.y, relative.z);
    }

    public float deckYaw() {
        var forward = new Quaterniond(rendered).transform(new Vector3d(0, 0, 1));
        return (float) Math.toDegrees(Math.atan2(-forward.x, forward.z));
    }

    public boolean solidForCrew(int x, int y, int z) {
        return crewCell(x, y, z) >= 0;
    }

    public DeckMotion.Space deckSpace() { return deckSpace; }

    private int crewCell(int x, int y, int z) {
        if (blueprint == null || Math.abs(x) >= Hull.MAX_SPAN || Math.abs(y) >= Hull.MAX_SPAN
                || Math.abs(z) >= Hull.MAX_SPAN) return -1;
        int index = blueprint.hull().indexOf(new Hull.Grid(x, y, z));
        return index >= 0 && !isCellDamaged(index) ? index : -1;
    }

    boolean leftHull(DeckMotion.State p) {
        return p.y() < minY - .85 || p.x() < minX - 1.35 || p.x() > maxX + 1.35
                || p.z() < minZ - 1.35 || p.z() > maxZ + 1.35;
    }

    boolean isWorldClear(Entity rider, Vec3d feet) {
        Box box = rider.getBoundingBox().offset(feet.subtract(rider.getPos())).contract(.015);
        return !getWorld().getBlockCollisions(rider, box).iterator().hasNext();
    }

    public boolean repairCell(int index) {
        if (index < 0 || !body.breached(index)) return false;
        return !crew.wouldTrap(blueprint.hull().blocks().get(index)) && body.repair(index);
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
