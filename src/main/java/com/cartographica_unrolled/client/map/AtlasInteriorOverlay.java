package com.cartographica_unrolled.client.map;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class AtlasInteriorOverlay {
    public static final int BLOCK_PX = BlockTopTextureCache.HI_PX;
    private static final int MARGIN_BLOCKS = 1;
    private static final int MAX_MIP_LEVELS = 5;

    private static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath(
            CartographicaUnrolled.MOD_ID, "dynamic/interior_overlay"
    );

    private static @Nullable MipmappedDynamicTexture texture;
    private static int texWidthPx;
    private static int texHeightPx;
    private static int originWorldX;
    private static int originWorldZ;
    private static int worldSpanX;
    private static int worldSpanZ;
    private static long lastSnapshotHash;
    private static int lastTextureEpoch = -1;
    private static boolean active;

    private static InteriorSliceSnapshot.Cell[] cellPool = new InteriorSliceSnapshot.Cell[0];
    private static int[] pixelBuffer = new int[0];
    private static final BlockPos.MutableBlockPos SCRATCH_POS = new BlockPos.MutableBlockPos();
    private static final BlockPos.MutableBlockPos SCRATCH_SUB_POS = new BlockPos.MutableBlockPos();
    private static final LevelChunk[] NEARBY_CHUNKS = new LevelChunk[9];
    private static final float[] SHADOW_BUF = new float[(BLOCK_PX + 2) * (BLOCK_PX + 2)];

    private AtlasInteriorOverlay() {
    }

    public static boolean isActive() {
        return active && texture != null && worldSpanX > 0 && worldSpanZ > 0;
    }

    public static Identifier getTextureId() {
        return TEXTURE_ID;
    }

    public static int getOriginWorldX() {
        return originWorldX;
    }

    public static int getOriginWorldZ() {
        return originWorldZ;
    }

    public static int getWorldSpanX() {
        return worldSpanX;
    }

    public static int getWorldSpanZ() {
        return worldSpanZ;
    }

    public static int getTexWidthPx() {
        return texWidthPx;
    }

    public static int getTexHeightPx() {
        return texHeightPx;
    }

    public static void clear() {
        active = false;
        lastSnapshotHash = 0L;
        worldSpanX = 0;
        worldSpanZ = 0;
        if (texture != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.getTextureManager() != null) {
                mc.getTextureManager().release(TEXTURE_ID);
            }
            texture = null;
        }
        texWidthPx = 0;
        texHeightPx = 0;
        cellPool = new InteriorSliceSnapshot.Cell[0];
        pixelBuffer = new int[0];
    }

    public static void update(
            ClientLevel level,
            int centerCx,
            int centerCz,
            int playerY,
            boolean @Nullable [][] masks
    ) {
        if (masks == null) {
            clear();
            return;
        }
        int epoch = BlockTopTextureCache.ensureFreshEpoch();
        if (epoch != lastTextureEpoch) {
            lastTextureEpoch = epoch;
            lastSnapshotHash = 0L;
        }
        BlockTopTextureCache.warmFluids();

        int minGx = 48;
        int maxGx = -1;
        int minGz = 48;
        int maxGz = -1;
        for (int gz = 0; gz < 48; gz++) {
            int czIdx = gz >> 4;
            int bz = gz & 15;
            for (int gx = 0; gx < 48; gx++) {
                boolean[] m = masks[czIdx * 3 + (gx >> 4)];
                if (m != null && m[(bz << 4) | (gx & 15)]) {
                    if (gx < minGx) minGx = gx;
                    if (gx > maxGx) maxGx = gx;
                    if (gz < minGz) minGz = gz;
                    if (gz > maxGz) maxGz = gz;
                }
            }
        }
        if (maxGx < minGx || maxGz < minGz) {
            clear();
            return;
        }

        int baseWx = (centerCx - 1) << 4;
        int baseWz = (centerCz - 1) << 4;
        int oWx = baseWx + minGx - MARGIN_BLOCKS;
        int oWz = baseWz + minGz - MARGIN_BLOCKS;
        int spanX = (maxGx - minGx + 1) + MARGIN_BLOCKS * 2;
        int spanZ = (maxGz - minGz + 1) + MARGIN_BLOCKS * 2;

        int totalCells = spanX * spanZ;
        if (cellPool.length < totalCells) {
            int oldLen = cellPool.length;
            cellPool = Arrays.copyOf(cellPool, totalCells);
            for (int i = oldLen; i < totalCells; i++) {
                cellPool[i] = new InteriorSliceSnapshot.Cell();
            }
        }
        InteriorSliceSnapshot.Cell[] cells = cellPool;
        BlockPos.MutableBlockPos pos = SCRATCH_POS;
        BlockPos.MutableBlockPos subPos = SCRATCH_SUB_POS;
        int minY = level.getMinY();

        long hash = 1469598103934665603L;
        hash = InteriorSliceSnapshot.mixHash(hash, oWx);
        hash = InteriorSliceSnapshot.mixHash(hash, oWz);
        hash = InteriorSliceSnapshot.mixHash(hash, spanX);
        hash = InteriorSliceSnapshot.mixHash(hash, spanZ);

        LevelChunk[] nearbyChunks = NEARBY_CHUNKS;
        Arrays.fill(nearbyChunks, null);
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(centerCx - 1 + dx, centerCz - 1 + dz);
                if (ch != null && !ch.isEmpty()) {
                    nearbyChunks[dz * 3 + dx] = ch;
                }
            }
        }

        try {
            for (int cz = 0; cz < spanZ; cz++) {
                int wz = oWz + cz;
                int gz = wz - baseWz;
                for (int cx = 0; cx < spanX; cx++) {
                    int wx = oWx + cx;
                    int gx = wx - baseWx;
                    int gridIdx = -1;
                    boolean masked = false;
                    if (gx >= 0 && gx < 48 && gz >= 0 && gz < 48) {
                        gridIdx = (gz >> 4) * 3 + (gx >> 4);
                        boolean[] m = masks[gridIdx];
                        masked = m != null && m[((gz & 15) << 4) | (gx & 15)];
                    }
                    InteriorSliceSnapshot.Cell cell = cells[cz * spanX + cx];
                    cell.reset(wx, wz);
                    if (masked) {
                        LevelChunk chunk = nearbyChunks[gridIdx];
                        if (chunk != null) {
                            InteriorSliceSnapshot.inspectCell(level, chunk, pos, subPos, wx, wz, playerY, minY, cell);
                        }
                    }
                    hash = InteriorSliceSnapshot.mixHash(hash, cell.hashSignature());
                }
            }
        } finally {
            Arrays.fill(nearbyChunks, null);
        }

        if (active && texture != null && hash == lastSnapshotHash
                && oWx == originWorldX && oWz == originWorldZ
                && spanX == worldSpanX && spanZ == worldSpanZ) {
            return;
        }

        int newTexW = spanX * BLOCK_PX;
        int newTexH = spanZ * BLOCK_PX;
        int totalPixels = newTexW * newTexH;
        if (pixelBuffer.length < totalPixels) {
            pixelBuffer = new int[totalPixels];
        }
        int[] fb = pixelBuffer;

        InteriorOverlayPainter.paint(fb, newTexW, newTexH, cells, spanX, spanZ, oWx, oWz, SHADOW_BUF);

        byte[][] mips = AtlasMath.argbSliceToRgbaMipChain(fb, newTexW, newTexH, MAX_MIP_LEVELS, false, 0);

        if (texture == null || texWidthPx != newTexW || texHeightPx != newTexH || texture.mipLevels() != mips.length) {
            Minecraft mc = Minecraft.getInstance();
            if (texture != null) {
                mc.getTextureManager().release(TEXTURE_ID);
                texture = null;
            }
            texture = MipmappedDynamicTexture.createWithMips(
                    () -> "cartographica_interior_overlay", newTexW, newTexH, mips
            );
            mc.getTextureManager().register(TEXTURE_ID, texture);
            texWidthPx = newTexW;
            texHeightPx = newTexH;
        } else {
            texture.uploadAllMips(mips);
        }

        originWorldX = oWx;
        originWorldZ = oWz;
        worldSpanX = spanX;
        worldSpanZ = spanZ;
        lastSnapshotHash = hash;
        active = true;
    }
}
