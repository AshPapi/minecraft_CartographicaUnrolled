package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.client.atmosphere.AtlasLighting;
import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.client.map.AtlasRouteManager;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.client.render.AtlasOverlayMath;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.cartographica_unrolled.client.ui.AtlasViewport.FRAME_PAD;

final class AtlasScreenAnnotationRenderer {
    private final List<MapStroke> visibleStrokesScratch = new ArrayList<>(64);
    private final List<MapMarker> visibleMarkersScratch = new ArrayList<>(32);
    private float[] scratchScreenPts = new float[128];
    private float[] scratchRouteDashes = new float[512];

    List<MapStroke> visibleStrokes() {
        return this.visibleStrokesScratch;
    }

    List<MapMarker> visibleMarkers() {
        return this.visibleMarkersScratch;
    }

    void clearScratch() {
        this.visibleStrokesScratch.clear();
        this.visibleMarkersScratch.clear();
    }

    void renderAnnotationsAndEntities(
            GuiGraphicsExtractor g,
            AtlasGuiBatcher batcher,
            Font font,
            @Nullable Minecraft mc,
            AtlasViewport vp,
            AtlasMarkerLabelEditor labelEditor,
            @Nullable MapStroke activeStroke,
            boolean overUi,
            int mouseX,
            int mouseY,
            List<AtlasPayloads.RemotePlayerPos> visibleOthers
    ) {
        drawStrokes(g, batcher, vp, activeStroke);
        drawRoutePath(g, batcher, vp);
        drawMarkers(g, batcher, font, mc, vp, labelEditor, mouseX, mouseY, overUi);
        drawDeathMarker(g, batcher, vp, mouseX, mouseY, overUi);
        drawRouteTargetCross(g, batcher, vp, mouseX, mouseY, overUi);
        drawOtherPlayers(g, batcher, vp, visibleOthers);
        drawPlayerCursor(g, batcher, vp);
    }

    private void drawStrokes(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, AtlasViewport vp, @Nullable MapStroke activeStroke) {
        double minWx = vp.screenToWorldX(FRAME_PAD);
        double minWz = vp.screenToWorldZ(FRAME_PAD);
        double maxWx = vp.screenToWorldX(vp.width() - FRAME_PAD);
        double maxWz = vp.screenToWorldZ(vp.height() - FRAME_PAD);

        AtlasEditorState.queryVisibleStrokes(minWx, minWz, maxWx, maxWz, this.visibleStrokesScratch);
        Identifier circleTex = ParchmentTexture.getCircle();
        for (MapStroke stroke : this.visibleStrokesScratch) {
            drawSingleStroke(g, batcher, vp, stroke, circleTex, minWx, minWz, maxWx, maxWz);
        }
        if (activeStroke != null) {
            drawSingleStroke(g, batcher, vp, activeStroke, circleTex, minWx, minWz, maxWx, maxWz);
        }
    }

    private void drawSingleStroke(
            GuiGraphicsExtractor g,
            AtlasGuiBatcher batcher,
            AtlasViewport vp,
            MapStroke stroke,
            Identifier circleTex,
            double minWx,
            double minWz,
            double maxWx,
            double maxWz
    ) {
        if (!stroke.intersectsBounds(minWx, minWz, maxWx, maxWz)) {
            return;
        }
        List<MapStroke.Point> pts = stroke.getPoints();
        int n = pts.size();
        if (n == 0) {
            return;
        }
        float thickness = Math.max(1.0f, stroke.getWidth() * vp.zoom());
        float radius = thickness * 0.5f;
        int color = stroke.isDye()
                ? AtlasLighting.dyeInkColor(stroke.getColor())
                : (0xFF000000 | stroke.getColor());
        int width = vp.width();
        int height = vp.height();

        if (n == 1) {
            float x = (float) vp.worldToScreenX(pts.getFirst().x());
            float y = (float) vp.worldToScreenY(pts.getFirst().z());
            if (x >= -radius && x <= width + radius && y >= -radius && y <= height + radius) {
                batcher.submitTexturedQuad(
                        g, RenderPipelines.GUI_TEXTURED, circleTex,
                        x - radius, y - radius, x + radius, y + radius,
                        0.0f, 0.0f, 1.0f, 1.0f, color
                );
            }
            return;
        }

        int needed = n * 2;
        if (this.scratchScreenPts.length < needed) {
            this.scratchScreenPts = new float[Math.max(needed, this.scratchScreenPts.length * 2)];
        }
        float[] sp = this.scratchScreenPts;
        for (int i = 0; i < n; i++) {
            MapStroke.Point pt = pts.get(i);
            sp[i * 2] = (float) vp.worldToScreenX(pt.x());
            sp[i * 2 + 1] = (float) vp.worldToScreenY(pt.z());
        }

        batcher.beginSolid(g);
        for (int i = 0; i < n - 1; i++) {
            float x0 = sp[i * 2];
            float y0 = sp[i * 2 + 1];
            float x1 = sp[i * 2 + 2];
            float y1 = sp[i * 2 + 3];
            if (Math.max(x0, x1) < -radius || Math.min(x0, x1) > width + radius
                    || Math.max(y0, y1) < -radius || Math.min(y0, y1) > height + radius) {
                continue;
            }
            batcher.addThickLine(x0, y0, x1, y1, thickness, color);
        }
        batcher.flush();

        batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, circleTex);
        for (int i = 0; i < n; i++) {
            float x = sp[i * 2];
            float y = sp[i * 2 + 1];
            if (x < -radius || x > width + radius || y < -radius || y > height + radius) {
                continue;
            }
            if (i > 0 && i < n - 1) {
                if (thickness < 1.5f || AtlasOverlayMath.shouldSkipInteriorJoint(
                        x - sp[(i - 1) * 2],
                        y - sp[(i - 1) * 2 + 1],
                        sp[(i + 1) * 2] - x,
                        sp[(i + 1) * 2 + 1] - y)) {
                    continue;
                }
            }
            batcher.addTexturedAxisQuad(
                    x - radius, y - radius, x + radius, y + radius,
                    0.0f, 0.0f, 1.0f, 1.0f, color
            );
        }
        batcher.flush();
    }

    private void drawRoutePath(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, AtlasViewport vp) {
        if (!AtlasRouteManager.hasTarget()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        List<AtlasRouteManager.RoutePoint> path = AtlasRouteManager.getRenderPath(mc.player.getX(), mc.player.getZ());
        if (path.size() < 2) {
            return;
        }

        float zoom = vp.zoom();
        int width = vp.width();
        int height = vp.height();
        float thickness = Math.max(0.55f, 0.48f * zoom);
        float dashLen = Math.max(2.0f, zoom);
        float gapLen = Math.max(2.3f, 1.15f * zoom);
        float period = dashLen + gapLen;
        int color = AtlasLighting.dyeInkColor(DyeColor.BLACK.getMapColor().col);

        boolean drawing = true;
        float remain = dashLen;
        int emittedDashes = 0;
        for (int i = 0; i < path.size() - 1 && emittedDashes < 4096; i++) {
            AtlasRouteManager.RoutePoint p0 = path.get(i);
            AtlasRouteManager.RoutePoint p1 = path.get(i + 1);
            float x0 = (float) vp.worldToScreenX(p0.x());
            float y0 = (float) vp.worldToScreenY(p0.z());
            float x1 = (float) vp.worldToScreenX(p1.x());
            float y1 = (float) vp.worldToScreenY(p1.z());
            float dx = x1 - x0;
            float dy = y1 - y0;
            float segLen = Mth.sqrt(dx * dx + dy * dy);
            if (!Float.isFinite(segLen) || segLen < 0.01f) {
                continue;
            }
            boolean visible = Math.max(x0, x1) >= -20.0f && Math.min(x0, x1) <= width + 20.0f
                    && Math.max(y0, y1) >= -20.0f && Math.min(y0, y1) <= height + 20.0f;
            if (!visible) {
                float nextState = AtlasOverlayMath.advanceOffscreenDashState(
                        drawing, remain, segLen, dashLen, gapLen, period, 0.01f);
                drawing = nextState > 0.0f;
                remain = Math.abs(nextState);
                continue;
            }
            float pos = 0.0f;
            while (pos < segLen && emittedDashes < 4096) {
                float stepLen = Math.min(remain, segLen - pos);
                float nextPos = pos + stepLen;
                if (nextPos <= pos) {
                    break;
                }
                if (drawing && stepLen > 0.01f) {
                    float t0 = pos / segLen;
                    float t1 = nextPos / segLen;
                    float ax = x0 + dx * t0;
                    float ay = y0 + dy * t0;
                    float bx = x0 + dx * t1;
                    float by = y0 + dy * t1;
                    if (Math.max(ax, bx) >= -20.0f && Math.min(ax, bx) <= width + 20.0f
                            && Math.max(ay, by) >= -20.0f && Math.min(ay, by) <= height + 20.0f) {
                        int baseIdx = emittedDashes * 4;
                        if (this.scratchRouteDashes.length < baseIdx + 4) {
                            this.scratchRouteDashes = Arrays.copyOf(
                                    this.scratchRouteDashes,
                                    Math.min(4096 * 4, this.scratchRouteDashes.length * 2)
                            );
                        }
                        this.scratchRouteDashes[baseIdx] = ax;
                        this.scratchRouteDashes[baseIdx + 1] = ay;
                        this.scratchRouteDashes[baseIdx + 2] = bx;
                        this.scratchRouteDashes[baseIdx + 3] = by;
                        emittedDashes++;
                    }
                }
                pos = nextPos;
                remain -= stepLen;
                if (remain <= 0.01f) {
                    drawing = !drawing;
                    remain = drawing ? dashLen : gapLen;
                }
            }
        }

        if (emittedDashes == 0) {
            return;
        }
        float[] dashes = this.scratchRouteDashes;
        batcher.beginSolid(g);
        for (int i = 0; i < emittedDashes; i++) {
            int baseIdx = i * 4;
            batcher.addThickLine(dashes[baseIdx], dashes[baseIdx + 1], dashes[baseIdx + 2], dashes[baseIdx + 3], thickness, color);
        }
        batcher.flush();

        if (thickness >= 1.35f) {
            float r = thickness * 0.5f;
            batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getCircle());
            for (int i = 0; i < emittedDashes; i++) {
                int baseIdx = i * 4;
                float ax = dashes[baseIdx];
                float ay = dashes[baseIdx + 1];
                float bx = dashes[baseIdx + 2];
                float by = dashes[baseIdx + 3];
                batcher.addTexturedAxisQuad(ax - r, ay - r, ax + r, ay + r, 0.0f, 0.0f, 1.0f, 1.0f, color);
                batcher.addTexturedAxisQuad(bx - r, by - r, bx + r, by + r, 0.0f, 0.0f, 1.0f, 1.0f, color);
            }
            batcher.flush();
        }
    }

    private void drawMarkers(
            GuiGraphicsExtractor g,
            AtlasGuiBatcher batcher,
            Font font,
            @Nullable Minecraft mc,
            AtlasViewport vp,
            AtlasMarkerLabelEditor labelEditor,
            int mouseX,
            int mouseY,
            boolean overUi
    ) {
        int width = vp.width();
        int height = vp.height();
        double padWorld = 44.0 / Math.max(0.05, vp.zoom());
        AtlasEditorState.queryVisibleMarkers(
                vp.screenToWorldX(FRAME_PAD),
                vp.screenToWorldZ(FRAME_PAD),
                vp.screenToWorldX(width - FRAME_PAD),
                vp.screenToWorldZ(height - FRAME_PAD),
                padWorld,
                this.visibleMarkersScratch
        );
        if (this.visibleMarkersScratch.isEmpty()) {
            return;
        }
        float markerScale = vp.getMarkerScale();
        double hoverRadius = 9.0 * markerScale;
        double hoverRadiusSq = hoverRadius * hoverRadius;
        boolean splitIcons = AtlasSidebar.ensureBannerTextures();
        Identifier baseTex = splitIcons ? AtlasSidebar.getStampBaseTexture() : AtlasSidebar.getIconsTexture();
        float baseU0 = splitIcons ? 0.0f : (128.0f / 192.0f);
        MapMarker editingMarker = labelEditor.editingMarker();

        float leftOff = -8.0f * markerScale;
        float rightOff = 8.0f * markerScale;
        float topOff = -6.0f * markerScale;
        float botOff = 10.0f * markerScale;

        boolean anyHover = false;
        boolean anyLabeled = false;
        batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, baseTex);
        for (MapMarker marker : this.visibleMarkersScratch) {
            float sx = (float) vp.worldToScreenX(marker.getWorldX());
            float sy = (float) vp.worldToScreenY(marker.getWorldZ());
            if (sx < -40.0f || sx > width + 40.0f || sy < -40.0f || sy > height + 40.0f) {
                continue;
            }
            if (!overUi && !anyHover) {
                double dx = mouseX - sx;
                double dy = mouseY - sy;
                if (dx * dx + dy * dy <= hoverRadiusSq) {
                    anyHover = true;
                }
            }
            if (!marker.getLabel().isEmpty() || marker == editingMarker) {
                anyLabeled = true;
            }
            batcher.addTexturedAxisQuad(
                    sx + leftOff, sy + topOff, sx + rightOff, sy + botOff,
                    baseU0, 0.0f, 1.0f, 1.0f, 0xFFFFFFFF
            );
        }
        batcher.flush();

        if (anyHover) {
            g.requestCursor(CursorTypes.POINTING_HAND);
        }

        if (splitIcons) {
            batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, AtlasSidebar.getStampPaintTexture());
            for (MapMarker marker : this.visibleMarkersScratch) {
                float sx = (float) vp.worldToScreenX(marker.getWorldX());
                float sy = (float) vp.worldToScreenY(marker.getWorldZ());
                if (sx < -40.0f || sx > width + 40.0f || sy < -40.0f || sy > height + 40.0f) {
                    continue;
                }
                batcher.addTexturedAxisQuad(
                        sx + leftOff, sy + topOff, sx + rightOff, sy + botOff,
                        0.0f, 0.0f, 1.0f, 1.0f, 0xFF000000 | marker.getType().color
                );
            }
            batcher.flush();
        }

        if (!anyLabeled) {
            return;
        }

        float textScale = 0.75f;
        int boxHeight = (int) Math.ceil(font.lineHeight * textScale) + 4;
        batcher.beginSolid(g);
        for (MapMarker marker : this.visibleMarkersScratch) {
            boolean selected = marker == editingMarker;
            String label = marker.getLabel();
            if (label.isEmpty() && !selected) {
                continue;
            }
            float sx = (float) vp.worldToScreenX(marker.getWorldX());
            float sy = (float) vp.worldToScreenY(marker.getWorldZ());
            if (sx < -40.0f || sx > width + 40.0f || sy < -40.0f || sy > height + 40.0f) {
                continue;
            }
            int w = font.width(label);
            int boxWidth = Math.max(selected ? 6 : 4, (int) Math.ceil(w * textScale) + 4);
            int rx0 = -boxWidth / 2;
            int ry0 = 10;
            batcher.addAxisQuad(
                    sx + rx0 * markerScale,
                    sy + ry0 * markerScale,
                    sx + (rx0 + boxWidth) * markerScale,
                    sy + (ry0 + boxHeight) * markerScale,
                    0xDD202020
            );
        }
        batcher.flush();

        boolean showCaret = (System.currentTimeMillis() / 500L & 1L) == 0L;
        for (MapMarker marker : this.visibleMarkersScratch) {
            boolean selected = marker == editingMarker;
            String label = marker.getLabel();
            if (label.isEmpty() && !selected) {
                continue;
            }
            float sx = (float) vp.worldToScreenX(marker.getWorldX());
            float sy = (float) vp.worldToScreenY(marker.getWorldZ());
            if (sx < -40.0f || sx > width + 40.0f || sy < -40.0f || sy > height + 40.0f) {
                continue;
            }
            int w = font.width(label);
            int boxWidth = Math.max(selected ? 6 : 4, (int) Math.ceil(w * textScale) + 4);
            int rx0 = -boxWidth / 2;
            int ry0 = 10;
            int rx1 = rx0 + boxWidth;
            int ry1 = ry0 + boxHeight;
            g.pose().pushMatrix();
            g.pose().translate(sx, sy);
            g.pose().scale(markerScale, markerScale);
            g.pose().translate(rx0 + (boxWidth - w * textScale) * 0.5f,
                    ry0 + (boxHeight - font.lineHeight * textScale) * 0.5f);
            g.pose().scale(textScale, textScale);
            g.text(font, label, 0, 0, 0xFFF5F5F5, false);
            if (selected && showCaret) {
                int cursor = Mth.clamp(labelEditor.cursorPos(), 0, label.length());
                int cursorX = font.width(label.substring(0, cursor));
                g.fill(cursorX, 0, cursorX + 1, font.lineHeight, 0xFFF5F5F5);
            }
            g.pose().popMatrix();
            if (selected && mc != null) {
                int isx = Math.round(sx);
                int isy = Math.round(sy);
                mc.textInputManager().setTextInputArea(
                        isx + Math.round(rx0 * markerScale),
                        isy + Math.round(ry0 * markerScale),
                        isx + Math.round(rx1 * markerScale),
                        isy + Math.round(ry1 * markerScale)
                );
            }
        }
    }

    private static void drawDeathMarker(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, AtlasViewport vp, int mouseX, int mouseY, boolean overUi) {
        AtlasSavedData.DeathPos death = AtlasLiveTracker.getDeathPos(ClientNetworking.currentDimensionKey());
        if (death == null) {
            return;
        }
        float sx = (float) vp.worldToScreenX(death.x());
        float sy = (float) vp.worldToScreenY(death.z());
        if (sx < -28.0f || sx > vp.width() + 28.0f || sy < -28.0f || sy > vp.height() + 28.0f) {
            return;
        }
        float scale = vp.getMarkerScale();
        if (!overUi && Math.hypot(mouseX - sx, mouseY - sy) <= 9.5 * scale) {
            g.requestCursor(CursorTypes.POINTING_HAND);
        }
        float halfSize = 7.0f * scale;
        int tint = AtlasLighting.tint(0xFFFFFFFF, 0xFFB8C4D8);
        batcher.submitTexturedQuad(
                g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getDeathSkull(),
                sx - halfSize, sy - halfSize, sx + halfSize, sy + halfSize,
                0.0f, 0.0f, 1.0f, 1.0f, tint
        );
    }

    private static void drawRouteTargetCross(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, AtlasViewport vp, int mouseX, int mouseY, boolean overUi) {
        if (!AtlasRouteManager.hasTarget()) {
            return;
        }
        float zoom = vp.zoom();
        float sx = (float) vp.worldToScreenX(AtlasRouteManager.getTargetX());
        float sy = (float) vp.worldToScreenY(AtlasRouteManager.getTargetZ());
        float arm = 3.0f * zoom;
        float coreArm = arm * 0.90f;
        float outlineThick = Math.max(1.2f, 1.9f * zoom);
        float coreThick = Math.max(0.75f, 1.1f * zoom);
        float cullPad = Math.max(40.0f, arm + outlineThick);
        if (sx < -cullPad || sx > vp.width() + cullPad || sy < -cullPad || sy > vp.height() + cullPad) {
            return;
        }
        double hoverPx = Math.max(6.0, 4.5 * zoom);
        if (!overUi && Math.hypot(mouseX - sx, mouseY - sy) <= hoverPx) {
            g.requestCursor(CursorTypes.POINTING_HAND);
        }

        int outlineColor = AtlasLighting.tint(0xE6300808, 0xE6180404);
        int crossColor = AtlasLighting.tint(0xFFE02424, 0xFF8C1818);

        batcher.beginSolid(g);
        batcher.addThickLine(sx - arm, sy - arm, sx + arm, sy + arm, outlineThick, outlineColor);
        batcher.addThickLine(sx - arm, sy + arm, sx + arm, sy - arm, outlineThick, outlineColor);
        batcher.addThickLine(sx - coreArm, sy - coreArm, sx + coreArm, sy + coreArm, coreThick, crossColor);
        batcher.addThickLine(sx - coreArm, sy + coreArm, sx + coreArm, sy - coreArm, coreThick, crossColor);
        batcher.flush();
    }

    private static void drawOtherPlayers(
            GuiGraphicsExtractor g,
            AtlasGuiBatcher batcher,
            AtlasViewport vp,
            List<AtlasPayloads.RemotePlayerPos> others
    ) {
        if (others.isEmpty()) {
            return;
        }
        int width = vp.width();
        int height = vp.height();
        float size = 2.4f * Mth.clamp((float) Math.sqrt(vp.zoom() / 1.25f), 0.50f, 1.0f);
        float halfSize = size * 1.6f;
        int tint = AtlasLighting.tint(0xFFFFFFFF, 0xFFBAC8DC);

        batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getOtherPlayerArrow());
        for (AtlasPayloads.RemotePlayerPos other : others) {
            float sx = (float) vp.worldToScreenX(other.x());
            float sy = (float) vp.worldToScreenY(other.z());
            if (sx < -24.0f || sx > width + 24.0f || sy < -24.0f || sy > height + 24.0f) {
                continue;
            }
            float yawRad = other.yRot() * Mth.DEG_TO_RAD;
            batcher.addRotatedTexturedQuad(
                    sx, sy, halfSize, halfSize, yawRad,
                    0.0f, 0.0f, 1.0f, 1.0f, tint
            );
        }
        batcher.flush();
    }

    private static void drawPlayerCursor(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, AtlasViewport vp) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        float sx = (float) vp.worldToScreenX(mc.player.getX());
        float sy = (float) vp.worldToScreenY(mc.player.getZ());
        float yawRad = mc.player.getYRot() * Mth.DEG_TO_RAD;
        float size = 2.8f * Mth.clamp((float) Math.sqrt(vp.zoom() / 1.25f), 0.50f, 1.0f);
        float halfSize = size * 1.6f;

        batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getPlayerArrow());
        batcher.addRotatedTexturedQuad(
                sx, sy, halfSize, halfSize, yawRad,
                0.0f, 0.0f, 1.0f, 1.0f, 0xFFFFFFFF
        );
        batcher.flush();
    }
}
