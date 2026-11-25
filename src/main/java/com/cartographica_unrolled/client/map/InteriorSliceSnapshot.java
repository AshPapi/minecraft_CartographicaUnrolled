package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.util.AtlasMath;
import com.cartographica_unrolled.world.AtlasBlockClassifier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.BeaconBeamBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

final class InteriorSliceSnapshot {
    static final int KIND_NONE = 0;
    static final int KIND_FLOOR = 1;
    static final int KIND_WALL = 2;
    static final int KIND_WINDOW = 3;
    static final int KIND_DOOR = 4;
    static final int KIND_BOOKSHELF = 5;

    private InteriorSliceSnapshot() {
    }

    static final class Cell {
        int wx;
        int wz;
        int kind = KIND_NONE;
        int floorY;
        int floorRgb;
        int floorTintRgb = 0xFFFFFF;
        int primaryRgb;
        int primaryTintRgb = 0xFFFFFF;
        int sillTintRgb = 0xFFFFFF;
        int lowerTintRgb = 0xFFFFFF;
        int upperTintRgb = 0xFFFFFF;
        boolean floorWater;
        boolean floorLava;
        boolean inNiche;
        int lightEmission;
        int lightColor = 0xFFBE5C;
        float lightOffX = 7.5f;
        float lightOffY = 7.5f;
        BlockState floorState = Blocks.AIR.defaultBlockState();
        BlockState primaryState = Blocks.AIR.defaultBlockState();
        BlockState sillState = Blocks.AIR.defaultBlockState();
        BlockState carpetState = Blocks.AIR.defaultBlockState();
        BlockState lowerFixture = Blocks.AIR.defaultBlockState();
        BlockState upperFixture = Blocks.AIR.defaultBlockState();
        BlockState ceilingFixture = Blocks.AIR.defaultBlockState();

        void reset(int wx, int wz) {
            BlockState air = Blocks.AIR.defaultBlockState();
            this.wx = wx;
            this.wz = wz;
            this.kind = KIND_NONE;
            this.floorY = 0;
            this.floorRgb = 0;
            this.floorTintRgb = 0xFFFFFF;
            this.primaryRgb = 0;
            this.primaryTintRgb = 0xFFFFFF;
            this.sillTintRgb = 0xFFFFFF;
            this.lowerTintRgb = 0xFFFFFF;
            this.upperTintRgb = 0xFFFFFF;
            this.floorWater = false;
            this.floorLava = false;
            this.inNiche = false;
            this.lightEmission = 0;
            this.lightColor = 0xFFBE5C;
            this.lightOffX = 7.5f;
            this.lightOffY = 7.5f;
            this.floorState = air;
            this.primaryState = air;
            this.sillState = air;
            this.carpetState = air;
            this.lowerFixture = air;
            this.upperFixture = air;
            this.ceilingFixture = air;
        }

        long hashSignature() {
            if (this.kind == KIND_NONE) {
                return KIND_NONE;
            }
            long h = this.kind;
            h = mixHash(h, this.floorY);
            h = mixHash(h, this.floorRgb);
            h = mixHash(h, this.floorTintRgb);
            h = mixHash(h, this.primaryRgb);
            h = mixHash(h, this.primaryTintRgb);
            h = mixHash(h, this.sillTintRgb);
            h = mixHash(h, this.lowerTintRgb);
            h = mixHash(h, this.upperTintRgb);
            h = mixHash(h, this.inNiche ? 1 : 0);
            h = mixHash(h, this.lightEmission);
            if (this.lightEmission >= 6) {
                h = mixHash(h, this.lightColor);
                h = mixHash(h, Float.floatToIntBits(this.lightOffX));
                h = mixHash(h, Float.floatToIntBits(this.lightOffY));
            }
            h = mixHash(h, this.floorState.hashCode());
            h = mixHash(h, this.primaryState.hashCode());
            h = mixHash(h, this.sillState.hashCode());
            h = mixHash(h, this.carpetState.hashCode());
            h = mixHash(h, this.lowerFixture.hashCode());
            h = mixHash(h, this.upperFixture.hashCode());
            h = mixHash(h, this.ceilingFixture.hashCode());
            return h;
        }
    }

    static long mixHash(long h, long v) {
        h ^= v;
        return h * 1099511628211L;
    }

    static void inspectCell(
            ClientLevel level,
            LevelChunk chunk,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int wz,
            int playerY,
            int minY,
            Cell out
    ) {
        subPos.set(wx, playerY + 1, wz);
        BlockState eyeState = chunk.getBlockState(subPos);
        pos.set(wx, playerY, wz);
        BlockState feetState = chunk.getBlockState(pos);
        boolean eyeOpen = AtlasBlockClassifier.isIndoorPassable(level, subPos, eyeState)
                || AtlasBlockClassifier.isIndoorFurnitureOrFixture(eyeState);

        int minFloorY = Math.max(minY, playerY - 4);
        int floorY = playerY - 1;
        BlockState floorState = Blocks.OAK_PLANKS.defaultBlockState();

        for (int fy = playerY; fy >= minFloorY; fy--) {
            subPos.set(wx, fy, wz);
            BlockState bs = chunk.getBlockState(subPos);
            if (bs.isAir()) {
                continue;
            }
            if (fy == playerY && isCarpet(bs)) {
                continue;
            }
            if (fy == playerY && (AtlasBlockClassifier.isIndoorFurnitureOrFixture(bs)
                    || bs.is(BlockTags.FENCES) || bs.is(BlockTags.TRAPDOORS)
                    || bs.is(BlockTags.DOORS) || bs.is(BlockTags.FENCE_GATES)
                    || bs.is(BlockTags.STAIRS) || bs.is(BlockTags.SLABS)
                    || bs.is(BlockTags.RAILS) || bs.is(BlockTags.PRESSURE_PLATES)
                    || bs.is(BlockTags.BUTTONS) || bs.is(BlockTags.SIGNS)
                    || bs.is(BlockTags.BANNERS) || bs.is(Blocks.TORCH)
                    || bs.is(Blocks.WALL_TORCH) || bs.is(Blocks.SOUL_TORCH)
                    || bs.is(Blocks.SOUL_WALL_TORCH) || bs.is(Blocks.REDSTONE_TORCH)
                    || bs.is(Blocks.REDSTONE_WALL_TORCH) || bs.is(Blocks.END_ROD))) {
                if ((bs.is(BlockTags.STAIRS) || bs.is(BlockTags.SLABS))
                        && bs.isCollisionShapeFullBlock(level, subPos)) {
                    floorY = fy;
                    floorState = bs;
                    break;
                }
                continue;
            }
            if (bs.getMapColor(level, subPos) != MapColor.NONE || !bs.getFluidState().isEmpty()) {
                floorY = fy;
                floorState = bs;
                break;
            }
        }

        out.floorState = floorState;
        out.floorY = floorY;
        out.floorRgb = resolveRgb(level, subPos.set(wx, floorY, wz), floorState, 0x9C7D53);
        out.floorTintRgb = resolveBiomeTintIfNeeded(level, subPos, floorState);
        FluidState floorFluid = floorState.getFluidState();
        out.floorWater = !floorFluid.isEmpty() && !floorFluid.is(FluidTags.LAVA);
        out.floorLava = !floorFluid.isEmpty() && floorFluid.is(FluidTags.LAVA);

        if (feetState.is(BlockTags.DOORS) || eyeState.is(BlockTags.DOORS)
                || feetState.is(BlockTags.FENCE_GATES) || eyeState.is(BlockTags.FENCE_GATES)) {
            boolean feetDoor = feetState.is(BlockTags.DOORS) || feetState.is(BlockTags.FENCE_GATES);
            BlockState doorState = feetDoor ? feetState : eyeState;
            out.kind = KIND_DOOR;
            out.primaryState = doorState;
            out.primaryRgb = resolveRgb(level, pos.set(wx, playerY, wz), doorState, 0x8C6438);
            registerLight(level, subPos.set(wx, floorY, wz), out, floorState);
            registerLight(level, subPos.set(wx, feetDoor ? playerY : playerY + 1, wz), out, doorState);
            return;
        }

        boolean eyeWindow = AtlasBlockClassifier.isWindowOrBars(eyeState) && eyeState.getFluidState().isEmpty();
        boolean feetWindow = AtlasBlockClassifier.isWindowOrBars(feetState) && feetState.getFluidState().isEmpty();
        if (eyeWindow || (!eyeOpen && feetWindow)) {
            BlockState winState = eyeWindow ? eyeState : feetState;
            out.kind = KIND_WINDOW;
            out.primaryState = winState;
            out.primaryRgb = resolveWindowRgb(winState);
            if (!feetState.isAir() && !feetWindow && feetState.getFluidState().isEmpty()
                    && feetState.isCollisionShapeFullBlock(level, pos.set(wx, playerY, wz))) {
                out.sillState = feetState;
                out.sillTintRgb = resolveBiomeTintIfNeeded(level, pos, feetState);
            }
            registerLight(level, subPos.set(wx, eyeWindow ? playerY + 1 : playerY, wz), out, winState);
            return;
        }

        if (eyeState.is(Blocks.BOOKSHELF) || eyeState.is(Blocks.CHISELED_BOOKSHELF)
                || feetState.is(Blocks.BOOKSHELF) || feetState.is(Blocks.CHISELED_BOOKSHELF)) {
            BlockState shelf = (eyeState.is(Blocks.BOOKSHELF) || eyeState.is(Blocks.CHISELED_BOOKSHELF))
                    ? eyeState : feetState;
            out.kind = KIND_BOOKSHELF;
            out.primaryState = shelf;
            out.primaryRgb = 0x8C663B;
            return;
        }

        if (!eyeOpen) {
            if (isNicheWorkstation(feetState)) {
                out.kind = KIND_FLOOR;
                out.primaryState = floorState;
                out.primaryRgb = out.floorRgb;
                out.lowerFixture = feetState;
                out.lowerTintRgb = resolveBiomeTintIfNeeded(level, pos.set(wx, playerY, wz), feetState);
                out.inNiche = true;
                registerLight(level, subPos.set(wx, floorY, wz), out, floorState);
                registerLight(level, pos.set(wx, playerY, wz), out, feetState);
                return;
            }
            out.kind = KIND_WALL;
            out.primaryState = eyeState;
            out.primaryRgb = resolveRgb(level, subPos.set(wx, playerY + 1, wz), eyeState, 0x7A6045);
            out.primaryTintRgb = resolveBiomeTintIfNeeded(level, subPos, eyeState);
            subPos.set(wx, playerY + 2, wz);
            BlockState aboveWall = chunk.getBlockState(subPos);
            if (isHighLightFixture(aboveWall)) {
                out.ceilingFixture = aboveWall;
                registerLight(level, subPos, out, aboveWall);
            }
            registerLight(level, subPos.set(wx, playerY + 1, wz), out, eyeState);
            return;
        }

        out.kind = KIND_FLOOR;
        out.primaryState = floorState;
        out.primaryRgb = out.floorRgb;

        if (!feetState.isAir() && floorY < playerY) {
            if (isCarpet(feetState)) {
                out.carpetState = feetState;
            } else if (!AtlasBlockClassifier.isGroundFloraToSkip(feetState) || feetState.is(BlockTags.FLOWER_POTS)) {
                out.lowerFixture = feetState;
                out.lowerTintRgb = resolveBiomeTintIfNeeded(level, pos.set(wx, playerY, wz), feetState);
            }
        }

        if (!eyeState.isAir() && (!AtlasBlockClassifier.isGroundFloraToSkip(eyeState)
                || AtlasBlockClassifier.isIndoorFurnitureOrFixture(eyeState))) {
            boolean sameDoubleBlock = !out.lowerFixture.isAir() && out.lowerFixture.getBlock() == eyeState.getBlock();
            if (!sameDoubleBlock) {
                out.upperFixture = eyeState;
                out.upperTintRgb = resolveBiomeTintIfNeeded(level, subPos.set(wx, playerY + 1, wz), eyeState);
            }
        }

        int ceilingY = playerY + 2;
        subPos.set(wx, playerY + 2, wz);
        BlockState highState = chunk.getBlockState(subPos);
        if (isHighLightFixture(highState)) {
            out.ceilingFixture = highState;
        } else if (highState.isAir()) {
            subPos.set(wx, playerY + 3, wz);
            BlockState higherState = chunk.getBlockState(subPos);
            if (isHighLightFixture(higherState)) {
                out.ceilingFixture = higherState;
                ceilingY = playerY + 3;
            }
        }

        registerLight(level, subPos.set(wx, floorY, wz), out, floorState);
        registerLight(level, pos.set(wx, playerY, wz), out, out.lowerFixture);
        registerLight(level, subPos.set(wx, playerY + 1, wz), out, out.upperFixture);
        registerLight(level, subPos.set(wx, ceilingY, wz), out, out.ceilingFixture);
    }

    static boolean isThinReadableFixture(BlockState state) {
        return state.is(BlockTags.DOORS)
                || state.is(BlockTags.FENCE_GATES)
                || state.is(BlockTags.FENCES)
                || state.is(BlockTags.TRAPDOORS);
    }

    static boolean isMasked(Cell[] cells, int spanX, int spanZ, int cx, int cz) {
        return cx >= 0 && cx < spanX && cz >= 0 && cz < spanZ && cells[cz * spanX + cx].kind != KIND_NONE;
    }

    static boolean isKind(Cell[] cells, int spanX, int spanZ, int cx, int cz, int kind) {
        return cx >= 0 && cx < spanX && cz >= 0 && cz < spanZ && cells[cz * spanX + cx].kind == kind;
    }

    static boolean isOpenRoom(Cell[] cells, int spanX, int spanZ, int cx, int cz) {
        if (cx < 0 || cx >= spanX || cz < 0 || cz >= spanZ) {
            return false;
        }
        int k = cells[cz * spanX + cx].kind;
        return k == KIND_FLOOR || k == KIND_DOOR;
    }

    static boolean hasCarpet(Cell[] cells, int spanX, int spanZ, int cx, int cz) {
        if (cx < 0 || cx >= spanX || cz < 0 || cz >= spanZ) {
            return false;
        }
        Cell c = cells[cz * spanX + cx];
        return c.kind == KIND_FLOOR && !c.carpetState.isAir();
    }

    static boolean isWallLike(Cell[] cells, int spanX, int spanZ, int cx, int cz) {
        if (cx < 0 || cx >= spanX || cz < 0 || cz >= spanZ) {
            return false;
        }
        int k = cells[cz * spanX + cx].kind;
        return k == KIND_WALL || k == KIND_WINDOW || k == KIND_BOOKSHELF;
    }

    static boolean isTallBlocker(Cell[] cells, int spanX, int spanZ, int cx, int cz) {
        return isWallLike(cells, spanX, spanZ, cx, cz);
    }

    static int higherFloorDelta(Cell[] cells, int spanX, int spanZ, int cx, int cz, int myFloorY) {
        if (cx < 0 || cx >= spanX || cz < 0 || cz >= spanZ) {
            return 0;
        }
        Cell n = cells[cz * spanX + cx];
        if (n.kind != KIND_FLOOR && n.kind != KIND_DOOR) {
            return 0;
        }
        return Math.max(0, n.floorY - myFloorY);
    }

    static int lowerFloorDelta(Cell[] cells, int spanX, int spanZ, int cx, int cz, int myFloorY) {
        if (cx < 0 || cx >= spanX || cz < 0 || cz >= spanZ) {
            return 0;
        }
        Cell n = cells[cz * spanX + cx];
        if (n.kind != KIND_FLOOR && n.kind != KIND_DOOR) {
            return 0;
        }
        return Math.max(0, myFloorY - n.floorY);
    }

    static boolean isLightUnoccluded(Cell[] cells, int spanX, int x0, int z0, int x1, int z1) {
        if (x0 == x1 && z0 == z1) {
            return true;
        }
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;
        int cx = x0;
        int cz = z0;

        while (true) {
            int e2 = err << 1;
            boolean stepX = e2 > -dz;
            boolean stepZ = e2 < dx;
            if (stepX && stepZ) {
                if (cells[cz * spanX + (cx + sx)].kind == KIND_WALL
                        && cells[(cz + sz) * spanX + cx].kind == KIND_WALL) {
                    return false;
                }
            }
            if (stepX) {
                err -= dz;
                cx += sx;
            }
            if (stepZ) {
                err += dx;
                cz += sz;
            }
            if (cx == x1 && cz == z1) {
                return true;
            }
            if (cells[cz * spanX + cx].kind == KIND_WALL) {
                return false;
            }
        }
    }

    private static void registerLight(ClientLevel level, BlockPos pos, Cell out, BlockState state) {
        if (state.isAir()) {
            return;
        }
        int emission = Math.min(15, state.getLightEmission(level, pos));
        if (state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) {
            boolean lit = !state.hasProperty(BlockStateProperties.LIT) || state.getValue(BlockStateProperties.LIT);
            emission = lit ? 15 : 0;
        } else if (state.is(Blocks.FURNACE) || state.is(Blocks.SMOKER) || state.is(Blocks.BLAST_FURNACE)) {
            boolean lit = state.hasProperty(BlockStateProperties.LIT) && state.getValue(BlockStateProperties.LIT);
            emission = lit ? 13 : 0;
        }
        if (emission <= out.lightEmission || emission < 6) {
            return;
        }
        out.lightEmission = emission;
        out.lightColor = resolveLightColor(state);
        out.lightOffX = 7.5f;
        out.lightOffY = 7.5f;
        if (state.is(Blocks.WALL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            Direction facing = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.SOUTH;
            switch (facing) {
                case NORTH -> out.lightOffY = 12.5f;
                case SOUTH -> out.lightOffY = 3.5f;
                case WEST -> out.lightOffX = 12.5f;
                case EAST -> out.lightOffX = 3.5f;
                default -> {
                }
            }
        }
    }

    private static int resolveLightColor(BlockState state) {
        if (state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.SOUL_LANTERN) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.SOUL_FIRE)) {
            return 0x5EE0EE;
        }
        if (state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
            return 0xE64B3A;
        }
        if (state.is(Blocks.SEA_LANTERN) || state.is(Blocks.BEACON) || state.is(Blocks.END_ROD)) {
            return 0xD8F2F6;
        }
        if (state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.AMETHYST_CLUSTER)
                || state.is(Blocks.RESPAWN_ANCHOR)) {
            return 0xBC74F0;
        }
        return 0xFFBE5C;
    }

    private static int resolveWindowRgb(BlockState state) {
        if (state.getBlock() instanceof BeaconBeamBlock beamBlock) {
            int dye = beamBlock.getColor().getTextureDiffuseColor() & 0xFFFFFF;
            return AtlasMath.mixRgb(AtlasMath.harmonizeRgb(dye, 0.85f, 1.05f), 0xDCE8EE, 0.28f);
        }
        if (state.is(Blocks.TINTED_GLASS)) {
            return 0x5E526A;
        }
        return 0xC2DFE8;
    }

    private static int resolveBiomeTintIfNeeded(ClientLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            return 0xFFFFFF;
        }
        boolean fluidOrCauldron = !state.getFluidState().isEmpty() || state.is(Blocks.WATER_CAULDRON);
        if (!fluidOrCauldron) {
            BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(state);
            if (tile == null || !tile.hasAnyHiTint()) {
                return 0xFFFFFF;
            }
        }
        Biome biome = level.getBiome(pos).value();
        if (fluidOrCauldron) {
            int c = biome.getWaterColor();
            return c != 0 ? (c & 0xFFFFFF) : 0x3F76E4;
        }
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.VINE)) {
            int c = biome.getFoliageColor();
            return c != 0 ? (c & 0xFFFFFF) : 0x629644;
        }
        int c = biome.getGrassColor(pos.getX(), pos.getZ());
        return c != 0 ? (c & 0xFFFFFF) : 0x7FA65A;
    }

    private static boolean isCarpet(BlockState state) {
        return state.is(BlockTags.WOOL_CARPETS)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.PALE_MOSS_CARPET);
    }

    private static boolean isNicheWorkstation(BlockState state) {
        return !isCarpet(state)
                && !state.is(Blocks.COMPOSTER)
                && !state.is(Blocks.BELL)
                && !state.is(Blocks.LANTERN)
                && !state.is(Blocks.SOUL_LANTERN)
                && !state.is(Blocks.CAKE)
                && !state.is(Blocks.DECORATED_POT)
                && !state.is(BlockTags.FLOWER_POTS)
                && !state.is(BlockTags.CANDLES)
                && !state.is(Blocks.SCAFFOLDING)
                && AtlasBlockClassifier.isIndoorFurnitureOrFixture(state);
    }

    private static boolean isHighLightFixture(BlockState state) {
        return state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.LANTERN)
                || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.END_ROD);
    }

    private static int resolveRgb(ClientLevel level, BlockPos pos, BlockState state, int fallback) {
        MapColor mc = state.getMapColor(level, pos);
        return (mc != MapColor.NONE && mc.col != 0) ? mc.col : fallback;
    }
}
