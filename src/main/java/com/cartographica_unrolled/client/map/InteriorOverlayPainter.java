package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

final class InteriorOverlayPainter {
    private static final int BLOCK_PX = AtlasInteriorOverlay.BLOCK_PX;
    private static final int INK = 0x2F1E12;
    private static final int INK_SOFT = 0x4E3826;
    private static final int SHADOW_COL = 0x22150B;
    private static final int PARCHMENT_TONE = AtlasMath.PARCHMENT_WARM;

    private static final int[] BOOK_SPINES = {
            0x9A3E36, 0x3D5E86, 0x4A7448, 0xC29740, 0x784B6C, 0xC6B692
    };

    private static final float[] DROP_NW_EDGE = new float[6];
    private static final float[] DROP_SE_EDGE = new float[3];
    private static final float[] DROP_NW_CORNER = new float[6 * 6];
    private static final float[] DROP_SIDE_CORNER = new float[3 * 5];
    private static final float[] DROP_SE_CORNER = new float[3 * 3];

    private static final float[] WALL_NW_EDGE = new float[7];
    private static final float[] WALL_SE_EDGE = new float[4];
    private static final float[] WALL_NW_CORNER = new float[6 * 6];
    private static final float[] QUARTER_SQRT_LUT = new float[6600];

    static {
        for (int i = 0; i < 6; i++) {
            float t = 1.0f - i / 6.0f;
            DROP_NW_EDGE[i] = t * t * 0.34f;
        }
        for (int i = 0; i < 3; i++) {
            float t = 1.0f - i / 3.0f;
            DROP_SE_EDGE[i] = t * t * 0.16f;
        }
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 6; x++) {
                float d = (float) Math.hypot(x, y);
                if (d < 6.0f) {
                    float t = 1.0f - d / 6.0f;
                    DROP_NW_CORNER[y * 6 + x] = t * t * 0.30f;
                    WALL_NW_CORNER[y * 6 + x] = (float) Math.pow(t, 1.4) * 0.34f;
                }
            }
        }
        for (int far = 0; far < 5; far++) {
            for (int near = 0; near < 3; near++) {
                float d = (float) Math.hypot(near, far);
                if (d < 5.0f) {
                    float t = 1.0f - d / 5.0f;
                    DROP_SIDE_CORNER[far * 3 + near] = t * t * 0.15f;
                }
            }
        }
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                float d = (float) Math.hypot(x, y);
                if (d < 3.5f) {
                    float t = 1.0f - d / 3.5f;
                    DROP_SE_CORNER[y * 3 + x] = t * t * 0.12f;
                }
            }
        }
        for (int i = 0; i < 7; i++) {
            float t = 1.0f - i / 7.0f;
            WALL_NW_EDGE[i] = (float) Math.pow(t, 1.35) * 0.42f;
        }
        for (int i = 0; i < 4; i++) {
            float t = 1.0f - i / 4.0f;
            WALL_SE_EDGE[i] = (float) Math.pow(t, 1.5) * 0.20f;
        }
        for (int q = 0; q < QUARTER_SQRT_LUT.length; q++) {
            QUARTER_SQRT_LUT[q] = (float) Math.sqrt(q * 0.25);
        }
    }

    private InteriorOverlayPainter() {
    }

    static void paint(
            int[] fb,
            int texW,
            int texH,
            InteriorSliceSnapshot.Cell[] cells,
            int spanX,
            int spanZ,
            int oWx,
            int oWz,
            float[] shadowBuf
    ) {
        Arrays.fill(fb, 0, texW * texH, 0);

        for (int cz = 0; cz < spanZ; cz++) {
            for (int cx = 0; cx < spanX; cx++) {
                InteriorSliceSnapshot.Cell c = cells[cz * spanX + cx];
                int px0 = cx * BLOCK_PX;
                int py0 = cz * BLOCK_PX;
                if (c.kind == InteriorSliceSnapshot.KIND_NONE) {
                    paintOuterDropShadow(fb, texW, cells, spanX, spanZ, cx, cz, px0, py0);
                    continue;
                }
                if (c.kind == InteriorSliceSnapshot.KIND_WALL) {
                    paintWall(fb, texW, cells, spanX, spanZ, cx, cz, c, px0, py0);
                } else if (c.kind == InteriorSliceSnapshot.KIND_BOOKSHELF) {
                    paintBookshelf(fb, texW, cells, spanX, spanZ, cx, cz, c, px0, py0);
                } else if (c.kind == InteriorSliceSnapshot.KIND_WINDOW && !c.sillState.isAir()) {
                    paintSillBase(fb, texW, cells, spanX, spanZ, cx, cz, c, px0, py0);
                } else if (c.floorWater || c.floorLava) {
                    paintFluidFloor(fb, texW, c, px0, py0);
                    applyWallShadowsOnFloor(fb, texW, cells, spanX, spanZ, cx, cz, px0, py0);
                } else {
                    paintFloor(fb, texW, c, px0, py0);
                    applyFloorHeightShading(fb, texW, cells, spanX, spanZ, cx, cz, c, px0, py0);
                    applyWallShadowsOnFloor(fb, texW, cells, spanX, spanZ, cx, cz, px0, py0);
                }
            }
        }

        for (int cz = 0; cz < spanZ; cz++) {
            for (int cx = 0; cx < spanX; cx++) {
                InteriorSliceSnapshot.Cell c = cells[cz * spanX + cx];
                if (c.kind == InteriorSliceSnapshot.KIND_FLOOR && !c.carpetState.isAir()) {
                    paintCarpet(fb, texW, cells, spanX, spanZ, cx, cz, c, cx * BLOCK_PX, cz * BLOCK_PX);
                }
            }
        }

        for (int cz = 0; cz < spanZ; cz++) {
            for (int cx = 0; cx < spanX; cx++) {
                InteriorSliceSnapshot.Cell c = cells[cz * spanX + cx];
                int px0 = cx * BLOCK_PX;
                int py0 = cz * BLOCK_PX;
                if (c.kind == InteriorSliceSnapshot.KIND_WINDOW) {
                    paintWindow(fb, texW, texH, cells, spanX, spanZ, cx, cz, c, px0, py0, shadowBuf);
                } else if (c.kind == InteriorSliceSnapshot.KIND_DOOR) {
                    paintDoor(fb, texW, texH, cells, spanX, spanZ, cx, cz, c, px0, py0, shadowBuf);
                } else if (c.kind == InteriorSliceSnapshot.KIND_WALL) {
                    if (!c.ceilingFixture.isAir()) {
                        paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.ceilingFixture, 0xFFFFFF,
                                px0, py0, false, 0.0f, false, shadowBuf);
                    }
                } else if (c.kind == InteriorSliceSnapshot.KIND_FLOOR) {
                    if (!c.lowerFixture.isAir()) {
                        paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.lowerFixture, c.lowerTintRgb,
                                px0, py0, true, 0.26f, InteriorSliceSnapshot.isThinReadableFixture(c.lowerFixture), shadowBuf);
                        if (c.inNiche) {
                            applyNicheShade(fb, texW, px0, py0);
                        }
                    }
                    if (!c.upperFixture.isAir()) {
                        paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.upperFixture, c.upperTintRgb,
                                px0, py0, true, 0.18f, InteriorSliceSnapshot.isThinReadableFixture(c.upperFixture), shadowBuf);
                    }
                    if (!c.ceilingFixture.isAir()) {
                        paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.ceilingFixture, 0xFFFFFF,
                                px0, py0, false, 0.0f, false, shadowBuf);
                    }
                }
            }
        }

        applyRoomLighting(fb, texW, texH, cells, spanX, spanZ);
        finishCartographyStyle(fb, texW, texH, oWx, oWz);
    }

    private static void paintOuterDropShadow(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz, int px0, int py0
    ) {
        boolean n = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx, cz - 1);
        boolean s = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx, cz + 1);
        boolean w = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx - 1, cz);
        boolean e = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx + 1, cz);
        boolean nw = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx - 1, cz - 1);
        boolean ne = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx + 1, cz - 1);
        boolean sw = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx - 1, cz + 1);
        boolean se = InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, cx + 1, cz + 1);
        if (!n && !s && !w && !e && !nw && !ne && !sw && !se) {
            return;
        }
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                float shadow = 0.0f;
                if (n && ly < 6) {
                    shadow = Math.max(shadow, DROP_NW_EDGE[ly]);
                }
                if (w && lx < 6) {
                    shadow = Math.max(shadow, DROP_NW_EDGE[lx]);
                }
                if (s && ly >= BLOCK_PX - 3) {
                    shadow = Math.max(shadow, DROP_SE_EDGE[BLOCK_PX - 1 - ly]);
                }
                if (e && lx >= BLOCK_PX - 3) {
                    shadow = Math.max(shadow, DROP_SE_EDGE[BLOCK_PX - 1 - lx]);
                }
                if (nw && lx < 6 && ly < 6) {
                    shadow = Math.max(shadow, DROP_NW_CORNER[ly * 6 + lx]);
                }
                if (ne && lx >= BLOCK_PX - 3 && ly < 5) {
                    shadow = Math.max(shadow, DROP_SIDE_CORNER[ly * 3 + (BLOCK_PX - 1 - lx)]);
                }
                if (sw && lx < 5 && ly >= BLOCK_PX - 3) {
                    shadow = Math.max(shadow, DROP_SIDE_CORNER[lx * 3 + (BLOCK_PX - 1 - ly)]);
                }
                if (se && lx >= BLOCK_PX - 3 && ly >= BLOCK_PX - 3) {
                    shadow = Math.max(shadow, DROP_SE_CORNER[(BLOCK_PX - 1 - ly) * 3 + (BLOCK_PX - 1 - lx)]);
                }
                if (shadow > 0.008f) {
                    int a = Mth.clamp(Math.round(shadow * 255.0f), 0, 255);
                    fb[rowBase + lx] = (a << 24) | SHADOW_COL;
                }
            }
        }
    }

    private static int sampleHiRgb(
            BlockTopTextureCache.@Nullable BakedBlockTile tile,
            int lx,
            int ly,
            int tintRgb,
            int fallback
    ) {
        if (tile == null || !tile.hasAnyPixel()) {
            return fallback;
        }
        int p = tile.hiPixel(lx, ly);
        int a = (p >>> 24) & 0xFF;
        if (a <= 0) {
            return fallback;
        }
        int tex = p & 0xFFFFFF;
        if (tile.isHiTinted(lx, ly)) {
            tex = AtlasMath.multiplyRgb(tex, tintRgb);
        }
        return a >= 250 ? tex : AtlasMath.mixRgb(fallback, tex, a / 255.0f);
    }

    private static void paintFloor(int[] fb, int texW, InteriorSliceSnapshot.Cell c, int px0, int py0) {
        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(c.floorState);
        int fallback = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(c.floorRgb, 0.88f, 0.96f), PARCHMENT_TONE, 0.14f);
        int avgRgb = (tile != null && tile.hasAnyPixel())
                ? tile.computeAvgHiRgb(c.floorTintRgb, fallback)
                : fallback;
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int rgb = fallback;
                if (tile != null && tile.hasAnyPixel()) {
                    int p = tile.hiPixel(lx, ly);
                    int a = (p >>> 24) & 0xFF;
                    if (a > 0) {
                        int tex = p & 0xFFFFFF;
                        if (tile.isHiTinted(lx, ly)) {
                            tex = AtlasMath.multiplyRgb(tex, c.floorTintRgb);
                        }
                        tex = AtlasMath.mixRgb(tex, avgRgb, 0.22f);
                        rgb = a >= 250 ? tex : AtlasMath.mixRgb(fallback, tex, a / 255.0f);
                    }
                }
                rgb = AtlasMath.mixRgb(rgb, PARCHMENT_TONE, 0.10f);
                fb[rowBase + lx] = 0xFF000000 | rgb;
            }
        }
    }

    private static void paintFluidFloor(int[] fb, int texW, InteriorSliceSnapshot.Cell c, int px0, int py0) {
        if (c.floorLava) {
            BlockTopTextureCache.BakedBlockTile lt = BlockTopTextureCache.getLavaTile();
            for (int ly = 0; ly < BLOCK_PX; ly++) {
                int rowBase = (py0 + ly) * texW + px0;
                for (int lx = 0; lx < BLOCK_PX; lx++) {
                    int rgb = (lt != null && lt.hasAnyPixel())
                            ? (lt.hiPixel(lx, ly) & 0xFFFFFF) : 0xD95B16;
                    rgb = AtlasMath.mixRgb(rgb, 0xF5C46E, 0.08f);
                    fb[rowBase + lx] = 0xFF000000 | rgb;
                }
            }
            return;
        }
        BlockTopTextureCache.BakedBlockTile wt = BlockTopTextureCache.getWaterTile();
        int waterBase = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(c.floorTintRgb, 0.82f, 0.88f), 0x4E86A2, 0.55f);
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int rgb = waterBase;
                if (wt != null && wt.hasAnyPixel()) {
                    int wp = wt.hiPixel(lx, ly);
                    int wl = (((wp >>> 16) & 0xFF) * 77 + ((wp >>> 8) & 0xFF) * 150 + (wp & 0xFF) * 29) >> 8;
                    float texMod = 1.0f + ((wl - 155) / 255.0f) * 0.30f;
                    rgb = AtlasMath.scaleRgb(rgb, Mth.clamp(texMod, 0.86f, 1.14f));
                }
                fb[rowBase + lx] = 0xFF000000 | rgb;
            }
        }
    }

    private static void applyFloorHeightShading(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz,
            InteriorSliceSnapshot.Cell c, int px0, int py0
    ) {
        int hN = InteriorSliceSnapshot.higherFloorDelta(cells, spanX, spanZ, cx, cz - 1, c.floorY);
        int hW = InteriorSliceSnapshot.higherFloorDelta(cells, spanX, spanZ, cx - 1, cz, c.floorY);
        int hS = InteriorSliceSnapshot.higherFloorDelta(cells, spanX, spanZ, cx, cz + 1, c.floorY);
        int hE = InteriorSliceSnapshot.higherFloorDelta(cells, spanX, spanZ, cx + 1, cz, c.floorY);
        int dN = InteriorSliceSnapshot.lowerFloorDelta(cells, spanX, spanZ, cx, cz - 1, c.floorY);
        int dW = InteriorSliceSnapshot.lowerFloorDelta(cells, spanX, spanZ, cx - 1, cz, c.floorY);
        int dS = InteriorSliceSnapshot.lowerFloorDelta(cells, spanX, spanZ, cx, cz + 1, c.floorY);
        int dE = InteriorSliceSnapshot.lowerFloorDelta(cells, spanX, spanZ, cx + 1, cz, c.floorY);
        if (hN == 0 && hW == 0 && hS == 0 && hE == 0 && dN == 0 && dW == 0 && dS == 0 && dE == 0) {
            return;
        }
        float capN = Math.min(0.36f, 0.28f + (hN - 1) * 0.05f);
        float capW = Math.min(0.36f, 0.28f + (hW - 1) * 0.05f);
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int idx = rowBase + lx;
                int argb = fb[idx];
                int rgb = argb & 0xFFFFFF;
                float shade = 0.0f;
                if (hN > 0 && ly < 5) {
                    float t = 1.0f - ly / 5.0f;
                    shade = Math.max(shade, t * t * capN);
                }
                if (hW > 0 && lx < 5) {
                    float t = 1.0f - lx / 5.0f;
                    shade = Math.max(shade, t * t * capW);
                }
                if (hS > 0 && ly >= BLOCK_PX - 3) {
                    float t = 1.0f - (BLOCK_PX - 1 - ly) / 3.0f;
                    shade = Math.max(shade, t * t * 0.22f);
                }
                if (hE > 0 && lx >= BLOCK_PX - 3) {
                    float t = 1.0f - (BLOCK_PX - 1 - lx) / 3.0f;
                    shade = Math.max(shade, t * t * 0.22f);
                }
                if (shade > 0.0f) {
                    rgb = AtlasMath.mixRgb(rgb, SHADOW_COL, shade);
                }
                if ((dN > 0 && ly == 0) || (dW > 0 && lx == 0)) {
                    rgb = AtlasMath.mixRgb(rgb, INK_SOFT, 0.40f);
                } else if ((dN > 0 && ly == 1) || (dW > 0 && lx == 1)) {
                    rgb = AtlasMath.scaleRgb(rgb, 1.10f);
                }
                if ((dS > 0 && ly == BLOCK_PX - 1) || (dE > 0 && lx == BLOCK_PX - 1)) {
                    rgb = AtlasMath.mixRgb(rgb, INK, 0.50f);
                } else if ((dS > 0 && ly == BLOCK_PX - 2) || (dE > 0 && lx == BLOCK_PX - 2)) {
                    rgb = AtlasMath.mixRgb(rgb, INK_SOFT, 0.16f);
                }
                fb[idx] = (argb & 0xFF000000) | rgb;
            }
        }
    }

    private static void applyWallShadowsOnFloor(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz, int px0, int py0
    ) {
        boolean n = InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, cx, cz - 1);
        boolean w = InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, cx - 1, cz);
        boolean s = InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, cx, cz + 1);
        boolean e = InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, cx + 1, cz);
        boolean nw = InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, cx - 1, cz - 1);
        if (!n && !w && !s && !e && !nw) {
            return;
        }
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                float shade = 0.0f;
                if (n && ly < 7) {
                    shade = Math.max(shade, WALL_NW_EDGE[ly]);
                }
                if (w && lx < 7) {
                    shade = Math.max(shade, WALL_NW_EDGE[lx]);
                }
                if (s && ly >= BLOCK_PX - 4) {
                    shade = Math.max(shade, WALL_SE_EDGE[BLOCK_PX - 1 - ly]);
                }
                if (e && lx >= BLOCK_PX - 4) {
                    shade = Math.max(shade, WALL_SE_EDGE[BLOCK_PX - 1 - lx]);
                }
                if (nw && lx < 6 && ly < 6) {
                    shade = Math.max(shade, WALL_NW_CORNER[ly * 6 + lx]);
                }
                if (shade > 0.005f) {
                    int idx = rowBase + lx;
                    int argb = fb[idx];
                    fb[idx] = (argb & 0xFF000000) | AtlasMath.mixRgb(argb & 0xFFFFFF, SHADOW_COL, shade);
                }
            }
        }
    }

    private static void paintWall(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz,
            InteriorSliceSnapshot.Cell c, int px0, int py0
    ) {
        boolean wN = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz - 1);
        boolean wS = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz + 1);
        boolean wW = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz);
        boolean wE = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz);
        boolean iNW = wN && wW && !InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz - 1);
        boolean iNE = wN && wE && !InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz - 1);
        boolean iSW = wS && wW && !InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz + 1);
        boolean iSE = wS && wE && !InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz + 1);

        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(c.primaryState);
        int fallback = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(c.primaryRgb, 0.78f, 0.72f), 0x261A11, 0.34f);

        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int rgb = sampleHiRgb(tile, lx, ly, c.primaryTintRgb, fallback);
                rgb = AtlasMath.mixRgb(AtlasMath.scaleRgb(rgb, 0.60f), 0x261A11, 0.28f);

                boolean onEdge = (!wN && ly == 0) || (!wS && ly == BLOCK_PX - 1)
                        || (!wW && lx == 0) || (!wE && lx == BLOCK_PX - 1)
                        || (iNW && lx == 0 && ly == 0)
                        || (iNE && lx == BLOCK_PX - 1 && ly == 0)
                        || (iSW && lx == 0 && ly == BLOCK_PX - 1)
                        || (iSE && lx == BLOCK_PX - 1 && ly == BLOCK_PX - 1);

                if (onEdge) {
                    rgb = AtlasMath.mixRgb(rgb, INK, 0.86f);
                } else if ((!wN && ly == 1) || (!wW && lx == 1)) {
                    rgb = AtlasMath.scaleRgb(rgb, 1.24f);
                } else if ((!wS && ly == BLOCK_PX - 2) || (!wE && lx == BLOCK_PX - 2)) {
                    rgb = AtlasMath.mixRgb(rgb, INK, 0.28f);
                }
                fb[rowBase + lx] = 0xFF000000 | rgb;
            }
        }
    }

    private static void paintSillBase(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz,
            InteriorSliceSnapshot.Cell c, int px0, int py0
    ) {
        boolean wN = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz - 1);
        boolean wS = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz + 1);
        boolean wW = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz);
        boolean wE = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz);
        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(c.sillState);
        int fallback = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(c.floorRgb, 0.85f, 0.92f), PARCHMENT_TONE, 0.12f);
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int rgb = AtlasMath.scaleRgb(sampleHiRgb(tile, lx, ly, c.sillTintRgb, fallback), 0.94f);
                if ((!wN && ly == 0) || (!wS && ly == BLOCK_PX - 1)
                        || (!wW && lx == 0) || (!wE && lx == BLOCK_PX - 1)) {
                    rgb = AtlasMath.mixRgb(rgb, INK, 0.45f);
                }
                fb[rowBase + lx] = 0xFF000000 | rgb;
            }
        }
    }

    private static void paintBookshelf(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz,
            InteriorSliceSnapshot.Cell c, int px0, int py0
    ) {
        boolean wN = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz - 1);
        boolean wS = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz + 1);
        boolean wW = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz);
        boolean wE = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz);
        boolean openN = InteriorSliceSnapshot.isOpenRoom(cells, spanX, spanZ, cx, cz - 1);
        boolean openS = InteriorSliceSnapshot.isOpenRoom(cells, spanX, spanZ, cx, cz + 1);
        boolean openW = InteriorSliceSnapshot.isOpenRoom(cells, spanX, spanZ, cx - 1, cz);
        boolean openE = InteriorSliceSnapshot.isOpenRoom(cells, spanX, spanZ, cx + 1, cz);

        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(c.primaryState);
        int fallback = 0x8C663B;
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int rgb = fallback;
                if (tile != null && tile.hasAnyPixel()) {
                    int p = tile.hiPixel(lx, ly);
                    if (((p >>> 24) & 0xFF) > 0) {
                        rgb = p & 0xFFFFFF;
                    }
                }
                rgb = AtlasMath.mixRgb(AtlasMath.scaleRgb(rgb, 0.88f), 0x3D2918, 0.12f);

                boolean onEdge = (!wN && ly == 0) || (!wS && ly == BLOCK_PX - 1)
                        || (!wW && lx == 0) || (!wE && lx == BLOCK_PX - 1);
                if (onEdge) {
                    rgb = AtlasMath.mixRgb(rgb, INK, 0.72f);
                } else if (openN && ly >= 1 && ly <= 3 && lx >= 2 && lx <= BLOCK_PX - 3) {
                    rgb = sampleBookSpine(c.wx, c.wz, 0, lx, ly == 3);
                } else if (openS && ly >= BLOCK_PX - 4 && ly <= BLOCK_PX - 2 && lx >= 2 && lx <= BLOCK_PX - 3) {
                    rgb = sampleBookSpine(c.wx, c.wz, 1, lx, ly == BLOCK_PX - 4);
                } else if (openW && lx >= 1 && lx <= 3 && ly >= 2 && ly <= BLOCK_PX - 3) {
                    rgb = sampleBookSpine(c.wx, c.wz, 2, ly, lx == 3);
                } else if (openE && lx >= BLOCK_PX - 4 && lx <= BLOCK_PX - 2 && ly >= 2 && ly <= BLOCK_PX - 3) {
                    rgb = sampleBookSpine(c.wx, c.wz, 3, ly, lx == BLOCK_PX - 4);
                } else if ((!wN && ly == 1) || (!wW && lx == 1)) {
                    rgb = AtlasMath.scaleRgb(rgb, 1.09f);
                }
                fb[rowBase + lx] = 0xFF000000 | rgb;
            }
        }
    }

    private static int sampleBookSpine(int wx, int wz, int side, int coord, boolean shelfLip) {
        if (shelfLip) {
            return 0x4E331B;
        }
        int bookIdx = (coord - 2) / 3;
        int inBook = (coord - 2) % 3;
        int col = BOOK_SPINES[Math.floorMod(wx * 7 + wz * 13 + side * 5 + bookIdx * 3, BOOK_SPINES.length)];
        return inBook == 2 ? AtlasMath.scaleRgb(col, 0.76f) : (inBook == 0 ? AtlasMath.scaleRgb(col, 1.06f) : col);
    }

    private static void paintWindow(
            int[] fb,
            int texW,
            int texH,
            InteriorSliceSnapshot.Cell[] cells,
            int spanX,
            int spanZ,
            int cx,
            int cz,
            InteriorSliceSnapshot.Cell c,
            int px0,
            int py0,
            float[] shadowBuf
    ) {
        if (c.primaryState.is(Blocks.IRON_BARS)) {
            paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.primaryState, 0xFFFFFF,
                    px0, py0, true, 0.20f, false, shadowBuf);
            return;
        }

        boolean wN = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz - 1);
        boolean wS = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz + 1);
        boolean wW = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz);
        boolean wE = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz);
        boolean horizontal = (wW || wE) && !(wN || wS);
        boolean vertical = (wN || wS) && !(wW || wE);
        boolean corner = (wW || wE) && (wN || wS);
        if (!horizontal && !vertical && !corner) {
            horizontal = true;
        }

        int glassCol = c.primaryRgb;
        int glassHi = AtlasMath.mixRgb(glassCol, 0xFAF6EE, 0.45f);
        int frameCol = INK_SOFT;
        int mullionCol = AtlasMath.mixRgb(glassCol, frameCol, 0.35f);
        boolean adjStartWindow = !corner && (horizontal
                ? InteriorSliceSnapshot.isKind(cells, spanX, spanZ, cx - 1, cz, InteriorSliceSnapshot.KIND_WINDOW)
                : InteriorSliceSnapshot.isKind(cells, spanX, spanZ, cx, cz - 1, InteriorSliceSnapshot.KIND_WINDOW));
        boolean adjEndWindow = !corner && (horizontal
                ? InteriorSliceSnapshot.isKind(cells, spanX, spanZ, cx + 1, cz, InteriorSliceSnapshot.KIND_WINDOW)
                : InteriorSliceSnapshot.isKind(cells, spanX, spanZ, cx, cz + 1, InteriorSliceSnapshot.KIND_WINDOW));

        for (int ly = 0; ly < BLOCK_PX; ly++) {
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                if (corner) {
                    boolean inH = ((wW && lx <= 10) || (wE && lx >= 5)) && ly >= 5 && ly <= 10;
                    boolean inV = ((wN && ly <= 10) || (wS && ly >= 5)) && lx >= 5 && lx <= 10;
                    if (!inH && !inV) {
                        continue;
                    }
                    boolean edge = (inH && !inV && (ly == 5 || ly == 10))
                            || (inV && !inH && (lx == 5 || lx == 10));
                    if (edge) {
                        blendPixel(fb, texW, px0 + lx, py0 + ly, frameCol, 0.72f);
                    } else {
                        int col = ((lx + ly) % 6 == 0) ? glassHi : glassCol;
                        blendPixel(fb, texW, px0 + lx, py0 + ly, col, 0.62f);
                    }
                    continue;
                }
                int u = horizontal ? lx : ly;
                int v = horizontal ? ly : lx;
                if (v < 5 || v > 10) {
                    continue;
                }
                boolean frame = v == 5 || v == 10
                        || (u == 0 && !adjStartWindow)
                        || (u == BLOCK_PX - 1 && !adjEndWindow);
                if (frame) {
                    blendPixel(fb, texW, px0 + lx, py0 + ly, frameCol, 0.74f);
                } else if (v == 7 || v == 8) {
                    boolean mullion = (u == 7 || u == 8);
                    int col = mullion ? mullionCol : (((u + v) % 7 == 0) ? glassHi : glassCol);
                    blendPixel(fb, texW, px0 + lx, py0 + ly, col, mullion ? 0.56f : 0.64f);
                } else {
                    blendPixel(fb, texW, px0 + lx, py0 + ly, glassHi, 0.50f);
                }
            }
        }
    }

    private static void paintDoor(
            int[] fb,
            int texW,
            int texH,
            InteriorSliceSnapshot.Cell[] cells,
            int spanX,
            int spanZ,
            int cx,
            int cz,
            InteriorSliceSnapshot.Cell c,
            int px0,
            int py0,
            float[] shadowBuf
    ) {
        paintFixtureTile(fb, texW, texH, cells, spanX, spanZ, c.primaryState, 0xFFFFFF,
                px0, py0, true, 0.34f, true, shadowBuf);
        boolean wN = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz - 1);
        boolean wS = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx, cz + 1);
        boolean wW = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx - 1, cz);
        boolean wE = InteriorSliceSnapshot.isWallLike(cells, spanX, spanZ, cx + 1, cz);
        int jamb = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(c.primaryRgb, 0.85f, 0.78f), 0x4A311C, 0.55f);
        if (wW) {
            paintJamb(fb, texW, px0, py0 + 6, 2, 4, jamb);
        }
        if (wE) {
            paintJamb(fb, texW, px0 + BLOCK_PX - 2, py0 + 6, 2, 4, jamb);
        }
        if (wN) {
            paintJamb(fb, texW, px0 + 6, py0, 4, 2, jamb);
        }
        if (wS) {
            paintJamb(fb, texW, px0 + 6, py0 + BLOCK_PX - 2, 4, 2, jamb);
        }
    }

    private static void paintJamb(int[] fb, int texW, int x0, int y0, int w, int h, int jambRgb) {
        for (int dy = 0; dy < h; dy++) {
            int rowBase = (y0 + dy) * texW + x0;
            for (int dx = 0; dx < w; dx++) {
                boolean border = dx == 0 || dx == w - 1 || dy == 0 || dy == h - 1;
                fb[rowBase + dx] = 0xFF000000 | (border ? INK : jambRgb);
            }
        }
    }

    private static void applyNicheShade(int[] fb, int texW, int px0, int py0) {
        for (int ly = 0; ly < BLOCK_PX; ly++) {
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int edge = Math.min(Math.min(lx, BLOCK_PX - 1 - lx), Math.min(ly, BLOCK_PX - 1 - ly));
                float shade = edge == 0 ? 0.34f : (edge == 1 ? 0.18f : 0.08f);
                blendPixel(fb, texW, px0 + lx, py0 + ly, SHADOW_COL, shade);
            }
        }
    }

    private static void paintCarpet(
            int[] fb, int texW, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ, int cx, int cz,
            InteriorSliceSnapshot.Cell c, int px0, int py0
    ) {
        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(c.carpetState);
        if (tile == null || !tile.hasAnyPixel()) {
            return;
        }
        boolean cN = InteriorSliceSnapshot.hasCarpet(cells, spanX, spanZ, cx, cz - 1);
        boolean cS = InteriorSliceSnapshot.hasCarpet(cells, spanX, spanZ, cx, cz + 1);
        boolean cW = InteriorSliceSnapshot.hasCarpet(cells, spanX, spanZ, cx - 1, cz);
        boolean cE = InteriorSliceSnapshot.hasCarpet(cells, spanX, spanZ, cx + 1, cz);

        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int p = tile.hiPixel(lx, ly);
                int a = (p >>> 24) & 0xFF;
                if (a < 16) {
                    continue;
                }
                int rgb = p & 0xFFFFFF;
                if (tile.isHiTinted(lx, ly)) {
                    rgb = AtlasMath.multiplyRgb(rgb, c.floorTintRgb);
                }
                boolean edgeN = !cN && ly == 0;
                boolean edgeS = !cS && ly == BLOCK_PX - 1;
                boolean edgeW = !cW && lx == 0;
                boolean edgeE = !cE && lx == BLOCK_PX - 1;
                if (edgeS || edgeE) {
                    rgb = AtlasMath.mixRgb(rgb, INK_SOFT, 0.28f);
                } else if (edgeN || edgeW) {
                    rgb = AtlasMath.scaleRgb(rgb, 1.04f);
                }
                if (a >= 250) {
                    fb[rowBase + lx] = 0xFF000000 | rgb;
                } else {
                    blendPixel(fb, texW, px0 + lx, py0 + ly, rgb, a / 255.0f);
                }
            }
        }
    }

    private static void paintFixtureTile(
            int[] fb,
            int texW,
            int texH,
            InteriorSliceSnapshot.Cell[] cells,
            int spanX,
            int spanZ,
            BlockState state,
            int tintRgb,
            int px0,
            int py0,
            boolean castShadow,
            float shadowStrength,
            boolean outlineEdges,
            float[] shadowBuf
    ) {
        BlockTopTextureCache.BakedBlockTile tile = BlockTopTextureCache.getOrBake(state);
        if (tile == null || !tile.hasAnyPixel()) {
            return;
        }

        if (castShadow && shadowStrength > 0.005f) {
            int sStride = BLOCK_PX + 2;
            Arrays.fill(shadowBuf, 0.0f);
            for (int ly = 0; ly < BLOCK_PX; ly++) {
                for (int lx = 0; lx < BLOCK_PX; lx++) {
                    int a = (tile.hiPixel(lx, ly) >>> 24) & 0xFF;
                    if (a < 48) {
                        continue;
                    }
                    float af = (a / 255.0f) * shadowStrength;
                    int idx11 = (ly + 1) * sStride + (lx + 1);
                    if (af > shadowBuf[idx11]) shadowBuf[idx11] = af;
                    int idx10 = ly * sStride + (lx + 1);
                    float side = af * 0.50f;
                    if (side > shadowBuf[idx10]) shadowBuf[idx10] = side;
                    int idx01 = (ly + 1) * sStride + lx;
                    if (side > shadowBuf[idx01]) shadowBuf[idx01] = side;
                    int idx22 = (ly + 2) * sStride + (lx + 2);
                    float far = af * 0.40f;
                    if (far > shadowBuf[idx22]) shadowBuf[idx22] = far;
                }
            }
            for (int sy = 0; sy < sStride; sy++) {
                int gy = py0 + sy;
                if (gy < 0 || gy >= texH) {
                    continue;
                }
                int row = sy * sStride;
                int targetCz = gy / BLOCK_PX;
                for (int sx = 0; sx < sStride; sx++) {
                    float sh = shadowBuf[row + sx];
                    if (sh <= 0.005f) {
                        continue;
                    }
                    int gx = px0 + sx;
                    if (gx < 0 || gx >= texW) {
                        continue;
                    }
                    int targetCx = gx / BLOCK_PX;
                    if (InteriorSliceSnapshot.isTallBlocker(cells, spanX, spanZ, targetCx, targetCz)
                            || !InteriorSliceSnapshot.isMasked(cells, spanX, spanZ, targetCx, targetCz)) {
                        continue;
                    }
                    blendPixel(fb, texW, gx, gy, SHADOW_COL, sh);
                }
            }
        }

        for (int ly = 0; ly < BLOCK_PX; ly++) {
            int rowBase = (py0 + ly) * texW + px0;
            for (int lx = 0; lx < BLOCK_PX; lx++) {
                int p = tile.hiPixel(lx, ly);
                int a = (p >>> 24) & 0xFF;
                if (a < 16) {
                    continue;
                }
                int rgb = p & 0xFFFFFF;
                if (tile.isHiTinted(lx, ly)) {
                    rgb = AtlasMath.multiplyRgb(rgb, tintRgb);
                }
                if (outlineEdges && a >= 128) {
                    boolean border = (lx == 0 || ((tile.hiPixel(lx - 1, ly) >>> 24) & 0xFF) < 64)
                            || (lx == BLOCK_PX - 1 || ((tile.hiPixel(lx + 1, ly) >>> 24) & 0xFF) < 64)
                            || (ly == 0 || ((tile.hiPixel(lx, ly - 1) >>> 24) & 0xFF) < 64)
                            || (ly == BLOCK_PX - 1 || ((tile.hiPixel(lx, ly + 1) >>> 24) & 0xFF) < 64);
                    if (border) {
                        rgb = AtlasMath.mixRgb(rgb, INK, 0.56f);
                    }
                }
                if (a >= 250) {
                    fb[rowBase + lx] = 0xFF000000 | rgb;
                } else {
                    blendPixel(fb, texW, px0 + lx, py0 + ly, rgb, a / 255.0f);
                }
            }
        }
    }

    private static void applyRoomLighting(
            int[] fb, int texW, int texH, InteriorSliceSnapshot.Cell[] cells, int spanX, int spanZ
    ) {
        for (int cz = 0; cz < spanZ; cz++) {
            for (int cx = 0; cx < spanX; cx++) {
                InteriorSliceSnapshot.Cell src = cells[cz * spanX + cx];
                if (src.kind == InteriorSliceSnapshot.KIND_NONE || src.lightEmission < 6) {
                    continue;
                }
                float centerX = cx * BLOCK_PX + src.lightOffX;
                float centerY = cz * BLOCK_PX + src.lightOffY;
                float radius = 18.0f + (src.lightEmission - 6) * 2.5f;
                float radiusSq = radius * radius;
                float invRadius = 1.0f / radius;
                int radCeil = Mth.ceil(radius);
                int minPx = Math.max(0, Math.round(centerX) - radCeil);
                int maxPx = Math.min(texW - 1, Math.round(centerX) + radCeil);
                int minPy = Math.max(0, Math.round(centerY) - radCeil);
                int maxPy = Math.min(texH - 1, Math.round(centerY) + radCeil);
                int minCx = minPx / BLOCK_PX;
                int maxCx = maxPx / BLOCK_PX;
                int minCz = minPy / BLOCK_PX;
                int maxCz = maxPy / BLOCK_PX;
                float maxStrength = 0.18f + (src.lightEmission / 15.0f) * 0.16f;
                int lightCol = src.lightColor;

                for (int tCz = minCz; tCz <= maxCz; tCz++) {
                    int py0 = Math.max(minPy, tCz * BLOCK_PX);
                    int py1 = Math.min(maxPy, (tCz + 1) * BLOCK_PX - 1);
                    for (int tCx = minCx; tCx <= maxCx; tCx++) {
                        InteriorSliceSnapshot.Cell target = cells[tCz * spanX + tCx];
                        if (target.kind == InteriorSliceSnapshot.KIND_NONE
                                || target.kind == InteriorSliceSnapshot.KIND_WALL) {
                            continue;
                        }
                        if (!InteriorSliceSnapshot.isLightUnoccluded(cells, spanX, cx, cz, tCx, tCz)) {
                            continue;
                        }
                        int px0 = Math.max(minPx, tCx * BLOCK_PX);
                        int px1 = Math.min(maxPx, (tCx + 1) * BLOCK_PX - 1);
                        for (int py = py0; py <= py1; py++) {
                            float dy = py - centerY;
                            float dySq = dy * dy;
                            if (dySq >= radiusSq) {
                                continue;
                            }
                            for (int px = px0; px <= px1; px++) {
                                float dx = px - centerX;
                                float distSq = dx * dx + dySq;
                                if (distSq >= radiusSq) {
                                    continue;
                                }
                                int q = Math.min(QUARTER_SQRT_LUT.length - 1, (int) (distSq * 4.0f + 0.5f));
                                float dist = QUARTER_SQRT_LUT[q];
                                float t = 1.0f - dist * invRadius;
                                float strength = t * t * maxStrength;
                                if (dist < 5.0f) {
                                    float core = 1.0f - dist * 0.2f;
                                    strength = Math.min(0.45f, strength + core * core * 0.18f);
                                }
                                if (strength > 0.005f) {
                                    blendPixel(fb, texW, px, py, lightCol, strength);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void finishCartographyStyle(int[] fb, int w, int h, int oWx, int oWz) {
        int grainBaseX = (oWx & 31) * 8;
        int grainBaseZ = (oWz & 31) * 8;
        for (int y = 0; y < h; y++) {
            float gy = grainBaseZ + y * 0.5f;
            int rowBase = y * w;
            for (int x = 0; x < w; x++) {
                int idx = rowBase + x;
                int argb = fb[idx];
                int a = (argb >>> 24) & 0xFF;
                if (a == 0) {
                    continue;
                }
                int rgb = argb & 0xFFFFFF;
                if (a >= 200) {
                    rgb = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(rgb, 0.91f, 0.98f), PARCHMENT_TONE, 0.08f);
                    float gx = grainBaseX + x * 0.5f;
                    float grain = samplePaperGrainSmooth(gx, gy) * 0.18f;
                    rgb = AtlasMath.scaleRgb(rgb, 1.0f + grain);
                    a = Math.min(a, 246);
                }
                fb[idx] = (a << 24) | rgb;
            }
        }
    }

    private static float samplePaperGrainSmooth(float gx, float gy) {
        int x0 = Mth.floor(gx);
        int y0 = Mth.floor(gy);
        float fx = gx - x0;
        float fy = gy - y0;
        float g00 = ParchmentTexture.samplePaperGrain(x0, y0);
        float g10 = ParchmentTexture.samplePaperGrain(x0 + 1, y0);
        float g01 = ParchmentTexture.samplePaperGrain(x0, y0 + 1);
        float g11 = ParchmentTexture.samplePaperGrain(x0 + 1, y0 + 1);
        return Mth.lerp(fy, Mth.lerp(fx, g00, g10), Mth.lerp(fx, g01, g11));
    }

    private static void blendPixel(int[] fb, int texW, int x, int y, int overlayRgb, float alpha) {
        int idx = y * texW + x;
        int argb = fb[idx];
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) {
            int outA = Mth.clamp(Math.round(alpha * 255.0f), 0, 255);
            fb[idx] = (outA << 24) | overlayRgb;
            return;
        }
        fb[idx] = (a << 24) | AtlasMath.mixRgb(argb & 0xFFFFFF, overlayRgb, alpha);
    }
}
