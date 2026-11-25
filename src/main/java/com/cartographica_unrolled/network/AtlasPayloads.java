package com.cartographica_unrolled.network;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.data.BlockStateCodecUtil;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AtlasPayloads {
    public static final int S2C_CHUNK_BATCH_SIZE = 40;
    public static final int MAX_STROKES = 2048;
    private static final int MAX_S2C_MARKERS = 2048;
    private static final int MAX_S2C_STROKES = 4096;
    private static final int MAX_TOTAL_REPLACE_POINTS = 65536;
    private static final int MAX_TOTAL_SYNC_POINTS = 131072;

    private AtlasPayloads() {
    }

    public record ChunkEntry(int cx, int cz, AtlasChunkTerrain terrain) {
    }

    public record RemotePlayerPos(UUID id, String name, double x, double z, float yRot) {
    }

    public record AddStrokePayload(
            String dimension,
            List<MapStroke.Point> points,
            int color,
            float width,
            boolean isDye
    ) implements CustomPacketPayload {
        public static final Type<AddStrokePayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "add_stroke"));
        public static final StreamCodec<FriendlyByteBuf, AddStrokePayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    writeStrokePoints(buf, p.points);
                    buf.writeInt(p.color);
                    buf.writeFloat(p.width);
                    buf.writeBoolean(p.isDye);
                },
                buf -> new AddStrokePayload(
                        buf.readUtf(128),
                        readStrokePoints(buf, 2048),
                        buf.readInt(),
                        buf.readFloat(),
                        buf.readBoolean()
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ReplaceStrokesPayload(String dimension, List<MapStroke> strokes) implements CustomPacketPayload {
        public static final Type<ReplaceStrokesPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "replace_strokes"));
        public static final StreamCodec<FriendlyByteBuf, ReplaceStrokesPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    writeBoundedStrokes(buf, p.strokes, MAX_STROKES, MAX_TOTAL_REPLACE_POINTS);
                },
                buf -> new ReplaceStrokesPayload(
                        buf.readUtf(128),
                        readBoundedStrokes(buf, MAX_STROKES, MAX_TOTAL_REPLACE_POINTS)
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UpsertMarkerPayload(
            String dimension,
            UUID id,
            float x,
            float z,
            int typeOrd,
            String label
    ) implements CustomPacketPayload {
        public static final Type<UpsertMarkerPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "upsert_marker"));
        public static final StreamCodec<FriendlyByteBuf, UpsertMarkerPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    buf.writeUUID(p.id);
                    buf.writeFloat(p.x);
                    buf.writeFloat(p.z);
                    buf.writeVarInt(p.typeOrd);
                    writeSafeUtf(buf, p.label, 64);
                },
                buf -> new UpsertMarkerPayload(
                        buf.readUtf(128),
                        buf.readUUID(),
                        buf.readFloat(),
                        buf.readFloat(),
                        buf.readVarInt(),
                        buf.readUtf(64)
                )
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record RemoveMarkerPayload(String dimension, UUID id) implements CustomPacketPayload {
        public static final Type<RemoveMarkerPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "remove_marker"));
        public static final StreamCodec<FriendlyByteBuf, RemoveMarkerPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    buf.writeUUID(p.id);
                },
                buf -> new RemoveMarkerPayload(buf.readUtf(128), buf.readUUID())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ExploreChunksBatchPayload(String dimension, List<ChunkEntry> entries) implements CustomPacketPayload {
        public static final Type<ExploreChunksBatchPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "explore_chunks"));
        public static final StreamCodec<FriendlyByteBuf, ExploreChunksBatchPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    writeChunkEntries(buf, p.entries, 32);
                },
                buf -> new ExploreChunksBatchPayload(buf.readUtf(128), readChunkEntries(buf, 32))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncAtlasMetaS2CPayload(
            String dimension,
            long worldCacheId,
            List<MapMarker> markers,
            List<MapStroke> strokes
    ) implements CustomPacketPayload {
        public static final Type<SyncAtlasMetaS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "sync_atlas_meta"));
        public static final StreamCodec<FriendlyByteBuf, SyncAtlasMetaS2CPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    buf.writeLong(p.worldCacheId);
                    int mCount = Math.min(p.markers.size(), MAX_S2C_MARKERS);
                    buf.writeVarInt(mCount);
                    for (int i = 0; i < mCount; i++) {
                        MapMarker m = p.markers.get(i);
                        buf.writeUUID(m.getId());
                        buf.writeFloat(m.getWorldX());
                        buf.writeFloat(m.getWorldZ());
                        buf.writeVarInt(m.getType().ordinal());
                        writeSafeUtf(buf, m.getLabel(), 64);
                        writeOptionalUuid(buf, m.getAuthorUuid());
                        writeSafeUtf(buf, m.getAuthorName(), 64);
                    }
                    writeBoundedStrokes(buf, p.strokes, MAX_S2C_STROKES, MAX_TOTAL_SYNC_POINTS);
                },
                buf -> {
                    String dim = buf.readUtf(128);
                    long worldCacheId = buf.readLong();
                    int mCount = Math.min(Math.max(0, buf.readVarInt()), MAX_S2C_MARKERS);
                    List<MapMarker> markers = new ArrayList<>(Math.min(mCount, 256));
                    MapMarker.BannerType[] types = MapMarker.BannerType.values();
                    for (int i = 0; i < mCount; i++) {
                        UUID id = buf.readUUID();
                        float wx = buf.readFloat();
                        float wz = buf.readFloat();
                        int ord = Mth.clamp(buf.readVarInt(), 0, types.length - 1);
                        String label = buf.readUtf(64);
                        UUID authorUuid = readOptionalUuid(buf);
                        String authorName = buf.readUtf(64);
                        markers.add(new MapMarker(
                                id, wx, wz, types[ord], label, authorUuid, authorName.isEmpty() ? null : authorName
                        ));
                    }
                    List<MapStroke> strokes = readBoundedStrokes(buf, MAX_S2C_STROKES, MAX_TOTAL_SYNC_POINTS);
                    return new SyncAtlasMetaS2CPayload(dim, worldCacheId, markers, strokes);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncAtlasChunksS2CPayload(String dimension, List<ChunkEntry> entries) implements CustomPacketPayload {
        public static final Type<SyncAtlasChunksS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "sync_atlas_chunks"));
        public static final StreamCodec<FriendlyByteBuf, SyncAtlasChunksS2CPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    writeChunkEntries(buf, p.entries, S2C_CHUNK_BATCH_SIZE);
                },
                buf -> new SyncAtlasChunksS2CPayload(buf.readUtf(128), readChunkEntries(buf, S2C_CHUNK_BATCH_SIZE))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ForceCloseAtlasPayload() implements CustomPacketPayload {
        public static final Type<ForceCloseAtlasPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "force_close"));
        public static final StreamCodec<FriendlyByteBuf, ForceCloseAtlasPayload> STREAM_CODEC =
                StreamCodec.unit(new ForceCloseAtlasPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetAtlasActionPayload(Action action) implements CustomPacketPayload {
        public enum Action {
            START_UNFOLD,
            START_FOLD,
            FORCE_CLOSE
        }

        private static final Action[] ACTIONS = Action.values();
        public static final Type<SetAtlasActionPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "set_atlas_action"));
        public static final StreamCodec<FriendlyByteBuf, SetAtlasActionPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.action.ordinal()),
                buf -> new SetAtlasActionPayload(ACTIONS[Mth.clamp(buf.readVarInt(), 0, ACTIONS.length - 1)])
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SetSharePersonalPayload(boolean share) implements CustomPacketPayload {
        public static final Type<SetSharePersonalPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "set_share_personal"));
        public static final StreamCodec<FriendlyByteBuf, SetSharePersonalPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.share),
                buf -> new SetSharePersonalPayload(buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncSharePersonalS2CPayload(boolean share) implements CustomPacketPayload {
        public static final Type<SyncSharePersonalS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "sync_share_personal"));
        public static final StreamCodec<FriendlyByteBuf, SyncSharePersonalS2CPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeBoolean(p.share),
                buf -> new SyncSharePersonalS2CPayload(buf.readBoolean())
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncDeathPosS2CPayload(String dimension, boolean present, double x, double z) implements CustomPacketPayload {
        public static final Type<SyncDeathPosS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "sync_death_pos"));
        public static final StreamCodec<FriendlyByteBuf, SyncDeathPosS2CPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    buf.writeBoolean(p.present);
                    if (p.present) {
                        buf.writeDouble(p.x);
                        buf.writeDouble(p.z);
                    }
                },
                buf -> {
                    String dim = buf.readUtf(128);
                    boolean present = buf.readBoolean();
                    return present
                            ? new SyncDeathPosS2CPayload(dim, true, buf.readDouble(), buf.readDouble())
                            : new SyncDeathPosS2CPayload(dim, false, 0.0, 0.0);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record ClearDeathPosPayload(String dimension) implements CustomPacketPayload {
        public static final Type<ClearDeathPosPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "clear_death_pos"));
        public static final StreamCodec<FriendlyByteBuf, ClearDeathPosPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> writeSafeUtf(buf, p.dimension, 128),
                buf -> new ClearDeathPosPayload(buf.readUtf(128))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SyncOtherPlayersS2CPayload(String dimension, List<RemotePlayerPos> players) implements CustomPacketPayload {
        public static final Type<SyncOtherPlayersS2CPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "sync_other_players"));
        public static final StreamCodec<FriendlyByteBuf, SyncOtherPlayersS2CPayload> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeSafeUtf(buf, p.dimension, 128);
                    int count = Math.min(p.players.size(), 128);
                    buf.writeVarInt(count);
                    for (int i = 0; i < count; i++) {
                        RemotePlayerPos rp = p.players.get(i);
                        buf.writeUUID(rp.id());
                        writeSafeUtf(buf, rp.name(), 64);
                        buf.writeDouble(rp.x());
                        buf.writeDouble(rp.z());
                        buf.writeFloat(rp.yRot());
                    }
                },
                buf -> {
                    String dim = buf.readUtf(128);
                    int count = Math.min(Math.max(0, buf.readVarInt()), 128);
                    List<RemotePlayerPos> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(new RemotePlayerPos(
                                buf.readUUID(),
                                buf.readUtf(64),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readFloat()
                        ));
                    }
                    return new SyncOtherPlayersS2CPayload(dim, list);
                }
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static void writeSafeUtf(FriendlyByteBuf buf, @Nullable String value, int maxLen) {
        if (value == null || value.isEmpty()) {
            buf.writeUtf("", maxLen);
            return;
        }
        if (value.length() <= maxLen) {
            buf.writeUtf(value, maxLen);
            return;
        }
        int end = value.offsetByCodePoints(0, Math.min(maxLen, value.codePointCount(0, value.length())));
        while (end > maxLen) {
            end = value.offsetByCodePoints(0, value.codePointCount(0, end) - 1);
        }
        buf.writeUtf(value.substring(0, end), maxLen);
    }

    private static void writeOptionalUuid(FriendlyByteBuf buf, @Nullable UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeUUID(uuid);
        }
    }

    private static @Nullable UUID readOptionalUuid(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readUUID() : null;
    }

    private static void writeBoundedStrokes(
            FriendlyByteBuf buf,
            List<MapStroke> strokes,
            int maxCountLimit,
            int maxTotalPoints
    ) {
        int maxCount = Math.min(strokes.size(), maxCountLimit);
        int budget = maxTotalPoints;
        int actualCount = 0;
        for (int i = 0; i < maxCount; i++) {
            int pts = Math.min(strokes.get(i).getPoints().size(), 2048);
            if (actualCount > 0 && budget - pts < 0) {
                break;
            }
            budget -= pts;
            actualCount++;
        }
        buf.writeVarInt(actualCount);
        for (int i = 0; i < actualCount; i++) {
            writeStroke(buf, strokes.get(i));
        }
    }

    private static List<MapStroke> readBoundedStrokes(
            FriendlyByteBuf buf,
            int maxCountLimit,
            int maxTotalPoints
    ) {
        int count = Math.min(Math.max(0, buf.readVarInt()), maxCountLimit);
        List<MapStroke> list = new ArrayList<>(Math.min(count, 256));
        int remainingBudget = maxTotalPoints;
        for (int i = 0; i < count; i++) {
            int maxPts = Math.min(2048, remainingBudget);
            MapStroke s = readStroke(buf, maxPts);
            remainingBudget = Math.max(0, remainingBudget - s.getPoints().size());
            if (!s.getPoints().isEmpty()) {
                list.add(s);
            }
        }
        return list;
    }

    private static void writeStrokePoints(FriendlyByteBuf buf, List<MapStroke.Point> points) {
        int size = Math.min(points.size(), 2048);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            MapStroke.Point pt = points.get(i);
            buf.writeFloat(pt.x());
            buf.writeFloat(pt.z());
        }
    }

    private static List<MapStroke.Point> readStrokePoints(FriendlyByteBuf buf, int maxKeep) {
        int rawSize = Math.min(Math.max(0, buf.readVarInt()), 2048);
        int keep = Math.min(rawSize, Math.max(0, maxKeep));
        List<MapStroke.Point> pts = new ArrayList<>(keep);
        for (int i = 0; i < keep; i++) {
            pts.add(new MapStroke.Point(buf.readFloat(), buf.readFloat()));
        }
        int skip = rawSize - keep;
        if (skip > 0) {
            buf.skipBytes(skip * 8);
        }
        return pts;
    }

    private static void writeStroke(FriendlyByteBuf buf, MapStroke s) {
        writeStrokePoints(buf, s.getPoints());
        buf.writeInt(s.getColor());
        buf.writeFloat(s.getWidth());
        buf.writeBoolean(s.isDye());
        writeOptionalUuid(buf, s.getAuthorUuid());
        writeSafeUtf(buf, s.getAuthorName(), 64);
    }

    private static MapStroke readStroke(FriendlyByteBuf buf, int maxKeepPoints) {
        List<MapStroke.Point> pts = readStrokePoints(buf, maxKeepPoints);
        int color = buf.readInt();
        float width = buf.readFloat();
        boolean isDye = buf.readBoolean();
        UUID authorUuid = readOptionalUuid(buf);
        String authorName = buf.readUtf(64);
        return new MapStroke(pts, color, width, isDye, authorUuid, authorName.isEmpty() ? null : authorName);
    }

    private static void writeChunkEntries(FriendlyByteBuf buf, List<ChunkEntry> entries, int maxCount) {
        int count = Math.min(entries.size(), maxCount);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ChunkEntry e = entries.get(i);
            buf.writeInt(e.cx());
            buf.writeInt(e.cz());
            BlockStateCodecUtil.writeTerrainToBuf(buf, e.terrain());
        }
    }

    private static List<ChunkEntry> readChunkEntries(FriendlyByteBuf buf, int maxCount) {
        int count = Math.min(Math.max(0, buf.readVarInt()), maxCount);
        List<ChunkEntry> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int cx = buf.readInt();
            int cz = buf.readInt();
            out.add(new ChunkEntry(cx, cz, BlockStateCodecUtil.readTerrainFromBuf(buf)));
        }
        return out;
    }
}
