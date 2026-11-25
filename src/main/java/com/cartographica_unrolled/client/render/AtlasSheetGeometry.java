package com.cartographica_unrolled.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

public final class AtlasSheetGeometry {
    public static final float STROKE_Z = 0.0093f;
    private static final int CAP_SIDES = 8;
    private static final float[] CAP_COS = new float[CAP_SIDES];
    private static final float[] CAP_SIN = new float[CAP_SIDES];

    static {
        for (int i = 0; i < CAP_SIDES; i++) {
            double angle = (2.0 * Math.PI * i) / CAP_SIDES;
            CAP_COS[i] = (float) Math.cos(angle);
            CAP_SIN[i] = (float) Math.sin(angle);
        }
    }

    public static final class ClipScratch {
        final float[] polyX = new float[8];
        final float[] polyY = new float[8];
        final float[] tmpX = new float[8];
        final float[] tmpY = new float[8];
        final float[] capX = new float[CAP_SIDES];
        final float[] capY = new float[CAP_SIDES];
    }

    private AtlasSheetGeometry() {
    }

    public static void emitQuad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float x0,
            float y0,
            float x1,
            float y1,
            float z,
            float u0,
            float vBottom,
            float u1,
            float vTop,
            int argb,
            int light
    ) {
        buffer.addVertex(pose, x0, y0, z).setColor(argb).setUv(u0, vBottom).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x1, y0, z).setColor(argb).setUv(u1, vBottom).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x1, y1, z).setColor(argb).setUv(u1, vTop).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x0, y1, z).setColor(argb).setUv(u0, vTop).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    public static void emitCrossStroke(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float x0,
            float y0,
            float x1,
            float y1,
            float halfWidth,
            float z,
            float minX,
            float maxX,
            float minY,
            float maxY,
            int color,
            int light,
            ClipScratch scratch
    ) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (!Float.isFinite(len) || len < 1.0e-6f) {
            return;
        }
        float nx = -dy * halfWidth / len;
        float ny = dx * halfWidth / len;
        emitClippedQuad(
                buffer, pose,
                x0 - nx, y0 - ny,
                x1 - nx, y1 - ny,
                x1 + nx, y1 + ny,
                x0 + nx, y0 + ny,
                z, minX, maxX, minY, maxY,
                color, light, scratch
        );
    }

    public static void emitClippedDisc(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float cx,
            float cy,
            float radius,
            float minX,
            float maxX,
            float minY,
            float maxY,
            int color,
            int light,
            ClipScratch scratch
    ) {
        float[] capX = scratch.capX;
        float[] capY = scratch.capY;
        for (int i = 0; i < CAP_SIDES; i++) {
            capX[i] = cx + radius * CAP_COS[i];
            capY[i] = cy + radius * CAP_SIN[i];
        }
        emitClippedQuad(
                buffer, pose,
                capX[0], capY[0],
                capX[1], capY[1],
                capX[2], capY[2],
                capX[3], capY[3],
                STROKE_Z, minX, maxX, minY, maxY,
                color, light, scratch
        );
        emitClippedQuad(
                buffer, pose,
                capX[4], capY[4],
                capX[5], capY[5],
                capX[6], capY[6],
                capX[7], capY[7],
                STROKE_Z, minX, maxX, minY, maxY,
                color, light, scratch
        );
        emitClippedQuad(
                buffer, pose,
                capX[0], capY[0],
                capX[3], capY[3],
                capX[4], capY[4],
                capX[7], capY[7],
                STROKE_Z, minX, maxX, minY, maxY,
                color, light, scratch
        );
    }

    public static void emitClippedQuad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float x0, float y0,
            float x1, float y1,
            float x2, float y2,
            float x3, float y3,
            float minX, float maxX,
            float minY, float maxY,
            int color,
            int light,
            ClipScratch scratch
    ) {
        emitClippedQuad(buffer, pose, x0, y0, x1, y1, x2, y2, x3, y3, STROKE_Z, minX, maxX, minY, maxY, color, light, scratch);
    }

    public static void emitClippedQuad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float x0, float y0,
            float x1, float y1,
            float x2, float y2,
            float x3, float y3,
            float z,
            float minX, float maxX,
            float minY, float maxY,
            int color,
            int light,
            ClipScratch scratch
    ) {
        float qMinX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
        float qMaxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        float qMinY = Math.min(Math.min(y0, y1), Math.min(y2, y3));
        float qMaxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        if (qMaxX <= minX || qMinX >= maxX || qMaxY <= minY || qMinY >= maxY) {
            return;
        }
        if (qMinX >= minX && qMaxX <= maxX && qMinY >= minY && qMaxY <= maxY) {
            emitStrokeQuad(buffer, pose, x0, y0, x1, y1, x2, y2, x3, y3, z, color, light);
            return;
        }
        float[] polyX = scratch.polyX;
        float[] polyY = scratch.polyY;
        float[] tmpX = scratch.tmpX;
        float[] tmpY = scratch.tmpY;
        polyX[0] = x0;
        polyY[0] = y0;
        polyX[1] = x1;
        polyY[1] = y1;
        polyX[2] = x2;
        polyY[2] = y2;
        polyX[3] = x3;
        polyY[3] = y3;
        int n = clipPolygonEdge(polyX, polyY, 4, tmpX, tmpY, 0, minX);
        if (n < 3) {
            return;
        }
        n = clipPolygonEdge(tmpX, tmpY, n, polyX, polyY, 1, maxX);
        if (n < 3) {
            return;
        }
        n = clipPolygonEdge(polyX, polyY, n, tmpX, tmpY, 2, minY);
        if (n < 3) {
            return;
        }
        n = clipPolygonEdge(tmpX, tmpY, n, polyX, polyY, 3, maxY);
        if (n < 3) {
            return;
        }

        for (int i = 1; i + 1 < n; i += 2) {
            int i3 = i + 2 < n ? i + 2 : i + 1;
            emitStrokeQuad(
                    buffer, pose,
                    polyX[0], polyY[0],
                    polyX[i], polyY[i],
                    polyX[i + 1], polyY[i + 1],
                    polyX[i3], polyY[i3],
                    z, color, light
            );
        }
    }

    private static int clipPolygonEdge(
            float[] inX, float[] inY, int count,
            float[] outX, float[] outY,
            int edge, float bound
    ) {
        int outCount = 0;
        float ax = inX[count - 1];
        float ay = inY[count - 1];
        boolean aIn = isInsideEdge(ax, ay, edge, bound);
        for (int i = 0; i < count; i++) {
            float bx = inX[i];
            float by = inY[i];
            boolean bIn = isInsideEdge(bx, by, edge, bound);
            if (bIn) {
                if (!aIn) {
                    float t = intersectEdgeT(ax, ay, bx, by, edge, bound);
                    outX[outCount] = ax + t * (bx - ax);
                    outY[outCount] = ay + t * (by - ay);
                    outCount++;
                }
                outX[outCount] = bx;
                outY[outCount] = by;
                outCount++;
            } else if (aIn) {
                float t = intersectEdgeT(ax, ay, bx, by, edge, bound);
                outX[outCount] = ax + t * (bx - ax);
                outY[outCount] = ay + t * (by - ay);
                outCount++;
            }
            ax = bx;
            ay = by;
            aIn = bIn;
        }
        return outCount;
    }

    private static boolean isInsideEdge(float x, float y, int edge, float bound) {
        return switch (edge) {
            case 0 -> x >= bound;
            case 1 -> x <= bound;
            case 2 -> y >= bound;
            default -> y <= bound;
        };
    }

    private static float intersectEdgeT(float ax, float ay, float bx, float by, int edge, float bound) {
        float denom = edge < 2 ? bx - ax : by - ay;
        if (Math.abs(denom) < 1.0e-12f) {
            return 0.0f;
        }
        float num = edge < 2 ? bound - ax : bound - ay;
        return Mth.clamp(num / denom, 0.0f, 1.0f);
    }

    private static void emitStrokeQuad(
            VertexConsumer buffer,
            PoseStack.Pose pose,
            float x0, float y0,
            float x1, float y1,
            float x2, float y2,
            float x3, float y3,
            float z,
            int color,
            int light
    ) {
        buffer.addVertex(pose, x0, y0, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x1, y1, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x2, y2, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
        buffer.addVertex(pose, x3, y3, z).setColor(color).setUv(0.5f, 0.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, 0.0f, 0.0f, 1.0f);
    }
}
