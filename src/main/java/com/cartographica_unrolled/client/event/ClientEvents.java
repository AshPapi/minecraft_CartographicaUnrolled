package com.cartographica_unrolled.client.event;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.atmosphere.AtlasAtmosphere;
import com.cartographica_unrolled.client.atmosphere.AtlasLighting;
import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.client.map.AtlasDiskCache;
import com.cartographica_unrolled.client.map.AtlasInteriorOverlay;
import com.cartographica_unrolled.client.map.AtlasRegionCache;
import com.cartographica_unrolled.client.map.AtlasRouteManager;
import com.cartographica_unrolled.client.map.BlockTopTextureCache;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.client.render.AtlasPlayerLayer;
import com.cartographica_unrolled.client.render.AtlasRenderPipelines;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.client.state.ClientAtlasAnimTracker;
import com.cartographica_unrolled.client.ui.AtlasEditorState;
import com.cartographica_unrolled.client.ui.AtlasSidebar;
import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.item.RolledAtlasItem;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.cartographica_unrolled.world.AtlasBlockClassifier;
import com.cartographica_unrolled.world.AtlasTerrainSampler;
import com.cartographica_unrolled.world.IndoorRoomScanner;
import com.mojang.blaze3d.platform.InputConstants;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@EventBusSubscriber(modid = CartographicaUnrolled.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "main")
    );

    public static final KeyMapping OPEN_ATLAS_KEY = new KeyMapping(
            "key.cartographica_unrolled.open_atlas",
            InputConstants.KEY_M,
            CATEGORY
    );

    private static final LongOpenHashSet SCANNED_THIS_SESSION = new LongOpenHashSet();
    private static final int MAX_SCAN_RADIUS = 20;
    private static final int CHUNKS_PER_TICK = 6;
    private static final int CLOSED_CHUNKS_PER_TICK = 2;
    private static final long TICK_WORK_BUDGET_NS = 2_000_000L;
    private static final long CLOSED_TICK_WORK_BUDGET_NS = 500_000L;
    private static final int[][] SPIRAL_OFFSETS = buildSpiralOffsets(MAX_SCAN_RADIUS);

    private static int spiralCursor = 0;
    private static int localRefreshTimer = 0;
    private static int bgRefreshTimer = 0;
    private static int bgRefreshIndex = 0;
    private static int localRefreshIndex = 0;
    private static int syncFlushTimer = 0;
    private static String pendingDimension = "";

    private static boolean isPlayerIndoors = false;
    private static int lastIndoorPlayerY = Integer.MIN_VALUE;
    private static int lastIndoorChunkX = Integer.MIN_VALUE;
    private static int lastIndoorChunkZ = Integer.MIN_VALUE;
    private static int lastIndoorBlockX = Integer.MIN_VALUE;
    private static int lastIndoorBlockZ = Integer.MIN_VALUE;
    private static int indoorMoveDebounceTicks = 0;
    private static int indoorRefreshTimer = 0;
    private static final int[] LAST_INDOOR_MASK_HASHES = new int[9];

    private static final BlockPos.MutableBlockPos INDOOR_CHECK_POS = new BlockPos.MutableBlockPos();
    private static final Long2ObjectLinkedOpenHashMap<AtlasChunkTerrain> PENDING_SYNC = new Long2ObjectLinkedOpenHashMap<>();

    private static int[][] buildSpiralOffsets(int maxR) {
        List<int[]> list = new ArrayList<>((maxR * 2 + 1) * (maxR * 2 + 1));
        for (int dz = -maxR; dz <= maxR; dz++) {
            for (int dx = -maxR; dx <= maxR; dx++) {
                list.add(new int[]{dx, dz, dx * dx + dz * dz});
            }
        }
        list.sort(Comparator.comparingInt(a -> a[2]));
        int[][] out = new int[list.size()][2];
        for (int i = 0; i < list.size(); i++) {
            out[i][0] = list.get(i)[0];
            out[i][1] = list.get(i)[1];
        }
        return out;
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "atlas_reload"),
                new SimplePreparableReloadListener<Boolean>() {
                    @Override
                    protected Boolean prepare(ResourceManager resources, ProfilerFiller profiler) {
                        return Boolean.TRUE;
                    }

                    @Override
                    protected void apply(Boolean prepared, ResourceManager resources, ProfilerFiller profiler) {
                        ParchmentTexture.invalidateCache();
                        AtlasSidebar.invalidateCache();
                        BlockTopTextureCache.invalidateAll();
                        AtlasBlockClassifier.clearCache();
                        AtlasRouteManager.onClientReloadOrDisconnect();
                        AtlasRenderPipelines.clearRenderTypeCache();
                        AtlasInteriorOverlay.clear();
                        SCANNED_THIS_SESSION.clear();
                        Arrays.fill(LAST_INDOOR_MASK_HASHES, 0);
                        spiralCursor = 0;
                        AtlasRegionCache.invalidateDiskRegionImages();
                        AtlasRegionCache.repaintAll();
                    }
                });
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        RolledAtlasItem.clientUnfoldHook = AtlasStateManager::tryStartUnfolding;
        event.registerCategory(CATEGORY);
        event.register(OPEN_ATLAS_KEY);
    }

    @SubscribeEvent
    public static void onRegisterRenderPipelines(RegisterRenderPipelinesEvent event) {
        AtlasRenderPipelines.registerPipelines(event);
    }

    @SubscribeEvent
    public static void onAddPlayerLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerModelType skin : event.getSkins()) {
            AvatarRenderer<AbstractClientPlayer> renderer = event.getPlayerRenderer(skin);
            if (renderer != null) {
                renderer.addLayer(new AtlasPlayerLayer(renderer));
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterRenderStateModifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(AtlasPlayerLayer.STATE_MODIFIER);
    }

    public static void onRemoteWorldSwitch() {
        SCANNED_THIS_SESSION.clear();
        PENDING_SYNC.clear();
        pendingDimension = "";
        spiralCursor = 0;
        localRefreshTimer = 0;
        localRefreshIndex = 0;
        bgRefreshTimer = 0;
        bgRefreshIndex = 0;
        syncFlushTimer = 0;
        resetIndoorState();
        ClientAtlasAnimTracker.clear();
        AtlasInteriorOverlay.clear();
        AtlasRouteManager.clear();
        AtlasEditorState.clearAll();
        AtlasLiveTracker.clearAll();
        AtlasAtmosphere.clear();
        AtlasLighting.reset();
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!AtlasRegionCache.getActiveDimension().isEmpty()) {
            AtlasRegionCache.clearAll();
            AtlasDiskCache.clearWorldKey();
            onRemoteWorldSwitch();
        }
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SCANNED_THIS_SESSION.clear();
        PENDING_SYNC.clear();
        pendingDimension = "";
        spiralCursor = 0;
        localRefreshTimer = 0;
        localRefreshIndex = 0;
        bgRefreshTimer = 0;
        bgRefreshIndex = 0;
        syncFlushTimer = 0;
        resetIndoorState();
        AtlasStateManager.onClientDisconnect();
        ClientAtlasAnimTracker.clear();
        AtlasRegionCache.flushDiskCache();
        AtlasRegionCache.clearAll();
        AtlasDiskCache.clearWorldKey();
        AtlasInteriorOverlay.clear();
        AtlasRouteManager.onClientReloadOrDisconnect();
        AtlasEditorState.clearAll();
        AtlasLiveTracker.clearAll();
        AtlasAtmosphere.clear();
        AtlasLighting.reset();
        AtlasBlockClassifier.clearCache();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        AtlasLighting.tick(mc);
        ClientLevel level = mc.level;
        String dimKey = level.dimension().identifier().toString();
        if (!dimKey.equals(pendingDimension)) {
            if (!pendingDimension.isEmpty()) {
                flushPendingSync(pendingDimension);
            }
            SCANNED_THIS_SESSION.clear();
            PENDING_SYNC.clear();
            pendingDimension = dimKey;
            spiralCursor = 0;
            localRefreshTimer = 0;
            localRefreshIndex = 0;
            bgRefreshTimer = 0;
            bgRefreshIndex = 0;
            syncFlushTimer = 0;
            resetIndoorState();
            ClientAtlasAnimTracker.clear();
            AtlasEditorState.clearAnnotationsForDimensionSwitch();
            AtlasInteriorOverlay.clear();
            AtlasAtmosphere.clear();
        }
        AtlasRegionCache.ensureDimension(dimKey);
        AtlasAtmosphere.tick(mc, isPlayerIndoors);

        int pcx = mc.player.chunkPosition().x();
        int pcz = mc.player.chunkPosition().z();
        int playerWx = Mth.floor(mc.player.getX());
        int playerY = Mth.floor(mc.player.getY());
        int playerWz = Mth.floor(mc.player.getZ());
        int activeRadius = Mth.clamp(mc.options.renderDistance().get(), 2, MAX_SCAN_RADIUS);
        int maxDistSq = activeRadius * activeRadius;

        AtlasPhase phase = AtlasStateManager.getPhase();
        boolean mapClosed = phase == AtlasPhase.CLOSED;
        int chunksPerTick = mapClosed ? CLOSED_CHUNKS_PER_TICK : CHUNKS_PER_TICK;
        long tickDeadlineNs = System.nanoTime() + (mapClosed ? CLOSED_TICK_WORK_BUDGET_NS : TICK_WORK_BUDGET_NS);

        if (mapClosed || phase == AtlasPhase.READING_UI) {
            AtlasRegionCache.processBackgroundBudget(8);
        }

        boolean indoorsNow = IndoorRoomScanner.isPlayerIndoors(
                level, mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), INDOOR_CHECK_POS
        );
        if (indoorsNow) {
            int indoorY = playerY;
            if (isPlayerIndoors && !mc.player.onGround() && lastIndoorPlayerY != Integer.MIN_VALUE
                    && Math.abs(playerY - lastIndoorPlayerY) <= 1) {
                indoorY = lastIndoorPlayerY;
            }
            boolean chunkChanged = pcx != lastIndoorChunkX || pcz != lastIndoorChunkZ;
            boolean immediateStructural = !isPlayerIndoors || indoorY != lastIndoorPlayerY || chunkChanged;
            boolean movedIndoors = Math.abs(playerWx - lastIndoorBlockX) + Math.abs(playerWz - lastIndoorBlockZ) >= 2;
            if (movedIndoors) {
                indoorMoveDebounceTicks++;
            } else {
                indoorMoveDebounceTicks = 0;
            }
            isPlayerIndoors = true;
            if (chunkChanged) {
                AtlasRegionCache.pruneInteriorOutside(pcx, pcz, 1);
                Arrays.fill(LAST_INDOOR_MASK_HASHES, 0);
            }
            if (immediateStructural || indoorMoveDebounceTicks >= 3) {
                lastIndoorPlayerY = indoorY;
                lastIndoorChunkX = pcx;
                lastIndoorChunkZ = pcz;
                lastIndoorBlockX = playerWx;
                lastIndoorBlockZ = playerWz;
                indoorMoveDebounceTicks = 0;
                indoorRefreshTimer = 0;
                if (immediateStructural) {
                    Arrays.fill(LAST_INDOOR_MASK_HASHES, 0);
                }
                refreshInteriorNeighborhood(level, pcx, pcz, playerWx, indoorY, playerWz, false);
            } else if (phase != AtlasPhase.UNFOLDING && phase != AtlasPhase.FOLDING) {
                if (++indoorRefreshTimer >= 10 && System.nanoTime() < tickDeadlineNs) {
                    indoorRefreshTimer = 0;
                    refreshInteriorNeighborhood(level, pcx, pcz, playerWx, indoorY, playerWz, true);
                }
            }
        } else if (isPlayerIndoors) {
            resetIndoorState();
            AtlasRegionCache.clearInteriorOverlay();
            AtlasInteriorOverlay.clear();
        }
        AtlasRouteManager.tick(mc.player, dimKey, isPlayerIndoors);

        if (phase != AtlasPhase.UNFOLDING && phase != AtlasPhase.FOLDING && System.nanoTime() < tickDeadlineNs) {
            if (++localRefreshTimer >= 20) {
                localRefreshTimer = 0;
                int idx = localRefreshIndex;
                localRefreshIndex = (localRefreshIndex + 1) % 9;
                scanSingleChunk(level, pcx + (idx % 3) - 1, pcz + (idx / 3) - 1, playerY);
            }
            if (++bgRefreshTimer >= 10 && System.nanoTime() < tickDeadlineNs) {
                bgRefreshTimer = 0;
                int r = Math.min(activeRadius, 8);
                int side = r * 2 + 1;
                int total = side * side;
                int idx = bgRefreshIndex % total;
                bgRefreshIndex = (idx + 1) % total;
                scanSingleChunk(level, pcx + (idx % side) - r, pcz + (idx / side) - r, playerY);
            }

            int scanned = 0;
            int attempts = 0;
            while (scanned < chunksPerTick && attempts < 36 && System.nanoTime() < tickDeadlineNs) {
                attempts++;
                if (spiralCursor >= SPIRAL_OFFSETS.length) {
                    spiralCursor = 0;
                }
                int[] off = SPIRAL_OFFSETS[spiralCursor++];
                if (off[0] * off[0] + off[1] * off[1] > maxDistSq) {
                    spiralCursor = 0;
                    break;
                }
                int cx = pcx + off[0];
                int cz = pcz + off[1];
                long chunkKey = ChunkPos.pack(cx, cz);
                if (!SCANNED_THIS_SESSION.contains(chunkKey) || AtlasRegionCache.needsResample(cx, cz)) {
                    boolean allNeighbors = hasAllNeighborsLoaded(level, cx, cz);
                    if (!allNeighbors && off[0] * off[0] + off[1] * off[1] > 4) {
                        continue;
                    }
                    if (scanSingleChunk(level, cx, cz, playerY)) {
                        if (allNeighbors) {
                            SCANNED_THIS_SESSION.add(chunkKey);
                        }
                        scanned++;
                    }
                }
            }
        }

        if (++syncFlushTimer >= 10 && !PENDING_SYNC.isEmpty()) {
            syncFlushTimer = 0;
            flushPendingSyncBatch(dimKey);
        }

        while (OPEN_ATLAS_KEY.consumeClick()) {
            if (AtlasStateManager.getPhase() == AtlasPhase.UNFOLDING) {
                AtlasStateManager.startFolding();
            } else if (AtlasStateManager.getPhase() == AtlasPhase.CLOSED
                    || AtlasStateManager.getPhase() == AtlasPhase.FOLDING) {
                AtlasStateManager.tryStartUnfolding();
            }
        }
        if (AtlasStateManager.getPhase() != AtlasPhase.CLOSED) {
            while (mc.options.keyDrop.consumeClick()) {
            }
            while (mc.options.keySwapOffhand.consumeClick()) {
            }
        }
    }

    private static void resetIndoorState() {
        isPlayerIndoors = false;
        lastIndoorPlayerY = Integer.MIN_VALUE;
        lastIndoorChunkX = Integer.MIN_VALUE;
        lastIndoorChunkZ = Integer.MIN_VALUE;
        lastIndoorBlockX = Integer.MIN_VALUE;
        lastIndoorBlockZ = Integer.MIN_VALUE;
        indoorMoveDebounceTicks = 0;
        indoorRefreshTimer = 0;
        Arrays.fill(LAST_INDOOR_MASK_HASHES, 0);
    }

    private static void refreshInteriorNeighborhood(
            ClientLevel level,
            int pcx,
            int pcz,
            int playerWx,
            int playerY,
            int playerWz,
            boolean forceWorldCheck
    ) {
        boolean[][] masks = IndoorRoomScanner.buildIndoorSliceMasks(level, pcx, pcz, playerWx, playerY, playerWz);
        AtlasInteriorOverlay.update(level, pcx, pcz, playerY, masks);
        if (masks == null) {
            Arrays.fill(LAST_INDOOR_MASK_HASHES, 0);
            AtlasRegionCache.clearInteriorOverlay();
            return;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                int idx = (dz + 1) * 3 + (dx + 1);
                boolean[] mask = masks[idx];
                int maskHash = IndoorRoomScanner.hashMask(mask);
                if (mask == null) {
                    LAST_INDOOR_MASK_HASHES[idx] = 0;
                    AtlasRegionCache.removeInteriorChunk(cx, cz);
                    continue;
                }
                AtlasChunkTerrain existing = AtlasRegionCache.getInteriorChunk(cx, cz);
                if (!forceWorldCheck && existing != null && maskHash == LAST_INDOOR_MASK_HASHES[idx]) {
                    continue;
                }
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || chunk.isEmpty()) {
                    continue;
                }
                AtlasChunkTerrain terrain = AtlasTerrainSampler.sampleChunk(level, chunk, playerY, true, mask, existing);
                if (terrain != null) {
                    LAST_INDOOR_MASK_HASHES[idx] = maskHash;
                    if (terrain != existing) {
                        AtlasRegionCache.putInteriorChunk(cx, cz, terrain);
                    }
                }
            }
        }
    }

    private static boolean hasAllNeighborsLoaded(ClientLevel level, int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                LevelChunk neighbor = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (neighbor == null || neighbor.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean scanSingleChunk(ClientLevel level, int cx, int cz, int playerY) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null || chunk.isEmpty()) {
            return false;
        }
        AtlasChunkTerrain existing = AtlasRegionCache.getOutdoorChunk(cx, cz);
        AtlasChunkTerrain terrain = AtlasTerrainSampler.sampleChunk(level, chunk, playerY, false, null, existing);
        if (terrain == null) {
            return false;
        }
        if (terrain != existing && AtlasRegionCache.putChunk(cx, cz, terrain)) {
            PENDING_SYNC.put(ChunkPos.pack(cx, cz), terrain);
        }
        return true;
    }

    private static void flushPendingSync(String dimension) {
        while (!PENDING_SYNC.isEmpty()) {
            flushPendingSyncBatch(dimension);
        }
    }

    private static void flushPendingSyncBatch(String dimension) {
        List<com.cartographica_unrolled.network.AtlasPayloads.ChunkEntry> batch = new ArrayList<>(16);
        var it = PENDING_SYNC.long2ObjectEntrySet().fastIterator();
        while (it.hasNext() && batch.size() < 16) {
            var e = it.next();
            long packed = e.getLongKey();
            batch.add(new com.cartographica_unrolled.network.AtlasPayloads.ChunkEntry(ChunkPos.getX(packed), ChunkPos.getZ(packed), e.getValue()));
            it.remove();
        }
        if (!batch.isEmpty()) {
            ClientNetworking.sendExploreChunks(dimension, batch);
        }
    }
}
