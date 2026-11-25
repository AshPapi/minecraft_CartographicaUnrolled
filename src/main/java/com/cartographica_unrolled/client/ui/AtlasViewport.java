package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.client.render.AtlasSurfaceRenderer;
import com.cartographica_unrolled.client.state.AtlasStateManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

public final class AtlasViewport {
    public static final float MIN_ZOOM = 0.15f;
    public static final float MAX_ZOOM = 6.0f;
    public static final int FRAME_PAD = 12;
    private static final int[] GRID_STEPS = {16, 32, 64, 128, 256, 512};

    private int width;
    private int height;
    private double panX;
    private double panZ;
    private double targetPanX;
    private double targetPanZ;
    private boolean smoothPanning;
    private float zoom = 1.25f;
    private float targetZoom = 1.25f;
    private boolean smoothZooming;
    private double zoomMouseX;
    private double zoomMouseY;
    private double zoomWorldX;
    private double zoomWorldZ;
    private long lastRenderNanos;
    private boolean viewInitialized;

    public void initForScreen(int screenWidth, int screenHeight, @Nullable LocalPlayer player) {
        this.width = screenWidth;
        this.height = screenHeight;
        if (player != null && !this.viewInitialized) {
            this.panX = player.getX();
            this.panZ = player.getZ();
            this.targetPanX = this.panX;
            this.targetPanZ = this.panZ;
            this.zoom = Mth.clamp(
                    Math.max(64.0f, screenWidth - FRAME_PAD * 2.0f) / AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS,
                    MIN_ZOOM,
                    MAX_ZOOM
            );
            this.targetZoom = this.zoom;
            this.viewInitialized = true;
        }
    }

    public void saveLastViewState() {
        float blocksAcross = Math.max(64.0f, this.width - FRAME_PAD * 2.0f) / Math.max(MIN_ZOOM, this.zoom);
        AtlasStateManager.setLastView(this.panX, this.panZ, blocksAcross);
    }

    public void stepAnimation() {
        long now = System.nanoTime();
        float dt = this.lastRenderNanos == 0L
                ? (1.0f / 60.0f)
                : (float) ((now - this.lastRenderNanos) / 1_000_000_000.0);
        this.lastRenderNanos = now;
        dt = Mth.clamp(dt, 0.0005f, 0.1f);
        float factor = 1.0f - (float) Math.exp(-18.0f * dt);

        this.zoom = Mth.lerp(factor, this.zoom, this.targetZoom);
        if (this.smoothZooming) {
            this.panX = this.zoomWorldX - (this.zoomMouseX - this.width / 2.0) / this.zoom;
            this.panZ = this.zoomWorldZ - (this.zoomMouseY - this.height / 2.0) / this.zoom;
            this.targetPanX = this.panX;
            this.targetPanZ = this.panZ;
            if (Math.abs(this.zoom - this.targetZoom) < 0.0005f) {
                this.zoom = this.targetZoom;
                this.panX = this.zoomWorldX - (this.zoomMouseX - this.width / 2.0) / this.zoom;
                this.panZ = this.zoomWorldZ - (this.zoomMouseY - this.height / 2.0) / this.zoom;
                this.targetPanX = this.panX;
                this.targetPanZ = this.panZ;
                this.smoothZooming = false;
            }
        } else if (this.smoothPanning) {
            this.panX = Mth.lerp(factor, this.panX, this.targetPanX);
            this.panZ = Mth.lerp(factor, this.panZ, this.targetPanZ);
            if (Math.hypot(this.panX - this.targetPanX, this.panZ - this.targetPanZ) < 0.08) {
                this.panX = this.targetPanX;
                this.panZ = this.targetPanZ;
                this.smoothPanning = false;
            }
        }
    }

    public void onScrollZoom(double mouseX, double mouseY, double scrollY) {
        this.zoomMouseX = mouseX;
        this.zoomMouseY = mouseY;
        this.zoomWorldX = screenToWorldX(mouseX);
        this.zoomWorldZ = screenToWorldZ(mouseY);

        float factor = scrollY > 0.0 ? 1.16f : (1.0f / 1.16f);
        this.targetZoom = Mth.clamp(this.targetZoom * factor, MIN_ZOOM, MAX_ZOOM);
        this.smoothZooming = true;
        this.smoothPanning = false;
    }

    public void stopSmoothing() {
        this.smoothPanning = false;
        this.smoothZooming = false;
    }

    public void dragBy(double dragX, double dragY) {
        this.panX -= dragX / this.zoom;
        this.panZ -= dragY / this.zoom;
        this.targetPanX = this.panX;
        this.targetPanZ = this.panZ;
    }

    public void panSmoothlyTo(double wx, double wz) {
        this.targetPanX = wx;
        this.targetPanZ = wz;
        this.smoothPanning = true;
        this.smoothZooming = false;
    }

    public double screenToWorldX(double screenX) {
        return this.panX + (screenX - this.width / 2.0) / this.zoom;
    }

    public double screenToWorldZ(double screenY) {
        return this.panZ + (screenY - this.height / 2.0) / this.zoom;
    }

    public double worldToScreenX(double worldX) {
        return this.width / 2.0 + (worldX - this.panX) * this.zoom;
    }

    public double worldToScreenY(double worldZ) {
        return this.height / 2.0 + (worldZ - this.panZ) * this.zoom;
    }

    public int getGridStep() {
        int step = 16;
        for (int value : GRID_STEPS) {
            step = value;
            if (value * this.zoom >= 50.0f) {
                break;
            }
        }
        return step;
    }

    public float getMarkerScale() {
        return Mth.clamp((float) Math.sqrt(this.zoom / 1.25f), 0.65f, 1.35f);
    }

    public float zoom() {
        return this.zoom;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }
}
