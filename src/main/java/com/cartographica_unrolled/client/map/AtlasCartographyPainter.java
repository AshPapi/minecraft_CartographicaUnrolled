package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class AtlasCartographyPainter {
    @FunctionalInterface
    public interface ChunkLookup {
        @Nullable AtlasChunkTerrain get(int cx, int cz);
    }

    public static final int PARCHMENT_TRANSPARENT = AtlasMath.PARCHMENT_TRANSPARENT;
    private static final int INK_DARK = 0x2F1E12;
    private static final int INK_STRUCT = 0x27180D;
    private static final int INK_CLIFF = 0x382515;
    private static final int GRID_SIZE = CartographyCornerField.GRID_SIZE;
    private static final int GRID_MARGIN = CartographyCornerField.GRID_MARGIN;

    private static final float[] LIGHT_CURVE_LUT = new float[257];

    static {
        for (int i = 0; i <= 256; i++) {
            LIGHT_CURVE_LUT[i] = (float) Math.pow(i / 256.0, 1.30);
        }
    }

    private AtlasCartographyPainter() {
    }

    public static void paintChunkTileToArrays(
            int[] colorOut,
            int @Nullable [] lightOut,
            int cx,
            int cz,
            @Nullable AtlasChunkTerrain center,
            ChunkLookup chunkLookup
    ) {
        if (center == null) {
            Arrays.fill(colorOut, PARCHMENT_TRANSPARENT);
            if (lightOut != null) {
                Arrays.fill(lightOut, 0);
            }
            return;
        }

        CartographyCornerField field = CartographyCornerField.get();
        try {
            boolean anyUnexplored = field.populate(cx, cz, center, lightOut != null, chunkLookup);
            paintTexturedChunkTile(colorOut, lightOut, cx, cz, center, field, anyUnexplored);
        } finally {
            field.clearPaletteRefs();
        }
    }

    private static void paintTexturedChunkTile(
            int[] colorOut,
            int @Nullable [] lightOut,
            int cx,
            int cz,
            AtlasChunkTerrain center,
            CartographyCornerField field,
            boolean anyUnexplored
    ) {
        long[] grid = field.grid;
        int[] flags = field.flags;
        float[] cornerLight = field.cornerLight;
        float[] cornerExploredDistance = field.cornerExploredDistance;
        int tileStride = AtlasRegionCache.TILE_PX;
        BlockTopTextureCache.BakedBlockTile waterTile = BlockTopTextureCache.getWaterTile();
        BlockTopTextureCache.BakedBlockTile lavaTile = BlockTopTextureCache.getLavaTile();
        short[] baseIndices = center.baseBlockIndices();
        short[] overlayIndices = center.overlayBlockIndices();
        BlockTopTextureCache.@Nullable BakedBlockTile[] paletteTiles = field.paletteTiles;
        int palLen = field.paletteLen;

        for (int bz = 0; bz < 16; bz++) {
            for (int bx = 0; bx < 16; bx++) {
                int gIdx = (bz + GRID_MARGIN) * GRID_SIZE + bx + GRID_MARGIN;
                long currentCol = grid[gIdx];
                if (currentCol == AtlasChunkTerrain.EMPTY_COLUMN) {
                    for (int sy = 0; sy < 8; sy++) {
                        int row = ((bz << 3) + sy) * tileStride + (bx << 3);
                        for (int sx = 0; sx < 8; sx++) {
                            colorOut[row + sx] = PARCHMENT_TRANSPARENT;
                            if (lightOut != null) {
                                lightOut[row + sx] = 0;
                            }
                        }
                    }
                    continue;
                }

                int cornerIndex = bz * 17 + bx;
                int cf = flags[gIdx];
                int currentY = AtlasChunkTerrain.unpackSurfaceY(currentCol);
                boolean chosenWater = (cf & AtlasChunkTerrain.FLAG_WATER) != 0;
                boolean chosenSnow = (cf & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0;
                boolean chosenCanopy = (cf & AtlasChunkTerrain.FLAG_CANOPY) != 0;
                boolean chosenLava = (cf & AtlasChunkTerrain.FLAG_LAVA) != 0;
                boolean structure = (cf & AtlasChunkTerrain.FLAG_STRUCTURE) != 0;
                int rawRgb = AtlasChunkTerrain.unpackRgb(currentCol);
                int fallbackPigment = computePigment(currentCol);

                int colIdx = (bz << 4) | bx;
                int bIdx = baseIndices != null ? (baseIndices[colIdx] & 0xFFFF) : -1;
                BlockTopTextureCache.BakedBlockTile baseTile = (bIdx >= 0 && bIdx < palLen) ? paletteTiles[bIdx] : null;
                int oIdx = overlayIndices != null ? (overlayIndices[colIdx] & 0xFFFF) : -1;
                BlockTopTextureCache.BakedBlockTile overlayTile = (oIdx >= 0 && oIdx < palLen) ? paletteTiles[oIdx] : null;

                long colW = grid[gIdx - 1];
                long colE = grid[gIdx + 1];
                long colN = grid[gIdx - GRID_SIZE];
                long colS = grid[gIdx + GRID_SIZE];
                long colNW = grid[gIdx - GRID_SIZE - 1];
                int cfW = flags[gIdx - 1];
                int cfE = flags[gIdx + 1];
                int cfN = flags[gIdx - GRID_SIZE];
                int cfS = flags[gIdx + GRID_SIZE];
                int cfNW = flags[gIdx - GRID_SIZE - 1];

                int yW = colW != AtlasChunkTerrain.EMPTY_COLUMN ? AtlasChunkTerrain.unpackSurfaceY(colW) : currentY;
                int yE = colE != AtlasChunkTerrain.EMPTY_COLUMN ? AtlasChunkTerrain.unpackSurfaceY(colE) : currentY;
                int yN = colN != AtlasChunkTerrain.EMPTY_COLUMN ? AtlasChunkTerrain.unpackSurfaceY(colN) : currentY;
                int yS = colS != AtlasChunkTerrain.EMPTY_COLUMN ? AtlasChunkTerrain.unpackSurfaceY(colS) : currentY;
                int yNW = colNW != AtlasChunkTerrain.EMPTY_COLUMN ? AtlasChunkTerrain.unpackSurfaceY(colNW) : currentY;

                int ledgeYN = (!chosenCanopy && (cfN & AtlasChunkTerrain.FLAG_CANOPY) != 0) ? field.groundHeights[gIdx - GRID_SIZE] : yN;
                int ledgeYW = (!chosenCanopy && (cfW & AtlasChunkTerrain.FLAG_CANOPY) != 0) ? field.groundHeights[gIdx - 1] : yW;
                int ledgeYNW = (!chosenCanopy && (cfNW & AtlasChunkTerrain.FLAG_CANOPY) != 0) ? field.groundHeights[gIdx - GRID_SIZE - 1] : yNW;

                int higherN = colN != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, ledgeYN - currentY) : 0;
                int higherW = colW != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, ledgeYW - currentY) : 0;
                int higherNW = colNW != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, ledgeYNW - currentY) : 0;
                int dropE = colE != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, currentY - yE) : 0;
                int dropS = colS != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, currentY - yS) : 0;
                int dropN = colN != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, currentY - yN) : 0;
                int dropW = colW != AtlasChunkTerrain.EMPTY_COLUMN ? Math.max(0, currentY - yW) : 0;

                boolean isSoloCanopy = chosenCanopy
                        && field.cornerCanopyW[cornerIndex] <= 0.25f
                        && field.cornerCanopyW[cornerIndex + 1] <= 0.25f
                        && field.cornerCanopyW[cornerIndex + 17] <= 0.25f
                        && field.cornerCanopyW[cornerIndex + 18] <= 0.25f;

                boolean emptyEdgeW = colW == AtlasChunkTerrain.EMPTY_COLUMN;
                boolean emptyEdgeE = colE == AtlasChunkTerrain.EMPTY_COLUMN;
                boolean emptyEdgeN = colN == AtlasChunkTerrain.EMPTY_COLUMN;
                boolean emptyEdgeS = colS == AtlasChunkTerrain.EMPTY_COLUMN;

                boolean nearCanopyShadowN = !chosenCanopy && !structure && !chosenLava && isHigherCanopy(colN, currentY);
                boolean nearCanopyShadowW = !chosenCanopy && !structure && !chosenLava && isHigherCanopy(colW, currentY);
                boolean nearCanopyShadowNW = !chosenCanopy && !structure && !chosenLava && isHigherCanopy(colNW, currentY);
                boolean farCanopyShadow = !chosenCanopy && !structure && !chosenLava
                        && (isHigherCanopy(grid[gIdx - 2], currentY)
                        || isHigherCanopy(grid[gIdx - 2 * GRID_SIZE], currentY)
                        || isHigherCanopy(grid[gIdx - 2 * GRID_SIZE - 2], currentY));

                int structTallN = !structure && !chosenCanopy && !chosenLava && colN != AtlasChunkTerrain.EMPTY_COLUMN
                        && (cfN & AtlasChunkTerrain.FLAG_STRUCTURE) != 0 ? Math.max(0, yN - currentY) : 0;
                int structTallW = !structure && !chosenCanopy && !chosenLava && colW != AtlasChunkTerrain.EMPTY_COLUMN
                        && (cfW & AtlasChunkTerrain.FLAG_STRUCTURE) != 0 ? Math.max(0, yW - currentY) : 0;
                int structTallNW = !structure && !chosenCanopy && !chosenLava && colNW != AtlasChunkTerrain.EMPTY_COLUMN
                        && (cfNW & AtlasChunkTerrain.FLAG_STRUCTURE) != 0 ? Math.max(0, yNW - currentY) : 0;

                int colDetail = AtlasChunkTerrain.unpackDetailType(currentCol);
                int colAccent = AtlasChunkTerrain.unpackDetailAccent(currentCol);

                for (int sy = 0; sy < 8; sy++) {
                    int lpy = (bz << 3) + sy;
                    int wpy = cz * tileStride + lpy;
                    double worldZ = cz * 16.0 + bz + (sy + 0.5) * 0.125;
                    int rowOffset = lpy * tileStride;

                    for (int sx = 0; sx < 8; sx++) {
                        int lpx = (bx << 3) + sx;
                        int wpx = cx * tileStride + lpx;
                        double worldX = cx * 16.0 + bx + (sx + 0.5) * 0.125;
                        int pixelIndex = rowOffset + lpx;

                        int rgb;
                        float canopySnowCover = 0.0f;
                        float crownSun = chosenCanopy ? interpolateCorners8(field.cornerCanopySun, cornerIndex, sx, sy) : 0.0f;
                        float canopyValley = chosenCanopy ? interpolateCorners8(field.cornerCanopyValley, cornerIndex, sx, sy) : 0.0f;
                        if (chosenWater) {
                            float depth = AtlasChunkTerrain.unpackWaterDepth(currentCol);
                            if (!chosenSnow) {
                                float presence = interpolateCorners8(field.cornerWaterPresence, cornerIndex, sx, sy);
                                depth = presence > 0.0f
                                        ? interpolateCorners8(field.cornerWaterDepth, cornerIndex, sx, sy) / presence
                                        : depth;
                                float floor = presence > 0.0f
                                        ? interpolateCorners8(field.cornerWaterFloor, cornerIndex, sx, sy) / presence
                                        : AtlasChunkTerrain.unpackFloorHint(currentCol);
                                int waterRgb = rawRgb;
                                if (presence > 0.0f) {
                                    int wr = Mth.clamp(Math.round(interpolateCorners8(field.cornerWaterR, cornerIndex, sx, sy) / presence), 0, 255);
                                    int wg = Mth.clamp(Math.round(interpolateCorners8(field.cornerWaterG, cornerIndex, sx, sy) / presence), 0, 255);
                                    int wb = Mth.clamp(Math.round(interpolateCorners8(field.cornerWaterB, cornerIndex, sx, sy) / presence), 0, 255);
                                    waterRgb = (wr << 16) | (wg << 8) | wb;
                                }
                                rgb = waterPigment(waterRgb, depth, floor);

                                if (waterTile != null && waterTile.hasAnyPixel()) {
                                    int wp = waterTile.pixel(sx, sy);
                                    int wl = (((wp >>> 16) & 0xFF) * 77 + ((wp >>> 8) & 0xFF) * 150 + (wp & 0xFF) * 29) >> 8;
                                    float texMod = 1.0f + ((wl - 155) / 255.0f) * 0.30f;
                                    rgb = AtlasMath.scaleRgb(rgb, Mth.clamp(texMod, 0.86f, 1.14f));
                                }

                                float shoreDist = interpolateCorners8(field.cornerShoreDist, cornerIndex, sx, sy);
                                if (shoreDist > 0.25f && shoreDist < 5.4f) {
                                    float waveWarp = smoothNoise(worldX * 0.45, worldZ * 0.45, 311) * 0.36f;
                                    float d = shoreDist + waveWarp;
                                    float r1 = Math.max(0.0f, 1.0f - Math.abs(d - 1.15f) / 0.28f);
                                    float r2 = Math.max(0.0f, 1.0f - Math.abs(d - 2.35f) / 0.30f) * 0.72f;
                                    float r3 = Math.max(0.0f, 1.0f - Math.abs(d - 3.65f) / 0.34f) * 0.45f;
                                    float ripple = Math.max(r1, Math.max(r2, r3));
                                    if (ripple > 0.02f && smoothNoise(worldX * 0.85, worldZ * 0.85, 887) > -0.32f) {
                                        rgb = AtlasMath.mixRgb(rgb, 0x263C47, ripple * 0.26f);
                                    }
                                }
                                if (depth > 6.5f) {
                                    float swell = (float) Math.sin(worldZ * 1.45 + smoothNoise(worldX * 0.22, worldZ * 0.22, 419) * 2.2);
                                    if (swell > 0.80f && smoothNoise(worldX * 0.38, worldZ * 0.38, 601) > 0.05f) {
                                        float strength = Mth.clamp((depth - 6.5f) / 14.0f, 0.0f, 1.0f) * ((swell - 0.80f) / 0.20f);
                                        rgb = AtlasMath.mixRgb(rgb, 0x1A2C38, strength * 0.20f);
                                    }
                                }
                            } else {
                                float floor = AtlasChunkTerrain.unpackFloorHint(currentCol);
                                int underIce = waterPigment(rawRgb, depth, floor);
                                if (baseTile != null && baseTile.hasAnyPixel()) {
                                    int ip = baseTile.pixel(sx, sy);
                                    int ia = (ip >>> 24) & 0xFF;
                                    int iRgb = ip & 0xFFFFFF;
                                    rgb = ia >= 250 ? iRgb : AtlasMath.mixRgb(underIce, iRgb, Math.max(0.45f, ia / 255.0f));
                                } else {
                                    rgb = AtlasMath.mixRgb(underIce, 0xDCE7EA, 0.72f);
                                }
                            }
                        } else if (chosenLava) {
                            BlockTopTextureCache.BakedBlockTile lt = (baseTile != null && baseTile.hasAnyPixel()) ? baseTile : lavaTile;
                            rgb = (lt != null && lt.hasAnyPixel()) ? (lt.pixel(sx, sy) & 0xFFFFFF) : fallbackPigment;
                            rgb = AtlasMath.mixRgb(rgb, 0xF5C46E, 0.08f);
                        } else {
                            if (baseTile != null && baseTile.hasAnyPixel()) {
                                int bp = baseTile.pixel(sx, sy);
                                int ba = (bp >>> 24) & 0xFF;
                                if (ba > 0) {
                                    int texRgb = bp & 0xFFFFFF;
                                    if (baseTile.isTinted(sx, sy)) {
                                        int tint = resolveDynamicTint(
                                                rawRgb, baseTile.defaultTintRgb(), chosenCanopy,
                                                field, cornerIndex, sx, sy
                                        );
                                        texRgb = AtlasMath.multiplyRgb(texRgb, tint);
                                    }
                                    rgb = ba >= 250 ? texRgb : AtlasMath.mixRgb(fallbackPigment, texRgb, ba / 255.0f);
                                } else {
                                    rgb = fallbackPigment;
                                }
                            } else {
                                rgb = fallbackPigment;
                            }
                            if (chosenCanopy) {
                                float cSnow = interpolateCorners8(field.cornerCanopySnow, cornerIndex, sx, sy);
                                if (chosenSnow || cSnow > 0.50f) {
                                    float cRim = interpolateCorners8(field.cornerCanopyRim, cornerIndex, sx, sy);
                                    if (chosenSnow && isSoloCanopy) {
                                        float dxC = (sx + 0.5f) * 0.125f - 0.5f;
                                        float dyC = (sy + 0.5f) * 0.125f - 0.5f;
                                        cRim = Math.max(0.0f, (1.0f - (dxC * dxC + dyC * dyC) * 4.2f) * 0.68f);
                                    }
                                    int lr = (rgb >>> 16) & 0xFF;
                                    int lg = (rgb >>> 8) & 0xFF;
                                    int lb = rgb & 0xFF;
                                    float leafLuma = lr * 0.299f + lg * 0.587f + lb * 0.114f;
                                    float n1 = smoothNoise(worldX * 1.45, worldZ * 1.45, 1373);
                                    float n2 = smoothNoise(worldX * 3.00, worldZ * 3.00, 2909);
                                    float boughNoise = n1 * 0.095f + n2 * 0.070f;
                                    float leafRelief = Mth.clamp((leafLuma - 62.0f) * 0.0016f, -0.035f, 0.035f);
                                    float rimThresh = 0.37f - crownSun * 0.16f;
                                    float rimMask = AtlasMath.smoothstep((cRim + boughNoise + leafRelief - rimThresh) / 0.13f);
                                    float snowPresence = chosenSnow ? 1.0f : AtlasMath.smoothstep((cSnow - 0.52f) / 0.24f) * 0.68f;
                                    canopySnowCover = rimMask * snowPresence;
                                    if (canopySnowCover > 0.01f) {
                                        float snowGrain = n2 * 0.022f + leafRelief * 0.40f;
                                        int snowCapRgb = AtlasMath.scaleRgb(0xEEF4F4, 1.0f + snowGrain);
                                        int frostedLeaf = AtlasMath.mixRgb(rgb, 0x6A8E86, canopySnowCover * (1.0f - canopySnowCover) * 1.2f);
                                        rgb = AtlasMath.mixRgb(frostedLeaf, snowCapRgb, canopySnowCover * 0.90f);
                                    }
                                }
                            }
                        }

                        if (overlayTile != null && overlayTile.hasAnyPixel()) {
                            int op = overlayTile.pixel(sx, sy);
                            int oa = (op >>> 24) & 0xFF;
                            if (oa >= 16) {
                                int oRgb = op & 0xFFFFFF;
                                if (overlayTile.isTinted(sx, sy)) {
                                    int oTint = resolveOverlayTint(
                                            rawRgb, overlayTile.defaultTintRgb(), chosenWater, chosenCanopy, structure,
                                            field, cornerIndex, sx, sy
                                    );
                                    oRgb = AtlasMath.multiplyRgb(oRgb, oTint);
                                }
                                rgb = oa >= 250 ? oRgb : AtlasMath.mixRgb(rgb, oRgb, oa / 255.0f);
                            }
                        }

                        if (!chosenLava) {
                            if (!chosenWater) {
                                rgb = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(rgb, 0.91f, 0.98f), AtlasMath.PARCHMENT_WARM, 0.08f);
                                float sun = interpolateCorners8(field.cornerSun, cornerIndex, sx, sy);
                                float steep = interpolateCorners8(field.cornerSteep, cornerIndex, sx, sy);
                                float skyShade = interpolateCorners8(field.cornerSkyShade, cornerIndex, sx, sy);
                                if (structure) {
                                    rgb = AtlasMath.scaleRgb(rgb, 1.0f + sun * 0.22f);
                                    if (sun < 0.0f) {
                                        rgb = AtlasMath.mixRgb(rgb, 0x362619, -sun * 0.14f);
                                    }
                                    rgb = AtlasMath.mixRgb(rgb, 0x362619, steep * 0.25f);
                                } else if (chosenCanopy) {
                                    float effSun = Mth.clamp(sun * 0.35f + crownSun * 0.95f, -0.26f, 0.20f);
                                    if (canopySnowCover > 0.01f) {
                                        int greenShaded = AtlasMath.scaleRgb(rgb, 1.0f + effSun * 0.30f);
                                        if (effSun < 0.0f) {
                                            greenShaded = AtlasMath.mixRgb(greenShaded, 0x22331C, -effSun * 0.26f);
                                        }
                                        greenShaded = AtlasMath.mixRgb(greenShaded, 0x22331C, steep * 0.28f);

                                        int snowShaded = rgb;
                                        if (effSun < 0.0f) {
                                            snowShaded = AtlasMath.mixRgb(snowShaded, 0x8EA2B0, -effSun * 1.25f);
                                        } else {
                                            snowShaded = AtlasMath.scaleRgb(snowShaded, 1.0f + effSun * 0.24f);
                                        }
                                        snowShaded = AtlasMath.mixRgb(snowShaded, 0x869AA8, Math.max(steep * 0.42f, canopyValley * 0.28f));
                                        rgb = AtlasMath.mixRgb(greenShaded, snowShaded, canopySnowCover);
                                    } else {
                                        rgb = AtlasMath.scaleRgb(rgb, 1.0f + effSun * 0.34f);
                                        if (effSun < 0.0f) {
                                            rgb = AtlasMath.mixRgb(rgb, 0x23331A, -effSun * 0.26f);
                                        }
                                        rgb = AtlasMath.mixRgb(rgb, 0x1D2D17, Math.max(steep * 0.32f, canopyValley * 0.22f));
                                    }
                                } else if (chosenSnow) {
                                    if (sun < 0.0f) {
                                        rgb = AtlasMath.mixRgb(rgb, 0xADB4BC, -sun * 0.85f);
                                    } else {
                                        rgb = AtlasMath.scaleRgb(rgb, 1.0f + sun * 0.20f);
                                    }
                                    rgb = AtlasMath.mixRgb(rgb, 0xA4ACB5, steep * 0.65f);
                                } else {
                                    rgb = AtlasMath.scaleRgb(rgb, 1.0f + sun * 0.42f);
                                    if (sun < 0.0f) {
                                        rgb = AtlasMath.mixRgb(rgb, 0x362619, -sun * 0.18f);
                                    }
                                    rgb = AtlasMath.mixRgb(rgb, 0x362619, steep * 0.55f);

                                    float groundH = interpolateCorners8(field.cornerGroundH, cornerIndex, sx, sy);
                                    float contourPhase = Mth.positiveModulo(groundH, 8.0f);
                                    float contourDist = Math.min(contourPhase, 8.0f - contourPhase);
                                    if (contourDist < 0.20f && steep > 0.02f && steep < 0.22f) {
                                        float majorPhase = Mth.positiveModulo(groundH, 24.0f);
                                        float major = majorPhase < 0.4f || majorPhase > 23.6f ? 1.3f : 1.0f;
                                        float cStrength = (1.0f - contourDist / 0.20f) * 0.075f * major;
                                        rgb = AtlasMath.mixRgb(rgb, 0x4A3522, cStrength);
                                    }
                                }
                                if (skyShade > 0.25f) {
                                    rgb = AtlasMath.mixRgb(rgb, 0x2B2118, Mth.clamp((skyShade - 0.25f) * 0.36f, 0.0f, 0.24f));
                                }
                            }

                            float ledgeMask = chosenCanopy
                                    ? (chosenSnow ? 0.0f : (1.0f - Mth.clamp(canopySnowCover * 1.5f, 0.0f, 1.0f)) * 0.42f)
                                    : (chosenSnow ? 0.72f : 1.0f);
                            if (ledgeMask > 0.01f) {
                                int ledgeShadeRgb = chosenCanopy ? 0x1D2B14 : (chosenSnow ? 0x7A8694 : 0x261A10);
                                if (higherN > 0 && sy <= 1) {
                                    float sStrength = Mth.clamp(0.16f + (higherN - 1) * 0.04f, 0.16f, 0.34f) * (sy == 0 ? 1.0f : 0.46f);
                                    rgb = AtlasMath.mixRgb(rgb, ledgeShadeRgb, sStrength * ledgeMask);
                                }
                                if (higherW > 0 && sx <= 1) {
                                    float sStrength = Mth.clamp(0.16f + (higherW - 1) * 0.04f, 0.16f, 0.34f) * (sx == 0 ? 1.0f : 0.46f);
                                    rgb = AtlasMath.mixRgb(rgb, ledgeShadeRgb, sStrength * ledgeMask);
                                }
                                if (higherNW > 0 && higherN == 0 && higherW == 0 && sx == 0 && sy == 0) {
                                    rgb = AtlasMath.mixRgb(rgb, ledgeShadeRgb, Mth.clamp(0.14f + (higherNW - 1) * 0.03f, 0.14f, 0.26f) * ledgeMask);
                                }
                            }
                            if (!chosenWater && (!chosenCanopy || (!chosenSnow && canopySnowCover < 0.05f))
                                    && ((dropN > 0 && sy == 0) || (dropW > 0 && sx == 0))) {
                                rgb = AtlasMath.scaleRgb(rgb, chosenCanopy ? 1.03f : 1.06f);
                            }
                            if (!chosenWater && !chosenCanopy && ((dropE >= 3 && sx == 7) || (dropS >= 3 && sy == 7))) {
                                rgb = AtlasMath.mixRgb(rgb, chosenSnow ? 0x6E7A87 : INK_CLIFF, chosenSnow ? 0.24f : 0.30f);
                            } else if (structure && ((dropE >= 1 && sx == 7) || (dropS >= 1 && sy == 7))) {
                                rgb = AtlasMath.mixRgb(rgb, INK_STRUCT, 0.22f);
                            }

                            if (!chosenCanopy && !structure) {
                                float cFalloff = 0.0f;
                                if (nearCanopyShadowN && sy <= 5) {
                                    cFalloff = Math.max(cFalloff, (5.5f - sy) / 5.5f);
                                }
                                if (nearCanopyShadowW && sx <= 5) {
                                    cFalloff = Math.max(cFalloff, (5.5f - sx) / 5.5f);
                                }
                                if (nearCanopyShadowNW && sx <= 5 && sy <= 5) {
                                    cFalloff = Math.max(cFalloff, (5.5f - Math.max(sx, sy)) / 5.5f * 0.85f);
                                }
                                if (farCanopyShadow && sx <= 2 && sy <= 2) {
                                    cFalloff = Math.max(cFalloff, (3.0f - Math.max(sx, sy)) / 3.0f * 0.45f);
                                }
                                if (cFalloff > 0.02f) {
                                    float shadowNoise = 1.0f + smoothNoise(worldX * 1.6, worldZ * 1.6, 1997) * 0.18f;
                                    float cStrength = Mth.clamp(cFalloff * shadowNoise, 0.0f, 1.0f);
                                    int shadowTint = chosenSnow ? 0x8C9BAA : 0x223019;
                                    float maxAlpha = chosenWater ? 0.13f : (chosenSnow ? 0.24f : 0.19f);
                                    rgb = AtlasMath.mixRgb(rgb, shadowTint, cStrength * maxAlpha);
                                }
                                int castH = 0;
                                if (structTallN >= 2 && sy <= 3) {
                                    castH = Math.max(castH, structTallN);
                                }
                                if (structTallW >= 2 && sx <= 3) {
                                    castH = Math.max(castH, structTallW);
                                }
                                if (structTallNW >= 2 && sx <= 3 && sy <= 3) {
                                    castH = Math.max(castH, structTallNW);
                                }
                                if (castH >= 2) {
                                    float sCast = Mth.clamp(0.12f + (castH - 2) * 0.025f, 0.12f, 0.24f);
                                    rgb = AtlasMath.mixRgb(rgb, 0x261A10, sCast * (chosenWater ? 0.65f : 1.0f));
                                }
                            }
                        }

                        rgb = AtlasMath.scaleRgb(rgb, 1.0f + ParchmentTexture.samplePaperGrain(wpx, wpy) * 0.22f);

                        float edgeAlpha = 1.0f;
                        if (anyUnexplored) {
                            float distance = interpolateCorners8(cornerExploredDistance, cornerIndex, sx, sy);
                            float noise = interpolateCorners8(field.cornerFrontierNoise, cornerIndex, sx, sy);
                            distance += noise * Mth.clamp(distance / 3.0f, 0.0f, 1.0f);
                            edgeAlpha = AtlasMath.smoothstep((distance - 1.2f) / 8.3f);
                        }
                        if ((emptyEdgeW && sx == 0) || (emptyEdgeE && sx == 7)
                                || (emptyEdgeN && sy == 0) || (emptyEdgeS && sy == 7)) {
                            rgb = AtlasMath.mixRgb(rgb, INK_DARK, 0.30f);
                        }
                        if (edgeAlpha < 0.85f) {
                            rgb = AtlasMath.mixRgb(rgb, 0x8A6B45, (0.85f - edgeAlpha) * 0.22f);
                            if (edgeAlpha > 0.18f && edgeAlpha < 0.42f) {
                                float rim = 1.0f - Math.abs(edgeAlpha - 0.30f) / 0.12f;
                                rgb = AtlasMath.mixRgb(rgb, 0x5C4328, rim * 0.16f);
                            }
                        }

                        int alpha = Mth.clamp(Math.round(edgeAlpha * 246.0f), 0, 246);
                        colorOut[pixelIndex] = alpha == 0 ? PARCHMENT_TRANSPARENT : ((alpha << 24) | rgb);

                        if (lightOut != null) {
                            float lightLevel = interpolateCorners8(cornerLight, cornerIndex, sx, sy);
                            lightOut[pixelIndex] = (lightLevel > 0.0f && alpha > 0)
                                    ? computePixelLightArgb(rgb, lightLevel, alpha, colDetail, colAccent, chosenLava)
                                    : 0;
                        }
                    }
                }
            }
        }
    }

    private static int computePixelLightArgb(
            int rgb,
            float lightLevel,
            int alpha,
            int colDetail,
            int colAccent,
            boolean chosenLava
    ) {
        int warm;
        if (colDetail == AtlasChunkTerrain.DETAIL_OUTDOOR_NETHER_PORTAL) {
            warm = AtlasMath.mixRgb(rgb, 0xC864FF, 0.72f);
        } else if ((colDetail == AtlasChunkTerrain.DETAIL_OUTDOOR_CAMPFIRE
                || colDetail == AtlasChunkTerrain.DETAIL_OUTDOOR_LANTERN_POST)
                && (colAccent == AtlasChunkTerrain.ACCENT_ORCHID_CYAN
                        || (colAccent >= AtlasChunkTerrain.ACCENT_SOUL_WALL_TORCH_N
                                && colAccent <= AtlasChunkTerrain.ACCENT_SOUL_WALL_TORCH_E))) {
            warm = AtlasMath.mixRgb(rgb, 0x6CE4F2, 0.65f);
        } else {
            int baseWarm = AtlasMath.mixRgb(rgb, 0xFFC266, 0.38f);
            warm = chosenLava ? AtlasMath.mixRgb(baseWarm, 0xFF9E3D, 0.45f) : baseWarm;
        }
        int lutIdx = Mth.clamp(Math.round((lightLevel / 15.0f) * 256.0f), 0, 256);
        int lightAlpha = Mth.clamp(Math.round(LIGHT_CURVE_LUT[lutIdx] * alpha), 0, 255);
        return lightAlpha == 0 ? 0 : ((lightAlpha << 24) | warm);
    }

    private static int resolveDynamicTint(
            int rawRgb,
            int defaultTintRgb,
            boolean canopy,
            CartographyCornerField field,
            int cornerIndex,
            int sx,
            int sy
    ) {
        int baseTint = rawRgb != 0 ? rawRgb : defaultTintRgb;
        if (canopy) {
            float cw = interpolateCorners8(field.cornerCanopyW, cornerIndex, sx, sy);
            if (cw > 0.01f) {
                int cr = Mth.clamp(Math.round(interpolateCorners8(field.cornerCanopyR, cornerIndex, sx, sy) / cw), 0, 255);
                int cg = Mth.clamp(Math.round(interpolateCorners8(field.cornerCanopyG, cornerIndex, sx, sy) / cw), 0, 255);
                int cb = Mth.clamp(Math.round(interpolateCorners8(field.cornerCanopyB, cornerIndex, sx, sy) / cw), 0, 255);
                int smooth = (cr << 16) | (cg << 8) | cb;
                if (AtlasMath.rgbManhattan(baseTint, smooth) < 56) {
                    return AtlasMath.mixRgb(baseTint, smooth, 0.45f);
                }
            }
            return baseTint;
        }
        float gw = interpolateCorners8(field.cornerGroundW, cornerIndex, sx, sy);
        if (gw > 0.01f) {
            int gr = Mth.clamp(Math.round(interpolateCorners8(field.cornerGroundR, cornerIndex, sx, sy) / gw), 0, 255);
            int gg = Mth.clamp(Math.round(interpolateCorners8(field.cornerGroundG, cornerIndex, sx, sy) / gw), 0, 255);
            int gb = Mth.clamp(Math.round(interpolateCorners8(field.cornerGroundB, cornerIndex, sx, sy) / gw), 0, 255);
            int smooth = (gr << 16) | (gg << 8) | gb;
            if (AtlasMath.rgbManhattan(baseTint, smooth) < 48) {
                return AtlasMath.mixRgb(baseTint, smooth, 0.40f);
            }
        }
        return baseTint;
    }

    private static int resolveOverlayTint(
            int rawRgb,
            int defaultTintRgb,
            boolean water,
            boolean canopy,
            boolean structure,
            CartographyCornerField field,
            int cornerIndex,
            int sx,
            int sy
    ) {
        if (!water && !structure && rawRgb != 0) {
            int g = (rawRgb >>> 8) & 0xFF;
            int r = (rawRgb >>> 16) & 0xFF;
            int b = rawRgb & 0xFF;
            if (g >= r - 12 && g > b + 12) {
                return resolveDynamicTint(rawRgb, defaultTintRgb, canopy, field, cornerIndex, sx, sy);
            }
        }
        return defaultTintRgb;
    }

    private static float interpolateCorners8(float[] values, int index, int sx, int sy) {
        float x = (sx + 0.5f) * 0.125f;
        float y = (sy + 0.5f) * 0.125f;
        float north = values[index] * (1.0f - x) + values[index + 1] * x;
        float south = values[index + 17] * (1.0f - x) + values[index + 18] * x;
        return north * (1.0f - y) + south * y;
    }

    private static boolean isHigherCanopy(long column, int currentY) {
        return (AtlasChunkTerrain.unpackFlags(column) & AtlasChunkTerrain.FLAG_CANOPY) != 0
                && AtlasChunkTerrain.unpackSurfaceY(column) > currentY;
    }

    private static int waterPigment(int rgb, float depth, float floor) {
        int shallow = AtlasMath.mixRgb(0x64888F, 0x9BB59E, Mth.clamp((floor - 8.0f) / 4.0f, 0.0f, 1.0f));
        int palette;
        if (depth <= 5.0f) {
            palette = AtlasMath.mixRgb(shallow, 0x567B86, Mth.clamp((depth - 1.0f) / 4.0f, 0.0f, 1.0f));
        } else if (depth <= 12.0f) {
            palette = AtlasMath.mixRgb(0x567B86, 0x315361, (depth - 5.0f) / 7.0f);
        } else {
            palette = AtlasMath.mixRgb(0x315361, 0x243A48, Mth.clamp((depth - 12.0f) / 24.0f, 0.0f, 1.0f));
        }
        return AtlasMath.mixRgb(palette, AtlasMath.adjustSaturation(rgb, 0.45f), 0.20f);
    }

    static int computePigment(long col) {
        int rgb = AtlasChunkTerrain.unpackRgb(col);
        int flags = AtlasChunkTerrain.unpackFlags(col);
        if (rgb == 0 && (flags & AtlasChunkTerrain.FLAG_STRUCTURE) == 0) {
            rgb = (flags & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0 ? 0xEAE0C8 : 0x7D8C49;
        }
        if ((flags & AtlasChunkTerrain.FLAG_STRUCTURE) != 0) {
            return rgb;
        }
        if ((flags & AtlasChunkTerrain.FLAG_WATER) != 0) {
            if ((flags & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0) {
                return 0xA8BEBE;
            }
            int depth = AtlasChunkTerrain.unpackWaterDepth(col);
            int palette;
            if (depth <= 5) {
                palette = AtlasMath.mixRgb(0x95AEA0, 0x567B86, Mth.clamp((depth - 1) / 4.0f, 0.0f, 1.0f));
            } else if (depth <= 12) {
                palette = AtlasMath.mixRgb(0x567B86, 0x315361, (depth - 5) / 7.0f);
            } else {
                palette = AtlasMath.mixRgb(0x315361, 0x243A48, Mth.clamp((depth - 12) / 24.0f, 0.0f, 1.0f));
            }
            return AtlasMath.mixRgb(palette, AtlasMath.adjustSaturation(rgb, 0.45f), 0.20f);
        }
        if ((flags & AtlasChunkTerrain.FLAG_LAVA) != 0) {
            return rgb;
        }
        if ((flags & AtlasChunkTerrain.FLAG_SNOW_ICE) != 0 && (flags & AtlasChunkTerrain.FLAG_CANOPY) == 0) {
            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;
            return b > r && g > r ? 0xC8D3CC : 0xEAE0C8;
        }
        if ((flags & AtlasChunkTerrain.FLAG_CANOPY) != 0) {
            int canopyKind = AtlasChunkTerrain.unpackCanopyKind(col);
            if (canopyKind == AtlasChunkTerrain.CANOPY_CHERRY
                    || canopyKind == AtlasChunkTerrain.CANOPY_MUSHROOM_RED
                    || canopyKind == AtlasChunkTerrain.CANOPY_MUSHROOM_BROWN
                    || canopyKind == AtlasChunkTerrain.CANOPY_PALE) {
                return AtlasMath.mixRgb(AtlasMath.harmonizeRgb(rgb, 1.02f, 0.94f), 0xE4D2AD, 0.06f);
            }
            int r = (rgb >> 16) & 255;
            int g = (rgb >> 8) & 255;
            int b = rgb & 255;
            float greenDom = Mth.clamp((g - Math.max(r, b) + 28.0f) / 56.0f, 0.0f, 1.0f);
            int toned = AtlasMath.harmonizeRgb(rgb, 0.98f, 0.92f);
            int forestBlend = AtlasMath.mixRgb(toned, 0x4A6B32, 0.24f * greenDom);
            return AtlasMath.mixRgb(forestBlend, 0xE4D2AD, 0.05f);
        }
        int r = (rgb >> 16) & 255;
        int g = (rgb >> 8) & 255;
        int b = rgb & 255;
        int chroma = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
        if ((flags & (AtlasChunkTerrain.FLAG_STRUCTURE | AtlasChunkTerrain.FLAG_CROP_FLORA)) == 0 && chroma <= 18) {
            float rockWeight = Mth.clamp((18.0f - chroma) / 10.0f, 0.0f, 1.0f);
            int rockTint = AtlasMath.mixRgb(rgb, 0x7A756B, 0.50f);
            int baseTint = AtlasMath.mixRgb(AtlasMath.harmonizeRgb(rgb, 1.02f, 0.95f), 0xE4D2AD, 0.05f);
            return AtlasMath.mixRgb(baseTint, rockTint, rockWeight);
        }
        float grassDom = Mth.clamp((g - Math.max(r, b) + 22.0f) / 44.0f, 0.0f, 1.0f);
        int grassHarmonized = AtlasMath.mixRgb(rgb, 0x869447, 0.22f * grassDom);
        return AtlasMath.mixRgb(AtlasMath.harmonizeRgb(grassHarmonized, 0.98f, 0.94f), 0xE4D2AD, 0.06f);
    }

    static float smoothNoise(double x, double z, int seed) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        float fx = (float) (x - ix);
        float fz = (float) (z - iz);
        fx = fx * fx * (3.0f - 2.0f * fx);
        fz = fz * fz * (3.0f - 2.0f * fz);
        float n00 = (hash2d(ix, iz, seed) & 65535) / 32767.5f - 1.0f;
        float n10 = (hash2d(ix + 1, iz, seed) & 65535) / 32767.5f - 1.0f;
        float n01 = (hash2d(ix, iz + 1, seed) & 65535) / 32767.5f - 1.0f;
        float n11 = (hash2d(ix + 1, iz + 1, seed) & 65535) / 32767.5f - 1.0f;
        return (n00 + (n10 - n00) * fx) * (1.0f - fz) + (n01 + (n11 - n01) * fx) * fz;
    }

    private static int hash2d(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }
}
