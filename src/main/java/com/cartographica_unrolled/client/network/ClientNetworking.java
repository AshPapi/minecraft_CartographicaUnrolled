package com.cartographica_unrolled.client.network;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.map.AtlasRegionCache;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.client.ui.AtlasEditorState;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = CartographicaUnrolled.MOD_ID, value = Dist.CLIENT)
public final class ClientNetworking {
    private ClientNetworking() {
    }

    @SubscribeEvent
    public static void onRegisterClientPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(AtlasPayloads.ForceCloseAtlasPayload.TYPE, (payload, context) -> onForceClose());
        event.register(AtlasPayloads.SyncAtlasMetaS2CPayload.TYPE, (payload, context) -> onSyncMeta(payload));
        event.register(AtlasPayloads.SyncAtlasChunksS2CPayload.TYPE, (payload, context) -> onSyncChunks(payload));
        event.register(AtlasPayloads.SyncSharePersonalS2CPayload.TYPE,
                (payload, context) -> AtlasEditorState.setSharePersonalWithAll(payload.share()));
        event.register(AtlasPayloads.SyncDeathPosS2CPayload.TYPE,
                (payload, context) -> AtlasLiveTracker.setDeathPos(payload.dimension(), payload.present(), payload.x(), payload.z()));
        event.register(AtlasPayloads.SyncOtherPlayersS2CPayload.TYPE,
                (payload, context) -> AtlasLiveTracker.setSyncedOtherPlayers(payload.dimension(), payload.players()));
    }

    public static String currentDimensionKey() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            return mc.level.dimension().identifier().toString();
        }
        String active = AtlasRegionCache.getActiveDimension();
        return active.isEmpty() ? AtlasSavedData.DEFAULT_DIMENSION : active;
    }

    public static void onForceClose() {
        AtlasStateManager.onServerForceClose();
    }

    public static void onSyncMeta(AtlasPayloads.SyncAtlasMetaS2CPayload payload) {
        if (AtlasSavedData.SHARED_DIMENSION.equals(payload.dimension())) {
            AtlasEditorState.loadSharedFromServer(payload.markers(), payload.strokes());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && !payload.dimension().equals(mc.level.dimension().identifier().toString())) {
            return;
        }
        AtlasRegionCache.bindWorldAndDimension(payload.worldCacheId(), payload.dimension());
        AtlasEditorState.loadFromServer(payload.markers(), payload.strokes());
    }

    public static void onSyncChunks(AtlasPayloads.SyncAtlasChunksS2CPayload payload) {
        if (!payload.dimension().equals(currentDimensionKey())) {
            return;
        }
        AtlasRegionCache.ensureDimension(payload.dimension());
        for (AtlasPayloads.ChunkEntry entry : payload.entries()) {
            AtlasRegionCache.putChunk(entry.cx(), entry.cz(), entry.terrain());
        }
    }

    private static void sendToServer(CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(payload);
        }
    }

    public static void sendAtlasAction(AtlasPayloads.SetAtlasActionPayload.Action action) {
        sendToServer(new AtlasPayloads.SetAtlasActionPayload(action));
    }

    public static void sendSetSharePersonal(boolean share) {
        sendToServer(new AtlasPayloads.SetSharePersonalPayload(share));
    }

    public static void sendClearDeathPos() {
        sendToServer(new AtlasPayloads.ClearDeathPosPayload(currentDimensionKey()));
    }

    public static void sendAddStroke(MapStroke stroke) {
        sendToServer(new AtlasPayloads.AddStrokePayload(
                currentDimensionKey(),
                stroke.getPoints(),
                stroke.getColor(),
                stroke.getWidth(),
                stroke.isDye()
        ));
    }

    public static void sendReplaceStrokes(String dimension, List<MapStroke> strokes) {
        sendToServer(new AtlasPayloads.ReplaceStrokesPayload(dimension, strokes));
    }

    public static void sendUpsertMarker(MapMarker marker) {
        sendUpsertMarker(currentDimensionKey(), marker);
    }

    public static void sendUpsertMarker(String dimension, MapMarker marker) {
        sendToServer(new AtlasPayloads.UpsertMarkerPayload(
                dimension,
                marker.getId(),
                marker.getWorldX(),
                marker.getWorldZ(),
                marker.getType().ordinal(),
                marker.getLabel()
        ));
    }

    public static void sendRemoveMarker(String dimension, UUID id) {
        sendToServer(new AtlasPayloads.RemoveMarkerPayload(dimension, id));
    }

    public static void sendExploreChunks(String dimension, List<AtlasPayloads.ChunkEntry> entries) {
        if (!entries.isEmpty()) {
            sendToServer(new AtlasPayloads.ExploreChunksBatchPayload(dimension, entries));
        }
    }
}
