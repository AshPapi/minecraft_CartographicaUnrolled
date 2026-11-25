package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public final class AtlasSidebar {
    public static int X = 16;
    public static final int Y = 16;
    public static final int WIDTH = 128;
    public static final int HEIGHT = 28;

    public static final int BTN_SIZE = 20;
    public static final int BTN_STEP = 22;
    private static final int TOOL_ICON_SIZE = 16;
    private static final int TOOL_ICON_PAD = (BTN_SIZE - TOOL_ICON_SIZE) / 2;

    public static final int PALETTE_PAD = 4;
    public static final int CELL_SIZE = 16;
    public static final int CELL_STEP = 18;
    private static final int PALETTE_X = 20;
    private static final int PALETTE_COLUMNS = 2;
    private static final int PALETTE_ICON_SIZE = 12;
    public static final List<DyeColor> PALETTE_COLOR_ORDER = List.of(
            DyeColor.WHITE, DyeColor.YELLOW,
            DyeColor.GREEN, DyeColor.LIME,
            DyeColor.CYAN, DyeColor.LIGHT_BLUE,
            DyeColor.BLUE, DyeColor.PURPLE,
            DyeColor.MAGENTA, DyeColor.PINK,
            DyeColor.RED, DyeColor.ORANGE,
            DyeColor.BROWN, DyeColor.LIGHT_GRAY,
            DyeColor.GRAY, DyeColor.BLACK);
    private static final int PALETTE_MIN_SIZE = CELL_SIZE + PALETTE_PAD * 2;
    private static final int PALETTE_WIDTH = PALETTE_MIN_SIZE + (PALETTE_COLUMNS - 1) * CELL_STEP;
    private static final int PALETTE_ROWS = (PALETTE_COLOR_ORDER.size() + PALETTE_COLUMNS - 1) / PALETTE_COLUMNS;
    private static final int PALETTE_HEIGHT = PALETTE_MIN_SIZE + (PALETTE_ROWS - 1) * CELL_STEP;
    private static final int PALETTE_TEXTURE_SCALE = 4;
    private static final int PALETTE_TEXTURE_WIDTH = PALETTE_WIDTH * PALETTE_TEXTURE_SCALE;
    private static final int PALETTE_TEXTURE_HEIGHT = PALETTE_HEIGHT * PALETTE_TEXTURE_SCALE;
    private static final int PALETTE_ICON_TILE_SIZE = 128;
    private static int screenHeight = 240;

    private static final Identifier ICONS_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/atlas_tools_detailed.png");

    private static final Identifier TOOL_CASE_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/tool_case.png");

    private static final Identifier PALETTE_CASE_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/palette_case.png");

    private static final Identifier PALETTE_SLOT_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/palette_slots.png");
    private static final Identifier PALETTE_DYE_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/palette_dye.png");

    private static final Identifier PANEL_TEXTURE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/leather_panel.png");
    private static final Identifier SHARED_MAP_ICON =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/shared_map_icon.png");
    private static final Identifier SHARE_PERSONAL_ICON =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/share_personal_icon.png");
    private static final Identifier SHARED_MAP_HIDDEN_ICON =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/shared_map_hidden_icon.png");
    private static final Identifier SHARE_PERSONAL_HIDDEN_ICON =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "textures/gui/share_personal_hidden_icon.png");

    private static final Identifier BRUSH_BASE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/brush_base");
    private static final Identifier BRUSH_PAINT =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/brush_paint");
    private static final Identifier STAMP_BASE =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/stamp_base");
    private static final Identifier STAMP_PAINT =
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "dynamic/stamp_paint");
    private static boolean toolTexturesDecoded;
    private static boolean brushTexturesReady;
    private static boolean stampTexturesReady;

    private static final Component[] TOOL_TOOLTIPS = {
            Component.translatable("gui.cartographica_unrolled.tool.dye"),
            Component.translatable("gui.cartographica_unrolled.tool.eraser"),
            Component.translatable("gui.cartographica_unrolled.tool.stamp")
    };
    private static final Component SHOW_SHARED_TOOLTIP =
            Component.translatable("gui.cartographica_unrolled.show_shared");
    private static final Component SHARE_PERSONAL_TOOLTIP =
            Component.translatable("gui.cartographica_unrolled.share_personal");
    private static final Component[] DYE_TOOLTIPS = new Component[PALETTE_COLOR_ORDER.size()];

    static {
        for (int i = 0; i < PALETTE_COLOR_ORDER.size(); i++) {
            DYE_TOOLTIPS[i] = Component.translatable(
                    "item.minecraft." + PALETTE_COLOR_ORDER.get(i).getName() + "_dye"
            );
        }
    }

    public interface Callbacks {
        void onSelectTool(AtlasEditorState.Tool tool);
        void onSelectDye(DyeColor dye);
        void onSelectStamp(DyeColor stamp);
        default void onBeforeToggle() {}
    }

    private AtlasSidebar() {
    }

    public static void invalidateCache() {
        var textures = Minecraft.getInstance().getTextureManager();
        if (brushTexturesReady) {
            textures.release(BRUSH_BASE);
            textures.release(BRUSH_PAINT);
        }
        if (stampTexturesReady) {
            textures.release(STAMP_BASE);
            textures.release(STAMP_PAINT);
        }
        toolTexturesDecoded = false;
        brushTexturesReady = false;
        stampTexturesReady = false;
    }

    public static void updateLayout(int width, int height) {
        screenHeight = height;
        X = Math.max(16, (width - WIDTH) / 2);
    }

    private static boolean hasPalette(AtlasEditorState.Tool tool) {
        return tool == AtlasEditorState.Tool.STAMP || tool == AtlasEditorState.Tool.DYE;
    }

    private static int paletteY() {
        return Math.max(16, (screenHeight - PALETTE_HEIGHT) / 2);
    }

    private static int paletteCellX(int index) {
        return PALETTE_X + PALETTE_PAD + (index % PALETTE_COLUMNS) * CELL_STEP;
    }

    private static int paletteCellY(int index, int paletteY) {
        return paletteY + PALETTE_PAD + (index / PALETTE_COLUMNS) * CELL_STEP;
    }

    private static boolean contains(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static boolean isMouseOver(
            double mouseX,
            double mouseY,
            AtlasEditorState.Tool activeTool
    ) {
        if (contains(mouseX, mouseY, X, Y, WIDTH, HEIGHT)) {
            return true;
        }
        return hasPalette(activeTool) && contains(mouseX, mouseY, PALETTE_X, paletteY(), PALETTE_WIDTH, PALETTE_HEIGHT);
    }

    public static boolean overlapsPalette(
            int x, int y, int w, int h, AtlasEditorState.Tool activeTool
    ) {
        if (!hasPalette(activeTool)) {
            return false;
        }
        int py = paletteY();
        return x < PALETTE_X + PALETTE_WIDTH && x + w > PALETTE_X
                && y < py + PALETTE_HEIGHT && y + h > py;
    }

    public static void render(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            AtlasEditorState.Tool activeTool,
            DyeColor selectedDye,
            DyeColor selectedStamp
    ) {
        g.blit(RenderPipelines.GUI_TEXTURED, TOOL_CASE_TEXTURE, X, Y, 0.0f, 0.0f,
                WIDTH, HEIGHT, WIDTH, HEIGHT, WIDTH, HEIGHT);
        for (int i = 0; i < CLICKABLE_TOOLS.length; i++) {
            int bx = toolX(i);
            int by = Y + 4;
            boolean hover = contains(mouseX, mouseY, bx, by, BTN_SIZE, BTN_SIZE);
            renderToolBtn(g, bx, by, i, activeTool == CLICKABLE_TOOLS[i], hover,
                    i == 0 ? selectedDye : i == 2 ? selectedStamp : null);
            if (hover) {
                g.setTooltipForNextFrame(TOOL_TOOLTIPS[i], mouseX, mouseY);
            }
        }
        int sharedBx = toolX(3);
        int sharedBy = Y + 4;
        boolean sharedActive = AtlasEditorState.isSharedMap();
        boolean sharedHover = contains(mouseX, mouseY, sharedBx, sharedBy, BTN_SIZE, BTN_SIZE);
        drawCaseButton(g, sharedBx, sharedBy, sharedActive, sharedHover);
        drawCaseToggleIcon(g, sharedBx, sharedBy, sharedActive ? SHARED_MAP_ICON : SHARED_MAP_HIDDEN_ICON);
        if (sharedHover) {
            g.setTooltipForNextFrame(SHOW_SHARED_TOOLTIP, mouseX, mouseY);
        }
        int sharePersonalBx = toolX(4);
        int sharePersonalBy = Y + 4;
        boolean sharePersonalActive = AtlasEditorState.isSharePersonalWithAll();
        boolean sharePersonalHover = contains(mouseX, mouseY, sharePersonalBx, sharePersonalBy, BTN_SIZE, BTN_SIZE);
        drawCaseButton(g, sharePersonalBx, sharePersonalBy, sharePersonalActive, sharePersonalHover);
        drawCaseToggleIcon(g, sharePersonalBx, sharePersonalBy,
                sharePersonalActive ? SHARE_PERSONAL_ICON : SHARE_PERSONAL_HIDDEN_ICON);
        if (sharePersonalHover) {
            g.setTooltipForNextFrame(SHARE_PERSONAL_TOOLTIP, mouseX, mouseY);
        }
        if (hasPalette(activeTool)) {
            boolean stamp = activeTool == AtlasEditorState.Tool.STAMP;
            int py = paletteY();
            drawPalettePanel(g, py);
            DyeColor selected = stamp ? selectedStamp : selectedDye;
            renderPalette(g, mouseX, mouseY, selected, stamp, py);
        }
    }

    private static void drawCaseToggleIcon(GuiGraphicsExtractor g, int x, int y, Identifier icon) {
        g.pose().pushMatrix();
        g.pose().translate(0.0f, -0.5f);
        g.blit(RenderPipelines.GUI_TEXTURED, icon, x + TOOL_ICON_PAD, y + TOOL_ICON_PAD,
                0.0f, 0.0f, TOOL_ICON_SIZE, TOOL_ICON_SIZE, 64, 64, 64, 64);
        g.pose().popMatrix();
    }

    private static int toolX(int index) {
        return X + 10 + index * BTN_STEP;
    }

    private static void drawPaletteCell(GuiGraphicsExtractor g, Identifier texture, int x, int y, boolean selected, boolean hover) {
        int tileSize = CELL_SIZE * PALETTE_TEXTURE_SCALE;
        int u = hover ? tileSize : 0;
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, 0.0f,
                CELL_SIZE, CELL_SIZE, tileSize, tileSize, tileSize * 3, tileSize);
        if (selected) {
            g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, tileSize * 2, 0.0f,
                    CELL_SIZE, CELL_SIZE, tileSize, tileSize, tileSize * 3, tileSize);
        }
    }

    private static void drawCaseButton(GuiGraphicsExtractor g, int x, int y, boolean active, boolean hover) {
        if (!active && !hover) {
            return;
        }
        int highlight = active ? 0x34E8B87C : 0x1FE8B87C;
        int top = y + 1;
        int bottom = y + BTN_SIZE - 2;
        g.fill(x + 2, top, x + BTN_SIZE - 2, top + 1, highlight);
        g.fill(x + 1, top + 1, x + BTN_SIZE - 1, bottom - 1, highlight);
        g.fill(x + 2, bottom - 1, x + BTN_SIZE - 2, bottom, highlight);
        if (active) {
            g.fill(x + 7, bottom, x + 13, bottom + 1, 0xFFCC9A62);
            g.fill(x + 8, bottom, x + 12, bottom + 1, 0xFFF2CB8F);
        }
    }

    private static void renderToolBtn(
            GuiGraphicsExtractor g,
            int btnX,
            int btnY,
            int iconIndex,
            boolean active,
            boolean hover,
            DyeColor toolColor
    ) {
        drawCaseButton(g, btnX, btnY, active, hover);
        int iconX = btnX + TOOL_ICON_PAD;
        int iconY = btnY + TOOL_ICON_PAD;
        boolean stamp = iconIndex == 2;
        g.pose().pushMatrix();
        if (stamp) {
            g.pose().translate(0.5f, 0.5f);
        } else if (iconIndex == 0) {
            g.pose().translate(-0.5f, 0.0f);
        } else {
            g.pose().translate(0.0f, -0.5f);
        }
        if (stamp && toolColor != null) {
            drawBannerIcon(g, iconX, iconY, TOOL_ICON_SIZE, toolColor.getMapColor().col);
        } else if (toolColor != null && ensureColoredTextures(false)) {
            g.blit(RenderPipelines.GUI_TEXTURED, BRUSH_BASE, iconX, iconY, 0.0f, 0.0f, TOOL_ICON_SIZE, TOOL_ICON_SIZE, 64, 64, 64, 64);
            g.blit(RenderPipelines.GUI_TEXTURED, BRUSH_PAINT, iconX, iconY, 0.0f, 0.0f, TOOL_ICON_SIZE, TOOL_ICON_SIZE, 64, 64, 64, 64,
                    0xFF000000 | toolColor.getMapColor().col);
        } else {
            g.blit(RenderPipelines.GUI_TEXTURED, ICONS_TEXTURE, iconX, iconY, iconIndex * 64.0f, 0.0f, TOOL_ICON_SIZE, TOOL_ICON_SIZE, 64, 64, 192, 64);
        }
        g.pose().popMatrix();
    }

    private static boolean ensureColoredTextures(boolean stamp) {
        if (!toolTexturesDecoded) {
            toolTexturesDecoded = true;
            decodeSplitToolTextures();
        }
        return stamp ? stampTexturesReady : brushTexturesReady;
    }

    private static void decodeSplitToolTextures() {
        Minecraft mc = Minecraft.getInstance();
        var resource = mc.getResourceManager().getResource(ICONS_TEXTURE);
        if (resource.isEmpty()) {
            return;
        }
        try (InputStream stream = resource.get().open(); NativeImage source = NativeImage.read(stream)) {
            if (source.format() != NativeImage.Format.RGBA || source.getHeight() < 64) {
                return;
            }
            if (source.getWidth() >= 64) {
                brushTexturesReady = extractSplitIcon(mc, source, false);
            }
            if (source.getWidth() >= 192) {
                stampTexturesReady = extractSplitIcon(mc, source, true);
            }
        } catch (IOException ignored) {
            brushTexturesReady = false;
            stampTexturesReady = false;
        }
    }

    private static boolean extractSplitIcon(Minecraft mc, NativeImage source, boolean stamp) {
        NativeImage base = new NativeImage(NativeImage.Format.RGBA, 64, 64, true);
        NativeImage paint = null;
        boolean baseRegistered = false;
        boolean paintRegistered = false;
        int srcOffX = stamp ? 128 : 0;
        try {
            paint = new NativeImage(NativeImage.Format.RGBA, 64, 64, true);
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int pixel = source.getPixel(x + srcOffX, y);
                    int alpha = pixel >>> 24;
                    int red = (pixel >>> 16) & 255;
                    int green = (pixel >>> 8) & 255;
                    int blue = pixel & 255;
                    boolean paintArea = stamp ? x >= 18 && y >= 9 && y < 42 : x >= 32 && y < 32 && x - y >= 16;
                    boolean colored = stamp
                            ? red > green * 1.18f && red > blue * 1.22f && green < red * 0.58f
                            : red >= 45 && green < red * 0.58f && red > blue * 1.3f;
                    if (paintArea && colored) {
                        int shade = stamp ? Math.min(255, 40 + red * 215 / 255)
                                : Math.min(255, 90 + red * 165 / 255);
                        paint.setPixel(x, y, (alpha << 24) | (shade << 16) | (shade << 8) | shade);
                    } else {
                        base.setPixel(x, y, pixel);
                    }
                }
            }
            Identifier baseId = stamp ? STAMP_BASE : BRUSH_BASE;
            Identifier paintId = stamp ? STAMP_PAINT : BRUSH_PAINT;
            mc.getTextureManager().register(baseId,
                    new DynamicTexture(() -> stamp ? "cartographica_stamp_base" : "cartographica_brush_base", base));
            baseRegistered = true;
            mc.getTextureManager().register(paintId,
                    new DynamicTexture(() -> stamp ? "cartographica_stamp_paint" : "cartographica_brush_paint", paint));
            paintRegistered = true;
            return true;
        } catch (Throwable t) {
            if (baseRegistered) {
                mc.getTextureManager().release(stamp ? STAMP_BASE : BRUSH_BASE);
            } else {
                base.close();
            }
            if (paint != null && !paintRegistered) {
                paint.close();
            }
            throw t;
        }
    }

    private static void renderPalette(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            DyeColor selected,
            boolean stamp,
            int paletteY
    ) {
        for (int i = 0; i < PALETTE_COLOR_ORDER.size(); i++) {
            DyeColor color = PALETTE_COLOR_ORDER.get(i);
            int cx = paletteCellX(i);
            int cy = paletteCellY(i, paletteY);
            boolean hover = contains(mouseX, mouseY, cx, cy, CELL_SIZE, CELL_SIZE);
            if (hover) g.setTooltipForNextFrame(DYE_TOOLTIPS[i], mouseX, mouseY);
            drawPaletteCell(g, PALETTE_SLOT_TEXTURE, cx, cy, color == selected, hover);
            int iconX = cx + (CELL_SIZE - PALETTE_ICON_SIZE) / 2;
            int iconY = cy + (CELL_SIZE - 1 - PALETTE_ICON_SIZE) / 2;
            int argb = 0xFF000000 | color.getMapColor().col;
            g.pose().pushMatrix();
            g.pose().translate(0.0f, stamp ? 0.5f : 0.875f);
            if (stamp) {
                drawBannerIcon(g, iconX, iconY, PALETTE_ICON_SIZE, argb);
            } else {
                g.blit(RenderPipelines.GUI_TEXTURED, PALETTE_DYE_TEXTURE, iconX, iconY, 0.0f, 0.0f,
                        PALETTE_ICON_SIZE, PALETTE_ICON_SIZE, PALETTE_ICON_TILE_SIZE, PALETTE_ICON_TILE_SIZE,
                        PALETTE_ICON_TILE_SIZE * 2, PALETTE_ICON_TILE_SIZE);
                g.blit(RenderPipelines.GUI_TEXTURED, PALETTE_DYE_TEXTURE, iconX, iconY, PALETTE_ICON_TILE_SIZE, 0.0f,
                        PALETTE_ICON_SIZE, PALETTE_ICON_SIZE, PALETTE_ICON_TILE_SIZE, PALETTE_ICON_TILE_SIZE,
                        PALETTE_ICON_TILE_SIZE * 2, PALETTE_ICON_TILE_SIZE, argb);
            }
            g.pose().popMatrix();
        }
    }

    private static final AtlasEditorState.Tool[] CLICKABLE_TOOLS = {
            AtlasEditorState.Tool.DYE, AtlasEditorState.Tool.ERASER, AtlasEditorState.Tool.STAMP
    };

    public static boolean mouseClicked(
            double mouseX,
            double mouseY,
            int button,
            AtlasEditorState.Tool activeTool,
            Callbacks cb
    ) {
        if (!isMouseOver(mouseX, mouseY, activeTool)) {
            return false;
        }
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return true;
        }
        for (int i = 0; i < CLICKABLE_TOOLS.length; i++) {
            if (contains(mouseX, mouseY, toolX(i), Y + 4, BTN_SIZE, BTN_SIZE)) {
                cb.onSelectTool(activeTool == CLICKABLE_TOOLS[i] ? AtlasEditorState.Tool.NONE : CLICKABLE_TOOLS[i]);
                return true;
            }
        }
        if (contains(mouseX, mouseY, toolX(3), Y + 4, BTN_SIZE, BTN_SIZE)) {
            cb.onBeforeToggle();
            AtlasEditorState.toggleSharedMap();
            return true;
        }
        if (contains(mouseX, mouseY, toolX(4), Y + 4, BTN_SIZE, BTN_SIZE)) {
            cb.onBeforeToggle();
            boolean next = !AtlasEditorState.isSharePersonalWithAll();
            AtlasEditorState.setSharePersonalWithAll(next);
            ClientNetworking.sendSetSharePersonal(next);
            return true;
        }
        if (hasPalette(activeTool)) {
            int py = paletteY();
            for (int i = 0; i < PALETTE_COLOR_ORDER.size(); i++) {
                int cx = paletteCellX(i);
                int cy = paletteCellY(i, py);
                if (contains(mouseX, mouseY, cx, cy, CELL_SIZE, CELL_SIZE)) {
                    if (activeTool == AtlasEditorState.Tool.DYE) {
                        cb.onSelectDye(PALETTE_COLOR_ORDER.get(i));
                    } else {
                        cb.onSelectStamp(PALETTE_COLOR_ORDER.get(i));
                    }
                    return true;
                }
            }
        }
        return true;
    }

    private static void drawPalettePanel(GuiGraphicsExtractor g, int paletteY) {
        g.blit(RenderPipelines.GUI_TEXTURED, PALETTE_CASE_TEXTURE, PALETTE_X, paletteY,
                0.0f, 0.0f, PALETTE_WIDTH, PALETTE_HEIGHT, PALETTE_TEXTURE_WIDTH, PALETTE_TEXTURE_HEIGHT,
                PALETTE_TEXTURE_WIDTH, PALETTE_TEXTURE_HEIGHT);
    }

    public static void drawLeatherPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        drawPanelTexture(g, PANEL_TEXTURE, x, y, w, h, Math.min(4, Math.min(w, h) / 2), true);
    }

    public static void drawLeatherFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, int border) {
        drawPanelTexture(g, PANEL_TEXTURE, x, y, w, h, Math.min(border, Math.min(w, h) / 2), false);
    }

    private static void drawPanelTexture(GuiGraphicsExtractor g, Identifier texture, int x, int y, int w, int h, int border, boolean center) {
        int textureSize = 64;
        int sourceBorder = 5;
        int midW = w - border * 2;
        int midH = h - border * 2;
        int srcMid = textureSize - sourceBorder * 2;
        int srcFar = textureSize - sourceBorder;
        for (int row = 0; row < 3; row++) {
            int dy = row == 0 ? y : (row == 1 ? y + border : y + h - border);
            int dh = row == 1 ? midH : border;
            if (dh <= 0) {
                continue;
            }
            int sy = row == 0 ? 0 : (row == 1 ? sourceBorder : srcFar);
            int sh = row == 1 ? srcMid : sourceBorder;
            for (int col = 0; col < 3; col++) {
                if (!center && row == 1 && col == 1) {
                    continue;
                }
                int dw = col == 1 ? midW : border;
                if (dw > 0) {
                    int dx = col == 0 ? x : (col == 1 ? x + border : x + w - border);
                    int sx = col == 0 ? 0 : (col == 1 ? sourceBorder : srcFar);
                    int sw = col == 1 ? srcMid : sourceBorder;
                    g.blit(RenderPipelines.GUI_TEXTURED, texture, dx, dy, sx, sy,
                            dw, dh, sw, sh, textureSize, textureSize,
                            0xFFFFFFFF);
                }
            }
        }
    }

    public static boolean ensureBannerTextures() {
        return ensureColoredTextures(true);
    }

    public static Identifier getStampBaseTexture() {
        return STAMP_BASE;
    }

    public static Identifier getStampPaintTexture() {
        return STAMP_PAINT;
    }

    public static Identifier getIconsTexture() {
        return ICONS_TEXTURE;
    }

    public static void drawBannerIcon(GuiGraphicsExtractor g, int x, int y, int rgb) {
        drawBannerIcon(g, x, y, TOOL_ICON_SIZE, rgb);
    }

    private static void drawBannerIcon(GuiGraphicsExtractor g, int x, int y, int size, int rgb) {
        if (ensureColoredTextures(true)) {
            g.blit(RenderPipelines.GUI_TEXTURED, STAMP_BASE, x, y, 0.0f, 0.0f, size, size, 64, 64, 64, 64);
            g.blit(RenderPipelines.GUI_TEXTURED, STAMP_PAINT, x, y, 0.0f, 0.0f, size, size, 64, 64, 64, 64, 0xFF000000 | rgb);
        } else {
            g.blit(RenderPipelines.GUI_TEXTURED, ICONS_TEXTURE, x, y, 128.0f, 0.0f, size, size, 64, 64, 192, 64);
        }
    }
}
