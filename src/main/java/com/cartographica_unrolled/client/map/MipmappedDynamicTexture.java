package com.cartographica_unrolled.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Supplier;

public final class MipmappedDynamicTexture extends AbstractTexture {
    private static ByteBuffer stagingBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.nativeOrder());

    private final int width;
    private final int height;
    private final int mipLevels;

    public MipmappedDynamicTexture(Supplier<String> label, int width, int height, int mipLevels, boolean mipmapped) {
        this.width = width;
        this.height = height;
        this.mipLevels = Math.max(1, mipLevels);
        GpuDevice device = RenderSystem.getDevice();
        this.texture = device.createTexture(
                label,
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                width,
                height,
                1,
                this.mipLevels
        );
        this.sampler = RenderSystem.getSamplerCache().getSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                mipmapped && this.mipLevels > 1
        );
        this.textureView = device.createTextureView(this.texture);
    }

    public static MipmappedDynamicTexture createSingleMip(String label, int width, int height, byte[] rgbaBytes) {
        MipmappedDynamicTexture tex = new MipmappedDynamicTexture(() -> label, width, height, 1, false);
        tex.uploadSingleMip(rgbaBytes);
        return tex;
    }

    public static MipmappedDynamicTexture createWithMips(Supplier<String> label, int width, int height, byte[][] mips) {
        MipmappedDynamicTexture tex = new MipmappedDynamicTexture(label, width, height, mips.length, true);
        tex.uploadAllMips(mips);
        return tex;
    }

    private static ByteBuffer prepareStaging(byte[] src, int byteLen) {
        if (stagingBuffer.capacity() < byteLen) {
            int nextCap = Math.max(byteLen, stagingBuffer.capacity() << 1);
            stagingBuffer = ByteBuffer.allocateDirect(nextCap).order(ByteOrder.nativeOrder());
        }
        stagingBuffer.clear();
        stagingBuffer.put(src, 0, byteLen);
        stagingBuffer.flip();
        return stagingBuffer;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int mipLevels() {
        return this.mipLevels;
    }

    public void uploadSingleMip(byte[] rgbaBytes) {
        if (this.texture == null) {
            return;
        }
        int expected = this.width * this.height * 4;
        if (rgbaBytes.length < expected) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(this.texture, prepareStaging(rgbaBytes, expected), 0, 0, 0, 0, this.width, this.height);
    }

    public void uploadAllMips(byte[][] mips) {
        if (this.texture == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int levels = Math.min(this.mipLevels, mips.length);
        int w = this.width;
        int h = this.height;
        for (int level = 0; level < levels; level++) {
            byte[] data = mips[level];
            int expected = w * h * 4;
            if (data != null && data.length >= expected) {
                encoder.writeToTexture(this.texture, prepareStaging(data, expected), level, 0, 0, 0, w, h);
            }
            w = Math.max(1, w >> 1);
            h = Math.max(1, h >> 1);
        }
    }

    public void uploadAlignedTileMips(int x0, int y0, int tileW, int tileH, byte[][] tileMips) {
        if (this.texture == null) {
            return;
        }
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        int levels = Math.min(this.mipLevels, tileMips.length);
        for (int level = 0; level < levels; level++) {
            int tx = x0 >> level;
            int ty = y0 >> level;
            int tw = Math.max(1, tileW >> level);
            int th = Math.max(1, tileH >> level);
            byte[] data = tileMips[level];
            int expected = tw * th * 4;
            if (data != null && data.length >= expected) {
                encoder.writeToTexture(this.texture, prepareStaging(data, expected), level, 0, tx, ty, tw, th);
            }
        }
    }

    @Override
    public void close() {
        this.releaseTextures();
    }
}
