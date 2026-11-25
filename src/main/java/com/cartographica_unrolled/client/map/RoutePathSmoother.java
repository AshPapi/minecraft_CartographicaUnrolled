package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

final class RoutePathSmoother {
    private RoutePathSmoother() {
    }

    static List<AtlasRouteManager.RoutePoint> smoothPath(
            List<AtlasRouteManager.RoutePoint> raw,
            IntArrayList rawNodes,
            @Nullable LocalWalkGraph localGraph,
            RouteCostModel.TerrainLookup lookup,
            int step,
            int version,
            IntSupplier currentVersion
    ) {
        int n = raw.size();
        if (n <= 2) {
            return raw;
        }
        int seamLocalIdx = -1;
        for (int i = 0; i < n - 1; i++) {
            if (rawNodes.getInt(i) >= 0 && rawNodes.getInt(i + 1) < 0) {
                seamLocalIdx = i;
                break;
            }
        }

        List<AtlasRouteManager.RoutePoint> smoothed = new ArrayList<>();
        int anchor = 0;
        smoothed.add(raw.get(0));
        int maxLookahead = Math.max(48, 128 / step);

        while (anchor < n - 1) {
            if (version != currentVersion.getAsInt()) {
                return List.of();
            }
            int limit = Math.min(n - 1, anchor + maxLookahead);
            int chosen = anchor + 1;
            AtlasRouteManager.RoutePoint from = raw.get(anchor);
            int fromNode = rawNodes.getInt(anchor);
            for (int probe = limit; probe > anchor + 1; probe--) {
                AtlasRouteManager.RoutePoint to = raw.get(probe);
                int toNode = rawNodes.getInt(probe);
                boolean ok;
                if (fromNode >= 0 && toNode >= 0 && localGraph != null) {
                    ok = hasLocalLineOfSight(localGraph, fromNode, from.x(), from.z(), to.x(), to.z(), toNode, true);
                } else if (fromNode < 0 && toNode < 0) {
                    ok = hasGlobalLineOfSight(from, to, localGraph, lookup);
                } else if (fromNode >= 0 && toNode < 0 && localGraph != null
                        && seamLocalIdx >= anchor && seamLocalIdx < probe) {
                    AtlasRouteManager.RoutePoint seamLocalPt = raw.get(seamLocalIdx);
                    int seamNode = rawNodes.getInt(seamLocalIdx);
                    AtlasRouteManager.RoutePoint seamGlobalPt = raw.get(seamLocalIdx + 1);
                    ok = isNearlyCollinear(from, seamLocalPt, to, 1.35f)
                            && (anchor == seamLocalIdx || hasLocalLineOfSight(
                                    localGraph, fromNode, from.x(), from.z(), seamLocalPt.x(), seamLocalPt.z(), seamNode, true))
                            && (seamLocalIdx + 1 == probe || hasGlobalLineOfSight(seamGlobalPt, to, localGraph, lookup));
                } else {
                    ok = false;
                }
                if (ok) {
                    chosen = probe;
                    break;
                }
            }
            smoothed.add(raw.get(chosen));
            anchor = chosen;
        }
        return pruneCollinearPoints(smoothed);
    }

    private static boolean isNearlyCollinear(
            AtlasRouteManager.RoutePoint a,
            AtlasRouteManager.RoutePoint mid,
            AtlasRouteManager.RoutePoint b,
            float maxDev
    ) {
        float dx = b.x() - a.x();
        float dz = b.z() - a.z();
        float lenSq = dx * dx + dz * dz;
        if (lenSq < 1.0e-4f) {
            return true;
        }
        float cross = dz * (mid.x() - a.x()) - dx * (mid.z() - a.z());
        return cross * cross <= (maxDev * maxDev) * lenSq;
    }

    private static List<AtlasRouteManager.RoutePoint> pruneCollinearPoints(List<AtlasRouteManager.RoutePoint> pts) {
        if (pts.size() <= 2) {
            return pts;
        }
        List<AtlasRouteManager.RoutePoint> out = new ArrayList<>(pts.size());
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            AtlasRouteManager.RoutePoint prev = out.getLast();
            AtlasRouteManager.RoutePoint cur = pts.get(i);
            AtlasRouteManager.RoutePoint next = pts.get(i + 1);
            if (!isNearlyCollinear(prev, cur, next, 0.05f)) {
                out.add(cur);
            }
        }
        out.add(pts.getLast());
        return out;
    }

    static boolean hasLocalLineOfSight(
            LocalWalkGraph graph,
            int startNode,
            float ax,
            float az,
            float bx,
            float bz,
            int expectedEndNode,
            boolean strictCost
    ) {
        int curGx = graph.worldX(startNode);
        int curGz = graph.worldZ(startNode);
        if (Mth.floor(ax) != curGx || Mth.floor(az) != curGz) {
            return false;
        }
        int endGx = Mth.floor(bx);
        int endGz = Mth.floor(bz);
        if (expectedEndNode >= 0 && (graph.worldX(expectedEndNode) != endGx || graph.worldZ(expectedEndNode) != endGz)) {
            return false;
        }
        if (curGx == endGx && curGz == endGz) {
            return expectedEndNode < 0 || startNode == expectedEndNode;
        }

        boolean startDry = !graph.isWater(startNode);
        boolean startLowCost = !graph.isHighCost(startNode);
        boolean endDry = expectedEndNode < 0 || !graph.isWater(expectedEndNode);
        boolean endLowCost = expectedEndNode < 0 || !graph.isHighCost(expectedEndNode);
        boolean avoidWater = strictCost && startDry && endDry;
        boolean avoidHighCost = strictCost && startLowCost && endLowCost;

        float dx = bx - ax;
        float dz = bz - az;
        float dist = Mth.sqrt(dx * dx + dz * dz);
        int samples = Math.max(4, Mth.ceil(dist * 4.0f));
        int cur = startNode;

        for (int i = 1; i <= samples; i++) {
            float t = (float) i / samples;
            float px = ax + dx * t;
            float pz = az + dz * t;
            int tx = i == samples ? endGx : Mth.floor(px);
            int tz = i == samples ? endGz : Mth.floor(pz);
            while (curGx != tx || curGz != tz) {
                int sdx = Integer.compare(tx, curGx);
                int sdz = Integer.compare(tz, curGz);
                int next = graph.stepDelta(cur, sdx, sdz);
                if (next < 0) {
                    return false;
                }
                if (avoidWater && graph.isWater(next)) {
                    return false;
                }
                if (avoidHighCost && graph.isHighCost(next)) {
                    return false;
                }
                cur = next;
                curGx += sdx;
                curGz += sdz;
            }
            if (strictCost && i > 1 && i < samples - 1) {
                float fx = px - curGx;
                float fz = pz - curGz;
                int cornerDx = fx < 0.18f ? -1 : (fx > 0.82f ? 1 : 0);
                int cornerDz = fz < 0.18f ? -1 : (fz > 0.82f ? 1 : 0);
                if (cornerDx != 0 && cornerDz != 0 && graph.stepDelta(cur, cornerDx, cornerDz) < 0) {
                    return false;
                }
            }
        }
        return expectedEndNode < 0 || cur == expectedEndNode;
    }

    private static boolean hasGlobalLineOfSight(
            AtlasRouteManager.RoutePoint a,
            AtlasRouteManager.RoutePoint b,
            @Nullable LocalWalkGraph localGraph,
            RouteCostModel.TerrainLookup lookup
    ) {
        float dx = b.x() - a.x();
        float dz = b.z() - a.z();
        float dist = Mth.sqrt(dx * dx + dz * dz);
        int samples = Math.max(4, Mth.ceil(dist * 3.0f));

        int prevWx = Mth.floor(a.x());
        int prevWz = Mth.floor(a.z());
        long prevCol = lookup.sample(prevWx, prevWz);
        long endCol = lookup.sample(Mth.floor(b.x()), Mth.floor(b.z()));
        boolean bothExplored = prevCol != AtlasChunkTerrain.EMPTY_COLUMN && endCol != AtlasChunkTerrain.EMPTY_COLUMN;
        boolean bothDry = bothExplored
                && (AtlasChunkTerrain.unpackFlags(prevCol) & AtlasChunkTerrain.FLAG_WATER) == 0
                && (AtlasChunkTerrain.unpackFlags(endCol) & AtlasChunkTerrain.FLAG_WATER) == 0;

        for (int i = 1; i <= samples; i++) {
            float t = (float) i / samples;
            int wx = Mth.floor(a.x() + dx * t);
            int wz = Mth.floor(a.z() + dz * t);
            while (prevWx != wx || prevWz != wz) {
                int sdx = Integer.compare(wx, prevWx);
                int sdz = Integer.compare(wz, prevWz);
                if (sdx != 0 && sdz != 0) {
                    long sideACol = lookup.sample(prevWx + sdx, prevWz);
                    long sideBCol = lookup.sample(prevWx, prevWz + sdz);
                    if (!isGlobalStepClear(prevWx + sdx, prevWz, sideACol, prevCol, bothExplored, bothDry, localGraph)
                            || !isGlobalStepClear(prevWx, prevWz + sdz, sideBCol, prevCol, bothExplored, bothDry, localGraph)) {
                        return false;
                    }
                }
                int nwx = prevWx + sdx;
                int nwz = prevWz + sdz;
                long nextCol = lookup.sample(nwx, nwz);
                if (!isGlobalStepClear(nwx, nwz, nextCol, prevCol, bothExplored, bothDry, localGraph)) {
                    return false;
                }
                prevWx = nwx;
                prevWz = nwz;
                prevCol = nextCol;
            }
        }
        return true;
    }

    private static boolean isGlobalStepClear(
            int wx,
            int wz,
            long col,
            long prevCol,
            boolean bothExplored,
            boolean bothDry,
            @Nullable LocalWalkGraph localGraph
    ) {
        if (localGraph != null && localGraph.containsLoaded(wx, wz)) {
            return false;
        }
        if (col == AtlasChunkTerrain.EMPTY_COLUMN) {
            return !bothExplored;
        }
        if (RouteCostModel.isHardBlockedColumn(col)) {
            return false;
        }
        int flags = AtlasChunkTerrain.unpackFlags(col);
        int detail = AtlasChunkTerrain.unpackDetailType(col);
        if (bothDry && (flags & AtlasChunkTerrain.FLAG_WATER) != 0
                && detail != AtlasChunkTerrain.DETAIL_OUTDOOR_BRIDGE
                && AtlasChunkTerrain.unpackWaterDepth(col) > 1) {
            return false;
        }
        if (prevCol != AtlasChunkTerrain.EMPTY_COLUMN) {
            int dy = AtlasChunkTerrain.unpackSurfaceY(col) - AtlasChunkTerrain.unpackSurfaceY(prevCol);
            if (dy > 1 || (dy < -3 && (flags & AtlasChunkTerrain.FLAG_WATER) == 0)) {
                return false;
            }
        }
        return true;
    }
}
