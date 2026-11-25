package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

final class CartographyCornerField {
    static final int GRID_SIZE = 30;
    static final int GRID_MARGIN = 7;

    private static final float[] INV_DIST_POW_LUT = new float[4609];

    static {
        INV_DIST_POW_LUT[0] = Float.POSITIVE_INFINITY;
        for (int dSq = 1; dSq < INV_DIST_POW_LUT.length; dSq++) {
            INV_DIST_POW_LUT[dSq] = (float) Math.pow(dSq, -1.1);
        }
    }

    private static final ThreadLocal<CartographyCornerField> THREAD_LOCAL =
            ThreadLocal.withInitial(CartographyCornerField::new);

    final long[] grid = new long[GRID_SIZE * GRID_SIZE];
    final int[] groundHeights = new int[GRID_SIZE * GRID_SIZE];
    final int[] flags = new int[GRID_SIZE * GRID_SIZE];
    final float[] cornerSun = new float[17 * 17];
    final float[] cornerSteep = new float[17 * 17];
    final float[] cornerGroundH = new float[17 * 17];
    final float[] cornerSkyShade = new float[17 * 17];
    final float[] cornerShoreDist = new float[17 * 17];
    final float[] cornerWaterDepth = new float[17 * 17];
    final float[] cornerWaterFloor = new float[17 * 17];
    final float[] cornerWaterPresence = new float[17 * 17];
    final float[] cornerWaterR = new float[17 * 17];
    final float[] cornerWaterG = new float[17 * 17];
    final float[] cornerWaterB = new float[17 * 17];
    final float[] cornerLight = new float[17 * 17];
    final float[] cornerExploredDistance = new float[17 * 17];
    final float[] cornerFrontierNoise = new float[17 * 17];
    final float[] cornerCanopyR = new float[17 * 17];
    final float[] cornerCanopyG = new float[17 * 17];
    final float[] cornerCanopyB = new float[17 * 17];
    final float[] cornerCanopyW = new float[17 * 17];
    final float[] cornerCanopyRim = new float[17 * 17];
    final float[] cornerCanopySnow = new float[17 * 17];
    final float[] cornerCanopySun = new float[17 * 17];
    final float[] cornerCanopyValley = new float[17 * 17];
    final float[] cornerGroundR = new float[17 * 17];
    final float[] cornerGroundG = new float[17 * 17];
    final float[] cornerGroundB = new float[17 * 17];
    final float[] cornerGroundW = new float[17 * 17];

    private final float[] shoreRowMinDxSq = new float[GRID_SIZE * 17];
    private final boolean[] shoreRowHasWater = new boolean[GRID_SIZE * 17];
    private final AtlasChunkTerrain[] nearby = new AtlasChunkTerrain[25];

    BlockTopTextureCache.@Nullable BakedBlockTile[] paletteTiles = new BlockTopTextureCache.BakedBlockTile[256];
    int paletteLen;

    static CartographyCornerField get() {
        return THREAD_LOCAL.get();
    }

    boolean populate(
            int cx,
            int cz,
            AtlasChunkTerrain center,
            boolean needLight,
            AtlasCartographyPainter.ChunkLookup chunkLookup
    ) {
        resolvePaletteTiles(center.blockPalette());

        boolean anyUnexplored = false;
        boolean anyGridWater = false;
        try {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    AtlasChunkTerrain chunk = dx == 0 && dz == 0 ? center : chunkLookup.get(cx + dx, cz + dz);
                    this.nearby[(dz + 2) * 5 + dx + 2] = chunk;
                    anyUnexplored |= chunk == null;
                }
            }
            AtlasChunkTerrain n = this.nearby[7];
            AtlasChunkTerrain s = this.nearby[17];
            AtlasChunkTerrain w = this.nearby[11];
            AtlasChunkTerrain e = this.nearby[13];
            AtlasChunkTerrain nw = this.nearby[6];
            AtlasChunkTerrain ne = this.nearby[8];
            AtlasChunkTerrain sw = this.nearby[16];
            AtlasChunkTerrain se = this.nearby[18];

            for (int z = -GRID_MARGIN; z < 16 + GRID_MARGIN; z++) {
                for (int x = -GRID_MARGIN; x < 16 + GRID_MARGIN; x++) {
                    AtlasChunkTerrain source;
                    if (x >= 0 && x < 16 && z >= 0 && z < 16) {
                        source = center;
                    } else if (z < 0) {
                        source = x < 0 ? nw : x >= 16 ? ne : n;
                    } else if (z >= 16) {
                        source = x < 0 ? sw : x >= 16 ? se : s;
                    } else {
                        source = x < 0 ? w : e;
                    }
                    int index = (z + GRID_MARGIN) * GRID_SIZE + x + GRID_MARGIN;
                    long col = source != null ? source.getColumn(x & 15, z & 15) : AtlasChunkTerrain.EMPTY_COLUMN;
                    this.grid[index] = col;
                    if (col == AtlasChunkTerrain.EMPTY_COLUMN) {
                        this.flags[index] = 0;
                        this.groundHeights[index] = 0;
                    } else {
                        int colFlags = AtlasChunkTerrain.unpackFlags(col);
                        this.flags[index] = colFlags;
                        anyGridWater |= (colFlags & AtlasChunkTerrain.FLAG_WATER) != 0;
                        this.groundHeights[index] = AtlasChunkTerrain.unpackSurfaceY(col)
                                - ((colFlags & AtlasChunkTerrain.FLAG_CANOPY) != 0
                                        ? AtlasChunkTerrain.unpackCanopyDrop(col) : 0);
                    }
                }
            }

            if (anyGridWater) {
                computeAllShoreDistances();
            } else {
                Arrays.fill(this.cornerShoreDist, 0.0f);
            }

            for (int z = 0; z <= 16; z++) {
                for (int x = 0; x <= 16; x++) {
                    int p = (z + GRID_MARGIN) * GRID_SIZE + x + GRID_MARGIN;
                    int ci = z * 17 + x;
                    int minHeight = Integer.MAX_VALUE;
                    int maxHeight = Integer.MIN_VALUE;
                    int nonEmptyCount = 0;
                    int sumGround = 0;
                    float waterDepth = 0.0f;
                    float waterFloor = 0.0f;
                    int waterR = 0;
                    int waterG = 0;
                    int waterB = 0;
                    int waterCount = 0;
                    int skyShadeSum = 0;
                    int skyShadeCount = 0;
                    int canopyR = 0;
                    int canopyG = 0;
                    int canopyB = 0;
                    int canopyColorCount = 0;
                    int canopySnowCount = 0;
                    int canopyTopSum = 0;
                    int groundR = 0;
                    int groundG = 0;
                    int groundB = 0;
                    int groundColorCount = 0;

                    for (int dz = -1; dz <= 0; dz++) {
                        for (int dx = -1; dx <= 0; dx++) {
                            int sample = p + dz * GRID_SIZE + dx;
                            long col = this.grid[sample];
                            if (col == AtlasChunkTerrain.EMPTY_COLUMN) {
                                continue;
                            }
                            int sf = this.flags[sample];
                            int gh = this.groundHeights[sample];
                            nonEmptyCount++;
                            sumGround += gh;
                            minHeight = Math.min(minHeight, gh);
                            maxHeight = Math.max(maxHeight, gh);
                            if ((sf & AtlasChunkTerrain.FLAG_WATER) != 0) {
                                waterDepth += AtlasChunkTerrain.unpackWaterDepth(col);
                                waterFloor += AtlasChunkTerrain.unpackFloorHint(col);
                                int wRgb = AtlasChunkTerrain.unpackRgb(col);
                                waterR += (wRgb >> 16) & 255;
                                waterG += (wRgb >> 8) & 255;
                                waterB += wRgb & 255;
                                waterCount++;
                            } else if ((sf & AtlasChunkTerrain.FLAG_CANOPY) == 0 && (sf & AtlasChunkTerrain.FLAG_LAVA) == 0) {
                                skyShadeSum += AtlasChunkTerrain.unpackFloorHint(col);
                                skyShadeCount++;
                                if ((sf & AtlasChunkTerrain.FLAG_STRUCTURE) == 0) {
                                    int gp = AtlasCartographyPainter.computePigment(col);
                                    groundR += (gp >> 16) & 255;
                                    groundG += (gp >> 8) & 255;
                                    groundB += gp & 255;
                                    groundColorCount++;
                                }
                            }
                            if ((sf & AtlasChunkTerrain.FLAG_CANOPY) != 0) {
                                int cp = AtlasCartographyPainter.computePigment(col);
                                canopyR += (cp >> 16) & 255;
                                canopyG += (cp >> 8) & 255;
                                canopyB += cp & 255;
                                canopyColorCount++;
                                canopyTopSum += AtlasChunkTerrain.unpackSurfaceY(col);
                                if ((sf & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0) {
                                    canopySnowCount++;
                                }
                            }
                        }
                    }

                    float fallbackH = nonEmptyCount > 0 ? (float) sumGround / nonEmptyCount : 64.0f;
                    this.cornerGroundH[ci] = fallbackH;
                    this.cornerSkyShade[ci] = skyShadeCount > 0 ? (float) skyShadeSum / (skyShadeCount * 15.0f) : 0.0f;
                    this.cornerCanopyR[ci] = canopyR * 0.25f;
                    this.cornerCanopyG[ci] = canopyG * 0.25f;
                    this.cornerCanopyB[ci] = canopyB * 0.25f;
                    this.cornerCanopyW[ci] = canopyColorCount * 0.25f;
                    this.cornerCanopySnow[ci] = canopySnowCount * 0.25f;
                    if (canopyColorCount > 0) {
                        float avgCrownY = (float) canopyTopSum / canopyColorCount;
                        float localPeakY = avgCrownY;
                        for (int dz = -2; dz < 2; dz++) {
                            int row = p + dz * GRID_SIZE;
                            for (int dx = -2; dx < 2; dx++) {
                                int idx = row + dx;
                                long nbCol = this.grid[idx];
                                if (nbCol != AtlasChunkTerrain.EMPTY_COLUMN
                                        && (this.flags[idx] & AtlasChunkTerrain.FLAG_CANOPY) != 0) {
                                    int cy = AtlasChunkTerrain.unpackSurfaceY(nbCol);
                                    if (cy > localPeakY) {
                                        localPeakY = cy;
                                    }
                                }
                            }
                        }
                        float valley = Mth.clamp((localPeakY - avgCrownY - 0.85f) * 0.42f, 0.0f, 0.78f);
                        this.cornerCanopyValley[ci] = valley;
                        if (canopyColorCount <= 2) {
                            this.cornerCanopyRim[ci] = 0.0f;
                        } else if (canopyColorCount == 3) {
                            this.cornerCanopyRim[ci] = 0.44f;
                        } else {
                            this.cornerCanopyRim[ci] = 1.0f - valley * 0.85f;
                        }
                        float baseCrownY = avgCrownY - 3.2f;
                        float cWest = sampleCanopyDomeAvg(baseCrownY, p - GRID_SIZE - 2, p - 2, p - GRID_SIZE - 1, p - 1);
                        float cEast = sampleCanopyDomeAvg(baseCrownY, p - GRID_SIZE, p, p - GRID_SIZE + 1, p + 1);
                        float cNorth = sampleCanopyDomeAvg(baseCrownY, p - 2 * GRID_SIZE - 1, p - 2 * GRID_SIZE, p - GRID_SIZE - 1, p - GRID_SIZE);
                        float cSouth = sampleCanopyDomeAvg(baseCrownY, p - 1, p, p + GRID_SIZE - 1, p + GRID_SIZE);
                        this.cornerCanopySun[ci] = Mth.clamp(((cEast - cWest) + (cSouth - cNorth)) * 0.048f, -0.25f, 0.20f);
                    } else {
                        this.cornerCanopyRim[ci] = 0.0f;
                        this.cornerCanopySun[ci] = 0.0f;
                        this.cornerCanopyValley[ci] = 0.0f;
                    }
                    this.cornerGroundR[ci] = groundR * 0.25f;
                    this.cornerGroundG[ci] = groundG * 0.25f;
                    this.cornerGroundB[ci] = groundB * 0.25f;
                    this.cornerGroundW[ci] = groundColorCount * 0.25f;

                    if (nonEmptyCount < 2) {
                        this.cornerSun[ci] = 0.0f;
                        this.cornerSteep[ci] = 0.0f;
                    } else {
                        float west = sampleGroundAvg(fallbackH, p - GRID_SIZE - 2, p - 2, p - GRID_SIZE - 1, p - 1);
                        float east = sampleGroundAvg(fallbackH, p - GRID_SIZE, p, p - GRID_SIZE + 1, p + 1);
                        float north = sampleGroundAvg(fallbackH, p - 2 * GRID_SIZE - 1, p - 2 * GRID_SIZE, p - GRID_SIZE - 1, p - GRID_SIZE);
                        float south = sampleGroundAvg(fallbackH, p - 1, p, p + GRID_SIZE - 1, p + GRID_SIZE);
                        this.cornerSun[ci] = Mth.clamp(((east - west) + (south - north)) * 0.056f, -0.22f, 0.18f);
                        this.cornerSteep[ci] = maxHeight - minHeight >= 3
                                ? Mth.clamp((maxHeight - minHeight - 2) * 0.038f, 0.0f, 0.34f) : 0.0f;
                    }

                    this.cornerWaterDepth[ci] = waterDepth * 0.25f;
                    this.cornerWaterFloor[ci] = waterFloor * 0.25f;
                    this.cornerWaterPresence[ci] = waterCount * 0.25f;
                    this.cornerWaterR[ci] = waterR * 0.25f;
                    this.cornerWaterG[ci] = waterG * 0.25f;
                    this.cornerWaterB[ci] = waterB * 0.25f;

                    if (anyUnexplored) {
                        this.cornerExploredDistance[ci] = distanceToUnexplored(this.nearby, x, z);
                        double cwx = cx * 16.0 + x;
                        double cwz = cz * 16.0 + z;
                        this.cornerFrontierNoise[ci] = AtlasCartographyPainter.smoothNoise(cwx / 14.0, cwz / 14.0, 577) * 1.4f
                                + AtlasCartographyPainter.smoothNoise(cwx / 5.0, cwz / 5.0, 913) * 0.6f;
                    }
                    if (needLight) {
                        int lightCount = 0;
                        int lightSum = 0;
                        long l0 = this.grid[p - GRID_SIZE - 1];
                        if (l0 != AtlasChunkTerrain.EMPTY_COLUMN) {
                            lightSum += effectiveLight(l0);
                            lightCount++;
                        }
                        long l1 = this.grid[p - GRID_SIZE];
                        if (l1 != AtlasChunkTerrain.EMPTY_COLUMN) {
                            lightSum += effectiveLight(l1);
                            lightCount++;
                        }
                        long l2 = this.grid[p - 1];
                        if (l2 != AtlasChunkTerrain.EMPTY_COLUMN) {
                            lightSum += effectiveLight(l2);
                            lightCount++;
                        }
                        long l3 = this.grid[p];
                        if (l3 != AtlasChunkTerrain.EMPTY_COLUMN) {
                            lightSum += effectiveLight(l3);
                            lightCount++;
                        }
                        this.cornerLight[ci] = lightCount > 0 ? (float) lightSum / lightCount : 0.0f;
                    }
                }
            }
            return anyUnexplored;
        } finally {
            Arrays.fill(this.nearby, null);
        }
    }

    void clearPaletteRefs() {
        if (this.paletteLen > 0) {
            Arrays.fill(this.paletteTiles, 0, this.paletteLen, null);
            this.paletteLen = 0;
        }
    }

    private void resolvePaletteTiles(BlockState @Nullable [] palette) {
        if (palette == null || palette.length == 0) {
            this.paletteLen = 0;
            return;
        }
        int len = palette.length;
        if (this.paletteTiles.length < len) {
            this.paletteTiles = new BlockTopTextureCache.BakedBlockTile[Math.max(len, this.paletteTiles.length * 2)];
        }
        for (int i = 0; i < len; i++) {
            this.paletteTiles[i] = BlockTopTextureCache.getCached(palette[i]);
        }
        this.paletteLen = len;
    }

    private void computeAllShoreDistances() {
        for (int gz = 0; gz < GRID_SIZE; gz++) {
            int gRow = gz * GRID_SIZE;
            int outRow = gz * 17;
            for (int cx = 0; cx <= 16; cx++) {
                int baseX = cx + GRID_MARGIN;
                float minDxSq = 36.0f;
                boolean waterInSpan = false;
                for (int dx = -6; dx <= 5; dx++) {
                    int gx = baseX + dx;
                    if (gx < 0 || gx >= GRID_SIZE) {
                        continue;
                    }
                    int idx = gRow + gx;
                    if (this.grid[idx] == AtlasChunkTerrain.EMPTY_COLUMN) {
                        continue;
                    }
                    if ((this.flags[idx] & AtlasChunkTerrain.FLAG_WATER) != 0) {
                        waterInSpan = true;
                    } else {
                        float ox = dx + 0.5f;
                        float dxSq = ox * ox;
                        if (dxSq < minDxSq) {
                            minDxSq = dxSq;
                        }
                    }
                }
                this.shoreRowMinDxSq[outRow + cx] = minDxSq;
                this.shoreRowHasWater[outRow + cx] = waterInSpan;
            }
        }

        for (int cz = 0; cz <= 16; cz++) {
            int baseZ = cz + GRID_MARGIN;
            int cRow = cz * 17;
            for (int cx = 0; cx <= 16; cx++) {
                float minSq = 36.0f;
                boolean anyWater = false;
                for (int dz = -6; dz <= 5; dz++) {
                    int gz = baseZ + dz;
                    if (gz < 0 || gz >= GRID_SIZE) {
                        continue;
                    }
                    int rIdx = gz * 17 + cx;
                    if (this.shoreRowHasWater[rIdx]) {
                        anyWater = true;
                    }
                    float rowMin = this.shoreRowMinDxSq[rIdx];
                    if (rowMin < 36.0f) {
                        float oz = dz + 0.5f;
                        float dSq = rowMin + oz * oz;
                        if (dSq < minSq) {
                            minSq = dSq;
                        }
                    }
                }
                this.cornerShoreDist[cRow + cx] = anyWater ? (float) Math.sqrt(minSq) : 0.0f;
            }
        }
    }

    private float sampleGroundAvg(float fallback, int i0, int i1, int i2, int i3) {
        int sum = 0;
        int count = 0;
        if (this.grid[i0] != AtlasChunkTerrain.EMPTY_COLUMN) {
            sum += this.groundHeights[i0];
            count++;
        }
        if (this.grid[i1] != AtlasChunkTerrain.EMPTY_COLUMN) {
            sum += this.groundHeights[i1];
            count++;
        }
        if (this.grid[i2] != AtlasChunkTerrain.EMPTY_COLUMN) {
            sum += this.groundHeights[i2];
            count++;
        }
        if (this.grid[i3] != AtlasChunkTerrain.EMPTY_COLUMN) {
            sum += this.groundHeights[i3];
            count++;
        }
        return count > 0 ? (float) sum / count : fallback;
    }

    private float sampleCanopyDomeAvg(float baseCrownY, int i0, int i1, int i2, int i3) {
        return (sampleCanopyDomeCell(baseCrownY, i0)
                + sampleCanopyDomeCell(baseCrownY, i1)
                + sampleCanopyDomeCell(baseCrownY, i2)
                + sampleCanopyDomeCell(baseCrownY, i3)) * 0.25f;
    }

    private float sampleCanopyDomeCell(float baseCrownY, int idx) {
        long col = this.grid[idx];
        if (col != AtlasChunkTerrain.EMPTY_COLUMN && (this.flags[idx] & AtlasChunkTerrain.FLAG_CANOPY) != 0) {
            return AtlasChunkTerrain.unpackSurfaceY(col);
        }
        return baseCrownY;
    }

    private static int effectiveLight(long col) {
        int blockLight = AtlasChunkTerrain.unpackBlockLight(col);
        return (AtlasChunkTerrain.unpackFlags(col) & AtlasChunkTerrain.FLAG_GLOW) != 0
                ? Math.max(blockLight, 10)
                : blockLight;
    }

    private static float distanceToUnexplored(AtlasChunkTerrain[] nearby, int x, int z) {
        double invSum = 0.0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (nearby[(dz + 2) * 5 + dx + 2] != null) {
                    continue;
                }
                int offsetX = Math.max(0, Math.max(dx * 16 - x, x - (dx + 1) * 16));
                int offsetZ = Math.max(0, Math.max(dz * 16 - z, z - (dz + 1) * 16));
                int dSq = offsetX * offsetX + offsetZ * offsetZ;
                if (dSq == 0) {
                    return 0.0f;
                }
                invSum += INV_DIST_POW_LUT[dSq];
            }
        }
        return invSum > 0.0 ? (float) Math.pow(invSum, -1.0 / 2.2) : 64.0f;
    }
}
