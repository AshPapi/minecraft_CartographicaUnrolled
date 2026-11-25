package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.atmosphere.AtlasAtmosphere;
import com.cartographica_unrolled.client.atmosphere.AtlasLighting;
import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.client.map.AtlasInteriorOverlay;
import com.cartographica_unrolled.client.map.AtlasRegion;
import com.cartographica_unrolled.client.map.AtlasRegionCache;
import com.cartographica_unrolled.client.render.AtlasOverlayMath;
import com.cartographica_unrolled.client.render.AtlasRenderPipelines;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.cartographica_unrolled.client.ui.AtlasViewport.FRAME_PAD;

public final class AtlasScreenMapRenderer {
    private static final Identifier SIGNATURE_TEXTURE = Identifier.fromNamespaceAndPath(
            CartographicaUnrolled.MOD_ID, "textures/gui/papi_signature.png");
    private static final Component BLOCKS_LABEL = Component.translatable("gui.cartographica_unrolled.blocks");

    private final AtlasGuiBatcher batcher = new AtlasGuiBatcher();
    private final AtlasScreenAnnotationRenderer annotations = new AtlasScreenAnnotationRenderer();

    public AtlasGuiBatcher batcher() {
        return this.batcher;
    }

    public List<MapStroke> visibleStrokes() {
        return this.annotations.visibleStrokes();
    }

    public List<MapMarker> visibleMarkers() {
        return this.annotations.visibleMarkers();
    }

    public void clearScratch() {
        this.annotations.clearScratch();
    }

    public void renderCanvas(
            GuiGraphicsExtractor g,
            Font font,
            @Nullable Minecraft mc,
            AtlasViewport vp,
            AtlasMarkerLabelEditor labelEditor,
            AtlasEditorState.Tool activeTool,
            @Nullable MapStroke activeStroke,
            boolean showGrid,
            boolean overUi,
            int mouseX,
            int mouseY,
            float partialTick,
            List<AtlasPayloads.RemotePlayerPos> visibleOthers
    ) {
        this.batcher.resetFrame();
        this.annotations.clearScratch();

        int width = vp.width();
        int height = vp.height();
        int innerW = width - FRAME_PAD * 2;
        int innerH = height - FRAME_PAD * 2;
        if (innerW > 0 && innerH > 0) {
            Identifier parchment = ParchmentTexture.get();
            float paperTile = 64.0f * vp.zoom();
            int parchmentTint = AtlasLighting.tint(0xFFFFFFFF, 0xFF343C4E);
            int uvScale = 65536;
            double tileX = vp.screenToWorldX(FRAME_PAD) / 64.0;
            double tileZ = vp.screenToWorldZ(FRAME_PAD) / 64.0;
            float u0 = (float) (tileX - Math.floor(tileX)) * uvScale;
            float v0 = (float) (tileZ - Math.floor(tileZ)) * uvScale;
            int srcW = Math.max(1, Math.round((innerW / paperTile) * uvScale));
            int srcH = Math.max(1, Math.round((innerH / paperTile) * uvScale));
            g.blit(RenderPipelines.GUI_TEXTURED, parchment,
                    FRAME_PAD, FRAME_PAD, u0, v0, innerW, innerH, srcW, srcH, uvScale, uvScale, parchmentTint);
        }

        drawExploredRegions(g, vp);
        drawAtmosphereSmoke(g, vp);
        if (showGrid) {
            drawWorldGrid(g, font, vp, activeTool);
        }
        this.annotations.renderAnnotationsAndEntities(
                g, this.batcher, font, mc, vp, labelEditor, activeStroke, overUi, mouseX, mouseY, visibleOthers
        );
        drawParchmentWeather(g, vp, partialTick);
        drawParchmentVignette(g, vp);
        drawSignature(g, vp);
        if (showGrid) {
            drawScaleRuler(g, font, vp);
        }
    }

    private void drawExploredRegions(GuiGraphicsExtractor g, AtlasViewport vp) {
        int regionCount = AtlasRegionCache.getRegionCount();
        int regionWorldBlocks = AtlasRegionCache.REGION_CHUNKS * 16;
        int regionTint = AtlasLighting.regionTint();
        int width = vp.width();
        int height = vp.height();

        int minRx = Math.floorDiv(Mth.floor(vp.screenToWorldX(FRAME_PAD)), regionWorldBlocks);
        int maxRx = Math.floorDiv(Mth.floor(vp.screenToWorldX(width - FRAME_PAD)), regionWorldBlocks);
        int minRz = Math.floorDiv(Mth.floor(vp.screenToWorldZ(FRAME_PAD)), regionWorldBlocks);
        int maxRz = Math.floorDiv(Mth.floor(vp.screenToWorldZ(height - FRAME_PAD)), regionWorldBlocks);
        boolean bounded = AtlasOverlayMath.shouldUseBoundedRegionGrid(regionCount, minRx, maxRx, minRz, maxRz);

        if (regionCount > 0) {
            if (bounded) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    for (int rx = minRx; rx <= maxRx; rx++) {
                        AtlasRegion region = AtlasRegionCache.getRegion(rx, rz);
                        if (region != null) {
                            drawSingleRegionColor(g, vp, region, regionWorldBlocks, regionTint);
                        }
                    }
                }
            } else {
                for (AtlasRegion region : AtlasRegionCache.getRegions().values()) {
                    drawSingleRegionColor(g, vp, region, regionWorldBlocks, regionTint);
                }
            }
        }

        if (AtlasInteriorOverlay.isActive()) {
            double iwx0 = AtlasInteriorOverlay.getOriginWorldX();
            double iwz0 = AtlasInteriorOverlay.getOriginWorldZ();
            float isx0 = (float) vp.worldToScreenX(iwx0);
            float isy0 = (float) vp.worldToScreenY(iwz0);
            float isx1 = (float) vp.worldToScreenX(iwx0 + AtlasInteriorOverlay.getWorldSpanX());
            float isy1 = (float) vp.worldToScreenY(iwz0 + AtlasInteriorOverlay.getWorldSpanZ());
            int tw = AtlasInteriorOverlay.getTexWidthPx();
            int th = AtlasInteriorOverlay.getTexHeightPx();
            if (tw > 0 && th > 0 && isx1 >= FRAME_PAD && isy1 >= FRAME_PAD
                    && isx0 <= width - FRAME_PAD && isy0 <= height - FRAME_PAD) {
                this.batcher.submitTexturedQuad(
                        g, RenderPipelines.GUI_TEXTURED, AtlasInteriorOverlay.getTextureId(),
                        isx0, isy0, isx1, isy1, 0.0f, 0.0f, 1.0f, 1.0f, regionTint
                );
            }
        }

        float night = AtlasLighting.getNightFactor();
        if (night > 0.01f && regionCount > 0) {
            int tint = (Math.round(night * 95.0f) << 24) | 0xFFB84D;
            if (bounded) {
                for (int rz = minRz; rz <= maxRz; rz++) {
                    for (int rx = minRx; rx <= maxRx; rx++) {
                        AtlasRegion region = AtlasRegionCache.getRegion(rx, rz);
                        if (region != null) {
                            drawSingleRegionLight(g, vp, region, regionWorldBlocks, tint);
                        }
                    }
                }
            } else {
                for (AtlasRegion region : AtlasRegionCache.getRegions().values()) {
                    drawSingleRegionLight(g, vp, region, regionWorldBlocks, tint);
                }
            }
        }
    }

    private void drawSingleRegionColor(GuiGraphicsExtractor g, AtlasViewport vp, AtlasRegion region, int regionWorldBlocks, int regionTint) {
        double wx0 = region.rx * (double) regionWorldBlocks;
        double wz0 = region.rz * (double) regionWorldBlocks;
        float sx0 = (float) vp.worldToScreenX(wx0);
        float sy0 = (float) vp.worldToScreenY(wz0);
        float sx1 = (float) vp.worldToScreenX(wx0 + regionWorldBlocks);
        float sy1 = (float) vp.worldToScreenY(wz0 + regionWorldBlocks);
        if (sx1 < FRAME_PAD || sy1 < FRAME_PAD || sx0 > vp.width() - FRAME_PAD || sy0 > vp.height() - FRAME_PAD) {
            return;
        }
        float screenSpan = Math.max(sx1 - sx0, sy1 - sy0);
        AtlasRegionCache.RegionTextureRef texRef = region.getRenderTexture(screenSpan > 110.0f);
        if (texRef == null) {
            return;
        }
        this.batcher.submitTexturedQuad(
                g, RenderPipelines.GUI_TEXTURED, texRef.textureId(),
                sx0, sy0, sx1, sy1, 0.0f, 0.0f, 1.0f, 1.0f, regionTint
        );
    }

    private void drawSingleRegionLight(GuiGraphicsExtractor g, AtlasViewport vp, AtlasRegion region, int regionWorldBlocks, int tint) {
        double wx0 = region.rx * (double) regionWorldBlocks;
        double wz0 = region.rz * (double) regionWorldBlocks;
        float sx0 = (float) vp.worldToScreenX(wx0);
        float sy0 = (float) vp.worldToScreenY(wz0);
        float sx1 = (float) vp.worldToScreenX(wx0 + regionWorldBlocks);
        float sy1 = (float) vp.worldToScreenY(wz0 + regionWorldBlocks);
        if (sx1 < FRAME_PAD || sy1 < FRAME_PAD || sx0 > vp.width() - FRAME_PAD || sy0 > vp.height() - FRAME_PAD) {
            return;
        }
        AtlasRegionCache.RegionTextureRef lightRef = region.getRenderLightTexture();
        if (lightRef == null) {
            return;
        }
        this.batcher.submitTexturedQuad(
                g, AtlasRenderPipelines.GUI_LIGHT_PIPELINE, lightRef.textureId(),
                sx0, sy0, sx1, sy1, 0.0f, 0.0f, 1.0f, 1.0f, tint
        );
    }

    private void drawAtmosphereSmoke(GuiGraphicsExtractor g, AtlasViewport vp) {
        List<AtlasAtmosphere.SmokeEmitter> emitters = AtlasAtmosphere.getSmokeEmitters();
        float zoom = vp.zoom();
        if (emitters.isEmpty() || zoom < 0.20f) {
            return;
        }
        int width = vp.width();
        int height = vp.height();
        double timeSec = System.nanoTime() / 1_000_000_000.0;
        int warmRgb = AtlasLighting.tint(0xFF3E3A36, 0xFFC2CEDE) & 0xFFFFFF;
        int soulRgb = AtlasLighting.tint(0xFF28899C, 0xFF8BE8F5) & 0xFFFFFF;
        float zoomScale = Mth.clamp(zoom, 0.55f, 3.6f);
        float driftScale = Mth.clamp(zoom * 0.85f, 0.5f, 2.8f);
        float swayScale = Mth.clamp(zoom * 0.7f, 0.5f, 2.2f);

        this.batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getSmokePuff());
        for (AtlasAtmosphere.SmokeEmitter emitter : emitters) {
            float baseX = (float) vp.worldToScreenX(emitter.x());
            float baseY = (float) vp.worldToScreenY(emitter.z());
            if (baseX < -72.0f || baseX > width + 72.0f || baseY < -72.0f || baseY > height + 72.0f) {
                continue;
            }
            int puffCount = AtlasOverlayMath.smokePuffCount(emitter);
            float baseRadius = (emitter.furnace() ? 1.85f : (emitter.signal() ? 3.8f : 3.1f)) * zoomScale;
            float period = AtlasOverlayMath.smokePeriod(emitter);
            float driftMax = (emitter.furnace() ? 12.0f : (emitter.signal() ? 34.0f : 23.0f)) * driftScale;
            float peakAlpha = emitter.furnace() ? 0.42f : (emitter.signal() ? 0.80f : 0.70f);
            float phaseOff = AtlasOverlayMath.smokePhaseOffset(emitter, period);
            int rgb = emitter.soul() ? soulRgb : warmRgb;
            for (int i = 0; i < puffCount; i++) {
                float cycle = AtlasOverlayMath.smokeCycle(timeSec, phaseOff, i, puffCount, period);
                float drift = cycle * driftMax;
                float sway = AtlasOverlayMath.smokeSwayUnit(timeSec, i, emitter.seed()) * (2.4f * swayScale) * cycle;
                float px = baseX + drift * 0.48f + sway;
                float py = baseY - drift * 0.92f;
                float radius = baseRadius * (0.55f + cycle * 1.45f);
                int alpha = Mth.clamp(Math.round(AtlasAtmosphere.sampleSmokeFade(cycle) * peakAlpha * 255.0f), 0, 255);
                if (alpha <= 2) {
                    continue;
                }
                this.batcher.addTexturedAxisQuad(
                        px - radius, py - radius, px + radius, py + radius,
                        0.0f, 0.0f, 1.0f, 1.0f, (alpha << 24) | rgb
                );
            }
        }
        this.batcher.flush();
    }

    private void drawWorldGrid(GuiGraphicsExtractor g, Font font, AtlasViewport vp, AtlasEditorState.Tool activeTool) {
        int width = vp.width();
        int height = vp.height();
        double topLeftX = vp.screenToWorldX(FRAME_PAD);
        double topLeftZ = vp.screenToWorldZ(FRAME_PAD);
        double bottomRightX = vp.screenToWorldX(width - FRAME_PAD);
        double bottomRightZ = vp.screenToWorldZ(height - FRAME_PAD);

        int step = vp.getGridStep();
        int majorStep = step * 4;
        int startX = Mth.floor(topLeftX / step) * step;
        int startZ = Mth.floor(topLeftZ / step) * step;
        int majorColor = AtlasLighting.tint(0x364A331C, 0x66CBD6EA);
        int minorColor = AtlasLighting.tint(0x1A4A331C, 0x33CBD6EA);
        int labelColor = AtlasLighting.tint(0x884A331C, 0xAACBD6EA);

        float minSx = FRAME_PAD;
        float maxSx = width - FRAME_PAD;
        float minSy = FRAME_PAD;
        float maxSy = height - FRAME_PAD;

        this.batcher.beginSolid(g);
        for (int wx = startX; wx <= bottomRightX; wx += step) {
            float fx = (float) vp.worldToScreenX(wx);
            boolean major = Math.floorMod(wx, majorStep) == 0;
            this.batcher.addAxisQuad(fx, minSy, fx + 1.0f, maxSy, major ? majorColor : minorColor);
        }
        for (int wz = startZ; wz <= bottomRightZ; wz += step) {
            float fy = (float) vp.worldToScreenY(wz);
            boolean major = Math.floorMod(wz, majorStep) == 0;
            this.batcher.addAxisQuad(minSx, fy, maxSx, fy + 1.0f, major ? majorColor : minorColor);
        }
        this.batcher.flush();

        int majorStartX = Math.floorDiv(startX + majorStep - 1, majorStep) * majorStep;
        if (Math.floorMod(startX, majorStep) == 0) {
            majorStartX = startX;
        }
        for (int wx = majorStartX; wx <= bottomRightX; wx += majorStep) {
            int sx = Math.round((float) vp.worldToScreenX(wx));
            String label = String.valueOf(wx);
            int tw = font.width(label);
            int textLeft = sx + 2;
            int textRight = sx + 4 + tw;
            boolean overPanel = textRight >= AtlasSidebar.X - 6 && textLeft <= AtlasSidebar.X + AtlasSidebar.WIDTH + 6;
            boolean overCompass = textRight >= width - 82 && textLeft <= width - 10;
            if (!overPanel && !overCompass && textLeft >= FRAME_PAD && textRight <= width - FRAME_PAD) {
                g.text(font, label, sx + 3, FRAME_PAD + 4, labelColor, false);
            }
        }

        int majorStartZ = Math.floorDiv(startZ + majorStep - 1, majorStep) * majorStep;
        if (Math.floorMod(startZ, majorStep) == 0) {
            majorStartZ = startZ;
        }
        for (int wz = majorStartZ; wz <= bottomRightZ; wz += majorStep) {
            int sy = Math.round((float) vp.worldToScreenY(wz));
            int labelX = FRAME_PAD + 5;
            if (sy < height - 46 && !AtlasSidebar.isMouseOver(labelX, sy, activeTool)) {
                String label = String.valueOf(wz);
                int labelY = sy + 2;
                if (!AtlasSidebar.overlapsPalette(labelX, labelY, font.width(label), font.lineHeight, activeTool)) {
                    g.text(font, label, labelX, labelY, labelColor, false);
                }
            }
        }
    }

    private void drawParchmentWeather(GuiGraphicsExtractor g, AtlasViewport vp, float partialTick) {
        float rain = AtlasAtmosphere.getRainFactor();
        float snow = AtlasAtmosphere.getSnowFactor();
        float flash = AtlasAtmosphere.getThunderFlash();
        if (rain <= 0.01f && snow <= 0.01f && flash <= 0.01f) {
            return;
        }
        int innerX = FRAME_PAD;
        int innerY = FRAME_PAD;
        int innerW = vp.width() - FRAME_PAD * 2;
        int innerH = vp.height() - FRAME_PAD * 2;

        if (rain > 0.01f) {
            int dampAlpha = Mth.clamp(Math.round(rain * 20.0f), 0, 26);
            int dampTop = (dampAlpha << 24) | 0x1D2836;
            int dampBand = Math.max(18, Math.min(48, innerH / 8));
            g.fillGradient(innerX, innerY, innerX + innerW, innerY + dampBand, dampTop, 0x001D2836);
            g.fillGradient(innerX, innerY + innerH - dampBand, innerX + innerW, innerY + innerH, 0x001D2836, dampTop);

            int dropRgb = AtlasLighting.tint(0xFFD6E0E8, 0xFF7B90A6) & 0xFFFFFF;
            int trailRgb = AtlasLighting.tint(0xFFC8D4DE, 0xFF6B8094) & 0xFFFFFF;
            float rainStrength = Mth.clamp(rain, 0.40f, 1.0f);
            AtlasAtmosphere.ParchmentDrop[] drops = AtlasAtmosphere.getDrops();

            this.batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getMoistureStreak());
            for (AtlasAtmosphere.ParchmentDrop drop : drops) {
                if (!drop.active || drop.trailCount <= 0) {
                    continue;
                }
                float curAge = Mth.lerp(partialTick, drop.prevAge, drop.age);
                float t = Mth.clamp(curAge / drop.maxAge, 0.0f, 1.0f);
                float fadeIn = Mth.clamp(curAge / 0.028f, 0.0f, 1.0f);
                float fadeOut = (1.0f - t) * (1.0f - t * 0.25f);
                float baseAlphaF = fadeIn * fadeOut * rainStrength;
                if (baseAlphaF <= 0.01f) {
                    continue;
                }
                float curU = Mth.lerp(partialTick, drop.prevU, drop.u);
                float curV = Mth.lerp(partialTick, drop.prevV, drop.v);
                float headX = innerX + curU * innerW;
                float headY = innerY + curV * innerH;
                int count = drop.trailCount;
                for (int i = 0; i < count; i++) {
                    float x0 = innerX + drop.trailU[i] * innerW;
                    float y0 = innerY + drop.trailV[i] * innerH;
                    float x1 = (i + 1 < count) ? (innerX + drop.trailU[i + 1] * innerW) : headX;
                    float y1 = (i + 1 < count) ? (innerY + drop.trailV[i + 1] * innerH) : headY;
                    float sdx = x1 - x0;
                    float sdy = y1 - y0;
                    float segLen = Mth.sqrt(sdx * sdx + sdy * sdy);
                    if (segLen < 0.6f) {
                        continue;
                    }
                    float nodeAge = curAge - drop.trailAge[i];
                    float dryFade = Mth.clamp(1.0f - nodeAge / 2.6f, 0.0f, 1.0f);
                    int segAlpha = Mth.clamp(Math.round(baseAlphaF * dryFade * 0.62f * 255.0f), 0, 155);
                    if (segAlpha <= 1) {
                        continue;
                    }
                    float trailWidth = Math.max(0.85f, drop.trailRad[i] * (0.36f + 0.24f * dryFade));
                    float mx = (x0 + x1) * 0.5f;
                    float my = (y0 + y1) * 0.5f;
                    float invLen = 1.0f / segLen;
                    float drawHalfLen = segLen * (0.5f / 0.84f);
                    this.batcher.addRotatedTexturedQuadCs(
                            mx, my, trailWidth, drawHalfLen,
                            sdy * invLen, -sdx * invLen,
                            0.0f, 0.0f, 1.0f, 1.0f, (segAlpha << 24) | trailRgb
                    );
                }
            }
            this.batcher.flush();

            this.batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, ParchmentTexture.getMoistureRing());
            for (AtlasAtmosphere.ParchmentDrop drop : drops) {
                if (!drop.active) {
                    continue;
                }
                float curAge = Mth.lerp(partialTick, drop.prevAge, drop.age);
                float t = Mth.clamp(curAge / drop.maxAge, 0.0f, 1.0f);
                float fadeIn = Mth.clamp(curAge / 0.028f, 0.0f, 1.0f);
                float fadeOut = (1.0f - t) * (1.0f - t * 0.25f);
                float baseAlphaF = fadeIn * fadeOut * rainStrength;
                if (baseAlphaF <= 0.01f) {
                    continue;
                }

                float curU = Mth.lerp(partialTick, drop.prevU, drop.u);
                float curV = Mth.lerp(partialTick, drop.prevV, drop.v);
                float headX = innerX + curU * innerW;
                float headY = innerY + curV * innerH;
                float impactX = innerX + drop.impactU * innerW;
                float impactY = innerY + drop.impactV * innerH;
                float impactRad = drop.initialRadius;

                float hitLife = Mth.clamp(1.0f - curAge / 1.8f, 0.0f, 1.0f);
                if (hitLife > 0.02f && drop.runner && curAge > drop.slipDelay) {
                    float spotRad = impactRad * (0.92f + (1.0f - hitLife) * 0.25f);
                    int spotAlpha = Mth.clamp(Math.round(fadeIn * hitLife * rainStrength * 0.45f * 255.0f), 0, 115);
                    if (spotAlpha > 1) {
                        float rx = spotRad * 1.10f;
                        float ry = spotRad * 1.05f;
                        this.batcher.addTexturedAxisQuad(
                                impactX - rx, impactY - ry, impactX + rx, impactY + ry,
                                0.0f, 0.0f, 1.0f, 1.0f, (spotAlpha << 24) | trailRgb
                        );
                    }
                }

                if (curAge < 0.36f) {
                    float speckLife = Mth.clamp(1.0f - curAge / 0.36f, 0.0f, 1.0f);
                    float burstT = Mth.clamp(curAge / 0.07f, 0.0f, 1.0f);
                    float burstEase = 1.0f - (1.0f - burstT) * (1.0f - burstT);
                    int speckCount = 2 + ((drop.seed >>> 4) & 1);
                    int speckAlpha = Mth.clamp(Math.round(fadeIn * speckLife * rainStrength * 0.52f * 255.0f), 0, 130);
                    if (speckAlpha > 1) {
                        int speckColor = (speckAlpha << 24) | dropRgb;
                        for (int s = 0; s < speckCount; s++) {
                            int sh = drop.seed * 31 + s * 7919;
                            float ang = ((sh & 0xFFFF) / 65535.0f) * Mth.TWO_PI;
                            float distMul = 0.85f + (((sh >>> 16) & 0xFF) / 255.0f) * 0.75f;
                            float speckRad = Math.max(0.8f, impactRad * (0.20f + (((sh >>> 24) & 0xFF) / 255.0f) * 0.14f));
                            float sx = impactX + Mth.cos(ang) * impactRad * distMul * burstEase;
                            float sy = impactY + Mth.sin(ang) * impactRad * distMul * burstEase;
                            this.batcher.addTexturedAxisQuad(
                                    sx - speckRad, sy - speckRad, sx + speckRad, sy + speckRad,
                                    0.0f, 0.0f, 1.0f, 1.0f, speckColor
                            );
                        }
                    }
                }

                float impactBounce;
                if (curAge < 0.055f) {
                    float k = curAge / 0.055f;
                    impactBounce = 0.52f + (1.16f - 0.52f) * (1.0f - (1.0f - k) * (1.0f - k));
                } else if (curAge < 0.16f) {
                    float k = (curAge - 0.055f) / 0.105f;
                    impactBounce = Mth.lerp(k, 1.16f, 1.0f);
                } else {
                    impactBounce = 1.0f;
                }

                int headAlpha = Mth.clamp(Math.round(baseAlphaF * 0.84f * 255.0f), 0, 210);
                if (headAlpha > 1) {
                    float rad = drop.radius * impactBounce * (1.0f + t * 0.10f);
                    float widthAspect = 0.90f + ((drop.seed & 0xFF) / 255.0f) * 0.20f;
                    float runStretch = drop.runner
                            ? (1.08f + Mth.clamp(drop.velV * 130.0f, 0.0f, 0.62f))
                            : (0.94f + ((drop.seed >>> 8 & 0xFF) / 255.0f) * 0.14f);
                    if (curAge < 0.10f) {
                        float flat = 1.0f - curAge / 0.10f;
                        widthAspect *= 1.0f + flat * 0.14f;
                        runStretch *= 1.0f - flat * 0.10f;
                    }
                    float rx = rad * 1.075f * widthAspect;
                    float ry = rad * 1.125f * runStretch;
                    this.batcher.addTexturedAxisQuad(
                            headX - rx, headY - ry, headX + rx, headY + ry,
                            0.0f, 0.0f, 1.0f, 1.0f, (headAlpha << 24) | dropRgb
                    );
                }
            }
            this.batcher.flush();
        }

        if (snow > 0.01f) {
            int frostAlpha = Mth.clamp(Math.round(snow * 52.0f), 0, 64);
            int frostTop = (frostAlpha << 24) | 0xDDECF7;
            int frostBand = Math.max(20, Math.min(54, innerH / 7));
            g.fillGradient(innerX, innerY, innerX + innerW, innerY + frostBand, frostTop, 0x00DDECF7);
            g.fillGradient(innerX, innerY + innerH - frostBand, innerX + innerW, innerY + innerH, 0x00DDECF7, frostTop);
        }

        if (flash > 0.01f) {
            int flashAlpha = Mth.clamp(Math.round(flash * 38.0f), 0, 48);
            int flashColor = (flashAlpha << 24) | 0xCFE2FF;
            int band = Math.max(18, Math.min(44, innerH / 8));
            g.fillGradient(innerX, innerY, innerX + innerW, innerY + band, flashColor, 0x00CFE2FF);
            g.fillGradient(innerX, innerY + innerH - band, innerX + innerW, innerY + innerH, 0x00CFE2FF, flashColor);
        }
    }

    private void drawParchmentVignette(GuiGraphicsExtractor g, AtlasViewport vp) {
        int left = FRAME_PAD;
        int top = FRAME_PAD;
        int right = vp.width() - FRAME_PAD;
        int bot = vp.height() - FRAME_PAD;
        int nightAlpha = Math.round(AtlasLighting.getNightFactor() * 85.0f);
        if (nightAlpha > 0) {
            g.fillGradient(left, top, right, top + 28, (nightAlpha << 24) | 0x080D1B, 0x00080D1B);
            g.fillGradient(left, bot - 28, right, bot, 0x00080D1B, (nightAlpha << 24) | 0x080D1B);
        }
        g.fillGradient(left, top, right, top + 20, 0x66291A0D, 0x00291A0D);
        g.fillGradient(left, bot - 22, right, bot, 0x00291A0D, 0x66291A0D);

        this.batcher.beginSolid(g);
        for (int i = 0; i < 14; i++) {
            int alpha = (14 - i) * 6;
            int col = (alpha << 24) | 0x291A0D;
            this.batcher.addAxisQuad(left + i, top, left + i + 1, bot, col);
            this.batcher.addAxisQuad(right - 1 - i, top, right - i, bot, col);
        }
        this.batcher.flush();
    }

    private void drawSignature(GuiGraphicsExtractor g, AtlasViewport vp) {
        float cx = vp.width() - FRAME_PAD - 25.0f;
        float cy = vp.height() - FRAME_PAD - 20.0f;
        this.batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, SIGNATURE_TEXTURE);
        this.batcher.addRotatedTexturedQuad(
                cx, cy, 26.0f, 13.0f, -(float) Math.PI / 8.0f,
                0.0f, 0.0f, 1.0f, 1.0f, 0xFF49382B
        );
        this.batcher.flush();
    }

    private void drawScaleRuler(GuiGraphicsExtractor g, Font font, AtlasViewport vp) {
        int blocks = vp.getGridStep();
        int length = Math.max(1, Math.round(blocks * vp.zoom()));
        int x = FRAME_PAD + 10;
        int y = vp.height() - FRAME_PAD - 18;
        int ink = AtlasLighting.tint(0xB94A331C, 0xB9CBD6EA);
        this.batcher.beginSolid(g);
        this.batcher.addAxisQuad(x, y, x + length + 1, y + 1, ink);
        this.batcher.addAxisQuad(x, y - 2, x + 1, y + 3, ink);
        this.batcher.addAxisQuad(x + length, y - 2, x + length + 1, y + 3, ink);
        this.batcher.flush();
        String label = blocks + " " + BLOCKS_LABEL.getString();
        g.text(font, label, x, y - 12, ink, false);
    }
}
