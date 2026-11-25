package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

final class RouteCostModel {
    static final float MIN_SURFACE_COST = 0.78f;
    static final float SQRT2 = 1.4142135f;

    static final int[] DIR_X = {1, -1, 0, 0, 1, 1, -1, -1};
    static final int[] DIR_Z = {0, 0, 1, -1, 1, -1, 1, -1};
    static final int[] DIAG_CARDINAL_X = {0, 0, 1, 1};
    static final int[] DIAG_CARDINAL_Z = {2, 3, 2, 3};

    static final class TerrainLookup {
        private static final AtlasChunkTerrain MISSING_CHUNK =
                new AtlasChunkTerrain(new long[AtlasChunkTerrain.COLUMN_COUNT]);

        private final Long2ObjectOpenHashMap<AtlasChunkTerrain> cache = new Long2ObjectOpenHashMap<>(256);
        private long lastKey = Long.MIN_VALUE;
        private AtlasChunkTerrain lastChunk = MISSING_CHUNK;

        void reset() {
            this.cache.clear();
            this.lastKey = Long.MIN_VALUE;
            this.lastChunk = MISSING_CHUNK;
        }

        void shrink() {
            this.cache.clear();
            this.cache.trim(256);
            this.lastKey = Long.MIN_VALUE;
            this.lastChunk = MISSING_CHUNK;
        }

        long sample(int wx, int wz) {
            int cx = wx >> 4;
            int cz = wz >> 4;
            long key = ChunkPos.pack(cx, cz);
            AtlasChunkTerrain chunk;
            if (key == this.lastKey) {
                chunk = this.lastChunk;
            } else {
                chunk = this.cache.get(key);
                if (chunk == null) {
                    AtlasChunkTerrain fetched = AtlasRegionCache.getOutdoorChunk(cx, cz);
                    chunk = fetched != null ? fetched : MISSING_CHUNK;
                    this.cache.put(key, chunk);
                }
                this.lastKey = key;
                this.lastChunk = chunk;
            }
            if (chunk == MISSING_CHUNK) {
                return AtlasChunkTerrain.EMPTY_COLUMN;
            }
            return chunk.getColumn(wx & 15, wz & 15);
        }
    }

    private RouteCostModel() {
    }

    static int cellCenter(int g, int step) {
        return g * step + (step >> 1);
    }

    static float heuristicWorld(int wx, int wz, int goalWx, int goalWz) {
        int dx = Math.abs(goalWx - wx);
        int dz = Math.abs(goalWz - wz);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return ((max - min) + min * SQRT2) * MIN_SURFACE_COST;
    }

    static boolean isIndoorWindow(long col) {
        return AtlasChunkTerrain.unpackDetailType(col) == AtlasChunkTerrain.DETAIL_INDOOR_PORTAL_DOOR
                && AtlasChunkTerrain.unpackDetailAccent(col) == AtlasChunkTerrain.ACCENT_ORCHID_CYAN;
    }

    static boolean isHardBlockedColumn(long col) {
        if (col == AtlasChunkTerrain.EMPTY_COLUMN) {
            return false;
        }
        int flags = AtlasChunkTerrain.unpackFlags(col);
        if ((flags & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return true;
        }
        int detail = AtlasChunkTerrain.unpackDetailType(col);
        return detail == AtlasChunkTerrain.DETAIL_INDOOR_WALL
                || detail == AtlasChunkTerrain.DETAIL_OUTDOOR_FENCE_WALL
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_BOOKSHELF
                || detail == AtlasChunkTerrain.DETAIL_OUTDOOR_CAMPFIRE
                || isIndoorWindow(col);
    }

    static boolean isHardBlockedStep(long curCol, long nextCol, int step) {
        if (nextCol == AtlasChunkTerrain.EMPTY_COLUMN) {
            return false;
        }
        int nextFlags = AtlasChunkTerrain.unpackFlags(nextCol);
        if ((nextFlags & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return true;
        }
        if (step == 1 && isHardBlockedColumn(nextCol)) {
            return true;
        }
        if (curCol != AtlasChunkTerrain.EMPTY_COLUMN) {
            int dy = AtlasChunkTerrain.unpackSurfaceY(nextCol) - AtlasChunkTerrain.unpackSurfaceY(curCol);
            int maxClimb = step == 1 ? 1 : (step + 1);
            if (dy > (step == 1 ? 1 : maxClimb + 2)) {
                return true;
            }
        }
        return false;
    }

    static long sampleMacroCell(TerrainLookup lookup, int gx, int gz, int step) {
        int cx = cellCenter(gx, step);
        int cz = cellCenter(gz, step);
        long center = lookup.sample(cx, cz);
        if (step <= 1 || center == AtlasChunkTerrain.EMPTY_COLUMN) {
            return center;
        }
        int cFlags = AtlasChunkTerrain.unpackFlags(center);
        if ((cFlags & (AtlasChunkTerrain.FLAG_WATER | AtlasChunkTerrain.FLAG_LAVA)) == 0
                && !isHardBlockedColumn(center)) {
            return center;
        }
        int off = Math.max(1, step >> 2);
        long best = center;
        float bestScore = scoreMacroSample(center);
        boolean anyLava = (cFlags & AtlasChunkTerrain.FLAG_LAVA) != 0;
        for (int dz = -off; dz <= off; dz += (off << 1)) {
            for (int dx = -off; dx <= off; dx += (off << 1)) {
                long s = lookup.sample(cx + dx, cz + dz);
                if (s == AtlasChunkTerrain.EMPTY_COLUMN) {
                    continue;
                }
                if ((AtlasChunkTerrain.unpackFlags(s) & AtlasChunkTerrain.FLAG_LAVA) != 0) {
                    anyLava = true;
                    continue;
                }
                float sc = scoreMacroSample(s);
                if (sc < bestScore) {
                    bestScore = sc;
                    best = s;
                }
            }
        }
        if (anyLava && (AtlasChunkTerrain.unpackFlags(best) & AtlasChunkTerrain.FLAG_LAVA) == 0
                && AtlasChunkTerrain.unpackDetailType(best) != AtlasChunkTerrain.DETAIL_OUTDOOR_BRIDGE) {
            return center;
        }
        return best;
    }

    private static float scoreMacroSample(long col) {
        if (col == AtlasChunkTerrain.EMPTY_COLUMN) {
            return 6.5f;
        }
        if ((AtlasChunkTerrain.unpackFlags(col) & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return 200.0f;
        }
        if (isHardBlockedColumn(col)) {
            return 80.0f;
        }
        return terrainSurfaceMult(col);
    }

    static float seamTransitionCost(
            int curWx,
            int curGroundY,
            int curWz,
            int nextWx,
            int nextWz,
            long nextCol,
            int step,
            boolean isGoal
    ) {
        int ddx = nextWx - curWx;
        int ddz = nextWz - curWz;
        float dist = Math.max(1.0f, Mth.sqrt((float) (ddx * ddx + ddz * ddz)));
        if (nextCol == AtlasChunkTerrain.EMPTY_COLUMN) {
            return dist * 6.5f;
        }
        int nextFlags = AtlasChunkTerrain.unpackFlags(nextCol);
        if ((nextFlags & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return isGoal ? dist * 200.0f : Float.POSITIVE_INFINITY;
        }
        if (step == 1 && isHardBlockedColumn(nextCol)) {
            return isGoal ? dist * 160.0f : Float.POSITIVE_INFINITY;
        }
        int dy = AtlasChunkTerrain.unpackSurfaceY(nextCol) - curGroundY;
        int maxClimb = step == 1 ? 1 : (step + 2);
        int hardLimit = step == 1 ? 3 : (maxClimb + 3);
        float climbPenalty = computeClimbDropPenalty(
                dy, maxClimb, hardLimit, step,
                (nextFlags & AtlasChunkTerrain.FLAG_WATER) != 0,
                isGoal, dist, 24.0f, 10.0f, 3.0f
        );
        if (!Float.isFinite(climbPenalty)) {
            return Float.POSITIVE_INFINITY;
        }
        return dist * terrainSurfaceMult(nextCol) + climbPenalty;
    }

    static float transitionCost(long curCol, long nextCol, int step, boolean isDiag, boolean isGoal) {
        float dist = isDiag ? SQRT2 : 1.0f;
        if (nextCol == AtlasChunkTerrain.EMPTY_COLUMN) {
            return dist * 6.5f;
        }
        int nextFlags = AtlasChunkTerrain.unpackFlags(nextCol);
        if ((nextFlags & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return isGoal ? dist * 200.0f : Float.POSITIVE_INFINITY;
        }
        if (isHardBlockedColumn(nextCol)) {
            return (step == 1 && !isGoal) ? Float.POSITIVE_INFINITY : dist * 80.0f;
        }
        float climbPenalty = 0.0f;
        if (curCol != AtlasChunkTerrain.EMPTY_COLUMN) {
            int dy = AtlasChunkTerrain.unpackSurfaceY(nextCol) - AtlasChunkTerrain.unpackSurfaceY(curCol);
            int maxClimb = step == 1 ? 1 : (step + 1);
            int hardLimit = step == 1 ? 2 : (maxClimb + 3);
            climbPenalty = computeClimbDropPenalty(
                    dy, maxClimb, hardLimit, step,
                    (nextFlags & AtlasChunkTerrain.FLAG_WATER) != 0,
                    isGoal, dist, 28.0f, 12.0f, 3.5f
            );
            if (!Float.isFinite(climbPenalty)) {
                return Float.POSITIVE_INFINITY;
            }
        }
        return dist * terrainSurfaceMult(nextCol) + climbPenalty;
    }

    private static float computeClimbDropPenalty(
            int dy,
            int maxClimb,
            int hardClimbLimit,
            int step,
            boolean nextWater,
            boolean isGoal,
            float dist,
            float steepClimbBase,
            float dropBase,
            float dropSlope
    ) {
        if (dy > maxClimb) {
            if (!isGoal && dy >= hardClimbLimit) {
                return Float.POSITIVE_INFINITY;
            }
            return dist * (steepClimbBase + (dy - maxClimb) * 14.0f);
        }
        if (dy > 0) {
            return 0.35f * dy;
        }
        if (dy < -(step + 3) && !nextWater) {
            if (!isGoal && dy < -(step + 8)) {
                return Float.POSITIVE_INFINITY;
            }
            return dist * (dropBase + (-dy - step - 3) * dropSlope);
        }
        return 0.0f;
    }

    static float terrainSurfaceMult(long col) {
        int flags = AtlasChunkTerrain.unpackFlags(col);
        int detail = AtlasChunkTerrain.unpackDetailType(col);
        if (detail == AtlasChunkTerrain.DETAIL_OUTDOOR_BRIDGE) {
            return MIN_SURFACE_COST;
        }
        if ((flags & AtlasChunkTerrain.FLAG_WATER) != 0) {
            int waterDepth = AtlasChunkTerrain.unpackWaterDepth(col);
            return waterDepth <= 1 ? 2.0f : (5.5f + Math.min(waterDepth, 12) * 0.25f);
        }
        if (detail == AtlasChunkTerrain.DETAIL_OUTDOOR_PATH_STEP
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_FLOOR
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_CARPET
                || (detail == AtlasChunkTerrain.DETAIL_INDOOR_PORTAL_DOOR && !isIndoorWindow(col))
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_STAIRS) {
            return 0.80f;
        }
        if (detail == AtlasChunkTerrain.DETAIL_INDOOR_FURNITURE
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_BED_HEAD
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_BED_FOOT
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_CHEST
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_HEARTH
                || detail == AtlasChunkTerrain.DETAIL_INDOOR_TABLE_CRAFTER) {
            return 3.5f;
        }
        if (detail == AtlasChunkTerrain.DETAIL_BERRY_BUSH
                || detail == AtlasChunkTerrain.DETAIL_OUTDOOR_CAMPFIRE) {
            return 4.0f;
        }
        if ((flags & AtlasChunkTerrain.FLAG_STRUCTURE) != 0) {
            return 2.0f;
        }
        if ((flags & AtlasChunkTerrain.FLAG_CANOPY) != 0) {
            return 1.22f;
        }
        return 1.0f;
    }
}
