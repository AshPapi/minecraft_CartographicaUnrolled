package com.cartographica_unrolled.world;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.data.BlockStateCodecUtil;
import com.cartographica_unrolled.util.AtlasMath;
import it.unimi.dsi.fastutil.objects.Reference2ShortOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AtlasTerrainSampler {
    private static final class SampleScratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos subPos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos auxPos = new BlockPos.MutableBlockPos();
        final BlockState[] indoorOut = new BlockState[2];
        final BiomeTintCache tintCache = new BiomeTintCache();
        final List<BlockState> paletteList = new ArrayList<>(32);
        final Reference2ShortOpenHashMap<BlockState> paletteMap = new Reference2ShortOpenHashMap<>(64);
        final long[] columns = new long[AtlasChunkTerrain.COLUMN_COUNT];
        final short[] baseIndices = new short[AtlasChunkTerrain.COLUMN_COUNT];
        final short[] overlayIndices = new short[AtlasChunkTerrain.COLUMN_COUNT];

        SampleScratch() {
            paletteMap.defaultReturnValue((short) -1);
        }
    }

    private static final ThreadLocal<SampleScratch> SAMPLE_SCRATCH = ThreadLocal.withInitial(SampleScratch::new);

    private AtlasTerrainSampler() {
    }

    public static @Nullable AtlasChunkTerrain sampleChunk(
            Level level,
            LevelChunk chunk,
            int playerY,
            boolean indoorMode,
            boolean @Nullable [] sliceMask,
            @Nullable AtlasChunkTerrain existing
    ) {
        if (chunk.isEmpty() || (level.isClientSide()
                ? !level.getLightEngine().lightOnInColumn(SectionPos.asLong(chunk.getPos().x(), 0, chunk.getPos().z()))
                : !chunk.isLightCorrect())) {
            return null;
        }

        SampleScratch scratch = SAMPLE_SCRATCH.get();
        BiomeTintCache tintCache = scratch.tintCache;
        tintCache.prepareForChunk(level, chunk);

        List<BlockState> paletteList = scratch.paletteList;
        Reference2ShortOpenHashMap<BlockState> paletteMap = scratch.paletteMap;
        paletteList.clear();
        paletteMap.clear();
        BlockState[] indoorOut = scratch.indoorOut;
        BlockPos.MutableBlockPos pos = scratch.pos;
        BlockPos.MutableBlockPos subPos = scratch.subPos;
        BlockPos.MutableBlockPos auxPos = scratch.auxPos;
        long[] columns = scratch.columns;
        short[] baseIndices = scratch.baseIndices;
        short[] overlayIndices = scratch.overlayIndices;
        Arrays.fill(baseIndices, (short) 0);
        Arrays.fill(overlayIndices, (short) 0);

        try {
            BlockState airState = Blocks.AIR.defaultBlockState();
            paletteList.add(airState);
            paletteMap.put(airState, (short) 0);
            boolean hasAnyOverlay = false;
            boolean hasCeiling = level.dimensionType().hasCeiling();
            int minY = level.getMinY();
            int maxY = minY + level.getHeight() - 1;
            int baseWx = chunk.getPos().x() << 4;
            int baseWz = chunk.getPos().z() << 4;

            for (int bz = 0; bz < 16; bz++) {
                int wz = baseWz + bz;
                for (int bx = 0; bx < 16; bx++) {
                    int wx = baseWx + bx;
                    int colIdx = (bz << 4) | bx;
                    int y;
                    if (hasCeiling) {
                        y = ColumnDepthScanner.findCeilingFloorY(level, chunk, pos, wx, wz, playerY, minY, maxY);
                    } else {
                        y = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) + 1);
                        while (y > minY) {
                            pos.set(wx, y, wz);
                            BlockState bs = chunk.getBlockState(pos);
                            if (bs.getMapColor(level, pos) != MapColor.NONE
                                    || AtlasBlockClassifier.isStructureOrRoad(bs)
                                    || AtlasBlockClassifier.isGroundFloraToSkip(bs)
                                    || AtlasBlockClassifier.isOutdoorSpecialProp(bs)) {
                                break;
                            }
                            y--;
                        }
                        if (indoorMode && (sliceMask == null || sliceMask[colIdx])) {
                            indoorOut[0] = airState;
                            indoorOut[1] = airState;
                            long indoorPacked = IndoorRoomScanner.sampleIndoorSlicedColumn(
                                    level, chunk, tintCache, pos, subPos, wx, wz, bx, bz, y, playerY, minY, sliceMask != null, indoorOut
                            );
                            if (indoorPacked != AtlasChunkTerrain.EMPTY_COLUMN) {
                                columns[colIdx] = indoorPacked;
                                baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, indoorOut[0]);
                                short ovIdx = internPaletteState(paletteList, paletteMap, indoorOut[1]);
                                overlayIndices[colIdx] = ovIdx;
                                if (ovIdx != 0) {
                                    hasAnyOverlay = true;
                                }
                                continue;
                            }
                        }
                    }

                    pos.set(wx, y, wz);
                    BlockState topState = chunk.getBlockState(pos);
                    BlockState overlayBlock = airState;
                    BlockState baseBlockOverride = null;
                    int detailType = AtlasChunkTerrain.DETAIL_NONE;
                    int detailAccent = AtlasChunkTerrain.ACCENT_NONE;
                    boolean hadLilyPad = false;
                    boolean skippedFlora = AtlasBlockClassifier.isGroundFloraToSkip(topState);
                    if (skippedFlora) {
                        overlayBlock = topState;
                        if (topState.is(Blocks.LILY_PAD) || topState.is(Blocks.FROGSPAWN)) {
                            hadLilyPad = true;
                        } else {
                            int floraCode = AtlasBlockClassifier.classifyGroundFlora(topState);
                            detailType = (floraCode >>> 8) & 0x3F;
                            detailAccent = floraCode & 0x3F;
                        }
                        boolean foundGround = false;
                        int searchY = y;
                        while (searchY > minY) {
                            subPos.set(wx, --searchY, wz);
                            BlockState below = chunk.getBlockState(subPos);
                            if (AtlasBlockClassifier.isGroundFloraToSkip(below)) {
                                if (below.is(Blocks.LILY_PAD) || below.is(Blocks.FROGSPAWN)) {
                                    hadLilyPad = true;
                                    overlayBlock = below;
                                } else if (detailType == AtlasChunkTerrain.DETAIL_NONE
                                        || detailType == AtlasChunkTerrain.DETAIL_FERN_TALLGRASS) {
                                    int subCode = AtlasBlockClassifier.classifyGroundFlora(below);
                                    int subType = (subCode >>> 8) & 0x3F;
                                    if (subType != AtlasChunkTerrain.DETAIL_NONE) {
                                        detailType = subType;
                                        detailAccent = subCode & 0x3F;
                                        overlayBlock = below;
                                    }
                                }
                                continue;
                            }
                            if (!below.getFluidState().isEmpty()
                                    || below.getMapColor(level, subPos) != MapColor.NONE
                                    || AtlasBlockClassifier.isStructureOrRoad(below)
                                    || AtlasBlockClassifier.isOutdoorSpecialProp(below)) {
                                y = searchY;
                                pos.set(wx, y, wz);
                                topState = below;
                                foundGround = true;
                                break;
                            }
                        }
                        if (!foundGround) {
                            columns[colIdx] = AtlasChunkTerrain.EMPTY_COLUMN;
                            continue;
                        }
                    }

                    FluidState topFluid = topState.getFluidState();
                    if (topFluid.isEmpty() && topState.getMapColor(level, pos) == MapColor.NONE
                            && !AtlasBlockClassifier.isStructureOrRoad(topState)
                            && !AtlasBlockClassifier.isOutdoorSpecialProp(topState)) {
                        columns[colIdx] = AtlasChunkTerrain.EMPTY_COLUMN;
                        continue;
                    }

                    int surfaceY = y;
                    int flags = 0;
                    int canopyDrop = 0;
                    int floorHint = 0;

                    if (AtlasBlockClassifier.isStructureOrRoad(topState) && !topFluid.isEmpty()) {
                        flags |= AtlasChunkTerrain.FLAG_STRUCTURE;
                        auxPos.set(wx, y + 1, wz);
                        int topEmission = topState.getLightEmission(level, pos);
                        int blockLight = Math.max(
                                topEmission,
                                Math.max(level.getBrightness(LightLayer.BLOCK, auxPos),
                                        level.getBrightness(LightLayer.BLOCK, pos))
                        );
                        MapColor mapColor = topState.getMapColor(level, pos);
                        int rgb = (mapColor != MapColor.NONE && mapColor.col != 0) ? mapColor.col : 0x786048;
                        if (!hasCeiling) {
                            floorHint = Mth.clamp(15 - level.getBrightness(LightLayer.SKY, auxPos), 0, 15);
                        }
                        if (topEmission >= 10) {
                            flags |= AtlasChunkTerrain.FLAG_GLOW;
                        }
                        columns[colIdx] = AtlasChunkTerrain.pack(rgb, surfaceY, 0, flags, 0, floorHint, blockLight);
                        baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, topState);
                        short ovIdx = internPaletteState(paletteList, paletteMap, overlayBlock);
                        overlayIndices[colIdx] = ovIdx;
                        if (ovIdx != 0) {
                            hasAnyOverlay = true;
                        }
                        continue;
                    }

                    if (topState.is(Blocks.ICE) || topState.is(Blocks.FROSTED_ICE)) {
                        columns[colIdx] = ColumnDepthScanner.sampleIceColumn(level, chunk, pos, subPos, auxPos, wx, wz, surfaceY, minY);
                        baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, topState);
                        short ovIdx = internPaletteState(paletteList, paletteMap, overlayBlock);
                        overlayIndices[colIdx] = ovIdx;
                        if (ovIdx != 0) {
                            hasAnyOverlay = true;
                        }
                        continue;
                    }

                    if (!topFluid.isEmpty()) {
                        if (topFluid.is(FluidTags.LAVA)) {
                            flags |= AtlasChunkTerrain.FLAG_LAVA | AtlasChunkTerrain.FLAG_GLOW;
                            int rgb = AtlasMath.mixRgb(0xD95B16, topState.getMapColor(level, pos).col, 0.35f);
                            columns[colIdx] = AtlasChunkTerrain.pack(rgb, surfaceY, 0, flags, 0, 0, 15);
                            baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, Blocks.LAVA.defaultBlockState());
                            continue;
                        }

                        columns[colIdx] = ColumnDepthScanner.sampleWaterColumn(
                                level, chunk, tintCache, pos, subPos, auxPos, topState, wx, wz, surfaceY, minY, hadLilyPad
                        );
                        baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, Blocks.WATER.defaultBlockState());
                        if (hadLilyPad) {
                            short ovIdx = internPaletteState(paletteList, paletteMap, overlayBlock);
                            overlayIndices[colIdx] = ovIdx;
                            if (ovIdx != 0) {
                                hasAnyOverlay = true;
                            }
                        }
                        continue;
                    }

                    int forcedBlockLight = 0;

                    if (AtlasBlockClassifier.isCropOrFarmland(topState)) {
                        flags |= AtlasChunkTerrain.FLAG_CROP_FLORA;
                        int cropCode = AtlasBlockClassifier.classifyCropStage(topState);
                        if (cropCode != AtlasChunkTerrain.DETAIL_NONE) {
                            detailType = (cropCode >>> 8) & 0x3F;
                            detailAccent = cropCode & 0x3F;
                        }
                        if (!topState.is(Blocks.FARMLAND) && surfaceY > minY) {
                            subPos.set(wx, surfaceY - 1, wz);
                            BlockState belowCrop = chunk.getBlockState(subPos);
                            if (belowCrop.getMapColor(level, subPos) != MapColor.NONE) {
                                overlayBlock = topState;
                                baseBlockOverride = belowCrop;
                                surfaceY--;
                            }
                        }
                    }

                    if (topState.is(Blocks.NETHER_PORTAL)
                            || ColumnDepthScanner.isNetherPortalTopFrame(chunk, subPos, wx, surfaceY, wz, minY)) {
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_NETHER_PORTAL;
                        detailAccent = AtlasChunkTerrain.ACCENT_SPORE_MAGENTA;
                        flags |= AtlasChunkTerrain.FLAG_STRUCTURE | AtlasChunkTerrain.FLAG_GLOW;
                        forcedBlockLight = 14;
                    } else if (topState.is(Blocks.CAMPFIRE) || topState.is(Blocks.SOUL_CAMPFIRE)) {
                        boolean lit = topState.hasProperty(BlockStateProperties.LIT) && topState.getValue(BlockStateProperties.LIT);
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_CAMPFIRE;
                        detailAccent = !lit
                                ? AtlasChunkTerrain.ACCENT_BROWN_OCHRE
                                : (topState.is(Blocks.SOUL_CAMPFIRE)
                                        ? AtlasChunkTerrain.ACCENT_ORCHID_CYAN
                                        : AtlasChunkTerrain.ACCENT_FIREFLY_AMBER);
                        if (lit) {
                            flags |= AtlasChunkTerrain.FLAG_GLOW;
                            forcedBlockLight = 14;
                        }
                        if (surfaceY > minY) {
                            subPos.set(wx, surfaceY - 1, wz);
                            BlockState belowCamp = chunk.getBlockState(subPos);
                            if (belowCamp.getMapColor(level, subPos) != MapColor.NONE && belowCamp.getFluidState().isEmpty()) {
                                overlayBlock = topState;
                                surfaceY--;
                                topState = belowCamp;
                                pos.set(wx, surfaceY, wz);
                            }
                        }
                    } else if (AtlasBlockClassifier.isOutdoorLanternOrTorch(topState)) {
                        boolean soul = topState.is(Blocks.SOUL_LANTERN) || topState.is(Blocks.SOUL_TORCH) || topState.is(Blocks.SOUL_WALL_TORCH);
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_LANTERN_POST;
                        if ((topState.is(Blocks.WALL_TORCH) || topState.is(Blocks.SOUL_WALL_TORCH))
                                && topState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                            Direction wallSide = topState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
                            int baseAccent = soul ? AtlasChunkTerrain.ACCENT_SOUL_WALL_TORCH_N : AtlasChunkTerrain.ACCENT_WALL_TORCH_N;
                            detailAccent = switch (wallSide) {
                                case NORTH -> baseAccent;
                                case SOUTH -> baseAccent + 1;
                                case WEST -> baseAccent + 2;
                                case EAST -> baseAccent + 3;
                                default -> soul ? AtlasChunkTerrain.ACCENT_ORCHID_CYAN : AtlasChunkTerrain.ACCENT_FIREFLY_AMBER;
                            };
                        } else {
                            detailAccent = soul ? AtlasChunkTerrain.ACCENT_ORCHID_CYAN : AtlasChunkTerrain.ACCENT_FIREFLY_AMBER;
                        }
                        flags |= AtlasChunkTerrain.FLAG_GLOW;
                        forcedBlockLight = 14;
                        BlockState lightState = topState;
                        int fy = surfaceY - 1;
                        int steps = 0;
                        while (fy > minY && steps < 4) {
                            subPos.set(wx, fy, wz);
                            BlockState belowLight = chunk.getBlockState(subPos);
                            if (belowLight.isAir() || belowLight.is(BlockTags.FENCES) || belowLight.is(BlockTags.WALLS)
                                    || belowLight.is(Blocks.IRON_CHAIN) || belowLight.is(Blocks.IRON_BARS)) {
                                fy--;
                                steps++;
                                continue;
                            }
                            if (belowLight.getMapColor(level, subPos) != MapColor.NONE && belowLight.getFluidState().isEmpty()) {
                                overlayBlock = lightState;
                                surfaceY = fy;
                                topState = belowLight;
                                pos.set(wx, surfaceY, wz);
                            }
                            break;
                        }
                    } else if (topState.is(Blocks.BELL) || topState.is(Blocks.CAULDRON)
                            || topState.is(Blocks.WATER_CAULDRON) || topState.is(Blocks.LAVA_CAULDRON)
                            || topState.is(Blocks.POWDER_SNOW_CAULDRON)) {
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_WELL_BELL;
                        if (topState.is(Blocks.BELL)) {
                            detailAccent = AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
                        } else if (topState.is(Blocks.WATER_CAULDRON)) {
                            detailAccent = AtlasChunkTerrain.ACCENT_CORNFLOWER;
                        } else if (topState.is(Blocks.LAVA_CAULDRON)) {
                            detailAccent = AtlasChunkTerrain.ACCENT_ORANGE;
                            flags |= AtlasChunkTerrain.FLAG_GLOW;
                            forcedBlockLight = 13;
                        } else if (topState.is(Blocks.POWDER_SNOW_CAULDRON)) {
                            detailAccent = AtlasChunkTerrain.ACCENT_WHITE_CREAM;
                        } else {
                            detailAccent = AtlasChunkTerrain.ACCENT_STONE_GRAY;
                        }
                        if (surfaceY > minY) {
                            subPos.set(wx, surfaceY - 1, wz);
                            BlockState belowBell = chunk.getBlockState(subPos);
                            if (belowBell.getMapColor(level, subPos) != MapColor.NONE && belowBell.getFluidState().isEmpty()) {
                                overlayBlock = topState;
                                surfaceY--;
                                topState = belowBell;
                                pos.set(wx, surfaceY, wz);
                            }
                        }
                    } else if (topState.is(Blocks.HAY_BLOCK)) {
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_HAY_BALE;
                        detailAccent = AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
                        flags |= AtlasChunkTerrain.FLAG_STRUCTURE;
                    } else if (topState.is(BlockTags.BANNERS)) {
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_BANNER;
                        detailAccent = AtlasBlockClassifier.classifyBannerAccent(topState);
                        if (surfaceY > minY) {
                            subPos.set(wx, surfaceY - 1, wz);
                            BlockState belowBanner = chunk.getBlockState(subPos);
                            if (belowBanner.getMapColor(level, subPos) != MapColor.NONE && belowBanner.getFluidState().isEmpty()) {
                                overlayBlock = topState;
                                surfaceY--;
                                topState = belowBanner;
                                pos.set(wx, surfaceY, wz);
                            }
                        }
                    } else if (topState.is(Blocks.BAMBOO) || topState.is(Blocks.CHORUS_PLANT)
                            || topState.is(Blocks.CHORUS_FLOWER) || topState.is(Blocks.CACTUS)) {
                        boolean isBamboo = topState.is(Blocks.BAMBOO);
                        boolean isCactus = topState.is(Blocks.CACTUS);
                        BlockState tallState = topState;
                        int fy = surfaceY - 1;
                        while (fy > minY) {
                            subPos.set(wx, fy, wz);
                            BlockState below = chunk.getBlockState(subPos);
                            if (!below.is(Blocks.BAMBOO) && !below.is(Blocks.CHORUS_PLANT)
                                    && !below.is(Blocks.CHORUS_FLOWER) && !below.is(Blocks.CACTUS)) {
                                surfaceY = fy;
                                if ((isBamboo || isCactus) && below.getMapColor(level, subPos) != MapColor.NONE) {
                                    overlayBlock = tallState;
                                    topState = below;
                                    pos.set(wx, surfaceY, wz);
                                    detailType = isBamboo ? AtlasChunkTerrain.DETAIL_REEDS_BAMBOO : AtlasChunkTerrain.DETAIL_ARID_FLORA;
                                    detailAccent = AtlasChunkTerrain.ACCENT_MELON_GREEN;
                                }
                                break;
                            }
                            fy--;
                        }
                    } else if ((topState.is(Blocks.PUMPKIN) || topState.is(Blocks.CARVED_PUMPKIN)
                            || topState.is(Blocks.JACK_O_LANTERN) || topState.is(Blocks.MELON)) && surfaceY > minY) {
                        subPos.set(wx, surfaceY - 1, wz);
                        BlockState belowGourd = chunk.getBlockState(subPos);
                        if (belowGourd.getMapColor(level, subPos) != MapColor.NONE
                                && (!AtlasBlockClassifier.isStructureOrRoad(belowGourd)
                                        || belowGourd.is(Blocks.FARMLAND)
                                        || belowGourd.is(Blocks.DIRT_PATH))) {
                            if (topState.is(Blocks.JACK_O_LANTERN)) {
                                flags |= AtlasChunkTerrain.FLAG_GLOW;
                            }
                            overlayBlock = topState;
                            detailType = AtlasChunkTerrain.DETAIL_PUMPKIN_MELON;
                            detailAccent = topState.is(Blocks.MELON)
                                    ? AtlasChunkTerrain.ACCENT_MELON_GREEN
                                    : AtlasChunkTerrain.ACCENT_ORANGE;
                            surfaceY--;
                            topState = belowGourd;
                            pos.set(wx, surfaceY, wz);
                        }
                    } else if ((topState.is(BlockTags.FENCES) || topState.is(BlockTags.FENCE_GATES) || topState.is(BlockTags.WALLS))
                            && surfaceY > minY) {
                        subPos.set(wx, surfaceY - 1, wz);
                        BlockState belowFence = chunk.getBlockState(subPos);
                        if (belowFence.getMapColor(level, subPos) != MapColor.NONE
                                && !belowFence.is(BlockTags.FENCES)
                                && !belowFence.is(BlockTags.FENCE_GATES)
                                && !belowFence.is(BlockTags.WALLS)
                                && (!AtlasBlockClassifier.isStructureOrRoad(belowFence)
                                        || belowFence.is(Blocks.DIRT_PATH)
                                        || belowFence.is(Blocks.FARMLAND))) {
                            overlayBlock = topState;
                            detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_FENCE_WALL;
                            detailAccent = topState.is(BlockTags.WALLS)
                                    ? AtlasChunkTerrain.ACCENT_STONE_GRAY
                                    : AtlasChunkTerrain.ACCENT_WOOD_OAK;
                            surfaceY--;
                            topState = belowFence;
                            pos.set(wx, surfaceY, wz);
                        }
                    } else if (topState.is(BlockTags.RAILS) && surfaceY > minY) {
                        subPos.set(wx, surfaceY - 1, wz);
                        BlockState belowRail = chunk.getBlockState(subPos);
                        if (belowRail.getMapColor(level, subPos) != MapColor.NONE) {
                            overlayBlock = topState;
                            detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_RAIL;
                            detailAccent = AtlasChunkTerrain.ACCENT_WOOD_OAK;
                            surfaceY--;
                            topState = belowRail;
                            pos.set(wx, surfaceY, wz);
                        }
                    }

                    boolean snowDust = false;
                    if (topState.is(Blocks.SNOW) && surfaceY > minY + 1) {
                        subPos.set(wx, surfaceY - 1, wz);
                        BlockState belowSnow = chunk.getBlockState(subPos);
                        if (AtlasBlockClassifier.isStructureOrRoad(belowSnow)
                                || belowSnow.is(Blocks.NETHERRACK)
                                || belowSnow.is(Blocks.MAGMA_BLOCK)) {
                            topState = belowSnow;
                            surfaceY--;
                            pos.set(wx, surfaceY, wz);
                            snowDust = true;
                        } else if (AtlasBlockClassifier.isCanopyBlock(belowSnow)) {
                            topState = belowSnow;
                            surfaceY--;
                            pos.set(wx, surfaceY, wz);
                            flags |= AtlasChunkTerrain.FLAG_SNOW_ICE;
                        } else {
                            flags |= AtlasChunkTerrain.FLAG_SNOW_ICE;
                            if (belowSnow.getMapColor(level, subPos) != MapColor.NONE) {
                                surfaceY--;
                            }
                        }
                    }

                    pos.set(wx, surfaceY, wz);
                    auxPos.set(wx, surfaceY + 1, wz);
                    int topEmission = topState.getLightEmission(level, pos);
                    int blockLight = Math.max(
                            forcedBlockLight,
                            Math.max(topEmission, level.getBrightness(LightLayer.BLOCK, auxPos))
                    );
                    MapColor mapColor = topState.getMapColor(level, pos);
                    int rgb = mapColor.col;
                    if (topState.is(Blocks.PACKED_ICE)) {
                        rgb = 0xC6D4D0;
                    } else if (topState.is(Blocks.BLUE_ICE)) {
                        rgb = 0xACC2C4;
                    }

                    if (!AtlasBlockClassifier.isCanopyBlock(topState)
                            && AtlasBlockClassifier.isBiomeGrassTinted(topState, mapColor)) {
                        rgb = AtlasMath.mixRgb(tintCache.sampleGrassTint(level, pos), 0x7A9E48, 0.12f);
                    } else if (AtlasBlockClassifier.isCanopyBlock(topState)) {
                        flags |= AtlasChunkTerrain.FLAG_CANOPY;
                        int groundY = ColumnDepthScanner.findGroundUnderCanopy(
                                level, chunk, subPos, wx, wz, bx, bz, surfaceY, minY, hasCeiling
                        );
                        canopyDrop = Mth.clamp(surfaceY - groundY, 1, 63);
                        subPos.set(wx, Math.min(maxY, groundY + 1), wz);
                        blockLight = Math.max(blockLight, level.getBrightness(LightLayer.BLOCK, subPos) - 3);
                        detailAccent = AtlasChunkTerrain.CANOPY_NORMAL;
                        if (topState.is(Blocks.CHERRY_LEAVES)) {
                            rgb = 0xE69CB8;
                            detailAccent = AtlasChunkTerrain.CANOPY_CHERRY;
                        } else if (topState.is(Blocks.FLOWERING_AZALEA_LEAVES)) {
                            rgb = 0x5E8C3A;
                            detailAccent = AtlasChunkTerrain.CANOPY_FLOWERING_AZALEA;
                        } else if (topState.is(Blocks.AZALEA_LEAVES)) {
                            rgb = 0x5E8C3A;
                        } else if (topState.is(Blocks.BIRCH_LEAVES)) {
                            rgb = 0x80A755;
                            detailAccent = AtlasChunkTerrain.CANOPY_BIRCH;
                        } else if (topState.is(Blocks.SPRUCE_LEAVES)) {
                            rgb = 0x5A8C5A;
                            detailAccent = AtlasChunkTerrain.CANOPY_SPRUCE_PINE;
                        } else if (topState.is(Blocks.PALE_OAK_LEAVES)) {
                            rgb = 0x8E948A;
                            detailAccent = AtlasChunkTerrain.CANOPY_PALE;
                        } else if (topState.is(Blocks.RED_MUSHROOM_BLOCK)) {
                            rgb = 0xC4362C;
                            detailAccent = AtlasChunkTerrain.CANOPY_MUSHROOM_RED;
                        } else if (topState.is(Blocks.BROWN_MUSHROOM_BLOCK)) {
                            rgb = 0x977151;
                            detailAccent = AtlasChunkTerrain.CANOPY_MUSHROOM_BROWN;
                        } else if (AtlasBlockClassifier.isBiomeFoliageTinted(topState)) {
                            rgb = AtlasMath.mixRgb(tintCache.sampleFoliageTint(level, pos), 0x4E7A36, 0.10f);
                            if (topState.is(Blocks.DARK_OAK_LEAVES) || topState.is(Blocks.MANGROVE_LEAVES)
                                    || topState.is(Blocks.MANGROVE_ROOTS)) {
                                detailAccent = AtlasChunkTerrain.CANOPY_DARK_MANGROVE;
                            }
                        }
                    }

                    if (AtlasBlockClassifier.isTranslucentBlock(topState)) {
                        rgb = ColumnDepthScanner.compositeUnderTranslucent(
                                level, chunk, tintCache, subPos, wx, wz, surfaceY, minY, topState, rgb
                        );
                        if (!topState.is(BlockTags.ICE) && overlayBlock.isAir()) {
                            for (int fy = surfaceY - 1; fy >= Math.max(minY, surfaceY - 8); fy--) {
                                subPos.set(wx, fy, wz);
                                BlockState under = chunk.getBlockState(subPos);
                                if (under.isAir() || AtlasBlockClassifier.isTranslucentBlock(under)) {
                                    continue;
                                }
                                if (!under.getFluidState().isEmpty() || under.getMapColor(level, subPos) != MapColor.NONE) {
                                    overlayBlock = topState;
                                    baseBlockOverride = under;
                                    break;
                                }
                            }
                        }
                    }

                    if (AtlasBlockClassifier.isSnowOrIce(topState, mapColor)) {
                        flags |= AtlasChunkTerrain.FLAG_SNOW_ICE;
                    }
                    if (skippedFlora && (topState.is(Blocks.GRASS_BLOCK) || topState.is(Blocks.DIRT))
                            && ColumnDepthScanner.isSnowyEnvironment(level, chunk, pos, subPos, bx, bz)) {
                        flags |= AtlasChunkTerrain.FLAG_SNOW_ICE;
                        rgb = 0xF0ECE0;
                    }
                    if (AtlasBlockClassifier.isStructureOrRoad(topState)
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_FENCE_WALL
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_RAIL
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_CAMPFIRE
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_LANTERN_POST
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_WELL_BELL
                            && detailType != AtlasChunkTerrain.DETAIL_OUTDOOR_BANNER) {
                        flags |= AtlasChunkTerrain.FLAG_STRUCTURE;
                        if ((detailType == AtlasChunkTerrain.DETAIL_NONE
                                || detailType == AtlasChunkTerrain.DETAIL_OUTDOOR_PATH_STEP)
                                && AtlasBlockClassifier.isBridgeDeckCandidate(topState)
                                && ColumnDepthScanner.hasWaterUnderBridge(chunk, subPos, wx, surfaceY, wz, minY)) {
                            detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_BRIDGE;
                            detailAccent = AtlasBlockClassifier.isWoodBridgeMaterial(topState)
                                    ? AtlasChunkTerrain.ACCENT_WOOD_OAK
                                    : AtlasChunkTerrain.ACCENT_STONE_GRAY;
                        } else if (detailType == AtlasChunkTerrain.DETAIL_NONE
                                && (topState.is(Blocks.DIRT_PATH) || topState.is(BlockTags.STAIRS))) {
                            detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_PATH_STEP;
                        }
                    }
                    if (AtlasBlockClassifier.isSandOrArid(topState, mapColor)) {
                        flags |= AtlasChunkTerrain.FLAG_SAND_ARID;
                    }
                    if (topEmission >= 10 || detailAccent == AtlasChunkTerrain.ACCENT_FIREFLY_AMBER) {
                        flags |= AtlasChunkTerrain.FLAG_GLOW;
                    }

                    if (!hasCeiling && (flags & AtlasChunkTerrain.FLAG_CANOPY) == 0) {
                        floorHint = Mth.clamp(15 - level.getBrightness(LightLayer.SKY, auxPos), 0, 15);
                    }
                    if (!hasCeiling
                            && detailType == AtlasChunkTerrain.DETAIL_NONE
                            && (flags & (AtlasChunkTerrain.FLAG_STRUCTURE | AtlasChunkTerrain.FLAG_CANOPY
                                    | AtlasChunkTerrain.FLAG_WATER | AtlasChunkTerrain.FLAG_LAVA)) == 0
                            && floorHint >= 7
                            && AtlasBlockClassifier.isNaturalTerrain(topState)
                            && ColumnDepthScanner.isCaveEntranceOverhang(chunk, subPos, wx, surfaceY, wz, maxY)) {
                        detailType = AtlasChunkTerrain.DETAIL_OUTDOOR_CAVE_ENTRANCE;
                        detailAccent = AtlasChunkTerrain.ACCENT_DARK_WITHER;
                    }
                    if ((flags & AtlasChunkTerrain.FLAG_STRUCTURE) == 0 && AtlasBlockClassifier.isNaturalStone(topState)) {
                        rgb = AtlasMath.mixRgb(rgb, 0x74726C, 0.75f);
                    } else if (floorHint >= 4 && topState.is(Blocks.DIRT)) {
                        rgb = AtlasMath.mixRgb(rgb, 0x685B4E, 0.45f);
                    }

                    if (topState.is(Blocks.OBSIDIAN)) {
                        rgb = 0x2B1E38;
                    } else if (topState.is(Blocks.CRYING_OBSIDIAN)) {
                        rgb = 0x3B2452;
                    } else if (topState.is(Blocks.GOLD_BLOCK)) {
                        rgb = 0xF2C83C;
                    }
                    if (snowDust) {
                        rgb = AtlasMath.mixRgb(rgb, 0xEDE5D2, 0.18f);
                    }
                    if (rgb == 0 && ((flags & AtlasChunkTerrain.FLAG_STRUCTURE) != 0
                            || AtlasBlockClassifier.isStructureOrRoad(topState))) {
                        rgb = 0x786048;
                    } else if (rgb == 0 && (flags & AtlasChunkTerrain.FLAG_STRUCTURE) == 0
                            && topState.getMapColor(level, pos) != MapColor.NONE) {
                        rgb = (flags & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0 ? 0xEAE0C8 : 0x7D8C49;
                    }
                    int packedDropOrDetail = (flags & AtlasChunkTerrain.FLAG_CANOPY) != 0 ? canopyDrop : detailType;
                    columns[colIdx] = AtlasChunkTerrain.pack(
                            rgb, surfaceY, detailAccent, flags, packedDropOrDetail, floorHint, blockLight
                    );
                    BlockState finalBase = baseBlockOverride != null ? baseBlockOverride : topState;
                    baseIndices[colIdx] = internPaletteState(paletteList, paletteMap, finalBase);
                    short ovIdx = internPaletteState(paletteList, paletteMap, overlayBlock);
                    overlayIndices[colIdx] = ovIdx;
                    if (ovIdx != 0) {
                        hasAnyOverlay = true;
                    }
                }
            }

            if (existing != null && matchesExisting(existing, columns, paletteList, baseIndices, hasAnyOverlay, overlayIndices)) {
                return existing;
            }

            BlockState[] palette = paletteList.toArray(BlockState[]::new);
            return new AtlasChunkTerrain(
                    columns.clone(),
                    palette,
                    baseIndices.clone(),
                    hasAnyOverlay ? overlayIndices.clone() : null
            );
        } finally {
            tintCache.clearRefs();
            paletteList.clear();
            paletteMap.clear();
            indoorOut[0] = null;
            indoorOut[1] = null;
        }
    }

    private static boolean matchesExisting(
            AtlasChunkTerrain existing,
            long[] columns,
            List<BlockState> paletteList,
            short[] baseIndices,
            boolean hasAnyOverlay,
            short[] overlayIndices
    ) {
        if (!Arrays.equals(existing.columns(), columns)) {
            return false;
        }
        BlockState[] exPal = existing.blockPalette();
        short[] exBase = existing.baseBlockIndices();
        short[] exOver = existing.overlayBlockIndices();
        if (exPal == null || exBase == null || exPal.length != paletteList.size()) {
            return false;
        }
        for (int i = 0; i < exPal.length; i++) {
            if (exPal[i] != paletteList.get(i)) {
                return false;
            }
        }
        if (!Arrays.equals(exBase, baseIndices)) {
            return false;
        }
        if (!hasAnyOverlay) {
            return exOver == null;
        }
        return exOver != null && Arrays.equals(exOver, overlayIndices);
    }

    private static short internPaletteState(
            List<BlockState> list,
            Reference2ShortOpenHashMap<BlockState> map,
            BlockState state
    ) {
        BlockState canonical = BlockStateCodecUtil.canonicalize(state);
        if (canonical.isAir()) {
            return 0;
        }
        short existing = map.getShort(canonical);
        if (existing >= 0) {
            return existing;
        }
        if (list.size() >= 512) {
            return 0;
        }
        short idx = (short) list.size();
        list.add(canonical);
        map.put(canonical, idx);
        return idx;
    }
}
