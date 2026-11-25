package com.cartographica_unrolled.util;

import net.minecraft.util.Mth;

public final class AtlasMath {
    public static final int PARCHMENT_TRANSPARENT = 0x00E4D2AD;
    public static final int PARCHMENT_WARM = 0xE6D6B5;

    private AtlasMath() {
    }

    public static float smoothstep(float t) {
        float x = Mth.clamp(t, 0.0f, 1.0f);
        return x * x * (3.0f - 2.0f * x);
    }

    public static float easeOutCubic(float t) {
        float u = 1.0f - Mth.clamp(t, 0.0f, 1.0f);
        return 1.0f - u * u * u;
    }

    public static int mixRgb(int a, int b, float t) {
        int ar = (a >>> 16) & 0xFF;
        int ag = (a >>> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF;
        int bg = (b >>> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = Mth.clamp(Math.round(ar + (br - ar) * t), 0, 255);
        int g = Mth.clamp(Math.round(ag + (bg - ag) * t), 0, 255);
        int bl = Mth.clamp(Math.round(ab + (bb - ab) * t), 0, 255);
        return (r << 16) | (g << 8) | bl;
    }

    public static int scaleRgb(int rgb, float factor) {
        int r = Mth.clamp(Math.round(((rgb >>> 16) & 0xFF) * factor), 0, 255);
        int g = Mth.clamp(Math.round(((rgb >>> 8) & 0xFF) * factor), 0, 255);
        int b = Mth.clamp(Math.round((rgb & 0xFF) * factor), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    public static int multiplyRgb(int a, int b) {
        int r = (((a >>> 16) & 0xFF) * ((b >>> 16) & 0xFF)) / 255;
        int g = (((a >>> 8) & 0xFF) * ((b >>> 8) & 0xFF)) / 255;
        int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
        return (r << 16) | (g << 8) | bl;
    }

    public static int multiplyArgbRgb(int argb, int tintRgb) {
        return (argb & 0xFF000000) | multiplyRgb(argb, tintRgb);
    }

    public static int adjustSaturation(int rgb, float sat) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        float luma = r * 0.299f + g * 0.587f + b * 0.114f;
        int nr = Mth.clamp(Math.round(luma + (r - luma) * sat), 0, 255);
        int ng = Mth.clamp(Math.round(luma + (g - luma) * sat), 0, 255);
        int nb = Mth.clamp(Math.round(luma + (b - luma) * sat), 0, 255);
        return (nr << 16) | (ng << 8) | nb;
    }

    public static int harmonizeRgb(int rgb, float sat, float brightness) {
        return scaleRgb(adjustSaturation(rgb, sat), brightness);
    }

    public static int darkenDyeInk(int rgb) {
        int r = ((rgb >>> 16) & 0xFF) * 58 / 100;
        int g = ((rgb >>> 8) & 0xFF) * 64 / 100;
        int b = (rgb & 0xFF) * 72 / 100;
        return (r << 16) | (g << 8) | b;
    }

    public static int rgbManhattan(int a, int b) {
        return Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF))
                + Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    public static void fillRgbaBytes(byte[] rgba, int argbColor) {
        byte r = (byte) ((argbColor >>> 16) & 0xFF);
        byte g = (byte) ((argbColor >>> 8) & 0xFF);
        byte b = (byte) (argbColor & 0xFF);
        byte a = (byte) ((argbColor >>> 24) & 0xFF);
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = r;
            rgba[i + 1] = g;
            rgba[i + 2] = b;
            rgba[i + 3] = a;
        }
    }

    public static void clearRgbaRect(byte[] rgba, int imgWidth, int ox, int oy, int rectW, int rectH, int argbColor) {
        byte r = (byte) ((argbColor >>> 16) & 0xFF);
        byte g = (byte) ((argbColor >>> 8) & 0xFF);
        byte b = (byte) (argbColor & 0xFF);
        byte a = (byte) ((argbColor >>> 24) & 0xFF);
        for (int z = 0; z < rectH; z++) {
            int rowStart = ((oy + z) * imgWidth + ox) << 2;
            for (int x = 0; x < rectW; x++) {
                int idx = rowStart + (x << 2);
                rgba[idx] = r;
                rgba[idx + 1] = g;
                rgba[idx + 2] = b;
                rgba[idx + 3] = a;
            }
        }
    }

    public static void copyRgbaRect(byte[] src, int srcW, byte[] dst, int dstW, int dstX, int dstY, int rectW, int rectH) {
        int rowBytes = rectW << 2;
        int srcStride = srcW << 2;
        int dstStride = dstW << 2;
        int dstBase = (dstY * dstW + dstX) << 2;
        for (int y = 0; y < rectH; y++) {
            System.arraycopy(src, y * srcStride, dst, dstBase + y * dstStride, rowBytes);
        }
    }

    public static byte[] argbToRgbaBytes(int[] argb) {
        return argbToRgbaBytes(argb, argb.length);
    }

    public static byte[] argbToRgbaBytes(int[] argb, int pixelCount) {
        byte[] out = new byte[pixelCount << 2];
        for (int i = 0; i < pixelCount; i++) {
            int c = argb[i];
            int di = i << 2;
            out[di] = (byte) ((c >>> 16) & 0xFF);
            out[di + 1] = (byte) ((c >>> 8) & 0xFF);
            out[di + 2] = (byte) (c & 0xFF);
            out[di + 3] = (byte) ((c >>> 24) & 0xFF);
        }
        return out;
    }

    public static byte[][] argbToRgbaMipChain(int[] argb, int baseW, int baseH, int maxLevels, boolean isLight) {
        return buildRgbaMipChain(argbToRgbaBytes(argb, baseW * baseH), baseW, baseH, maxLevels, isLight, isLight ? 0 : PARCHMENT_TRANSPARENT);
    }

    public static byte[][] argbSliceToRgbaMipChain(int[] argb, int baseW, int baseH, int maxLevels, boolean isLight, int emptyArgb) {
        return buildRgbaMipChain(argbToRgbaBytes(argb, baseW * baseH), baseW, baseH, maxLevels, isLight, emptyArgb);
    }

    public static byte[][] buildRgbaMipChain(byte[] baseRgba, int baseW, int baseH, int maxLevels, boolean isLight) {
        return buildRgbaMipChain(baseRgba, baseW, baseH, maxLevels, isLight, isLight ? 0 : PARCHMENT_TRANSPARENT);
    }

    public static byte[][] buildRgbaMipChain(byte[] baseRgba, int baseW, int baseH, int maxLevels, boolean isLight, int emptyArgb) {
        int levels = 1;
        int tw = baseW;
        int th = baseH;
        while (levels < maxLevels && (tw > 1 || th > 1)) {
            tw = Math.max(1, tw >> 1);
            th = Math.max(1, th >> 1);
            levels++;
        }
        byte[][] mips = new byte[levels][];
        mips[0] = baseRgba;
        int pw = baseW;
        int ph = baseH;
        for (int level = 1; level < levels; level++) {
            int cw = Math.max(1, pw >> 1);
            int ch = Math.max(1, ph >> 1);
            byte[] curr = new byte[cw * ch * 4];
            downsampleRgba2x2(mips[level - 1], pw, ph, curr, cw, ch, isLight, emptyArgb);
            mips[level] = curr;
            pw = cw;
            ph = ch;
        }
        return mips;
    }

    public static void downsampleRgba2x2(
            byte[] prev,
            int pw,
            int ph,
            byte[] curr,
            int cw,
            int ch,
            boolean isLight,
            int emptyArgb
    ) {
        byte emptyR = (byte) ((emptyArgb >>> 16) & 0xFF);
        byte emptyG = (byte) ((emptyArgb >>> 8) & 0xFF);
        byte emptyB = (byte) (emptyArgb & 0xFF);
        for (int y = 0; y < ch; y++) {
            int sy0 = Math.min(ph - 1, y << 1);
            int sy1 = Math.min(ph - 1, sy0 + 1);
            int row0 = sy0 * pw;
            int row1 = sy1 * pw;
            int dstRow = y * cw;
            for (int x = 0; x < cw; x++) {
                int sx0 = Math.min(pw - 1, x << 1);
                int sx1 = Math.min(pw - 1, sx0 + 1);
                int i00 = (row0 + sx0) << 2;
                int i10 = (row0 + sx1) << 2;
                int i01 = (row1 + sx0) << 2;
                int i11 = (row1 + sx1) << 2;
                int a00 = prev[i00 + 3] & 0xFF;
                int a10 = prev[i10 + 3] & 0xFF;
                int a01 = prev[i01 + 3] & 0xFF;
                int a11 = prev[i11 + 3] & 0xFF;
                int aSum = a00 + a10 + a01 + a11;
                int di = (dstRow + x) << 2;
                if (aSum == 0) {
                    curr[di] = emptyR;
                    curr[di + 1] = emptyG;
                    curr[di + 2] = emptyB;
                    curr[di + 3] = 0;
                } else if (isLight) {
                    curr[di] = (byte) (((prev[i00] & 0xFF) + (prev[i10] & 0xFF) + (prev[i01] & 0xFF) + (prev[i11] & 0xFF) + 2) >> 2);
                    curr[di + 1] = (byte) (((prev[i00 + 1] & 0xFF) + (prev[i10 + 1] & 0xFF) + (prev[i01 + 1] & 0xFF) + (prev[i11 + 1] & 0xFF) + 2) >> 2);
                    curr[di + 2] = (byte) (((prev[i00 + 2] & 0xFF) + (prev[i10 + 2] & 0xFF) + (prev[i01 + 2] & 0xFF) + (prev[i11 + 2] & 0xFF) + 2) >> 2);
                    curr[di + 3] = (byte) ((aSum + 2) >> 2);
                } else {
                    int a = (aSum + 2) >> 2;
                    if (a == 0) {
                        curr[di] = emptyR;
                        curr[di + 1] = emptyG;
                        curr[di + 2] = emptyB;
                        curr[di + 3] = 0;
                    } else {
                        int half = aSum >> 1;
                        int r = ((prev[i00] & 0xFF) * a00 + (prev[i10] & 0xFF) * a10
                                + (prev[i01] & 0xFF) * a01 + (prev[i11] & 0xFF) * a11 + half) / aSum;
                        int g = ((prev[i00 + 1] & 0xFF) * a00 + (prev[i10 + 1] & 0xFF) * a10
                                + (prev[i01 + 1] & 0xFF) * a01 + (prev[i11 + 1] & 0xFF) * a11 + half) / aSum;
                        int b = ((prev[i00 + 2] & 0xFF) * a00 + (prev[i10 + 2] & 0xFF) * a10
                                + (prev[i01 + 2] & 0xFF) * a01 + (prev[i11 + 2] & 0xFF) * a11 + half) / aSum;
                        curr[di] = (byte) r;
                        curr[di + 1] = (byte) g;
                        curr[di + 2] = (byte) b;
                        curr[di + 3] = (byte) a;
                    }
                }
            }
        }
    }

    public static byte[] downsampleRgba8x8(byte[] src, int srcSize, boolean isLight) {
        int dstSize = srcSize >> 3;
        byte[] out = new byte[dstSize * dstSize * 4];
        int emptyArgb = isLight ? 0 : PARCHMENT_TRANSPARENT;
        byte emptyR = (byte) ((emptyArgb >>> 16) & 0xFF);
        byte emptyG = (byte) ((emptyArgb >>> 8) & 0xFF);
        byte emptyB = (byte) (emptyArgb & 0xFF);
        for (int dy = 0; dy < dstSize; dy++) {
            int syBase = dy << 3;
            int dstRow = dy * dstSize;
            for (int dx = 0; dx < dstSize; dx++) {
                int sxBase = dx << 3;
                int aSum = 0;
                long rSum = 0L;
                long gSum = 0L;
                long bSum = 0L;
                for (int oy = 0; oy < 8; oy++) {
                    int rowBase = ((syBase + oy) * srcSize + sxBase) << 2;
                    for (int ox = 0; ox < 8; ox++) {
                        int si = rowBase + (ox << 2);
                        int a = src[si + 3] & 0xFF;
                        aSum += a;
                        if (isLight) {
                            rSum += src[si] & 0xFF;
                            gSum += src[si + 1] & 0xFF;
                            bSum += src[si + 2] & 0xFF;
                        } else if (a > 0) {
                            rSum += (long) (src[si] & 0xFF) * a;
                            gSum += (long) (src[si + 1] & 0xFF) * a;
                            bSum += (long) (src[si + 2] & 0xFF) * a;
                        }
                    }
                }
                int di = (dstRow + dx) << 2;
                int outA = (aSum + 32) >> 6;
                if (aSum == 0 || outA == 0) {
                    out[di] = emptyR;
                    out[di + 1] = emptyG;
                    out[di + 2] = emptyB;
                    out[di + 3] = 0;
                } else if (isLight) {
                    out[di] = (byte) ((rSum + 32L) >> 6);
                    out[di + 1] = (byte) ((gSum + 32L) >> 6);
                    out[di + 2] = (byte) ((bSum + 32L) >> 6);
                    out[di + 3] = (byte) outA;
                } else {
                    long half = aSum >> 1;
                    out[di] = (byte) ((rSum + half) / aSum);
                    out[di + 1] = (byte) ((gSum + half) / aSum);
                    out[di + 2] = (byte) ((bSum + half) / aSum);
                    out[di + 3] = (byte) outA;
                }
            }
        }
        return out;
    }
}
