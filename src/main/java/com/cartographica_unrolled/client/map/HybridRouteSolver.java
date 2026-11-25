package com.cartographica_unrolled.client.map;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntSupplier;

final class HybridRouteSolver {
    private static final int MAX_GRID_DIM = 512;
    private static final int MAX_EXPANDED = 65_000;
    private static final int WORKER_SHRINK_THRESHOLD = 16384;

    private static float[] workerGScore = new float[0];
    private static int[] workerParent = new int[0];
    private static byte[] workerClosed = new byte[0];
    private static final IndexedMinHeap workerHeap = new IndexedMinHeap(1024);
    private static final RouteCostModel.TerrainLookup workerLookup = new RouteCostModel.TerrainLookup();

    private HybridRouteSolver() {
    }

    static void shrinkWorkerBuffers() {
        if (workerGScore.length > WORKER_SHRINK_THRESHOLD) {
            workerGScore = new float[0];
            workerParent = new int[0];
            workerClosed = new byte[0];
            workerHeap.shrink();
        }
        workerLookup.shrink();
    }

    static List<AtlasRouteManager.RoutePoint> computeRoute(
            double startX,
            int startWy,
            double startZ,
            float goalX,
            float goalZ,
            @Nullable LocalWalkGraph localGraph,
            int version,
            IntSupplier currentVersion
    ) {
        int startWx = Mth.floor(startX);
        int startWz = Mth.floor(startZ);
        int goalWx = Mth.floor(goalX);
        int goalWz = Mth.floor(goalZ);

        double straightDist = Math.hypot(goalX - startX, goalZ - startZ);
        if (straightDist <= 2.0) {
            return List.of(
                    new AtlasRouteManager.RoutePoint((float) startX, (float) startZ),
                    new AtlasRouteManager.RoutePoint(goalX, goalZ)
            );
        }

        int step;
        if (straightDist <= 220.0) {
            step = 1;
        } else if (straightDist <= 520.0) {
            step = 2;
        } else if (straightDist <= 1200.0) {
            step = 4;
        } else {
            step = 8;
        }

        int marginBlocks = (int) Mth.clamp(straightDist * 0.45 + 64.0, 96.0, 384.0);
        int startGx;
        int startGz;
        int goalGx;
        int goalGz;
        int minGx;
        int maxGx;
        int minGz;
        int maxGz;
        int gridW;
        int gridH;

        while (true) {
            startGx = Math.floorDiv(startWx, step);
            startGz = Math.floorDiv(startWz, step);
            goalGx = Math.floorDiv(goalWx, step);
            goalGz = Math.floorDiv(goalWz, step);
            int marginCells = Math.max(16, marginBlocks / step);
            minGx = Math.min(startGx, goalGx) - marginCells;
            maxGx = Math.max(startGx, goalGx) + marginCells;
            minGz = Math.min(startGz, goalGz) - marginCells;
            maxGz = Math.max(startGz, goalGz) + marginCells;
            gridW = maxGx - minGx + 1;
            gridH = maxGz - minGz + 1;
            if (gridW <= MAX_GRID_DIM && gridH <= MAX_GRID_DIM) {
                break;
            }
            step *= 2;
        }

        int localCount = localGraph != null ? localGraph.nodeCount() : 0;
        boolean goalInsideLocalLoaded = localGraph != null && localGraph.containsLoaded(goalWx, goalWz);
        boolean goalIsLocal = false;
        int localGoalNode = -1;
        if (goalInsideLocalLoaded) {
            if (localGraph.hasColumn(goalWx, goalWz)) {
                goalIsLocal = true;
            } else {
                int snapped = localGraph.findNearestNode(goalX, goalZ, 3.5f, startWy);
                if (snapped >= 0) {
                    goalIsLocal = true;
                    localGoalNode = snapped;
                }
            }
        }

        int gridSize = (goalIsLocal || (goalInsideLocalLoaded && localCount > 0)) ? 0 : (gridW * gridH);
        int totalNodes = localCount + gridSize;
        if (totalNodes <= 0) {
            return List.of(
                    new AtlasRouteManager.RoutePoint((float) startX, (float) startZ),
                    new AtlasRouteManager.RoutePoint(goalX, goalZ)
            );
        }
        if (workerGScore.length < totalNodes) {
            workerGScore = new float[totalNodes];
            workerParent = new int[totalNodes];
            workerClosed = new byte[totalNodes];
        }
        float[] gScore = workerGScore;
        int[] parent = workerParent;
        byte[] closed = workerClosed;
        Arrays.fill(gScore, 0, totalNodes, Float.POSITIVE_INFINITY);
        Arrays.fill(parent, 0, totalNodes, -1);
        Arrays.fill(closed, 0, totalNodes, (byte) 0);

        IndexedMinHeap open = workerHeap;
        open.reset(totalNodes);
        RouteCostModel.TerrainLookup lookup = workerLookup;
        lookup.reset();

        int startIdx = localCount > 0 ? 0 : ((startGz - minGz) * gridW + (startGx - minGx));
        int goalGlobalIdx = gridSize == 0 ? -1 : (localCount + (goalGz - minGz) * gridW + (goalGx - minGx));

        int effGoalWx = localGoalNode >= 0 ? localGraph.worldX(localGoalNode) : goalWx;
        int effGoalWz = localGoalNode >= 0 ? localGraph.worldZ(localGoalNode) : goalWz;

        int startNodeWx = localCount > 0 ? localGraph.worldX(0) : RouteCostModel.cellCenter(startGx, step);
        int startNodeWz = localCount > 0 ? localGraph.worldZ(0) : RouteCostModel.cellCenter(startGz, step);
        gScore[startIdx] = 0.0f;
        float startH = RouteCostModel.heuristicWorld(startNodeWx, startNodeWz, effGoalWx, effGoalWz);
        open.pushOrDecrease(startIdx, startH);

        int bestIdx = startIdx;
        float bestH = startH;
        int expanded = 0;
        boolean reached = false;

        try {
            while (!open.isEmpty() && expanded < MAX_EXPANDED) {
                if ((expanded & 1023) == 0 && version != currentVersion.getAsInt()) {
                    return List.of();
                }
                int curIdx = open.pop();
                if (closed[curIdx] != 0) {
                    continue;
                }
                closed[curIdx] = 1;
                expanded++;

                if (curIdx < localCount) {
                    int curWx = localGraph.worldX(curIdx);
                    int curWz = localGraph.worldZ(curIdx);
                    if (goalIsLocal) {
                        if (localGoalNode >= 0 ? (curIdx == localGoalNode) : (curWx == goalWx && curWz == goalWz)) {
                            bestIdx = curIdx;
                            reached = true;
                            break;
                        }
                    }

                    float curH = RouteCostModel.heuristicWorld(curWx, curWz, effGoalWx, effGoalWz);
                    if (curH < bestH) {
                        bestH = curH;
                        bestIdx = curIdx;
                    }

                    float curG = gScore[curIdx];
                    int curY = localGraph.worldY(curIdx);

                    for (int d = 0; d < 8; d++) {
                        int nextLocal = localGraph.edge(curIdx, d);
                        if (nextLocal < 0 || closed[nextLocal] != 0) {
                            continue;
                        }
                        float dist = d >= 4 ? RouteCostModel.SQRT2 : 1.0f;
                        float moveCost = dist * localGraph.moveCost(nextLocal);
                        int dy = localGraph.worldY(nextLocal) - curY;
                        if (dy > 0) {
                            moveCost += localGraph.isStairs(nextLocal) ? 0.10f * dy : 0.35f * dy;
                        } else if (dy < -1 && !localGraph.isWater(nextLocal)) {
                            moveCost += 0.25f * (-dy - 1);
                        }
                        float tentativeG = curG + moveCost;
                        if (tentativeG < gScore[nextLocal]) {
                            gScore[nextLocal] = tentativeG;
                            parent[nextLocal] = curIdx;
                            float f = tentativeG + RouteCostModel.heuristicWorld(
                                    localGraph.worldX(nextLocal), localGraph.worldZ(nextLocal), effGoalWx, effGoalWz);
                            open.pushOrDecrease(nextLocal, f);
                        }
                    }

                    if (gridSize > 0 && localGraph.isBoundary(curIdx)) {
                        int dirLimit = step == 1 ? 8 : 4;
                        int curGroundY = curY - 1;
                        for (int d = 0; d < dirLimit; d++) {
                            int dx = RouteCostModel.DIR_X[d];
                            int dz = RouteCostModel.DIR_Z[d];
                            int probeWx = curWx + dx;
                            int probeWz = curWz + dz;
                            if (localGraph.containsLoaded(probeWx, probeWz)) {
                                continue;
                            }
                            int ngx = Math.floorDiv(probeWx, step);
                            int ngz = Math.floorDiv(probeWz, step);
                            int nextWx = RouteCostModel.cellCenter(ngx, step);
                            int nextWz = RouteCostModel.cellCenter(ngz, step);
                            if (step > 1 && localGraph.containsLoaded(nextWx, nextWz)) {
                                ngx += dx;
                                ngz += dz;
                                nextWx = RouteCostModel.cellCenter(ngx, step);
                                nextWz = RouteCostModel.cellCenter(ngz, step);
                                if (localGraph.containsLoaded(nextWx, nextWz)) {
                                    continue;
                                }
                            }
                            if (ngx < minGx || ngx > maxGx || ngz < minGz || ngz > maxGz) {
                                continue;
                            }
                            int nextIdx = localCount + (ngz - minGz) * gridW + (ngx - minGx);
                            if (closed[nextIdx] != 0) {
                                continue;
                            }
                            long nextCol = RouteCostModel.sampleMacroCell(lookup, ngx, ngz, step);
                            float seamCost = RouteCostModel.seamTransitionCost(
                                    curWx, curGroundY, curWz, nextWx, nextWz, nextCol, step, nextIdx == goalGlobalIdx
                            );
                            if (!Float.isFinite(seamCost)) {
                                continue;
                            }
                            float tentativeG = curG + seamCost;
                            if (tentativeG < gScore[nextIdx]) {
                                gScore[nextIdx] = tentativeG;
                                parent[nextIdx] = curIdx;
                                float f = tentativeG + RouteCostModel.heuristicWorld(nextWx, nextWz, effGoalWx, effGoalWz);
                                open.pushOrDecrease(nextIdx, f);
                            }
                        }
                    }
                } else {
                    if (curIdx == goalGlobalIdx) {
                        bestIdx = curIdx;
                        reached = true;
                        break;
                    }

                    int gIdx = curIdx - localCount;
                    int curGx = minGx + (gIdx % gridW);
                    int curGz = minGz + (gIdx / gridW);
                    int curWx = RouteCostModel.cellCenter(curGx, step);
                    int curWz = RouteCostModel.cellCenter(curGz, step);
                    float curH = RouteCostModel.heuristicWorld(curWx, curWz, effGoalWx, effGoalWz);
                    if (curH < bestH) {
                        bestH = curH;
                        bestIdx = curIdx;
                    }

                    long curCol = RouteCostModel.sampleMacroCell(lookup, curGx, curGz, step);
                    float curG = gScore[curIdx];

                    for (int d = 0; d < 8; d++) {
                        int dx = RouteCostModel.DIR_X[d];
                        int dz = RouteCostModel.DIR_Z[d];
                        int ngx = curGx + dx;
                        int ngz = curGz + dz;
                        if (ngx < minGx || ngx > maxGx || ngz < minGz || ngz > maxGz) {
                            continue;
                        }
                        int nextIdx = localCount + (ngz - minGz) * gridW + (ngx - minGx);
                        if (closed[nextIdx] != 0) {
                            continue;
                        }
                        int nextWx = RouteCostModel.cellCenter(ngx, step);
                        int nextWz = RouteCostModel.cellCenter(ngz, step);
                        if (localGraph != null && localGraph.containsLoaded(nextWx, nextWz)) {
                            continue;
                        }

                        boolean isDiag = dx != 0 && dz != 0;
                        if (isDiag) {
                            int sideAWx = RouteCostModel.cellCenter(curGx + dx, step);
                            int sideBWz = RouteCostModel.cellCenter(curGz + dz, step);
                            if (localGraph != null && (localGraph.containsLoaded(sideAWx, curWz)
                                    || localGraph.containsLoaded(curWx, sideBWz))) {
                                continue;
                            }
                            long sideA = RouteCostModel.sampleMacroCell(lookup, curGx + dx, curGz, step);
                            long sideB = RouteCostModel.sampleMacroCell(lookup, curGx, curGz + dz, step);
                            if (RouteCostModel.isHardBlockedStep(curCol, sideA, step)
                                    || RouteCostModel.isHardBlockedStep(curCol, sideB, step)) {
                                continue;
                            }
                        }

                        long nextCol = RouteCostModel.sampleMacroCell(lookup, ngx, ngz, step);
                        float moveCost = RouteCostModel.transitionCost(
                                curCol, nextCol, step, isDiag, nextIdx == goalGlobalIdx
                        ) * step;
                        if (!Float.isFinite(moveCost)) {
                            continue;
                        }

                        float tentativeG = curG + moveCost;
                        if (tentativeG < gScore[nextIdx]) {
                            gScore[nextIdx] = tentativeG;
                            parent[nextIdx] = curIdx;
                            float f = tentativeG + RouteCostModel.heuristicWorld(nextWx, nextWz, effGoalWx, effGoalWz);
                            open.pushOrDecrease(nextIdx, f);
                        }
                    }
                }
            }

            if (version != currentVersion.getAsInt()) {
                return List.of();
            }

            List<AtlasRouteManager.RoutePoint> raw = new ArrayList<>();
            IntArrayList rawNodes = new IntArrayList();
            int trace = bestIdx;
            while (trace != -1) {
                if (trace < localCount) {
                    raw.add(new AtlasRouteManager.RoutePoint(
                            localGraph.worldX(trace) + 0.5f, localGraph.worldZ(trace) + 0.5f));
                    rawNodes.add(trace);
                } else {
                    int gIdx = trace - localCount;
                    int gx = minGx + (gIdx % gridW);
                    int gz = minGz + (gIdx / gridW);
                    raw.add(new AtlasRouteManager.RoutePoint(
                            RouteCostModel.cellCenter(gx, step) + 0.5f,
                            RouteCostModel.cellCenter(gz, step) + 0.5f
                    ));
                    rawNodes.add(-1);
                }
                trace = parent[trace];
            }
            Collections.reverse(raw);
            reverseIntList(rawNodes);

            if (raw.isEmpty()) {
                return List.of(
                        new AtlasRouteManager.RoutePoint((float) startX, (float) startZ),
                        new AtlasRouteManager.RoutePoint(goalX, goalZ)
                );
            }

            if (localCount == 0 || (Mth.floor(startX) == localGraph.worldX(0) && Mth.floor(startZ) == localGraph.worldZ(0))) {
                raw.set(0, new AtlasRouteManager.RoutePoint((float) startX, (float) startZ));
            } else {
                raw.add(0, new AtlasRouteManager.RoutePoint((float) startX, (float) startZ));
                rawNodes.add(0, -1);
            }

            if (reached && localGoalNode < 0) {
                raw.set(raw.size() - 1, new AtlasRouteManager.RoutePoint(goalX, goalZ));
            } else if (!reached && !goalInsideLocalLoaded) {
                AtlasRouteManager.RoutePoint last = raw.get(raw.size() - 1);
                if ((last.x() - goalX) * (last.x() - goalX) + (last.z() - goalZ) * (last.z() - goalZ) > 1.0f) {
                    raw.add(new AtlasRouteManager.RoutePoint(goalX, goalZ));
                    rawNodes.add(-1);
                }
            }

            return RoutePathSmoother.smoothPath(raw, rawNodes, localGraph, lookup, step, version, currentVersion);
        } finally {
            lookup.reset();
        }
    }

    private static void reverseIntList(IntArrayList list) {
        int i = 0;
        int j = list.size() - 1;
        while (i < j) {
            int tmp = list.getInt(i);
            list.set(i, list.getInt(j));
            list.set(j, tmp);
            i++;
            j--;
        }
    }

    private static final class IndexedMinHeap {
        private int[] nodes;
        private float[] priorities;
        private int[] heapPos;
        private int size;

        IndexedMinHeap(int initialCapacity) {
            int cap = Math.max(64, initialCapacity);
            this.nodes = new int[cap];
            this.priorities = new float[cap];
            this.heapPos = new int[cap];
        }

        void reset(int totalNodes) {
            this.size = 0;
            if (this.heapPos.length < totalNodes) {
                this.heapPos = new int[totalNodes];
            }
            Arrays.fill(this.heapPos, 0, totalNodes, -1);
        }

        void shrink() {
            this.size = 0;
            this.nodes = new int[256];
            this.priorities = new float[256];
            this.heapPos = new int[0];
        }

        boolean isEmpty() {
            return this.size == 0;
        }

        void pushOrDecrease(int node, float priority) {
            int existingIdx = this.heapPos[node];
            if (existingIdx >= 0) {
                if (priority < this.priorities[existingIdx]) {
                    this.priorities[existingIdx] = priority;
                    siftUp(existingIdx, node, priority);
                }
                return;
            }
            if (this.size >= this.nodes.length) {
                int newCap = this.nodes.length << 1;
                this.nodes = Arrays.copyOf(this.nodes, newCap);
                this.priorities = Arrays.copyOf(this.priorities, newCap);
            }
            siftUp(this.size++, node, priority);
        }

        int pop() {
            int top = this.nodes[0];
            this.heapPos[top] = -1;
            this.size--;
            if (this.size > 0) {
                siftDown(0, this.nodes[this.size], this.priorities[this.size]);
            }
            return top;
        }

        private void siftUp(int i, int node, float priority) {
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (this.priorities[p] <= priority) {
                    break;
                }
                int pNode = this.nodes[p];
                this.nodes[i] = pNode;
                this.priorities[i] = this.priorities[p];
                this.heapPos[pNode] = i;
                i = p;
            }
            this.nodes[i] = node;
            this.priorities[i] = priority;
            this.heapPos[node] = i;
        }

        private void siftDown(int i, int node, float priority) {
            int half = this.size >>> 1;
            while (i < half) {
                int child = (i << 1) + 1;
                int right = child + 1;
                if (right < this.size && this.priorities[right] < this.priorities[child]) {
                    child = right;
                }
                if (priority <= this.priorities[child]) {
                    break;
                }
                int cNode = this.nodes[child];
                this.nodes[i] = cNode;
                this.priorities[i] = this.priorities[child];
                this.heapPos[cNode] = i;
                i = child;
            }
            this.nodes[i] = node;
            this.priorities[i] = priority;
            this.heapPos[node] = i;
        }
    }
}
