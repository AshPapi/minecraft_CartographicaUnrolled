package com.cartographica_unrolled.world;

import com.cartographica_unrolled.data.AtlasChunkTerrain;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class IndoorRoomScanner {
    private static final int[] CARDINAL_DX = {-1, 1, 0, 0};
    private static final int[] CARDINAL_DZ = {0, 0, -1, 1};

    private static final class IndoorScratch {
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockPos.MutableBlockPos subPos = new BlockPos.MutableBlockPos();
        final LevelChunk[] chunks = new LevelChunk[9];
        final byte[] cellType = new byte[48 * 48];
        final boolean[] openSky = new boolean[48 * 48];
        final byte[] skyLights = new byte[48 * 48];
        final boolean[] naturalGround = new boolean[48 * 48];
        final boolean[] lowObstacle = new boolean[48 * 48];
        final boolean[] erodeMark = new boolean[48 * 48];
        final boolean[] visitedRoom = new boolean[48 * 48];
        final boolean[] perimeterDoor = new boolean[48 * 48];
        final int[] queue = new int[48 * 48];
        final boolean[][] maskPool = new boolean[9][256];
        final boolean[][] maskView = new boolean[9][];
    }

    private static final ThreadLocal<IndoorScratch> SCRATCH = ThreadLocal.withInitial(IndoorScratch::new);

    private IndoorRoomScanner() {
    }

    public static boolean isPlayerIndoors(
            Level level,
            double playerX,
            double playerEyeY,
            double playerZ,
            BlockPos.MutableBlockPos checkPos
    ) {
        if (level.dimensionType().hasCeiling()) {
            return false;
        }
        int eyeX = Mth.floor(playerX);
        int eyeY = Mth.floor(playerEyeY);
        int eyeZ = Mth.floor(playerZ);
        int maxY = level.getMinY() + level.getHeight() - 1;
        int maxCheckY = Math.min(maxY, eyeY + 24);
        checkPos.set(eyeX, eyeY + 1, eyeZ);
        boolean skyBlocked = !level.canSeeSky(checkPos);
        for (int y = eyeY + 1; y <= maxCheckY; y++) {
            checkPos.set(eyeX, y, eyeZ);
            BlockState state = level.getBlockState(checkPos);
            if (state.isAir() || !state.getFluidState().isEmpty() || AtlasBlockClassifier.isCanopyOrFoliage(state)) {
                continue;
            }
            if (state.canOcclude() || state.isCollisionShapeFullBlock(level, checkPos)
                    || (skyBlocked && !state.getCollisionShape(level, checkPos).isEmpty())) {
                return true;
            }
        }
        if (skyBlocked) {
            int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, eyeX, eyeZ) - 1;
            if (topY > eyeY && topY <= maxCheckY) {
                checkPos.set(eyeX, topY, eyeZ);
                BlockState topState = level.getBlockState(checkPos);
                if (!topState.isAir() && topState.getFluidState().isEmpty()
                        && !AtlasBlockClassifier.isCanopyOrFoliage(topState)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static int hashMask(boolean @Nullable [] mask) {
        if (mask == null) {
            return 0;
        }
        int h = 1;
        for (int i = 0; i < 256; i += 32) {
            int word = 0;
            for (int b = 0; b < 32; b++) {
                if (mask[i + b]) {
                    word |= (1 << b);
                }
            }
            h = 31 * h + word;
        }
        return h == 0 ? 1 : h;
    }

    public static boolean @Nullable [][] buildIndoorSliceMasks(
            Level level,
            int centerCx,
            int centerCz,
            int playerWx,
            int playerY,
            int playerWz
    ) {
        if (level.dimensionType().hasCeiling()) {
            return null;
        }
        int minY = level.getMinY();
        int maxY = minY + level.getHeight() - 1;
        int baseWx = (centerCx - 1) << 4;
        int baseWz = (centerCz - 1) << 4;
        IndoorScratch scratch = SCRATCH.get();
        LevelChunk[] chunks = scratch.chunks;
        for (int dz = 0; dz < 3; dz++) {
            for (int dx = 0; dx < 3; dx++) {
                chunks[dz * 3 + dx] = level.getChunkSource().getChunkNow(centerCx + dx - 1, centerCz + dz - 1);
            }
        }

        byte[] cellType = scratch.cellType;
        boolean[] openSkyPassable = scratch.openSky;
        byte[] skyLights = scratch.skyLights;
        boolean[] naturalGround = scratch.naturalGround;
        boolean[] lowObstacle = scratch.lowObstacle;
        Arrays.fill(cellType, (byte) 0);
        Arrays.fill(openSkyPassable, false);
        Arrays.fill(skyLights, (byte) 0);
        Arrays.fill(naturalGround, false);
        Arrays.fill(lowObstacle, false);
        BlockPos.MutableBlockPos pos = scratch.pos;
        BlockPos.MutableBlockPos subPos = scratch.subPos;

        try {
            for (int gz = 0; gz < 48; gz++) {
                int czIdx = gz >> 4;
                int bz = gz & 15;
                int wz = baseWz + gz;
                for (int gx = 0; gx < 48; gx++) {
                    int cxIdx = gx >> 4;
                    LevelChunk chunk = chunks[czIdx * 3 + cxIdx];
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    int idx = gz * 48 + gx;
                    int bx = gx & 15;
                    int wx = baseWx + gx;
                    subPos.set(wx, playerY + 1, wz);
                    BlockState eyeState = chunk.getBlockState(subPos);
                    skyLights[idx] = (byte) level.getBrightness(LightLayer.SKY, subPos);
                    subPos.set(wx, playerY, wz);
                    BlockState feetState = chunk.getBlockState(subPos);
                    boolean eyePassable = AtlasBlockClassifier.isIndoorPassable(level, subPos.set(wx, playerY + 1, wz), eyeState)
                            || AtlasBlockClassifier.isIndoorFurnitureOrFixture(eyeState);

                    if (feetState.is(BlockTags.DOORS) || eyeState.is(BlockTags.DOORS)
                            || feetState.is(BlockTags.FENCE_GATES) || eyeState.is(BlockTags.FENCE_GATES)) {
                        cellType[idx] = 4;
                        continue;
                    }

                    int roofY = Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) + 1);
                    while (roofY > minY) {
                        pos.set(wx, roofY, wz);
                        BlockState bs = chunk.getBlockState(pos);
                        if (AtlasBlockClassifier.isCanopyBlock(bs) || AtlasBlockClassifier.isGroundFloraToSkip(bs)
                                || (bs.getMapColor(level, pos) == MapColor.NONE && !AtlasBlockClassifier.isStructureOrRoad(bs))) {
                            roofY--;
                            continue;
                        }
                        break;
                    }
                    if (roofY <= playerY + 1) {
                        if (eyePassable) {
                            openSkyPassable[idx] = true;
                        } else if (!eyeState.isAir()
                                && (eyeState.getMapColor(level, subPos.set(wx, playerY + 1, wz)) != MapColor.NONE
                                        || AtlasBlockClassifier.isStructureOrRoad(eyeState))) {
                            cellType[idx] = 3;
                        }
                        continue;
                    }
                    pos.set(wx, roofY, wz);
                    if (!chunk.getBlockState(pos).getFluidState().isEmpty()) {
                        if (eyePassable) {
                            openSkyPassable[idx] = true;
                        }
                        continue;
                    }

                    int maxCeilingSearchY = Math.min(roofY, playerY + 24);
                    int ceilingY = Integer.MIN_VALUE;
                    for (int cy = playerY + 2; cy <= maxCeilingSearchY; cy++) {
                        subPos.set(wx, cy, wz);
                        BlockState cState = chunk.getBlockState(subPos);
                        if (!cState.isAir() && cState.getFluidState().isEmpty()
                                && !AtlasBlockClassifier.isCanopyBlock(cState)
                                && !AtlasBlockClassifier.isGroundFloraToSkip(cState)
                                && (cState.canOcclude() || AtlasBlockClassifier.isStructureOrRoad(cState)
                                        || AtlasBlockClassifier.isTranslucentBlock(cState)
                                        || cState.isCollisionShapeFullBlock(level, subPos))) {
                            ceilingY = cy;
                            break;
                        }
                    }
                    if (ceilingY == Integer.MIN_VALUE) {
                        if (eyePassable) {
                            openSkyPassable[idx] = true;
                        } else if (!eyeState.isAir()
                                && (eyeState.getMapColor(level, subPos.set(wx, playerY + 1, wz)) != MapColor.NONE
                                        || AtlasBlockClassifier.isStructureOrRoad(eyeState))) {
                            cellType[idx] = 3;
                        }
                        continue;
                    }

                    boolean feetPassable = AtlasBlockClassifier.isIndoorPassable(level, subPos.set(wx, playerY, wz), feetState);
                    boolean feetFurniture = AtlasBlockClassifier.isIndoorFurnitureOrFixture(feetState)
                            || (eyePassable && !feetState.isCollisionShapeFullBlock(level, subPos.set(wx, playerY, wz)));
                    boolean feetLowSolid = eyePassable && !feetPassable && !feetFurniture
                            && !feetState.isAir()
                            && (feetState.getMapColor(level, subPos.set(wx, playerY, wz)) != MapColor.NONE
                                    || AtlasBlockClassifier.isStructureOrRoad(feetState));

                    if (eyePassable && (feetPassable || feetFurniture || feetLowSolid)) {
                        int floorY = Integer.MIN_VALUE;
                        BlockState floorState = Blocks.AIR.defaultBlockState();
                        int minFloorY = Math.max(minY, playerY - 3);
                        for (int fy = playerY; fy >= minFloorY; fy--) {
                            subPos.set(wx, fy, wz);
                            BlockState fs = chunk.getBlockState(subPos);
                            if (!fs.isAir() && fs.getFluidState().isEmpty()
                                    && !AtlasBlockClassifier.isGroundFloraToSkip(fs)
                                    && (fs.getMapColor(level, subPos) != MapColor.NONE
                                            || AtlasBlockClassifier.isStructureOrRoad(fs))) {
                                floorY = fy;
                                floorState = fs;
                                break;
                            }
                        }
                        if (floorY != Integer.MIN_VALUE) {
                            naturalGround[idx] = AtlasBlockClassifier.isOutdoorNaturalGround(floorState);
                            cellType[idx] = (byte) (feetPassable ? 1 : 2);
                            if (feetLowSolid) {
                                lowObstacle[idx] = true;
                            }
                            continue;
                        }
                    }

                    if ((!eyeState.isAir() && (eyeState.getMapColor(level, subPos.set(wx, playerY + 1, wz)) != MapColor.NONE
                            || AtlasBlockClassifier.isStructureOrRoad(eyeState)))
                            || (!feetState.isAir() && (feetState.getMapColor(level, subPos.set(wx, playerY, wz)) != MapColor.NONE
                                    || AtlasBlockClassifier.isStructureOrRoad(feetState)))) {
                        cellType[idx] = 3;
                    }
                }
            }

            int[] dxs = CARDINAL_DX;
            int[] dzs = CARDINAL_DZ;
            for (int gz = 0; gz < 48; gz++) {
                for (int gx = 0; gx < 48; gx++) {
                    int idx = gz * 48 + gx;
                    if (!lowObstacle[idx]) {
                        continue;
                    }
                    boolean touchesOutside = false;
                    for (int i = 0; i < 4; i++) {
                        int nx = gx + dxs[i];
                        int nz = gz + dzs[i];
                        if (nx < 0 || nx >= 48 || nz < 0 || nz >= 48) {
                            touchesOutside = true;
                            break;
                        }
                        int nIdx = nz * 48 + nx;
                        if (openSkyPassable[nIdx] || cellType[nIdx] == 0) {
                            touchesOutside = true;
                            break;
                        }
                    }
                    if (touchesOutside) {
                        cellType[idx] = 3;
                    }
                }
            }

            boolean[] erodeMark = scratch.erodeMark;
            for (int pass = 0; pass < 3; pass++) {
                boolean anyEroded = false;
                Arrays.fill(erodeMark, false);
                for (int gz = 0; gz < 48; gz++) {
                    for (int gx = 0; gx < 48; gx++) {
                        int idx = gz * 48 + gx;
                        if (cellType[idx] != 1 && cellType[idx] != 2) {
                            continue;
                        }
                        int skyNbrs = 0;
                        for (int i = 0; i < 4; i++) {
                            int nx = gx + dxs[i];
                            int nz = gz + dzs[i];
                            if (nx >= 0 && nx < 48 && nz >= 0 && nz < 48 && openSkyPassable[nz * 48 + nx]) {
                                skyNbrs++;
                            }
                        }
                        if (skyNbrs == 0) {
                            continue;
                        }
                        boolean wallW = gx > 0 && (cellType[idx - 1] == 3 || cellType[idx - 1] == 4);
                        boolean wallE = gx < 47 && (cellType[idx + 1] == 3 || cellType[idx + 1] == 4);
                        boolean wallN = gz > 0 && (cellType[idx - 48] == 3 || cellType[idx - 48] == 4);
                        boolean wallS = gz < 47 && (cellType[idx + 48] == 3 || cellType[idx + 48] == 4);
                        if ((wallW && wallE) || (wallN && wallS)) {
                            continue;
                        }
                        int sl = skyLights[idx] & 0xFF;
                        int wallNbrs = (wallW ? 1 : 0) + (wallE ? 1 : 0) + (wallN ? 1 : 0) + (wallS ? 1 : 0);
                        if (skyNbrs >= 2 || (naturalGround[idx] && sl >= 10) || (wallNbrs == 0 && sl >= 14)) {
                            erodeMark[idx] = true;
                            anyEroded = true;
                        }
                    }
                }
                if (!anyEroded) {
                    break;
                }
                for (int i = 0; i < 48 * 48; i++) {
                    if (erodeMark[i]) {
                        cellType[i] = 0;
                        openSkyPassable[i] = true;
                    }
                }
            }

            int pGx = playerWx - baseWx;
            int pGz = playerWz - baseWz;
            if (pGx < 0 || pGx >= 48 || pGz < 0 || pGz >= 48) {
                return null;
            }
            int pIdx = pGz * 48 + pGx;
            if (cellType[pIdx] == 0) {
                return null;
            }

            boolean[] visitedRoom = scratch.visitedRoom;
            boolean[] perimeterDoor = scratch.perimeterDoor;
            int[] queue = scratch.queue;
            Arrays.fill(visitedRoom, false);
            Arrays.fill(perimeterDoor, false);
            int head = 0;
            int tail = 0;

            if (cellType[pIdx] == 1 || cellType[pIdx] == 2) {
                visitedRoom[pIdx] = true;
                queue[tail++] = pIdx;
            } else {
                for (int i = 0; i < 4; i++) {
                    int sx = pGx + dxs[i];
                    int sz = pGz + dzs[i];
                    if (sx >= 0 && sx < 48 && sz >= 0 && sz < 48) {
                        int sIdx = sz * 48 + sx;
                        if ((cellType[sIdx] == 1 || cellType[sIdx] == 2) && !visitedRoom[sIdx]) {
                            visitedRoom[sIdx] = true;
                            queue[tail++] = sIdx;
                        }
                    }
                }
            }
            if (tail == 0) {
                return null;
            }

            int visitedCount = tail;
            while (head < tail) {
                int cur = queue[head++];
                int cx = cur % 48;
                int cz = cur / 48;
                for (int i = 0; i < 4; i++) {
                    int nx = cx + dxs[i];
                    int nz = cz + dzs[i];
                    if (nx < 0 || nx >= 48 || nz < 0 || nz >= 48) {
                        continue;
                    }
                    int nIdx = nz * 48 + nx;
                    byte nt = cellType[nIdx];
                    if ((nt == 1 || nt == 2) && !visitedRoom[nIdx]) {
                        visitedRoom[nIdx] = true;
                        visitedCount++;
                        queue[tail++] = nIdx;
                    } else if (nt == 4 && !visitedRoom[nIdx]) {
                        boolean touchesOutside = false;
                        for (int j = 0; j < 4; j++) {
                            int dx2 = nx + dxs[j];
                            int dz2 = nz + dzs[j];
                            if (dx2 < 0 || dx2 >= 48 || dz2 < 0 || dz2 >= 48) {
                                touchesOutside = true;
                                break;
                            }
                            int dIdx = dz2 * 48 + dx2;
                            if (cellType[dIdx] == 0 || openSkyPassable[dIdx]) {
                                touchesOutside = true;
                                break;
                            }
                        }
                        if (touchesOutside) {
                            perimeterDoor[nIdx] = true;
                        } else {
                            visitedRoom[nIdx] = true;
                            visitedCount++;
                            queue[tail++] = nIdx;
                        }
                    }
                }
            }

            if (visitedCount < 2 && (skyLights[pIdx] & 0xFF) >= 13) {
                return null;
            }

            boolean[][] masks = scratch.maskView;
            Arrays.fill(masks, null);
            for (int gz = 0; gz < 48; gz++) {
                for (int gx = 0; gx < 48; gx++) {
                    int idx = gz * 48 + gx;
                    boolean slice = visitedRoom[idx] || perimeterDoor[idx];
                    if (!slice && (cellType[idx] == 2 || cellType[idx] == 3 || cellType[idx] == 4)) {
                        for (int dz = -1; dz <= 1 && !slice; dz++) {
                            int nz = gz + dz;
                            if (nz < 0 || nz >= 48) {
                                continue;
                            }
                            for (int dx = -1; dx <= 1; dx++) {
                                int nx = gx + dx;
                                if (nx >= 0 && nx < 48 && visitedRoom[nz * 48 + nx]) {
                                    slice = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (slice) {
                        int chunkIdx = (gz >> 4) * 3 + (gx >> 4);
                        if (masks[chunkIdx] == null) {
                            boolean[] buf = scratch.maskPool[chunkIdx];
                            Arrays.fill(buf, false);
                            masks[chunkIdx] = buf;
                        }
                        masks[chunkIdx][((gz & 15) << 4) | (gx & 15)] = true;
                    }
                }
            }
            return masks;
        } finally {
            Arrays.fill(chunks, null);
        }
    }

    static long sampleIndoorSlicedColumn(
            Level level,
            LevelChunk chunk,
            BiomeTintCache tintCache,
            BlockPos.MutableBlockPos pos,
            BlockPos.MutableBlockPos subPos,
            int wx,
            int wz,
            int bx,
            int bz,
            int surfaceY,
            int playerY,
            int minY,
            boolean prevalidatedByMask,
            BlockState[] outStates
    ) {
        int roofY = surfaceY;
        boolean startedWithCanopy = false;
        while (roofY > minY) {
            pos.set(wx, roofY, wz);
            BlockState bs = chunk.getBlockState(pos);
            if (AtlasBlockClassifier.isCanopyBlock(bs)) {
                startedWithCanopy = true;
                roofY--;
                continue;
            }
            if (AtlasBlockClassifier.isGroundFloraToSkip(bs)
                    || (bs.getMapColor(level, pos) == MapColor.NONE && !AtlasBlockClassifier.isStructureOrRoad(bs))) {
                roofY--;
                continue;
            }
            break;
        }
        if (roofY <= playerY + 1) {
            return AtlasChunkTerrain.EMPTY_COLUMN;
        }
        pos.set(wx, roofY, wz);
        if (!chunk.getBlockState(pos).getFluidState().isEmpty()) {
            return AtlasChunkTerrain.EMPTY_COLUMN;
        }

        subPos.set(wx, playerY + 1, wz);
        BlockState eyeState = chunk.getBlockState(subPos);
        pos.set(wx, playerY, wz);
        BlockState feetState = chunk.getBlockState(pos);
        boolean eyeOpen = AtlasBlockClassifier.isIndoorPassable(level, subPos, eyeState)
                || AtlasBlockClassifier.isIndoorFurnitureOrFixture(eyeState);

        if (!prevalidatedByMask) {
            if (startedWithCanopy && !eyeOpen) {
                return AtlasChunkTerrain.EMPTY_COLUMN;
            }
            if (!eyeOpen) {
                boolean adjacentOpen = false;
                for (int i = 0; i < 4; i++) {
                    int nbx = bx + CARDINAL_DX[i];
                    int nbz = bz + CARDINAL_DZ[i];
                    if (nbx < 0 || nbx >= 16 || nbz < 0 || nbz >= 16) {
                        continue;
                    }
                    subPos.set(wx + CARDINAL_DX[i], playerY + 1, wz + CARDINAL_DZ[i]);
                    BlockState nEye = chunk.getBlockState(subPos);
                    if (AtlasBlockClassifier.isIndoorPassable(level, subPos, nEye)
                            || AtlasBlockClassifier.isIndoorFurnitureOrFixture(nEye)) {
                        int nTop = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nbx, nbz) - 1;
                        if (nTop > playerY + 2) {
                            adjacentOpen = true;
                            break;
                        }
                    }
                }
                if (!adjacentOpen) {
                    return AtlasChunkTerrain.EMPTY_COLUMN;
                }
            }
        }

        subPos.set(wx, playerY + 1, wz);
        pos.set(wx, playerY, wz);
        int sliceEmission = Math.max(eyeState.getLightEmission(level, subPos), feetState.getLightEmission(level, pos));
        int blockLight = Math.max(
                sliceEmission,
                Math.max(level.getBrightness(LightLayer.BLOCK, subPos),
                        level.getBrightness(LightLayer.BLOCK, pos))
        );
        int floorHint = Mth.clamp(15 - level.getBrightness(LightLayer.SKY, subPos), 0, 15);
        int flags = AtlasChunkTerrain.FLAG_STRUCTURE;
        if (sliceEmission >= 10) {
            flags |= AtlasChunkTerrain.FLAG_GLOW;
        }

        if (feetState.is(BlockTags.DOORS) || eyeState.is(BlockTags.DOORS)
                || feetState.is(BlockTags.FENCE_GATES) || eyeState.is(BlockTags.FENCE_GATES)) {
            BlockState doorState = (feetState.is(BlockTags.DOORS) || feetState.is(BlockTags.FENCE_GATES)) ? feetState : eyeState;
            outStates[0] = doorState;
            int rgb = resolveBlockRgb(level, tintCache, pos.set(wx, playerY, wz), doorState, 0x8A673B);
            return AtlasChunkTerrain.pack(
                    rgb, playerY, AtlasChunkTerrain.ACCENT_WOOD_OAK, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_PORTAL_DOOR, floorHint, blockLight
            );
        }

        if (AtlasBlockClassifier.isWindowOrBars(eyeState) || (!eyeOpen && AtlasBlockClassifier.isWindowOrBars(feetState))) {
            outStates[0] = AtlasBlockClassifier.isWindowOrBars(eyeState) ? eyeState : feetState;
            return AtlasChunkTerrain.pack(
                    0x98C6D4, playerY + 1, AtlasChunkTerrain.ACCENT_ORCHID_CYAN, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_PORTAL_DOOR, floorHint, blockLight
            );
        }

        if (eyeState.is(Blocks.BOOKSHELF) || eyeState.is(Blocks.CHISELED_BOOKSHELF)
                || feetState.is(Blocks.BOOKSHELF) || feetState.is(Blocks.CHISELED_BOOKSHELF)) {
            outStates[0] = (eyeState.is(Blocks.BOOKSHELF) || eyeState.is(Blocks.CHISELED_BOOKSHELF)) ? eyeState : feetState;
            return AtlasChunkTerrain.pack(
                    0x8A673B, playerY + 1, AtlasChunkTerrain.ACCENT_WOOD_OAK, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_BOOKSHELF, floorHint, blockLight
            );
        }

        if (!eyeOpen) {
            outStates[0] = eyeState;
            int wallRgb = resolveBlockRgb(level, tintCache, subPos.set(wx, playerY + 1, wz), eyeState, 0x786048);
            return AtlasChunkTerrain.pack(
                    wallRgb, playerY + 2, AtlasChunkTerrain.ACCENT_NONE, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_WALL, floorHint, blockLight
            );
        }

        int minFloorY = Math.max(minY, playerY - 4);
        int floorY = playerY - 1;
        BlockState floorState = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState fixtureState = Blocks.AIR.defaultBlockState();
        int fixtureY = playerY;

        if ((!eyeState.isAir() && !AtlasBlockClassifier.isGroundFloraToSkip(eyeState))
                || AtlasBlockClassifier.isIndoorFurnitureOrFixture(eyeState)) {
            fixtureState = eyeState;
            fixtureY = playerY + 1;
        }

        for (int fy = playerY; fy >= minFloorY; fy--) {
            subPos.set(wx, fy, wz);
            BlockState bs = chunk.getBlockState(subPos);
            if (bs.isAir()) {
                continue;
            }
            if (fy >= playerY && (AtlasBlockClassifier.isIndoorFurnitureOrFixture(bs)
                    || bs.is(BlockTags.FENCES) || bs.is(BlockTags.TRAPDOORS)
                    || bs.is(BlockTags.STAIRS) || bs.is(BlockTags.SLABS))) {
                if (fixtureState.isAir()) {
                    fixtureState = bs;
                    fixtureY = fy;
                }
                if (fy == playerY && (bs.is(BlockTags.STAIRS) || bs.is(BlockTags.SLABS))
                        && bs.isCollisionShapeFullBlock(level, subPos)) {
                    floorY = fy;
                    floorState = bs;
                    break;
                }
                continue;
            }
            if (bs.getMapColor(level, subPos) != MapColor.NONE
                    || AtlasBlockClassifier.isStructureOrRoad(bs)
                    || !bs.getFluidState().isEmpty()) {
                floorY = fy;
                floorState = bs;
                break;
            }
        }

        if (!floorState.getFluidState().isEmpty()) {
            if (floorState.getFluidState().is(FluidTags.LAVA)) {
                outStates[0] = Blocks.LAVA.defaultBlockState();
                return AtlasChunkTerrain.pack(0xD95B16, floorY, 0,
                        AtlasChunkTerrain.FLAG_LAVA | AtlasChunkTerrain.FLAG_GLOW, 0, 0, 15);
            }
            outStates[0] = Blocks.WATER.defaultBlockState();
            int wRgb = tintCache.sampleWaterTint(level, subPos.set(wx, floorY, wz));
            return AtlasChunkTerrain.pack(wRgb, floorY, 2, AtlasChunkTerrain.FLAG_WATER, 0, 8, blockLight);
        }

        outStates[0] = floorState;
        if (!fixtureState.isAir()) {
            outStates[1] = fixtureState;
            subPos.set(wx, fixtureY, wz);
            int fixtureEmission = fixtureState.getLightEmission(level, subPos);
            if (fixtureEmission >= 10) {
                flags |= AtlasChunkTerrain.FLAG_GLOW;
                blockLight = Math.max(blockLight, fixtureEmission);
            }
            if (fixtureState.is(BlockTags.BEDS)) {
                int bedRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0xB83832);
                boolean isHead = fixtureState.hasProperty(BlockStateProperties.BED_PART)
                        && fixtureState.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD;
                int bedDetail = isHead ? AtlasChunkTerrain.DETAIL_INDOOR_BED_HEAD : AtlasChunkTerrain.DETAIL_INDOOR_BED_FOOT;
                return AtlasChunkTerrain.pack(
                        bedRgb, floorY + 1, AtlasChunkTerrain.ACCENT_WHITE_CREAM, flags, bedDetail, floorHint, blockLight
                );
            }
            if (fixtureState.is(Blocks.CHEST) || fixtureState.is(Blocks.TRAPPED_CHEST)
                    || fixtureState.is(Blocks.ENDER_CHEST) || fixtureState.is(Blocks.BARREL)) {
                int chestRgb = fixtureState.is(Blocks.ENDER_CHEST) ? 0x28423E
                        : fixtureState.is(Blocks.BARREL) ? 0x7E5C36 : 0x9E7339;
                return AtlasChunkTerrain.pack(
                        chestRgb, floorY + 1, AtlasChunkTerrain.ACCENT_GOLD_YELLOW, flags,
                        AtlasChunkTerrain.DETAIL_INDOOR_CHEST, floorHint, blockLight
                );
            }
            if (fixtureState.is(Blocks.FURNACE) || fixtureState.is(Blocks.SMOKER)
                    || fixtureState.is(Blocks.BLAST_FURNACE) || fixtureState.is(Blocks.CAMPFIRE)
                    || fixtureState.is(Blocks.SOUL_CAMPFIRE) || fixtureState.is(Blocks.BREWING_STAND)
                    || fixtureState.is(Blocks.CAULDRON) || fixtureState.is(Blocks.WATER_CAULDRON)
                    || fixtureState.is(Blocks.LAVA_CAULDRON) || fixtureState.is(Blocks.POWDER_SNOW_CAULDRON)) {
                boolean lit;
                if (fixtureState.is(Blocks.CAMPFIRE) || fixtureState.is(Blocks.SOUL_CAMPFIRE)) {
                    lit = !fixtureState.hasProperty(BlockStateProperties.LIT) || fixtureState.getValue(BlockStateProperties.LIT);
                } else {
                    lit = fixtureState.is(Blocks.LAVA_CAULDRON)
                            || (fixtureState.hasProperty(BlockStateProperties.LIT) && fixtureState.getValue(BlockStateProperties.LIT));
                }
                if (lit) {
                    flags |= AtlasChunkTerrain.FLAG_GLOW;
                    blockLight = Math.max(blockLight, 14);
                }
                int hRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0x6E6B66);
                int accent = lit
                        ? (fixtureState.is(Blocks.SOUL_CAMPFIRE) ? AtlasChunkTerrain.ACCENT_ORCHID_CYAN : AtlasChunkTerrain.ACCENT_ORANGE)
                        : AtlasChunkTerrain.ACCENT_STONE_GRAY;
                return AtlasChunkTerrain.pack(
                        hRgb, floorY + 1, accent, flags, AtlasChunkTerrain.DETAIL_INDOOR_HEARTH, floorHint, blockLight
                );
            }
            if (fixtureState.is(Blocks.CRAFTING_TABLE) || fixtureState.is(Blocks.CARTOGRAPHY_TABLE)
                    || fixtureState.is(Blocks.FLETCHING_TABLE) || fixtureState.is(Blocks.SMITHING_TABLE)
                    || fixtureState.is(Blocks.LOOM) || fixtureState.is(Blocks.STONECUTTER)
                    || fixtureState.is(Blocks.GRINDSTONE) || fixtureState.is(Blocks.ANVIL)
                    || fixtureState.is(Blocks.CHIPPED_ANVIL) || fixtureState.is(Blocks.DAMAGED_ANVIL)
                    || fixtureState.is(Blocks.ENCHANTING_TABLE) || fixtureState.is(Blocks.LECTERN)
                    || fixtureState.is(Blocks.COMPOSTER) || fixtureState.is(Blocks.SCAFFOLDING)) {
                int tRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0x8A673B);
                return AtlasChunkTerrain.pack(
                        tRgb, floorY + 1, AtlasChunkTerrain.ACCENT_WOOD_OAK, flags,
                        AtlasChunkTerrain.DETAIL_INDOOR_TABLE_CRAFTER, floorHint, blockLight
                );
            }
            if (fixtureState.is(Blocks.LANTERN) || fixtureState.is(Blocks.SOUL_LANTERN)
                    || fixtureState.is(BlockTags.CANDLES) || fixtureState.is(BlockTags.FLOWER_POTS)
                    || fixtureState.is(Blocks.DECORATED_POT) || fixtureState.is(Blocks.BELL)
                    || fixtureState.is(Blocks.CAKE)) {
                int fRgb = resolveBlockRgb(level, tintCache, subPos.set(wx, floorY, wz), floorState, 0x8A6B4A);
                int accent;
                if (fixtureState.is(Blocks.SOUL_LANTERN)) {
                    accent = AtlasChunkTerrain.ACCENT_ORCHID_CYAN;
                    flags |= AtlasChunkTerrain.FLAG_GLOW;
                } else if (fixtureState.is(Blocks.LANTERN)) {
                    accent = AtlasChunkTerrain.ACCENT_FIREFLY_AMBER;
                    flags |= AtlasChunkTerrain.FLAG_GLOW;
                } else if (fixtureState.is(BlockTags.CANDLES)) {
                    boolean litCandle = fixtureState.hasProperty(BlockStateProperties.LIT)
                            && fixtureState.getValue(BlockStateProperties.LIT);
                    if (litCandle) {
                        accent = AtlasChunkTerrain.ACCENT_FIREFLY_AMBER;
                        flags |= AtlasChunkTerrain.FLAG_GLOW;
                        blockLight = Math.max(blockLight, Math.max(fixtureEmission, 6));
                    } else {
                        accent = AtlasChunkTerrain.ACCENT_WHITE_CREAM;
                    }
                } else if (fixtureState.is(Blocks.BELL)) {
                    accent = AtlasChunkTerrain.ACCENT_GOLD_YELLOW;
                } else {
                    accent = AtlasChunkTerrain.ACCENT_RED;
                }
                return AtlasChunkTerrain.pack(
                        fRgb, floorY, accent, flags, AtlasChunkTerrain.DETAIL_INDOOR_LIGHT_DECOR, floorHint, blockLight
                );
            }
            if (fixtureState.is(BlockTags.WOOL_CARPETS) || fixtureState.is(Blocks.MOSS_CARPET)
                    || fixtureState.is(Blocks.PALE_MOSS_CARPET)) {
                int cRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0xB83832);
                return AtlasChunkTerrain.pack(
                        cRgb, floorY, AtlasChunkTerrain.ACCENT_NONE, flags,
                        AtlasChunkTerrain.DETAIL_INDOOR_CARPET, floorHint, blockLight
                );
            }
            if (fixtureState.is(BlockTags.STAIRS)) {
                int sRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0x8A6B4A);
                return AtlasChunkTerrain.pack(
                        sRgb, floorY, AtlasChunkTerrain.ACCENT_NONE, flags,
                        AtlasChunkTerrain.DETAIL_INDOOR_STAIRS, floorHint, blockLight
                );
            }
            int furnRgb = resolveBlockRgb(level, tintCache, subPos, fixtureState, 0x8A6B4A);
            return AtlasChunkTerrain.pack(
                    furnRgb, floorY + 1, AtlasChunkTerrain.ACCENT_WOOD_OAK, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_FURNITURE, floorHint, blockLight
            );
        }

        subPos.set(wx, floorY, wz);
        int floorRgb = resolveBlockRgb(level, tintCache, subPos, floorState, 0x8A6B4A);
        if (floorState.is(BlockTags.STAIRS)) {
            return AtlasChunkTerrain.pack(
                    floorRgb, floorY, AtlasChunkTerrain.ACCENT_NONE, flags,
                    AtlasChunkTerrain.DETAIL_INDOOR_STAIRS, floorHint, blockLight
            );
        }
        int floorAccent = (floorState.is(BlockTags.PLANKS) || floorState.is(BlockTags.LOGS))
                ? AtlasChunkTerrain.ACCENT_WOOD_OAK
                : AtlasChunkTerrain.ACCENT_STONE_GRAY;
        return AtlasChunkTerrain.pack(
                floorRgb, floorY, floorAccent, flags, AtlasChunkTerrain.DETAIL_INDOOR_FLOOR, floorHint, blockLight
        );
    }

    private static int resolveBlockRgb(
            Level level,
            BiomeTintCache tintCache,
            BlockPos pos,
            BlockState state,
            int fallback
    ) {
        MapColor mc = state.getMapColor(level, pos);
        int rgb = mc != MapColor.NONE && mc.col != 0 ? mc.col : fallback;
        if (AtlasBlockClassifier.isBiomeGrassTinted(state, mc)) {
            rgb = AtlasMath.mixRgb(tintCache.sampleGrassTint(level, pos), 0x7A9E48, 0.12f);
        }
        return rgb;
    }
}
