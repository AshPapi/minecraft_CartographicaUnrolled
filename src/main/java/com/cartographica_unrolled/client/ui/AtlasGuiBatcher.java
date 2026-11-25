package com.cartographica_unrolled.client.ui;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AtlasGuiBatcher {
    float[] xyuv = new float[1024 * 16];
    int[] colors = new int[1024];
    private int quadCount;
    private int batchStartQuad;
    private boolean texturedBatch;
    private @Nullable GuiGraphicsExtractor activeGraphics;
    private @Nullable RenderPipeline activePipeline;
    private @Nullable TextureSetup activeTextureSetup;
    private float minX;
    private float minY;
    private float maxX;
    private float maxY;

    private final List<Submission> submissionPool = new ArrayList<>(64);
    private int poolUsed;

    public void resetFrame() {
        this.quadCount = 0;
        this.batchStartQuad = 0;
        this.poolUsed = 0;
        this.activeGraphics = null;
        this.activePipeline = null;
        this.activeTextureSetup = null;
    }

    public void beginSolid(GuiGraphicsExtractor g) {
        if (this.activePipeline != null) {
            flush();
        }
        this.activeGraphics = g;
        this.activePipeline = RenderPipelines.GUI;
        this.activeTextureSetup = TextureSetup.noTexture();
        this.texturedBatch = false;
        this.batchStartQuad = this.quadCount;
        resetBounds();
    }

    public void beginTextured(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier textureId) {
        if (this.activePipeline != null) {
            flush();
        }
        AbstractTexture tex = Minecraft.getInstance().getTextureManager().getTexture(textureId);
        this.activeGraphics = g;
        this.activePipeline = pipeline;
        this.activeTextureSetup = TextureSetup.singleTexture(tex.getTextureView(), tex.getSampler());
        this.texturedBatch = true;
        this.batchStartQuad = this.quadCount;
        resetBounds();
    }

    public void submitTexturedQuad(
            GuiGraphicsExtractor g,
            RenderPipeline pipeline,
            Identifier textureId,
            float x0, float y0,
            float x1, float y1,
            float u0, float v0,
            float u1, float v1,
            int argb
    ) {
        beginTextured(g, pipeline, textureId);
        addTexturedAxisQuad(x0, y0, x1, y1, u0, v0, u1, v1, argb);
        flush();
    }

    public void addAxisQuad(float x0, float y0, float x1, float y1, int argb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int q = reserveQuad();
        int o = q << 4;
        float[] d = this.xyuv;
        d[o] = x0;
        d[o + 1] = y0;
        d[o + 4] = x0;
        d[o + 5] = y1;
        d[o + 8] = x1;
        d[o + 9] = y1;
        d[o + 12] = x1;
        d[o + 13] = y0;
        this.colors[q] = argb;
        expandBounds(x0, y0, x1, y1);
    }

    public void addTexturedAxisQuad(
            float x0, float y0,
            float x1, float y1,
            float u0, float v0,
            float u1, float v1,
            int argb
    ) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int q = reserveQuad();
        int o = q << 4;
        float[] d = this.xyuv;
        d[o] = x0;
        d[o + 1] = y0;
        d[o + 2] = u0;
        d[o + 3] = v0;
        d[o + 4] = x0;
        d[o + 5] = y1;
        d[o + 6] = u0;
        d[o + 7] = v1;
        d[o + 8] = x1;
        d[o + 9] = y1;
        d[o + 10] = u1;
        d[o + 11] = v1;
        d[o + 12] = x1;
        d[o + 13] = y0;
        d[o + 14] = u1;
        d[o + 15] = v0;
        this.colors[q] = argb;
        expandBounds(x0, y0, x1, y1);
    }

    public void addThickLine(float x0, float y0, float x1, float y1, float thickness, int argb) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = Mth.sqrt(dx * dx + dy * dy);
        if (!Float.isFinite(len) || len <= 1.0e-5f) {
            return;
        }
        float hw = thickness * 0.5f;
        float nx = -dy * hw / len;
        float ny = dx * hw / len;

        float vx0 = x0 - nx;
        float vy0 = y0 - ny;
        float vx1 = x0 + nx;
        float vy1 = y0 + ny;
        float vx2 = x1 + nx;
        float vy2 = y1 + ny;
        float vx3 = x1 - nx;
        float vy3 = y1 - ny;

        int q = reserveQuad();
        int o = q << 4;
        float[] d = this.xyuv;
        d[o] = vx0;
        d[o + 1] = vy0;
        d[o + 4] = vx1;
        d[o + 5] = vy1;
        d[o + 8] = vx2;
        d[o + 9] = vy2;
        d[o + 12] = vx3;
        d[o + 13] = vy3;
        this.colors[q] = argb;
        expandBoundsQuad(vx0, vy0, vx1, vy1, vx2, vy2, vx3, vy3);
    }

    public void addRotatedTexturedQuad(
            float cx, float cy,
            float halfW, float halfH,
            float angleRad,
            float u0, float v0,
            float u1, float v1,
            int argb
    ) {
        addRotatedTexturedQuadCs(
                cx, cy, halfW, halfH,
                Mth.cos(angleRad), Mth.sin(angleRad),
                u0, v0, u1, v1, argb
        );
    }

    public void addRotatedTexturedQuadCs(
            float cx, float cy,
            float halfW, float halfH,
            float cos, float sin,
            float u0, float v0,
            float u1, float v1,
            int argb
    ) {
        if (halfW <= 0.0f || halfH <= 0.0f) {
            return;
        }
        float ux = halfW * cos;
        float uy = halfW * sin;
        float vx = -halfH * sin;
        float vy = halfH * cos;

        float vx0 = cx - ux - vx;
        float vy0 = cy - uy - vy;
        float vx1 = cx - ux + vx;
        float vy1 = cy - uy + vy;
        float vx2 = cx + ux + vx;
        float vy2 = cy + uy + vy;
        float vx3 = cx + ux - vx;
        float vy3 = cy + uy - vy;

        int q = reserveQuad();
        int o = q << 4;
        float[] d = this.xyuv;
        d[o] = vx0;
        d[o + 1] = vy0;
        d[o + 2] = u0;
        d[o + 3] = v0;
        d[o + 4] = vx1;
        d[o + 5] = vy1;
        d[o + 6] = u0;
        d[o + 7] = v1;
        d[o + 8] = vx2;
        d[o + 9] = vy2;
        d[o + 10] = u1;
        d[o + 11] = v1;
        d[o + 12] = vx3;
        d[o + 13] = vy3;
        d[o + 14] = u1;
        d[o + 15] = v0;
        this.colors[q] = argb;
        expandBoundsQuad(vx0, vy0, vx1, vy1, vx2, vy2, vx3, vy3);
    }

    public void flush() {
        GuiGraphicsExtractor g = this.activeGraphics;
        RenderPipeline pipeline = this.activePipeline;
        TextureSetup texSetup = this.activeTextureSetup;
        int start = this.batchStartQuad;
        int end = this.quadCount;

        this.activeGraphics = null;
        this.activePipeline = null;
        this.activeTextureSetup = null;
        this.batchStartQuad = end;

        if (g == null || pipeline == null || texSetup == null || end <= start) {
            return;
        }
        int bx0 = Mth.floor(this.minX);
        int by0 = Mth.floor(this.minY);
        int bx1 = Mth.ceil(this.maxX);
        int by1 = Mth.ceil(this.maxY);
        if (bx1 <= bx0 || by1 <= by0) {
            return;
        }
        ScreenRectangle rawBounds = new ScreenRectangle(bx0, by0, bx1 - bx0, by1 - by0);
        ScreenRectangle scissor = g.peekScissorStack();
        ScreenRectangle finalBounds = scissor != null ? rawBounds.intersection(scissor) : rawBounds;
        if (finalBounds == null) {
            return;
        }
        Submission sub = acquireSubmission();
        sub.init(pipeline, texSetup, scissor, finalBounds, start, end, this.texturedBatch);
        g.submitGuiElementRenderState(sub);
    }

    private void resetBounds() {
        this.minX = Float.POSITIVE_INFINITY;
        this.minY = Float.POSITIVE_INFINITY;
        this.maxX = Float.NEGATIVE_INFINITY;
        this.maxY = Float.NEGATIVE_INFINITY;
    }

    private void expandBounds(float x0, float y0, float x1, float y1) {
        if (x0 < this.minX) this.minX = x0;
        if (y0 < this.minY) this.minY = y0;
        if (x1 > this.maxX) this.maxX = x1;
        if (y1 > this.maxY) this.maxY = y1;
    }

    private void expandBoundsQuad(
            float x0, float y0,
            float x1, float y1,
            float x2, float y2,
            float x3, float y3
    ) {
        float qMinX = Math.min(Math.min(x0, x1), Math.min(x2, x3));
        float qMaxX = Math.max(Math.max(x0, x1), Math.max(x2, x3));
        float qMinY = Math.min(Math.min(y0, y1), Math.min(y2, y3));
        float qMaxY = Math.max(Math.max(y0, y1), Math.max(y2, y3));
        expandBounds(qMinX, qMinY, qMaxX, qMaxY);
    }

    private int reserveQuad() {
        int q = this.quadCount;
        if (q >= this.colors.length) {
            int nextCap = this.colors.length * 2;
            this.colors = Arrays.copyOf(this.colors, nextCap);
            this.xyuv = Arrays.copyOf(this.xyuv, nextCap * 16);
        }
        this.quadCount = q + 1;
        return q;
    }

    private Submission acquireSubmission() {
        if (this.poolUsed < this.submissionPool.size()) {
            return this.submissionPool.get(this.poolUsed++);
        }
        Submission created = new Submission(this);
        this.submissionPool.add(created);
        this.poolUsed++;
        return created;
    }

    private static final class Submission implements GuiElementRenderState {
        private final AtlasGuiBatcher owner;
        private RenderPipeline pipeline;
        private TextureSetup textureSetup;
        private @Nullable ScreenRectangle scissorArea;
        private @Nullable ScreenRectangle bounds;
        private int startQuad;
        private int endQuad;
        private boolean textured;

        Submission(AtlasGuiBatcher owner) {
            this.owner = owner;
            this.pipeline = RenderPipelines.GUI;
            this.textureSetup = TextureSetup.noTexture();
        }

        void init(
                RenderPipeline pipeline,
                TextureSetup textureSetup,
                @Nullable ScreenRectangle scissorArea,
                ScreenRectangle bounds,
                int startQuad,
                int endQuad,
                boolean textured
        ) {
            this.pipeline = pipeline;
            this.textureSetup = textureSetup;
            this.scissorArea = scissorArea;
            this.bounds = bounds;
            this.startQuad = startQuad;
            this.endQuad = endQuad;
            this.textured = textured;
        }

        @Override
        public void buildVertices(VertexConsumer vc) {
            float[] d = this.owner.xyuv;
            int[] cols = this.owner.colors;
            if (this.textured) {
                for (int q = this.startQuad; q < this.endQuad; q++) {
                    int o = q << 4;
                    int c = cols[q];
                    vc.addVertex(d[o], d[o + 1], 0.0f).setUv(d[o + 2], d[o + 3]).setColor(c);
                    vc.addVertex(d[o + 4], d[o + 5], 0.0f).setUv(d[o + 6], d[o + 7]).setColor(c);
                    vc.addVertex(d[o + 8], d[o + 9], 0.0f).setUv(d[o + 10], d[o + 11]).setColor(c);
                    vc.addVertex(d[o + 12], d[o + 13], 0.0f).setUv(d[o + 14], d[o + 15]).setColor(c);
                }
            } else {
                for (int q = this.startQuad; q < this.endQuad; q++) {
                    int o = q << 4;
                    int c = cols[q];
                    vc.addVertex(d[o], d[o + 1], 0.0f).setColor(c);
                    vc.addVertex(d[o + 4], d[o + 5], 0.0f).setColor(c);
                    vc.addVertex(d[o + 8], d[o + 9], 0.0f).setColor(c);
                    vc.addVertex(d[o + 12], d[o + 13], 0.0f).setColor(c);
                }
            }
        }

        @Override
        public RenderPipeline pipeline() {
            return this.pipeline;
        }

        @Override
        public TextureSetup textureSetup() {
            return this.textureSetup;
        }

        @Override
        public @Nullable ScreenRectangle scissorArea() {
            return this.scissorArea;
        }

        @Override
        public @Nullable ScreenRectangle bounds() {
            return this.bounds;
        }
    }
}
