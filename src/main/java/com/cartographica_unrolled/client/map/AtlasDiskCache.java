package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.data.BlockStateCodecUtil;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class AtlasDiskCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int REGION_MAGIC = 0x43415254;
    private static final int REGION_VERSION = 3;
    private static final int OVERVIEW_MAGIC = 0x43414F56;
    private static final int OVERVIEW_VERSION = 2;
    private static final int MAX_DISK_CHUNKS = 2_000_000;
    private static final int IO_BUFFER_SIZE = 32768;
    private static final int LOCK_STRIPES = 32;
    private static final int LOCK_MASK = LOCK_STRIPES - 1;
    private static final Pattern UNSAFE_NAME_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private static final ReentrantReadWriteLock[] FILE_LOCKS = new ReentrantReadWriteLock[LOCK_STRIPES];
    private static final ExecutorService DISK_IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Cartographica-DiskCacheIO");
        t.setDaemon(true);
        return t;
    });
    private static final ConcurrentHashMap<String, PendingRegionSave> PENDING_REGION_SAVES = new ConcurrentHashMap<>();
    private static volatile @Nullable Path activeWorldDir = null;
    private static volatile long activeWorldCacheId = 0L;
    private static volatile boolean legacyRootPurged = false;

    @FunctionalInterface
    private interface DataStreamWriter {
        void write(DataOutputStream out) throws IOException;
    }

    private record PendingRegionSave(
            Path worldDir,
            String dimensionId,
            int rx,
            int rz,
            int resourcePackHash,
            long[] tileSignatures,
            byte[][] colorTiles,
            byte @Nullable [][] lightTiles
    ) {
    }

    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            FILE_LOCKS[i] = new ReentrantReadWriteLock();
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> flushPendingSaves(3000L), "Cartographica-DiskCacheShutdown"));
    }

    public record LoadedRegionBytes(
            byte[] colorBytes,
            byte @Nullable [] lightBytes,
            long[] tileSignatures
    ) {
    }

    public record OverviewEntry(
            int rx,
            int rz,
            byte[] colorBytes,
            byte @Nullable [] lightBytes
    ) {
    }

    private AtlasDiskCache() {
    }

    private static ReentrantReadWriteLock lockFor(Path path) {
        return FILE_LOCKS[HashCommon.mix(path.hashCode()) & LOCK_MASK];
    }

    public static @Nullable Path bindServerWorld(long worldCacheId) {
        scheduleLegacyRootCleanupOnce();
        if (worldCacheId == 0L) {
            return activeWorldDir;
        }
        Path current = activeWorldDir;
        if (current != null && activeWorldCacheId == worldCacheId) {
            return current;
        }
        Path resolved = resolveWorldDir(worldCacheId);
        if (resolved != null) {
            activeWorldCacheId = worldCacheId;
            activeWorldDir = resolved;
        }
        return resolved;
    }

    public static @Nullable Path getActiveWorldDir() {
        return activeWorldDir;
    }

    public static void clearWorldKey() {
        activeWorldDir = null;
        activeWorldCacheId = 0L;
    }

    private static @Nullable Path resolveWorldDir(long worldCacheId) {
        if (worldCacheId == 0L) {
            return null;
        }
        String hexId = Long.toUnsignedString(worldCacheId, 16);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() != null) {
            Path worldRoot = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).normalize();
            return worldRoot.resolve("cartographica_cache").resolve(hexId);
        }
        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            String hostTag = sanitize(ip) + "_" + Integer.toHexString(ip.hashCode());
            return mc.gameDirectory.toPath()
                    .resolve("cartographica_cache")
                    .resolve("mp_" + hostTag + "_" + hexId)
                    .normalize();
        }
        return mc.gameDirectory.toPath()
                .resolve("cartographica_cache")
                .resolve("srv_" + hexId)
                .normalize();
    }

    private static void scheduleLegacyRootCleanupOnce() {
        if (legacyRootPurged) {
            return;
        }
        legacyRootPurged = true;
        Path rootCache = Minecraft.getInstance().gameDirectory.toPath().resolve("cartographica_cache");
        DISK_IO_EXECUTOR.execute(() -> {
            if (!Files.isDirectory(rootCache)) {
                return;
            }
            List<Path> legacyDirs = new ArrayList<>();
            try (Stream<Path> entries = Files.list(rootCache)) {
                entries.forEach(entry -> {
                    Path name = entry.getFileName();
                    if (name != null && name.toString().startsWith("sp_") && Files.isDirectory(entry)) {
                        legacyDirs.add(entry);
                    }
                });
            } catch (IOException e) {
                LOGGER.debug("Failed to scan legacy atlas cache directory {}", rootCache, e);
                return;
            }
            for (Path legacyDir : legacyDirs) {
                try (Stream<Path> walk = Files.walk(legacyDir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(AtlasDiskCache::deleteQuietly);
                } catch (IOException e) {
                    LOGGER.debug("Failed to purge legacy singleplayer cache {}", legacyDir, e);
                }
            }
        });
    }

    private static Path resolveCacheFile(Path worldDir, String dimensionId) {
        return worldDir.resolve(sanitize(dimensionId) + ".bin");
    }

    private static Path resolveRegionDir(Path worldDir, String dimensionId) {
        return worldDir.resolve("regions").resolve(sanitize(dimensionId));
    }

    private static String regionFileName(int rx, int rz) {
        return "r_" + rx + "_" + rz + ".atx";
    }

    private static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "default";
        }
        return UNSAFE_NAME_CHARS.matcher(input).replaceAll("_");
    }

    private static void writeCompressedAtomically(Path target, String logContext, DataStreamWriter writer) {
        Path parent = target.getParent();
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    new DeflaterOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), IO_BUFFER_SIZE), deflater, IO_BUFFER_SIZE),
                    IO_BUFFER_SIZE))) {
                writer.write(out);
            }
            ReentrantReadWriteLock.WriteLock wl = lockFor(target).writeLock();
            wl.lock();
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException | AccessDeniedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                wl.unlock();
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save atlas cache ({})", logContext, e);
            deleteQuietly(tmp);
        } finally {
            deflater.end();
        }
    }

    public static boolean saveAsync(String dimensionId, Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks) {
        if (dimensionId == null || dimensionId.isEmpty() || chunks.isEmpty()) {
            return false;
        }
        Path worldDir = activeWorldDir;
        if (worldDir == null) {
            return false;
        }
        DISK_IO_EXECUTOR.execute(() -> {
            Path file = resolveCacheFile(worldDir, dimensionId);
            writeCompressedAtomically(file, dimensionId, out -> BlockStateCodecUtil.writeChunkMapToStream(out, chunks));
        });
        return true;
    }

    public static void loadDimensionAsync(
            String dimensionId,
            int expectedResourcePackHash,
            BiConsumer<Long2ObjectOpenHashMap<AtlasChunkTerrain>, List<OverviewEntry>> callback
    ) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return;
        }
        Path worldDir = activeWorldDir;
        if (worldDir == null) {
            return;
        }
        DISK_IO_EXECUTOR.execute(() -> {
            Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks = loadChunkFileInternal(worldDir, dimensionId);
            List<OverviewEntry> overviews = loadOverviewInternal(worldDir, dimensionId, expectedResourcePackHash);
            if (!chunks.isEmpty() || !overviews.isEmpty()) {
                callback.accept(chunks, overviews);
            }
        });
    }

    private static Long2ObjectOpenHashMap<AtlasChunkTerrain> loadChunkFileInternal(Path worldDir, String dimensionId) {
        Path file = resolveCacheFile(worldDir, dimensionId);
        ReentrantReadWriteLock.ReadLock rl = lockFor(file).readLock();
        rl.lock();
        try {
            if (!Files.isRegularFile(file)) {
                return new Long2ObjectOpenHashMap<>(0);
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file), IO_BUFFER_SIZE)),
                    IO_BUFFER_SIZE))) {
                return BlockStateCodecUtil.readChunkMapFromStream(in, MAX_DISK_CHUNKS);
            } catch (IOException e) {
                return new Long2ObjectOpenHashMap<>(0);
            }
        } finally {
            rl.unlock();
        }
    }

    public static boolean saveRegionTextureAsync(
            String dimensionId,
            int rx,
            int rz,
            int resourcePackHash,
            long[] tileSignatures,
            byte[][] colorTilesSnapshot,
            byte @Nullable [][] lightTilesSnapshot
    ) {
        int tileCount = AtlasRegionCache.REGION_CHUNKS * AtlasRegionCache.REGION_CHUNKS;
        if (dimensionId == null || dimensionId.isEmpty()
                || tileSignatures.length != tileCount
                || colorTilesSnapshot.length != tileCount) {
            return false;
        }
        Path worldDir = activeWorldDir;
        if (worldDir == null) {
            return false;
        }
        String saveKey = worldDir + "|" + dimensionId + "|" + rx + "|" + rz;
        PendingRegionSave pending = new PendingRegionSave(
                worldDir,
                dimensionId,
                rx,
                rz,
                resourcePackHash,
                tileSignatures,
                colorTilesSnapshot,
                lightTilesSnapshot
        );
        if (PENDING_REGION_SAVES.put(saveKey, pending) == null) {
            DISK_IO_EXECUTOR.execute(() -> {
                PendingRegionSave latest = PENDING_REGION_SAVES.remove(saveKey);
                if (latest != null) {
                    writeRegionFile(latest);
                }
            });
        }
        return true;
    }

    public static void flushPendingSaves(long timeoutMs) {
        try {
            Future<?> barrier = DISK_IO_EXECUTOR.submit(() -> {
            });
            barrier.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.debug("Timed out or failed while flushing atlas disk cache", e);
        }
    }

    private static void writeRegionFile(PendingRegionSave save) {
        Path file = resolveRegionDir(save.worldDir(), save.dimensionId()).resolve(regionFileName(save.rx(), save.rz()));
        writeCompressedAtomically(file, save.dimensionId() + " " + save.rx() + "," + save.rz(), out -> {
            out.writeInt(REGION_MAGIC);
            out.writeInt(REGION_VERSION);
            out.writeInt(save.resourcePackHash());
            out.writeInt(AtlasRegionCache.REGION_TEX_PX);
            byte[][] lightTiles = save.lightTiles();
            out.writeBoolean(lightTiles != null);
            for (long sig : save.tileSignatures()) {
                out.writeLong(sig);
            }
            writeTiledRgba(out, save.colorTiles(), AtlasRegionCache.EMPTY_TILE_COLOR_0);
            if (lightTiles != null) {
                writeTiledRgba(out, lightTiles, AtlasRegionCache.EMPTY_TILE_LIGHT_MIPS[0]);
            }
        });
    }

    private static void writeTiledRgba(DataOutputStream out, byte[][] tiles, byte[] fallbackTile) throws IOException {
        int tileRowBytes = AtlasRegionCache.TILE_PX * 4;
        int expectedTileBytes = AtlasRegionCache.TILE_PX * tileRowBytes;
        int regionChunks = AtlasRegionCache.REGION_CHUNKS;
        for (int lz = 0; lz < regionChunks; lz++) {
            int rowBase = lz * regionChunks;
            for (int ty = 0; ty < AtlasRegionCache.TILE_PX; ty++) {
                int off = ty * tileRowBytes;
                for (int lx = 0; lx < regionChunks; lx++) {
                    byte[] tile = tiles[rowBase + lx];
                    byte[] src = (tile != null && tile.length >= expectedTileBytes) ? tile : fallbackTile;
                    out.write(src, off, tileRowBytes);
                }
            }
        }
    }

    public static @Nullable LoadedRegionBytes loadRegionBytes(
            @Nullable Path worldDir,
            String dimensionId,
            int rx,
            int rz,
            int expectedResourcePackHash
    ) {
        if (worldDir == null || dimensionId == null || dimensionId.isEmpty()) {
            return null;
        }
        Path file = resolveRegionDir(worldDir, dimensionId).resolve(regionFileName(rx, rz));
        ReentrantReadWriteLock.ReadLock rl = lockFor(file).readLock();
        rl.lock();
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            int tileCount = AtlasRegionCache.REGION_CHUNKS * AtlasRegionCache.REGION_CHUNKS;
            int expectedBytes = AtlasRegionCache.REGION_TEX_PX * AtlasRegionCache.REGION_TEX_PX * 4;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file), IO_BUFFER_SIZE)),
                    IO_BUFFER_SIZE))) {
                int magic = in.readInt();
                int version = in.readInt();
                int packHash = in.readInt();
                int texPx = in.readInt();
                if (magic != REGION_MAGIC || version != REGION_VERSION
                        || packHash != expectedResourcePackHash || texPx != AtlasRegionCache.REGION_TEX_PX) {
                    return null;
                }
                boolean hasLight = in.readBoolean();
                long[] tileSignatures = new long[tileCount];
                for (int i = 0; i < tileCount; i++) {
                    tileSignatures[i] = in.readLong();
                }
                byte[] colorBytes = readExactBytes(in, expectedBytes);
                byte[] lightBytes = hasLight ? readExactBytes(in, expectedBytes) : null;
                return new LoadedRegionBytes(colorBytes, lightBytes, tileSignatures);
            } catch (IOException e) {
                return null;
            }
        } finally {
            rl.unlock();
        }
    }

    private static byte[] readExactBytes(DataInputStream in, int expectedBytes) throws IOException {
        byte[] out = new byte[expectedBytes];
        int offset = 0;
        while (offset < expectedBytes) {
            int n = in.read(out, offset, expectedBytes - offset);
            if (n < 0) {
                throw new EOFException();
            }
            offset += n;
        }
        return out;
    }

    public static boolean saveOverviewAsync(String dimensionId, int resourcePackHash, List<OverviewEntry> entries) {
        if (dimensionId == null || dimensionId.isEmpty() || entries.isEmpty()) {
            return false;
        }
        Path worldDir = activeWorldDir;
        if (worldDir == null) {
            return false;
        }
        List<OverviewEntry> snapshot = new ArrayList<>(entries);
        DISK_IO_EXECUTOR.execute(() -> {
            Path file = resolveRegionDir(worldDir, dimensionId).resolve("overview.atx");
            writeCompressedAtomically(file, dimensionId + " overview", out -> {
                out.writeInt(OVERVIEW_MAGIC);
                out.writeInt(OVERVIEW_VERSION);
                out.writeInt(resourcePackHash);
                out.writeInt(AtlasRegionCache.OVERVIEW_PX);
                out.writeInt(snapshot.size());
                for (OverviewEntry e : snapshot) {
                    out.writeInt(e.rx());
                    out.writeInt(e.rz());
                    out.write(e.colorBytes());
                    byte[] light = e.lightBytes();
                    out.writeBoolean(light != null);
                    if (light != null) {
                        out.write(light);
                    }
                }
            });
        });
        return true;
    }

    private static List<OverviewEntry> loadOverviewInternal(Path worldDir, String dimensionId, int expectedResourcePackHash) {
        Path file = resolveRegionDir(worldDir, dimensionId).resolve("overview.atx");
        ReentrantReadWriteLock.ReadLock rl = lockFor(file).readLock();
        rl.lock();
        try {
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            int expectedBytes = AtlasRegionCache.OVERVIEW_PX * AtlasRegionCache.OVERVIEW_PX * 4;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new InflaterInputStream(new BufferedInputStream(Files.newInputStream(file), IO_BUFFER_SIZE)),
                    IO_BUFFER_SIZE))) {
                int magic = in.readInt();
                int version = in.readInt();
                int packHash = in.readInt();
                int ovPx = in.readInt();
                if (magic != OVERVIEW_MAGIC || version != OVERVIEW_VERSION
                        || packHash != expectedResourcePackHash || ovPx != AtlasRegionCache.OVERVIEW_PX) {
                    return List.of();
                }
                int count = in.readInt();
                if (count <= 0 || count > 16_384) {
                    return List.of();
                }
                List<OverviewEntry> result = new ArrayList<>(Math.min(count, 256));
                for (int i = 0; i < count; i++) {
                    int rx = in.readInt();
                    int rz = in.readInt();
                    byte[] colorBytes = readExactBytes(in, expectedBytes);
                    boolean hasLight = in.readBoolean();
                    byte[] lightBytes = hasLight ? readExactBytes(in, expectedBytes) : null;
                    result.add(new OverviewEntry(rx, rz, colorBytes, lightBytes));
                }
                return result;
            } catch (IOException e) {
                return List.of();
            }
        } finally {
            rl.unlock();
        }
    }

    public static void clearRegionTextures(String dimensionId) {
        if (dimensionId == null || dimensionId.isEmpty()) {
            return;
        }
        Path worldDir = activeWorldDir;
        if (worldDir == null) {
            return;
        }
        String prefix = worldDir + "|" + dimensionId + "|";
        PENDING_REGION_SAVES.keySet().removeIf(k -> k.startsWith(prefix));
        Path dir = resolveRegionDir(worldDir, dimensionId);
        DISK_IO_EXECUTOR.execute(() -> {
            if (!Files.isDirectory(dir)) {
                return;
            }
            List<Path> targets = new ArrayList<>();
            try (Stream<Path> files = Files.list(dir)) {
                files.forEach(targets::add);
            } catch (IOException ignored) {
                return;
            }
            for (Path p : targets) {
                ReentrantReadWriteLock.WriteLock wl = lockFor(p).writeLock();
                wl.lock();
                try {
                    deleteQuietly(p);
                } finally {
                    wl.unlock();
                }
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("Failed to delete atlas cache file {}", path, e);
        }
    }
}
