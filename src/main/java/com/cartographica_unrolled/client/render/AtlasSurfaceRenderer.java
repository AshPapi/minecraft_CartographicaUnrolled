package com.cartographica_unrolled.client.render;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.atmosphere.AtlasAtmosphere;
import com.cartographica_unrolled.client.atmosphere.AtlasLighting;
import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.client.map.AtlasInteriorOverlay;
import com.cartographica_unrolled.client.map.AtlasRegion;
import com.cartographica_unrolled.client.map.AtlasRegionCache;
import com.cartographica_unrolled.client.map.AtlasRouteManager;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.client.ui.AtlasEditorState;
import com.cartographica_unrolled.client.ui.AtlasSidebar;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.cartographica_unrolled.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AtlasSurfaceRenderer {
    public static final Identifier UNROLLED_MODEL_ID =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "unrolled_atlas");

    public static final float MODEL_HALF_W = 0.734375f;
    public static final float MODEL_HALF_H = 0.484375f;
    public static final float MAP_HALF_W = 0.64375f;
    public static final float MAP_HALF_H = 0.39375f;
    public static final float WORLD_BLOCKS_ACROSS = 384.0f;

    private static final AtlasSheetGeometry.ClipScratch CLIP_SCRATCH = new AtlasSheetGeometry.ClipScratch();
    private static final List<MapStroke> SCRATCH_VISIBLE_STROKES = new ArrayList<>(64);
    private static final List<MapMarker> SCRATCH_VISIBLE_MARKERS = new ArrayList<>(32);
    private static float[] scratchStrokePx = new float[64];
    private static float[] scratchStrokePy = new float[64];

    private static @Nullable ItemStack cachedRolledStack;
    private static @Nullable ItemStack cachedUnrolledStack;

    private AtlasSurfaceRenderer() {
    }

    private static ItemStack getDefaultRolledStack() {
        if (cachedRolledStack == null) {
            cachedRolledStack = ModItems.ROLLED_ATLAS.toStack();
        }
        return cachedRolledStack;
    }

    private static ItemStack getUnrolledStack() {
        if (cachedUnrolledStack == null) {
            ItemStack stack = ModItems.ROLLED_ATLAS.toStack();
            stack.set(DataComponents.ITEM_MODEL, UNROLLED_MODEL_ID);
            cachedUnrolledStack = stack;
        }
        return cachedUnrolledStack;
    }

    public static void extractRolledModel(
            ItemStackRenderState outState,
            ItemStack equippedStack,
            @Nullable Level level,
            @Nullable LivingEntity entity,
            int seed
    ) {
        ItemStack stack = equippedStack.isEmpty() ? getDefaultRolledStack() : equippedStack;
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                outState, stack, ItemDisplayContext.NONE, level, entity, seed
        );
    }

    public static void extractUnrolledModel(
            ItemStackRenderState outState,
            @Nullable Level level,
            @Nullable LivingEntity entity,
            int seed
    ) {
        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                outState, getUnrolledStack(), ItemDisplayContext.NONE, level, entity, seed + 31
        );
    }

    public static void submitUnrolledWithMap(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            ItemStackRenderState unrolledState,
            ItemStackRenderState rolledState,
            int light,
            float unrollFactor,
            float anchorSign,
            double worldCenterX,
            double worldCenterZ,
            float playerYaw,
            boolean renderEdgeRoll,
            boolean showAnnotations
    ) {
        submitUnrolledWithMap(poseStack, collector, unrolledState, rolledState, light, unrollFactor,
                anchorSign, worldCenterX, worldCenterZ, playerYaw, renderEdgeRoll, showAnnotations,
                1.0f, WORLD_BLOCKS_ACROSS, worldCenterX, worldCenterZ);
    }

    public static void submitUnrolledWithMap(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            ItemStackRenderState unrolledState,
            ItemStackRenderState rolledState,
            int light,
            float unrollFactor,
            float anchorSign,
            double worldCenterX,
            double worldCenterZ,
            float playerYaw,
            boolean renderEdgeRoll,
            boolean showAnnotations,
            float verticalScale,
            float worldBlocksAcross,
            double playerWorldX,
            double playerWorldZ
    ) {
        float u = Mth.clamp(unrollFactor, 0.0f, 1.0f);
        float scaleX = Mth.lerp(u, 0.07f, 1.0f);
        float anchorX = anchorSign * 0.68f;

        if (!unrolledState.isEmpty()) {
            poseStack.pushPose();
            poseStack.translate(anchorX, 0.0f, 0.0f);
            poseStack.scale(scaleX, 1.0f, 1.0f);
            poseStack.translate(-anchorX, 0.0f, 0.0f);
            unrolledState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }

        float leftOuter = anchorX + (-MODEL_HALF_W - anchorX) * scaleX;
        float rightOuter = anchorX + (MODEL_HALF_W - anchorX) * scaleX;
        float leatherRim = (MODEL_HALF_W - MAP_HALF_W) * scaleX;
        float sheetMinX = Math.max(-MAP_HALF_W, leftOuter + leatherRim);
        float sheetMaxX = Math.min(MAP_HALF_W, rightOuter - leatherRim);

        if (sheetMaxX - sheetMinX > 0.025f) {
            Identifier parchmentTex = ParchmentTexture.get();
            float pu0 = (sheetMinX + MAP_HALF_W) / (2.0f * MAP_HALF_W);
            float pu1 = (sheetMaxX + MAP_HALF_W) / (2.0f * MAP_HALF_W);
            collector.order(0).submitCustomGeometry(poseStack, RenderTypes.entityCutout(parchmentTex, false), (pose, buffer) ->
                    AtlasSheetGeometry.emitQuad(buffer, pose, sheetMinX, -MAP_HALF_H, sheetMaxX, MAP_HALF_H, 0.0082f,
                            pu0, 1.0f, pu1, 0.0f, AtlasLighting.tint(0xFFFFFFFF, 0xFF343C4E), light));

            Identifier whiteTex = ParchmentTexture.getWhite();
            float borderT = 0.011f;
            collector.order(1).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(whiteTex), (pose, buffer) -> {
                int rimColor = 0xAA3E2A17;
                AtlasSheetGeometry.emitQuad(buffer, pose, sheetMinX, MAP_HALF_H - borderT, sheetMaxX, MAP_HALF_H, 0.0086f, 0.0f, 1.0f, 1.0f, 0.0f, rimColor, light);
                AtlasSheetGeometry.emitQuad(buffer, pose, sheetMinX, -MAP_HALF_H, sheetMaxX, -MAP_HALF_H + borderT, 0.0086f, 0.0f, 1.0f, 1.0f, 0.0f, rimColor, light);
                AtlasSheetGeometry.emitQuad(buffer, pose, sheetMinX, -MAP_HALF_H, sheetMinX + borderT, MAP_HALF_H, 0.0086f, 0.0f, 1.0f, 1.0f, 0.0f, rimColor, light);
                AtlasSheetGeometry.emitQuad(buffer, pose, sheetMaxX - borderT, -MAP_HALF_H, sheetMaxX, MAP_HALF_H, 0.0086f, 0.0f, 1.0f, 1.0f, 0.0f, rimColor, light);
            });

            float effectiveBlocksAcross = Math.max(16.0f, worldBlocksAcross);
            submitExploredRegions(poseStack, collector, light, sheetMinX, sheetMaxX, worldCenterX, worldCenterZ, verticalScale, effectiveBlocksAcross);
            submitAtmosphereSmoke(poseStack, collector, light, sheetMinX, sheetMaxX, worldCenterX, worldCenterZ, verticalScale, effectiveBlocksAcross);
            if (showAnnotations) {
                submitAnnotations(poseStack, collector, whiteTex, light, sheetMinX, sheetMaxX, worldCenterX, worldCenterZ, verticalScale, effectiveBlocksAcross, playerWorldX, playerWorldZ);
                submitLiveOverlays(poseStack, collector, light, sheetMinX, sheetMaxX, worldCenterX, worldCenterZ, verticalScale, effectiveBlocksAcross);
            }

            submitCompassArrow(poseStack, collector, light, sheetMinX, sheetMaxX, worldCenterX, worldCenterZ, verticalScale, effectiveBlocksAcross, playerWorldX, playerWorldZ, playerYaw);
            submitParchmentWeather(poseStack, collector, whiteTex, light, sheetMinX, sheetMaxX);
        }

        if (renderEdgeRoll && u < 0.96f && !rolledState.isEmpty()) {
            float fade = Mth.clamp((0.96f - u) / 0.14f, 0.0f, 1.0f);
            float radiusScale = Mth.lerp(u, 0.82f, 0.26f) * fade;
            if (radiusScale > 0.03f) {
                if (Math.abs(anchorSign) <= 0.01f) {
                    float symRadius = radiusScale * 0.78f;
                    poseStack.pushPose();
                    poseStack.translate(rightOuter, 0.0f, 0.038f * symRadius + 0.008f);
                    poseStack.scale(symRadius, 1.0f, symRadius);
                    poseStack.rotateDegrees(Axis.YP, -u * 480.0f);
                    rolledState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
                    poseStack.popPose();

                    poseStack.pushPose();
                    poseStack.translate(leftOuter, 0.0f, 0.038f * symRadius + 0.008f);
                    poseStack.scale(symRadius, 1.0f, symRadius);
                    poseStack.rotateDegrees(Axis.YP, u * 480.0f);
                    rolledState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
                    poseStack.popPose();
                } else {
                    float rollX = anchorSign < 0.0f ? rightOuter : leftOuter;
                    float spinSign = anchorSign < 0.0f ? -1.0f : 1.0f;
                    poseStack.pushPose();
                    poseStack.translate(rollX, 0.0f, 0.038f * radiusScale + 0.008f);
                    poseStack.scale(radiusScale, 1.0f, radiusScale);
                    poseStack.rotateDegrees(Axis.YP, spinSign * u * 520.0f);
                    rolledState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
                    poseStack.popPose();
                }
            }
        }
    }

    private static void submitExploredRegions(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float clipMinX,
            float clipMaxX,
            double worldCenterX,
            double worldCenterZ,
            float verticalScale,
            float worldBlocksAcross
    ) {
        int regionCount = AtlasRegionCache.getRegionCount();
        if (regionCount == 0 && !AtlasInteriorOverlay.isActive()) {
            return;
        }
        float unitsPerBlock = (2.0f * MAP_HALF_W) / worldBlocksAcross;
        float verticalUnitsPerBlock = unitsPerBlock * verticalScale;
        int regionBlocks = AtlasRegionCache.REGION_CHUNKS * 16;
        boolean needHd = worldBlocksAcross < 1400.0f;
        int regionTint = AtlasLighting.regionTint();
        float night = AtlasLighting.getNightFactor();
        int lightTint = (Math.round(night * 95.0f) << 24) | 0xFFB84D;

        if (regionCount > 0) {
            int minRx = Math.floorDiv(Mth.floor(worldCenterX + clipMinX / unitsPerBlock), regionBlocks);
            int maxRx = Math.floorDiv(Mth.floor(worldCenterX + clipMaxX / unitsPerBlock), regionBlocks);
            int minRz = Math.floorDiv(Mth.floor(worldCenterZ - MAP_HALF_H / verticalUnitsPerBlock), regionBlocks);
            int maxRz = Math.floorDiv(Mth.floor(worldCenterZ + MAP_HALF_H / verticalUnitsPerBlock), regionBlocks);
            if (AtlasOverlayMath.shouldUseBoundedRegionGrid(regionCount, minRx, maxRx, minRz, maxRz)) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    for (int rx = minRx; rx <= maxRx; rx++) {
                        AtlasRegion region = AtlasRegionCache.getRegion(rx, rz);
                        if (region != null) {
                            submitSingleRegion(poseStack, collector, region, regionBlocks, unitsPerBlock, verticalUnitsPerBlock,
                                    worldCenterX, worldCenterZ, clipMinX, clipMaxX, needHd, regionTint, night, lightTint, light);
                        }
                    }
                }
            } else {
                for (AtlasRegion region : AtlasRegionCache.getRegions().values()) {
                    submitSingleRegion(poseStack, collector, region, regionBlocks, unitsPerBlock, verticalUnitsPerBlock,
                            worldCenterX, worldCenterZ, clipMinX, clipMaxX, needHd, regionTint, night, lightTint, light);
                }
            }
        }

        if (AtlasInteriorOverlay.isActive()) {
            double iwx0 = AtlasInteriorOverlay.getOriginWorldX();
            double iwz0 = AtlasInteriorOverlay.getOriginWorldZ();
            double iwx1 = iwx0 + AtlasInteriorOverlay.getWorldSpanX();
            double iwz1 = iwz0 + AtlasInteriorOverlay.getWorldSpanZ();

            float mx0 = (float) ((iwx0 - worldCenterX) * unitsPerBlock);
            float mx1 = (float) ((iwx1 - worldCenterX) * unitsPerBlock);
            float myTop = (float) (-(iwz0 - worldCenterZ) * verticalUnitsPerBlock);
            float myBottom = (float) (-(iwz1 - worldCenterZ) * verticalUnitsPerBlock);

            if (mx1 > clipMinX && mx0 < clipMaxX && myTop > -MAP_HALF_H && myBottom < MAP_HALF_H) {
                float cx0 = Math.max(mx0, clipMinX);
                float cx1 = Math.min(mx1, clipMaxX);
                float cy0 = Math.max(myBottom, -MAP_HALF_H);
                float cy1 = Math.min(myTop, MAP_HALF_H);
                float spanX = mx1 - mx0;
                float spanY = myTop - myBottom;
                if (spanX > 1e-5f && spanY > 1e-5f) {
                    float u0 = (cx0 - mx0) / spanX;
                    float u1 = (cx1 - mx0) / spanX;
                    float v0 = (myTop - cy1) / spanY;
                    float v1 = (myTop - cy0) / spanY;
                    collector.order(2).submitCustomGeometry(
                            poseStack,
                            AtlasRenderPipelines.atlasMapLayer(AtlasInteriorOverlay.getTextureId()),
                            (pose, buffer) -> AtlasSheetGeometry.emitQuad(buffer, pose, cx0, cy0, cx1, cy1, 0.00915f, u0, v1, u1, v0, regionTint, light)
                    );
                }
            }
        }
    }

    private static void submitSingleRegion(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            AtlasRegion region,
            int regionBlocks,
            float unitsPerBlock,
            float verticalUnitsPerBlock,
            double worldCenterX,
            double worldCenterZ,
            float clipMinX,
            float clipMaxX,
            boolean needHd,
            int regionTint,
            float night,
            int lightTint,
            int light
    ) {
        double wx0 = region.rx * (double) regionBlocks;
        double wz0 = region.rz * (double) regionBlocks;
        float mx0 = (float) ((wx0 - worldCenterX) * unitsPerBlock);
        float mx1 = (float) ((wx0 + regionBlocks - worldCenterX) * unitsPerBlock);
        float myTop = (float) (-(wz0 - worldCenterZ) * verticalUnitsPerBlock);
        float myBottom = (float) (-(wz0 + regionBlocks - worldCenterZ) * verticalUnitsPerBlock);

        if (mx1 <= clipMinX || mx0 >= clipMaxX || myTop <= -MAP_HALF_H || myBottom >= MAP_HALF_H) {
            return;
        }

        float cx0 = Math.max(mx0, clipMinX);
        float cx1 = Math.min(mx1, clipMaxX);
        float cy0 = Math.max(myBottom, -MAP_HALF_H);
        float cy1 = Math.min(myTop, MAP_HALF_H);

        float spanX = mx1 - mx0;
        float spanY = myTop - myBottom;
        if (spanX <= 1e-5f || spanY <= 1e-5f) {
            return;
        }

        float u0 = (cx0 - mx0) / spanX;
        float u1 = (cx1 - mx0) / spanX;
        float v0 = (myTop - cy1) / spanY;
        float v1 = (myTop - cy0) / spanY;

        AtlasRegionCache.RegionTextureRef texRef = region.getRenderTexture(needHd);
        if (texRef == null) {
            return;
        }
        collector.order(2).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(texRef.textureId()), (pose, buffer) ->
                AtlasSheetGeometry.emitQuad(buffer, pose, cx0, cy0, cx1, cy1, 0.0091f, u0, v1, u1, v0, regionTint, light));
        if (night > 0.01f) {
            Identifier lightTexture = region.getLightTexture();
            if (lightTexture != null) {
                collector.order(3).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasLightLayer(lightTexture), (pose, buffer) ->
                        AtlasSheetGeometry.emitQuad(buffer, pose, cx0, cy0, cx1, cy1, 0.0092f, u0, v1, u1, v0, lightTint, 0x00F000F0));
            }
        }
    }

    private static void submitAnnotations(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Identifier whiteTex,
            int light,
            float minX,
            float maxX,
            double worldCenterX,
            double worldCenterZ,
            float verticalScale,
            float worldBlocksAcross,
            double playerWorldX,
            double playerWorldZ
    ) {
        boolean hasStrokes = !AtlasEditorState.getVisibleStrokes().isEmpty();
        boolean hasMarkers = !AtlasEditorState.getVisibleMarkers().isEmpty();
        boolean hasRoute = AtlasRouteManager.hasTarget();
        if (!hasStrokes && !hasMarkers && !hasRoute) {
            return;
        }
        List<AtlasRouteManager.RoutePoint> routePath = hasRoute
                ? AtlasRouteManager.getRenderPath(playerWorldX, playerWorldZ)
                : List.of();
        float unitsPerBlock = (2.0f * MAP_HALF_W) / worldBlocksAcross;
        float verticalUnitsPerBlock = unitsPerBlock * verticalScale;
        double minWorldX = worldCenterX + minX / unitsPerBlock;
        double maxWorldX = worldCenterX + maxX / unitsPerBlock;
        double minWorldZ = worldCenterZ - MAP_HALF_H / verticalUnitsPerBlock;
        double maxWorldZ = worldCenterZ + MAP_HALF_H / verticalUnitsPerBlock;

        if (hasStrokes || routePath.size() >= 2) {
            double strokeWorldPad = Math.max(8.0, 0.020 / Math.min(unitsPerBlock, verticalUnitsPerBlock));
            collector.order(4).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(whiteTex), (pose, buffer) -> {
                if (hasStrokes) {
                    AtlasEditorState.queryVisibleStrokes(
                            minWorldX - strokeWorldPad,
                            minWorldZ - strokeWorldPad,
                            maxWorldX + strokeWorldPad,
                            maxWorldZ + strokeWorldPad,
                            SCRATCH_VISIBLE_STROKES
                    );
                    for (MapStroke stroke : SCRATCH_VISIBLE_STROKES) {
                        List<MapStroke.Point> points = stroke.getPoints();
                        if (points.isEmpty()) {
                            continue;
                        }
                        float halfWidth = Mth.clamp(stroke.getWidth() * unitsPerBlock * 0.5f, 0.0016f, 0.018f);
                        int color = stroke.isDye()
                                ? AtlasLighting.dyeInkColor(stroke.getColor())
                                : (0xFF000000 | stroke.getColor());

                        int ptCount = points.size();
                        if (scratchStrokePx.length < ptCount) {
                            int nextLen = Math.max(ptCount, scratchStrokePx.length * 2);
                            scratchStrokePx = new float[nextLen];
                            scratchStrokePy = new float[nextLen];
                        }
                        float[] px = scratchStrokePx;
                        float[] py = scratchStrokePy;
                        int count = 0;
                        for (MapStroke.Point pt : points) {
                            float x = (float) ((pt.x() - worldCenterX) * unitsPerBlock);
                            float y = (float) (-(pt.z() - worldCenterZ) * verticalUnitsPerBlock);
                            if (count == 0) {
                                px[count] = x;
                                py[count] = y;
                                count++;
                            } else {
                                float dx = x - px[count - 1];
                                float dy = y - py[count - 1];
                                float lenSq = dx * dx + dy * dy;
                                if (Float.isFinite(lenSq) && lenSq >= 1.0e-14f) {
                                    px[count] = x;
                                    py[count] = y;
                                    count++;
                                }
                            }
                        }
                        if (count == 0) {
                            continue;
                        }
                        for (int i = 0; i < count - 1; i++) {
                            float x0 = px[i];
                            float y0 = py[i];
                            float x1 = px[i + 1];
                            float y1 = py[i + 1];
                            if (Math.max(x0, x1) + halfWidth <= minX || Math.min(x0, x1) - halfWidth >= maxX
                                    || Math.max(y0, y1) + halfWidth <= -MAP_HALF_H || Math.min(y0, y1) - halfWidth >= MAP_HALF_H) {
                                continue;
                            }
                            float dx = x1 - x0;
                            float dy = y1 - y0;
                            float length = Mth.sqrt(dx * dx + dy * dy);
                            if (!Float.isFinite(length) || length < 1.0e-7f) {
                                continue;
                            }
                            float nx = -dy * halfWidth / length;
                            float ny = dx * halfWidth / length;
                            AtlasSheetGeometry.emitClippedQuad(buffer, pose,
                                    x0 - nx, y0 - ny,
                                    x1 - nx, y1 - ny,
                                    x1 + nx, y1 + ny,
                                    x0 + nx, y0 + ny,
                                    minX, maxX, -MAP_HALF_H, MAP_HALF_H,
                                    color, light, CLIP_SCRATCH);
                        }
                        for (int i = 0; i < count; i++) {
                            float cx = px[i];
                            float cy = py[i];
                            if (cx + halfWidth <= minX || cx - halfWidth >= maxX
                                    || cy + halfWidth <= -MAP_HALF_H || cy - halfWidth >= MAP_HALF_H) {
                                continue;
                            }
                            if (i > 0 && i < count - 1) {
                                if (halfWidth < 0.0018f || AtlasOverlayMath.shouldSkipInteriorJoint(
                                        cx - px[i - 1], cy - py[i - 1], px[i + 1] - cx, py[i + 1] - cy)) {
                                    continue;
                                }
                            }
                            AtlasSheetGeometry.emitClippedDisc(buffer, pose, cx, cy, halfWidth,
                                    minX, maxX, -MAP_HALF_H, MAP_HALF_H,
                                    color, light, CLIP_SCRATCH);
                        }
                    }
                    SCRATCH_VISIBLE_STROKES.clear();
                }
                if (routePath.size() >= 2) {
                    float dashLen = 1.0f * unitsPerBlock;
                    float gapLen = 1.15f * unitsPerBlock;
                    float period = dashLen + gapLen;
                    float halfW = 0.24f * unitsPerBlock;
                    boolean emitDashCaps = halfW > 0.0018f;
                    int routeColor = AtlasLighting.dyeInkColor(DyeColor.BLACK.getMapColor().col);
                    boolean drawing = true;
                    float remain = dashLen;
                    int emittedDashes = 0;
                    for (int i = 0; i < routePath.size() - 1 && emittedDashes < 2048; i++) {
                        AtlasRouteManager.RoutePoint p0 = routePath.get(i);
                        AtlasRouteManager.RoutePoint p1 = routePath.get(i + 1);
                        float x0 = (float) ((p0.x() - worldCenterX) * unitsPerBlock);
                        float y0 = (float) (-(p0.z() - worldCenterZ) * verticalUnitsPerBlock);
                        float x1 = (float) ((p1.x() - worldCenterX) * unitsPerBlock);
                        float y1 = (float) (-(p1.z() - worldCenterZ) * verticalUnitsPerBlock);
                        float dx = x1 - x0;
                        float dy = y1 - y0;
                        float segLen = Mth.sqrt(dx * dx + dy * dy);
                        if (!Float.isFinite(segLen) || segLen < 1.0e-5f) {
                            continue;
                        }
                        boolean inBounds = Math.max(x0, x1) + halfW > minX && Math.min(x0, x1) - halfW < maxX
                                && Math.max(y0, y1) + halfW > -MAP_HALF_H && Math.min(y0, y1) - halfW < MAP_HALF_H;
                        if (!inBounds) {
                            float nextState = AtlasOverlayMath.advanceOffscreenDashState(
                                    drawing, remain, segLen, dashLen, gapLen, period, 1.0e-5f);
                            drawing = nextState > 0.0f;
                            remain = Math.abs(nextState);
                            continue;
                        }
                        float nx = -dy * halfW / segLen;
                        float ny = dx * halfW / segLen;
                        float pos = 0.0f;
                        while (pos < segLen && emittedDashes < 2048) {
                            float stepLen = Math.min(remain, segLen - pos);
                            float nextPos = pos + stepLen;
                            if (nextPos <= pos) {
                                break;
                            }
                            if (drawing && stepLen > 1.0e-5f) {
                                float t0 = pos / segLen;
                                float t1 = nextPos / segLen;
                                float ax = x0 + dx * t0;
                                float ay = y0 + dy * t0;
                                float bx = x0 + dx * t1;
                                float by = y0 + dy * t1;
                                if (Math.max(ax, bx) + halfW > minX && Math.min(ax, bx) - halfW < maxX
                                        && Math.max(ay, by) + halfW > -MAP_HALF_H && Math.min(ay, by) - halfW < MAP_HALF_H) {
                                    AtlasSheetGeometry.emitClippedQuad(buffer, pose,
                                            ax - nx, ay - ny,
                                            bx - nx, by - ny,
                                            bx + nx, by + ny,
                                            ax + nx, ay + ny,
                                            minX, maxX, -MAP_HALF_H, MAP_HALF_H,
                                            routeColor, light, CLIP_SCRATCH);
                                    if (emitDashCaps) {
                                        AtlasSheetGeometry.emitClippedDisc(buffer, pose, ax, ay, halfW,
                                                minX, maxX, -MAP_HALF_H, MAP_HALF_H,
                                                routeColor, light, CLIP_SCRATCH);
                                        AtlasSheetGeometry.emitClippedDisc(buffer, pose, bx, by, halfW,
                                                minX, maxX, -MAP_HALF_H, MAP_HALF_H,
                                                routeColor, light, CLIP_SCRATCH);
                                    }
                                    emittedDashes++;
                                }
                            }
                            pos = nextPos;
                            remain -= stepLen;
                            if (remain <= 1.0e-5f) {
                                drawing = !drawing;
                                remain = drawing ? dashLen : gapLen;
                            }
                        }
                    }
                }
            });
        }

        if (hasRoute) {
            float tx = (float) ((AtlasRouteManager.getTargetX() - worldCenterX) * unitsPerBlock);
            float ty = (float) (-(AtlasRouteManager.getTargetZ() - worldCenterZ) * verticalUnitsPerBlock);
            float arm = 3.0f * unitsPerBlock;
            if (tx + arm > minX && tx - arm < maxX && ty + arm > -MAP_HALF_H && ty - arm < MAP_HALF_H) {
                float outlineHalf = 0.95f * unitsPerBlock;
                float coreHalf = 0.55f * unitsPerBlock;
                float coreArm = arm * 0.90f;
                int outlineColor = AtlasLighting.tint(0xE6300808, 0xE6180404);
                int crossColor = AtlasLighting.tint(0xFFE02424, 0xFF8C1818);
                collector.order(5).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(whiteTex), (pose, buffer) -> {
                    AtlasSheetGeometry.emitCrossStroke(buffer, pose, tx - arm, ty - arm, tx + arm, ty + arm, outlineHalf, 0.00947f, minX, maxX, -MAP_HALF_H, MAP_HALF_H, outlineColor, light, CLIP_SCRATCH);
                    AtlasSheetGeometry.emitCrossStroke(buffer, pose, tx - arm, ty + arm, tx + arm, ty - arm, outlineHalf, 0.009475f, minX, maxX, -MAP_HALF_H, MAP_HALF_H, outlineColor, light, CLIP_SCRATCH);
                });
                collector.order(6).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(whiteTex), (pose, buffer) -> {
                    AtlasSheetGeometry.emitCrossStroke(buffer, pose, tx - coreArm, ty - coreArm, tx + coreArm, ty + coreArm, coreHalf, 0.00949f, minX, maxX, -MAP_HALF_H, MAP_HALF_H, crossColor, light, CLIP_SCRATCH);
                    AtlasSheetGeometry.emitCrossStroke(buffer, pose, tx - coreArm, ty + coreArm, tx + coreArm, ty - coreArm, coreHalf, 0.009495f, minX, maxX, -MAP_HALF_H, MAP_HALF_H, crossColor, light, CLIP_SCRATCH);
                });
            }
        }

        if (!hasMarkers) {
            return;
        }
        float markerSize = 0.040f;
        float halfW = markerSize * 0.5f;
        float bottomOffset = markerSize * 0.625f;
        float topOffset = markerSize * 0.375f;
        double markerWorldPad = 0.030 / Math.min(unitsPerBlock, verticalUnitsPerBlock);
        if (AtlasSidebar.ensureBannerTextures()) {
            collector.order(5).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(AtlasSidebar.getStampBaseTexture()), (pose, buffer) -> {
                AtlasEditorState.queryVisibleMarkers(minWorldX, minWorldZ, maxWorldX, maxWorldZ, markerWorldPad, SCRATCH_VISIBLE_MARKERS);
                for (MapMarker marker : SCRATCH_VISIBLE_MARKERS) {
                    float x = (float) ((marker.getWorldX() - worldCenterX) * unitsPerBlock);
                    float y = (float) (-(marker.getWorldZ() - worldCenterZ) * verticalUnitsPerBlock);
                    if (x - 0.024f < minX || x + 0.024f > maxX || y - 0.024f < -MAP_HALF_H || y + 0.024f > MAP_HALF_H) {
                        continue;
                    }
                    AtlasSheetGeometry.emitQuad(buffer, pose, x - halfW, y - bottomOffset, x + halfW, y + topOffset, 0.00945f, 0.0f, 1.0f, 1.0f, 0.0f, 0xFFFFFFFF, light);
                }
                SCRATCH_VISIBLE_MARKERS.clear();
            });
            collector.order(6).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(AtlasSidebar.getStampPaintTexture()), (pose, buffer) -> {
                AtlasEditorState.queryVisibleMarkers(minWorldX, minWorldZ, maxWorldX, maxWorldZ, markerWorldPad, SCRATCH_VISIBLE_MARKERS);
                for (MapMarker marker : SCRATCH_VISIBLE_MARKERS) {
                    float x = (float) ((marker.getWorldX() - worldCenterX) * unitsPerBlock);
                    float y = (float) (-(marker.getWorldZ() - worldCenterZ) * verticalUnitsPerBlock);
                    if (x - 0.024f < minX || x + 0.024f > maxX || y - 0.024f < -MAP_HALF_H || y + 0.024f > MAP_HALF_H) {
                        continue;
                    }
                    AtlasSheetGeometry.emitQuad(buffer, pose, x - halfW, y - bottomOffset, x + halfW, y + topOffset, 0.00946f, 0.0f, 1.0f, 1.0f, 0.0f, 0xFF000000 | marker.getType().color, light);
                }
                SCRATCH_VISIBLE_MARKERS.clear();
            });
        } else {
            collector.order(5).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(AtlasSidebar.getIconsTexture()), (pose, buffer) -> {
                AtlasEditorState.queryVisibleMarkers(minWorldX, minWorldZ, maxWorldX, maxWorldZ, markerWorldPad, SCRATCH_VISIBLE_MARKERS);
                for (MapMarker marker : SCRATCH_VISIBLE_MARKERS) {
                    float x = (float) ((marker.getWorldX() - worldCenterX) * unitsPerBlock);
                    float y = (float) (-(marker.getWorldZ() - worldCenterZ) * verticalUnitsPerBlock);
                    if (x - 0.024f < minX || x + 0.024f > maxX || y - 0.024f < -MAP_HALF_H || y + 0.024f > MAP_HALF_H) {
                        continue;
                    }
                    AtlasSheetGeometry.emitQuad(buffer, pose, x - halfW, y - bottomOffset, x + halfW, y + topOffset, 0.00945f, 128.0f / 192.0f, 1.0f, 1.0f, 0.0f, 0xFFFFFFFF, light);
                }
                SCRATCH_VISIBLE_MARKERS.clear();
            });
        }
    }

    private static void submitCompassArrow(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float minX,
            float maxX,
            double worldCenterX,
            double worldCenterZ,
            float verticalScale,
            float worldBlocksAcross,
            double playerWorldX,
            double playerWorldZ,
            float playerYaw
    ) {
        float unitsPerBlock = (2.0f * MAP_HALF_W) / worldBlocksAcross;
        float verticalUnitsPerBlock = unitsPerBlock * verticalScale;
        float px = (float) ((playerWorldX - worldCenterX) * unitsPerBlock);
        float py = (float) (-(playerWorldZ - worldCenterZ) * verticalUnitsPerBlock);
        float r = 0.018f;
        if (px - r < minX || px + r > maxX || py - r < -MAP_HALF_H || py + r > MAP_HALF_H) {
            return;
        }
        int arrowBlock = Math.max((light >> 4) & 0xF, 12);
        int arrowLight = (light & 0x00F00000) | (arrowBlock << 4);
        Identifier arrowTex = ParchmentTexture.getPlayerArrow();
        poseStack.pushPose();
        poseStack.translate(px, py, 0.0096f);
        if (Math.abs(verticalScale - 1.0f) > 1.0e-4f) {
            poseStack.scale(1.0f, verticalScale, 1.0f);
        }
        poseStack.rotateDegrees(Axis.ZP, -playerYaw);
        collector.order(7).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(arrowTex), (pose, buffer) ->
                AtlasSheetGeometry.emitQuad(buffer, pose, -r, -r, r, r, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0xFFFFFFFF, arrowLight));
        poseStack.popPose();
    }

    private static void submitAtmosphereSmoke(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float minX,
            float maxX,
            double worldCenterX,
            double worldCenterZ,
            float verticalScale,
            float worldBlocksAcross
    ) {
        List<AtlasAtmosphere.SmokeEmitter> emitters = AtlasAtmosphere.getSmokeEmitters();
        if (emitters.isEmpty()) {
            return;
        }
        float unitsPerBlock = (2.0f * MAP_HALF_W) / worldBlocksAcross;
        float verticalUnitsPerBlock = unitsPerBlock * verticalScale;
        double timeSec = System.nanoTime() / 1_000_000_000.0;
        int warmRgb = AtlasLighting.tint(0xFF3E3A36, 0xFFC2CEDE) & 0xFFFFFF;
        int soulRgb = AtlasLighting.tint(0xFF28899C, 0xFF8BE8F5) & 0xFFFFFF;
        collector.order(3).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(ParchmentTexture.getSmokePuff()), (pose, buffer) -> {
            for (AtlasAtmosphere.SmokeEmitter emitter : emitters) {
                float baseX = (float) ((emitter.x() - worldCenterX) * unitsPerBlock);
                float baseY = (float) (-(emitter.z() - worldCenterZ) * verticalUnitsPerBlock);
                if (baseX < minX - 0.10f || baseX > maxX + 0.10f || baseY < -MAP_HALF_H - 0.10f || baseY > MAP_HALF_H + 0.10f) {
                    continue;
                }
                int puffCount = AtlasOverlayMath.smokePuffCount(emitter);
                float baseR = emitter.furnace() ? 0.0072f : (emitter.signal() ? 0.0140f : 0.0112f);
                float period = AtlasOverlayMath.smokePeriod(emitter);
                float driftMax = emitter.furnace() ? 0.032f : (emitter.signal() ? 0.088f : 0.060f);
                float peakAlpha = emitter.furnace() ? 112.0f : (emitter.signal() ? 210.0f : 185.0f);
                float phaseOff = AtlasOverlayMath.smokePhaseOffset(emitter, period);
                int rgb = emitter.soul() ? soulRgb : warmRgb;
                for (int i = 0; i < puffCount; i++) {
                    float cycle = AtlasOverlayMath.smokeCycle(timeSec, phaseOff, i, puffCount, period);
                    float drift = cycle * driftMax;
                    float sway = AtlasOverlayMath.smokeSwayUnit(timeSec, i, emitter.seed()) * 0.006f * cycle;
                    float px = baseX + drift * 0.48f + sway;
                    float py = baseY + drift * 0.92f;
                    float r = baseR * (0.55f + cycle * 1.45f);
                    if (px - r < minX || px + r > maxX || py - r < -MAP_HALF_H || py + r > MAP_HALF_H) {
                        continue;
                    }
                    int alpha = Mth.clamp(Math.round(AtlasAtmosphere.sampleSmokeFade(cycle) * peakAlpha), 0, 255);
                    if (alpha <= 2) {
                        continue;
                    }
                    AtlasSheetGeometry.emitQuad(buffer, pose, px - r, py - r, px + r, py + r, 0.00926f, 0.0f, 1.0f, 1.0f, 0.0f, (alpha << 24) | rgb, light);
                }
            }
        });
    }

    private static void submitLiveOverlays(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            float minX,
            float maxX,
            double worldCenterX,
            double worldCenterZ,
            float verticalScale,
            float worldBlocksAcross
    ) {
        float unitsPerBlock = (2.0f * MAP_HALF_W) / worldBlocksAcross;
        float verticalUnitsPerBlock = unitsPerBlock * verticalScale;
        int tint = AtlasLighting.tint(0xFFFFFFFF, 0xFFB8C4D8);

        AtlasSavedData.DeathPos death = AtlasLiveTracker.getDeathPos(ClientNetworking.currentDimensionKey());
        if (death != null) {
            float dx = (float) ((death.x() - worldCenterX) * unitsPerBlock);
            float dy = (float) (-(death.z() - worldCenterZ) * verticalUnitsPerBlock);
            float r = 0.017f;
            if (dx - r >= minX && dx + r <= maxX && dy - r >= -MAP_HALF_H && dy + r <= MAP_HALF_H) {
                collector.order(6).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(ParchmentTexture.getDeathSkull()), (pose, buffer) ->
                        AtlasSheetGeometry.emitQuad(buffer, pose, dx - r, dy - r, dx + r, dy + r, 0.00950f, 0.0f, 1.0f, 1.0f, 0.0f, tint, light));
            }
        }

        Minecraft mc = Minecraft.getInstance();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        List<AtlasPayloads.RemotePlayerPos> others = AtlasLiveTracker.getVisibleOtherPlayers(mc.level, mc.player, partialTick);
        if (!others.isEmpty()) {
            float r = 0.019f;
            Identifier arrowTex = ParchmentTexture.getOtherPlayerArrow();
            for (AtlasPayloads.RemotePlayerPos other : others) {
                float ox = (float) ((other.x() - worldCenterX) * unitsPerBlock);
                float oy = (float) (-(other.z() - worldCenterZ) * verticalUnitsPerBlock);
                if (ox - r < minX || ox + r > maxX || oy - r < -MAP_HALF_H || oy + r > MAP_HALF_H) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(ox, oy, 0.00954f);
                poseStack.rotateDegrees(Axis.ZP, -other.yRot());
                collector.order(6).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(arrowTex), (pose, buffer) ->
                        AtlasSheetGeometry.emitQuad(buffer, pose, -r, -r, r, r, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, tint, light));
                poseStack.popPose();
            }
        }
    }

    private static void submitParchmentWeather(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            Identifier whiteTex,
            int light,
            float minX,
            float maxX
    ) {
        float rain = AtlasAtmosphere.getRainFactor();
        float snow = AtlasAtmosphere.getSnowFactor();
        float flash = AtlasAtmosphere.getThunderFlash();
        if (rain <= 0.01f && snow <= 0.01f && flash <= 0.01f) {
            return;
        }
        if (rain > 0.01f) {
            int dropRgb = AtlasLighting.tint(0xFFFFFFFF, 0xFF9AB2CE) & 0xFFFFFF;
            float rainStrength = Mth.clamp(rain, 0.35f, 1.0f);
            collector.order(7).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(ParchmentTexture.getMoistureRing()), (pose, buffer) -> {
                for (AtlasAtmosphere.ParchmentDrop drop : AtlasAtmosphere.getDrops()) {
                    if (!drop.active) {
                        continue;
                    }
                    float t = Mth.clamp(drop.age / drop.maxAge, 0.0f, 1.0f);
                    float fadeIn = Mth.clamp(t * 9.0f, 0.0f, 1.0f);
                    float fadeOut = (1.0f - t) * (1.0f - t * 0.45f);
                    int alpha = Mth.clamp(Math.round(fadeIn * fadeOut * rainStrength * 0.76f * 255.0f), 0, 200);
                    if (alpha <= 1) {
                        continue;
                    }
                    float dx = -MAP_HALF_W + drop.u * (2.0f * MAP_HALF_W);
                    float dy = MAP_HALF_H - drop.v * (2.0f * MAP_HALF_H);
                    float rad = (drop.radius * 0.0032f) * (0.86f + t * 0.16f);
                    float aspect = 0.90f + ((drop.seed & 0xFF) / 255.0f) * 0.20f;
                    float radX = rad * aspect;
                    float radY = rad / aspect;
                    if (dx - radX < minX || dx + radX > maxX || dy - radY < -MAP_HALF_H || dy + radY > MAP_HALF_H) {
                        continue;
                    }
                    AtlasSheetGeometry.emitQuad(buffer, pose, dx - radX, dy - radY, dx + radX, dy + radY, 0.00970f, 0.0f, 1.0f, 1.0f, 0.0f, (alpha << 24) | dropRgb, light);
                }
            });
        }
        if (snow > 0.01f || flash > 0.01f) {
            float band = 0.045f;
            int color = flash > snow
                    ? (Mth.clamp(Math.round(flash * 34.0f), 0, 44) << 24) | 0xCFE2FF
                    : (Mth.clamp(Math.round(snow * 42.0f), 0, 56) << 24) | 0xDDECF7;
            collector.order(7).submitCustomGeometry(poseStack, AtlasRenderPipelines.atlasMapLayer(whiteTex), (pose, buffer) -> {
                AtlasSheetGeometry.emitQuad(buffer, pose, minX, MAP_HALF_H - band, maxX, MAP_HALF_H, 0.00972f, 0.0f, 1.0f, 1.0f, 0.0f, color, light);
                AtlasSheetGeometry.emitQuad(buffer, pose, minX, -MAP_HALF_H, maxX, -MAP_HALF_H + band, 0.00972f, 0.0f, 1.0f, 1.0f, 0.0f, color, light);
            });
        }
    }
}
