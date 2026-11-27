package dev.navalengineering.construction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import dev.navalengineering.NavalEngineering;
import dev.navalengineering.ocean.WorldOcean;
import dev.navalengineering.ship.ShipEntity;
import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.Hydrostatics;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

public final class ShipCommands {
    private static final int MAX_SHIPS_PER_AREA = 8;
    private static final DynamicCommandExceptionType ERROR = new DynamicCommandExceptionType(
            reason -> Text.translatable("naval.command.error", reason));

    private ShipCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var root = CommandManager.literal("naval");
        root.then(CommandManager.literal("inspect")
                .executes(c -> inspect(c.getSource()))
                .then(CommandManager.argument("helm", BlockPosArgumentType.blockPos())
                        .executes(c -> preview(c.getSource(), BlockPosArgumentType.getLoadedBlockPos(c, "helm")))));
        root.then(CommandManager.literal("launch").requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("helm", BlockPosArgumentType.blockPos())
                        .executes(c -> launch(c.getSource(), BlockPosArgumentType.getLoadedBlockPos(c, "helm")))));
        var water = CommandManager.argument("water", BlockPosArgumentType.blockPos())
                .executes(c -> demo(c.getSource(), BlockPosArgumentType.getLoadedBlockPos(c, "water"), VesselDesigns.MERIDIAN));
        for (VesselDesigns design : VesselDesigns.values()) {
            water.then(CommandManager.literal(design.id()).executes(c ->
                    demo(c.getSource(), BlockPosArgumentType.getLoadedBlockPos(c, "water"), design)));
        }
        root.then(CommandManager.literal("demo").requires(s -> s.hasPermissionLevel(2)).then(water));
        root.then(cellCommand("breach", false));
        root.then(cellCommand("repair", true));
        root.then(CommandManager.literal("refuel").requires(s -> s.hasPermissionLevel(2))
                .executes(c -> {
                    ShipEntity ship = nearest(c.getSource());
                    ship.body().refuel();
                    c.getSource().sendFeedback(() -> Text.translatable("naval.command.refuel"), true);
                    return 1;
                }));
        dispatcher.register(root);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> cellCommand(
            String name, boolean repair) {
        return CommandManager.literal(name).requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("x", IntegerArgumentType.integer(1 - Hull.MAX_SPAN, Hull.MAX_SPAN - 1))
                        .then(CommandManager.argument("y", IntegerArgumentType.integer(1 - Hull.MAX_SPAN, Hull.MAX_SPAN - 1))
                                .then(CommandManager.argument("z", IntegerArgumentType.integer(1 - Hull.MAX_SPAN, Hull.MAX_SPAN - 1))
                                        .executes(c -> changeCell(c, repair)))));
    }

    private static int changeCell(CommandContext<ServerCommandSource> context, boolean repair)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ShipEntity ship = nearest(context.getSource());
        var position = new Hull.Grid(IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
        int index = ship.blueprint().hull().indexOf(position);
        if (!(repair ? ship.repairCell(index) : ship.body().breach(index))) {
            throw ERROR.create(Text.translatable("naval.command.invalid_cell"));
        }
        context.getSource().sendFeedback(() -> Text.translatable(repair ? "naval.command.repair" : "naval.command.breach",
                position.x(), position.y(), position.z()), true);
        return 1;
    }

    private static ShipEntity nearest(ServerCommandSource source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (source.getEntity() != null && source.getEntity().getVehicle() instanceof ShipEntity ship && ship.body() != null) {
            return ship;
        }
        Vec3d pos = source.getPosition();
        return source.getWorld().getEntitiesByClass(ShipEntity.class, new Box(pos, pos).expand(16), s -> s.body() != null)
                .stream().min(java.util.Comparator.comparingDouble(s -> s.squaredDistanceTo(pos)))
                .orElseThrow(() -> ERROR.create(Text.translatable("naval.command.no_ship")));
    }

    private static int inspect(ServerCommandSource source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ShipEntity ship = nearest(source);
        var data = ship.telemetry();
        source.sendFeedback(() -> Text.translatable("naval.command.navigation",
                fixed(data.getDouble("Knots"), 1), fixed(data.getDouble("Heading"), 0),
                fixed(data.getDouble("Heel"), 1), fixed(data.getDouble("Flood"), 2),
                fixed(data.getDouble("Fuel"), 1), fixed(data.getDouble("Heat"), 0),
                fixed(ship.getX(), 1), fixed(ship.getY(), 1), fixed(ship.getZ(), 1)), false);
        return 1;
    }

    private static int preview(ServerCommandSource source, BlockPos helm)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var design = collect(source.getWorld(), helm).blueprint;
        report(source, design);
        return design.states().size();
    }

    static void report(ServerCommandSource source, Blueprint design) {
        var report = Hydrostatics.analyze(design.hull());
        var center = report.centerOfMass();
        source.sendFeedback(() -> Text.translatable("naval.command.design", design.states().size(),
                design.hull().compartments().size(), fixed(report.mass() / 1000, 2),
                fixed(center.x, 2), fixed(center.y, 2), fixed(center.z, 2),
                fixed(report.mass() / dev.navalengineering.simulation.Ocean.WATER_DENSITY, 2)), false);
        if (report.floats()) {
            source.sendFeedback(() -> Text.translatable("naval.command.waterline", fixed(report.draft(), 2),
                    fixed(report.reserveVolume(), 2), fixed(report.transverseGm(), 2),
                    fixed(report.longitudinalGm(), 2), fixed(report.speedEstimate() * 1.943844, 1)), false);
            if (report.transverseGm() < 0 || report.longitudinalGm() < 0 || Math.abs(center.x) > .25) {
                source.sendFeedback(() -> Text.translatable("naval.command.unstable"), false);
            }
        } else {
            source.sendFeedback(() -> Text.translatable("naval.command.sinks"), false);
        }
    }

    private static int launch(ServerCommandSource source, BlockPos helm)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerWorld world = source.getWorld();
        Site sourceBlocks = collect(world, helm);
        Vec3d position = Vec3d.ofCenter(helm);
        checkCapacity(world, position, sourceBlocks.blueprint.hull().radius());

        // Both writes and rollbacks run on the server thread before the entity can tick.
        ShipEntity ship = ShipEntity.create(world, sourceBlocks.blueprint, position);
        var removed = new HashMap<BlockPos, BlockState>();
        int flags = Block.NOTIFY_LISTENERS | Block.SKIP_DROPS | Block.FORCE_STATE;
        boolean committed = false;
        try {
            for (var entry : sourceBlocks.blocks.entrySet()) {
                if (!world.setBlockState(entry.getKey(), Blocks.AIR.getDefaultState(), flags)) {
                    throw ERROR.create(Text.translatable("naval.command.block_write"));
                }
                removed.put(entry.getKey(), entry.getValue());
            }
            if (!world.spawnEntity(ship)) throw ERROR.create(Text.translatable("naval.command.spawn"));
            committed = true;
        } finally {
            if (!committed) removed.forEach((pos, state) -> world.setBlockState(pos, state, flags));
        }
        for (BlockPos pos : removed.keySet()) world.updateNeighbors(pos, Blocks.AIR);
        source.sendFeedback(() -> Text.translatable("naval.command.launched", sourceBlocks.blueprint.states().size()), true);
        return 1;
    }

    private static int demo(ServerCommandSource source, BlockPos water, VesselDesigns type)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerWorld world = source.getWorld();
        var design = type.build();
        var ocean = new WorldOcean(world, 1);
        var column = ocean.column(water.getX(), water.getZ());
        if (column == WorldOcean.DRY || column.exposure() < .75) {
            throw ERROR.create(Text.translatable("naval.command.deep_water"));
        }
        double x = water.getX() + .5, z = water.getZ() + .5;
        double y = column.level(ocean.frame(), x, z) - Hydrostatics.analyze(design.hull()).waterline();
        var position = new Vec3d(x, y, z);
        checkCapacity(world, position, design.hull().radius());
        for (Hull.Cell hullCell : design.hull().blocks()) {
            Hull.Grid p = hullCell.position();
            BlockPos cell = BlockPos.ofFloored(x + p.x(), y + p.y(), z + p.z());
            if (!inLoadedWorld(world, cell)) throw ERROR.create(Text.translatable("naval.command.clearance"));
            for (var part : hullCell.geometry().boxes()) {
                Box voxel = new Box(x + p.x() + part.x0(), y + p.y() + part.y0(), z + p.z() + part.z0(),
                        x + p.x() + part.x1(), y + p.y() + part.y1(), z + p.z() + part.z1()).contract(.00001);
                if (world.getBlockCollisions(null, voxel).iterator().hasNext()) {
                    throw ERROR.create(Text.translatable("naval.command.clearance"));
                }
            }
        }
        var ship = ShipEntity.create(world, design, position);
        Text name = Text.translatable("naval.ship." + type.id());
        ship.setCustomName(name);
        if (!world.spawnEntity(ship)) {
            throw ERROR.create(Text.translatable("naval.command.spawn"));
        }
        source.sendFeedback(() -> Text.translatable("naval.command.demo_named", name, fixed(x, 1), fixed(y, 1), fixed(z, 1)), true);
        return 1;
    }

    private static void checkCapacity(ServerWorld world, Vec3d at, double radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (world.getEntitiesByClass(ShipEntity.class, new Box(at, at).expand(128), ship -> true).size()
                >= MAX_SHIPS_PER_AREA) {
            throw ERROR.create(Text.translatable("naval.command.capacity"));
        }
        if (!world.getEntitiesByClass(ShipEntity.class, new Box(at, at).expand(radius + Math.sqrt(3) * Hull.MAX_SPAN), ship -> {
            if (ship.blueprint() == null) return false;
            double reach = radius + ship.blueprint().hull().radius() + .5;
            return ship.squaredDistanceTo(at) < reach * reach;
        }).isEmpty()) {
            throw ERROR.create(Text.translatable("naval.command.clearance"));
        }
    }

    private static Site collect(ServerWorld world, BlockPos helm)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!world.getBlockState(helm).isOf(NavalEngineering.HELM)) {
            throw ERROR.create(Text.translatable("naval.command.helm"));
        }
        var pending = new ArrayDeque<BlockPos>();
        var visited = new HashSet<BlockPos>();
        var states = new HashMap<BlockPos, BlockState>();
        var grid = new HashMap<Hull.Grid, BlockState>();
        int helms = 0;
        visited.add(helm);
        pending.add(helm);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.remove();
            if (!inLoadedWorld(world, pos)) throw ERROR.create(Text.translatable("naval.command.unloaded"));
            BlockState state = world.getBlockState(pos);
            if (Blueprint.material(state) == null) continue;
            if (state.hasBlockEntity() || (!(state.getBlock() instanceof ProfileBlock)
                    && (state != state.getBlock().getDefaultState() || !state.isFullCube(world, pos)))) {
                throw ERROR.create(Text.translatable("naval.command.unsupported"));
            }
            if (state.isOf(NavalEngineering.HELM) && ++helms > 1) {
                throw ERROR.create(Text.translatable("naval.command.too_many_helms"));
            }
            if (states.size() >= Hull.MAX_BLOCKS) throw ERROR.create(Text.translatable("naval.command.capacity"));
            states.put(pos, state);
            try {
                grid.put(new Hull.Grid(pos.getX() - helm.getX(), pos.getY() - helm.getY(), pos.getZ() - helm.getZ()), state);
            } catch (IllegalArgumentException e) {
                throw ERROR.create(Text.literal(e.getMessage()));
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.offset(direction);
                if (visited.add(next)) pending.add(next);
            }
        }
        try {
            return new Site(Blueprint.create(grid), states);
        } catch (IllegalArgumentException e) {
            throw ERROR.create(Text.literal(e.getMessage()));
        }
    }

    private static boolean inLoadedWorld(ServerWorld world, BlockPos pos) {
        return world.isInBuildLimit(pos) && world.getWorldBorder().contains(pos)
                && world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static String fixed(double value, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private record Site(Blueprint blueprint, Map<BlockPos, BlockState> blocks) {}
}
