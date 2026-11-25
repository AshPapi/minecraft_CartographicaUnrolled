package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.client.event.ClientEvents;
import com.cartographica_unrolled.client.render.AtlasRenderPipelines;
import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.util.AtlasMath;
import com.cartographica_unrolled.util.StripedLong2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasRegionCache {
    public static final int REGION_CHUNKS = 8;
    public static final int TILE_PX = 128;
    public static final int REGION_PX = REGION_CHUNKS * TILE_PX;
    public static final int REGION_TEX_PX = REGION_PX;
    public static final int OVERVIEW_MIP_LEVEL = 3;
    public static final int OVERVIEW_PX = REGION_TEX_PX >> OVERVIEW_MIP_LEVEL;
    static final int MIP_LEVELS = 5;
    static final int MAX_GPU_REGIONS = 28;
    private static final int MAX_BG_PRELOAD_REGIONS = 16;

    static final byte[][] EMPTY_REGION_COLOR_MIPS;
    static final byte[][] EMPTY_REGION_LIGHT_MIPS;
    static final byte[] EMPTY_TILE_COLOR_0;
    static final byte[][] EMPTY_TILE_LIGHT_MIPS;
    static final byte[] EMPTY_OVERVIEW_COLOR;

    static {
        byte[] baseColor = new byte[REGION_TEX_PX * REGION_TEX_PX * 4];
        AtlasMath.fillRgbaBytes(baseColor, AtlasMath.PARCHMENT_TRANSPARENT);
        EMPTY_REGION_COLOR_MIPS = AtlasMath.buildRgbaMipChain(baseColor, REGION_TEX_PX, REGION_TEX_PX, MIP_LEVELS, false);

        EMPTY_TILE_COLOR_0 = new byte[TILE_PX * TILE_PX * 4];
        AtlasMath.fillRgbaBytes(EMPTY_TILE_COLOR_0, AtlasMath.PARCHMENT_TRANSPARENT);

        byte[] baseLight = new byte[REGION_TEX_PX * REGION_TEX_PX * 4];
        EMPTY_REGION_LIGHT_MIPS = AtlasMath.buildRgbaMipChain(baseLight, REGION_TEX_PX, REGION_TEX_PX, MIP_LEVELS, true);

        byte[] tileLight = new byte[TILE_PX * TILE_PX * 4];
        EMPTY_TILE_LIGHT_MIPS = AtlasMath.buildRgbaMipChain(tileLight, TILE_PX, TILE_PX, MIP_LEVELS, true);

        EMPTY_OVERVIEW_COLOR = EMPTY_REGION_COLOR_MIPS[OVERVIEW_MIP_LEVEL];
    }

    public record RegionTextureRef(Identifier textureId, int texPx) {
    }

    static final class WorkerScratch {
        final int[] colorPixels = new int[TILE_PX * TILE_PX];
        final int[] lightPixels = new int[TILE_PX * TILE_PX];
    }

    static final ThreadLocal<WorkerScratch> WORKER_SCRATCH = ThreadLocal.withInitial(WorkerScratch::new);
    private static final AtomicInteger WORKER_ID = new AtomicInteger(1);
    static final ExecutorService PAINT_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)),
            r -> {
                Thread t = new Thread(r, "Cartographica-TilePainter-" + WORKER_ID.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
    );

    private static final StripedLong2ObjectMap<AtlasChunkTerrain> CHUNKS = new StripedLong2ObjectMap<>(2048);
    private static final StripedLong2ObjectMap<AtlasChunkTerrain> INTERIOR_OVERLAY = new StripedLong2ObjectMap<>(64);
    private static final LongOpenHashSet OUTDOOR_LIGHT_CHUNKS = new LongOpenHashSet(512);
    private static final LongOpenHashSet INTERIOR_LIGHT_CHUNKS = new LongOpenHashSet(64);
    private static final LongOpenHashSet CORRUPT_CHUNKS = new LongOpenHashSet();
    private static final Long2ObjectOpenHashMap<AtlasRegion> REGIONS = new Long2ObjectOpenHashMap<>();
    private static final List<AtlasRegion> EVICT_SCRATCH = new ArrayList<>(MAX_GPU_REGIONS);

    private static final AtomicInteger TERRAIN_MUTATION_EPOCH = new AtomicInteger(1);

    private static int loadedHdRegionCount = 0;
    private static int evictTickCounter = 0;
    private static int bgSaveTickCounter = 0;
    private static int overviewSaveTickCounter = 0;
    private static String activeDimension = "";
    private static long activeWorldCacheId = 0L;
    private static boolean diskLoadedForActiveDim = false;
    private static volatile int generationCounter = 0;
    private static volatile int lastTextureEpoch = -1;
    private static boolean diskDirty = false;
    private static volatile boolean overviewDiskDirty = false;

    private AtlasRegionCache() {
    }

    public static int getTerrainEpoch() {
        return TERRAIN_MUTATION_EPOCH.get();
    }

    static int getGenerationCounter() {
        return generationCounter;
    }

    static int getLoadedHdRegionCount() {
        return loadedHdRegionCount;
    }

    static void incrementLoadedHdCount() {
        loadedHdRegionCount++;
    }

    static void decrementLoadedHdCount() {
        loadedHdRegionCount = Math.max(0, loadedHdRegionCount - 1);
    }

    static void markOverviewDiskDirty() {
        overviewDiskDirty = true;
    }

    static void checkTextureEpoch() {
        int epoch = BlockTopTextureCache.ensureFreshEpoch();
        if (epoch != lastTextureEpoch) {
            lastTextureEpoch = epoch;
            repaintAll();
        }
    }

    static boolean hasAnyChunkAt(long key) {
        return CHUNKS.containsKey(key) || INTERIOR_OVERLAY.containsKey(key);
    }

    static boolean hasChunkLight(long key) {
        if (!INTERIOR_OVERLAY.isEmpty() && INTERIOR_OVERLAY.containsKey(key)) {
            return INTERIOR_LIGHT_CHUNKS.contains(key);
        }
        return OUTDOOR_LIGHT_CHUNKS.contains(key);
    }

    private static boolean computeChunkHasLight(AtlasChunkTerrain terrain) {
        for (long column : terrain.columns()) {
            if (AtlasChunkTerrain.unpackBlockLight(column) > 0
                    || (AtlasChunkTerrain.unpackFlags(column)
                            & (AtlasChunkTerrain.FLAG_LAVA | AtlasChunkTerrain.FLAG_GLOW)) != 0) {
                return true;
            }
        }
        return false;
    }

    static boolean tryEvictOneHdRegion(AtlasRegion exclude, boolean allowInProgress) {
        long now = System.nanoTime();
        AtlasRegion oldestFinished = null;
        long oldestFinishedAccess = Long.MAX_VALUE;
        AtlasRegion oldestAny = null;
        long oldestAnyAccess = Long.MAX_VALUE;
        for (AtlasRegion r : REGIONS.values()) {
            if (r == exclude || !r.hasHdTexture()) {
                continue;
            }
            if (r.isHdBakeComplete() && r.lastAccessNanos < oldestFinishedAccess) {
                oldestFinishedAccess = r.lastAccessNanos;
                oldestFinished = r;
            }
            if (allowInProgress && r.lastAccessNanos < oldestAnyAccess) {
                oldestAnyAccess = r.lastAccessNanos;
                oldestAny = r;
            }
        }
        if (oldestFinished != null && (!allowInProgress || now - oldestFinishedAccess > 50_000_000L)) {
            oldestFinished.evictGpuTextures();
            return true;
        }
        if (allowInProgress && oldestAny != null && now - oldestAnyAccess > 50_000_000L) {
            oldestAny.evictGpuTextures();
            return true;
        }
        return false;
    }

    public static String getActiveDimension() {
        return activeDimension;
    }

    public static void bindWorldAndDimension(long worldCacheId, String dimensionId) {
        if (worldCacheId != 0L && activeWorldCacheId != 0L && activeWorldCacheId != worldCacheId) {
            flushDiskCache();
            clearAll();
            AtlasDiskCache.clearWorldKey();
            ClientEvents.onRemoteWorldSwitch();
        }
        if (worldCacheId != 0L) {
            activeWorldCacheId = worldCacheId;
            AtlasDiskCache.bindServerWorld(worldCacheId);
        }
        switchOrLoadDimension(dimensionId);
    }

    public static void ensureDimension(String dimensionId) {
        switchOrLoadDimension(dimensionId);
    }

    private static void switchOrLoadDimension(String dimensionId) {
        if (!dimensionId.equals(activeDimension)) {
            flushDiskCache();
            clearDimensionState();
            activeDimension = dimensionId;
            if (!dimensionId.isEmpty() && AtlasDiskCache.getActiveWorldDir() != null) {
                diskLoadedForActiveDim = true;
                triggerAsyncDimensionLoad(dimensionId);
            }
        } else if (!dimensionId.isEmpty() && !diskLoadedForActiveDim && AtlasDiskCache.getActiveWorldDir() != null) {
            diskLoadedForActiveDim = true;
            for (AtlasRegion region : REGIONS.values()) {
                region.onWorldCacheBound();
            }
            triggerAsyncDimensionLoad(dimensionId);
        }
    }

    private static void triggerAsyncDimensionLoad(String dimensionId) {
        int loadGen = generationCounter;
        int packHash = getResourcePackHash();
        AtlasDiskCache.loadDimensionAsync(dimensionId, packHash, (loadedChunks, overviews) ->
                Minecraft.getInstance().execute(() -> applyAsyncDiskDimensionLoad(loadGen, loadedChunks, overviews))
        );
    }

    private static void applyAsyncDiskDimensionLoad(
            int expectedGen,
            Long2ObjectOpenHashMap<AtlasChunkTerrain> loadedChunks,
            List<AtlasDiskCache.OverviewEntry> overviews
    ) {
        if (expectedGen != generationCounter) {
            return;
        }
        for (Long2ObjectMap.Entry<AtlasChunkTerrain> entry : Long2ObjectMaps.fastIterable(loadedChunks)) {
            long key = entry.getLongKey();
            putChunkFromDiskIfAbsent(ChunkPos.getX(key), ChunkPos.getZ(key), entry.getValue());
        }
        for (AtlasDiskCache.OverviewEntry entry : overviews) {
            long rKey = ChunkPos.pack(entry.rx(), entry.rz());
            AtlasRegion region = REGIONS.get(rKey);
            if (region == null) {
                region = new AtlasRegion(entry.rx(), entry.rz(), generationCounter);
                REGIONS.put(rKey, region);
            }
            region.setOverviewFromFile(entry.colorBytes(), entry.lightBytes());
        }
    }

    private static void flushOverviewDiskCache(String dimensionId, int packHash) {
        if (!overviewDiskDirty || dimensionId.isEmpty() || AtlasDiskCache.getActiveWorldDir() == null) {
            return;
        }
        List<AtlasDiskCache.OverviewEntry> entries = new ArrayList<>(REGIONS.size());
        for (AtlasRegion region : REGIONS.values()) {
            AtlasDiskCache.OverviewEntry e = region.toOverviewEntry();
            if (e != null) {
                entries.add(e);
            }
        }
        if (entries.isEmpty() || AtlasDiskCache.saveOverviewAsync(dimensionId, packHash, entries)) {
            overviewDiskDirty = false;
        }
    }

    public static void flushDiskCache() {
        if (!activeDimension.isEmpty() && AtlasDiskCache.getActiveWorldDir() != null) {
            if (diskDirty && !CHUNKS.isEmpty()) {
                Long2ObjectOpenHashMap<AtlasChunkTerrain> snapshot = CHUNKS.snapshot();
                if (AtlasDiskCache.saveAsync(activeDimension, snapshot)) {
                    diskDirty = false;
                }
            }
            int packHash = getResourcePackHash();
            for (AtlasRegion region : REGIONS.values()) {
                region.saveImageToDiskIfDirty(activeDimension, packHash);
            }
            flushOverviewDiskCache(activeDimension, packHash);
        }
    }

    public static void invalidateDiskRegionImages() {
        if (!activeDimension.isEmpty()) {
            AtlasDiskCache.clearRegionTextures(activeDimension);
        }
        overviewDiskDirty = false;
    }

    static int getResourcePackHash() {
        Minecraft mc = Minecraft.getInstance();
        int h = 17;
        if (mc != null && mc.getResourcePackRepository() != null) {
            for (String id : mc.getResourcePackRepository().getSelectedIds()) {
                h = 31 * h + id.hashCode();
            }
        }
        return h;
    }

    static boolean hasInteriorInNeighborhood(int cx, int cz) {
        if (INTERIOR_OVERLAY.isEmpty()) {
            return false;
        }
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (INTERIOR_OVERLAY.containsKey(ChunkPos.pack(cx + dx, cz + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    static long computeTileSignature(int cx, int cz) {
        AtlasChunkTerrain center = CHUNKS.get(ChunkPos.pack(cx, cz));
        if (center == null) {
            return 0L;
        }
        long h = 0x9E3779B97F4A7C15L;
        int presenceMask = 0;
        int bit = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                AtlasChunkTerrain c = (dx == 0 && dz == 0) ? center : CHUNKS.get(ChunkPos.pack(cx + dx, cz + dz));
                if (c != null) {
                    presenceMask |= (1 << bit);
                    h ^= (c.persistentHash() & 0xFFFFFFFFL);
                    h *= 0x100000001B3L;
                }
                bit++;
            }
        }
        h ^= (presenceMask & 0xFFFFFFFFL) << 32;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        return h == 0L ? 1L : h;
    }

    private static void clearDimensionState() {
        generationCounter++;
        TERRAIN_MUTATION_EPOCH.incrementAndGet();
        for (AtlasRegion region : REGIONS.values()) {
            region.dispose();
        }
        REGIONS.clear();
        EVICT_SCRATCH.clear();
        loadedHdRegionCount = 0;
        CHUNKS.clear();
        INTERIOR_OVERLAY.clear();
        OUTDOOR_LIGHT_CHUNKS.clear();
        INTERIOR_LIGHT_CHUNKS.clear();
        CORRUPT_CHUNKS.clear();
        activeDimension = "";
        diskLoadedForActiveDim = false;
        diskDirty = false;
        overviewDiskDirty = false;
        AtlasRenderPipelines.clearRenderTypeCache();
    }

    public static void clearAll() {
        clearDimensionState();
        activeWorldCacheId = 0L;
    }

    public static Map<Long, AtlasRegion> getRegions() {
        return REGIONS;
    }

    public static int getRegionCount() {
        return REGIONS.size();
    }

    public static @Nullable AtlasRegion getRegion(int rx, int rz) {
        return REGIONS.get(ChunkPos.pack(rx, rz));
    }

    public static void processBackgroundBudget(int maxTiles) {
        Minecraft mc = Minecraft.getInstance();
        if (maxTiles <= 0 || mc.player == null || AtlasDiskCache.getActiveWorldDir() == null) {
            return;
        }
        int playerRx = Math.floorDiv(mc.player.chunkPosition().x(), REGION_CHUNKS);
        int playerRz = Math.floorDiv(mc.player.chunkPosition().z(), REGION_CHUNKS);
        boolean checkBgSave = ++bgSaveTickCounter >= 20 && !activeDimension.isEmpty();
        if (checkBgSave) {
            bgSaveTickCounter = 0;
        }
        boolean checkOverviewSave = ++overviewSaveTickCounter >= 60 && overviewDiskDirty && !activeDimension.isEmpty();
        if (checkOverviewSave) {
            overviewSaveTickCounter = 0;
        }
        boolean checkEvict = ++evictTickCounter >= 100 && loadedHdRegionCount > MAX_BG_PRELOAD_REGIONS;
        if (evictTickCounter >= 100) {
            evictTickCounter = 0;
        }

        int uploadedOverviews = 0;
        int scheduledOverviewExtracts = 0;
        int remaining = maxTiles;
        int uploadedPreloaded = 0;
        int scheduledDiskLoads = 0;
        boolean savedOneImage = false;
        boolean anyOverviewPending = false;
        int packHash = (checkBgSave || checkOverviewSave) ? getResourcePackHash() : 0;

        if (checkEvict) {
            EVICT_SCRATCH.clear();
        }

        for (AtlasRegion region : REGIONS.values()) {
            boolean hasHd = region.hasHdTexture();
            if (!hasHd) {
                if (region.hasPendingOverviewUpload() && uploadedOverviews < 12) {
                    region.ensureOverviewGpuTexture();
                    uploadedOverviews++;
                } else if (region.needsOverviewDiskExtract() && scheduledOverviewExtracts < 4) {
                    if (region.scheduleOverviewExtractFromDisk()) {
                        scheduledOverviewExtracts++;
                    }
                }
            }

            if (remaining > 0 && region.hasPendingWork()) {
                if (!hasHd) {
                    if (loadedHdRegionCount < MAX_BG_PRELOAD_REGIONS
                            && Math.abs((long) region.rx - playerRx) <= 2
                            && Math.abs((long) region.rz - playerRz) <= 2) {
                        if (region.hasPreloadedRegion() || region.isDiskChecked()) {
                            if (uploadedPreloaded < 2 && region.ensureTextureOrScheduleLoad(false)) {
                                uploadedPreloaded++;
                                hasHd = true;
                            }
                        } else if (scheduledDiskLoads < 4 && !region.isDiskLoadInFlight()) {
                            region.ensureTextureOrScheduleLoad(false);
                            scheduledDiskLoads++;
                        }
                    }
                }
                if (hasHd && region.hasPendingWork()) {
                    region.scheduleBackgroundPainting(remaining * 2);
                    remaining -= region.uploadReadyTiles(remaining);
                }
            }

            if (checkBgSave && !savedOneImage && region.isReadyForBackgroundSave()) {
                region.saveImageToDiskIfDirty(activeDimension, packHash);
                savedOneImage = true;
            }
            if (checkOverviewSave && !anyOverviewPending && region.isOverviewPending()) {
                anyOverviewPending = true;
            }
            if (checkEvict && region.hasHdTexture()) {
                EVICT_SCRATCH.add(region);
            }
        }

        if (checkOverviewSave && !anyOverviewPending) {
            flushOverviewDiskCache(activeDimension, packHash);
        }
        if (checkEvict && EVICT_SCRATCH.size() > MAX_BG_PRELOAD_REGIONS) {
            long now = System.nanoTime();
            long staleThreshold = 15_000_000_000L;
            EVICT_SCRATCH.sort(Comparator.comparingLong(r -> r.lastAccessNanos));
            int toEvict = Math.min(4, EVICT_SCRATCH.size() - MAX_BG_PRELOAD_REGIONS);
            for (AtlasRegion r : EVICT_SCRATCH) {
                if (toEvict <= 0) {
                    break;
                }
                if (Math.abs((long) r.rx - playerRx) <= 2 && Math.abs((long) r.rz - playerRz) <= 2) {
                    continue;
                }
                if (now - r.lastAccessNanos < staleThreshold || !r.isHdBakeComplete()) {
                    continue;
                }
                r.evictGpuTextures();
                toEvict--;
            }
            EVICT_SCRATCH.clear();
        }
    }

    public static @Nullable AtlasChunkTerrain getChunk(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        if (!INTERIOR_OVERLAY.isEmpty()) {
            AtlasChunkTerrain interior = INTERIOR_OVERLAY.get(key);
            if (interior != null) {
                return interior;
            }
        }
        return CHUNKS.get(key);
    }

    public static @Nullable AtlasChunkTerrain getOutdoorChunk(int cx, int cz) {
        return CHUNKS.get(ChunkPos.pack(cx, cz));
    }

    public static @Nullable AtlasChunkTerrain getInteriorChunk(int cx, int cz) {
        return INTERIOR_OVERLAY.isEmpty() ? null : INTERIOR_OVERLAY.get(ChunkPos.pack(cx, cz));
    }

    public static boolean needsResample(int cx, int cz) {
        return CORRUPT_CHUNKS.contains(ChunkPos.pack(cx, cz));
    }

    public static boolean putChunk(int cx, int cz, AtlasChunkTerrain terrain) {
        long key = ChunkPos.pack(cx, cz);
        AtlasChunkTerrain prev = CHUNKS.get(key);
        if (prev != null && prev.equalsContent(terrain)) {
            return false;
        }
        CHUNKS.put(key, terrain);
        updateLightSet(OUTDOOR_LIGHT_CHUNKS, key, terrain);
        diskDirty = true;
        updateCorruptStatus(key, terrain);
        markChunkAndNeighborsDirty(cx, cz);
        return true;
    }

    private static void putChunkFromDiskIfAbsent(int cx, int cz, AtlasChunkTerrain terrain) {
        long key = ChunkPos.pack(cx, cz);
        if (CHUNKS.putIfAbsent(key, terrain) == null) {
            updateLightSet(OUTDOOR_LIGHT_CHUNKS, key, terrain);
            updateCorruptStatus(key, terrain);
            markChunkAndNeighborsDirty(cx, cz);
        }
    }

    private static void updateLightSet(LongOpenHashSet set, long key, AtlasChunkTerrain terrain) {
        if (computeChunkHasLight(terrain)) {
            set.add(key);
        } else {
            set.remove(key);
        }
    }

    private static void updateCorruptStatus(long key, AtlasChunkTerrain terrain) {
        CORRUPT_CHUNKS.remove(key);
        if (!terrain.hasBlockStates()) {
            CORRUPT_CHUNKS.add(key);
        } else {
            for (long column : terrain.columns()) {
                if (column != AtlasChunkTerrain.EMPTY_COLUMN
                        && AtlasChunkTerrain.unpackRgb(column) == 0
                        && (AtlasChunkTerrain.unpackFlags(column) & AtlasChunkTerrain.FLAG_STRUCTURE) == 0) {
                    CORRUPT_CHUNKS.add(key);
                    break;
                }
            }
        }
    }

    public static boolean putInteriorChunk(int cx, int cz, AtlasChunkTerrain terrain) {
        long key = ChunkPos.pack(cx, cz);
        AtlasChunkTerrain prev = INTERIOR_OVERLAY.get(key);
        if (prev != null && prev.equalsContent(terrain)) {
            return false;
        }
        INTERIOR_OVERLAY.put(key, terrain);
        updateLightSet(INTERIOR_LIGHT_CHUNKS, key, terrain);
        markChunkAndNeighborsDirty(cx, cz);
        return true;
    }

    public static void removeInteriorChunk(int cx, int cz) {
        long key = ChunkPos.pack(cx, cz);
        INTERIOR_LIGHT_CHUNKS.remove(key);
        if (INTERIOR_OVERLAY.remove(key) != null) {
            markChunkAndNeighborsDirty(cx, cz);
        }
    }

    public static void clearInteriorOverlay() {
        if (INTERIOR_OVERLAY.isEmpty()) {
            return;
        }
        long[] keys = INTERIOR_OVERLAY.toKeyArray();
        INTERIOR_OVERLAY.clear();
        INTERIOR_LIGHT_CHUNKS.clear();
        for (long key : keys) {
            markChunkAndNeighborsDirty(ChunkPos.getX(key), ChunkPos.getZ(key));
        }
    }

    public static void pruneInteriorOutside(int centerCx, int centerCz, int radius) {
        if (INTERIOR_OVERLAY.isEmpty()) {
            return;
        }
        long[] keys = INTERIOR_OVERLAY.toKeyArray();
        for (long key : keys) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            if (Math.abs(cx - centerCx) > radius || Math.abs(cz - centerCz) > radius) {
                INTERIOR_LIGHT_CHUNKS.remove(key);
                if (INTERIOR_OVERLAY.remove(key) != null) {
                    markChunkAndNeighborsDirty(cx, cz);
                }
            }
        }
    }

    private static void markChunkAndNeighborsDirty(int cx, int cz) {
        TERRAIN_MUTATION_EPOCH.incrementAndGet();
        boolean hasInterior = !INTERIOR_OVERLAY.isEmpty();
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int ncx = cx + dx;
                int ncz = cz + dz;
                long nKey = ChunkPos.pack(ncx, ncz);
                if ((dx == 0 && dz == 0) || CHUNKS.containsKey(nKey) || (hasInterior && INTERIOR_OVERLAY.containsKey(nKey))) {
                    markChunkDirty(ncx, ncz, nKey);
                }
            }
        }
    }

    public static void repaintAll() {
        for (AtlasRegion region : REGIONS.values()) {
            region.resetSavedImageTracking();
        }
        CHUNKS.forEachKey(key -> markChunkDirty(ChunkPos.getX(key), ChunkPos.getZ(key), key));
        INTERIOR_OVERLAY.forEachKey(key -> markChunkDirty(ChunkPos.getX(key), ChunkPos.getZ(key), key));
    }

    private static void markChunkDirty(int cx, int cz, long key) {
        int rx = Math.floorDiv(cx, REGION_CHUNKS);
        int rz = Math.floorDiv(cz, REGION_CHUNKS);
        long rKey = ChunkPos.pack(rx, rz);
        AtlasRegion region = REGIONS.get(rKey);
        if (region == null) {
            region = new AtlasRegion(rx, rz, generationCounter);
            REGIONS.put(rKey, region);
        }
        int lx = Math.floorMod(cx, REGION_CHUNKS);
        int lz = Math.floorMod(cz, REGION_CHUNKS);
        region.markTileDirty(lx, lz, hasChunkLight(key));
    }
}
