package com.cartographica_unrolled.client.state;

import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.network.AtlasPayloads;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AtlasLiveTracker {
    private static final Map<String, AtlasSavedData.DeathPos> DEATH_BY_DIM = new HashMap<>();
    private static final Map<UUID, AtlasPayloads.RemotePlayerPos> MERGED_SCRATCH = new LinkedHashMap<>();
    private static final List<AtlasPayloads.RemotePlayerPos> VISIBLE_SCRATCH = new ArrayList<>();
    private static String syncedPlayersDim = "";
    private static List<AtlasPayloads.RemotePlayerPos> syncedOtherPlayers = List.of();

    private AtlasLiveTracker() {
    }

    public static void setDeathPos(String dimension, boolean present, double x, double z) {
        if (!present) {
            DEATH_BY_DIM.remove(dimension);
        } else {
            DEATH_BY_DIM.put(dimension, new AtlasSavedData.DeathPos(x, z));
        }
    }

    public static AtlasSavedData.@Nullable DeathPos getDeathPos(String dimension) {
        return DEATH_BY_DIM.get(dimension);
    }

    public static void clearDeathPosLocal(String dimension) {
        DEATH_BY_DIM.remove(dimension);
    }

    public static void setSyncedOtherPlayers(String dimension, List<AtlasPayloads.RemotePlayerPos> players) {
        syncedPlayersDim = dimension;
        syncedOtherPlayers = List.copyOf(players);
    }

    public static List<AtlasPayloads.RemotePlayerPos> getVisibleOtherPlayers(
            @Nullable ClientLevel level,
            @Nullable AbstractClientPlayer localPlayer,
            float partialTick
    ) {
        if (level == null || localPlayer == null) {
            return List.of();
        }
        if (syncedOtherPlayers.isEmpty() && level.players().size() <= 1) {
            return List.of();
        }
        String dim = level.dimension().identifier().toString();
        Map<UUID, AtlasPayloads.RemotePlayerPos> merged = MERGED_SCRATCH;
        merged.clear();
        if (dim.equals(syncedPlayersDim)) {
            for (AtlasPayloads.RemotePlayerPos rp : syncedOtherPlayers) {
                if (!rp.id().equals(localPlayer.getUUID())) {
                    merged.put(rp.id(), rp);
                }
            }
        }
        for (AbstractClientPlayer other : level.players()) {
            if (other == localPlayer || other.isSpectator() || other.isInvisible()) {
                merged.remove(other.getUUID());
                continue;
            }
            Vec3 pos = other.getPosition(partialTick);
            merged.put(other.getUUID(), new AtlasPayloads.RemotePlayerPos(
                    other.getUUID(),
                    other.getName().getString(),
                    pos.x,
                    pos.z,
                    other.getYRot()
            ));
        }
        VISIBLE_SCRATCH.clear();
        VISIBLE_SCRATCH.addAll(merged.values());
        merged.clear();
        return VISIBLE_SCRATCH;
    }

    public static void clearAll() {
        DEATH_BY_DIM.clear();
        MERGED_SCRATCH.clear();
        VISIBLE_SCRATCH.clear();
        syncedPlayersDim = "";
        syncedOtherPlayers = List.of();
    }
}
