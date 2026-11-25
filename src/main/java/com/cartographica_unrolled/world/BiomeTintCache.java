package com.cartographica_unrolled.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

final class BiomeTintCache {
    static final int TYPE_GRASS = 0;
    static final int TYPE_FOLIAGE = 1;
    static final int TYPE_WATER = 2;

    private static final int[] TINT_OFFSETS = {-6, -4, -2, 0, 2, 4, 6};
    private static final int[] TINT_WEIGHTS = {1, 2, 3, 4, 3, 2, 1};
    private static final int TINT_GRID_STRIDE = 28;
    private static final int TINT_MARGIN = 6;

    final LevelChunk[] nearbyChunks = new LevelChunk[9];
    private final int[] tintPointCache = new int[3 * TINT_GRID_STRIDE * TINT_GRID_STRIDE];
    private final int[] tintRowCache = new int[3 * 16 * TINT_GRID_STRIDE * 4];
    private final boolean[] tintRowReady = new boolean[3 * 16 * TINT_GRID_STRIDE];
    private final int[] tintCachedQy = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};

    private int lastBiomeKeyX = Integer.MIN_VALUE;
    private int lastBiomeKeyY = Integer.MIN_VALUE;
    private int lastBiomeKeyZ = Integer.MIN_VALUE;
    private @Nullable Biome lastBiome;
    private int centerCx;
    private int centerCz;

    void prepareForChunk(Level level, LevelChunk centerChunk) {
        int cx = centerChunk.getPos().x();
        int cz = centerChunk.getPos().z();
        this.centerCx = cx;
        this.centerCz = cz;
        var chunkSource = level.getChunkSource();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                LevelChunk nc = (dx == 0 && dz == 0) ? centerChunk : chunkSource.getChunkNow(cx + dx, cz + dz);
                this.nearbyChunks[(dz + 1) * 3 + (dx + 1)] = (nc != null && !nc.isEmpty()) ? nc : null;
            }
        }
        this.tintCachedQy[0] = Integer.MIN_VALUE;
        this.tintCachedQy[1] = Integer.MIN_VALUE;
        this.tintCachedQy[2] = Integer.MIN_VALUE;
        this.lastBiome = null;
    }

    void clearRefs() {
        this.lastBiome = null;
        Arrays.fill(this.nearbyChunks, null);
    }

    int sampleGrassTint(Level level, BlockPos pos) {
        return sample(level, pos, TYPE_GRASS);
    }

    int sampleFoliageTint(Level level, BlockPos pos) {
        return sample(level, pos, TYPE_FOLIAGE);
    }

    int sampleWaterTint(Level level, BlockPos pos) {
        return sample(level, pos, TYPE_WATER);
    }

    int sample(Level level, BlockPos pos, int type) {
        int px = pos.getX();
        int pz = pos.getZ();
        int qy = QuartPos.fromBlock(pos.getY());
        int chunkOriginX = this.centerCx << 4;
        int chunkOriginZ = this.centerCz << 4;
        int bx = px - chunkOriginX;
        int bz = pz - chunkOriginZ;

        int red = 0;
        int green = 0;
        int blue = 0;
        int totalWeight = 0;

        if (bx >= 0 && bx < 16 && bz >= 0 && bz < 16) {
            if (this.tintCachedQy[type] != qy) {
                this.tintCachedQy[type] = qy;
                int ptStart = type * TINT_GRID_STRIDE * TINT_GRID_STRIDE;
                Arrays.fill(this.tintPointCache, ptStart, ptStart + TINT_GRID_STRIDE * TINT_GRID_STRIDE, -1);
                int rowStart = type * 16 * TINT_GRID_STRIDE;
                Arrays.fill(this.tintRowReady, rowStart, rowStart + 16 * TINT_GRID_STRIDE, false);
            }
            int typePtBase = type * TINT_GRID_STRIDE * TINT_GRID_STRIDE;
            int bxRowBase = (type * 16 + bx) * TINT_GRID_STRIDE;

            for (int kz = 0; kz < 7; kz++) {
                int dz = TINT_OFFSETS[kz];
                int wz = TINT_WEIGHTS[kz];
                int lz = bz + TINT_MARGIN + dz;
                int rowIdx = bxRowBase + lz;
                int rowSlot = rowIdx << 2;
                if (!this.tintRowReady[rowIdx]) {
                    int rSum = 0;
                    int gSum = 0;
                    int bSum = 0;
                    int wSum = 0;
                    int ptRowBase = typePtBase + lz * TINT_GRID_STRIDE;
                    int worldZ = chunkOriginZ + lz - TINT_MARGIN;
                    for (int kx = 0; kx < 7; kx++) {
                        int dx = TINT_OFFSETS[kx];
                        int wxWeight = TINT_WEIGHTS[kx];
                        int lx = bx + TINT_MARGIN + dx;
                        int ptIdx = ptRowBase + lx;
                        int c = this.tintPointCache[ptIdx];
                        if (c == -1) {
                            c = samplePointBiomeColor(level, chunkOriginX + lx - TINT_MARGIN, qy, worldZ, type);
                            this.tintPointCache[ptIdx] = c;
                        }
                        if (c >= 0) {
                            rSum += ((c >>> 16) & 255) * wxWeight;
                            gSum += ((c >>> 8) & 255) * wxWeight;
                            bSum += (c & 255) * wxWeight;
                            wSum += wxWeight;
                        }
                    }
                    this.tintRowCache[rowSlot] = rSum;
                    this.tintRowCache[rowSlot + 1] = gSum;
                    this.tintRowCache[rowSlot + 2] = bSum;
                    this.tintRowCache[rowSlot + 3] = wSum;
                    this.tintRowReady[rowIdx] = true;
                }
                int rowW = this.tintRowCache[rowSlot + 3];
                if (rowW > 0) {
                    red += this.tintRowCache[rowSlot] * wz;
                    green += this.tintRowCache[rowSlot + 1] * wz;
                    blue += this.tintRowCache[rowSlot + 2] * wz;
                    totalWeight += rowW * wz;
                }
            }
        }

        if (totalWeight == 0) {
            Biome fallback = level.getBiome(pos).value();
            int color = type == TYPE_GRASS ? fallback.getGrassColor(px, pz)
                    : (type == TYPE_FOLIAGE ? fallback.getFoliageColor() : fallback.getWaterColor());
            if (color == 0 && type != TYPE_WATER) {
                color = type == TYPE_GRASS ? 0x7FA65A : 0x629644;
            }
            return color & 0xFFFFFF;
        }
        return ((red / totalWeight) << 16) | ((green / totalWeight) << 8) | (blue / totalWeight);
    }

    private int samplePointBiomeColor(Level level, int x, int qy, int z, int type) {
        int tcx = x >> 4;
        int tcz = z >> 4;
        int rx = tcx - (this.centerCx - 1);
        int rz = tcz - (this.centerCz - 1);
        LevelChunk target;
        if (rx >= 0 && rx < 3 && rz >= 0 && rz < 3) {
            target = this.nearbyChunks[rz * 3 + rx];
        } else {
            target = level.getChunkSource().getChunkNow(tcx, tcz);
            if (target != null && target.isEmpty()) {
                target = null;
            }
        }
        if (target == null) {
            return -2;
        }
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        Biome biome;
        if (this.lastBiome != null && qx == this.lastBiomeKeyX && qy == this.lastBiomeKeyY && qz == this.lastBiomeKeyZ) {
            biome = this.lastBiome;
        } else {
            biome = target.getNoiseBiome(qx, qy, qz).value();
            this.lastBiomeKeyX = qx;
            this.lastBiomeKeyY = qy;
            this.lastBiomeKeyZ = qz;
            this.lastBiome = biome;
        }
        int color = type == TYPE_GRASS ? biome.getGrassColor(x, z)
                : (type == TYPE_FOLIAGE ? biome.getFoliageColor() : biome.getWaterColor());
        if (color == 0 && type != TYPE_WATER) {
            color = type == TYPE_GRASS ? 0x7FA65A : 0x629644;
        }
        return color & 0xFFFFFF;
    }
}
