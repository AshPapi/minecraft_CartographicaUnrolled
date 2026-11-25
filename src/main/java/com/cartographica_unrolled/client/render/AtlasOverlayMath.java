package com.cartographica_unrolled.client.render;

import com.cartographica_unrolled.client.atmosphere.AtlasAtmosphere;
import net.minecraft.util.Mth;

public final class AtlasOverlayMath {
    public static final int MAX_REGION_GRID_SPAN = 512;

    private AtlasOverlayMath() {
    }

    public static boolean shouldUseBoundedRegionGrid(int regionCount, int minRx, int maxRx, int minRz, int maxRz) {
        if (regionCount <= 0) {
            return false;
        }
        long spanX = (long) maxRx - minRx + 1L;
        long spanZ = (long) maxRz - minRz + 1L;
        return spanX > 0 && spanZ > 0
                && spanX <= MAX_REGION_GRID_SPAN && spanZ <= MAX_REGION_GRID_SPAN
                && spanX * spanZ <= regionCount;
    }

    public static boolean shouldSkipInteriorJoint(float dx0, float dy0, float dx1, float dy1) {
        float dot = dx0 * dx1 + dy0 * dy1;
        if (dot <= 0.0f) {
            return false;
        }
        float lenSqProd = (dx0 * dx0 + dy0 * dy0) * (dx1 * dx1 + dy1 * dy1);
        return dot * dot > 0.970225f * lenSqProd;
    }

    public static float advanceOffscreenDashState(
            boolean drawing,
            float remain,
            float segLen,
            float dashLen,
            float gapLen,
            float period,
            float minRemain
    ) {
        double total = (drawing ? (dashLen - remain) : (dashLen + (gapLen - remain))) + segLen;
        float phase = (float) (total - Math.floor(total / period) * period);
        if (phase < dashLen) {
            return Math.max(minRemain, dashLen - phase);
        }
        return -Math.max(minRemain, period - phase);
    }

    public static int smokePuffCount(AtlasAtmosphere.SmokeEmitter emitter) {
        return emitter.furnace() ? 3 : (emitter.signal() ? 8 : 6);
    }

    public static float smokePeriod(AtlasAtmosphere.SmokeEmitter emitter) {
        return emitter.furnace() ? 4.0f : (emitter.signal() ? 4.4f : 3.6f);
    }

    public static float smokePhaseOffset(AtlasAtmosphere.SmokeEmitter emitter, float period) {
        return ((emitter.seed() & 0xFF) / 255.0f) * period;
    }

    public static float smokeCycle(double timeSec, float phaseOff, int puffIdx, int puffCount, float period) {
        return (float) (((timeSec + phaseOff + puffIdx * (period / puffCount)) % period) / period);
    }

    public static float smokeSwayUnit(double timeSec, int puffIdx, int seed) {
        return Mth.sin((float) (timeSec * 1.6 + puffIdx * 1.9 + (seed & 31)));
    }
}
