package com.cartographica_unrolled.world;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

final class ColumnDepthScanner {
    private ColumnDepthScanner() {
    }

    static long sampleIceColumn(
            Level level,
            LevelChunk chunk,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos subPos,
            BlockPos.MutableBlockPos auxPos,
            int wx,
            int wz,
            int surfaceY,
            int minY
    ) {
        int iceDepth = 1;
        int fy = surfaceY - 1;
        while (fy > minY && iceDepth < 63) {
            subPos.set(wx, fy, wz);
            BlockState below = chunk.getBlockState(subPos);
            if (!below.getFluidState().is(FluidTags.WATER) && !below.is(BlockTags.ICE)) {
                break;
            }
            iceDepth++;
            fy--;
        }
        auxPos.set(wx, surfaceY + 1, wz);
        int light = Math.max(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.BLOCK, auxPos));
        subPos.set(wx, Math.max(minY, fy + 1), wz);
        light = Math.max(light, level.getBrightness(LightLayer.BLOCK, subPos) - 3);
        subPos.set(wx, Math.max(minY, fy), wz);
        BlockState floorState = chunk.getBlockState(subPos);
        int floorRgb = floorState.getMapColor(level, subPos).col;
        int floorLuma = (((floorRgb >> 16) & 255) * 77 + ((floorRgb >> 8) & 255) * 150 + (floorRgb & 255) * 29) >> 8;
        int iceFlags = AtlasChunkTerrain.FLAG_SNOW_ICE | AtlasChunkTerrain.FLAG_WATER;
        if (floorState.getLightEmission(level, subPos) >= 10) {
            iceFlags |= AtlasChunkTerrain.FLAG_GLOW;
        }
        return AtlasChunkTerrain.pack(0x96B2B6, surfaceY, iceDepth, iceFlags, 0, Mth.clamp(floorLuma >> 4, 1, 15), light);
    }

    static long sampleWaterColumn(
            Level level,
            LevelChunk chunk,
            BiomeTintCache tintCache,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos subPos,
            BlockPos.MutableBlockPos auxPos,
            BlockState topState,
            int wx,
            int wz,
            int surfaceY,
            int minY,
            boolean hadLilyPad
    ) {
        int flags = AtlasChunkTerrain.FLAG_WATER;
        int waterDetail = hadLilyPad ? AtlasChunkTerrain.DETAIL_WATER_LILY_PAD : AtlasChunkTerrain.DETAIL_NONE;
        int coralRgb = 0;
        if (AtlasBlockClassifier.isCoralOrPickle(topState)) {
            waterDetail = AtlasChunkTerrain.DETAIL_WATER_CORAL;
            coralRgb = topState.getMapColor(level, pos).col;
        } else if (AtlasBlockClassifier.isUnderwaterFlora(topState) && waterDetail == AtlasChunkTerrain.DETAIL_NONE) {
            waterDetail = AtlasChunkTerrain.DETAIL_WATER_KELP_SEAGRASS;
        }

        int fy = surfaceY - 1;
        int depth = 1;
        BlockState floorState = Blocks.STONE.defaultBlockState();
        while (fy > minY && depth < 63) {
            subPos.set(wx, fy, wz);
            BlockState below = chunk.getBlockState(subPos);
            if (!below.getFluidState().isEmpty()) {
                if (AtlasBlockClassifier.isCoralOrPickle(below)) {
                    waterDetail = AtlasChunkTerrain.DETAIL_WATER_CORAL;
                    if (coralRgb == 0) {
                        coralRgb = below.getMapColor(level, subPos).col;
                    }
                } else if (AtlasBlockClassifier.isUnderwaterFlora(below) && waterDetail == AtlasChunkTerrain.DETAIL_NONE) {
                    waterDetail = AtlasChunkTerrain.DETAIL_WATER_KELP_SEAGRASS;
                }
                depth++;
                fy--;
            } else {
                floorState = below;
                if (AtlasBlockClassifier.isCoralOrPickle(below)) {
                    waterDetail = AtlasChunkTerrain.DETAIL_WATER_CORAL;
                    if (coralRgb == 0) {
                        coralRgb = below.getMapColor(level, subPos).col;
                    }
                }
                break;
            }
        }
        subPos.set(wx, Math.max(minY, fy), wz);
        int floorCol = floorState.getMapColor(level, subPos).col;
        if (coralRgb != 0) {
            floorCol = AtlasMath.mixRgb(floorCol, coralRgb, 0.55f);
        }
        int luma = (((floorCol >> 16) & 0xFF) * 77 + ((floorCol >> 8) & 0xFF) * 150 + (floorCol & 0xFF) * 29) >> 8;
        int floorHint = Mth.clamp(luma >> 4, 1, 15);
        if (floorState.getLightEmission(level, subPos) >= 10 || topState.is(Blocks.SEA_PICKLE)) {
            flags |= AtlasChunkTerrain.FLAG_GLOW;
        }

        pos.set(wx, surfaceY, wz);
        int biomeWater = tintCache.sampleWaterTint(level, pos);
        if (coralRgb != 0 && depth <= 8) {
            biomeWater = AtlasMath.mixRgb(biomeWater, coralRgb, 0.28f);
        }
        auxPos.set(wx, surfaceY + 1, wz);
        int waterLight = Math.max(level.getBrightness(LightLayer.BLOCK, auxPos), level.getBrightness(LightLayer.BLOCK, pos));
        subPos.set(wx, Math.max(minY, fy + 1), wz);
        waterLight = Math.max(waterLight, level.getBrightness(LightLayer.BLOCK, subPos) - 3);
        return AtlasChunkTerrain.pack(biomeWater, surfaceY, depth, flags, waterDetail, floorHint, waterLight);
    }

    static boolean isSnowyEnvironment(
            Level level,
            LevelChunk chunk,
            BlockPos pos,
            BlockPos.MutableBlockPos neighbor,
            int bx,
            int bz
    ) {
        if (level.getBiome(pos).value().coldEnoughToSnow(pos, level.getSeaLevel())) {
            return true;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (Math.abs(dx) + Math.abs(dz) != 1 || bx + dx < 0 || bx + dx >= 16 || bz + dz < 0 || bz + dz >= 16) {
                    continue;
                }
                neighbor.set(pos.getX() + dx, pos.getY(), pos.getZ() + dz);
                BlockState state = chunk.getBlockState(neighbor);
                if (state.is(Blocks.SNOW) || (state.is(Blocks.GRASS_BLOCK)
                        && state.hasProperty(BlockStateProperties.SNOWY) && state.getValue(BlockStateProperties.SNOWY))) {
                    return true;
                }
                neighbor.set(pos.getX() + dx, pos.getY() + 1, pos.getZ() + dz);
                if (chunk.getBlockState(neighbor).is(Blocks.SNOW)) {
                    return true;
                }
            }
        }
        return false;
    }

    static int findGroundUnderCanopy(
            Level level,
            LevelChunk chunk,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int wz,
            int bx,
            int bz,
            int surfaceY,
            int minY,
            boolean hasCeiling
    ) {
        if (!hasCeiling) {
            int hmY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            if (hmY < surfaceY && hmY > minY) {
                subPos.set(wx, hmY - 1, wz);
                BlockState hmState = chunk.getBlockState(subPos);
                if (!AtlasBlockClassifier.isCanopyBlock(hmState)
                        && !AtlasBlockClassifier.isGroundFloraToSkip(hmState)) {
                    return hmY;
                }
            }
        }
        int limitY = Math.max(minY, surfaceY - 32);
        for (int fy = surfaceY - 1; fy >= limitY; fy--) {
            subPos.set(wx, fy, wz);
            BlockState below = chunk.getBlockState(subPos);
            if (below.isAir()
                    || AtlasBlockClassifier.isCanopyBlock(below)
                    || AtlasBlockClassifier.isGroundFloraToSkip(below)) {
                continue;
            }
            if (!below.getFluidState().isEmpty()
                    || below.getMapColor(level, subPos) != MapColor.NONE
                    || AtlasBlockClassifier.isStructureOrRoad(below)) {
                return fy;
            }
        }
        return Math.max(minY, surfaceY - 4);
    }

    static int compositeUnderTranslucent(
            Level level,
            LevelChunk chunk,
            BiomeTintCache tintCache,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int wz,
            int surfaceY,
            int minY,
            BlockState topState,
            int topRgb
    ) {
        int topColor = topRgb != 0 ? topRgb : 0xD8E8EE;
        float topAlpha = AtlasBlockClassifier.getTranslucentAlpha(topState);
        float accR = ((topColor >> 16) & 0xFF) * topAlpha;
        float accG = ((topColor >> 8) & 0xFF) * topAlpha;
        float accB = (topColor & 0xFF) * topAlpha;
        float transmittance = 1.0f - topAlpha;

        int baseRgb = 0x4A3F35;
        int limitY = Math.max(minY, surfaceY - 16);
        for (int fy = surfaceY - 1; fy >= limitY && transmittance > 0.05f; fy--) {
            subPos.set(wx, fy, wz);
            BlockState below = chunk.getBlockState(subPos);
            if (below.isAir() || AtlasBlockClassifier.isGroundFloraToSkip(below)) {
                continue;
            }
            if (AtlasBlockClassifier.isTranslucentBlock(below)) {
                int c = below.getMapColor(level, subPos).col;
                if (c == 0) {
                    c = 0xD8E8EE;
                }
                float bAlpha = AtlasBlockClassifier.getTranslucentAlpha(below);
                float a = bAlpha * transmittance;
                accR += ((c >> 16) & 0xFF) * a;
                accG += ((c >> 8) & 0xFF) * a;
                accB += (c & 0xFF) * a;
                transmittance *= (1.0f - bAlpha);
                continue;
            }
            FluidState fs = below.getFluidState();
            if (!fs.isEmpty()) {
                baseRgb = fs.is(FluidTags.LAVA) ? 0xD95B16 : tintCache.sampleWaterTint(level, subPos);
                break;
            }
            MapColor mc = below.getMapColor(level, subPos);
            if (mc != MapColor.NONE || AtlasBlockClassifier.isStructureOrRoad(below)) {
                baseRgb = mc.col != 0 ? mc.col : 0x786048;
                if (AtlasBlockClassifier.isBiomeGrassTinted(below, mc)) {
                    baseRgb = AtlasMath.mixRgb(tintCache.sampleGrassTint(level, subPos), baseRgb, 0.16f);
                } else if (AtlasBlockClassifier.isCanopyBlock(below)
                        && AtlasBlockClassifier.isBiomeFoliageTinted(below)) {
                    baseRgb = AtlasMath.mixRgb(tintCache.sampleFoliageTint(level, subPos), baseRgb, 0.14f);
                }
                break;
            }
        }

        int r = Mth.clamp(Math.round(accR + ((baseRgb >> 16) & 0xFF) * transmittance), 0, 255);
        int g = Mth.clamp(Math.round(accG + ((baseRgb >> 8) & 0xFF) * transmittance), 0, 255);
        int b = Mth.clamp(Math.round(accB + (baseRgb & 0xFF) * transmittance), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    static int findCeilingFloorY(
            Level level,
            LevelChunk chunk,
            BlockPos.MutableBlockPos pos,
            int wx,
            int wz,
            int playerY,
            int minY,
            int maxY
    ) {
        int bandY = Mth.clamp(((playerY - minY) / 24) * 24 + minY + 12, minY + 8, maxY - 4);
        int a0 = Math.min(maxY - 6, 108);
        int a1 = Math.min(maxY - 6, 72);
        int a2 = Math.min(maxY - 6, 44);
        for (int ai = 0; ai < 4; ai++) {
            int startY = ai == 0 ? a0 : ai == 1 ? a1 : ai == 2 ? a2 : bandY;
            int y = Mth.clamp(startY, minY + 4, maxY - 2);
            boolean foundOpenSpace = false;
            while (y > minY + 1) {
                pos.set(wx, y, wz);
                BlockState bs = chunk.getBlockState(pos);
                if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                    foundOpenSpace = true;
                    break;
                }
                y--;
            }
            if (foundOpenSpace) {
                while (y > minY) {
                    pos.set(wx, y, wz);
                    BlockState bs = chunk.getBlockState(pos);
                    if (!bs.getFluidState().isEmpty()
                            || bs.getMapColor(level, pos) != MapColor.NONE
                            || AtlasBlockClassifier.isStructureOrRoad(bs)) {
                        return y;
                    }
                    y--;
                }
            }
        }
        return Mth.clamp(bandY, minY + 1, maxY - 1);
    }

    static boolean isNetherPortalTopFrame(
            LevelChunk chunk,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int surfaceY,
            int wz,
            int minY
    ) {
        if (!chunk.getBlockState(subPos.set(wx, surfaceY, wz)).is(Blocks.OBSIDIAN)) {
            return false;
        }
        int minCheck = Math.max(minY, surfaceY - 4);
        for (int y = surfaceY - 1; y >= minCheck; y--) {
            subPos.set(wx, y, wz);
            if (chunk.getBlockState(subPos).is(Blocks.NETHER_PORTAL)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasWaterUnderBridge(
            LevelChunk chunk,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int surfaceY,
            int wz,
            int minY
    ) {
        if (surfaceY <= minY + 1) {
            return false;
        }
        subPos.set(wx, surfaceY - 1, wz);
        BlockState direct = chunk.getBlockState(subPos);
        if (!direct.isAir() && !direct.getFluidState().is(FluidTags.WATER)) {
            return false;
        }
        int minCheck = Math.max(minY, surfaceY - 6);
        for (int y = surfaceY - 1; y >= minCheck; y--) {
            subPos.set(wx, y, wz);
            BlockState bs = chunk.getBlockState(subPos);
            if (bs.getFluidState().is(FluidTags.WATER)) {
                return true;
            }
            if (!bs.isAir() && !bs.is(BlockTags.FENCES) && !bs.is(BlockTags.LOGS)) {
                return false;
            }
        }
        return false;
    }

    static boolean isCaveEntranceOverhang(
            LevelChunk chunk,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int surfaceY,
            int wz,
            int maxY
    ) {
        if (surfaceY + 3 > maxY) {
            return false;
        }
        if (!chunk.getBlockState(subPos.set(wx, surfaceY + 1, wz)).isAir()
                || !chunk.getBlockState(subPos.set(wx, surfaceY + 2, wz)).isAir()) {
            return false;
        }
        int maxCheck = Math.min(maxY, surfaceY + 12);
        for (int y = surfaceY + 3; y <= maxCheck; y++) {
            subPos.set(wx, y, wz);
            BlockState bs = chunk.getBlockState(subPos);
            if (bs.isAir()
                    || !bs.getFluidState().isEmpty()
                    || AtlasBlockClassifier.isCanopyBlock(bs)
                    || AtlasBlockClassifier.isGroundFloraToSkip(bs)) {
                continue;
            }
            return AtlasBlockClassifier.isNaturalStone(bs)
                    || bs.is(BlockTags.DIRT)
                    || bs.is(BlockTags.SAND)
                    || bs.is(Blocks.SANDSTONE);
        }
        return false;
    }
}
