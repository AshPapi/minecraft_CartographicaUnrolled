package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class AtlasEditorState {
    public enum Tool {
        NONE,
        DYE,
        ERASER,
        STAMP
    }

    private static final int MAX_HISTORY = 40;
    private static final int GRID_SHIFT = 7;
    private static final int SPATIAL_THRESHOLD = 24;

    private static final List<MapStroke> STROKES = new ArrayList<>();
    private static final List<MapMarker> MARKERS = new ArrayList<>();
    private static final List<MapStroke> SHARED_STROKES = new ArrayList<>();
    private static final List<MapMarker> SHARED_MARKERS = new ArrayList<>();

    private record Snapshot(
            List<MapStroke> strokes,
            List<MapMarker> markers,
            List<MapStroke> sharedStrokes,
            List<MapMarker> sharedMarkers
    ) {
    }

    private static final Deque<Snapshot> UNDO_STACK = new ArrayDeque<>();
    private static @Nullable List<MapStroke> cachedVisibleStrokes;
    private static @Nullable List<MapMarker> cachedVisibleMarkers;
    private static @Nullable Long2ObjectOpenHashMap<IntArrayList> strokeGrid;
    private static @Nullable Long2ObjectOpenHashMap<IntArrayList> markerGrid;
    private static int[] strokeQueryStamps = new int[0];
    private static int currentQueryStamp = 1;

    private static boolean sharedMapMode = false;
    private static boolean sharePersonalWithAll = false;
    private static boolean personalStrokesErased = false;
    private static boolean sharedStrokesErased = false;

    private static void invalidateVisibleCache() {
        cachedVisibleStrokes = null;
        cachedVisibleMarkers = null;
        strokeGrid = null;
        markerGrid = null;
    }

    public static boolean isSharePersonalWithAll() {
        return sharePersonalWithAll;
    }

    public static void setSharePersonalWithAll(boolean share) {
        sharePersonalWithAll = share;
    }

    public static boolean isSharedMap() {
        return sharedMapMode;
    }

    public static boolean toggleSharedMap() {
        sharedMapMode = !sharedMapMode;
        invalidateVisibleCache();
        return sharedMapMode;
    }

    public static List<MapStroke> getVisibleStrokes() {
        List<MapStroke> cached = cachedVisibleStrokes;
        if (cached == null) {
            if (!sharedMapMode) {
                cached = List.copyOf(STROKES);
            } else {
                List<MapStroke> combined = new ArrayList<>(STROKES.size() + SHARED_STROKES.size());
                combined.addAll(STROKES);
                combined.addAll(SHARED_STROKES);
                cached = List.copyOf(combined);
            }
            cachedVisibleStrokes = cached;
        }
        return cached;
    }

    public static List<MapMarker> getVisibleMarkers() {
        List<MapMarker> cached = cachedVisibleMarkers;
        if (cached == null) {
            if (!sharedMapMode) {
                cached = List.copyOf(MARKERS);
            } else {
                List<MapMarker> combined = new ArrayList<>(MARKERS.size() + SHARED_MARKERS.size());
                combined.addAll(MARKERS);
                combined.addAll(SHARED_MARKERS);
                cached = List.copyOf(combined);
            }
            cachedVisibleMarkers = cached;
        }
        return cached;
    }

    public static void queryVisibleStrokes(double left, double top, double right, double bottom, List<MapStroke> out) {
        out.clear();
        List<MapStroke> all = getVisibleStrokes();
        int n = all.size();
        if (n == 0) {
            return;
        }
        if (n <= SPATIAL_THRESHOLD) {
            for (int i = 0; i < n; i++) {
                MapStroke s = all.get(i);
                if (s.intersectsBounds(left, top, right, bottom)) {
                    out.add(s);
                }
            }
            return;
        }
        Long2ObjectOpenHashMap<IntArrayList> grid = strokeGrid;
        if (grid == null) {
            grid = buildStrokeGrid(all);
            strokeGrid = grid;
        }
        int minCx = Mth.floor(left) >> GRID_SHIFT;
        int maxCx = Mth.floor(right) >> GRID_SHIFT;
        int minCz = Mth.floor(top) >> GRID_SHIFT;
        int maxCz = Mth.floor(bottom) >> GRID_SHIFT;
        if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) > (long) n * 2L) {
            for (int i = 0; i < n; i++) {
                MapStroke s = all.get(i);
                if (s.intersectsBounds(left, top, right, bottom)) {
                    out.add(s);
                }
            }
            return;
        }
        if (strokeQueryStamps.length < n) {
            strokeQueryStamps = new int[n];
        }
        int stamp = ++currentQueryStamp;
        if (stamp == 0) {
            java.util.Arrays.fill(strokeQueryStamps, 0);
            stamp = ++currentQueryStamp;
        }
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                IntArrayList bucket = grid.get(ChunkPos.pack(cx, cz));
                if (bucket == null) {
                    continue;
                }
                for (int bi = 0; bi < bucket.size(); bi++) {
                    int idx = bucket.getInt(bi);
                    if (strokeQueryStamps[idx] == stamp) {
                        continue;
                    }
                    strokeQueryStamps[idx] = stamp;
                    MapStroke s = all.get(idx);
                    if (s.intersectsBounds(left, top, right, bottom)) {
                        out.add(s);
                    }
                }
            }
        }
    }

    public static void queryVisibleMarkers(double left, double top, double right, double bottom, double pad, List<MapMarker> out) {
        out.clear();
        List<MapMarker> all = getVisibleMarkers();
        int n = all.size();
        if (n == 0) {
            return;
        }
        double qLeft = left - pad;
        double qTop = top - pad;
        double qRight = right + pad;
        double qBottom = bottom + pad;
        if (n <= SPATIAL_THRESHOLD) {
            for (int i = 0; i < n; i++) {
                MapMarker m = all.get(i);
                float wx = m.getWorldX();
                float wz = m.getWorldZ();
                if (wx >= qLeft && wx <= qRight && wz >= qTop && wz <= qBottom) {
                    out.add(m);
                }
            }
            return;
        }
        Long2ObjectOpenHashMap<IntArrayList> grid = markerGrid;
        if (grid == null) {
            grid = buildMarkerGrid(all);
            markerGrid = grid;
        }
        int minCx = Mth.floor(qLeft) >> GRID_SHIFT;
        int maxCx = Mth.floor(qRight) >> GRID_SHIFT;
        int minCz = Mth.floor(qTop) >> GRID_SHIFT;
        int maxCz = Mth.floor(qBottom) >> GRID_SHIFT;
        if ((long) (maxCx - minCx + 1) * (maxCz - minCz + 1) > (long) n * 2L) {
            for (int i = 0; i < n; i++) {
                MapMarker m = all.get(i);
                float wx = m.getWorldX();
                float wz = m.getWorldZ();
                if (wx >= qLeft && wx <= qRight && wz >= qTop && wz <= qBottom) {
                    out.add(m);
                }
            }
            return;
        }
        for (int cz = minCz; cz <= maxCz; cz++) {
            for (int cx = minCx; cx <= maxCx; cx++) {
                IntArrayList bucket = grid.get(ChunkPos.pack(cx, cz));
                if (bucket == null) {
                    continue;
                }
                for (int bi = 0; bi < bucket.size(); bi++) {
                    MapMarker m = all.get(bucket.getInt(bi));
                    float wx = m.getWorldX();
                    float wz = m.getWorldZ();
                    if (wx >= qLeft && wx <= qRight && wz >= qTop && wz <= qBottom) {
                        out.add(m);
                    }
                }
            }
        }
    }

    private static Long2ObjectOpenHashMap<IntArrayList> buildStrokeGrid(List<MapStroke> strokes) {
        Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>(Math.max(16, strokes.size()));
        for (int i = 0; i < strokes.size(); i++) {
            MapStroke s = strokes.get(i);
            if (s.getPoints().isEmpty()) {
                continue;
            }
            float pad = s.getWidth() * 0.5f + 1.0f;
            int minCx = Mth.floor(s.getMinX() - pad) >> GRID_SHIFT;
            int maxCx = Mth.floor(s.getMaxX() + pad) >> GRID_SHIFT;
            int minCz = Mth.floor(s.getMinZ() - pad) >> GRID_SHIFT;
            int maxCz = Mth.floor(s.getMaxZ() + pad) >> GRID_SHIFT;
            for (int cz = minCz; cz <= maxCz; cz++) {
                for (int cx = minCx; cx <= maxCx; cx++) {
                    long key = ChunkPos.pack(cx, cz);
                    IntArrayList bucket = grid.get(key);
                    if (bucket == null) {
                        bucket = new IntArrayList(4);
                        grid.put(key, bucket);
                    }
                    bucket.add(i);
                }
            }
        }
        return grid;
    }

    private static Long2ObjectOpenHashMap<IntArrayList> buildMarkerGrid(List<MapMarker> markers) {
        Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>(Math.max(16, markers.size()));
        for (int i = 0; i < markers.size(); i++) {
            MapMarker m = markers.get(i);
            int cx = Mth.floor(m.getWorldX()) >> GRID_SHIFT;
            int cz = Mth.floor(m.getWorldZ()) >> GRID_SHIFT;
            long key = ChunkPos.pack(cx, cz);
            IntArrayList bucket = grid.get(key);
            if (bucket == null) {
                bucket = new IntArrayList(4);
                grid.put(key, bucket);
            }
            bucket.add(i);
        }
        return grid;
    }

    public static void clearUndoHistory() {
        UNDO_STACK.clear();
    }

    public static void clearAnnotationsForDimensionSwitch() {
        STROKES.clear();
        MARKERS.clear();
        UNDO_STACK.clear();
        SHARED_STROKES.clear();
        SHARED_MARKERS.clear();
        personalStrokesErased = false;
        sharedStrokesErased = false;
        invalidateVisibleCache();
    }

    public static void clearAll() {
        STROKES.clear();
        MARKERS.clear();
        UNDO_STACK.clear();
        SHARED_STROKES.clear();
        SHARED_MARKERS.clear();
        sharedMapMode = false;
        sharePersonalWithAll = false;
        personalStrokesErased = false;
        sharedStrokesErased = false;
        strokeQueryStamps = new int[0];
        invalidateVisibleCache();
    }

    public static void loadFromServer(List<MapMarker> markers, List<MapStroke> strokes) {
        MARKERS.clear();
        MARKERS.addAll(markers);
        STROKES.clear();
        STROKES.addAll(strokes);
        UNDO_STACK.clear();
        personalStrokesErased = false;
        invalidateVisibleCache();
    }

    public static void loadSharedFromServer(List<MapMarker> markers, List<MapStroke> strokes) {
        SHARED_MARKERS.clear();
        SHARED_MARKERS.addAll(markers);
        SHARED_STROKES.clear();
        SHARED_STROKES.addAll(strokes);
        sharedStrokesErased = false;
        invalidateVisibleCache();
    }

    private static List<MapMarker> copyMarkers(List<MapMarker> source) {
        List<MapMarker> copy = new ArrayList<>(source.size());
        for (MapMarker marker : source) {
            copy.add(new MapMarker(marker.getId(), marker.getWorldX(), marker.getWorldZ(), marker.getType(), marker.getLabel(), marker.getAuthorUuid(), marker.getAuthorName()));
        }
        return copy;
    }

    public static void pushUndoSnapshot() {
        if (UNDO_STACK.size() >= MAX_HISTORY) {
            UNDO_STACK.removeFirst();
        }
        List<MapStroke> ownSharedStrokes = new ArrayList<>();
        for (MapStroke s : SHARED_STROKES) {
            if (isOwnedByLocalPlayer(s.getAuthorUuid(), s.getAuthorName())) {
                ownSharedStrokes.add(s);
            }
        }
        List<MapMarker> ownSharedMarkers = new ArrayList<>();
        for (MapMarker m : SHARED_MARKERS) {
            if (isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName())) {
                ownSharedMarkers.add(m);
            }
        }
        UNDO_STACK.addLast(new Snapshot(
                new ArrayList<>(STROKES),
                copyMarkers(MARKERS),
                ownSharedStrokes,
                copyMarkers(ownSharedMarkers)
        ));
    }

    public static void addCompletedStroke(MapStroke stroke) {
        pushUndoSnapshot();
        STROKES.add(stroke);
        invalidateVisibleCache();
        ClientNetworking.sendAddStroke(stroke);
    }

    public static void addMarker(MapMarker marker) {
        pushUndoSnapshot();
        MARKERS.add(marker);
        invalidateVisibleCache();
        ClientNetworking.sendUpsertMarker(marker);
    }

    public static void syncMarker(MapMarker marker) {
        boolean inPersonal = containsMarkerId(MARKERS, marker.getId());
        String dim = (!inPersonal && containsMarkerId(SHARED_MARKERS, marker.getId()))
                ? AtlasSavedData.SHARED_DIMENSION
                : ClientNetworking.currentDimensionKey();
        ClientNetworking.sendUpsertMarker(dim, marker);
    }

    public static @Nullable MapMarker findMarkerById(UUID id) {
        for (MapMarker m : MARKERS) {
            if (m.getId().equals(id)) {
                return m;
            }
        }
        if (sharedMapMode) {
            for (MapMarker m : SHARED_MARKERS) {
                if (m.getId().equals(id)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean isOwnedByLocalPlayer(@Nullable UUID authorUuid, @Nullable String authorName) {
        if (authorUuid == null) {
            return true;
        }
        var player = Minecraft.getInstance().player;
        if (player == null || authorUuid.equals(player.getUUID())) {
            return true;
        }
        return authorName != null && authorName.equalsIgnoreCase(player.getScoreboardName());
    }

    public static void removeMarker(UUID id) {
        if (removeMarkerFromList(MARKERS, ClientNetworking.currentDimensionKey(), id, true)) {
            return;
        }
        if (sharedMapMode) {
            removeMarkerFromList(SHARED_MARKERS, AtlasSavedData.SHARED_DIMENSION, id, true);
        }
    }

    private static boolean removeMarkerFromList(List<MapMarker> markers, String dim, UUID id, boolean snapshot) {
        for (int i = 0; i < markers.size(); i++) {
            MapMarker marker = markers.get(i);
            if (marker.getId().equals(id)) {
                if (!isOwnedByLocalPlayer(marker.getAuthorUuid(), marker.getAuthorName())) {
                    return true;
                }
                if (snapshot) {
                    pushUndoSnapshot();
                }
                markers.remove(i);
                invalidateVisibleCache();
                ClientNetworking.sendRemoveMarker(dim, id);
                return true;
            }
        }
        return false;
    }

    public static @Nullable MapMarker findMarkerAt(double worldX, double worldZ, double maxWorldDist) {
        double bestDistSq = maxWorldDist * maxWorldDist;
        MapMarker best = null;
        for (int i = MARKERS.size() - 1; i >= 0; i--) {
            MapMarker m = MARKERS.get(i);
            if (!isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName())) {
                continue;
            }
            double dx = m.getWorldX() - worldX;
            double dz = m.getWorldZ() - worldZ;
            double dSq = dx * dx + dz * dz;
            if (dSq <= bestDistSq) {
                bestDistSq = dSq;
                best = m;
            }
        }
        if (sharedMapMode) {
            for (int i = SHARED_MARKERS.size() - 1; i >= 0; i--) {
                MapMarker m = SHARED_MARKERS.get(i);
                if (!isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName())) {
                    continue;
                }
                double dx = m.getWorldX() - worldX;
                double dz = m.getWorldZ() - worldZ;
                double dSq = dx * dx + dz * dz;
                if (dSq <= bestDistSq) {
                    bestDistSq = dSq;
                    best = m;
                }
            }
        }
        return best;
    }

    private static boolean containsMarkerId(List<MapMarker> markers, UUID id) {
        for (int i = 0; i < markers.size(); i++) {
            if (markers.get(i).getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMarkerWithLabel(List<MapMarker> markers, UUID id, String label) {
        for (int i = 0; i < markers.size(); i++) {
            MapMarker marker = markers.get(i);
            if (marker.getId().equals(id) && marker.getLabel().equals(label)) {
                return true;
            }
        }
        return false;
    }

    private static void restoreLayer(
            List<MapMarker> currentMarkers,
            List<MapStroke> currentStrokes,
            List<MapMarker> savedMarkers,
            List<MapStroke> savedStrokes,
            String dimensionKey
    ) {
        for (MapMarker marker : currentMarkers) {
            if (!containsMarkerId(savedMarkers, marker.getId())) {
                ClientNetworking.sendRemoveMarker(dimensionKey, marker.getId());
            }
        }
        for (MapMarker marker : savedMarkers) {
            if (!containsMarkerWithLabel(currentMarkers, marker.getId(), marker.getLabel())) {
                ClientNetworking.sendUpsertMarker(dimensionKey, marker);
            }
        }
        currentMarkers.clear();
        currentMarkers.addAll(savedMarkers);
        currentStrokes.clear();
        currentStrokes.addAll(savedStrokes);
        ClientNetworking.sendReplaceStrokes(dimensionKey, currentStrokes);
    }

    public static boolean undo() {
        if (UNDO_STACK.isEmpty()) {
            return false;
        }
        personalStrokesErased = false;
        sharedStrokesErased = false;
        Snapshot snapshot = UNDO_STACK.removeLast();
        restoreLayer(MARKERS, STROKES, snapshot.markers(), snapshot.strokes(), ClientNetworking.currentDimensionKey());
        List<MapMarker> ownSharedMarkers = new ArrayList<>();
        for (MapMarker m : SHARED_MARKERS) {
            if (isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName())) {
                ownSharedMarkers.add(m);
            }
        }
        List<MapStroke> ownSharedStrokes = new ArrayList<>();
        for (MapStroke s : SHARED_STROKES) {
            if (isOwnedByLocalPlayer(s.getAuthorUuid(), s.getAuthorName())) {
                ownSharedStrokes.add(s);
            }
        }
        boolean sharedMarkersChanged = !ownSharedMarkers.equals(snapshot.sharedMarkers());
        boolean sharedStrokesChanged = !ownSharedStrokes.equals(snapshot.sharedStrokes());
        if (sharedMarkersChanged) {
            SHARED_MARKERS.removeIf(m -> isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName()));
            SHARED_MARKERS.addAll(copyMarkers(snapshot.sharedMarkers()));
            String sharedDim = AtlasSavedData.SHARED_DIMENSION;
            for (MapMarker prev : ownSharedMarkers) {
                if (!containsMarkerId(snapshot.sharedMarkers(), prev.getId())) {
                    ClientNetworking.sendRemoveMarker(sharedDim, prev.getId());
                }
            }
            for (MapMarker next : snapshot.sharedMarkers()) {
                if (!containsMarkerWithLabel(ownSharedMarkers, next.getId(), next.getLabel())) {
                    ClientNetworking.sendUpsertMarker(sharedDim, next);
                }
            }
        }
        if (sharedStrokesChanged) {
            SHARED_STROKES.removeIf(s -> isOwnedByLocalPlayer(s.getAuthorUuid(), s.getAuthorName()));
            SHARED_STROKES.addAll(snapshot.sharedStrokes());
            ClientNetworking.sendReplaceStrokes(
                    AtlasSavedData.SHARED_DIMENSION,
                    List.copyOf(snapshot.sharedStrokes())
            );
        }
        invalidateVisibleCache();
        return true;
    }

    public static boolean eraseStrokesAt(float worldX, float worldZ, float radius, boolean allowSnapshot) {
        boolean changedPersonal = eraseStrokesInList(STROKES, worldX, worldZ, radius, allowSnapshot);
        personalStrokesErased |= changedPersonal;
        boolean changedShared = false;
        if (sharedMapMode) {
            changedShared = eraseStrokesInList(SHARED_STROKES, worldX, worldZ, radius, allowSnapshot && !changedPersonal);
            sharedStrokesErased |= changedShared;
        }
        return changedPersonal || changedShared;
    }

    private static boolean eraseStrokesInList(List<MapStroke> strokes, float worldX, float worldZ, float radius, boolean allowSnapshot) {
        boolean changed = false;
        List<MapStroke> next = new ArrayList<>(strokes.size());
        for (MapStroke stroke : strokes) {
            if (isOwnedByLocalPlayer(stroke.getAuthorUuid(), stroke.getAuthorName()) && stroke.touchesCircle(worldX, worldZ, radius)) {
                List<MapStroke> pieces = stroke.splitByCircle(worldX, worldZ, radius);
                if (pieces.size() == 1 && pieces.getFirst() == stroke) {
                    next.add(stroke);
                    continue;
                }
                if (!changed) {
                    if (allowSnapshot) {
                        pushUndoSnapshot();
                    }
                    changed = true;
                }
                next.addAll(pieces);
            } else {
                next.add(stroke);
            }
        }
        if (changed) {
            strokes.clear();
            strokes.addAll(next);
            invalidateVisibleCache();
        }
        return changed;
    }

    public static void flushErasedStrokes() {
        if (personalStrokesErased) {
            personalStrokesErased = false;
            ClientNetworking.sendReplaceStrokes(ClientNetworking.currentDimensionKey(), STROKES);
        }
        if (sharedStrokesErased) {
            sharedStrokesErased = false;
            List<MapStroke> ownShared = new ArrayList<>();
            for (MapStroke s : SHARED_STROKES) {
                if (isOwnedByLocalPlayer(s.getAuthorUuid(), s.getAuthorName())) {
                    ownShared.add(s);
                }
            }
            ClientNetworking.sendReplaceStrokes(AtlasSavedData.SHARED_DIMENSION, ownShared);
        }
    }

    public static boolean eraseMarkersAt(float worldX, float worldZ, float radius, @Nullable UUID activeEditId, boolean allowSnapshot) {
        boolean removed = eraseMarkersInList(MARKERS, ClientNetworking.currentDimensionKey(), worldX, worldZ, radius, activeEditId, allowSnapshot);
        if (sharedMapMode) {
            removed |= eraseMarkersInList(SHARED_MARKERS, AtlasSavedData.SHARED_DIMENSION, worldX, worldZ, radius, activeEditId, allowSnapshot && !removed);
        }
        return removed;
    }

    private static boolean eraseMarkersInList(
            List<MapMarker> markers,
            String dim,
            float worldX,
            float worldZ,
            float radius,
            @Nullable UUID activeEditId,
            boolean allowSnapshot
    ) {
        float rSq = radius * radius;
        boolean removedAny = false;
        Iterator<MapMarker> it = markers.iterator();
        while (it.hasNext()) {
            MapMarker m = it.next();
            if (m.getId().equals(activeEditId) || !isOwnedByLocalPlayer(m.getAuthorUuid(), m.getAuthorName())) {
                continue;
            }
            float dx = m.getWorldX() - worldX;
            float dz = m.getWorldZ() - worldZ;
            if (dx * dx + dz * dz <= rSq) {
                if (!removedAny && allowSnapshot) {
                    pushUndoSnapshot();
                }
                it.remove();
                invalidateVisibleCache();
                ClientNetworking.sendRemoveMarker(dim, m.getId());
                removedAny = true;
            }
        }
        return removedAny;
    }
}
