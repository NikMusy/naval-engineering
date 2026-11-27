package dev.navalengineering.construction;

import dev.navalengineering.simulation.Hull;
import dev.navalengineering.simulation.HullGeometry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

public final class ProfileBlock extends ShipBlock {
    private final HullGeometry.Profile profile;
    private final VoxelShape[] shapes = new VoxelShape[4];

    public ProfileBlock(Hull.Material material, HullGeometry.Profile profile, Settings settings) {
        super(material, settings.nonOpaque());
        this.profile = profile;
        setDefaultState(getStateManager().getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH));
        for (int rotation = 0; rotation < shapes.length; rotation++) {
            VoxelShape shape = VoxelShapes.empty();
            for (HullGeometry.Box box : geometry(rotation).boxes()) {
                shape = VoxelShapes.union(shape, VoxelShapes.cuboid(box.x0() + .5, box.y0() + .5, box.z0() + .5,
                        box.x1() + .5, box.y1() + .5, box.z1() + .5));
            }
            shapes[rotation] = shape.simplify();
        }
    }

    public HullGeometry geometry(int turn) { return HullGeometry.of(profile, turn); }

    public HullGeometry geometry(BlockState state) { return geometry(turn(state)); }

    public static int turn(BlockState state) { return turn(state.get(Properties.HORIZONTAL_FACING)); }

    public static int turn(Direction direction) {
        return switch (direction) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Expected a horizontal facing");
        };
    }

    public BlockState facing(Direction direction) { return getDefaultState().with(Properties.HORIZONTAL_FACING, direction); }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(Properties.HORIZONTAL_FACING);
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return facing(context.getHorizontalPlayerFacing());
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return facing(rotation.rotate(state.get(Properties.HORIZONTAL_FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) return state;
        int turns = turn(state);
        int mirrored = switch (profile) {
            case BOW -> (mirror == BlockMirror.FRONT_BACK ? 3 : 1) - turns;
            case CHINE, SHOULDER -> (mirror == BlockMirror.FRONT_BACK ? 2 : 0) - turns;
            default -> turn(mirror.apply(state.get(Properties.HORIZONTAL_FACING)));
        };
        return facing(switch (Math.floorMod(mirrored, 4)) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            default -> Direction.WEST;
        });
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[turn(state)];
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes[turn(state)];
    }
}
