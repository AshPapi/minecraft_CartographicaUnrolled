package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AtlasRegion {
    private static final int REGION_CHUNKS = AtlasRegionCache.REGION_CHUNKS;
    private static final int TILE_COUNT = REGION_CHUNKS * REGION_CHUNKS;
    private static final int TILE_PX = AtlasRegionCache.TILE_PX;
    private static final int REGION_TEX_PX = AtlasRegionCache.REGION_TEX_PX;
    private static final int OVERVIEW_MIP_LEVEL = AtlasRegionCache.OVERVIEW_MIP_LEVEL;
    private static final int OVERVIEW_PX = AtlasRegionCache.OVERVIEW_PX;
    private static final int OVERVIEW_TILE_PX = TILE_PX >> OVERVIEW_MIP_LEVEL;
    private static final int MIP_LEVELS = AtlasRegionCache.MIP_LEVELS;

    private static final int MAX_TILES_PER_FRAME = 16;
    private static final int MAX_REGION_CREATES_PER_FRAME = 8;
    private static long lastFrameNs = 0L;
    private static int remainingFrameTiles = MAX_TILES_PER_FRAME;
    private static int remainingFrameRegionCreates = MAX_REGION_CREATES_PER_FRAME;

    private record PaintedTile(
            int generation,
            int regionGen,
            int lx,
            int lz,
            byte[][] colorMips,
            byte @Nullable [][] lightMips,
            long signature,
            boolean hadInterior
    ) {
    }

    private record PreloadedRegion(
            int generation,
            int regionGen,
            byte[][] colorMips,
            byte @Nullable [][] lightMips,
            byte[][] colorTiles,
            byte[][] lightTiles,
            long[] savedSignatures,
            boolean clearedAnyStale
    ) {
    }

    public final int rx;
    public final int rz;
    public final int generation;
    public final Identifier textureId;
    private final Identifier lightTextureId;
    private final Identifier overviewTextureId;
    private final Identifier overviewLightTextureId;
    private final AtlasRegionCache.RegionTextureRef hdRef;
    private final AtlasRegionCache.RegionTextureRef overviewRef;
    private final AtlasRegionCache.RegionTextureRef hdLightRef;
    private final AtlasRegionCache.RegionTextureRef overviewLightRef;

    private volatile @Nullable MipmappedDynamicTexture texture;
    private volatile @Nullable MipmappedDynamicTexture lightTexture;
    private volatile @Nullable MipmappedDynamicTexture overviewTexture;
    private volatile @Nullable MipmappedDynamicTexture overviewLightTexture;
    private boolean hasCpuTiles;
    private final byte[][] cpuColorTiles = new byte[TILE_COUNT][];
    private final byte[][] cpuLightTiles = new byte[TILE_COUNT][];
    private volatile byte @Nullable [] overviewColorBytes;
    private volatile byte @Nullable [] overviewLightBytes;
    private volatile byte @Nullable [] pendingOverviewColor;
    private volatile byte @Nullable [] pendingOverviewLight;
    private volatile boolean hasPendingOverview;
    private volatile boolean overviewFetchInFlight = false;
    private volatile boolean overviewFetchDone = false;

    private final BitSet lightTiles = new BitSet(TILE_COUNT);
    private final BitSet dirtyTiles = new BitSet(TILE_COUNT);
    private final BitSet inFlightTiles = new BitSet(TILE_COUNT);
    private final BitSet interiorBakedTiles = new BitSet(TILE_COUNT);
    private final long[] paintedSignatures = new long[TILE_COUNT];
    private final ConcurrentLinkedQueue<PaintedTile> readyTiles = new ConcurrentLinkedQueue<>();

    private volatile int regionGen = 0;
    private volatile boolean diskChecked = false;
    private volatile boolean diskLoadInFlight = false;
    private volatile @Nullable PreloadedRegion preloadedRegion;
    private boolean imageDiskDirty = false;
    volatile long lastAccessNanos = System.nanoTime();

    public AtlasRegion(int rx, int rz, int generation) {
        this.rx = rx;
        this.rz = rz;
        this.generation = generation;
        String coord = generation + "_" + (rx < 0 ? "m" + (-rx) : rx) + "_" + (rz < 0 ? "m" + (-rz) : rz);
        this.lightTextureId = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/light_" + coord);
        this.textureId = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/region_" + coord);
        this.overviewTextureId = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/overview_" + coord);
        this.overviewLightTextureId = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/overview_light_" + coord);
        this.hdRef = new AtlasRegionCache.RegionTextureRef(this.textureId, REGION_TEX_PX);
        this.overviewRef = new AtlasRegionCache.RegionTextureRef(this.overviewTextureId, OVERVIEW_PX);
        this.hdLightRef = new AtlasRegionCache.RegionTextureRef(this.lightTextureId, REGION_TEX_PX);
        this.overviewLightRef = new AtlasRegionCache.RegionTextureRef(this.overviewLightTextureId, OVERVIEW_PX);
    }

    private static void refreshFrameBudgets() {
        long now = System.nanoTime();
        if (now - lastFrameNs > 10_000_000L) {
            lastFrameNs = now;
            remainingFrameTiles = MAX_TILES_PER_FRAME;
            remainingFrameRegionCreates = MAX_REGION_CREATES_PER_FRAME;
        }
    }

    private static int consumeFrameBudget(int desired) {
        refreshFrameBudgets();
        int allowed = Math.min(desired, remainingFrameTiles);
        remainingFrameTiles -= allowed;
        return allowed;
    }

    private static boolean tryConsumeRegionCreateBudget() {
        refreshFrameBudgets();
        if (remainingFrameRegionCreates <= 0) {
            return false;
        }
        remainingFrameRegionCreates--;
        return true;
    }

    private static boolean hasAnyNonZeroInt(int[] arr) {
        for (int v : arr) {
            if (v != 0) {
                return true;
            }
        }
        return false;
    }

    private static byte[] extractTileBytes(byte[] regionRgba, int lx, int lz) {
        byte[] tile = new byte[TILE_PX * TILE_PX * 4];
        int tileRowBytes = TILE_PX * 4;
        int regionRowBytes = REGION_TEX_PX * 4;
        int srcXBytes = lx * tileRowBytes;
        int baseY = lz * TILE_PX;
        for (int ty = 0; ty < TILE_PX; ty++) {
            System.arraycopy(
                    regionRgba,
                    (baseY + ty) * regionRowBytes + srcXBytes,
                    tile,
                    ty * tileRowBytes,
                    tileRowBytes
            );
        }
        return tile;
    }

    boolean hasHdTexture() {
        return this.texture != null;
    }

    boolean hasPendingOverviewUpload() {
        return this.hasPendingOverview;
    }

    boolean needsOverviewDiskExtract() {
        return this.overviewColorBytes == null && !this.overviewFetchDone && !this.overviewFetchInFlight;
    }

    boolean isOverviewPending() {
        return this.overviewFetchInFlight || (this.texture == null && this.overviewColorBytes == null && !this.overviewFetchDone);
    }

    boolean hasPreloadedRegion() {
        return this.preloadedRegion != null;
    }

    boolean isDiskChecked() {
        return this.diskChecked;
    }

    boolean isDiskLoadInFlight() {
        return this.diskLoadInFlight;
    }

    synchronized void markTileDirty(int lx, int lz, boolean hasLight) {
        if (lx >= 0 && lx < REGION_CHUNKS && lz >= 0 && lz < REGION_CHUNKS) {
            int bit = lz * REGION_CHUNKS + lx;
            this.dirtyTiles.set(bit);
            this.lightTiles.set(bit, hasLight);
        }
    }

    public AtlasRegionCache.@Nullable RegionTextureRef getRenderTexture(boolean needHd) {
        if (this.texture != null) {
            boolean bakeDone = isHdBakeComplete();
            if (needHd || !bakeDone) {
                this.lastAccessNanos = System.nanoTime();
            }
            if (!bakeDone) {
                scheduleBackgroundPainting(needHd ? 16 : 8);
            }
            int budget = consumeFrameBudget(needHd ? 12 : 4);
            if (budget > 0) {
                int used = uploadReadyTiles(budget);
                remainingFrameTiles += (budget - used);
            }
            return this.hdRef;
        }
        if (needHd) {
            this.lastAccessNanos = System.nanoTime();
        }
        ensureOverviewGpuTexture();
        if (!needHd) {
            if (this.overviewTexture != null) {
                return this.overviewRef;
            }
            if (!this.overviewFetchDone) {
                if (!this.overviewFetchInFlight) {
                    scheduleOverviewExtractFromDisk();
                }
                return null;
            }
            if (AtlasRegionCache.getLoadedHdRegionCount() >= AtlasRegionCache.MAX_GPU_REGIONS
                    && !AtlasRegionCache.tryEvictOneHdRegion(this, false)) {
                return null;
            }
        } else if (AtlasRegionCache.getLoadedHdRegionCount() >= AtlasRegionCache.MAX_GPU_REGIONS
                && !AtlasRegionCache.tryEvictOneHdRegion(this, true)) {
            return this.overviewTexture != null ? this.overviewRef : null;
        }
        if (ensureTextureOrScheduleLoad(true)) {
            this.lastAccessNanos = System.nanoTime();
            scheduleBackgroundPainting(needHd ? 16 : 8);
            int budget = consumeFrameBudget(needHd ? 12 : 4);
            if (budget > 0) {
                int used = uploadReadyTiles(budget);
                remainingFrameTiles += (budget - used);
            }
            return this.hdRef;
        }
        return this.overviewTexture != null ? this.overviewRef : null;
    }

    public AtlasRegionCache.@Nullable RegionTextureRef getRenderLightTexture() {
        if (this.lightTexture != null) {
            return this.hdLightRef;
        }
        ensureOverviewGpuTexture();
        return this.overviewLightTexture != null ? this.overviewLightRef : null;
    }

    public @Nullable Identifier getLightTexture() {
        AtlasRegionCache.RegionTextureRef ref = getRenderLightTexture();
        return ref == null ? null : ref.textureId();
    }

    synchronized void setOverviewFromFile(byte[] color128, byte @Nullable [] light128) {
        this.overviewColorBytes = color128;
        this.overviewLightBytes = light128;
        this.pendingOverviewColor = color128;
        this.pendingOverviewLight = light128;
        this.hasPendingOverview = true;
        this.overviewFetchDone = true;
    }

    synchronized void ensureOverviewGpuTexture() {
        if (!this.hasPendingOverview) {
            return;
        }
        byte[] color128 = this.pendingOverviewColor;
        byte[] light128 = this.pendingOverviewLight;
        this.pendingOverviewColor = null;
        this.pendingOverviewLight = null;
        this.hasPendingOverview = false;
        var textures = Minecraft.getInstance().getTextureManager();
        if (color128 != null) {
            MipmappedDynamicTexture ovTex = this.overviewTexture;
            if (ovTex != null) {
                ovTex.uploadSingleMip(color128);
            } else {
                ovTex = MipmappedDynamicTexture.createSingleMip(
                        "atlas_overview_" + this.rx + "_" + this.rz, OVERVIEW_PX, OVERVIEW_PX, color128
                );
                textures.register(this.overviewTextureId, ovTex);
                this.overviewTexture = ovTex;
            }
        } else if (this.overviewTexture != null) {
            textures.release(this.overviewTextureId);
            this.overviewTexture = null;
        }
        if (light128 != null && !this.lightTiles.isEmpty()) {
            MipmappedDynamicTexture ovLight = this.overviewLightTexture;
            if (ovLight != null) {
                ovLight.uploadSingleMip(light128);
            } else {
                ovLight = MipmappedDynamicTexture.createSingleMip(
                        "atlas_overview_light_" + this.rx + "_" + this.rz, OVERVIEW_PX, OVERVIEW_PX, light128
                );
                textures.register(this.overviewLightTextureId, ovLight);
                this.overviewLightTexture = ovLight;
            }
        } else if (this.overviewLightTexture != null) {
            textures.release(this.overviewLightTextureId);
            this.overviewLightTexture = null;
        }
    }

    synchronized void onWorldCacheBound() {
        if (this.texture == null && !this.diskLoadInFlight) {
            this.diskChecked = false;
        }
        if (this.overviewColorBytes == null && !this.overviewFetchInFlight) {
            this.overviewFetchDone = false;
        }
    }

    synchronized boolean scheduleOverviewExtractFromDisk() {
        if (this.overviewFetchInFlight || this.overviewFetchDone || this.overviewColorBytes != null) {
            return false;
        }
        String dim = AtlasRegionCache.getActiveDimension();
        Path worldDir = AtlasDiskCache.getActiveWorldDir();
        if (dim.isEmpty() || worldDir == null) {
            this.overviewFetchDone = true;
            return false;
        }
        int packHash = AtlasRegionCache.getResourcePackHash();
        int gen = this.generation;
        int rGen = this.regionGen;
        this.overviewFetchInFlight = true;
        AtlasRegionCache.PAINT_EXECUTOR.execute(() -> {
            byte[] color128 = null;
            byte[] light128 = null;
            try {
                if (gen != AtlasRegionCache.getGenerationCounter()) {
                    return;
                }
                AtlasDiskCache.LoadedRegionBytes loaded = AtlasDiskCache.loadRegionBytes(
                        worldDir, dim, this.rx, this.rz, packHash
                );
                if (loaded == null || gen != AtlasRegionCache.getGenerationCounter()) {
                    return;
                }
                color128 = AtlasMath.downsampleRgba8x8(loaded.colorBytes(), REGION_TEX_PX, false);
                if (loaded.lightBytes() != null) {
                    light128 = AtlasMath.downsampleRgba8x8(loaded.lightBytes(), REGION_TEX_PX, true);
                }
            } finally {
                synchronized (AtlasRegion.this) {
                    if (rGen == this.regionGen) {
                        this.overviewFetchInFlight = false;
                        this.overviewFetchDone = true;
                        if (color128 != null && gen == AtlasRegionCache.getGenerationCounter() && this.overviewColorBytes == null) {
                            this.overviewColorBytes = color128;
                            this.overviewLightBytes = light128;
                            this.pendingOverviewColor = color128;
                            this.pendingOverviewLight = light128;
                            this.hasPendingOverview = true;
                            AtlasRegionCache.markOverviewDiskDirty();
                        }
                    }
                }
            }
        });
        return true;
    }

    synchronized boolean ensureTextureOrScheduleLoad(boolean useFrameBudget) {
        if (this.texture != null) {
            return true;
        }
        PreloadedRegion pre = this.preloadedRegion;
        if (pre != null) {
            if (useFrameBudget && !tryConsumeRegionCreateBudget()) {
                return false;
            }
            this.preloadedRegion = null;
            if (pre.generation() == this.generation && pre.regionGen() == this.regionGen) {
                applyPreloadedRegion(pre);
                return true;
            }
        }
        if (!this.diskChecked) {
            if (!this.diskLoadInFlight) {
                scheduleAsyncDiskLoad();
            }
            return false;
        }
        if (useFrameBudget && !tryConsumeRegionCreateBudget()) {
            return false;
        }
        Arrays.fill(this.cpuColorTiles, AtlasRegionCache.EMPTY_TILE_COLOR_0);
        Arrays.fill(this.cpuLightTiles, AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0]);
        this.hasCpuTiles = true;
        MipmappedDynamicTexture created = MipmappedDynamicTexture.createWithMips(
                () -> "atlas_region_" + this.rx + "_" + this.rz,
                REGION_TEX_PX,
                REGION_TEX_PX,
                AtlasRegionCache.EMPTY_REGION_COLOR_MIPS
        );
        Minecraft.getInstance().getTextureManager().register(this.textureId, created);
        this.texture = created;
        AtlasRegionCache.incrementLoadedHdCount();
        return true;
    }

    private void scheduleAsyncDiskLoad() {
        String dim = AtlasRegionCache.getActiveDimension();
        Path worldDir = AtlasDiskCache.getActiveWorldDir();
        if (dim.isEmpty() || worldDir == null) {
            this.diskChecked = true;
            return;
        }
        int packHash = AtlasRegionCache.getResourcePackHash();
        int gen = this.generation;
        int rGen = this.regionGen;
        int baseCx = this.rx * REGION_CHUNKS;
        int baseCz = this.rz * REGION_CHUNKS;
        this.diskLoadInFlight = true;
        AtlasRegionCache.PAINT_EXECUTOR.execute(() -> {
            PreloadedRegion result = null;
            try {
                if (gen != AtlasRegionCache.getGenerationCounter()) {
                    return;
                }
                AtlasDiskCache.LoadedRegionBytes loaded = AtlasDiskCache.loadRegionBytes(
                        worldDir, dim, this.rx, this.rz, packHash
                );
                if (loaded == null || gen != AtlasRegionCache.getGenerationCounter()) {
                    return;
                }
                byte[] colorBytes = loaded.colorBytes();
                byte[] lightBytes = loaded.lightBytes();
                long[] savedSigs = loaded.tileSignatures();
                boolean clearedAnyStale = false;
                byte[][] colorTiles = new byte[TILE_COUNT][];
                byte[][] lightTilesArr = new byte[TILE_COUNT][];
                for (int bit = 0; bit < TILE_COUNT; bit++) {
                    int lz = bit / REGION_CHUNKS;
                    int lx = bit % REGION_CHUNKS;
                    if (savedSigs[bit] != 0L && AtlasRegionCache.computeTileSignature(baseCx + lx, baseCz + lz) == 0L) {
                        int ox = lx * TILE_PX;
                        int oy = lz * TILE_PX;
                        AtlasMath.clearRgbaRect(colorBytes, REGION_TEX_PX, ox, oy, TILE_PX, TILE_PX, AtlasMath.PARCHMENT_TRANSPARENT);
                        if (lightBytes != null) {
                            AtlasMath.clearRgbaRect(lightBytes, REGION_TEX_PX, ox, oy, TILE_PX, TILE_PX, 0);
                        }
                        savedSigs[bit] = 0L;
                        clearedAnyStale = true;
                    }
                    if (savedSigs[bit] == 0L) {
                        colorTiles[bit] = AtlasRegionCache.EMPTY_TILE_COLOR_0;
                        lightTilesArr[bit] = AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0];
                    } else {
                        colorTiles[bit] = extractTileBytes(colorBytes, lx, lz);
                        lightTilesArr[bit] = lightBytes != null
                                ? extractTileBytes(lightBytes, lx, lz)
                                : AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0];
                    }
                }
                byte[][] colorMips = AtlasMath.buildRgbaMipChain(colorBytes, REGION_TEX_PX, REGION_TEX_PX, MIP_LEVELS, false);
                byte[][] lightMips = lightBytes != null
                        ? AtlasMath.buildRgbaMipChain(lightBytes, REGION_TEX_PX, REGION_TEX_PX, MIP_LEVELS, true)
                        : null;
                result = new PreloadedRegion(gen, rGen, colorMips, lightMips, colorTiles, lightTilesArr, savedSigs, clearedAnyStale);
            } finally {
                synchronized (AtlasRegion.this) {
                    if (rGen == this.regionGen) {
                        this.diskLoadInFlight = false;
                        this.diskChecked = true;
                        if (result != null && gen == AtlasRegionCache.getGenerationCounter() && this.texture == null) {
                            this.preloadedRegion = result;
                        }
                    }
                }
            }
        });
    }

    private void applyPreloadedRegion(PreloadedRegion pre) {
        long[] savedSigs = pre.savedSignatures();
        int baseCx = this.rx * REGION_CHUNKS;
        int baseCz = this.rz * REGION_CHUNKS;
        boolean hasLightMips = pre.lightMips() != null;
        boolean lightOk = this.lightTiles.isEmpty() || hasLightMips;
        for (int bit = 0; bit < TILE_COUNT; bit++) {
            int lz = bit / REGION_CHUNKS;
            int lx = bit % REGION_CHUNKS;
            int cx = baseCx + lx;
            int cz = baseCz + lz;
            long savedSig = savedSigs[bit];
            if (AtlasRegionCache.hasInteriorInNeighborhood(cx, cz)) {
                this.paintedSignatures[bit] = 0L;
                this.dirtyTiles.set(bit);
                continue;
            }
            long currentSig = AtlasRegionCache.computeTileSignature(cx, cz);
            if (currentSig == 0L) {
                this.paintedSignatures[bit] = 0L;
                this.dirtyTiles.clear(bit);
            } else if (savedSig == currentSig && lightOk) {
                this.paintedSignatures[bit] = currentSig;
                this.dirtyTiles.clear(bit);
            } else {
                this.paintedSignatures[bit] = 0L;
                this.dirtyTiles.set(bit);
            }
        }
        this.imageDiskDirty = pre.clearedAnyStale();
        this.lastAccessNanos = System.nanoTime();
        System.arraycopy(pre.colorTiles(), 0, this.cpuColorTiles, 0, TILE_COUNT);
        System.arraycopy(pre.lightTiles(), 0, this.cpuLightTiles, 0, TILE_COUNT);
        this.hasCpuTiles = true;

        MipmappedDynamicTexture created = MipmappedDynamicTexture.createWithMips(
                () -> "atlas_region_" + this.rx + "_" + this.rz, REGION_TEX_PX, REGION_TEX_PX, pre.colorMips()
        );
        Minecraft.getInstance().getTextureManager().register(this.textureId, created);
        this.texture = created;
        AtlasRegionCache.incrementLoadedHdCount();
        if (!this.lightTiles.isEmpty() && pre.lightMips() != null) {
            MipmappedDynamicTexture createdLight = MipmappedDynamicTexture.createWithMips(
                    () -> "atlas_light_" + this.rx + "_" + this.rz, REGION_TEX_PX, REGION_TEX_PX, pre.lightMips()
            );
            Minecraft.getInstance().getTextureManager().register(this.lightTextureId, createdLight);
            this.lightTexture = createdLight;
        }
        if (this.overviewColorBytes == null) {
            this.overviewColorBytes = pre.colorMips()[OVERVIEW_MIP_LEVEL];
            this.overviewLightBytes = (!this.lightTiles.isEmpty() && pre.lightMips() != null)
                    ? pre.lightMips()[OVERVIEW_MIP_LEVEL]
                    : null;
            this.pendingOverviewColor = this.overviewColorBytes;
            this.pendingOverviewLight = this.overviewLightBytes;
            this.hasPendingOverview = true;
            this.overviewFetchDone = true;
            AtlasRegionCache.markOverviewDiskDirty();
        }
    }

    int scheduleBackgroundPainting(int maxSchedule) {
        if (maxSchedule <= 0) {
            return 0;
        }
        AtlasRegionCache.checkTextureEpoch();
        int[] candidateBits = null;
        int candidateCount = 0;
        boolean needLight;
        int gen;
        int rGen;
        synchronized (this) {
            if (this.dirtyTiles.isEmpty()) {
                return 0;
            }
            for (int bit = this.dirtyTiles.nextSetBit(0);
                 bit >= 0 && candidateCount < maxSchedule;
                 bit = this.dirtyTiles.nextSetBit(bit + 1)) {
                if (this.inFlightTiles.get(bit)) {
                    continue;
                }
                if (candidateBits == null) {
                    candidateBits = new int[Math.min(maxSchedule, TILE_COUNT)];
                }
                this.dirtyTiles.clear(bit);
                this.inFlightTiles.set(bit);
                candidateBits[candidateCount++] = bit;
            }
            if (candidateCount == 0) {
                return 0;
            }
            needLight = !this.lightTiles.isEmpty();
            gen = this.generation;
            rGen = this.regionGen;
        }

        BlockTopTextureCache.warmFluids();
        int baseCx = this.rx * REGION_CHUNKS;
        int baseCz = this.rz * REGION_CHUNKS;
        for (int i = 0; i < candidateCount; i++) {
            int tileBit = candidateBits[i];
            int lz = tileBit / REGION_CHUNKS;
            int lx = tileBit % REGION_CHUNKS;
            int cx = baseCx + lx;
            int cz = baseCz + lz;
            AtlasChunkTerrain terrainSnapshot = AtlasRegionCache.getChunk(cx, cz);
            if (terrainSnapshot != null && terrainSnapshot.hasBlockStates()) {
                BlockTopTextureCache.warmPalette(terrainSnapshot.blockPalette());
            }
            AtlasRegionCache.PAINT_EXECUTOR.execute(() -> {
                byte[][] colorMips = null;
                byte[][] lightMips = null;
                long tileSig = 0L;
                boolean hadInterior = false;
                boolean completed = false;
                try {
                    if (gen != AtlasRegionCache.getGenerationCounter() || rGen != this.regionGen) {
                        return;
                    }
                    boolean interiorBefore = AtlasRegionCache.hasInteriorInNeighborhood(cx, cz);
                    long sigBefore = interiorBefore ? 0L : AtlasRegionCache.computeTileSignature(cx, cz);
                    AtlasChunkTerrain latest = AtlasRegionCache.getChunk(cx, cz);
                    AtlasChunkTerrain terrain = terrainSnapshot != null ? terrainSnapshot : latest;
                    AtlasRegionCache.WorkerScratch scratch = AtlasRegionCache.WORKER_SCRATCH.get();
                    int[] colorPixels = scratch.colorPixels;
                    int[] lightPixels = needLight ? scratch.lightPixels : null;
                    if (lightPixels != null) {
                        Arrays.fill(lightPixels, 0);
                    }
                    AtlasCartographyPainter.paintChunkTileToArrays(
                            colorPixels,
                            lightPixels,
                            cx,
                            cz,
                            terrain,
                            AtlasRegionCache::getChunk
                    );
                    colorMips = AtlasMath.argbToRgbaMipChain(colorPixels, TILE_PX, TILE_PX, MIP_LEVELS, false);
                    if (lightPixels != null && hasAnyNonZeroInt(lightPixels)) {
                        lightMips = AtlasMath.argbToRgbaMipChain(lightPixels, TILE_PX, TILE_PX, MIP_LEVELS, true);
                    }
                    boolean interiorAfter = AtlasRegionCache.hasInteriorInNeighborhood(cx, cz);
                    hadInterior = interiorBefore || interiorAfter;
                    tileSig = (!hadInterior && terrain == latest && sigBefore != 0L
                            && sigBefore == AtlasRegionCache.computeTileSignature(cx, cz))
                            ? sigBefore
                            : 0L;
                    completed = true;
                } finally {
                    synchronized (AtlasRegion.this) {
                        if (rGen == this.regionGen) {
                            this.inFlightTiles.clear(tileBit);
                            if (completed && gen == AtlasRegionCache.getGenerationCounter()) {
                                this.readyTiles.add(new PaintedTile(gen, rGen, lx, lz, colorMips, lightMips, tileSig, hadInterior));
                            }
                        }
                    }
                }
            });
        }
        return candidateCount;
    }

    int uploadReadyTiles(int maxTiles) {
        MipmappedDynamicTexture hdTex = this.texture;
        if (hdTex == null || maxTiles <= 0) {
            return 0;
        }
        synchronized (this) {
            if (!this.lightTiles.isEmpty() && this.lightTexture == null) {
                MipmappedDynamicTexture createdLight = MipmappedDynamicTexture.createWithMips(
                        () -> "atlas_light_" + this.rx + "_" + this.rz,
                        REGION_TEX_PX,
                        REGION_TEX_PX,
                        AtlasRegionCache.EMPTY_REGION_LIGHT_MIPS
                );
                Minecraft.getInstance().getTextureManager().register(this.lightTextureId, createdLight);
                this.lightTexture = createdLight;
            } else if (this.lightTiles.isEmpty() && this.lightTexture != null
                    && this.readyTiles.isEmpty() && this.inFlightTiles.isEmpty()) {
                Minecraft.getInstance().getTextureManager().release(this.lightTextureId);
                this.lightTexture = null;
                Arrays.fill(this.cpuLightTiles, AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0]);
            }
        }
        int uploaded = 0;
        boolean updatedOutdoorOverview = false;
        PaintedTile tile;
        while (uploaded < maxTiles && (tile = this.readyTiles.poll()) != null) {
            if (tile.generation() != this.generation || tile.regionGen() != this.regionGen) {
                continue;
            }
            int lx = tile.lx();
            int lz = tile.lz();
            int originX = lx * TILE_PX;
            int originY = lz * TILE_PX;
            byte[][] colorMips = tile.colorMips();
            byte[][] lightMips = tile.lightMips();
            int bit = lz * REGION_CHUNKS + lx;

            hdTex.uploadAlignedTileMips(originX, originY, TILE_PX, TILE_PX, colorMips);
            MipmappedDynamicTexture ltTex = this.lightTexture;
            if (ltTex != null) {
                ltTex.uploadAlignedTileMips(
                        originX, originY, TILE_PX, TILE_PX,
                        lightMips != null ? lightMips : AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS
                );
            }

            synchronized (this) {
                this.interiorBakedTiles.set(bit, tile.hadInterior());
                this.paintedSignatures[bit] = tile.hadInterior() ? 0L : tile.signature();
                if (!tile.hadInterior()) {
                    if (this.hasCpuTiles) {
                        this.cpuColorTiles[bit] = colorMips[0];
                        this.cpuLightTiles[bit] = lightMips != null
                                ? lightMips[0]
                                : AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0];
                    }
                    byte[] ovColor = this.overviewColorBytes;
                    if (ovColor == null) {
                        ovColor = AtlasRegionCache.EMPTY_OVERVIEW_COLOR.clone();
                        this.overviewColorBytes = ovColor;
                    }
                    AtlasMath.copyRgbaRect(
                            colorMips[OVERVIEW_MIP_LEVEL], OVERVIEW_TILE_PX,
                            ovColor, OVERVIEW_PX,
                            lx * OVERVIEW_TILE_PX, lz * OVERVIEW_TILE_PX,
                            OVERVIEW_TILE_PX, OVERVIEW_TILE_PX
                    );

                    byte[] tileLightOv = lightMips != null
                            ? lightMips[OVERVIEW_MIP_LEVEL]
                            : AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[OVERVIEW_MIP_LEVEL];
                    if (!this.lightTiles.isEmpty()) {
                        byte[] ovLight = this.overviewLightBytes;
                        if (ovLight == null && lightMips != null) {
                            ovLight = new byte[OVERVIEW_PX * OVERVIEW_PX * 4];
                            this.overviewLightBytes = ovLight;
                        }
                        if (ovLight != null) {
                            AtlasMath.copyRgbaRect(
                                    tileLightOv, OVERVIEW_TILE_PX,
                                    ovLight, OVERVIEW_PX,
                                    lx * OVERVIEW_TILE_PX, lz * OVERVIEW_TILE_PX,
                                    OVERVIEW_TILE_PX, OVERVIEW_TILE_PX
                            );
                        }
                    } else {
                        this.overviewLightBytes = null;
                    }
                    if (tile.signature() != 0L) {
                        this.imageDiskDirty = true;
                    }
                    updatedOutdoorOverview = true;
                }
            }
            uploaded++;
        }
        synchronized (this) {
            if (updatedOutdoorOverview && this.overviewColorBytes != null) {
                this.pendingOverviewColor = this.overviewColorBytes;
                this.pendingOverviewLight = !this.lightTiles.isEmpty() ? this.overviewLightBytes : null;
                this.hasPendingOverview = true;
                this.overviewFetchDone = true;
                AtlasRegionCache.markOverviewDiskDirty();
            }
            if (this.lightTiles.isEmpty() && this.lightTexture != null
                    && this.readyTiles.isEmpty() && this.inFlightTiles.isEmpty()) {
                Minecraft.getInstance().getTextureManager().release(this.lightTextureId);
                this.lightTexture = null;
                Arrays.fill(this.cpuLightTiles, AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0]);
                this.overviewLightBytes = null;
            }
        }
        return uploaded;
    }

    synchronized void saveImageToDiskIfDirty(String dimensionId, int resourcePackHash) {
        if (!this.imageDiskDirty || this.texture == null || !this.hasCpuTiles) {
            return;
        }
        boolean hasAnySignature = false;
        for (long sig : this.paintedSignatures) {
            if (sig != 0L) {
                hasAnySignature = true;
                break;
            }
        }
        if (!hasAnySignature) {
            this.imageDiskDirty = false;
            return;
        }
        if (AtlasDiskCache.getActiveWorldDir() == null) {
            return;
        }
        byte[][] colorTilesSnapshot = this.cpuColorTiles.clone();
        byte[][] lightTilesSnapshot = !this.lightTiles.isEmpty() ? this.cpuLightTiles.clone() : null;
        if (AtlasDiskCache.saveRegionTextureAsync(
                dimensionId,
                this.rx,
                this.rz,
                resourcePackHash,
                this.paintedSignatures.clone(),
                colorTilesSnapshot,
                lightTilesSnapshot
        )) {
            this.imageDiskDirty = false;
        }
    }

    synchronized AtlasDiskCache.@Nullable OverviewEntry toOverviewEntry() {
        if (this.overviewColorBytes == null) {
            return null;
        }
        return new AtlasDiskCache.OverviewEntry(
                this.rx,
                this.rz,
                this.overviewColorBytes.clone(),
                this.overviewLightBytes != null ? this.overviewLightBytes.clone() : null
        );
    }

    synchronized boolean isReadyForBackgroundSave() {
        return this.imageDiskDirty
                && this.texture != null
                && this.hasCpuTiles
                && this.dirtyTiles.isEmpty()
                && this.inFlightTiles.isEmpty()
                && this.readyTiles.isEmpty()
                && this.interiorBakedTiles.isEmpty();
    }

    synchronized void resetSavedImageTracking() {
        this.regionGen++;
        this.readyTiles.clear();
        this.inFlightTiles.clear();
        this.interiorBakedTiles.clear();
        this.diskLoadInFlight = false;
        this.overviewFetchInFlight = false;
        Arrays.fill(this.paintedSignatures, 0L);
        if (this.hasCpuTiles) {
            Arrays.fill(this.cpuColorTiles, AtlasRegionCache.EMPTY_TILE_COLOR_0);
            Arrays.fill(this.cpuLightTiles, AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0]);
        }
        this.imageDiskDirty = false;
        this.preloadedRegion = null;
        this.diskChecked = true;
        this.overviewColorBytes = null;
        this.overviewLightBytes = null;
        this.pendingOverviewColor = null;
        this.pendingOverviewLight = null;
        this.hasPendingOverview = false;
        this.overviewFetchDone = true;
        releaseOverviewTextures();
    }

    private void releaseOverviewTextures() {
        var textures = Minecraft.getInstance().getTextureManager();
        if (this.overviewTexture != null) {
            textures.release(this.overviewTextureId);
            this.overviewTexture = null;
        }
        if (this.overviewLightTexture != null) {
            textures.release(this.overviewLightTextureId);
            this.overviewLightTexture = null;
        }
    }

    private synchronized void disposeHdOnly() {
        this.regionGen++;
        this.diskChecked = false;
        this.diskLoadInFlight = false;
        this.preloadedRegion = null;
        this.readyTiles.clear();
        this.inFlightTiles.clear();
        this.interiorBakedTiles.clear();
        Arrays.fill(this.paintedSignatures, 0L);
        this.imageDiskDirty = false;
        this.hasCpuTiles = false;
        Arrays.fill(this.cpuColorTiles, null);
        Arrays.fill(this.cpuLightTiles, null);
        var textures = Minecraft.getInstance().getTextureManager();
        if (this.lightTexture != null) {
            textures.release(this.lightTextureId);
            this.lightTexture = null;
        }
        if (this.texture != null) {
            textures.release(this.textureId);
            this.texture = null;
            AtlasRegionCache.decrementLoadedHdCount();
        }
    }

    synchronized void dispose() {
        disposeHdOnly();
        this.overviewColorBytes = null;
        this.overviewLightBytes = null;
        this.pendingOverviewColor = null;
        this.pendingOverviewLight = null;
        this.hasPendingOverview = false;
        this.overviewFetchInFlight = false;
        this.overviewFetchDone = false;
        releaseOverviewTextures();
    }

    synchronized void evictGpuTextures() {
        String dim = AtlasRegionCache.getActiveDimension();
        if (!dim.isEmpty()) {
            saveImageToDiskIfDirty(dim, AtlasRegionCache.getResourcePackHash());
        }
        if (this.overviewColorBytes == null) {
            this.overviewColorBytes = AtlasRegionCache.EMPTY_OVERVIEW_COLOR.clone();
            this.pendingOverviewColor = this.overviewColorBytes;
            this.hasPendingOverview = true;
            this.overviewFetchDone = true;
        }
        ensureOverviewGpuTexture();
        disposeHdOnly();
        int baseCx = this.rx * REGION_CHUNKS;
        int baseCz = this.rz * REGION_CHUNKS;
        for (int lz = 0; lz < REGION_CHUNKS; lz++) {
            for (int lx = 0; lx < REGION_CHUNKS; lx++) {
                long key = ChunkPos.pack(baseCx + lx, baseCz + lz);
                if (AtlasRegionCache.hasAnyChunkAt(key)) {
                    markTileDirty(lx, lz, AtlasRegionCache.hasChunkLight(key));
                }
            }
        }
    }

    synchronized boolean isHdBakeComplete() {
        return this.texture != null
                && this.preloadedRegion == null
                && !this.diskLoadInFlight
                && this.dirtyTiles.isEmpty()
                && this.inFlightTiles.isEmpty()
                && this.readyTiles.isEmpty();
    }

    synchronized boolean hasPendingWork() {
        return this.preloadedRegion != null || !this.dirtyTiles.isEmpty() || !this.readyTiles.isEmpty();
    }
}
