package com.cartographica_unrolled.data;

import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

public final class AtlasChunkTerrain {
    public static final int COLUMN_COUNT = 256;
    public static final long EMPTY_COLUMN = 0L;

    public static final int FLAG_WATER = 1;
    public static final int FLAG_LAVA = 1 << 1;
    public static final int FLAG_CANOPY = 1 << 2;
    public static final int FLAG_SNOW_ICE = 1 << 3;
    public static final int FLAG_STRUCTURE = 1 << 4;
    public static final int FLAG_CROP_FLORA = 1 << 5;
    public static final int FLAG_SAND_ARID = 1 << 6;
    public static final int FLAG_GLOW = 1 << 7;

    public static final int DETAIL_NONE = 0;
    public static final int DETAIL_FLOWER_SINGLE = 1;
    public static final int DETAIL_FLOWER_CLUSTER = 2;
    public static final int DETAIL_PETALS_LITTER = 3;
    public static final int DETAIL_MUSHROOM = 4;
    public static final int DETAIL_BERRY_BUSH = 5;
    public static final int DETAIL_FERN_TALLGRASS = 6;
    public static final int DETAIL_ARID_FLORA = 7;
    public static final int DETAIL_REEDS_BAMBOO = 8;
    public static final int DETAIL_PUMPKIN_MELON = 9;
    public static final int DETAIL_MOSS_LICHEN = 10;

    public static final int DETAIL_WATER_LILY_PAD = 11;
    public static final int DETAIL_WATER_KELP_SEAGRASS = 12;
    public static final int DETAIL_WATER_CORAL = 13;

    public static final int DETAIL_INDOOR_FLOOR = 16;
    public static final int DETAIL_INDOOR_CARPET = 17;
    public static final int DETAIL_INDOOR_WALL = 18;
    public static final int DETAIL_INDOOR_PORTAL_DOOR = 19;
    public static final int DETAIL_INDOOR_FURNITURE = 20;
    public static final int DETAIL_INDOOR_BED_HEAD = 21;
    public static final int DETAIL_INDOOR_BED_FOOT = 22;
    public static final int DETAIL_INDOOR_CHEST = 23;
    public static final int DETAIL_INDOOR_HEARTH = 24;
    public static final int DETAIL_INDOOR_LIGHT_DECOR = 25;
    public static final int DETAIL_INDOOR_TABLE_CRAFTER = 26;
    public static final int DETAIL_INDOOR_BOOKSHELF = 27;
    public static final int DETAIL_INDOOR_STAIRS = 28;

    public static final int DETAIL_OUTDOOR_PATH_STEP = 30;
    public static final int DETAIL_OUTDOOR_RAIL = 31;
    public static final int DETAIL_OUTDOOR_FENCE_WALL = 32;
    public static final int DETAIL_OUTDOOR_NETHER_PORTAL = 33;
    public static final int DETAIL_OUTDOOR_BRIDGE = 34;
    public static final int DETAIL_OUTDOOR_CAMPFIRE = 35;
    public static final int DETAIL_OUTDOOR_LANTERN_POST = 36;
    public static final int DETAIL_OUTDOOR_WELL_BELL = 37;
    public static final int DETAIL_OUTDOOR_HAY_BALE = 38;
    public static final int DETAIL_OUTDOOR_BANNER = 39;
    public static final int DETAIL_OUTDOOR_CAVE_ENTRANCE = 40;
    public static final int DETAIL_OUTDOOR_CROP_STAGE = 41;

    public static final int CANOPY_NORMAL = 0;
    public static final int CANOPY_SPRUCE_PINE = 1;
    public static final int CANOPY_BIRCH = 2;
    public static final int CANOPY_CHERRY = 3;
    public static final int CANOPY_FLOWERING_AZALEA = 4;
    public static final int CANOPY_MUSHROOM_RED = 5;
    public static final int CANOPY_MUSHROOM_BROWN = 6;
    public static final int CANOPY_DARK_MANGROVE = 7;
    public static final int CANOPY_PALE = 8;

    public static final int ACCENT_NONE = 0;
    public static final int ACCENT_RED = 1;
    public static final int ACCENT_GOLD_YELLOW = 2;
    public static final int ACCENT_CORNFLOWER = 3;
    public static final int ACCENT_ORCHID_CYAN = 4;
    public static final int ACCENT_LILAC_PURPLE = 5;
    public static final int ACCENT_PINK = 6;
    public static final int ACCENT_ORANGE = 7;
    public static final int ACCENT_WHITE_CREAM = 8;
    public static final int ACCENT_DARK_WITHER = 9;
    public static final int ACCENT_BROWN_OCHRE = 10;
    public static final int ACCENT_BERRY_CRIMSON = 11;
    public static final int ACCENT_FIREFLY_AMBER = 12;
    public static final int ACCENT_MELON_GREEN = 13;
    public static final int ACCENT_SPORE_MAGENTA = 14;
    public static final int ACCENT_STONE_GRAY = 15;
    public static final int ACCENT_WOOD_OAK = 16;
    public static final int ACCENT_WALL_TORCH_N = 17;
    public static final int ACCENT_WALL_TORCH_S = 18;
    public static final int ACCENT_WALL_TORCH_W = 19;
    public static final int ACCENT_WALL_TORCH_E = 20;
    public static final int ACCENT_SOUL_WALL_TORCH_N = 21;
    public static final int ACCENT_SOUL_WALL_TORCH_S = 22;
    public static final int ACCENT_SOUL_WALL_TORCH_W = 23;
    public static final int ACCENT_SOUL_WALL_TORCH_E = 24;

    private final long[] columns;
    private final BlockState @Nullable [] blockPalette;
    private final short @Nullable [] baseBlockIndices;
    private final short @Nullable [] overlayBlockIndices;
    private final int contentHash;
    private volatile int persistentHash;

    public AtlasChunkTerrain(long[] columns) {
        this(columns, null, null, null);
    }

    public AtlasChunkTerrain(
            long[] columns,
            BlockState @Nullable [] blockPalette,
            short @Nullable [] baseBlockIndices,
            short @Nullable [] overlayBlockIndices
    ) {
        if (columns.length == COLUMN_COUNT) {
            this.columns = columns;
        } else {
            this.columns = Arrays.copyOf(columns, COLUMN_COUNT);
        }
        if (blockPalette != null && blockPalette.length > 0 && baseBlockIndices != null) {
            this.blockPalette = blockPalette;
            this.baseBlockIndices = baseBlockIndices.length == COLUMN_COUNT
                    ? baseBlockIndices : Arrays.copyOf(baseBlockIndices, COLUMN_COUNT);
            this.overlayBlockIndices = overlayBlockIndices == null ? null
                    : (overlayBlockIndices.length == COLUMN_COUNT
                    ? overlayBlockIndices : Arrays.copyOf(overlayBlockIndices, COLUMN_COUNT));
        } else {
            this.blockPalette = null;
            this.baseBlockIndices = null;
            this.overlayBlockIndices = null;
        }
        int h = Arrays.hashCode(this.columns);
        if (this.blockPalette != null) {
            h = 31 * h + Arrays.hashCode(this.blockPalette);
            h = 31 * h + Arrays.hashCode(this.baseBlockIndices);
            h = 31 * h + Arrays.hashCode(this.overlayBlockIndices);
        }
        this.contentHash = h;
    }

    public long[] columns() {
        return this.columns;
    }

    public int persistentHash() {
        int cached = this.persistentHash;
        if (cached != 0) {
            return cached;
        }
        int h = Arrays.hashCode(this.columns);
        if (this.blockPalette != null) {
            int palHash = 1;
            for (BlockState state : this.blockPalette) {
                palHash = 31 * palHash + BlockStateCodecUtil.encodeStateString(state).hashCode();
            }
            h = 31 * h + palHash;
            h = 31 * h + Arrays.hashCode(this.baseBlockIndices);
            h = 31 * h + Arrays.hashCode(this.overlayBlockIndices);
        }
        if (h == 0) {
            h = 1;
        }
        this.persistentHash = h;
        return h;
    }

    public boolean hasBlockStates() {
        return this.blockPalette != null && this.baseBlockIndices != null;
    }

    public BlockState @Nullable [] blockPalette() {
        return this.blockPalette;
    }

    public short @Nullable [] baseBlockIndices() {
        return this.baseBlockIndices;
    }

    public short @Nullable [] overlayBlockIndices() {
        return this.overlayBlockIndices;
    }

    public boolean equalsContent(@Nullable AtlasChunkTerrain other) {
        if (other == this) {
            return true;
        }
        if (other == null || this.contentHash != other.contentHash) {
            return false;
        }
        if (!Arrays.equals(this.columns, other.columns)) {
            return false;
        }
        return Arrays.equals(this.blockPalette, other.blockPalette)
                && Arrays.equals(this.baseBlockIndices, other.baseBlockIndices)
                && Arrays.equals(this.overlayBlockIndices, other.overlayBlockIndices);
    }

    public long getColumn(int bx, int bz) {
        return this.columns[((bz & 15) << 4) | (bx & 15)];
    }

    public static long pack(int rgb, int surfaceY, int waterDepth, int flags, int canopyDrop, int floorHint, int blockLight) {
        long c = rgb & 0xFFFFFFL;
        long y = Math.clamp(surfaceY + 1024, 0, 4095);
        long wd = Math.clamp(waterDepth, 0, 63);
        long fl = flags & 0xFFL;
        long cd = Math.clamp(canopyDrop, 0, 63);
        long fh = Math.clamp(floorHint, 0, 15);
        long bl = Math.clamp(blockLight, 0, 15);
        return c | (y << 24) | (wd << 36) | (fl << 42) | (cd << 50) | (fh << 56) | (bl << 60);
    }

    public static int unpackRgb(long packed) {
        return (int) (packed & 0xFFFFFFL);
    }

    public static int unpackSurfaceY(long packed) {
        return (int) ((packed >>> 24) & 0xFFFL) - 1024;
    }

    public static int unpackWaterDepth(long packed) {
        return (int) ((packed >>> 36) & 0x3FL);
    }

    public static int unpackFlags(long packed) {
        return (int) ((packed >>> 42) & 0xFFL);
    }

    public static int unpackCanopyDrop(long packed) {
        return (int) ((packed >>> 50) & 0x3FL);
    }

    public static int unpackDetailType(long packed) {
        if ((unpackFlags(packed) & FLAG_CANOPY) != 0) {
            return DETAIL_NONE;
        }
        return (int) ((packed >>> 50) & 0x3FL);
    }

    public static int unpackDetailAccent(long packed) {
        int fl = unpackFlags(packed);
        if ((fl & (FLAG_WATER | FLAG_CANOPY)) != 0) {
            return ACCENT_NONE;
        }
        return (int) ((packed >>> 36) & 0x3FL);
    }

    public static int unpackCanopyKind(long packed) {
        if ((unpackFlags(packed) & FLAG_CANOPY) == 0) {
            return CANOPY_NORMAL;
        }
        return (int) ((packed >>> 36) & 0x3FL);
    }

    public static int unpackFloorHint(long packed) {
        return (int) ((packed >>> 56) & 0xFL);
    }

    public static int unpackBlockLight(long packed) {
        return (int) ((packed >>> 60) & 0xFL);
    }
}
