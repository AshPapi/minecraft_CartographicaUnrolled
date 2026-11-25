package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractChestBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlockTopTextureCache {
    public static final int BLOCK_PX = 8;
    public static final int HI_PX = 16;

    public static final class BakedBlockTile {
        public static final BakedBlockTile EMPTY = new BakedBlockTile(
                new int[BLOCK_PX * BLOCK_PX], 0L, new int[HI_PX * HI_PX], new long[4],
                0xFFFFFF, false, 0, 0, 0, 0, 0, 0, 0, 0
        );

        private final int[] pixels;
        private final long tintMask;
        private final int[] hiPixels;
        private final long[] hiTintMask;
        private final int defaultTintRgb;
        private final boolean hasAnyPixel;
        private final boolean hasAnyHiTint;
        private final int untintedSumR;
        private final int untintedSumG;
        private final int untintedSumB;
        private final int untintedCount;
        private final int tintedSumR;
        private final int tintedSumG;
        private final int tintedSumB;
        private final int tintedCount;
        private final int avgUntintedRgb;

        public BakedBlockTile(
                int[] pixels,
                long tintMask,
                int[] hiPixels,
                long[] hiTintMask,
                int defaultTintRgb,
                boolean hasAnyPixel,
                int untintedSumR,
                int untintedSumG,
                int untintedSumB,
                int untintedCount,
                int tintedSumR,
                int tintedSumG,
                int tintedSumB,
                int tintedCount
        ) {
            this.pixels = pixels;
            this.tintMask = tintMask;
            this.hiPixels = hiPixels;
            this.hiTintMask = hiTintMask;
            this.defaultTintRgb = defaultTintRgb;
            this.hasAnyPixel = hasAnyPixel;
            this.hasAnyHiTint = (hiTintMask[0] | hiTintMask[1] | hiTintMask[2] | hiTintMask[3]) != 0L;
            this.untintedSumR = untintedSumR;
            this.untintedSumG = untintedSumG;
            this.untintedSumB = untintedSumB;
            this.untintedCount = untintedCount;
            this.tintedSumR = tintedSumR;
            this.tintedSumG = tintedSumG;
            this.tintedSumB = tintedSumB;
            this.tintedCount = tintedCount;
            if (untintedCount > 0 && tintedCount == 0) {
                this.avgUntintedRgb = ((untintedSumR / untintedCount) << 16)
                        | ((untintedSumG / untintedCount) << 8)
                        | (untintedSumB / untintedCount);
            } else {
                this.avgUntintedRgb = 0;
            }
        }

        public int pixel(int sx, int sy) {
            return this.pixels[(sy << 3) | sx];
        }

        public boolean isTinted(int sx, int sy) {
            return ((this.tintMask >>> ((sy << 3) | sx)) & 1L) != 0L;
        }

        public int hiPixel(int sx, int sy) {
            return this.hiPixels[(sy << 4) | sx];
        }

        public boolean isHiTinted(int sx, int sy) {
            int idx = (sy << 4) | sx;
            return ((this.hiTintMask[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
        }

        public boolean hasAnyHiTint() {
            return this.hasAnyHiTint;
        }

        public int defaultTintRgb() {
            return this.defaultTintRgb;
        }

        public boolean hasAnyPixel() {
            return this.hasAnyPixel;
        }

        public int computeAvgHiRgb(int tintRgb, int fallbackRgb) {
            int total = this.untintedCount + this.tintedCount;
            if (total <= 0) {
                return fallbackRgb;
            }
            if (this.tintedCount == 0) {
                return this.avgUntintedRgb;
            }
            int tr = (tintRgb >>> 16) & 0xFF;
            int tg = (tintRgb >>> 8) & 0xFF;
            int tb = tintRgb & 0xFF;
            int sumR = this.untintedSumR + (this.tintedSumR * tr) / 255;
            int sumG = this.untintedSumG + (this.tintedSumG * tg) / 255;
            int sumB = this.untintedSumB + (this.tintedSumB * tb) / 255;
            return ((sumR / total) << 16) | ((sumG / total) << 8) | (sumB / total);
        }
    }

    private static final class BakeScratch {
        final List<BlockStateModelPart> parts = new ArrayList<>(8);
        final List<BakedQuad> quads = new ArrayList<>(32);
        final float[] hiDepth = new float[HI_PX * HI_PX];
        final boolean[] hiTinted = new boolean[HI_PX * HI_PX];
        final Vector3fc[] pos = new Vector3fc[4];
        final float[] lu = new float[4];
        final float[] lv = new float[4];
        final RandomSource random = RandomSource.create(42L);
    }

    private static final ThreadLocal<BakeScratch> BAKE_SCRATCH = ThreadLocal.withInitial(BakeScratch::new);
    private static final ConcurrentHashMap<BlockState, BakedBlockTile> CACHE = new ConcurrentHashMap<>(512);
    private static final AtomicInteger CACHE_EPOCH = new AtomicInteger(1);
    private static volatile @Nullable BlockStateModelSet lastModelSet;
    private static volatile @Nullable BakedBlockTile waterTile;
    private static volatile @Nullable BakedBlockTile lavaTile;

    private BlockTopTextureCache() {
    }

    public static void invalidateAll() {
        CACHE.clear();
        waterTile = null;
        lavaTile = null;
        lastModelSet = null;
        CACHE_EPOCH.incrementAndGet();
    }

    public static int ensureFreshEpoch() {
        ensureFresh();
        return CACHE_EPOCH.get();
    }

    public static boolean ensureFresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) {
            return false;
        }
        BlockStateModelSet current = mc.getModelManager().getBlockStateModelSet();
        if (current != lastModelSet) {
            CACHE.clear();
            waterTile = null;
            lavaTile = null;
            lastModelSet = current;
            CACHE_EPOCH.incrementAndGet();
            return true;
        }
        return false;
    }

    public static void warmFluids() {
        if (waterTile != null && lavaTile != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) {
            return;
        }
        if (waterTile == null) {
            waterTile = bakeFluid(mc.getModelManager(), Fluids.WATER.defaultFluidState(), true);
        }
        if (lavaTile == null) {
            lavaTile = bakeFluid(mc.getModelManager(), Fluids.LAVA.defaultFluidState(), false);
        }
    }

    public static void warmPalette(BlockState @Nullable [] palette) {
        if (palette == null || palette.length == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) {
            return;
        }
        ModelManager modelManager = mc.getModelManager();
        BlockStateModelSet modelSet = modelManager.getBlockStateModelSet();
        BlockColors blockColors = mc.getBlockColors();
        for (BlockState state : palette) {
            if (state == null || state.isAir() || CACHE.containsKey(state)) {
                continue;
            }
            BakedBlockTile baked = bakeBlockState(state, modelManager, modelSet, blockColors);
            CACHE.putIfAbsent(state, baked);
        }
    }

    public static @Nullable BakedBlockTile getCached(@Nullable BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        return CACHE.get(state);
    }

    public static @Nullable BakedBlockTile getOrBake(@Nullable BlockState state) {
        if (state == null || state.isAir()) {
            return null;
        }
        BakedBlockTile cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) {
            return null;
        }
        ModelManager modelManager = mc.getModelManager();
        BlockStateModelSet modelSet = modelManager.getBlockStateModelSet();
        BlockColors blockColors = mc.getBlockColors();
        BakedBlockTile baked = bakeBlockState(state, modelManager, modelSet, blockColors);
        BakedBlockTile prev = CACHE.putIfAbsent(state, baked);
        return prev != null ? prev : baked;
    }

    public static @Nullable BakedBlockTile getWaterTile() {
        return waterTile;
    }

    public static @Nullable BakedBlockTile getLavaTile() {
        return lavaTile;
    }

    private static BakedBlockTile bakeFluid(ModelManager modelManager, FluidState fluidState, boolean tinted) {
        BakeScratch scratch = BAKE_SCRATCH.get();
        try {
            FluidModel fm = modelManager.getFluidStateModelSet().get(fluidState);
            if (fm == null || fm.stillMaterial() == null) {
                return BakedBlockTile.EMPTY;
            }
            TextureAtlasSprite sprite = fm.stillMaterial().sprite();
            if (sprite == null) {
                return BakedBlockTile.EMPTY;
            }
            int[] hiArgb = new int[HI_PX * HI_PX];
            Arrays.fill(scratch.hiTinted, false);
            sampleSpriteFull(sprite, hiArgb, scratch.hiTinted, tinted, -1);
            return downsampleToTile(hiArgb, scratch.hiTinted, tinted ? 0x3F76E4 : 0xD45A12);
        } catch (Exception e) {
            return BakedBlockTile.EMPTY;
        }
    }

    private static BakedBlockTile bakeBlockState(
            BlockState state,
            ModelManager modelManager,
            BlockStateModelSet modelSet,
            BlockColors blockColors
    ) {
        BakeScratch scratch = BAKE_SCRATCH.get();
        try {
            if (state.getBlock() instanceof LiquidBlock && !state.getFluidState().isEmpty()) {
                boolean isWater = state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER);
                return bakeFluid(modelManager, state.getFluidState(), isWater);
            }
            if (state.getBlock() instanceof AbstractChestBlock<?>) {
                return SpecialBlockTileBakers.bakeChest(state, modelSet, scratch.hiTinted);
            }
            if (state.getBlock() instanceof BedBlock bed) {
                return SpecialBlockTileBakers.bakeBed(state, bed, scratch.hiTinted);
            }
            if (state.getBlock() instanceof AbstractBannerBlock banner) {
                return SpecialBlockTileBakers.bakeBanner(banner, scratch.hiTinted);
            }

            BlockStateModel model = modelSet.get(state);
            if (model == null) {
                return BakedBlockTile.EMPTY;
            }

            scratch.parts.clear();
            scratch.random.setSeed(42L);
            model.collectParts(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state, scratch.random, scratch.parts);

            scratch.quads.clear();
            for (int pIdx = 0; pIdx < scratch.parts.size(); pIdx++) {
                BlockStateModelPart part = scratch.parts.get(pIdx);
                List<BakedQuad> upQuads = part.getQuads(Direction.UP);
                if (upQuads != null && !upQuads.isEmpty()) {
                    scratch.quads.addAll(upQuads);
                }
                List<BakedQuad> generalQuads = part.getQuads(null);
                if (generalQuads != null && !generalQuads.isEmpty()) {
                    scratch.quads.addAll(generalQuads);
                }
            }

            int[] hiArgb = new int[HI_PX * HI_PX];
            Arrays.fill(scratch.hiDepth, -Float.MAX_VALUE);
            Arrays.fill(scratch.hiTinted, false);

            int defaultTintRgb = resolveDefaultBiomeTint(state, blockColors);

            for (int qIdx = 0; qIdx < scratch.quads.size(); qIdx++) {
                BakedQuad quad = scratch.quads.get(qIdx);
                if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null) {
                    continue;
                }
                if (quad.direction() == Direction.DOWN) {
                    continue;
                }
                rasterizeUpwardQuad(state, quad, blockColors, hiArgb, scratch.hiDepth, scratch.hiTinted);
            }

            int opaqueCount = countVisiblePixels(hiArgb);
            if (opaqueCount < 16 && !scratch.quads.isEmpty()) {
                for (int qIdx = 0; qIdx < scratch.quads.size(); qIdx++) {
                    BakedQuad quad = scratch.quads.get(qIdx);
                    if (quad == null || quad.materialInfo() == null || quad.materialInfo().sprite() == null) {
                        continue;
                    }
                    if (quad.direction() == Direction.DOWN) {
                        continue;
                    }
                    rasterizeVerticalQuad(state, quad, blockColors, hiArgb, scratch);
                }
                opaqueCount = countVisiblePixels(hiArgb);
            }

            if (opaqueCount == 0) {
                Material.Baked particle = model.particleMaterial(BlockAndTintGetter.EMPTY, BlockPos.ZERO, state);
                if (particle != null && particle.sprite() != null) {
                    long td = classifyTintPacked(state, 0, blockColors);
                    sampleSpriteFull(particle.sprite(), hiArgb, scratch.hiTinted, (td >>> 32) != 0L, (int) td);
                }
            }

            if (state.is(BlockTags.LEAVES)) {
                fillLeafHoles(hiArgb, scratch.hiTinted);
            } else {
                applyInternalHeightShade(hiArgb, scratch.hiDepth);
            }

            return downsampleToTile(hiArgb, scratch.hiTinted, defaultTintRgb);
        } catch (Exception e) {
            return BakedBlockTile.EMPTY;
        } finally {
            scratch.parts.clear();
            scratch.quads.clear();
            Arrays.fill(scratch.pos, null);
        }
    }

    private static long packTint(boolean biomeTinted, int constantColor) {
        return (biomeTinted ? (1L << 32) : 0L) | (constantColor & 0xFFFFFFFFL);
    }

    private static long classifyTintPacked(BlockState state, int tintIndex, BlockColors blockColors) {
        if (tintIndex < 0) {
            return packTint(false, -1);
        }
        BlockTintSource src = blockColors.getTintSource(state, tintIndex);
        if (src == null) {
            return packTint(false, -1);
        }
        if (state.is(Blocks.LILY_PAD)) {
            return packTint(false, BlockColors.LILY_PAD_IN_WORLD & 0xFFFFFF);
        }
        if ((state.is(Blocks.PINK_PETALS) || state.is(Blocks.WILDFLOWERS)) && tintIndex == 0) {
            return packTint(false, -1);
        }
        if (isBiomeDynamicBlock(state)) {
            return packTint(true, -1);
        }
        int col = src.color(state);
        if (col == -1) {
            return packTint(false, -1);
        }
        return packTint(false, col & 0xFFFFFF);
    }

    private static boolean isBiomeDynamicBlock(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.BUSH)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.PINK_PETALS)
                || state.is(Blocks.WILDFLOWERS)
                || state.is(Blocks.OAK_LEAVES)
                || state.is(Blocks.JUNGLE_LEAVES)
                || state.is(Blocks.ACACIA_LEAVES)
                || state.is(Blocks.DARK_OAK_LEAVES)
                || state.is(Blocks.MANGROVE_LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.LEAF_LITTER)
                || state.is(Blocks.WATER_CAULDRON);
    }

    private static int resolveDefaultBiomeTint(BlockState state, BlockColors blockColors) {
        if (state.is(Blocks.WATER_CAULDRON)) {
            return 0x3F76E4;
        }
        List<BlockTintSource> list = blockColors.getTintSources(state);
        for (int i = 0; i < list.size(); i++) {
            BlockTintSource src = list.get(i);
            if (src != null) {
                int c = src.color(state);
                if (c != -1) {
                    return c & 0xFFFFFF;
                }
            }
        }
        return 0x79C05A;
    }

    private static void rasterizeUpwardQuad(
            BlockState state,
            BakedQuad quad,
            BlockColors blockColors,
            int[] hiArgb,
            float[] hiDepth,
            boolean[] hiTinted
    ) {
        Vector3fc p0 = quad.position0();
        Vector3fc p1 = quad.position1();
        Vector3fc p2 = quad.position2();
        Vector3fc p3 = quad.position3();

        float ax = p1.x() - p0.x();
        float ay = p1.y() - p0.y();
        float az = p1.z() - p0.z();
        float bx = p2.x() - p0.x();
        float by = p2.y() - p0.y();
        float bz = p2.z() - p0.z();
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq < 1e-10f) {
            return;
        }
        if ((ny <= 0.0f || ny * ny <= 0.0144f * lenSq) && quad.direction() != Direction.UP) {
            return;
        }
        if (Math.abs(ny) < 1e-6f) {
            return;
        }

        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        SpriteContents contents = sprite.contents();
        int sw = contents.width();
        int sh = contents.height();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        float u0 = sprite.getU0();
        float uSpan = sprite.getU1() - u0;
        float v0 = sprite.getV0();
        float vSpan = sprite.getV1() - v0;
        if (Math.abs(uSpan) < 1e-7f || Math.abs(vSpan) < 1e-7f) {
            return;
        }

        float lu0 = (UVPair.unpackU(quad.packedUV0()) - u0) / uSpan;
        float lv0 = (UVPair.unpackV(quad.packedUV0()) - v0) / vSpan;
        float lu1 = (UVPair.unpackU(quad.packedUV1()) - u0) / uSpan;
        float lv1 = (UVPair.unpackV(quad.packedUV1()) - v0) / vSpan;
        float lu2 = (UVPair.unpackU(quad.packedUV2()) - u0) / uSpan;
        float lv2 = (UVPair.unpackV(quad.packedUV2()) - v0) / vSpan;
        float lu3 = (UVPair.unpackU(quad.packedUV3()) - u0) / uSpan;
        float lv3 = (UVPair.unpackV(quad.packedUV3()) - v0) / vSpan;

        long td = classifyTintPacked(state, quad.materialInfo().tintIndex(), blockColors);

        rasterizeTriangle(
                sprite, sw, sh, td,
                p0.x(), p0.y(), p0.z(), lu0, lv0,
                p1.x(), p1.y(), p1.z(), lu1, lv1,
                p2.x(), p2.y(), p2.z(), lu2, lv2,
                hiArgb, hiDepth, hiTinted
        );
        rasterizeTriangle(
                sprite, sw, sh, td,
                p0.x(), p0.y(), p0.z(), lu0, lv0,
                p2.x(), p2.y(), p2.z(), lu2, lv2,
                p3.x(), p3.y(), p3.z(), lu3, lv3,
                hiArgb, hiDepth, hiTinted
        );
    }

    private static void rasterizeTriangle(
            TextureAtlasSprite sprite,
            int sw,
            int sh,
            long td,
            float x0, float y0, float z0, float u0, float v0,
            float x1, float y1, float z1, float u1, float v1,
            float x2, float y2, float z2, float u2, float v2,
            int[] hiArgb,
            float[] hiDepth,
            boolean[] hiTinted
    ) {
        float denom = (z1 - z2) * (x0 - x2) + (x2 - x1) * (z0 - z2);
        if (Math.abs(denom) < 1e-6f) {
            return;
        }
        float invDenom = 1.0f / denom;
        boolean biomeTinted = (td >>> 32) != 0L;
        int constantColor = (int) td;

        int minPx = Mth.clamp(Mth.floor(Math.min(x0, Math.min(x1, x2)) * HI_PX), 0, HI_PX - 1);
        int maxPx = Mth.clamp(Mth.ceil(Math.max(x0, Math.max(x1, x2)) * HI_PX) - 1, 0, HI_PX - 1);
        int minPz = Mth.clamp(Mth.floor(Math.min(z0, Math.min(z1, z2)) * HI_PX), 0, HI_PX - 1);
        int maxPz = Mth.clamp(Mth.ceil(Math.max(z0, Math.max(z1, z2)) * HI_PX) - 1, 0, HI_PX - 1);

        for (int pz = minPz; pz <= maxPz; pz++) {
            float sz = (pz + 0.5f) / HI_PX;
            int row = pz * HI_PX;
            for (int px = minPx; px <= maxPx; px++) {
                float sx = (px + 0.5f) / HI_PX;
                float w0 = ((z1 - z2) * (sx - x2) + (x2 - x1) * (sz - z2)) * invDenom;
                float w1 = ((z2 - z0) * (sx - x2) + (x0 - x2) * (sz - z2)) * invDenom;
                float w2 = 1.0f - w0 - w1;
                if (w0 < -0.002f || w1 < -0.002f || w2 < -0.002f) {
                    continue;
                }
                float y = w0 * y0 + w1 * y1 + w2 * y2;
                int idx = row + px;
                if (y < hiDepth[idx] - 1e-4f) {
                    continue;
                }
                float lu = w0 * u0 + w1 * u1 + w2 * u2;
                float lv = w0 * v0 + w1 * v1 + w2 * v2;
                int tx = Mth.clamp(Mth.floor(lu * sw), 0, sw - 1);
                int ty = Mth.clamp(Mth.floor(lv * sh), 0, sh - 1);
                int argb = sprite.getPixelARGB(0, tx, ty);
                int a = (argb >>> 24) & 0xFF;
                if (a < 16) {
                    continue;
                }
                if (constantColor >= 0) {
                    argb = AtlasMath.multiplyArgbRgb(argb, constantColor);
                }
                writeHiPixel(hiArgb, hiDepth, hiTinted, idx, argb, y, biomeTinted);
            }
        }
    }

    private static void rasterizeVerticalQuad(
            BlockState state,
            BakedQuad quad,
            BlockColors blockColors,
            int[] hiArgb,
            BakeScratch scratch
    ) {
        Vector3fc[] pos = scratch.pos;
        pos[0] = quad.position0();
        pos[1] = quad.position1();
        pos[2] = quad.position2();
        pos[3] = quad.position3();
        float ax = pos[1].x() - pos[0].x();
        float ay = pos[1].y() - pos[0].y();
        float az = pos[1].z() - pos[0].z();
        float bx = pos[2].x() - pos[0].x();
        float by = pos[2].y() - pos[0].y();
        float bz = pos[2].z() - pos[0].z();
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        float lenSq = nx * nx + ny * ny + nz * nz;
        if (lenSq < 1e-10f || ny * ny > 0.04f * lenSq) {
            return;
        }

        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        SpriteContents contents = sprite.contents();
        int sw = contents.width();
        int sh = contents.height();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        float u0 = sprite.getU0();
        float uSpan = sprite.getU1() - u0;
        float v0 = sprite.getV0();
        float vSpan = sprite.getV1() - v0;
        if (Math.abs(uSpan) < 1e-7f || Math.abs(vSpan) < 1e-7f) {
            return;
        }

        float[] lu = scratch.lu;
        float[] lv = scratch.lv;
        for (int i = 0; i < 4; i++) {
            lu[i] = (UVPair.unpackU(quad.packedUV(i)) - u0) / uSpan;
            lv[i] = (UVPair.unpackV(quad.packedUV(i)) - v0) / vSpan;
        }

        int topA = 0;
        int topB = 1;
        for (int i = 0; i < 4; i++) {
            if (pos[i].y() > pos[topA].y()) {
                topB = topA;
                topA = i;
            } else if (i != topA && (topB == topA || pos[i].y() > pos[topB].y())) {
                topB = i;
            }
        }
        int botA = -1;
        int botB = -1;
        for (int i = 0; i < 4; i++) {
            if (i == topA || i == topB) {
                continue;
            }
            float dA = Math.abs(pos[i].x() - pos[topA].x()) + Math.abs(pos[i].z() - pos[topA].z());
            float dB = Math.abs(pos[i].x() - pos[topB].x()) + Math.abs(pos[i].z() - pos[topB].z());
            if (dA <= dB && botA < 0) {
                botA = i;
            } else if (botB < 0) {
                botB = i;
            } else {
                botA = i;
            }
        }
        if (botA < 0 || botB < 0) {
            return;
        }

        float xA = pos[topA].x();
        float zA = pos[topA].z();
        float xB = pos[topB].x();
        float zB = pos[topB].z();
        float segLenSq = (xB - xA) * (xB - xA) + (zB - zA) * (zB - zA);
        if (segLenSq < 0.0025f) {
            return;
        }
        float segLen = (float) Math.sqrt(segLenSq);

        long td = classifyTintPacked(state, quad.materialInfo().tintIndex(), blockColors);
        boolean biomeTinted = (td >>> 32) != 0L;
        int constantColor = (int) td;
        float maxY = Math.max(pos[topA].y(), pos[topB].y());
        float minBotY = Math.min(pos[botA].y(), pos[botB].y());
        int steps = Math.max(8, Math.round(segLen * HI_PX * 1.5f));

        for (int s = 0; s <= steps; s++) {
            float t = (float) s / steps;
            float wx = Mth.lerp(t, xA, xB);
            float wz = Mth.lerp(t, zA, zB);
            float uTop = Mth.lerp(t, lu[topA], lu[topB]);
            float vTop = Mth.lerp(t, lv[topA], lv[topB]);
            float uBot = Mth.lerp(t, lu[botA], lu[botB]);
            float vBot = Mth.lerp(t, lv[botA], lv[botB]);

            int sumR = 0;
            int sumG = 0;
            int sumB = 0;
            int sumA = 0;
            int hits = 0;
            float hitY = maxY;
            for (int vStep = 0; vStep < 14; vStep++) {
                float vf = (vStep + 0.5f) / 14.0f;
                float sampleU = Mth.lerp(vf, uTop, uBot);
                float sampleV = Mth.lerp(vf, vTop, vBot);
                int tx = Mth.clamp(Mth.floor(sampleU * sw), 0, sw - 1);
                int ty = Mth.clamp(Mth.floor(sampleV * sh), 0, sh - 1);
                int argb = sprite.getPixelARGB(0, tx, ty);
                int a = (argb >>> 24) & 0xFF;
                if (a >= 64) {
                    if (hits == 0) {
                        hitY = Mth.lerp(vf, maxY, minBotY);
                    }
                    sumR += (argb >>> 16) & 0xFF;
                    sumG += (argb >>> 8) & 0xFF;
                    sumB += argb & 0xFF;
                    sumA += a;
                    hits++;
                    if (hits >= 3) {
                        break;
                    }
                }
            }
            if (hits == 0) {
                continue;
            }
            int col = ((sumA / hits) << 24) | ((sumR / hits) << 16) | ((sumG / hits) << 8) | (sumB / hits);
            if (constantColor >= 0) {
                col = AtlasMath.multiplyArgbRgb(col, constantColor);
            }
            int cx = Mth.clamp(Mth.floor(wx * HI_PX), 0, HI_PX - 1);
            int cz = Mth.clamp(Mth.floor(wz * HI_PX), 0, HI_PX - 1);
            int idx = cz * HI_PX + cx;
            if (hitY >= scratch.hiDepth[idx] - 1e-4f) {
                writeHiPixel(hiArgb, scratch.hiDepth, scratch.hiTinted, idx, col, hitY, biomeTinted);
            }
        }
    }

    private static void writeHiPixel(
            int[] hiArgb,
            float[] hiDepth,
            boolean[] hiTinted,
            int idx,
            int argb,
            float depth,
            boolean tinted
    ) {
        int a = (argb >>> 24) & 0xFF;
        int prev = hiArgb[idx];
        int prevA = (prev >>> 24) & 0xFF;
        if (a >= 250 || prevA == 0) {
            hiArgb[idx] = argb;
            hiDepth[idx] = depth;
            hiTinted[idx] = tinted;
        } else {
            int outA = a + ((prevA * (255 - a)) / 255);
            if (outA <= 0) {
                return;
            }
            int r = (((argb >>> 16) & 0xFF) * a + ((prev >>> 16) & 0xFF) * prevA * (255 - a) / 255) / outA;
            int g = (((argb >>> 8) & 0xFF) * a + ((prev >>> 8) & 0xFF) * prevA * (255 - a) / 255) / outA;
            int b = ((argb & 0xFF) * a + (prev & 0xFF) * prevA * (255 - a) / 255) / outA;
            hiArgb[idx] = (Math.min(255, outA) << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
            hiDepth[idx] = Math.max(hiDepth[idx], depth);
            if (a >= 128) {
                hiTinted[idx] = tinted;
            }
        }
    }

    private static void sampleSpriteFull(
            TextureAtlasSprite sprite,
            int[] hiArgb,
            boolean[] hiTinted,
            boolean biomeTinted,
            int constantColor
    ) {
        SpriteContents contents = sprite.contents();
        int sw = contents.width();
        int sh = contents.height();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        for (int pz = 0; pz < HI_PX; pz++) {
            int ty = Mth.clamp((pz * sh) / HI_PX, 0, sh - 1);
            int row = pz * HI_PX;
            for (int px = 0; px < HI_PX; px++) {
                int tx = Mth.clamp((px * sw) / HI_PX, 0, sw - 1);
                int argb = sprite.getPixelARGB(0, tx, ty);
                if (constantColor >= 0) {
                    argb = AtlasMath.multiplyArgbRgb(argb, constantColor);
                }
                hiArgb[row + px] = argb;
                hiTinted[row + px] = biomeTinted;
            }
        }
    }

    private static void fillLeafHoles(int[] hiArgb, boolean[] hiTinted) {
        long rSum = 0;
        long gSum = 0;
        long bSum = 0;
        int count = 0;
        int tintCount = 0;
        for (int i = 0; i < hiArgb.length; i++) {
            int a = (hiArgb[i] >>> 24) & 0xFF;
            if (a >= 128) {
                rSum += (hiArgb[i] >>> 16) & 0xFF;
                gSum += (hiArgb[i] >>> 8) & 0xFF;
                bSum += hiArgb[i] & 0xFF;
                count++;
                if (hiTinted[i]) {
                    tintCount++;
                }
            }
        }
        if (count == 0) {
            return;
        }
        int fillR = (int) ((rSum / count) * 0.56f);
        int fillG = (int) ((gSum / count) * 0.56f);
        int fillB = (int) ((bSum / count) * 0.56f);
        int fillArgb = 0xFF000000 | (fillR << 16) | (fillG << 8) | fillB;
        boolean fillTint = tintCount * 2 >= count;
        for (int i = 0; i < hiArgb.length; i++) {
            if (((hiArgb[i] >>> 24) & 0xFF) < 128) {
                hiArgb[i] = fillArgb;
                hiTinted[i] = fillTint;
            }
        }
    }

    private static void applyInternalHeightShade(int[] hiArgb, float[] hiDepth) {
        float maxY = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        for (int i = 0; i < hiArgb.length; i++) {
            if (((hiArgb[i] >>> 24) & 0xFF) >= 32 && hiDepth[i] > -1000.0f) {
                if (hiDepth[i] > maxY) maxY = hiDepth[i];
                if (hiDepth[i] < minY) minY = hiDepth[i];
            }
        }
        if (maxY - minY < 0.22f) {
            return;
        }
        for (int i = 0; i < hiArgb.length; i++) {
            int argb = hiArgb[i];
            int a = (argb >>> 24) & 0xFF;
            if (a >= 32 && hiDepth[i] > -1000.0f) {
                float drop = maxY - hiDepth[i];
                if (drop > 0.16f) {
                    float factor = Mth.clamp(1.0f - drop * 0.22f, 0.82f, 0.96f);
                    hiArgb[i] = (a << 24) | AtlasMath.scaleRgb(argb & 0xFFFFFF, factor);
                }
            }
        }
    }

    static BakedBlockTile downsampleToTile(int[] hiArgb, boolean[] hiTinted, int defaultTintRgb) {
        int[] out = new int[BLOCK_PX * BLOCK_PX];
        long tintMask = 0L;
        boolean hasAny = false;

        for (int bz = 0; bz < BLOCK_PX; bz++) {
            for (int bx = 0; bx < BLOCK_PX; bx++) {
                int sumA = 0;
                long sumR = 0;
                long sumG = 0;
                long sumB = 0;
                int opaqueHits = 0;
                int tintHits = 0;
                for (int dz = 0; dz < 2; dz++) {
                    int row = (bz * 2 + dz) * HI_PX;
                    for (int dx = 0; dx < 2; dx++) {
                        int idx = row + bx * 2 + dx;
                        int argb = hiArgb[idx];
                        int a = (argb >>> 24) & 0xFF;
                        sumA += a;
                        if (a > 0) {
                            sumR += (long) ((argb >>> 16) & 0xFF) * a;
                            sumG += (long) ((argb >>> 8) & 0xFF) * a;
                            sumB += (long) (argb & 0xFF) * a;
                            opaqueHits++;
                            if (hiTinted[idx]) {
                                tintHits++;
                            }
                        }
                    }
                }
                int outIdx = (bz << 3) | bx;
                int avgA = (sumA + 2) >> 2;
                if (avgA < 12 || sumA == 0) {
                    out[outIdx] = 0;
                } else {
                    int r = Mth.clamp((int) (sumR / sumA), 0, 255);
                    int g = Mth.clamp((int) (sumG / sumA), 0, 255);
                    int b = Mth.clamp((int) (sumB / sumA), 0, 255);
                    out[outIdx] = (avgA << 24) | (r << 16) | (g << 8) | b;
                    hasAny = true;
                    if (tintHits * 2 >= opaqueHits && tintHits > 0) {
                        tintMask |= (1L << outIdx);
                    }
                }
            }
        }

        long[] hiTintMask = new long[4];
        int untintedR = 0;
        int untintedG = 0;
        int untintedB = 0;
        int untintedCount = 0;
        int tintedR = 0;
        int tintedG = 0;
        int tintedB = 0;
        int tintedCount = 0;
        for (int i = 0; i < hiTinted.length; i++) {
            int argb = hiArgb[i];
            boolean t = hiTinted[i];
            if (t) {
                hiTintMask[i >>> 6] |= (1L << (i & 63));
            }
            if (((argb >>> 24) & 0xFF) >= 32) {
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (t) {
                    tintedR += r;
                    tintedG += g;
                    tintedB += b;
                    tintedCount++;
                } else {
                    untintedR += r;
                    untintedG += g;
                    untintedB += b;
                    untintedCount++;
                }
            }
        }
        return new BakedBlockTile(
                out,
                tintMask,
                hiArgb,
                hiTintMask,
                defaultTintRgb,
                hasAny,
                untintedR,
                untintedG,
                untintedB,
                untintedCount,
                tintedR,
                tintedG,
                tintedB,
                tintedCount
        );
    }

    private static int countVisiblePixels(int[] hiArgb) {
        int count = 0;
        for (int argb : hiArgb) {
            if (((argb >>> 24) & 0xFF) >= 32) {
                count++;
            }
        }
        return count;
    }
}
