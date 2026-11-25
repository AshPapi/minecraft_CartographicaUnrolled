package com.cartographica_unrolled.network;

import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.server.ServerEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModNetworking {
    private static final class PlayerRateState {
        long windowSec;
        int editWeight;
        long lastActionNanos;
        long lastShareToggleNanos;
    }

    @FunctionalInterface
    private interface EditHandler {
        void apply(ServerPlayer sp, ServerLevel level, String dim, AtlasSavedData data);
    }

    private static final Map<UUID, PlayerRateState> PLAYER_RATE_LIMITS = new ConcurrentHashMap<>();

    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("4");
        registrar.playToServer(AtlasPayloads.AddStrokePayload.TYPE, AtlasPayloads.AddStrokePayload.STREAM_CODEC, ModNetworking::handleAddStroke);
        registrar.playToServer(AtlasPayloads.ReplaceStrokesPayload.TYPE, AtlasPayloads.ReplaceStrokesPayload.STREAM_CODEC, ModNetworking::handleReplaceStrokes);
        registrar.playToServer(AtlasPayloads.UpsertMarkerPayload.TYPE, AtlasPayloads.UpsertMarkerPayload.STREAM_CODEC, ModNetworking::handleUpsertMarker);
        registrar.playToServer(AtlasPayloads.RemoveMarkerPayload.TYPE, AtlasPayloads.RemoveMarkerPayload.STREAM_CODEC, ModNetworking::handleRemoveMarker);
        registrar.playToServer(AtlasPayloads.ExploreChunksBatchPayload.TYPE, AtlasPayloads.ExploreChunksBatchPayload.STREAM_CODEC, ModNetworking::handleExploreChunks);
        registrar.playToServer(AtlasPayloads.SetAtlasActionPayload.TYPE, AtlasPayloads.SetAtlasActionPayload.STREAM_CODEC, ModNetworking::handleSetAtlasAction);
        registrar.playToServer(AtlasPayloads.SetSharePersonalPayload.TYPE, AtlasPayloads.SetSharePersonalPayload.STREAM_CODEC, ModNetworking::handleSetSharePersonal);
        registrar.playToServer(AtlasPayloads.ClearDeathPosPayload.TYPE, AtlasPayloads.ClearDeathPosPayload.STREAM_CODEC, ModNetworking::handleClearDeathPos);

        registrar.playToClient(AtlasPayloads.ForceCloseAtlasPayload.TYPE, AtlasPayloads.ForceCloseAtlasPayload.STREAM_CODEC);
        registrar.playToClient(AtlasPayloads.SyncAtlasMetaS2CPayload.TYPE, AtlasPayloads.SyncAtlasMetaS2CPayload.STREAM_CODEC);
        registrar.playToClient(AtlasPayloads.SyncAtlasChunksS2CPayload.TYPE, AtlasPayloads.SyncAtlasChunksS2CPayload.STREAM_CODEC);
        registrar.playToClient(AtlasPayloads.SyncSharePersonalS2CPayload.TYPE, AtlasPayloads.SyncSharePersonalS2CPayload.STREAM_CODEC);
        registrar.playToClient(AtlasPayloads.SyncDeathPosS2CPayload.TYPE, AtlasPayloads.SyncDeathPosS2CPayload.STREAM_CODEC);
        registrar.playToClient(AtlasPayloads.SyncOtherPlayersS2CPayload.TYPE, AtlasPayloads.SyncOtherPlayersS2CPayload.STREAM_CODEC);
    }

    private static boolean allowEditPacket(UUID playerId, int weight) {
        PlayerRateState state = PLAYER_RATE_LIMITS.computeIfAbsent(playerId, k -> new PlayerRateState());
        long nowSec = System.nanoTime() / 1_000_000_000L;
        synchronized (state) {
            if (state.windowSec != nowSec) {
                state.windowSec = nowSec;
                state.editWeight = 0;
            }
            if (state.editWeight + weight > 45) {
                return false;
            }
            state.editWeight += weight;
            return true;
        }
    }

    private static boolean allowAtlasAction(UUID playerId, AtlasPayloads.SetAtlasActionPayload.Action action) {
        if (action == AtlasPayloads.SetAtlasActionPayload.Action.FORCE_CLOSE) {
            return true;
        }
        PlayerRateState state = PLAYER_RATE_LIMITS.computeIfAbsent(playerId, k -> new PlayerRateState());
        long now = System.nanoTime();
        synchronized (state) {
            if (now - state.lastActionNanos < 75_000_000L) {
                return false;
            }
            state.lastActionNanos = now;
            return true;
        }
    }

    private static boolean allowShareToggle(UUID playerId) {
        PlayerRateState state = PLAYER_RATE_LIMITS.computeIfAbsent(playerId, k -> new PlayerRateState());
        long now = System.nanoTime();
        synchronized (state) {
            if (now - state.lastShareToggleNanos < 250_000_000L) {
                return false;
            }
            state.lastShareToggleNanos = now;
            return true;
        }
    }

    private static void enqueueEdit(
            IPayloadContext context,
            int weight,
            String rawDim,
            boolean allowShared,
            EditHandler handler
    ) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!allowEditPacket(sp.getUUID(), weight)) {
                return;
            }
            String dim = resolveEditDimension(sp, rawDim);
            if (dim == null || (!allowShared && AtlasSavedData.SHARED_DIMENSION.equals(dim))) {
                return;
            }
            ServerLevel level = sp.level();
            handler.apply(sp, level, dim, AtlasSavedData.get(level));
        });
    }

    private static void handleAddStroke(AtlasPayloads.AddStrokePayload payload, IPayloadContext context) {
        enqueueEdit(context, 1, payload.dimension(), true, (sp, level, dim, data) -> {
            if (payload.points().isEmpty() || !Float.isFinite(payload.width())) {
                return;
            }
            for (MapStroke.Point pt : payload.points()) {
                if (!isValidWorldCoord(pt.x()) || !isValidWorldCoord(pt.z())) {
                    return;
                }
            }
            AtlasSavedData.DimensionAtlasData dimData = resolveTargetData(data, sp, dim);
            if (dimData.strokes().size() >= AtlasPayloads.MAX_STROKES) {
                return;
            }
            float clampedWidth = Mth.clamp(payload.width(), 0.25f, 32.0f);
            boolean shared = AtlasSavedData.SHARED_DIMENSION.equals(dim);
            dimData.strokes().add(new MapStroke(
                    payload.points(),
                    payload.color(),
                    clampedWidth,
                    payload.isDye(),
                    shared ? sp.getUUID() : null,
                    shared ? sp.getName().getString() : null
            ));
            data.setDirty();
            AtlasServerSync.broadcastSharedIfNeeded(sp, dim, data);
        });
    }

    private static void handleReplaceStrokes(AtlasPayloads.ReplaceStrokesPayload payload, IPayloadContext context) {
        enqueueEdit(context, 5, payload.dimension(), true, (sp, level, dim, data) -> {
            for (MapStroke s : payload.strokes()) {
                if (!isValidStroke(s)) {
                    return;
                }
            }
            AtlasSavedData.DimensionAtlasData dimData = resolveTargetData(data, sp, dim);
            boolean shared = AtlasSavedData.SHARED_DIMENSION.equals(dim);
            if (shared) {
                UUID me = sp.getUUID();
                String myName = sp.getName().getString();
                dimData.strokes().removeIf(s -> s.getAuthorUuid() == null || me.equals(s.getAuthorUuid()));
                int remain = Math.max(0, AtlasPayloads.MAX_STROKES - dimData.strokes().size());
                int addCount = Math.min(payload.strokes().size(), remain);
                for (int i = 0; i < addCount; i++) {
                    MapStroke src = payload.strokes().get(i);
                    dimData.strokes().add(new MapStroke(
                            src.getPoints(), src.getColor(), src.getWidth(), src.isDye(), me, myName
                    ));
                }
            } else {
                dimData.strokes().clear();
                dimData.strokes().addAll(payload.strokes());
            }
            data.setDirty();
            AtlasServerSync.broadcastSharedIfNeeded(sp, dim, data);
        });
    }

    private static void handleUpsertMarker(AtlasPayloads.UpsertMarkerPayload payload, IPayloadContext context) {
        enqueueEdit(context, 1, payload.dimension(), true, (sp, level, dim, data) -> {
            if (!isValidWorldCoord(payload.x()) || !isValidWorldCoord(payload.z())) {
                return;
            }
            MapMarker.BannerType[] types = MapMarker.BannerType.values();
            if (payload.typeOrd() < 0 || payload.typeOrd() >= types.length) {
                return;
            }
            AtlasSavedData.DimensionAtlasData dimData = resolveTargetData(data, sp, dim);
            List<MapMarker> markers = dimData.markers();
            boolean shared = AtlasSavedData.SHARED_DIMENSION.equals(dim);
            UUID me = sp.getUUID();
            String safeLabel = sanitizeLabel(payload.label());
            for (MapMarker m : markers) {
                if (m.getId().equals(payload.id())) {
                    if (shared && m.getAuthorUuid() != null && !me.equals(m.getAuthorUuid())) {
                        return;
                    }
                    m.setLabel(safeLabel);
                    data.setDirty();
                    AtlasServerSync.broadcastSharedIfNeeded(sp, dim, data);
                    return;
                }
            }
            if (markers.size() >= 1024) {
                return;
            }
            markers.add(new MapMarker(
                    payload.id(),
                    payload.x(),
                    payload.z(),
                    types[payload.typeOrd()],
                    safeLabel,
                    shared ? me : null,
                    shared ? sp.getName().getString() : null
            ));
            data.setDirty();
            AtlasServerSync.broadcastSharedIfNeeded(sp, dim, data);
        });
    }

    private static void handleRemoveMarker(AtlasPayloads.RemoveMarkerPayload payload, IPayloadContext context) {
        enqueueEdit(context, 1, payload.dimension(), true, (sp, level, dim, data) -> {
            AtlasSavedData.DimensionAtlasData dimData = resolveTargetData(data, sp, dim);
            boolean shared = AtlasSavedData.SHARED_DIMENSION.equals(dim);
            UUID me = sp.getUUID();
            boolean removed = dimData.markers().removeIf(m ->
                    m.getId().equals(payload.id())
                            && (!shared || m.getAuthorUuid() == null || me.equals(m.getAuthorUuid()))
            );
            if (removed) {
                data.setDirty();
                AtlasServerSync.broadcastSharedIfNeeded(sp, dim, data);
            }
        });
    }

    private static void handleExploreChunks(AtlasPayloads.ExploreChunksBatchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            ServerLevel level = sp.level();
            String targetDim = resolveDimension(level, payload.dimension());
            if (targetDim == null || AtlasSavedData.SHARED_DIMENSION.equals(targetDim)) {
                return;
            }
            boolean currentDim = AtlasSavedData.dimensionKey(level).equals(targetDim);
            int pcx = sp.chunkPosition().x();
            int pcz = sp.chunkPosition().z();
            AtlasSavedData data = AtlasSavedData.get(level);
            AtlasSavedData.DimensionAtlasData dimData = data.getOrCreate(sp.getUUID(), targetDim);
            boolean changed = false;
            for (AtlasPayloads.ChunkEntry entry : payload.entries()) {
                boolean inRange = currentDim
                        ? (Math.abs((long) entry.cx() - pcx) <= 36 && Math.abs((long) entry.cz() - pcz) <= 36)
                        : ServerEvents.isRecentPortalChunk(sp.getUUID(), targetDim, entry.cx(), entry.cz(), level.getGameTime());
                if (inRange && dimData.putChunkIfChanged(entry.cx(), entry.cz(), entry.terrain(), 32768)) {
                    changed = true;
                }
            }
            if (changed) {
                data.setDirty();
            }
        });
    }

    private static void handleSetSharePersonal(AtlasPayloads.SetSharePersonalPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            if (!allowShareToggle(sp.getUUID())) {
                return;
            }
            ServerLevel level = sp.level();
            AtlasSavedData data = AtlasSavedData.get(level);
            if (data.isSharingPersonal(sp.getUUID()) == payload.share()) {
                return;
            }
            data.setSharingPersonal(sp.getUUID(), payload.share());
            data.setDirty();
            AtlasServerSync.broadcastSharedToAll(level, data);
        });
    }

    private static void handleSetAtlasAction(AtlasPayloads.SetAtlasActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp && allowAtlasAction(sp.getUUID(), payload.action())) {
                ServerEvents.handleClientAction(sp, payload.action());
            }
        });
    }

    private static void handleClearDeathPos(AtlasPayloads.ClearDeathPosPayload payload, IPayloadContext context) {
        enqueueEdit(context, 1, payload.dimension(), false, (sp, level, dim, data) -> {
            data.clearDeathPos(sp.getUUID(), dim);
            AtlasServerSync.sendDeathPos(sp, dim, null);
        });
    }

    private static boolean isValidWorldCoord(double v) {
        return Double.isFinite(v) && Math.abs(v) <= 30_000_000.0;
    }

    private static boolean isValidStroke(MapStroke s) {
        if (s == null || !Float.isFinite(s.getWidth()) || s.getWidth() <= 0.0f || s.getWidth() > 64.0f) {
            return false;
        }
        List<MapStroke.Point> pts = s.getPoints();
        if (pts == null || pts.isEmpty()) {
            return false;
        }
        for (MapStroke.Point pt : pts) {
            if (!isValidWorldCoord(pt.x()) || !isValidWorldCoord(pt.z())) {
                return false;
            }
        }
        return true;
    }

    private static AtlasSavedData.DimensionAtlasData resolveTargetData(AtlasSavedData data, ServerPlayer sp, String dim) {
        return AtlasSavedData.SHARED_DIMENSION.equals(dim)
                ? data.getSharedData(AtlasSavedData.dimensionKey(sp.level()))
                : data.getOrCreate(sp.getUUID(), dim);
    }

    private static @Nullable String resolveEditDimension(ServerPlayer sp, String requested) {
        String currentDim = AtlasSavedData.dimensionKey(sp.level());
        if (requested == null || requested.isEmpty() || currentDim.equals(requested)) {
            return currentDim;
        }
        return AtlasSavedData.SHARED_DIMENSION.equals(requested) ? AtlasSavedData.SHARED_DIMENSION : null;
    }

    private static @Nullable String resolveDimension(ServerLevel level, String requested) {
        if (requested == null || requested.isEmpty()) {
            return AtlasSavedData.dimensionKey(level);
        }
        if (AtlasSavedData.SHARED_DIMENSION.equals(requested)) {
            return AtlasSavedData.SHARED_DIMENSION;
        }
        if (Identifier.tryParse(requested) == null) {
            return null;
        }
        for (ServerLevel serverLevel : level.getServer().getAllLevels()) {
            if (AtlasSavedData.dimensionKey(serverLevel).equals(requested)) {
                return requested;
            }
        }
        return null;
    }

    private static String sanitizeLabel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(32);
        int count = 0;
        for (int i = 0; i < raw.length() && count < 32; ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isISOControl(cp)) {
                sb.appendCodePoint(cp);
                count++;
            }
        }
        return sb.toString();
    }

    public static void sendDeathPos(ServerPlayer player, String dim, AtlasSavedData.@Nullable DeathPos deathPos) {
        AtlasServerSync.sendDeathPos(player, dim, deathPos);
    }

    public static void syncOtherPlayersTo(ServerPlayer viewer) {
        AtlasServerSync.syncOtherPlayersTo(viewer, false);
    }

    public static void sendForceClose(ServerPlayer player) {
        AtlasServerSync.sendForceClose(player);
    }

    public static void clearChunkSync(UUID playerId) {
        AtlasServerSync.clearPlayer(playerId);
        PLAYER_RATE_LIMITS.remove(playerId);
    }

    public static void clearAllChunkSyncs() {
        AtlasServerSync.clearAll();
        PLAYER_RATE_LIMITS.clear();
    }

    public static void tickChunkSync(ServerPlayer player) {
        AtlasServerSync.tickChunkSync(player);
    }

    public static void syncAtlasToPlayer(ServerPlayer player) {
        AtlasServerSync.syncAtlasToPlayer(player, true);
    }

    public static void syncAtlasToPlayer(ServerPlayer player, boolean includeChunks) {
        AtlasServerSync.syncAtlasToPlayer(player, includeChunks);
    }
}
