package com.cartographica_unrolled.client.atmosphere;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.util.AtlasMath;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;

public class ParchmentTexture {
    public static final Identifier CUSTOM_PARCHMENT =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/parchment.png");
    public static final Identifier PROCEDURAL_PARCHMENT =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/parchment");
    public static final Identifier SOLID_WHITE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/solid_white");

    private static final float[] PAPER_GRAIN = new float[256 * 256];

    static {
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                PAPER_GRAIN[y * 256 + x] = Mth.clamp((float) (paperDelta(x, y) / 228.0), -0.07f, 0.07f);
            }
        }
    }

    private static Identifier activeTexture;
    private static Identifier whiteTexture;
    private static Identifier circleTexture;
    private static Identifier eraserCursorTexture;
    private static Identifier playerArrowTexture;
    private static Identifier otherPlayerArrowTexture;
    private static Identifier deathSkullTexture;
    private static Identifier smokePuffTexture;
    private static Identifier moistureRingTexture;
    private static Identifier moistureStreakTexture;
    private static Identifier compassLettersLight;

    public static void invalidateCache() {
        var textures = Minecraft.getInstance().getTextureManager();
        if (PROCEDURAL_PARCHMENT.equals(activeTexture)) textures.release(activeTexture);
        if (whiteTexture != null) textures.release(whiteTexture);
        if (circleTexture != null) textures.release(circleTexture);
        if (eraserCursorTexture != null) textures.release(eraserCursorTexture);
        if (playerArrowTexture != null) textures.release(playerArrowTexture);
        if (otherPlayerArrowTexture != null) textures.release(otherPlayerArrowTexture);
        if (deathSkullTexture != null) textures.release(deathSkullTexture);
        if (smokePuffTexture != null) textures.release(smokePuffTexture);
        if (moistureRingTexture != null) textures.release(moistureRingTexture);
        if (moistureStreakTexture != null) textures.release(moistureStreakTexture);
        if (compassLettersLight != null) textures.release(compassLettersLight);
        activeTexture = null;
        whiteTexture = null;
        circleTexture = null;
        eraserCursorTexture = null;
        playerArrowTexture = null;
        otherPlayerArrowTexture = null;
        deathSkullTexture = null;
        smokePuffTexture = null;
        moistureRingTexture = null;
        moistureStreakTexture = null;
        compassLettersLight = null;
    }

    private static void registerDefault(Identifier id, String label, NativeImage image) {
        try {
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> label, image));
        } catch (Throwable t) {
            image.close();
            throw t;
        }
    }

    private static void registerLinearClamped(Identifier id, String label, NativeImage image) {
        try {
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> label, image) {
                {
                    this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
                }
            });
        } catch (Throwable t) {
            image.close();
            throw t;
        }
    }

    public static Identifier get() {
        if (activeTexture != null) {
            return activeTexture;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getResourceManager().getResource(CUSTOM_PARCHMENT).isPresent()) {
            activeTexture = CUSTOM_PARCHMENT;
            return activeTexture;
        }

        int size = 256;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double delta = paperDelta(x, y);
                int r = clamp(228 + (int) Math.round(delta));
                int g = clamp(210 + (int) Math.round(delta * 0.92));
                int b = clamp(173 + (int) Math.round(delta * 0.80));
                image.setPixel(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
            }
        }
        registerDefault(PROCEDURAL_PARCHMENT, "cartographica_parchment", image);
        activeTexture = PROCEDURAL_PARCHMENT;
        return activeTexture;
    }

    public static float samplePaperGrain(int x, int y) {
        return PAPER_GRAIN[(y & 255) * 256 + (x & 255)];
    }

    private static double paperDelta(int x, int y) {
        double freq1 = (2.0 * Math.PI) / 256.0;
        double cloud = Math.sin(x * freq1 * 2.0 + 0.7) * Math.cos(y * freq1 * 2.0 - 0.4) * 5.8
                + Math.sin((x + y) * freq1 * 3.0) * 3.4
                + Math.cos((x - y * 2) * freq1 * 5.0) * 2.2
                + Math.sin((x * 3 - y) * freq1 * 4.0 + 1.1) * 1.4;
        double fiberH = Math.sin(y * freq1 * 48.0 + Math.sin(x * freq1 * 4.0) * 1.5) * 1.7
                + Math.sin(y * freq1 * 96.0 + Math.cos(x * freq1 * 7.0) * 0.9) * 0.55;
        double fiberV = Math.cos(x * freq1 * 44.0 + Math.cos(y * freq1 * 3.0) * 1.4) * 1.3
                + Math.cos(x * freq1 * 88.0 + Math.sin(y * freq1 * 6.0) * 0.8) * 0.45;
        int grain = ((hashTile(x & 255, y & 255, 1337) & 15) - 7) / 2;
        int speckRoll = hashTile(x & 255, y & 255, 7919) & 1023;
        int speck = speckRoll == 0 ? -15 : speckRoll == 1 ? -9 : speckRoll == 2 ? 6 : 0;
        return cloud + fiberH + fiberV + grain + speck;
    }

    public static Identifier getWhite() {
        if (whiteTexture != null) {
            return whiteTexture;
        }
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, 4, 4, false);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.setPixel(x, y, 0xFFFFFFFF);
            }
        }
        registerDefault(SOLID_WHITE, "cartographica_white", image);
        whiteTexture = SOLID_WHITE;
        return whiteTexture;
    }

    public static Identifier getCircle() {
        if (circleTexture == null) {
            int size = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = x + 0.5f - size * 0.5f;
                    float dy = y + 0.5f - size * 0.5f;
                    float alpha = Mth.clamp(size * 0.5f - Mth.sqrt(dx * dx + dy * dy), 0.0f, 1.0f);
                    image.setPixel(x, y, (Math.round(alpha * 255.0f) << 24) | 0xFFFFFF);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/stroke_circle");
            registerLinearClamped(id, "cartographica_stroke_circle", image);
            circleTexture = id;
        }
        return circleTexture;
    }

    public static @Nullable Identifier getCompassLettersLight() {
        if (compassLettersLight != null) {
            return compassLettersLight;
        }
        Minecraft mc = Minecraft.getInstance();
        Identifier sourceId = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/compass_letters.png");
        var resource = mc.getResourceManager().getResource(sourceId);
        if (resource.isEmpty()) {
            return null;
        }
        try (InputStream stream = resource.get().open(); NativeImage source = NativeImage.read(stream)) {
            if (source.format() != NativeImage.Format.RGBA) {
                return null;
            }
            NativeImage mask = new NativeImage(NativeImage.Format.RGBA, source.getWidth(), source.getHeight(), true);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    mask.setPixel(x, y, (source.getPixel(x, y) & 0xFF000000) | 0xFFFFFF);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/compass_letters_light");
            registerLinearClamped(id, "atlas_compass_letters_light", mask);
            compassLettersLight = id;
            return compassLettersLight;
        } catch (IOException e) {
            return null;
        }
    }

    public static Identifier getEraserCursor() {
        if (eraserCursorTexture == null) {
            int size = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, false);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float dx = x + 0.5f - size * 0.5f;
                    float dy = y + 0.5f - size * 0.5f;
                    float distance = Mth.sqrt(dx * dx + dy * dy);
                    float alpha = Mth.clamp(Math.min(32.0f - distance, distance - 27.5f), 0.0f, 1.0f);
                    image.setPixel(x, y, (Math.round(alpha * 220.0f) << 24) | 0x241D18);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/eraser_cursor");
            registerLinearClamped(id, "cartographica_eraser_cursor", image);
            eraserCursorTexture = id;
        }
        return eraserCursorTexture;
    }

    public static Identifier getPlayerArrow() {
        if (playerArrowTexture == null) {
            playerArrowTexture = createArrowTexture("dynamic/player_arrow", "cartographica_player_arrow", 0x221309, 0xD13B2E, 0xA82218);
        }
        return playerArrowTexture;
    }

    public static Identifier getOtherPlayerArrow() {
        if (otherPlayerArrowTexture == null) {
            otherPlayerArrowTexture = createArrowTexture("dynamic/other_player_arrow", "cartographica_other_player_arrow", 0x1E140B, 0x4C88A8, 0x2F5F7D);
        }
        return otherPlayerArrowTexture;
    }

    private static Identifier createArrowTexture(String idPath, String label, int borderRgb, int leftRgb, int rightRgb) {
        int size = 128;
        NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, true);
        float[] xs = {0.0f, -0.95f, 0.0f, 0.95f};
        float[] ys = {1.45f, -0.90f, -0.30f, -0.90f};
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float px = (x + 0.5f - size * 0.5f) * 3.2f / size;
                float py = (y + 0.5f - size * 0.5f) * 3.2f / size;
                boolean inside = false;
                float edgeDistanceSq = Float.MAX_VALUE;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    if ((ys[i] > py) != (ys[j] > py)
                            && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) inside = !inside;
                    float dx = xs[j] - xs[i];
                    float dy = ys[j] - ys[i];
                    float t = Mth.clamp(((px - xs[i]) * dx + (py - ys[i]) * dy) / (dx * dx + dy * dy), 0.0f, 1.0f);
                    float ex = px - (xs[i] + t * dx);
                    float ey = py - (ys[i] + t * dy);
                    edgeDistanceSq = Math.min(edgeDistanceSq, ex * ex + ey * ey);
                }
                float signed = (inside ? 1.0f : -1.0f) * (float) Math.sqrt(edgeDistanceSq);
                float t = Mth.clamp((signed + 0.075f) * size / (3.2f * 1.5f) + 0.5f, 0.0f, 1.0f);
                int alpha = Math.round(t * t * (3.0f - 2.0f * t) * 255.0f);
                int color = signed < 0.075f ? borderRgb : px < 0.0f ? leftRgb : rightRgb;
                image.setPixel(x, y, (alpha << 24) | color);
            }
        }
        Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, idPath);
        registerLinearClamped(id, label, image);
        return id;
    }

    public static Identifier getDeathSkull() {
        if (deathSkullTexture == null) {
            int size = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float nx = (x + 0.5f - size * 0.5f) / (size * 0.5f);
                    float ny = (y + 0.5f - size * 0.5f) / (size * 0.5f);
                    float cranium = (float) Math.hypot(nx / 0.50f, (ny + 0.14f) / 0.44f) - 1.0f;
                    float jaw = Math.max(Math.abs(nx) - 0.25f, Math.abs(ny - 0.34f) - 0.20f);
                    float skull = Math.min(cranium * 0.44f, jaw);
                    float boneA = distToSegment(nx, ny, -0.68f, -0.52f, 0.68f, 0.62f) - 0.075f;
                    float boneB = distToSegment(nx, ny, 0.68f, -0.52f, -0.68f, 0.62f) - 0.075f;
                    float bones = Math.min(boneA, boneB);
                    float shape = Math.min(skull, bones);

                    float leftEye = (float) Math.hypot((nx + 0.17f) / 0.12f, (ny + 0.10f) / 0.13f);
                    float rightEye = (float) Math.hypot((nx - 0.17f) / 0.12f, (ny + 0.10f) / 0.13f);
                    float nose = (float) Math.hypot(nx / 0.06f, (ny - 0.09f) / 0.09f);
                    boolean toothGap = ny > 0.28f && ny < 0.50f && (Math.abs(nx) < 0.03f || Math.abs(Math.abs(nx) - 0.12f) < 0.025f);

                    float aa = Mth.clamp((-shape + 0.055f) * 26.0f, 0.0f, 1.0f);
                    int alpha = Math.round(aa * aa * (3.0f - 2.0f * aa) * 255.0f);
                    if (alpha <= 0) {
                        image.setPixel(x, y, 0);
                        continue;
                    }
                    boolean ink = shape > -0.045f || (skull <= 0.0f && (leftEye < 1.0f || rightEye < 1.0f || nose < 1.0f || toothGap));
                    int color;
                    if (ink) {
                        color = 0x2B170E;
                    } else if (skull <= 0.0f) {
                        color = (ny < -0.10f && nx < 0.08f) ? 0xEFE1C2 : 0xD8C29C;
                    } else {
                        color = 0xC8B08A;
                    }
                    image.setPixel(x, y, (alpha << 24) | color);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/death_skull");
            registerLinearClamped(id, "cartographica_death_skull", image);
            deathSkullTexture = id;
        }
        return deathSkullTexture;
    }

    public static Identifier getSmokePuff() {
        if (smokePuffTexture == null) {
            int size = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float nx = (x + 0.5f - size * 0.5f) / (size * 0.5f);
                    float ny = (y + 0.5f - size * 0.5f) / (size * 0.5f);
                    float d1 = (float) Math.hypot(nx - 0.06f, ny + 0.04f);
                    float d2 = (float) Math.hypot(nx + 0.22f, ny - 0.08f);
                    float d3 = (float) Math.hypot(nx - 0.20f, ny - 0.16f);
                    float d4 = (float) Math.hypot(nx + 0.05f, ny + 0.22f);
                    float p = Math.max(
                            Math.max(
                                    Mth.clamp(1.0f - d1 * 1.02f, 0.0f, 1.0f),
                                    Mth.clamp(1.0f - d2 * 1.18f, 0.0f, 1.0f) * 0.92f),
                            Math.max(
                                    Mth.clamp(1.0f - d3 * 1.24f, 0.0f, 1.0f) * 0.86f,
                                    Mth.clamp(1.0f - d4 * 1.28f, 0.0f, 1.0f) * 0.82f));
                    float a = AtlasMath.smoothstep((float) Math.pow(p, 0.72));
                    int alpha = Mth.clamp(Math.round(a * 245.0f), 0, 245);
                    float shade = Mth.clamp(0.84f + (1.0f - ny) * 0.10f + p * 0.10f, 0.78f, 1.0f);
                    int ch = Mth.clamp(Math.round(shade * 255.0f), 0, 255);
                    image.setPixel(x, y, (alpha << 24) | (ch << 16) | (ch << 8) | ch);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/smoke_puff");
            registerLinearClamped(id, "cartographica_smoke_puff", image);
            smokePuffTexture = id;
        }
        return smokePuffTexture;
    }

    public static Identifier getMoistureRing() {
        if (moistureRingTexture == null) {
            int size = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, size, size, true);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float nx = (x + 0.5f - size * 0.5f) / (size * 0.5f);
                    float ny = (y + 0.5f - size * 0.5f) / (size * 0.5f);
                    float taper = ny < 0.0f ? (1.0f - ny * 0.42f) : (1.0f + ny * 0.06f);
                    float sx = nx * taper;
                    float sy = (ny - 0.06f) * 0.90f;
                    float ang = (float) Math.atan2(sy, sx);
                    float wobble = 0.032f * Mth.sin(ang * 2.0f + 0.8f)
                            + 0.020f * Mth.cos(ang * 3.0f - 0.5f)
                            + 0.012f * Mth.sin(ang * 5.0f + 1.4f);
                    float r = (float) Math.hypot(sx, sy) + wobble;

                    float body = smoothstep(0.88f, 0.30f, r);
                    float softEdge = smoothstep(0.98f, 0.48f, r);
                    float blurInner = (float) Math.exp(-(r * r) / (0.50f * 0.50f));
                    float soakRim = (float) Math.exp(-Math.pow((r - 0.62f) / 0.22f, 2.0)) * softEdge;
                    float belly = smoothstep(-0.20f, 0.65f, ny) * body;

                    float outA = Mth.clamp(body * 0.34f + soakRim * 0.26f + belly * 0.14f, 0.0f, 0.62f);
                    float haze = Mth.clamp(blurInner * 0.55f + belly * 0.18f - soakRim * 0.35f, 0.0f, 0.60f);
                    float outR = Mth.lerp(haze, 54.0f / 255.0f, 152.0f / 255.0f);
                    float outG = Mth.lerp(haze, 66.0f / 255.0f, 168.0f / 255.0f);
                    float outB = Mth.lerp(haze, 80.0f / 255.0f, 182.0f / 255.0f);

                    int ir = clamp(Math.round(outR * 255.0f));
                    int ig = clamp(Math.round(outG * 255.0f));
                    int ib = clamp(Math.round(outB * 255.0f));
                    int ia = clamp(Math.round(outA * 255.0f));
                    image.setPixel(x, y, ia <= 0 ? 0 : (ia << 24) | (ir << 16) | (ig << 8) | ib);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/moisture_ring");
            registerLinearClamped(id, "cartographica_moisture_ring", image);
            moistureRingTexture = id;
        }
        return moistureRingTexture;
    }

    public static Identifier getMoistureStreak() {
        if (moistureStreakTexture == null) {
            int w = 32;
            int h = 64;
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, true);
            for (int y = 0; y < h; y++) {
                float ny = (y + 0.5f) / h;
                float endFade = 1.0f;
                if (ny < 0.15f) {
                    endFade = ny / 0.15f;
                } else if (ny > 0.85f) {
                    endFade = (1.0f - ny) / 0.15f;
                }
                for (int x = 0; x < w; x++) {
                    float nx = (x + 0.5f - w * 0.5f) / (w * 0.5f);
                    float ax = Math.abs(nx);
                    float lateral = smoothstep(0.95f, 0.10f, ax);
                    float core = (float) Math.exp(-(ax * ax) / (0.38f * 0.38f));
                    float edge = (float) Math.exp(-Math.pow((ax - 0.52f) / 0.24f, 2.0)) * lateral;
                    float outA = Mth.clamp(endFade * (core * 0.26f + edge * 0.14f), 0.0f, 0.38f);
                    float haze = Mth.clamp(core * 0.52f - edge * 0.25f, 0.0f, 0.50f);
                    float outR = Mth.lerp(haze, 58.0f / 255.0f, 146.0f / 255.0f);
                    float outG = Mth.lerp(haze, 70.0f / 255.0f, 162.0f / 255.0f);
                    float outB = Mth.lerp(haze, 84.0f / 255.0f, 176.0f / 255.0f);
                    int ir = clamp(Math.round(outR * 255.0f));
                    int ig = clamp(Math.round(outG * 255.0f));
                    int ib = clamp(Math.round(outB * 255.0f));
                    int ia = clamp(Math.round(outA * 255.0f));
                    image.setPixel(x, y, ia <= 0 ? 0 : (ia << 24) | (ir << 16) | (ig << 8) | ib);
                }
            }
            Identifier id = Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/moisture_streak");
            registerLinearClamped(id, "cartographica_moisture_streak", image);
            moistureStreakTexture = id;
        }
        return moistureStreakTexture;
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        return AtlasMath.smoothstep((x - edge0) / (edge1 - edge0));
    }

    private static float distToSegment(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax;
        float dy = by - ay;
        float t = Mth.clamp(((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy), 0.0f, 1.0f);
        float ex = px - (ax + t * dx);
        float ey = py - (ay + t * dy);
        return (float) Math.hypot(ex, ey);
    }

    private static int hashTile(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    private static int clamp(int val) {
        return Mth.clamp(val, 0, 255);
    }
}
