package com.cartographica_unrolled.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class MapStroke {
    public record Point(float x, float z) {}

    private final List<Point> points;
    private final int color;
    private final float width;
    private final boolean isDye;
    private final UUID authorUuid;
    private final String authorName;
    private float minX;
    private float minZ;
    private float maxX;
    private float maxZ;

    public MapStroke(List<Point> points, int color, float width, boolean isDye) {
        this(points, color, width, isDye, null, null);
    }

    public MapStroke(List<Point> points, int color, float width, boolean isDye, UUID authorUuid, String authorName) {
        this.points = new ArrayList<>(points);
        this.color = color;
        this.width = width;
        this.isDye = isDye;
        this.authorUuid = authorUuid;
        this.authorName = authorName;
        recomputeBounds();
    }

    public void addPoint(float x, float z) {
        if (!points.isEmpty()) {
            Point last = points.getLast();
            float dx = x - last.x();
            float dz = z - last.z();
            if (dx * dx + dz * dz < 0.25f) {
                return;
            }
        }
        points.add(new Point(x, z));
        if (points.size() == 1) {
            minX = maxX = x;
            minZ = maxZ = z;
        } else {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
    }

    public void simplify(float epsilon) {
        if (points.size() <= 2) {
            return;
        }
        List<Point> simplified = ramerDouglasPeucker(points, epsilon);
        points.clear();
        points.addAll(simplified);
        recomputeBounds();
    }

    private void recomputeBounds() {
        if (points.isEmpty()) {
            minX = maxX = minZ = maxZ = 0;
            return;
        }
        minX = maxX = points.getFirst().x();
        minZ = maxZ = points.getFirst().z();
        for (Point p : points) {
            if (p.x() < minX) minX = p.x();
            if (p.x() > maxX) maxX = p.x();
            if (p.z() < minZ) minZ = p.z();
            if (p.z() > maxZ) maxZ = p.z();
        }
    }

    public boolean intersectsBounds(double left, double top, double right, double bottom) {
        double padding = width * 0.5 + 1.0;
        return maxX + padding >= left && minX - padding <= right
                && maxZ + padding >= top && minZ - padding <= bottom;
    }

    public boolean intersectsCircle(float cx, float cz, float radius) {
        float r = radius + width * 0.5f;
        return cx + r >= minX && cx - r <= maxX && cz + r >= minZ && cz - r <= maxZ;
    }

    public List<MapStroke> splitByCircle(float cx, float cz, float radius) {
        if (points.isEmpty() || radius <= 0.0f) {
            return List.of(this);
        }
        float effectiveRadius = radius + width * 0.5f;
        double radiusSq = (double) effectiveRadius * effectiveRadius;
        if (points.size() == 1) {
            Point point = points.getFirst();
            double dx = (double) point.x() - cx;
            double dz = (double) point.z() - cz;
            return dx * dx + dz * dz <= radiusSq ? List.of() : List.of(this);
        }
        List<MapStroke> result = new ArrayList<>();
        List<Point> current = new ArrayList<>();
        boolean cut = false;
        for (int i = 1; i < points.size(); i++) {
            Point a = points.get(i - 1);
            Point b = points.get(i);
            double dx = (double) b.x() - a.x();
            double dz = (double) b.z() - a.z();
            double ox = (double) a.x() - cx;
            double oz = (double) a.z() - cz;
            double lengthSq = dx * dx + dz * dz;
            if (lengthSq == 0.0) {
                if (ox * ox + oz * oz >= radiusSq) {
                    appendDistinct(current, a);
                }
                continue;
            }
            double linear = 2.0 * (ox * dx + oz * dz);
            double discriminant = linear * linear - 4.0 * lengthSq * (ox * ox + oz * oz - radiusSq);
            if (discriminant <= 0.0) {
                appendDistinct(current, a);
                appendDistinct(current, b);
                continue;
            }
            double root = Math.sqrt(discriminant);
            double enter = Math.max(0.0, (-linear - root) / (2.0 * lengthSq));
            double exit = Math.min(1.0, (-linear + root) / (2.0 * lengthSq));
            if (enter >= exit || exit <= 0.0 || enter >= 1.0) {
                appendDistinct(current, a);
                appendDistinct(current, b);
                continue;
            }
            cut = true;
            if (enter > 0.0) {
                appendDistinct(current, a);
                appendDistinct(current, new Point((float) (a.x() + enter * dx), (float) (a.z() + enter * dz)));
            }
            addPiece(result, current);
            current = new ArrayList<>();
            if (exit < 1.0) {
                appendDistinct(current, new Point((float) (a.x() + exit * dx), (float) (a.z() + exit * dz)));
                appendDistinct(current, b);
            }
        }
        addPiece(result, current);
        return cut ? result : List.of(this);
    }

    private static void appendDistinct(List<Point> points, Point point) {
        if (points.isEmpty() || !points.getLast().equals(point)) {
            points.add(point);
        }
    }

    private void addPiece(List<MapStroke> result, List<Point> piece) {
        double length = 0.0;
        for (int i = 1; i < piece.size(); i++) {
            Point a = piece.get(i - 1);
            Point b = piece.get(i);
            length += Math.hypot((double) b.x() - a.x(), (double) b.z() - a.z());
        }
        if (length >= 0.12) {
            result.add(new MapStroke(piece, color, width, isDye, authorUuid, authorName));
        }
    }

    public boolean touchesCircle(float cx, float cz, float radius) {
        if (!intersectsCircle(cx, cz, radius)) {
            return false;
        }
        float r = radius + width * 0.5f;
        float rSq = r * r;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            float dx = p.x() - cx;
            float dz = p.z() - cz;
            if (dx * dx + dz * dz <= rSq) {
                return true;
            }
            if (i > 0) {
                Point prev = points.get(i - 1);
                if (segmentIntersectsCircle(prev.x(), prev.z(), p.x(), p.z(), cx, cz, rSq)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentIntersectsCircle(float x1, float z1, float x2, float z2, float cx, float cz, float rSq) {
        float vx = x2 - x1;
        float vz = z2 - z1;
        float wx = cx - x1;
        float wz = cz - z1;
        float lenSq = vx * vx + vz * vz;
        float t = lenSq > 0.0001f ? Math.max(0f, Math.min(1f, (wx * vx + wz * vz) / lenSq)) : 0f;
        float px = x1 + t * vx - cx;
        float pz = z1 + t * vz - cz;
        return px * px + pz * pz <= rSq;
    }

    private static List<Point> ramerDouglasPeucker(List<Point> pts, float epsilon) {
        int n = pts.size();
        if (n <= 2) {
            return new ArrayList<>(pts);
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        int[] stack = new int[64];
        int top = 0;
        stack[top++] = 0;
        stack[top++] = n - 1;
        double epsSq = (double) epsilon * epsilon;

        while (top > 0) {
            int endIdx = stack[--top];
            int startIdx = stack[--top];
            if (endIdx - startIdx <= 1) {
                continue;
            }
            Point start = pts.get(startIdx);
            Point end = pts.get(endIdx);
            double sx = start.x();
            double sz = start.z();
            double dx = (double) end.x() - sx;
            double dz = (double) end.z() - sz;
            double lenSq = dx * dx + dz * dz;
            int bestIdx = -1;

            if (lenSq <= 1e-8) {
                double maxDistSq = epsSq;
                for (int i = startIdx + 1; i < endIdx; i++) {
                    Point p = pts.get(i);
                    double px = (double) p.x() - sx;
                    double pz = (double) p.z() - sz;
                    double dSq = px * px + pz * pz;
                    if (dSq > maxDistSq) {
                        maxDistSq = dSq;
                        bestIdx = i;
                    }
                }
            } else {
                double maxCrossSq = epsSq * lenSq;
                for (int i = startIdx + 1; i < endIdx; i++) {
                    Point p = pts.get(i);
                    double cross = dz * ((double) p.x() - sx) - dx * ((double) p.z() - sz);
                    double crossSq = cross * cross;
                    if (crossSq > maxCrossSq) {
                        maxCrossSq = crossSq;
                        bestIdx = i;
                    }
                }
            }

            if (bestIdx >= 0) {
                keep[bestIdx] = true;
                if (top + 4 > stack.length) {
                    stack = java.util.Arrays.copyOf(stack, stack.length << 1);
                }
                stack[top++] = bestIdx;
                stack[top++] = endIdx;
                stack[top++] = startIdx;
                stack[top++] = bestIdx;
            }
        }

        List<Point> out = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    public List<Point> getPoints() {
        return points;
    }

    public float getMinX() {
        return minX;
    }

    public float getMinZ() {
        return minZ;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxZ() {
        return maxZ;
    }

    public int getColor() {
        return color;
    }

    public float getWidth() {
        return width;
    }

    public boolean isDye() {
        return isDye;
    }

    public UUID getAuthorUuid() {
        return authorUuid;
    }

    public String getAuthorName() {
        return authorName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof MapStroke other)) {
            return false;
        }
        return this.color == other.color
                && Float.compare(this.width, other.width) == 0
                && this.isDye == other.isDye
                && Objects.equals(this.points, other.points)
                && Objects.equals(this.authorUuid, other.authorUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.points, this.color, this.width, this.isDye, this.authorUuid);
    }
}
