package io.canvasmc.canvas.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * This is part of the C2ME mod for Fabric:
 * <p>
 * <a
 * href="https://github.com/RelativityMC/C2ME-fabric/blob/dev/1.21.11/c2me-rewrites-chunk-system/src/main/java/com/ishland/c2me/rewrites/chunksystem/common/quirks/FlowableFluidUtils.java">Link
 * to file</a>
 *
 * @author ishland
 */
public final class FlowableFluidUtils {

    private FlowableFluidUtils() {
    }

    private static boolean canFlowNormally(final LevelReader level, final BlockPos pos, final BlockState state, final FluidState fluidState) {
        if (fluidState.isEmpty()) return false;

        final BlockPos belowPos = pos.below();
        final BlockState belowBlockState = level.getBlockState(belowPos);
        final FluidState belowFluidState = belowBlockState.getFluidState();
        // very rough filtering
        if (((FlowingFluid) fluidState.getType()).canMaybePassThrough(level, pos, state, Direction.DOWN, belowPos, belowBlockState, belowFluidState)) {
            final FluidState updatedState = getUpdatedState((FlowingFluid) fluidState.getType(), level, belowPos, belowBlockState);
            if (updatedState == null) {
                return true; // shortcut
            }
            final Fluid fluid = updatedState.getType();
            if (belowFluidState.canBeReplacedWith(level, belowPos, fluid, Direction.DOWN) && FlowingFluid.canHoldSpecificFluid(level, belowPos, belowBlockState, fluid)) {
                return true;
            }
        }
        return (fluidState.isSource() || !(((FlowingFluid) fluidState.getType()).isWaterHole(level, pos, state, belowPos, belowBlockState))) &&
            canSpreadToSidesNormally(level, pos, state, fluidState);
    }

    private static boolean canSpreadToSidesNormally(final LevelReader level, final BlockPos pos, final BlockState state, final FluidState fluidState) {
        int nextFluidLevel = fluidState.getAmount() - ((FlowingFluid) fluidState.getType()).getDropOff(level);
        if (fluidState.getValue(FlowingFluid.FALLING)) {
            nextFluidLevel = 7;
        }
        if (nextFluidLevel > 0) {
            for (final Direction direction : Direction.Plane.HORIZONTAL) {
                final BlockPos offsetPos = pos.relative(direction);
                final BlockState offsetBlockState = level.getBlockState(offsetPos);
                final FluidState offsetFluidState = offsetBlockState.getFluidState();
                if (((FlowingFluid) fluidState.getType()).canMaybePassThrough(level, pos, state, direction, offsetPos, offsetBlockState, offsetFluidState)) {
                    final FluidState updatedState = getUpdatedState((FlowingFluid) fluidState.getType(), level, offsetPos, offsetBlockState);
                    if (updatedState == null) {
                        return true; // shortcut
                    }
                    if (FlowingFluid.canHoldSpecificFluid(level, offsetPos, offsetBlockState, updatedState.getType())) {
                        return true; // shortcut
                    }
                }
            }
        }

        return false;
    }

    @Nullable
    private static FluidState getUpdatedState(final FlowingFluid receiver, final LevelReader level, final BlockPos pos, final BlockState state) {
        int maxAmount = 0;
        int sourceCount = 0;
        final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (final Direction direction : Direction.Plane.HORIZONTAL) {
            final BlockPos blockPos = mutable.setWithOffset(pos, direction);
            final BlockState blockState = level.getBlockState(blockPos);
            final FluidState fluidState = blockState.getFluidState();
            if (fluidState.getType().isSame(receiver) && FlowingFluid.canPassThroughWall(direction, level, pos, state, blockPos, blockState)) {
                if (fluidState.isSource()) {
                    sourceCount++;
                }

                maxAmount = Math.max(maxAmount, fluidState.getAmount());
            }
        }

        // two or more adjacent sources mean the position is already saturated, so the caller
        // must not filter this block away
        if (sourceCount >= 2) {
            return null; // to not filter this
        }

        final BlockPos upPos = mutable.setWithOffset(pos, Direction.UP);
        final BlockState upState = level.getBlockState(upPos);
        final FluidState upFluidState = upState.getFluidState();
        if (!upFluidState.isEmpty() && upFluidState.getType().isSame(receiver) && FlowingFluid.canPassThroughWall(Direction.UP, level, pos, state, upPos, upState)) {
            return receiver.getFlowing(8, true);
        } else {
            final int amount = maxAmount - receiver.getDropOff(level);
            return amount <= 0 ? Fluids.EMPTY.defaultFluidState() : receiver.getFlowing(amount, false);
        }
    }

    public static boolean needsPostProcessing(final LevelReader level, final BlockPos pos, final BlockState state, final FluidState fluidState) {
        if (!fluidState.isSource()) {
            return true;
        }
        return canFlowNormally(level, pos, state, fluidState);
    }

}
