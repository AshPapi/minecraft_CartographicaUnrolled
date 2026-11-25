package com.cartographica_unrolled.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class AtlasSavedData extends SavedData {
    public static final String DEFAULT_DIMENSION = "minecraft:overworld";
    public static final String SHARED_DIMENSION = "cartographica_unrolled:shared";
    private static final int MAX_SAVED_CHUNKS_PER_DIM = 65536;
    private static final int REGION_BUCKET_SHIFT = 4;
    private static final int MAX_CHUNKS_PER_BUCKET = 256;

    public record DeathPos(double x, double z) {
    }

    private static final Codec<DeathPos> DEATH_POS_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.DOUBLE.fieldOf("x").forGetter(DeathPos::x),
            Codec.DOUBLE.fieldOf("z").forGetter(DeathPos::z)
    ).apply(inst, DeathPos::new));

    private static final class RegionChunkBucket {
        final Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks;
        byte @Nullable [] cleanBlob;

        RegionChunkBucket(int expected) {
            this.chunks = new Long2ObjectOpenHashMap<>(expected);
        }

        byte[] getOrEncodeBlob() {
            byte[] blob = this.cleanBlob;
            if (blob == null) {
                blob = BlockStateCodecUtil.encodeChunkMapBytes(this.chunks);
                this.cleanBlob = blob;
            }
            return blob;
        }
    }

    public static final class DimensionAtlasData {
        private final Long2ObjectOpenHashMap<AtlasChunkTerrain> allChunks;
        private final Long2ObjectMap<AtlasChunkTerrain> chunksView;
        private final Long2ObjectOpenHashMap<RegionChunkBucket> regionBuckets;
        private final List<MapStroke> strokes;
        private final List<MapMarker> markers;
        private byte @Nullable [] cachedDimensionBlob;

        public DimensionAtlasData() {
            this.allChunks = new Long2ObjectOpenHashMap<>();
            this.chunksView = Long2ObjectMaps.unmodifiable(this.allChunks);
            this.regionBuckets = new Long2ObjectOpenHashMap<>();
            this.strokes = new ArrayList<>();
            this.markers = new ArrayList<>();
        }

        private DimensionAtlasData(
                Long2ObjectOpenHashMap<AtlasChunkTerrain> allChunks,
                Long2ObjectOpenHashMap<RegionChunkBucket> regionBuckets,
                List<MapStroke> strokes,
                List<MapMarker> markers,
                byte @Nullable [] cachedDimensionBlob
        ) {
            this.allChunks = allChunks;
            this.chunksView = Long2ObjectMaps.unmodifiable(this.allChunks);
            this.regionBuckets = regionBuckets;
            this.strokes = strokes;
            this.markers = markers;
            this.cachedDimensionBlob = cachedDimensionBlob;
        }

        public Long2ObjectMap<AtlasChunkTerrain> chunksView() {
            return this.chunksView;
        }

        public List<MapStroke> strokes() {
            return this.strokes;
        }

        public List<MapMarker> markers() {
            return this.markers;
        }

        public boolean putChunkIfChanged(int cx, int cz, AtlasChunkTerrain terrain, int maxChunks) {
            long key = ChunkPos.pack(cx, cz);
            AtlasChunkTerrain prev = this.allChunks.get(key);
            if (prev == null && this.allChunks.size() >= maxChunks) {
                return false;
            }
            if (prev != null && prev.equalsContent(terrain)) {
                return false;
            }
            this.allChunks.put(key, terrain);
            long bucketKey = ChunkPos.pack(cx >> REGION_BUCKET_SHIFT, cz >> REGION_BUCKET_SHIFT);
            RegionChunkBucket bucket = this.regionBuckets.get(bucketKey);
            if (bucket == null) {
                bucket = new RegionChunkBucket(32);
                this.regionBuckets.put(bucketKey, bucket);
            }
            bucket.chunks.put(key, terrain);
            bucket.cleanBlob = null;
            this.cachedDimensionBlob = null;
            return true;
        }

        public ByteBuffer encodeChunksBuffer() {
            if (this.allChunks.isEmpty()) {
                return ByteBuffer.allocate(0);
            }
            byte[] cached = this.cachedDimensionBlob;
            if (cached != null) {
                return ByteBuffer.wrap(cached);
            }
            int nonEmptyBuckets = 0;
            int totalBytes = 8;
            for (Long2ObjectMap.Entry<RegionChunkBucket> entry : Long2ObjectMaps.fastIterable(this.regionBuckets)) {
                RegionChunkBucket bucket = entry.getValue();
                if (bucket.chunks.isEmpty()) {
                    continue;
                }
                byte[] blob = bucket.getOrEncodeBlob();
                if (blob.length == 0) {
                    continue;
                }
                nonEmptyBuckets++;
                totalBytes += 12 + blob.length;
            }
            if (nonEmptyBuckets == 0) {
                return ByteBuffer.allocate(0);
            }
            byte[] out = new byte[totalBytes];
            ByteBuffer buf = ByteBuffer.wrap(out);
            buf.putInt(BlockStateCodecUtil.SEGMENTED_MAP_MAGIC);
            buf.putInt(nonEmptyBuckets);
            for (Long2ObjectMap.Entry<RegionChunkBucket> entry : Long2ObjectMaps.fastIterable(this.regionBuckets)) {
                RegionChunkBucket bucket = entry.getValue();
                if (bucket.chunks.isEmpty() || bucket.cleanBlob == null || bucket.cleanBlob.length == 0) {
                    continue;
                }
                buf.putLong(entry.getLongKey());
                buf.putInt(bucket.cleanBlob.length);
                buf.put(bucket.cleanBlob);
            }
            this.cachedDimensionBlob = out;
            return ByteBuffer.wrap(out);
        }

        static DimensionAtlasData decode(ByteBuffer chunksBuf, List<MapStroke> strokes, List<MapMarker> markers) {
            List<MapStroke> strokesCopy = new ArrayList<>(strokes);
            List<MapMarker> markersCopy = new ArrayList<>(markers);
            if (chunksBuf == null || !chunksBuf.hasRemaining()) {
                return new DimensionAtlasData(
                        new Long2ObjectOpenHashMap<>(),
                        new Long2ObjectOpenHashMap<>(),
                        strokesCopy,
                        markersCopy,
                        null
                );
            }
            ByteBuffer dup = chunksBuf.duplicate();
            if (dup.remaining() >= 8) {
                int magic = dup.getInt(dup.position());
                if (magic == BlockStateCodecUtil.SEGMENTED_MAP_MAGIC) {
                    return decodeSegmented(dup, strokesCopy, markersCopy);
                }
            }
            Long2ObjectOpenHashMap<AtlasChunkTerrain> legacyChunks =
                    BlockStateCodecUtil.decodeChunkMapBuffer(dup, MAX_SAVED_CHUNKS_PER_DIM);
            Long2ObjectOpenHashMap<RegionChunkBucket> buckets = new Long2ObjectOpenHashMap<>();
            for (Long2ObjectMap.Entry<AtlasChunkTerrain> entry : Long2ObjectMaps.fastIterable(legacyChunks)) {
                long key = entry.getLongKey();
                int cx = ChunkPos.getX(key);
                int cz = ChunkPos.getZ(key);
                long bucketKey = ChunkPos.pack(cx >> REGION_BUCKET_SHIFT, cz >> REGION_BUCKET_SHIFT);
                RegionChunkBucket bucket = buckets.get(bucketKey);
                if (bucket == null) {
                    bucket = new RegionChunkBucket(32);
                    buckets.put(bucketKey, bucket);
                }
                bucket.chunks.put(key, entry.getValue());
            }
            return new DimensionAtlasData(legacyChunks, buckets, strokesCopy, markersCopy, null);
        }

        private static DimensionAtlasData decodeSegmented(
                ByteBuffer dup,
                List<MapStroke> strokesCopy,
                List<MapMarker> markersCopy
        ) {
            byte[] fullSnapshot = new byte[dup.remaining()];
            dup.get(fullSnapshot);
            ByteBuffer in = ByteBuffer.wrap(fullSnapshot);
            int magic = in.getInt();
            int bucketCount = in.getInt();
            if (magic != BlockStateCodecUtil.SEGMENTED_MAP_MAGIC
                    || bucketCount < 0
                    || bucketCount > MAX_SAVED_CHUNKS_PER_DIM) {
                return new DimensionAtlasData(
                        new Long2ObjectOpenHashMap<>(),
                        new Long2ObjectOpenHashMap<>(),
                        strokesCopy,
                        markersCopy,
                        null
                );
            }
            Long2ObjectOpenHashMap<AtlasChunkTerrain> allChunks = new Long2ObjectOpenHashMap<>();
            Long2ObjectOpenHashMap<RegionChunkBucket> buckets = new Long2ObjectOpenHashMap<>(Math.min(bucketCount, 256));
            boolean allValid = true;
            for (int i = 0; i < bucketCount; i++) {
                if (in.remaining() < 12) {
                    allValid = false;
                    break;
                }
                long bucketKey = in.getLong();
                int blobLen = in.getInt();
                if (blobLen <= 0 || blobLen > in.remaining()) {
                    allValid = false;
                    break;
                }
                byte[] blob = new byte[blobLen];
                in.get(blob);
                int remainBudget = MAX_SAVED_CHUNKS_PER_DIM - allChunks.size();
                if (remainBudget <= 0) {
                    allValid = false;
                    break;
                }
                Long2ObjectOpenHashMap<AtlasChunkTerrain> decoded =
                        BlockStateCodecUtil.decodeChunkMapBytes(blob, Math.min(MAX_CHUNKS_PER_BUCKET, remainBudget));
                if (decoded.isEmpty()) {
                    allValid = false;
                    continue;
                }
                RegionChunkBucket existing = buckets.get(bucketKey);
                RegionChunkBucket target = existing != null ? existing : new RegionChunkBucket(decoded.size());
                boolean bucketPure = existing == null;
                if (existing != null) {
                    allValid = false;
                }
                for (Long2ObjectMap.Entry<AtlasChunkTerrain> e : Long2ObjectMaps.fastIterable(decoded)) {
                    long ck = e.getLongKey();
                    int expectedBx = ChunkPos.getX(ck) >> REGION_BUCKET_SHIFT;
                    int expectedBz = ChunkPos.getZ(ck) >> REGION_BUCKET_SHIFT;
                    if (ChunkPos.pack(expectedBx, expectedBz) == bucketKey) {
                        target.chunks.put(ck, e.getValue());
                        allChunks.put(ck, e.getValue());
                    } else {
                        bucketPure = false;
                        allValid = false;
                    }
                }
                if (!target.chunks.isEmpty()) {
                    target.cleanBlob = bucketPure ? blob : null;
                    if (existing == null) {
                        buckets.put(bucketKey, target);
                    }
                } else {
                    allValid = false;
                }
            }
            byte[] cachedWhole = (allValid && !in.hasRemaining() && !allChunks.isEmpty()) ? fullSnapshot : null;
            return new DimensionAtlasData(allChunks, buckets, strokesCopy, markersCopy, cachedWhole);
        }

        DimensionAtlasData copyForInstance() {
            return new DimensionAtlasData(
                    this.allChunks,
                    this.regionBuckets,
                    new ArrayList<>(this.strokes),
                    new ArrayList<>(this.markers),
                    this.cachedDimensionBlob
            );
        }
    }

    private static final Codec<UUID> UUID_STRING_CODEC = Codec.STRING.comapFlatMap(
            raw -> {
                UUID parsed = parseUuidSafe(raw);
                return parsed != null
                        ? DataResult.success(parsed)
                        : DataResult.error(() -> "Invalid UUID: " + raw);
            },
            UUID::toString
    );

    private static final Codec<MapStroke.Point> POINT_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.FLOAT.fieldOf("x").forGetter(MapStroke.Point::x),
            Codec.FLOAT.fieldOf("z").forGetter(MapStroke.Point::z)
    ).apply(inst, MapStroke.Point::new));

    private static final Codec<MapStroke> STROKE_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            POINT_CODEC.listOf().fieldOf("points").forGetter(MapStroke::getPoints),
            Codec.INT.fieldOf("color").forGetter(MapStroke::getColor),
            Codec.FLOAT.fieldOf("width").forGetter(MapStroke::getWidth),
            Codec.BOOL.optionalFieldOf("is_dye", false).forGetter(MapStroke::isDye),
            Codec.STRING.optionalFieldOf("author_uuid", "")
                    .forGetter(s -> s.getAuthorUuid() != null ? s.getAuthorUuid().toString() : ""),
            Codec.STRING.optionalFieldOf("author_name", "")
                    .forGetter(s -> s.getAuthorName() != null ? s.getAuthorName() : "")
    ).apply(inst, (pts, color, width, isDye, uuidStr, name) -> new MapStroke(
            pts,
            color,
            width,
            isDye,
            uuidStr.isEmpty() ? null : parseUuidSafe(uuidStr),
            name.isEmpty() ? null : name
    )));

    private static final Codec<MapMarker> MARKER_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUID_STRING_CODEC.fieldOf("id").forGetter(MapMarker::getId),
            Codec.FLOAT.fieldOf("x").forGetter(MapMarker::getWorldX),
            Codec.FLOAT.fieldOf("z").forGetter(MapMarker::getWorldZ),
            Codec.STRING.fieldOf("type").forGetter(m -> m.getType().name()),
            Codec.STRING.optionalFieldOf("label", "").forGetter(MapMarker::getLabel),
            Codec.STRING.optionalFieldOf("author_uuid", "")
                    .forGetter(m -> m.getAuthorUuid() != null ? m.getAuthorUuid().toString() : ""),
            Codec.STRING.optionalFieldOf("author_name", "")
                    .forGetter(m -> m.getAuthorName() != null ? m.getAuthorName() : "")
    ).apply(inst, (id, x, z, type, label, uuidStr, name) -> new MapMarker(
            id,
            x,
            z,
            parseBannerSafe(type),
            label,
            uuidStr.isEmpty() ? null : parseUuidSafe(uuidStr),
            name.isEmpty() ? null : name
    )));

    private static final Codec<DimensionAtlasData> PLAYER_DIMENSION_DATA_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.BYTE_BUFFER.optionalFieldOf("chunks_bin", ByteBuffer.allocate(0))
                    .forGetter(DimensionAtlasData::encodeChunksBuffer),
            STROKE_CODEC.listOf().optionalFieldOf("strokes", List.of()).forGetter(DimensionAtlasData::strokes),
            MARKER_CODEC.listOf().optionalFieldOf("markers", List.of()).forGetter(DimensionAtlasData::markers)
    ).apply(inst, DimensionAtlasData::decode));

    private static final Codec<DimensionAtlasData> SHARED_DIMENSION_DATA_CODEC = RecordCodecBuilder.create(inst -> inst.group(
            STROKE_CODEC.listOf().optionalFieldOf("strokes", List.of()).forGetter(DimensionAtlasData::strokes),
            MARKER_CODEC.listOf().optionalFieldOf("markers", List.of()).forGetter(DimensionAtlasData::markers)
    ).apply(inst, (strokes, markers) -> DimensionAtlasData.decode(null, strokes, markers)));

    public static final Codec<AtlasSavedData> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.LONG.optionalFieldOf("world_cache_id", 0L).forGetter(AtlasSavedData::worldCacheId),
            Codec.unboundedMap(
                    UUID_STRING_CODEC,
                    Codec.unboundedMap(Codec.STRING, PLAYER_DIMENSION_DATA_CODEC)
            ).optionalFieldOf("player_dimensions", Map.of()).forGetter(d -> d.playerDimensions),
            Codec.unboundedMap(Codec.STRING, SHARED_DIMENSION_DATA_CODEC)
                    .optionalFieldOf("shared_dimensions", Map.of())
                    .forGetter(d -> d.sharedDimensions),
            UUID_STRING_CODEC.listOf()
                    .optionalFieldOf("shared_personal_players", List.of())
                    .forGetter(d -> new ArrayList<>(d.sharedPersonalPlayers)),
            Codec.unboundedMap(
                    UUID_STRING_CODEC,
                    Codec.unboundedMap(Codec.STRING, DEATH_POS_CODEC)
            ).optionalFieldOf("last_death_positions", Map.of())
                    .forGetter(d -> d.lastDeathPositions)
    ).apply(inst, AtlasSavedData::fromDecoded));

    public static final SavedDataType<AtlasSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("cartographica_unrolled", "atlas"),
            AtlasSavedData::new,
            CODEC,
            null
    );

    private final long worldCacheId;
    private final Map<UUID, Map<String, DimensionAtlasData>> playerDimensions;
    private final Map<String, DimensionAtlasData> sharedDimensions;
    private final Set<UUID> sharedPersonalPlayers;
    private final Map<UUID, Map<String, DeathPos>> lastDeathPositions;

    public AtlasSavedData() {
        this.worldCacheId = newRandomWorldCacheId();
        this.playerDimensions = new HashMap<>();
        this.sharedDimensions = new HashMap<>();
        this.sharedPersonalPlayers = new HashSet<>();
        this.lastDeathPositions = new HashMap<>();
        this.setDirty();
    }

    private AtlasSavedData(
            long worldCacheId,
            Map<UUID, Map<String, DimensionAtlasData>> playerDimensions,
            Map<String, DimensionAtlasData> sharedDimensions,
            Set<UUID> sharedPersonalPlayers,
            Map<UUID, Map<String, DeathPos>> lastDeathPositions
    ) {
        this.worldCacheId = worldCacheId;
        this.playerDimensions = playerDimensions;
        this.sharedDimensions = sharedDimensions;
        this.sharedPersonalPlayers = sharedPersonalPlayers;
        this.lastDeathPositions = lastDeathPositions;
    }

    private static long newRandomWorldCacheId() {
        long id;
        do {
            id = ThreadLocalRandom.current().nextLong();
        } while (id == 0L);
        return id;
    }

    public long worldCacheId() {
        return this.worldCacheId;
    }

    public void setDeathPos(UUID playerId, String dimension, double x, double z) {
        String dimKey = (dimension == null || dimension.isEmpty()) ? DEFAULT_DIMENSION : dimension;
        this.lastDeathPositions
                .computeIfAbsent(playerId, id -> new HashMap<>())
                .put(dimKey, new DeathPos(x, z));
        this.setDirty();
    }

    public void clearDeathPos(UUID playerId, String dimension) {
        String dimKey = (dimension == null || dimension.isEmpty()) ? DEFAULT_DIMENSION : dimension;
        Map<String, DeathPos> dims = this.lastDeathPositions.get(playerId);
        if (dims != null && dims.remove(dimKey) != null) {
            this.setDirty();
        }
    }

    public @Nullable DeathPos getDeathPos(UUID playerId, String dimension) {
        String dimKey = (dimension == null || dimension.isEmpty()) ? DEFAULT_DIMENSION : dimension;
        Map<String, DeathPos> dims = this.lastDeathPositions.get(playerId);
        return dims != null ? dims.get(dimKey) : null;
    }

    public boolean isSharingPersonal(UUID playerId) {
        return this.sharedPersonalPlayers.contains(playerId);
    }

    public void setSharingPersonal(UUID playerId, boolean share) {
        boolean changed = share ? this.sharedPersonalPlayers.add(playerId) : this.sharedPersonalPlayers.remove(playerId);
        if (changed) {
            this.setDirty();
        }
    }

    public List<MapMarker> collectSharedMarkersFor(UUID viewerId, String dim) {
        List<MapMarker> result = new ArrayList<>(getSharedData(dim).markers());
        for (UUID sharerId : this.sharedPersonalPlayers) {
            if (sharerId.equals(viewerId)) {
                continue;
            }
            Map<String, DimensionAtlasData> dims = this.playerDimensions.get(sharerId);
            if (dims != null) {
                DimensionAtlasData data = dims.get(dim);
                if (data != null) {
                    result.addAll(data.markers());
                }
            }
        }
        return result;
    }

    public List<MapStroke> collectSharedStrokesFor(UUID viewerId, String dim) {
        List<MapStroke> result = new ArrayList<>(getSharedData(dim).strokes());
        for (UUID sharerId : this.sharedPersonalPlayers) {
            if (sharerId.equals(viewerId)) {
                continue;
            }
            Map<String, DimensionAtlasData> dims = this.playerDimensions.get(sharerId);
            if (dims != null) {
                DimensionAtlasData data = dims.get(dim);
                if (data != null) {
                    result.addAll(data.strokes());
                }
            }
        }
        return result;
    }

    private static @Nullable UUID parseUuidSafe(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static MapMarker.BannerType parseBannerSafe(String name) {
        try {
            return MapMarker.BannerType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            return MapMarker.BannerType.RED;
        }
    }

    private static AtlasSavedData fromDecoded(
            long decodedWorldCacheId,
            Map<UUID, Map<String, DimensionAtlasData>> byDim,
            Map<String, DimensionAtlasData> sharedDims,
            List<UUID> sharedPersonalPlayers,
            Map<UUID, Map<String, DeathPos>> deathPositions
    ) {
        Map<UUID, Map<String, DimensionAtlasData>> merged = new HashMap<>();
        for (Map.Entry<UUID, Map<String, DimensionAtlasData>> entry : byDim.entrySet()) {
            Map<String, DimensionAtlasData> dims = new HashMap<>();
            for (Map.Entry<String, DimensionAtlasData> de : entry.getValue().entrySet()) {
                dims.put(de.getKey(), de.getValue().copyForInstance());
            }
            merged.put(entry.getKey(), dims);
        }
        Map<String, DimensionAtlasData> sharedDimsCopy = new HashMap<>();
        for (Map.Entry<String, DimensionAtlasData> entry : sharedDims.entrySet()) {
            sharedDimsCopy.put(entry.getKey(), entry.getValue().copyForInstance());
        }
        Map<UUID, Map<String, DeathPos>> deathsCopy = new HashMap<>();
        for (Map.Entry<UUID, Map<String, DeathPos>> entry : deathPositions.entrySet()) {
            deathsCopy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        boolean generatedCacheId = decodedWorldCacheId == 0L;
        long resolvedCacheId = generatedCacheId ? newRandomWorldCacheId() : decodedWorldCacheId;
        AtlasSavedData instance = new AtlasSavedData(
                resolvedCacheId,
                merged,
                sharedDimsCopy,
                new HashSet<>(sharedPersonalPlayers),
                deathsCopy
        );
        if (generatedCacheId) {
            instance.setDirty();
        }
        return instance;
    }

    public DimensionAtlasData getSharedData(String dim) {
        String dimKey = (dim == null || dim.isEmpty()) ? DEFAULT_DIMENSION : dim;
        return sharedDimensions.computeIfAbsent(dimKey, k -> new DimensionAtlasData());
    }

    public DimensionAtlasData getOrCreate(UUID playerId, String dimension) {
        String dimKey = (dimension == null || dimension.isEmpty()) ? DEFAULT_DIMENSION : dimension;
        return playerDimensions
                .computeIfAbsent(playerId, id -> new HashMap<>())
                .computeIfAbsent(dimKey, d -> new DimensionAtlasData());
    }

    public static String dimensionKey(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    public static AtlasSavedData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
