package com.cartographica_unrolled.server;

import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.item.RolledAtlasItem;
import com.cartographica_unrolled.network.ModNetworking;
import com.cartographica_unrolled.registry.ModAttachments;
import com.cartographica_unrolled.registry.ModItems;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import com.cartographica_unrolled.CartographicaUnrolled;

@EventBusSubscriber(modid = CartographicaUnrolled.MOD_ID)
public class ServerEvents {
    private static final Map<UUID, Integer> PHASE_TICKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void ensureAtlasEquipped(ServerPlayer player) {
        ItemStack equipped = player.getData(ModAttachments.ATLAS_SLOT);
        if (equipped.isEmpty() || !(equipped.getItem() instanceof RolledAtlasItem)) {
            player.setData(ModAttachments.ATLAS_SLOT, ModItems.ROLLED_ATLAS.toStack());
            player.inventoryMenu.broadcastChanges();
        }
    }

    public static void handleClientAction(ServerPlayer player, com.cartographica_unrolled.network.AtlasPayloads.SetAtlasActionPayload.Action action) {
        ensureAtlasEquipped(player);
        switch (action) {
            case START_UNFOLD -> {
                AtlasPhase current = player.getData(ModAttachments.ATLAS_STATE);
                if (current == AtlasPhase.FOLDING) {
                    if (!AtlasPhase.canStartUnfolding(player)) {
                        abortAtlas(player, true);
                        return;
                    }
                    int foldElapsed = PHASE_TICKS.getOrDefault(player.getUUID(), 0);
                    float progress = 1.0f - Mth.clamp(foldElapsed / (float) AtlasPhase.FOLD_TICKS, 0.0f, 1.0f);
                    int unfoldTicks = Math.round(progress * AtlasPhase.UNFOLD_TICKS);
                    PHASE_TICKS.put(player.getUUID(), unfoldTicks);
                    player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.UNFOLDING);
                    return;
                }
                if (current != AtlasPhase.CLOSED) {
                    abortAtlas(player, true);
                    return;
                }
                if (!AtlasPhase.canStartUnfolding(player)) {
                    abortAtlas(player, true);
                    return;
                }
                PHASE_TICKS.put(player.getUUID(), 0);
                player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.UNFOLDING);
                player.level().playSound(
                        player,
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        SoundEvents.BOOK_PAGE_TURN,
                        SoundSource.PLAYERS,
                        0.85f,
                        0.9f
                );
            }
            case START_FOLD -> {
                AtlasPhase current = player.getData(ModAttachments.ATLAS_STATE);
                if (current == AtlasPhase.READING_UI) {
                    PHASE_TICKS.put(player.getUUID(), 0);
                    player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.FOLDING);
                    player.level().playSound(
                            player,
                            player.getX(),
                            player.getY(),
                            player.getZ(),
                            SoundEvents.BOOK_PAGE_TURN,
                            SoundSource.PLAYERS,
                            0.8f,
                            1.15f
                    );
                } else if (current == AtlasPhase.UNFOLDING) {
                    int unfolded = PHASE_TICKS.getOrDefault(player.getUUID(), AtlasPhase.UNFOLD_TICKS);
                    float ratio = Mth.clamp(unfolded / (float) AtlasPhase.UNFOLD_TICKS, 0.0f, 1.0f);
                    int startFoldTick = Mth.clamp(Math.round((1.0f - ratio) * AtlasPhase.FOLD_TICKS), 0, AtlasPhase.FOLD_TICKS - 1);
                    PHASE_TICKS.put(player.getUUID(), startFoldTick);
                    player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.FOLDING);
                }
            }
            case FORCE_CLOSE -> abortAtlas(player, false);
        }
    }

    public static void abortAtlas(ServerPlayer player, boolean notifyClient) {
        PHASE_TICKS.remove(player.getUUID());
        AtlasPhase current = player.getData(ModAttachments.ATLAS_STATE);
        if (current != AtlasPhase.CLOSED) {
            player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.CLOSED);
        }
        if (notifyClient) {
            ModNetworking.sendForceClose(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAtlasEquipped(player);
            ModNetworking.syncAtlasToPlayer(player);
            LAST_SYNCED_DIM.put(player.getUUID(), player.level().dimension().identifier().toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAtlasEquipped(player);
            String currentDim = player.level().dimension().identifier().toString();
            boolean includeChunks = !currentDim.equals(LAST_SYNCED_DIM.get(player.getUUID()));
            ModNetworking.syncAtlasToPlayer(player, includeChunks);
            if (includeChunks) {
                LAST_SYNCED_DIM.put(player.getUUID(), currentDim);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAtlasEquipped(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            event.getDrops().removeIf(itemEntity -> itemEntity.getItem().getItem() instanceof RolledAtlasItem);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        UUID playerId = player.getUUID();
        String currentDim = player.level().dimension().identifier().toString();
        int cx = player.chunkPosition().x();
        int cz = player.chunkPosition().z();
        PortalOrigin prevPos = LAST_POSITIONS.get(playerId);
        if (prevPos == null || prevPos.cx() != cx || prevPos.cz() != cz || !prevPos.dim().equals(currentDim)) {
            LAST_POSITIONS.put(playerId, new PortalOrigin(currentDim, cx, cz, player.level().getGameTime()));
        }
        ModNetworking.tickChunkSync(player);
        if ((player.tickCount % 20) == 0) {
            ensureAtlasEquipped(player);
        } else if ((player.tickCount % 20) == 10) {
            ModNetworking.syncOtherPlayersTo(player);
        }
        AtlasPhase phase = player.getData(ModAttachments.ATLAS_STATE);
        if (phase == AtlasPhase.CLOSED) {
            return;
        }
        if (!AtlasPhase.canKeepUsing(player)) {
            abortAtlas(player, true);
            return;
        }

        int ticks = PHASE_TICKS.getOrDefault(player.getUUID(), 0) + 1;
        PHASE_TICKS.put(player.getUUID(), ticks);

        if (phase == AtlasPhase.UNFOLDING) {
            if (ticks >= AtlasPhase.UNFOLD_TICKS) {
                PHASE_TICKS.put(player.getUUID(), 0);
                player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.READING_UI);
            }
        } else if (phase == AtlasPhase.FOLDING) {
            if (ticks >= AtlasPhase.FOLD_TICKS) {
                PHASE_TICKS.remove(player.getUUID());
                player.setData(ModAttachments.ATLAS_STATE, AtlasPhase.CLOSED);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.getData(ModAttachments.ATLAS_STATE) != AtlasPhase.CLOSED) {
                abortAtlas(player, true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAtlasEquipped(player);
            if (player.getData(ModAttachments.ATLAS_STATE) != AtlasPhase.CLOSED) {
                abortAtlas(player, true);
            }
            if (player.level() instanceof ServerLevel serverLevel) {
                String dim = AtlasSavedData.dimensionKey(serverLevel);
                AtlasSavedData data = AtlasSavedData.get(serverLevel);
                data.setDeathPos(player.getUUID(), dim, player.getX(), player.getZ());
                ModNetworking.sendDeathPos(player, dim, new AtlasSavedData.DeathPos(player.getX(), player.getZ()));
            }
        }
    }

    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (player.getData(ModAttachments.ATLAS_STATE) != AtlasPhase.CLOSED) {
                abortAtlas(player, true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            abortAtlas(player, false);
            UUID playerId = player.getUUID();
            LAST_PORTALS.remove(playerId);
            LAST_POSITIONS.remove(playerId);
            LAST_SYNCED_DIM.remove(playerId);
            ModNetworking.clearChunkSync(playerId);
        }
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ensureAtlasEquipped(player);
            if (player.getData(ModAttachments.ATLAS_STATE) != AtlasPhase.CLOSED) {
                abortAtlas(player, true);
            }
            String fromDim = event.getFrom().identifier().toString();
            PortalOrigin prev = LAST_POSITIONS.get(player.getUUID());
            if (prev != null && prev.dim().equals(fromDim)) {
                LAST_PORTALS.put(player.getUUID(), new PortalOrigin(fromDim, prev.cx(), prev.cz(), player.level().getGameTime()));
            }
            ModNetworking.syncAtlasToPlayer(player, true);
            LAST_SYNCED_DIM.put(player.getUUID(), player.level().dimension().identifier().toString());
        }
    }

    private record PortalOrigin(String dim, int cx, int cz, long gameTime) {}
    private static final Map<UUID, PortalOrigin> LAST_PORTALS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, PortalOrigin> LAST_POSITIONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<UUID, String> LAST_SYNCED_DIM = new java.util.concurrent.ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PHASE_TICKS.clear();
        LAST_PORTALS.clear();
        LAST_POSITIONS.clear();
        LAST_SYNCED_DIM.clear();
        ModNetworking.clearAllChunkSyncs();
    }

    public static boolean isRecentPortalChunk(UUID playerId, String dim, int cx, int cz, long currentGameTime) {
        PortalOrigin origin = LAST_PORTALS.get(playerId);
        if (origin == null || !origin.dim().equals(dim)) {
            return false;
        }
        if (currentGameTime - origin.gameTime() > 300L) {
            return false;
        }
        return Math.abs((long) cx - origin.cx()) <= 36 && Math.abs((long) cz - origin.cz()) <= 36;
    }
}
