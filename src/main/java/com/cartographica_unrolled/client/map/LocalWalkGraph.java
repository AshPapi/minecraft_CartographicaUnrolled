package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.world.AtlasBlockClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

final class LocalWalkGraph {
    static final int LOCAL_RADIUS = 36;
    private static final int LOCAL_SIDE = LOCAL_RADIUS * 2 + 1;
    static final int LOCAL_MAX_DY = 16;
    private static final int MAX_LOCAL_NODES = 8192;

    private static final int[] STEP_DELTAS = {0, 1, -1, -2, -3};
    private static final int[] WATER_DROP_DELTAS = {-4, -5, -6, -7};
    private static final int[] START_CANDIDATES_HIGH = {1, 0, -1, -2};
    private static final int[] START_CANDIDATES_LOW = {0, -1, 1, -2};

    private static final byte FLAG_DOOR = 1;
    private static final byte FLAG_WATER = 1 << 1;
    private static final byte FLAG_HIGH_COST = 1 << 2;
    private static final byte FLAG_BOUNDARY = 1 << 3;
    private static final byte FLAG_STAIRS = 1 << 4;

    private static final long SP_COMPUTED = 1L;
    private static final long SP_AIR = 1L << 1;
    private static final long SP_WATER = 1L << 2;
    private static final long SP_LAVA = 1L << 3;
    private static final long SP_NON_EMPTY_FLUID = 1L << 4;
    private static final long SP_HAZARD_BLOCK = 1L << 5;
    private static final long SP_HAZARD_SUPPORT = 1L << 6;
    private static final long SP_PORTAL = 1L << 7;
    private static final long SP_CLOSED_IRON_DOOR = 1L << 8;
    private static final long SP_UNSUPPORTIVE = 1L << 9;
    private static final long SP_FENCE_WALL_BARS = 1L << 10;
    private static final long SP_STAIRS_OR_SLABS = 1L << 11;
    private static final long SP_CARPET = 1L << 12;
    private static final long SP_SLOW_FEET = 1L << 13;
    private static final long SP_COBWEB = 1L << 14;
    private static final long SP_DYNAMIC_SHAPE = 1L << 15;
    private static final long SP_HEAD_PASSABLE = 1L << 16;
    private static final long SP_FEET_PASSABLE = 1L << 17;
    private static final long SP_UPPER_CEIL_STAIR = 1L << 18;
    private static final long SP_VALID_SUPPORT = 1L << 19;
    private static final long SP_SUPPORT_STAIR_FLG = 1L << 20;
    private static final int SP_SUP_COST_SHIFT = 21;
    private static final long SP_STAIRS_ONLY = 1L << 23;
    private static final long SP_SLABS_ONLY = 1L << 24;

    private static volatile long[] statePropsCache = new long[32768];

    static void clearStateCache() {
        statePropsCache = new long[32768];
    }

    private static long stateProps(BlockState bs) {
        int id = Block.getId(bs);
        long[] cache = statePropsCache;
        if (id >= 0 && id < cache.length) {
            long v = cache[id];
            if (v != 0L) {
                return v;
            }
        }
        return computeAndCacheStateProps(bs, id);
    }

    private static long computeAndCacheStateProps(BlockState bs, int id) {
        long f = SP_COMPUTED;
        if (bs.isAir()) {
            f |= SP_AIR | SP_HEAD_PASSABLE | SP_FEET_PASSABLE;
        } else {
            FluidState fluid = bs.getFluidState();
            boolean water = false;
            boolean nonEmptyFluid = !fluid.isEmpty();
            if (nonEmptyFluid) {
                f |= SP_NON_EMPTY_FLUID;
                if (fluid.is(FluidTags.WATER)) {
                    water = true;
                    f |= SP_WATER;
                } else if (fluid.is(FluidTags.LAVA)) {
                    f |= SP_LAVA;
                }
            }
            boolean hazardBlock = bs.is(BlockTags.FIRE)
                    || bs.is(BlockTags.CAMPFIRES)
                    || bs.is(Blocks.CACTUS)
                    || bs.is(Blocks.WITHER_ROSE)
                    || bs.is(Blocks.POWDER_SNOW);
            if (hazardBlock) {
                f |= SP_HAZARD_BLOCK | SP_HAZARD_SUPPORT;
            } else if (bs.is(Blocks.MAGMA_BLOCK)) {
                f |= SP_HAZARD_SUPPORT;
            }

            boolean doors = bs.is(BlockTags.DOORS);
            boolean gates = bs.is(BlockTags.FENCE_GATES);
            boolean portal = doors || gates;
            if (portal) {
                f |= SP_PORTAL;
                if (bs.is(Blocks.IRON_DOOR)
                        && bs.hasProperty(BlockStateProperties.OPEN)
                        && !bs.getValue(BlockStateProperties.OPEN)) {
                    f |= SP_CLOSED_IRON_DOOR;
                }
            }

            boolean fenceWallBars = bs.is(BlockTags.FENCES)
                    || bs.is(BlockTags.WALLS)
                    || bs.getBlock() instanceof IronBarsBlock;
            boolean unsupportive = fenceWallBars || portal;
            if (fenceWallBars) {
                f |= SP_FENCE_WALL_BARS;
            }
            if (unsupportive) {
                f |= SP_UNSUPPORTIVE;
            }

            boolean stairs = bs.is(BlockTags.STAIRS);
            boolean slabs = bs.is(BlockTags.SLABS);
            if (stairs) {
                f |= SP_STAIRS_ONLY | SP_STAIRS_OR_SLABS;
            }
            if (slabs) {
                f |= SP_SLABS_ONLY | SP_STAIRS_OR_SLABS;
            }
            if (bs.is(BlockTags.WOOL_CARPETS)) {
                f |= SP_CARPET;
            }
            if (bs.is(Blocks.COBWEB)) {
                f |= SP_COBWEB | SP_SLOW_FEET;
            } else if (bs.is(Blocks.SWEET_BERRY_BUSH)) {
                f |= SP_SLOW_FEET;
            }

            long costCat = 0L;
            if (isRoadOrBridgeSupport(bs)) {
                costCat = 1L;
            } else if (isIndoorFurnitureSupport(bs)) {
                costCat = 2L;
            } else if (stairs || slabs || bs.is(BlockTags.PLANKS)) {
                costCat = 3L;
            }
            f |= (costCat << SP_SUP_COST_SHIFT);

            if (bs.getBlock().hasDynamicShape()) {
                f |= SP_DYNAMIC_SHAPE;
            } else {
                VoxelShape shape = bs.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                boolean emptyShape = shape.isEmpty();
                if (portal || (!nonEmptyFluid && emptyShape)) {
                    f |= SP_HEAD_PASSABLE;
                }
                boolean feetOk;
                if (portal) {
                    feetOk = true;
                } else if (nonEmptyFluid) {
                    feetOk = water && emptyShape;
                } else if (emptyShape) {
                    feetOk = true;
                } else if (fenceWallBars) {
                    feetOk = false;
                } else {
                    feetOk = shape.max(Direction.Axis.Y) <= 0.251;
                }
                if (feetOk) {
                    f |= SP_FEET_PASSABLE;
                }
                if ((stairs || slabs) && !emptyShape && shape.min(Direction.Axis.Y) >= 0.49) {
                    f |= SP_UPPER_CEIL_STAIR;
                }
                if (!unsupportive && !emptyShape) {
                    double supMaxY = shape.max(Direction.Axis.Y);
                    if (supMaxY >= 0.05 && supMaxY <= 1.01) {
                        f |= SP_VALID_SUPPORT;
                        if (stairs || (slabs && supMaxY <= 0.55)) {
                            f |= SP_SUPPORT_STAIR_FLG;
                        }
                        f |= ((long) Float.floatToRawIntBits((float) supMaxY)) << 32;
                    }
                }
            }
        }

        if (id >= 0 && id < 1_048_576) {
            synchronized (LocalWalkGraph.class) {
                long[] cur = statePropsCache;
                if (id >= cur.length) {
                    int nextLen = Math.max(id + 1024, cur.length * 2);
                    cur = Arrays.copyOf(cur, nextLen);
                    cur[id] = f;
                    statePropsCache = cur;
                } else {
                    cur[id] = f;
                }
            }
        }
        return f;
    }

    private static boolean isRoadOrBridgeSupport(BlockState bs) {
        return bs.is(Blocks.DIRT_PATH)
                || bs.is(Blocks.GRAVEL)
                || bs.is(Blocks.COBBLESTONE)
                || bs.is(Blocks.MOSSY_COBBLESTONE)
                || bs.is(Blocks.STONE_BRICKS)
                || bs.is(Blocks.SMOOTH_STONE_SLAB);
    }

    private static boolean isIndoorFurnitureSupport(BlockState bs) {
        return !bs.is(BlockTags.WOOL_CARPETS)
                && !bs.is(Blocks.MOSS_CARPET)
                && !bs.is(Blocks.PALE_MOSS_CARPET)
                && !bs.is(Blocks.BELL)
                && !bs.is(Blocks.LANTERN)
                && !bs.is(Blocks.SOUL_LANTERN)
                && !bs.is(Blocks.CAKE)
                && !bs.is(Blocks.DECORATED_POT)
                && !bs.is(BlockTags.FLOWER_POTS)
                && !bs.is(BlockTags.CANDLES)
                && !bs.is(Blocks.SCAFFOLDING)
                && AtlasBlockClassifier.isIndoorFurnitureOrFixture(bs);
    }

    private final int minX;
    private final int minZ;
    private final int side;
    private final int minChunkX;
    private final int minChunkZ;
    private final int chunkSpanX;
    private final int chunkSpanZ;
    private final boolean[] chunkLoaded;

    private final int nodeCount;
    private final byte[] lx;
    private final short[] y;
    private final byte[] lz;
    private final float[] moveCost;
    private final byte[] flags;
    private final int[] edges;
    private final int[] colHead;
    private final int[] nextInCol;

    private LocalWalkGraph(
            int minX,
            int minZ,
            int side,
            int minChunkX,
            int minChunkZ,
            int chunkSpanX,
            int chunkSpanZ,
            boolean[] chunkLoaded,
            int nodeCount,
            byte[] lx,
            short[] y,
            byte[] lz,
            float[] moveCost,
            byte[] flags,
            int[] edges,
            int[] colHead,
            int[] nextInCol
    ) {
        this.minX = minX;
        this.minZ = minZ;
        this.side = side;
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.chunkSpanX = chunkSpanX;
        this.chunkSpanZ = chunkSpanZ;
        this.chunkLoaded = chunkLoaded;
        this.nodeCount = nodeCount;
        this.lx = lx;
        this.y = y;
        this.lz = lz;
        this.moveCost = moveCost;
        this.flags = flags;
        this.edges = edges;
        this.colHead = colHead;
        this.nextInCol = nextInCol;
    }

    int nodeCount() {
        return this.nodeCount;
    }

    int worldX(int node) {
        return this.minX + (this.lx[node] & 0xFF);
    }

    int worldY(int node) {
        return this.y[node];
    }

    int worldZ(int node) {
        return this.minZ + (this.lz[node] & 0xFF);
    }

    float moveCost(int node) {
        return this.moveCost[node];
    }

    boolean isWater(int node) {
        return (this.flags[node] & FLAG_WATER) != 0;
    }

    boolean isHighCost(int node) {
        return (this.flags[node] & FLAG_HIGH_COST) != 0;
    }

    boolean isBoundary(int node) {
        return (this.flags[node] & FLAG_BOUNDARY) != 0;
    }

    boolean isStairs(int node) {
        return (this.flags[node] & FLAG_STAIRS) != 0;
    }

    int edge(int node, int dir) {
        return this.edges[(node << 3) | dir];
    }

    int stepDelta(int node, int dx, int dz) {
        int dir = deltaToDir(dx, dz);
        return dir >= 0 ? this.edges[(node << 3) | dir] : -1;
    }

    boolean containsLoaded(int wx, int wz) {
        int rx = wx - this.minX;
        int rz = wz - this.minZ;
        if (rx < 0 || rx >= this.side || rz < 0 || rz >= this.side) {
            return false;
        }
        int cx = (wx >> 4) - this.minChunkX;
        int cz = (wz >> 4) - this.minChunkZ;
        if (cx < 0 || cx >= this.chunkSpanX || cz < 0 || cz >= this.chunkSpanZ) {
            return false;
        }
        return this.chunkLoaded[cz * this.chunkSpanX + cx];
    }

    boolean hasColumn(int wx, int wz) {
        int rx = wx - this.minX;
        int rz = wz - this.minZ;
        if (rx < 0 || rx >= this.side || rz < 0 || rz >= this.side) {
            return false;
        }
        return this.colHead[rz * this.side + rx] >= 0;
    }

    int findClosestNodeInColumn(int wx, int wz, int preferredY) {
        int rx = wx - this.minX;
        int rz = wz - this.minZ;
        if (rx < 0 || rx >= this.side || rz < 0 || rz >= this.side) {
            return -1;
        }
        int best = -1;
        int bestDy = Integer.MAX_VALUE;
        for (int cur = this.colHead[rz * this.side + rx]; cur >= 0; cur = this.nextInCol[cur]) {
            int dy = Math.abs(this.y[cur] - preferredY);
            if (dy < bestDy) {
                bestDy = dy;
                best = cur;
            }
        }
        return best;
    }

    int findNearestNode(float targetX, float targetZ, float maxRadius, int preferredY) {
        float bestDistSq = maxRadius * maxRadius;
        int best = -1;
        int tx = Mth.floor(targetX);
        int tz = Mth.floor(targetZ);
        int r = Mth.ceil(maxRadius);
        int x0 = Math.max(0, tx - r - this.minX);
        int x1 = Math.min(this.side - 1, tx + r - this.minX);
        int z0 = Math.max(0, tz - r - this.minZ);
        int z1 = Math.min(this.side - 1, tz + r - this.minZ);
        for (int rz = z0; rz <= z1; rz++) {
            float wz = this.minZ + rz + 0.5f;
            float dz = wz - targetZ;
            for (int rx = x0; rx <= x1; rx++) {
                int head = this.colHead[rz * this.side + rx];
                if (head < 0) {
                    continue;
                }
                float wx = this.minX + rx + 0.5f;
                float dx = wx - targetX;
                float dSq = dx * dx + dz * dz;
                if (dSq < bestDistSq) {
                    bestDistSq = dSq;
                    int colBest = head;
                    int bestDy = Math.abs(this.y[head] - preferredY);
                    for (int cur = this.nextInCol[head]; cur >= 0; cur = this.nextInCol[cur]) {
                        int dy = Math.abs(this.y[cur] - preferredY);
                        if (dy < bestDy) {
                            bestDy = dy;
                            colBest = cur;
                        }
                    }
                    best = colBest;
                }
            }
        }
        return best;
    }

    private static int deltaToDir(int dx, int dz) {
        if (dx == 1 && dz == 0) return 0;
        if (dx == -1 && dz == 0) return 1;
        if (dx == 0 && dz == 1) return 2;
        if (dx == 0 && dz == -1) return 3;
        if (dx == 1 && dz == 1) return 4;
        if (dx == 1 && dz == -1) return 5;
        if (dx == -1 && dz == 1) return 6;
        if (dx == -1 && dz == -1) return 7;
        return -1;
    }

    static @Nullable LocalWalkGraph buildFromSnapshot(WalkSectionSnapshot snapshot) {
        Builder b = new Builder(snapshot);
        return b.run(snapshot.startWx, snapshot.startY, snapshot.startWz, snapshot.rawY);
    }

    private static final class BuilderScratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final byte[] lx = new byte[MAX_LOCAL_NODES];
        final short[] y = new short[MAX_LOCAL_NODES];
        final byte[] lz = new byte[MAX_LOCAL_NODES];
        final float[] surfTopY = new float[MAX_LOCAL_NODES];
        final float[] moveCost = new float[MAX_LOCAL_NODES];
        final byte[] flags = new byte[MAX_LOCAL_NODES];
        final int[] edges = new int[MAX_LOCAL_NODES * 8];
        final int[] nextInCol = new int[MAX_LOCAL_NODES];
        final int[] queue = new int[MAX_LOCAL_NODES];
        final int[] colHead = new int[LOCAL_SIDE * LOCAL_SIDE];
        final long[] rejectedYMask = new long[LOCAL_SIDE * LOCAL_SIDE];
    }

    private static final BuilderScratch BUILDER_SCRATCH = new BuilderScratch();

    private static final class Builder {
        private final WalkSectionSnapshot snapshot;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int side;
        private final int minSearchY;
        private final int maxSearchY;
        private final BlockPos.MutableBlockPos pos = BUILDER_SCRATCH.pos;

        private final byte[] lx = BUILDER_SCRATCH.lx;
        private final short[] y = BUILDER_SCRATCH.y;
        private final byte[] lz = BUILDER_SCRATCH.lz;
        private final float[] surfTopY = BUILDER_SCRATCH.surfTopY;
        private final float[] moveCost = BUILDER_SCRATCH.moveCost;
        private final byte[] flags = BUILDER_SCRATCH.flags;
        private final int[] edges = BUILDER_SCRATCH.edges;
        private final int[] colHead = BUILDER_SCRATCH.colHead;
        private final long[] rejectedYMask = BUILDER_SCRATCH.rejectedYMask;
        private final int[] nextInCol = BUILDER_SCRATCH.nextInCol;
        private final int[] queue = BUILDER_SCRATCH.queue;
        private int nodeCount;

        private float tmpSurfTopY;
        private float tmpMoveCost;
        private byte tmpFlags;
        private boolean isNewlyAdded;

        Builder(WalkSectionSnapshot snapshot) {
            this.snapshot = snapshot;
            this.minX = snapshot.minX;
            this.maxX = snapshot.maxX;
            this.minZ = snapshot.minZ;
            this.maxZ = snapshot.maxZ;
            this.side = LOCAL_SIDE;
            this.minSearchY = snapshot.minSearchY;
            this.maxSearchY = snapshot.maxSearchY;
            Arrays.fill(this.colHead, -1);
            Arrays.fill(this.rejectedYMask, 0L);
        }

        @Nullable LocalWalkGraph run(int startWx, double startY, int startWz, int rawY) {
            int[] offsets = (startY - rawY) >= 0.35 ? START_CANDIDATES_HIGH : START_CANDIDATES_LOW;
            int startNode = -1;
            for (int off : offsets) {
                int sy = rawY + off;
                if (sy >= this.minSearchY && sy <= this.maxSearchY && classifyNode(startWx, sy, startWz)) {
                    startNode = addNode(startWx, sy, startWz, this.tmpSurfTopY, this.tmpMoveCost, this.tmpFlags);
                    break;
                }
            }
            if (startNode < 0) {
                outer:
                for (int off : offsets) {
                    int sy = rawY + off;
                    if (sy < this.minSearchY || sy > this.maxSearchY) {
                        continue;
                    }
                    for (int d = 0; d < 4; d++) {
                        int nwx = startWx + RouteCostModel.DIR_X[d];
                        int nwz = startWz + RouteCostModel.DIR_Z[d];
                        if (classifyNode(nwx, sy, nwz)) {
                            startNode = addNode(nwx, sy, nwz, this.tmpSurfTopY, this.tmpMoveCost, this.tmpFlags);
                            break outer;
                        }
                    }
                }
            }
            if (startNode < 0) {
                return null;
            }

            int qHead = 0;
            int qTail = 0;
            this.queue[qTail++] = startNode;

            while (qHead < qTail) {
                int u = this.queue[qHead++];
                int ux = this.minX + (this.lx[u] & 0xFF);
                int uy = this.y[u];
                int uz = this.minZ + (this.lz[u] & 0xFF);
                float uTopY = this.surfTopY[u];
                boolean uStairs = (this.flags[u] & FLAG_STAIRS) != 0;

                for (int d = 0; d < 4; d++) {
                    int nwx = ux + RouteCostModel.DIR_X[d];
                    int nwz = uz + RouteCostModel.DIR_Z[d];
                    if (nwx < this.minX || nwx > this.maxX || nwz < this.minZ || nwz > this.maxZ
                            || !this.snapshot.isChunkLoadedAt(nwx, nwz)) {
                        this.flags[u] |= FLAG_BOUNDARY;
                        continue;
                    }

                    int target = tryStepCardinal(ux, uy, uz, uTopY, uStairs, nwx, nwz, qTail < MAX_LOCAL_NODES);
                    if (target >= 0) {
                        this.edges[(u << 3) | d] = target;
                        if (target == this.nodeCount - 1 && qTail < MAX_LOCAL_NODES && this.isNewlyAdded) {
                            this.queue[qTail++] = target;
                        }
                    } else if (this.nodeCount >= MAX_LOCAL_NODES) {
                        this.flags[u] |= FLAG_BOUNDARY;
                    }
                }
            }

            for (int u = 0; u < this.nodeCount; u++) {
                if ((this.flags[u] & FLAG_DOOR) != 0) {
                    continue;
                }
                int uy = this.y[u];
                for (int di = 0; di < 4; di++) {
                    int cxDir = RouteCostModel.DIAG_CARDINAL_X[di];
                    int czDir = RouteCostModel.DIAG_CARDINAL_Z[di];
                    int sideA = this.edges[(u << 3) | cxDir];
                    int sideB = this.edges[(u << 3) | czDir];
                    if (sideA < 0 || sideB < 0) {
                        continue;
                    }
                    if ((this.flags[sideA] & FLAG_DOOR) != 0 || (this.flags[sideB] & FLAG_DOOR) != 0) {
                        continue;
                    }
                    int diagA = this.edges[(sideA << 3) | czDir];
                    int diagB = this.edges[(sideB << 3) | cxDir];
                    if (diagA >= 0 && diagA == diagB
                            && (this.flags[diagA] & FLAG_DOOR) == 0
                            && Math.abs(this.y[diagA] - uy) <= 1) {
                        this.edges[(u << 3) | (di + 4)] = diagA;
                    }
                }
            }

            return new LocalWalkGraph(
                    this.minX, this.minZ, this.side,
                    this.snapshot.minChunkX, this.snapshot.minChunkZ,
                    this.snapshot.chunkSpanX, this.snapshot.chunkSpanZ,
                    this.snapshot.chunkLoaded,
                    this.nodeCount,
                    Arrays.copyOf(this.lx, this.nodeCount),
                    Arrays.copyOf(this.y, this.nodeCount),
                    Arrays.copyOf(this.lz, this.nodeCount),
                    Arrays.copyOf(this.moveCost, this.nodeCount),
                    Arrays.copyOf(this.flags, this.nodeCount),
                    Arrays.copyOf(this.edges, this.nodeCount * 8),
                    this.colHead.clone(),
                    Arrays.copyOf(this.nextInCol, this.nodeCount)
            );
        }

        private int tryStepCardinal(
                int ux, int uy, int uz, float uTopY, boolean uStairs,
                int nwx, int nwz, boolean canAllocate
        ) {
            this.isNewlyAdded = false;
            int colIdx = (nwz - this.minZ) * this.side + (nwx - this.minX);
            int res = tryDeltas(colIdx, ux, uy, uz, uTopY, uStairs, nwx, nwz, canAllocate, STEP_DELTAS, false);
            if (res != -2) {
                return res;
            }
            res = tryDeltas(colIdx, ux, uy, uz, uTopY, uStairs, nwx, nwz, canAllocate, WATER_DROP_DELTAS, true);
            return res < 0 ? -1 : res;
        }

        private int tryDeltas(
                int colIdx,
                int ux, int uy, int uz, float uTopY, boolean uStairs,
                int nwx, int nwz,
                boolean canAllocate,
                int[] deltas,
                boolean waterOnly
        ) {
            long rejMask = this.rejectedYMask[colIdx];
            for (int dy : deltas) {
                int ny = uy + dy;
                if (ny < this.minSearchY) {
                    if (waterOnly) {
                        return -1;
                    }
                    continue;
                }
                if (ny > this.maxSearchY) {
                    continue;
                }
                int existing = findInColumn(colIdx, ny);
                if (existing >= 0) {
                    boolean exWater = (this.flags[existing] & FLAG_WATER) != 0;
                    if ((!waterOnly || exWater) && canTransitionHeight(
                            ux, uy, uz, uTopY, uStairs, nwx, ny, nwz,
                            this.surfTopY[existing], exWater,
                            !waterOnly && (this.flags[existing] & FLAG_STAIRS) != 0
                    )) {
                        return existing;
                    }
                    continue;
                }
                long yBit = 1L << (ny - this.minSearchY);
                if ((rejMask & yBit) != 0L) {
                    continue;
                }
                if (!classifyNode(nwx, ny, nwz)) {
                    rejMask |= yBit;
                    this.rejectedYMask[colIdx] = rejMask;
                    continue;
                }
                boolean newWater = (this.tmpFlags & FLAG_WATER) != 0;
                if (waterOnly && !newWater) {
                    return -1;
                }
                if (!canTransitionHeight(
                        ux, uy, uz, uTopY, uStairs, nwx, ny, nwz,
                        this.tmpSurfTopY, newWater, !waterOnly && (this.tmpFlags & FLAG_STAIRS) != 0
                )) {
                    continue;
                }
                if (!canAllocate || this.nodeCount >= MAX_LOCAL_NODES) {
                    return -1;
                }
                int created = addNode(nwx, ny, nwz, this.tmpSurfTopY, this.tmpMoveCost, this.tmpFlags);
                this.isNewlyAdded = true;
                return created;
            }
            return -2;
        }

        private boolean canTransitionHeight(
                int ux, int uy, int uz, float uTopY, boolean uStairs,
                int nwx, int ny, int nwz, float vTopY, boolean vWater, boolean vStairs
        ) {
            float dSurf = vTopY - uTopY;
            if (dSurf > 1.25f) {
                return false;
            }
            if (ny > uy) {
                if (dSurf > 0.6f && !vStairs) {
                    if (!this.snapshot.isChunkLoadedAt(ux, uz)) {
                        return false;
                    }
                    BlockState aboveHead = this.snapshot.getStateFast(ux, uy + 2, uz);
                    if (!isHeadPassable(ux, uy + 2, uz, aboveHead, stateProps(aboveHead))) {
                        return false;
                    }
                }
            } else if (ny < uy) {
                int drop = uy - ny;
                if (!vWater && drop > 3) {
                    return false;
                }
                if (!this.snapshot.isChunkLoadedAt(nwx, nwz)) {
                    return false;
                }
                for (int cy = ny + 2; cy <= uy + 1; cy++) {
                    BlockState bs = this.snapshot.getStateFast(nwx, cy, nwz);
                    long bp = stateProps(bs);
                    if (!isHeadPassable(nwx, cy, nwz, bs, bp)) {
                        if (drop == 1 && cy == uy + 1 && uStairs && isUpperCeilingStairOrSlab(nwx, cy, nwz, bs, bp)) {
                            continue;
                        }
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean classifyNode(int wx, int wy, int wz) {
            if (!this.snapshot.isChunkLoadedAt(wx, wz)) {
                return false;
            }
            BlockState feet = this.snapshot.getStateFast(wx, wy, wz);
            BlockState head = this.snapshot.getStateFast(wx, wy + 1, wz);
            BlockState support = this.snapshot.getStateFast(wx, wy - 1, wz);

            long fp = stateProps(feet);
            long hp = stateProps(head);
            long sp = stateProps(support);

            if (((fp | hp | sp) & SP_LAVA) != 0L) {
                return false;
            }
            if (((fp | hp) & SP_HAZARD_BLOCK) != 0L || (sp & SP_HAZARD_SUPPORT) != 0L) {
                return false;
            }
            if (!isHeadPassable(wx, wy + 1, wz, head, hp) || !isFeetPassable(wx, wy, wz, feet, fp)) {
                return false;
            }

            byte nodeFlags = 0;
            float cost = 1.0f;
            float topY;

            boolean feetPortal = (fp & SP_PORTAL) != 0L;
            boolean headPortal = (hp & SP_PORTAL) != 0L;
            if (feetPortal || headPortal) {
                nodeFlags |= FLAG_DOOR;
            }

            boolean feetWater = (fp & SP_WATER) != 0L;
            boolean supWater = (sp & SP_WATER) != 0L;

            if (supWater && !feetWater) {
                topY = (wy - 1) + 0.875f;
                nodeFlags |= FLAG_WATER;
                long up = stateProps(this.snapshot.getStateFast(wx, wy - 2, wz));
                boolean deep = (up & (SP_WATER | SP_AIR)) != 0L;
                cost = deep ? 6.0f : 2.2f;
            } else {
                if ((sp & SP_UNSUPPORTIVE) != 0L) {
                    return false;
                }
                double supMaxY;
                if ((sp & SP_DYNAMIC_SHAPE) == 0L) {
                    if ((sp & SP_VALID_SUPPORT) == 0L) {
                        return false;
                    }
                    supMaxY = Float.intBitsToFloat((int) (sp >>> 32));
                    if ((sp & SP_SUPPORT_STAIR_FLG) != 0L) {
                        nodeFlags |= FLAG_STAIRS;
                    }
                } else {
                    this.pos.set(wx, wy - 1, wz);
                    VoxelShape supShape = support.getCollisionShape(this.snapshot, this.pos);
                    if (supShape.isEmpty()) {
                        return false;
                    }
                    supMaxY = supShape.max(Direction.Axis.Y);
                    if (supMaxY > 1.01 || supMaxY < 0.05) {
                        return false;
                    }
                    if ((sp & SP_STAIRS_ONLY) != 0L || ((sp & SP_SLABS_ONLY) != 0L && supMaxY <= 0.55)) {
                        nodeFlags |= FLAG_STAIRS;
                    }
                }
                topY = (float) ((wy - 1) + supMaxY);

                if (feetWater) {
                    nodeFlags |= FLAG_WATER;
                    cost = 2.2f;
                } else {
                    int costCat = (int) ((sp >>> SP_SUP_COST_SHIFT) & 3L);
                    if (costCat == 1) {
                        cost = RouteCostModel.MIN_SURFACE_COST;
                    } else if (costCat == 2) {
                        cost = 3.0f;
                        nodeFlags |= FLAG_HIGH_COST;
                    } else if (costCat == 3 || (fp & SP_CARPET) != 0L) {
                        cost = 0.90f;
                    }
                }
            }

            if ((nodeFlags & FLAG_DOOR) != 0) {
                if (((fp | hp) & SP_CLOSED_IRON_DOOR) != 0L) {
                    cost = Math.max(cost, 4.0f);
                    nodeFlags |= FLAG_HIGH_COST;
                } else {
                    cost = Math.max(cost, 1.15f);
                }
            }

            if ((fp & SP_SLOW_FEET) != 0L || (hp & SP_COBWEB) != 0L) {
                cost = Math.max(cost, 6.0f);
                nodeFlags |= FLAG_HIGH_COST;
            }

            this.tmpSurfTopY = topY;
            this.tmpMoveCost = cost;
            this.tmpFlags = nodeFlags;
            return true;
        }

        private boolean isHeadPassable(int wx, int wy, int wz, BlockState bs, long props) {
            if ((props & SP_DYNAMIC_SHAPE) == 0L) {
                return (props & SP_HEAD_PASSABLE) != 0L;
            }
            if ((props & (SP_AIR | SP_PORTAL)) != 0L) {
                return true;
            }
            if ((props & SP_NON_EMPTY_FLUID) != 0L) {
                return false;
            }
            this.pos.set(wx, wy, wz);
            return bs.getCollisionShape(this.snapshot, this.pos).isEmpty();
        }

        private boolean isFeetPassable(int wx, int wy, int wz, BlockState bs, long props) {
            if ((props & SP_DYNAMIC_SHAPE) == 0L) {
                return (props & SP_FEET_PASSABLE) != 0L;
            }
            if ((props & (SP_AIR | SP_PORTAL)) != 0L) {
                return true;
            }
            if ((props & SP_NON_EMPTY_FLUID) != 0L) {
                if ((props & SP_WATER) == 0L) {
                    return false;
                }
                this.pos.set(wx, wy, wz);
                return bs.getCollisionShape(this.snapshot, this.pos).isEmpty();
            }
            this.pos.set(wx, wy, wz);
            VoxelShape shape = bs.getCollisionShape(this.snapshot, this.pos);
            if (shape.isEmpty()) {
                return true;
            }
            if ((props & SP_FENCE_WALL_BARS) != 0L) {
                return false;
            }
            return shape.max(Direction.Axis.Y) <= 0.251;
        }

        private boolean isUpperCeilingStairOrSlab(int wx, int wy, int wz, BlockState bs, long props) {
            if ((props & SP_DYNAMIC_SHAPE) == 0L) {
                return (props & SP_UPPER_CEIL_STAIR) != 0L;
            }
            if ((props & SP_STAIRS_OR_SLABS) == 0L) {
                return false;
            }
            this.pos.set(wx, wy, wz);
            VoxelShape shape = bs.getCollisionShape(this.snapshot, this.pos);
            return !shape.isEmpty() && shape.min(Direction.Axis.Y) >= 0.49;
        }

        private int findInColumn(int colIdx, int wy) {
            for (int cur = this.colHead[colIdx]; cur >= 0; cur = this.nextInCol[cur]) {
                if (this.y[cur] == wy) {
                    return cur;
                }
            }
            return -1;
        }

        private int addNode(int wx, int wy, int wz, float topY, float cost, byte nodeFlags) {
            int id = this.nodeCount++;
            int rx = wx - this.minX;
            int rz = wz - this.minZ;
            this.lx[id] = (byte) rx;
            this.y[id] = (short) wy;
            this.lz[id] = (byte) rz;
            this.surfTopY[id] = topY;
            this.moveCost[id] = cost;
            this.flags[id] = nodeFlags;
            int eBase = id << 3;
            for (int i = 0; i < 8; i++) {
                this.edges[eBase + i] = -1;
            }
            int colIdx = rz * this.side + rx;
            this.nextInCol[id] = this.colHead[colIdx];
            this.colHead[colIdx] = id;
            return id;
        }
    }
}
