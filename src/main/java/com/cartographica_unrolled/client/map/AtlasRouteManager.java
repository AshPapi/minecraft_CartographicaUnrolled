package com.cartographica_unrolled.client.map;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AtlasRouteManager {
    public record RoutePoint(float x, float z) {
    }

    private static final float ARRIVAL_RADIUS_SQ = 16.0f;
    private static final double RECOMPUTE_MOVE_DIST_SQ_OUTDOOR = 16.0;
    private static final double RECOMPUTE_MOVE_DIST_SQ_INDOOR = 4.0;
    private static final int MIN_RECOMPUTE_TICKS = 6;
    private static final int PERIODIC_RECOMPUTE_TICKS = 60;

    private static final ExecutorService ROUTE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Cartographica-RouteFinder");
        t.setDaemon(true);
        return t;
    });

    private static final Object ROUTE_LOCK = new Object();
    private static final AtomicInteger REQUEST_VERSION = new AtomicInteger(0);

    private static volatile boolean hasTarget;
    private static volatile float targetX;
    private static volatile float targetZ;
    private static volatile String targetDimension = "";
    private static volatile List<RoutePoint> routePoints = List.of();
    private static volatile @Nullable LocalWalkGraph lastLocalGraph;
    private static volatile int lastLocalPlayerY;

    private static @Nullable WalkSectionSnapshot workerLastSnapshot;
    private static int workerLastTerrainEpoch = -1;
    private static final FriendlyByteBuf WORKER_CMP_A = new FriendlyByteBuf(Unpooled.buffer(2048));
    private static final FriendlyByteBuf WORKER_CMP_B = new FriendlyByteBuf(Unpooled.buffer(2048));

    private static double lastStartX = Double.NaN;
    private static double lastStartY = Double.NaN;
    private static double lastStartZ = Double.NaN;
    private static boolean lastIndoors;
    private static int ticksSinceRecompute;

    private static final class RenderCacheSlot {
        double playerX = Double.NaN;
        double playerZ = Double.NaN;
        @Nullable List<RoutePoint> source;
        @Nullable LocalWalkGraph graph;
        List<RoutePoint> result = List.of();

        void reset() {
            this.playerX = Double.NaN;
            this.playerZ = Double.NaN;
            this.source = null;
            this.graph = null;
            this.result = List.of();
        }
    }

    private static final RenderCacheSlot RENDER_SLOT_A = new RenderCacheSlot();
    private static final RenderCacheSlot RENDER_SLOT_B = new RenderCacheSlot();
    private static boolean nextRenderSlotB = false;

    private AtlasRouteManager() {
    }

    public static boolean hasTarget() {
        return hasTarget;
    }

    public static float getTargetX() {
        return targetX;
    }

    public static float getTargetZ() {
        return targetZ;
    }

    public static boolean isNearTarget(double worldX, double worldZ, double radius) {
        if (!hasTarget) {
            return false;
        }
        double dx = worldX - targetX;
        double dz = worldZ - targetZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    public static void setTarget(float wx, float wz, double playerX, double playerZ, String dimensionId) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        double playerY = mc.player != null ? mc.player.getY() : 0.0;
        String safeDim = dimensionId == null ? "" : dimensionId;
        lastStartX = playerX;
        lastStartY = playerY;
        lastStartZ = playerZ;
        ticksSinceRecompute = 0;

        double dx = wx - playerX;
        double dz = wz - playerZ;
        if (dx * dx + dz * dz <= 4.0) {
            synchronized (ROUTE_LOCK) {
                REQUEST_VERSION.incrementAndGet();
                targetX = wx;
                targetZ = wz;
                targetDimension = safeDim;
                hasTarget = true;
                routePoints = List.of(
                        new RoutePoint((float) playerX, (float) playerZ),
                        new RoutePoint(wx, wz)
                );
                lastLocalGraph = null;
            }
            return;
        }

        int version;
        synchronized (ROUTE_LOCK) {
            version = REQUEST_VERSION.incrementAndGet();
            if (!safeDim.equals(targetDimension)) {
                routePoints = List.of();
                lastLocalGraph = null;
            }
            targetX = wx;
            targetZ = wz;
            targetDimension = safeDim;
            hasTarget = true;
        }
        submitComputeWithVersion(version, level, playerX, playerY, playerZ, wx, wz, safeDim, false);
    }

    public static void clear() {
        synchronized (ROUTE_LOCK) {
            REQUEST_VERSION.incrementAndGet();
            hasTarget = false;
            targetDimension = "";
            routePoints = List.of();
            lastLocalGraph = null;
        }
        ROUTE_EXECUTOR.execute(() -> {
            if (!hasTarget) {
                workerLastSnapshot = null;
                workerLastTerrainEpoch = -1;
                HybridRouteSolver.shrinkWorkerBuffers();
            }
        });
        lastStartX = Double.NaN;
        lastStartY = Double.NaN;
        lastStartZ = Double.NaN;
        lastIndoors = false;
        ticksSinceRecompute = 0;
        RENDER_SLOT_A.reset();
        RENDER_SLOT_B.reset();
    }

    public static void onClientReloadOrDisconnect() {
        clear();
        LocalWalkGraph.clearStateCache();
    }

    public static void tick(Player player, String dimensionId, boolean indoors) {
        if (!hasTarget) {
            return;
        }
        if (dimensionId == null || !dimensionId.equals(targetDimension)) {
            clear();
            return;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double dx = targetX - px;
        double dz = targetZ - pz;
        if (dx * dx + dz * dz <= ARRIVAL_RADIUS_SQ) {
            clear();
            return;
        }

        ticksSinceRecompute++;
        double movedSq = Double.isNaN(lastStartX)
                ? Double.POSITIVE_INFINITY
                : (px - lastStartX) * (px - lastStartX) + (pz - lastStartZ) * (pz - lastStartZ);
        double moveThresholdSq = indoors ? RECOMPUTE_MOVE_DIST_SQ_INDOOR : RECOMPUTE_MOVE_DIST_SQ_OUTDOOR;
        boolean yChanged = Double.isNaN(lastStartY) || Math.abs(py - lastStartY) >= 1.0;
        boolean indoorChanged = indoors != lastIndoors;

        boolean movedOrStateChanged = (movedSq >= moveThresholdSq || yChanged || indoorChanged)
                && ticksSinceRecompute >= MIN_RECOMPUTE_TICKS;
        boolean periodicDue = !movedOrStateChanged && ticksSinceRecompute >= PERIODIC_RECOMPUTE_TICKS;

        if (movedOrStateChanged || periodicDue) {
            lastStartX = px;
            lastStartY = py;
            lastStartZ = pz;
            lastIndoors = indoors;
            ticksSinceRecompute = 0;
            ClientLevel level = player.level() instanceof ClientLevel cl ? cl : Minecraft.getInstance().level;
            int version;
            synchronized (ROUTE_LOCK) {
                version = REQUEST_VERSION.incrementAndGet();
            }
            submitComputeWithVersion(version, level, px, py, pz, targetX, targetZ, targetDimension, periodicDue);
        }
    }

    private static boolean matchesRenderSlot(
            RenderCacheSlot slot,
            List<RoutePoint> source,
            @Nullable LocalWalkGraph graph,
            double px,
            double pz
    ) {
        if (slot.source != source || slot.graph != graph || Double.isNaN(slot.playerX)) {
            return false;
        }
        double dx = px - slot.playerX;
        double dz = pz - slot.playerZ;
        return dx * dx + dz * dz <= 0.0025;
    }

    public static List<RoutePoint> getRenderPath(double playerX, double playerZ) {
        List<RoutePoint> cached;
        LocalWalkGraph localGraph;
        int localPlayerY;
        synchronized (ROUTE_LOCK) {
            if (!hasTarget) {
                return List.of();
            }
            cached = routePoints;
            localGraph = lastLocalGraph;
            localPlayerY = lastLocalPlayerY;
        }
        if (cached.size() < 2) {
            return List.of();
        }

        if (matchesRenderSlot(RENDER_SLOT_A, cached, localGraph, playerX, playerZ)) {
            return RENDER_SLOT_A.result;
        }
        if (matchesRenderSlot(RENDER_SLOT_B, cached, localGraph, playerX, playerZ)) {
            return RENDER_SLOT_B.result;
        }

        int playerNode = localGraph != null
                ? localGraph.findClosestNodeInColumn(Mth.floor(playerX), Mth.floor(playerZ), localPlayerY)
                : -1;

        int bestSeg = 0;
        double bestDistSq = Double.POSITIVE_INFINITY;
        float bestProjX = cached.get(0).x();
        float bestProjZ = cached.get(0).z();
        boolean bestVisible = false;
        double arcLen = 0.0;

        for (int i = 0; i < cached.size() - 1; i++) {
            if (i > 0 && arcLen > 64.0 && bestDistSq <= 16.0) {
                break;
            }
            RoutePoint a = cached.get(i);
            RoutePoint b = cached.get(i + 1);
            double abx = b.x() - a.x();
            double abz = b.z() - a.z();
            double lenSq = abx * abx + abz * abz;
            double t = lenSq < 1.0e-6
                    ? 0.0
                    : Mth.clamp(((playerX - a.x()) * abx + (playerZ - a.z()) * abz) / lenSq, 0.0, 1.0);
            double projX = a.x() + abx * t;
            double projZ = a.z() + abz * t;
            double dSq = (playerX - projX) * (playerX - projX) + (playerZ - projZ) * (playerZ - projZ);
            arcLen += Math.sqrt(lenSq);

            if (dSq >= bestDistSq) {
                continue;
            }

            boolean visible = true;
            if (localGraph != null) {
                if (playerNode >= 0 && localGraph.containsLoaded(Mth.floor(projX), Mth.floor(projZ))) {
                    visible = RoutePathSmoother.hasLocalLineOfSight(
                            localGraph, playerNode, (float) playerX, (float) playerZ,
                            (float) projX, (float) projZ, -1, false
                    );
                } else if (i > 0 && dSq > 9.0) {
                    visible = false;
                }
            } else if (i > 0 && dSq > 16.0) {
                visible = false;
            }

            if (i > 0 && !visible) {
                continue;
            }

            bestDistSq = dSq;
            bestSeg = i;
            bestProjX = (float) projX;
            bestProjZ = (float) projZ;
            bestVisible = visible;
        }

        List<RoutePoint> result = new ArrayList<>(cached.size() - bestSeg + 2);
        result.add(new RoutePoint((float) playerX, (float) playerZ));
        if (bestSeg == 0 && !bestVisible) {
            RoutePoint p0 = cached.get(0);
            double toP0Sq = (p0.x() - playerX) * (p0.x() - playerX) + (p0.z() - playerZ) * (p0.z() - playerZ);
            if (toP0Sq > 0.25) {
                result.add(p0);
            }
        } else if (bestDistSq > 1.0 && bestDistSq < 100.0) {
            RoutePoint nextPt = cached.get(bestSeg + 1);
            double toProjSq = (bestProjX - playerX) * (bestProjX - playerX) + (bestProjZ - playerZ) * (bestProjZ - playerZ);
            double projToNextSq = (nextPt.x() - bestProjX) * (nextPt.x() - bestProjX)
                    + (nextPt.z() - bestProjZ) * (nextPt.z() - bestProjZ);
            if (toProjSq > 1.0 && projToNextSq > 2.25) {
                result.add(new RoutePoint(bestProjX, bestProjZ));
            }
        }
        for (int i = bestSeg + 1; i < cached.size(); i++) {
            result.add(cached.get(i));
        }

        RenderCacheSlot slot = nextRenderSlotB ? RENDER_SLOT_B : RENDER_SLOT_A;
        nextRenderSlotB = !nextRenderSlotB;
        slot.playerX = playerX;
        slot.playerZ = playerZ;
        slot.source = cached;
        slot.graph = localGraph;
        slot.result = result;
        return result;
    }

    private static void submitComputeWithVersion(
            int version,
            @Nullable ClientLevel level,
            double startX,
            double startY,
            double startZ,
            float goalX,
            float goalZ,
            String dimId,
            boolean periodicOnly
    ) {
        WalkSectionSnapshot snapshot = level != null
                ? WalkSectionSnapshot.capture(level, startX, startY, startZ)
                : null;
        int terrainEpoch = AtlasRegionCache.getTerrainEpoch();
        int startWy = Mth.floor(startY);

        ROUTE_EXECUTOR.execute(() -> {
            LocalWalkGraph prevGraph;
            synchronized (ROUTE_LOCK) {
                if (version != REQUEST_VERSION.get() || !hasTarget || !dimId.equals(targetDimension)) {
                    return;
                }
                prevGraph = lastLocalGraph;
            }

            boolean snapshotUnchanged = snapshot != null
                    && prevGraph != null
                    && snapshot.equalsContent(workerLastSnapshot, WORKER_CMP_A, WORKER_CMP_B);

            if (periodicOnly && snapshotUnchanged && terrainEpoch == workerLastTerrainEpoch) {
                return;
            }

            LocalWalkGraph localGraph;
            if (snapshot == null) {
                localGraph = null;
            } else if (snapshotUnchanged) {
                localGraph = prevGraph;
            } else {
                localGraph = LocalWalkGraph.buildFromSnapshot(snapshot);
            }

            synchronized (ROUTE_LOCK) {
                if (version != REQUEST_VERSION.get() || !hasTarget || !dimId.equals(targetDimension)) {
                    return;
                }
            }

            List<RoutePoint> path = HybridRouteSolver.computeRoute(
                    startX, startWy, startZ, goalX, goalZ, localGraph, version, REQUEST_VERSION::get
            );
            if (path.isEmpty()) {
                return;
            }

            boolean committed = false;
            synchronized (ROUTE_LOCK) {
                if (version == REQUEST_VERSION.get() && hasTarget && dimId.equals(targetDimension)) {
                    lastLocalGraph = localGraph;
                    lastLocalPlayerY = startWy;
                    routePoints = path;
                    committed = true;
                }
            }
            if (committed) {
                workerLastSnapshot = snapshot;
                workerLastTerrainEpoch = terrainEpoch;
            }
        });
    }
}
