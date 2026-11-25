package com.cartographica_unrolled.network;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.data.AtlasSavedData;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class AtlasServerSync {
    private static final Map<UUID, ArrayDeque<AtlasPayloads.SyncAtlasChunksS2CPayload>> PENDING_CHUNK_SYNCS =
            new ConcurrentHashMap<>();
    private static final Set<UUID> HAD_OTHER_PLAYERS = ConcurrentHashMap.newKeySet();

    private AtlasServerSync() {
    }

    static void clearPlayer(UUID playerId) {
        PENDING_CHUNK_SYNCS.remove(playerId);
        HAD_OTHER_PLAYERS.remove(playerId);
    }

    static void clearAll() {
        PENDING_CHUNK_SYNCS.clear();
        HAD_OTHER_PLAYERS.clear();
    }

    static void sendForceClose(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new AtlasPayloads.ForceCloseAtlasPayload());
    }

    static void sendDeathPos(ServerPlayer player, String dim, AtlasSavedData.@Nullable DeathPos deathPos) {
        if (deathPos == null) {
            PacketDistributor.sendToPlayer(player, new AtlasPayloads.SyncDeathPosS2CPayload(dim, false, 0.0, 0.0));
        } else {
            PacketDistributor.sendToPlayer(player, new AtlasPayloads.SyncDeathPosS2CPayload(dim, true, deathPos.x(), deathPos.z()));
        }
    }

    static void sendSharedToPlayer(ServerPlayer player, String dim, AtlasSavedData data) {
        PacketDistributor.sendToPlayer(player, new AtlasPayloads.SyncAtlasMetaS2CPayload(
                AtlasSavedData.SHARED_DIMENSION,
                data.worldCacheId(),
                data.collectSharedMarkersFor(player.getUUID(), dim),
                data.collectSharedStrokesFor(player.getUUID(), dim)
        ));
    }

    static void broadcastSharedIfNeeded(ServerPlayer sender, String dim, AtlasSavedData data) {
        if (!AtlasSavedData.SHARED_DIMENSION.equals(dim) && !data.isSharingPersonal(sender.getUUID())) {
            return;
        }
        String editedDim = AtlasSavedData.SHARED_DIMENSION.equals(dim)
                ? AtlasSavedData.dimensionKey(sender.level())
                : dim;
        for (ServerPlayer other : sender.level().getServer().getPlayerList().getPlayers()) {
            if (!other.getUUID().equals(sender.getUUID())) {
                String targetDim = AtlasSavedData.dimensionKey(other.level());
                if (targetDim.equals(editedDim)) {
                    sendSharedToPlayer(other, targetDim, data);
                }
            }
        }
    }

    static void broadcastSharedToAll(ServerLevel level, AtlasSavedData data) {
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            sendSharedToPlayer(player, AtlasSavedData.dimensionKey(player.level()), data);
        }
    }

    static void syncOtherPlayersTo(ServerPlayer viewer, boolean forceSend) {
        ServerLevel level = viewer.level();
        String dim = AtlasSavedData.dimensionKey(level);
        List<ServerPlayer> levelPlayers = level.players();
        List<AtlasPayloads.RemotePlayerPos> list = null;
        for (ServerPlayer other : levelPlayers) {
            if (other == viewer || other.isSpectator() || other.isInvisible()) {
                continue;
            }
            if (list == null) {
                list = new ArrayList<>(Math.max(1, levelPlayers.size() - 1));
            }
            list.add(new AtlasPayloads.RemotePlayerPos(
                    other.getUUID(),
                    other.getName().getString(),
                    other.getX(),
                    other.getZ(),
                    other.getYRot()
            ));
        }
        if (list == null) {
            boolean hadPreviously = HAD_OTHER_PLAYERS.remove(viewer.getUUID());
            if (!forceSend && !hadPreviously) {
                return;
            }
            PacketDistributor.sendToPlayer(viewer, new AtlasPayloads.SyncOtherPlayersS2CPayload(dim, List.of()));
            return;
        }
        HAD_OTHER_PLAYERS.add(viewer.getUUID());
        PacketDistributor.sendToPlayer(viewer, new AtlasPayloads.SyncOtherPlayersS2CPayload(dim, list));
    }

    static void tickChunkSync(ServerPlayer player) {
        ArrayDeque<AtlasPayloads.SyncAtlasChunksS2CPayload> queue = PENDING_CHUNK_SYNCS.get(player.getUUID());
        if (queue == null || queue.isEmpty()) {
            return;
        }
        for (int i = 0; i < 3 && !queue.isEmpty(); i++) {
            PacketDistributor.sendToPlayer(player, queue.pollFirst());
        }
        if (queue.isEmpty()) {
            PENDING_CHUNK_SYNCS.remove(player.getUUID());
        }
    }

    static void syncAtlasToPlayer(ServerPlayer player, boolean includeChunks) {
        ServerLevel level = player.level();
        String dim = AtlasSavedData.dimensionKey(level);
        AtlasSavedData saved = AtlasSavedData.get(level);
        AtlasSavedData.DimensionAtlasData dimData = saved.getOrCreate(player.getUUID(), dim);

        PacketDistributor.sendToPlayer(player, new AtlasPayloads.SyncAtlasMetaS2CPayload(
                dim,
                saved.worldCacheId(),
                new ArrayList<>(dimData.markers()),
                new ArrayList<>(dimData.strokes())
        ));
        PacketDistributor.sendToPlayer(player, new AtlasPayloads.SyncSharePersonalS2CPayload(
                saved.isSharingPersonal(player.getUUID())
        ));
        sendSharedToPlayer(player, dim, saved);
        sendDeathPos(player, dim, saved.getDeathPos(player.getUUID(), dim));
        syncOtherPlayersTo(player, true);

        if (includeChunks) {
            PENDING_CHUNK_SYNCS.remove(player.getUUID());
            Long2ObjectMap<AtlasChunkTerrain> chunkMap = dimData.chunksView();
            if (!chunkMap.isEmpty()) {
                ArrayDeque<AtlasPayloads.SyncAtlasChunksS2CPayload> queue = new ArrayDeque<>();
                List<AtlasPayloads.ChunkEntry> batch = new ArrayList<>(AtlasPayloads.S2C_CHUNK_BATCH_SIZE);
                for (Long2ObjectMap.Entry<AtlasChunkTerrain> e : Long2ObjectMaps.fastIterable(chunkMap)) {
                    long packedKey = e.getLongKey();
                    batch.add(new AtlasPayloads.ChunkEntry(
                            ChunkPos.getX(packedKey), ChunkPos.getZ(packedKey), e.getValue()
                    ));
                    if (batch.size() >= AtlasPayloads.S2C_CHUNK_BATCH_SIZE) {
                        queue.add(new AtlasPayloads.SyncAtlasChunksS2CPayload(dim, new ArrayList<>(batch)));
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    queue.add(new AtlasPayloads.SyncAtlasChunksS2CPayload(dim, batch));
                }
                if (!queue.isEmpty()) {
                    PENDING_CHUNK_SYNCS.put(player.getUUID(), queue);
                }
            }
        }
    }
}
