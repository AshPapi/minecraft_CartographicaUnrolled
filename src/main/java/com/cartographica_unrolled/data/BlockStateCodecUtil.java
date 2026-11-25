package com.cartographica_unrolled.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class BlockStateCodecUtil {
    public static final int MAX_PALETTE = 512;
    public static final int SEGMENTED_MAP_MAGIC = 0x43415435;
    private static final int CHUNK_MAP_MAGIC = 0x4341544D;
    private static final int CHUNK_MAP_VERSION = 4;
    private static final int MAX_STRING_CACHE = 4096;
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final ConcurrentHashMap<BlockState, String> ENCODE_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<String, BlockState> DECODE_CACHE = new ConcurrentHashMap<>(256);
    private static final ThreadLocal<Deflater> THREAD_DEFLATER =
            ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));

    private BlockStateCodecUtil() {
    }

    public static BlockState canonicalize(BlockState state) {
        if (state == null || state.isAir()) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState def = state.getBlock().defaultBlockState();
        if (state == def) {
            return state;
        }
        BlockState out = state;
        out = resetIfPresent(out, def, BlockStateProperties.DISTANCE);
        out = resetIfPresent(out, def, BlockStateProperties.PERSISTENT);
        out = resetIfPresent(out, def, BlockStateProperties.WATERLOGGED);
        out = resetIfPresent(out, def, BlockStateProperties.STAGE);
        out = resetIfPresent(out, def, BlockStateProperties.NOTE);
        out = resetIfPresent(out, def, BlockStateProperties.NOTEBLOCK_INSTRUMENT);
        out = resetIfPresent(out, def, BlockStateProperties.TRIGGERED);
        out = resetIfPresent(out, def, BlockStateProperties.UNSTABLE);
        out = resetIfPresent(out, def, BlockStateProperties.OCCUPIED);
        return out;
    }

    private static <T extends Comparable<T>> BlockState resetIfPresent(BlockState state, BlockState def, Property<T> prop) {
        if (state.hasProperty(prop)) {
            return state.setValue(prop, def.getValue(prop));
        }
        return state;
    }

    public static String encodeStateString(BlockState state) {
        if (state == null || state.isAir()) {
            return "minecraft:air";
        }
        String cached = ENCODE_CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        Block block = state.getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        BlockState def = block.defaultBlockState();
        StringBuilder sb = null;
        for (Property<?> prop : state.getProperties()) {
            Comparable<?> val = state.getValue(prop);
            if (!val.equals(def.getValue(prop))) {
                if (sb == null) {
                    sb = new StringBuilder(id.toString()).append('[');
                } else {
                    sb.append(',');
                }
                sb.append(prop.getName()).append('=').append(getValueName(prop, val));
            }
        }
        String result = sb == null ? id.toString() : sb.append(']').toString();
        if (ENCODE_CACHE.size() < MAX_STRING_CACHE) {
            ENCODE_CACHE.putIfAbsent(state, result);
        }
        return result;
    }

    public static BlockState decodeStateString(String encoded) {
        if (encoded == null || encoded.isEmpty() || "minecraft:air".equals(encoded)) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState cached = DECODE_CACHE.get(encoded);
        if (cached != null) {
            return cached;
        }
        int bracket = encoded.indexOf('[');
        String idPart = bracket >= 0 ? encoded.substring(0, bracket) : encoded;
        Identifier id = Identifier.tryParse(idPart);
        if (id == null) {
            return Blocks.AIR.defaultBlockState();
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == null || block == Blocks.AIR) {
            return Blocks.AIR.defaultBlockState();
        }
        BlockState state = block.defaultBlockState();
        if (bracket >= 0 && encoded.endsWith("]")) {
            String propsStr = encoded.substring(bracket + 1, encoded.length() - 1);
            if (!propsStr.isEmpty()) {
                StateDefinition<Block, BlockState> definition = block.getStateDefinition();
                int start = 0;
                while (start < propsStr.length()) {
                    int comma = propsStr.indexOf(',', start);
                    String pair = comma >= 0 ? propsStr.substring(start, comma) : propsStr.substring(start);
                    int eq = pair.indexOf('=');
                    if (eq > 0 && eq < pair.length() - 1) {
                        String k = pair.substring(0, eq);
                        String v = pair.substring(eq + 1);
                        Property<?> prop = definition.getProperty(k);
                        if (prop != null) {
                            state = applyPropertyValue(state, prop, v);
                        }
                    }
                    if (comma < 0) {
                        break;
                    }
                    start = comma + 1;
                }
            }
        }
        if (DECODE_CACHE.size() < MAX_STRING_CACHE) {
            DECODE_CACHE.putIfAbsent(encoded, state);
        }
        return state;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getValueName(Property<T> prop, Comparable<?> val) {
        return prop.getName((T) val);
    }

    private static <T extends Comparable<T>> BlockState applyPropertyValue(BlockState state, Property<T> prop, String valueName) {
        Optional<T> parsed = prop.getValue(valueName);
        return parsed.map(t -> state.setValue(prop, t)).orElse(state);
    }

    private static String safeEncodedString(BlockState state) {
        String encoded = encodeStateString(state);
        if (encoded.length() > 1024) {
            encoded = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (encoded.length() > 1024) {
                encoded = "minecraft:air";
            }
        }
        return encoded;
    }

    public static void writeTerrainToBuf(FriendlyByteBuf buf, AtlasChunkTerrain terrain) {
        long[] cols = terrain.columns();
        for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
            buf.writeLong(i < cols.length ? cols[i] : 0L);
        }
        BlockState[] palette = terrain.blockPalette();
        int palLen = (terrain.hasBlockStates() && palette != null) ? Math.min(palette.length, MAX_PALETTE) : 0;
        if (palLen <= 0) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(palLen);
        for (int i = 0; i < palLen; i++) {
            buf.writeUtf(safeEncodedString(palette[i]), 1024);
        }
        boolean wide = palLen > 255;
        writeIndexArrayBuf(buf, terrain.baseBlockIndices(), wide);
        short[] overlay = terrain.overlayBlockIndices();
        if (overlay == null) {
            buf.writeBoolean(false);
        } else {
            buf.writeBoolean(true);
            writeIndexArrayBuf(buf, overlay, wide);
        }
    }

    public static AtlasChunkTerrain readTerrainFromBuf(FriendlyByteBuf buf) {
        long[] cols = new long[AtlasChunkTerrain.COLUMN_COUNT];
        for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
            cols[i] = buf.readLong();
        }
        if (!buf.readBoolean()) {
            return new AtlasChunkTerrain(cols);
        }
        int palLen = Math.min(Math.max(0, buf.readVarInt()), MAX_PALETTE);
        if (palLen == 0) {
            return new AtlasChunkTerrain(cols);
        }
        BlockState[] palette = new BlockState[palLen];
        for (int i = 0; i < palLen; i++) {
            palette[i] = decodeStateString(buf.readUtf(1024));
        }
        boolean wide = palLen > 255;
        short[] base = readIndexArrayBuf(buf, wide, palLen);
        short[] overlay = buf.readBoolean() ? readIndexArrayBuf(buf, wide, palLen) : null;
        return new AtlasChunkTerrain(cols, palette, base, overlay);
    }

    private static void writeIndexArrayBuf(FriendlyByteBuf buf, short[] arr, boolean wide) {
        for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
            int v = arr != null && i < arr.length ? (arr[i] & 0xFFFF) : 0;
            if (wide) {
                buf.writeShort(v);
            } else {
                buf.writeByte(v & 0xFF);
            }
        }
    }

    private static short[] readIndexArrayBuf(FriendlyByteBuf buf, boolean wide, int palLen) {
        short[] arr = new short[AtlasChunkTerrain.COLUMN_COUNT];
        for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
            int v = wide ? (buf.readShort() & 0xFFFF) : (buf.readByte() & 0xFF);
            arr[i] = (short) (v < palLen ? v : 0);
        }
        return arr;
    }

    public static void writeChunkMapToStream(DataOutput out, Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks) throws IOException {
        out.writeInt(CHUNK_MAP_MAGIC);
        out.writeInt(CHUNK_MAP_VERSION);
        if (chunks == null || chunks.isEmpty()) {
            out.writeInt(0);
            out.writeInt(0);
            return;
        }
        Object2IntOpenHashMap<BlockState> globalIndices = new Object2IntOpenHashMap<>(256);
        globalIndices.defaultReturnValue(-1);
        List<String> globalStrings = new ArrayList<>(256);
        BlockState air = Blocks.AIR.defaultBlockState();
        globalIndices.put(air, 0);
        globalStrings.add("minecraft:air");

        for (Long2ObjectMap.Entry<AtlasChunkTerrain> entry : Long2ObjectMaps.fastIterable(chunks)) {
            AtlasChunkTerrain terrain = entry.getValue();
            BlockState[] pal = terrain.blockPalette();
            if (terrain.hasBlockStates() && pal != null) {
                int len = Math.min(pal.length, MAX_PALETTE);
                for (int i = 0; i < len; i++) {
                    BlockState st = pal[i] != null ? pal[i] : air;
                    if (globalIndices.getInt(st) < 0) {
                        globalIndices.put(st, globalStrings.size());
                        globalStrings.add(safeEncodedString(st));
                    }
                }
            }
        }

        int globalCount = globalStrings.size();
        out.writeInt(globalCount);
        for (int i = 0; i < globalCount; i++) {
            out.writeUTF(globalStrings.get(i));
        }

        int idMode = globalCount <= 255 ? 0 : (globalCount <= 65535 ? 1 : 2);
        out.writeInt(chunks.size());
        for (Long2ObjectMap.Entry<AtlasChunkTerrain> entry : Long2ObjectMaps.fastIterable(chunks)) {
            out.writeLong(entry.getLongKey());
            AtlasChunkTerrain terrain = entry.getValue();
            long[] cols = terrain.columns();
            for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
                out.writeLong(i < cols.length ? cols[i] : 0L);
            }
            BlockState[] pal = terrain.blockPalette();
            int palLen = (terrain.hasBlockStates() && pal != null) ? Math.min(pal.length, MAX_PALETTE) : 0;
            short[] overlay = terrain.overlayBlockIndices();
            int flags = (palLen > 0 ? 1 : 0) | (palLen > 0 && overlay != null ? 2 : 0);
            out.writeByte(flags);
            if (palLen > 0) {
                out.writeShort(palLen);
                for (int i = 0; i < palLen; i++) {
                    BlockState st = pal[i] != null ? pal[i] : air;
                    int gid = Math.max(0, globalIndices.getInt(st));
                    if (idMode == 0) {
                        out.writeByte(gid);
                    } else if (idMode == 1) {
                        out.writeShort(gid);
                    } else {
                        out.writeInt(gid);
                    }
                }
                boolean wideLocal = palLen > 255;
                writeIndexArrayStream(out, terrain.baseBlockIndices(), wideLocal);
                if (overlay != null) {
                    writeIndexArrayStream(out, overlay, wideLocal);
                }
            }
        }
    }

    public static Long2ObjectOpenHashMap<AtlasChunkTerrain> readChunkMapFromStream(DataInput in, int maxChunks) throws IOException {
        int magic = in.readInt();
        int version = in.readInt();
        if (magic != CHUNK_MAP_MAGIC || version != CHUNK_MAP_VERSION) {
            return new Long2ObjectOpenHashMap<>();
        }
        int globalCount = in.readInt();
        if (globalCount < 0 || globalCount > 262_144) {
            throw new IOException("Invalid global palette count: " + globalCount);
        }
        BlockState[] globalStates = new BlockState[Math.max(1, globalCount)];
        globalStates[0] = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < globalCount; i++) {
            globalStates[i] = decodeStateString(in.readUTF());
        }
        int count = in.readInt();
        if (count <= 0 || count > maxChunks) {
            return new Long2ObjectOpenHashMap<>();
        }
        int idMode = globalCount <= 255 ? 0 : (globalCount <= 65535 ? 1 : 2);
        Long2ObjectOpenHashMap<AtlasChunkTerrain> out = new Long2ObjectOpenHashMap<>(Math.min(count, 4096));
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int c = 0; c < count; c++) {
            long chunkKey = in.readLong();
            long[] cols = new long[AtlasChunkTerrain.COLUMN_COUNT];
            for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
                cols[i] = in.readLong();
            }
            int flags = in.readUnsignedByte();
            if ((flags & 1) == 0) {
                out.put(chunkKey, new AtlasChunkTerrain(cols));
                continue;
            }
            int palLen = in.readUnsignedShort();
            if (palLen == 0 || palLen > MAX_PALETTE) {
                throw new IOException("Invalid local palette length: " + palLen);
            }
            BlockState[] localPalette = new BlockState[palLen];
            for (int i = 0; i < palLen; i++) {
                int gid = idMode == 0
                        ? in.readUnsignedByte()
                        : (idMode == 1 ? in.readUnsignedShort() : in.readInt());
                localPalette[i] = (gid >= 0 && gid < globalCount) ? globalStates[gid] : air;
            }
            boolean wideLocal = palLen > 255;
            short[] base = readIndexArrayStream(in, wideLocal, palLen);
            short[] overlay = (flags & 2) != 0 ? readIndexArrayStream(in, wideLocal, palLen) : null;
            out.put(chunkKey, new AtlasChunkTerrain(cols, localPalette, base, overlay));
        }
        return out;
    }

    public static byte[] encodeChunkMapBytes(Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return EMPTY_BYTES;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(chunks.size() * 512, 1 << 20));
        Deflater deflater = THREAD_DEFLATER.get();
        deflater.reset();
        try {
            try (DataOutputStream dos = new DataOutputStream(
                    new BufferedOutputStream(new DeflaterOutputStream(baos, deflater, 4096), 16384))) {
                writeChunkMapToStream(dos, chunks);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return EMPTY_BYTES;
        } finally {
            deflater.reset();
        }
    }

    public static Long2ObjectOpenHashMap<AtlasChunkTerrain> decodeChunkMapBytes(byte[] bytes, int maxChunks) {
        if (bytes == null || bytes.length == 0 || maxChunks <= 0) {
            return new Long2ObjectOpenHashMap<>();
        }
        try (DataInputStream dis = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            return readChunkMapFromStream(dis, maxChunks);
        } catch (IOException e) {
            return new Long2ObjectOpenHashMap<>();
        }
    }

    public static ByteBuffer encodeChunkMapBuffer(Long2ObjectOpenHashMap<AtlasChunkTerrain> chunks) {
        byte[] bytes = encodeChunkMapBytes(chunks);
        return bytes.length == 0 ? ByteBuffer.allocate(0) : ByteBuffer.wrap(bytes);
    }

    public static Long2ObjectOpenHashMap<AtlasChunkTerrain> decodeChunkMapBuffer(ByteBuffer buffer, int maxChunks) {
        if (buffer == null || !buffer.hasRemaining() || maxChunks <= 0) {
            return new Long2ObjectOpenHashMap<>();
        }
        ByteBuffer dup = buffer.duplicate();
        byte[] bytes;
        if (dup.hasArray() && dup.arrayOffset() == 0 && dup.position() == 0 && dup.remaining() == dup.array().length) {
            bytes = dup.array();
        } else {
            bytes = new byte[dup.remaining()];
            dup.get(bytes);
        }
        return decodeChunkMapBytes(bytes, maxChunks);
    }

    private static void writeIndexArrayStream(DataOutput out, short[] arr, boolean wide) throws IOException {
        if (wide) {
            byte[] buf = new byte[AtlasChunkTerrain.COLUMN_COUNT * 2];
            if (arr != null) {
                int limit = Math.min(arr.length, AtlasChunkTerrain.COLUMN_COUNT);
                for (int i = 0, o = 0; i < limit; i++) {
                    int v = arr[i] & 0xFFFF;
                    buf[o++] = (byte) (v >>> 8);
                    buf[o++] = (byte) v;
                }
            }
            out.write(buf);
        } else {
            byte[] buf = new byte[AtlasChunkTerrain.COLUMN_COUNT];
            if (arr != null) {
                int limit = Math.min(arr.length, AtlasChunkTerrain.COLUMN_COUNT);
                for (int i = 0; i < limit; i++) {
                    buf[i] = (byte) arr[i];
                }
            }
            out.write(buf);
        }
    }

    private static short[] readIndexArrayStream(DataInput in, boolean wide, int palLen) throws IOException {
        short[] arr = new short[AtlasChunkTerrain.COLUMN_COUNT];
        for (int i = 0; i < AtlasChunkTerrain.COLUMN_COUNT; i++) {
            int v = wide ? in.readUnsignedShort() : in.readUnsignedByte();
            arr[i] = (short) (v < palLen ? v : 0);
        }
        return arr;
    }
}
