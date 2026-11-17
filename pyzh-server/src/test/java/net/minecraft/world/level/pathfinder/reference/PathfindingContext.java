package net.minecraft.world.level.pathfinder.reference;

import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public class PathfindingContext {
    private final CollisionGetter level;
    public static @Nullable PathTypeCache levelCache;
    public @Nullable PathTypeCache cache; // Kaiiju - petal - async path processing - private -> public-f
    private final BlockPos mobPosition;
    private final boolean stonecutterDamaging;
    private final BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

    public PathfindingContext(final CollisionGetter level, final Mob mob) {
        this.level = level;
        this.cache = levelCache;

        this.mobPosition = mob.blockPosition();
        this.stonecutterDamaging = mob.level().purpurConfig.stonecutterDamage > 0.0F;
    }

    public PathType getPathTypeFromState(final int x, final int y, final int z) {
        BlockPos pos = this.mutablePos.set(x, y, z);
        PathType type = this.cache == null ? WalkNodeEvaluator.leaf$pathType(this.level, pos) : this.cache.getOrCompute(this.level, pos); // Leaf - Cache block state tags
        // Trapdoor tags precede stonecutter damage in Leaf; open and water states cannot be stonecutters.
        if (this.stonecutterDamaging && type != PathType.OPEN && type != PathType.WATER && type != PathType.TRAPDOOR) {
            BlockState state = this.level.getBlockStateIfLoaded(pos);
            if (state != null && state.is(net.minecraft.world.level.block.Blocks.STONECUTTER)) {
                return PathType.DAMAGING;
            }
        }
        return type;
    }

    public BlockState getBlockState(final BlockPos pos) {
        return this.level.getBlockState(pos);
    }

    public CollisionGetter level() {
        return this.level;
    }

    public BlockPos mobPosition() {
        return this.mobPosition;
    }
}
