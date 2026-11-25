package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.atmosphere.AtlasLighting;
import com.cartographica_unrolled.client.atmosphere.ParchmentTexture;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.cartographica_unrolled.client.ui.AtlasViewport.FRAME_PAD;

public final class AtlasScreenHud {
    private static final int COMPASS_SIZE = 44;
    private static final Identifier COMPASS_TEXTURE = Identifier.fromNamespaceAndPath(
            CartographicaUnrolled.MOD_ID, "textures/gui/compass_rose_detailed.png");
    private static final Identifier COMPASS_LETTERS = Identifier.fromNamespaceAndPath(
            CartographicaUnrolled.MOD_ID, "textures/gui/compass_letters.png");
    private static final Component LAST_DEATH_LABEL = Component.translatable("gui.cartographica_unrolled.last_death");

    private int statusBarX;
    private int statusBarY;
    private int statusBarW;
    private int cachedStatusWx = Integer.MIN_VALUE;
    private int cachedStatusWz = Integer.MIN_VALUE;
    private String cachedStatusCoords = "";
    private @Nullable String cachedOfflineSkinName;
    private @Nullable UUID cachedOfflineSkinUuid;

    public void updateStatusBarMetrics(Font font, AtlasViewport vp, double mx, double my) {
        int wx = Mth.floor(vp.screenToWorldX(mx));
        int wz = Mth.floor(vp.screenToWorldZ(my));
        if (wx != this.cachedStatusWx || wz != this.cachedStatusWz || this.cachedStatusCoords.isEmpty()) {
            this.cachedStatusWx = wx;
            this.cachedStatusWz = wz;
            this.cachedStatusCoords = "X: " + wx + "  Z: " + wz;
            this.statusBarW = font.width(this.cachedStatusCoords) + 12;
        }
        this.statusBarX = (vp.width() - this.statusBarW) / 2;
        this.statusBarY = vp.height() - 38;
    }

    public boolean isMouseOverAnyPanel(AtlasViewport vp, AtlasEditorState.Tool activeTool, double mx, double my) {
        int width = vp.width();
        int height = vp.height();
        if (mx < FRAME_PAD || mx >= width - FRAME_PAD || my < FRAME_PAD || my >= height - FRAME_PAD) {
            return true;
        }
        int compassX = width - FRAME_PAD - COMPASS_SIZE - 16;
        int compassY = FRAME_PAD + 16;
        if (mx >= compassX - 11 && mx < compassX + COMPASS_SIZE + 11
                && my >= compassY - 11 && my < compassY + COMPASS_SIZE + 11) {
            return true;
        }
        if (my >= this.statusBarY && my < this.statusBarY + 16
                && mx >= this.statusBarX && mx < this.statusBarX + this.statusBarW) {
            return true;
        }
        return AtlasSidebar.isMouseOver(mx, my, activeTool);
    }

    public void drawToolCursor(
            GuiGraphicsExtractor g,
            AtlasEditorState.Tool activeTool,
            boolean draggingPan,
            float eraseRadiusPx,
            int mouseX,
            int mouseY
    ) {
        if (activeTool == AtlasEditorState.Tool.ERASER) {
            int r = (int) eraseRadiusPx;
            g.blit(RenderPipelines.GUI_TEXTURED, ParchmentTexture.getEraserCursor(),
                    mouseX - r, mouseY - r, 0.0f, 0.0f, r * 2, r * 2, 64, 64, 64, 64);
            g.requestCursor(CursorTypes.CROSSHAIR);
        } else if (activeTool == AtlasEditorState.Tool.DYE || activeTool == AtlasEditorState.Tool.STAMP) {
            g.requestCursor(CursorTypes.CROSSHAIR);
        } else if (draggingPan) {
            g.requestCursor(CursorTypes.RESIZE_ALL);
        }
    }

    public void renderOverlayChrome(
            GuiGraphicsExtractor g,
            AtlasGuiBatcher batcher,
            Font font,
            AtlasViewport vp,
            AtlasEditorState.Tool activeTool,
            DyeColor selectedDye,
            DyeColor selectedStamp,
            boolean drawTooltip,
            int mouseX,
            int mouseY,
            List<AtlasPayloads.RemotePlayerPos> visibleOthers,
            List<MapMarker> visibleMarkers,
            List<MapStroke> visibleStrokes
    ) {
        AtlasSidebar.drawLeatherFrame(g, 0, 0, vp.width(), vp.height(), FRAME_PAD);
        drawCompassRose(g, batcher, vp.width());
        AtlasSidebar.render(g, mouseX, mouseY, activeTool, selectedDye, selectedStamp);
        AtlasSidebar.drawLeatherPanel(g, this.statusBarX, this.statusBarY, this.statusBarW, 16);
        g.text(font, this.cachedStatusCoords, this.statusBarX + 6, this.statusBarY + 4, 0xFFF5E6C8, false);
        if (drawTooltip) {
            drawAuthorTooltip(g, font, vp, mouseX, mouseY, visibleOthers, visibleMarkers, visibleStrokes);
        }
    }

    private void drawCompassRose(GuiGraphicsExtractor g, AtlasGuiBatcher batcher, int screenWidth) {
        int x = screenWidth - FRAME_PAD - COMPASS_SIZE - 16;
        float fy = FRAME_PAD + 16 + 0.5f;
        batcher.submitTexturedQuad(
                g, RenderPipelines.GUI_TEXTURED, COMPASS_TEXTURE,
                x, fy, x + COMPASS_SIZE, fy + COMPASS_SIZE,
                0.0f, 0.0f, 1.0f, 1.0f, 0xFFFFFFFF
        );

        float cx = x + COMPASS_SIZE * 0.5f;
        float cy = fy + COMPASS_SIZE * 0.5f;
        Identifier lightLetters = ParchmentTexture.getCompassLettersLight();
        int letterColor = AtlasLighting.tint(0xFFFFE1A1, 0xFFFFEBC7);
        if (lightLetters == null) {
            batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, COMPASS_LETTERS);
            addCompassLetterQuad(batcher, 0, cx - 5.0f, fy - 11.0f, 0xFFFFFFFF);
            addCompassLetterQuad(batcher, 1, cx - 5.0f, fy + COMPASS_SIZE + 1.0f, 0xFFFFFFFF);
            addCompassLetterQuad(batcher, 2, x - 11.0f, cy - 5.0f, 0xFFFFFFFF);
            addCompassLetterQuad(batcher, 3, x + COMPASS_SIZE + 1.0f, cy - 5.0f, 0xFFFFFFFF);
            batcher.flush();
            return;
        }
        batcher.beginTextured(g, RenderPipelines.GUI_TEXTURED, lightLetters);
        addShadowedCompassLetter(batcher, 0, cx - 5.0f, fy - 11.0f, letterColor);
        addShadowedCompassLetter(batcher, 1, cx - 5.0f, fy + COMPASS_SIZE + 1.0f, letterColor);
        addShadowedCompassLetter(batcher, 2, x - 11.0f, cy - 5.0f, letterColor);
        addShadowedCompassLetter(batcher, 3, x + COMPASS_SIZE + 1.0f, cy - 5.0f, letterColor);
        batcher.flush();
    }

    private static void addShadowedCompassLetter(AtlasGuiBatcher batcher, int index, float x, float y, int color) {
        int shadow = 0xF022160B;
        addCompassLetterQuad(batcher, index, x - 0.35f, y, shadow);
        addCompassLetterQuad(batcher, index, x + 0.35f, y, shadow);
        addCompassLetterQuad(batcher, index, x, y - 0.35f, shadow);
        addCompassLetterQuad(batcher, index, x, y + 0.35f, shadow);
        addCompassLetterQuad(batcher, index, x, y, color);
    }

    private static void addCompassLetterQuad(AtlasGuiBatcher batcher, int index, float x, float y, int color) {
        float u0 = index * 0.25f;
        float u1 = u0 + 0.25f;
        batcher.addTexturedAxisQuad(x, y, x + 10.0f, y + 10.0f, u0, 0.0f, u1, 1.0f, color);
    }

    private void drawAuthorTooltip(
            GuiGraphicsExtractor g,
            Font font,
            AtlasViewport vp,
            int mouseX,
            int mouseY,
            List<AtlasPayloads.RemotePlayerPos> visibleOthers,
            List<MapMarker> visibleMarkers,
            List<MapStroke> visibleStrokes
    ) {
        String authorName = null;
        UUID authorUuid = null;
        Minecraft mc = Minecraft.getInstance();

        double markerScreenRadius = Math.max(4.0, 9.0 * vp.getMarkerScale());
        double markerScreenSq = markerScreenRadius * markerScreenRadius;

        for (AtlasPayloads.RemotePlayerPos other : visibleOthers) {
            double dx = vp.worldToScreenX(other.x()) - mouseX;
            double dy = vp.worldToScreenY(other.z()) - mouseY;
            if (dx * dx + dy * dy <= markerScreenSq) {
                authorName = other.name();
                authorUuid = other.id();
                break;
            }
        }

        if (authorName == null) {
            AtlasSavedData.DeathPos death = AtlasLiveTracker.getDeathPos(ClientNetworking.currentDimensionKey());
            if (death != null) {
                double dx = vp.worldToScreenX(death.x()) - mouseX;
                double dy = vp.worldToScreenY(death.z()) - mouseY;
                if (dx * dx + dy * dy <= markerScreenSq) {
                    String label = LAST_DEATH_LABEL.getString();
                    int textW = font.width(label);
                    int pad = 4;
                    int boxW = pad + textW + pad;
                    int boxH = pad + 10 + pad;
                    int bx = Math.min(mouseX + 10, vp.width() - boxW - 4);
                    int by = Math.max(4, mouseY - boxH - 6);
                    drawTooltipBox(g, bx, by, boxW, boxH);
                    g.text(font, label, bx + pad, by + pad + 1, 0xFFF3E5C8, true);
                    return;
                }
            }
        }

        if (authorName == null) {
            for (MapMarker marker : visibleMarkers) {
                if (marker.getAuthorName() == null || marker.getAuthorName().isEmpty()) {
                    continue;
                }
                double dx = vp.worldToScreenX(marker.getWorldX()) - mouseX;
                double dy = vp.worldToScreenY(marker.getWorldZ()) - mouseY;
                if (dx * dx + dy * dy <= markerScreenSq) {
                    authorName = marker.getAuthorName();
                    authorUuid = marker.getAuthorUuid();
                    break;
                }
            }
        }

        if (authorName == null) {
            double wx = vp.screenToWorldX(mouseX);
            double wz = vp.screenToWorldZ(mouseY);
            float zoom = vp.zoom();
            for (MapStroke stroke : visibleStrokes) {
                if (stroke.getAuthorName() == null || stroke.getAuthorName().isEmpty()) {
                    continue;
                }
                double screenHitRadius = Math.max(2.0, stroke.getWidth() * zoom * 0.5 + 1.5);
                double worldBoundPad = screenHitRadius / Math.max(0.05, zoom);
                if (!stroke.intersectsCircle((float) wx, (float) wz, (float) worldBoundPad)) {
                    continue;
                }
                List<MapStroke.Point> pts = stroke.getPoints();
                double hitSq = screenHitRadius * screenHitRadius;
                boolean hit = false;
                if (pts.size() == 1) {
                    double dx = vp.worldToScreenX(pts.getFirst().x()) - mouseX;
                    double dy = vp.worldToScreenY(pts.getFirst().z()) - mouseY;
                    hit = dx * dx + dy * dy <= hitSq;
                } else {
                    for (int i = 1; i < pts.size(); i++) {
                        double x0 = vp.worldToScreenX(pts.get(i - 1).x());
                        double y0 = vp.worldToScreenY(pts.get(i - 1).z());
                        double x1 = vp.worldToScreenX(pts.get(i).x());
                        double y1 = vp.worldToScreenY(pts.get(i).z());
                        double abx = x1 - x0;
                        double aby = y1 - y0;
                        double lenSq = abx * abx + aby * aby;
                        double t = lenSq > 1e-6 ? Mth.clamp(((mouseX - x0) * abx + (mouseY - y0) * aby) / lenSq, 0.0, 1.0) : 0.0;
                        double px = x0 + t * abx - mouseX;
                        double py = y0 + t * aby - mouseY;
                        if (px * px + py * py <= hitSq) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (hit) {
                    authorName = stroke.getAuthorName();
                    authorUuid = stroke.getAuthorUuid();
                    break;
                }
            }
        }

        if (authorName == null) {
            return;
        }

        int textW = font.width(authorName);
        int headSize = 10;
        int pad = 4;
        int boxW = pad + headSize + 4 + textW + pad;
        int boxH = pad + headSize + pad;
        int bx = Math.min(mouseX + 10, vp.width() - boxW - 4);
        int by = Math.max(4, mouseY - boxH - 6);
        drawTooltipBox(g, bx, by, boxW, boxH);

        PlayerSkin skin = null;
        if (authorUuid != null && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(authorUuid);
            if (info != null) {
                skin = info.getSkin();
            }
        }
        if (skin == null) {
            UUID fallbackUuid = authorUuid;
            if (fallbackUuid == null) {
                if (!authorName.equals(this.cachedOfflineSkinName) || this.cachedOfflineSkinUuid == null) {
                    this.cachedOfflineSkinName = authorName;
                    this.cachedOfflineSkinUuid = UUID.nameUUIDFromBytes(authorName.getBytes(StandardCharsets.UTF_8));
                }
                fallbackUuid = this.cachedOfflineSkinUuid;
            }
            skin = DefaultPlayerSkin.get(fallbackUuid);
        }
        Identifier skinTex = skin.body().texturePath();
        g.blit(RenderPipelines.GUI_TEXTURED, skinTex, bx + pad, by + pad, 8.0f, 8.0f, headSize, headSize, 8, 8, 64, 64);
        g.blit(RenderPipelines.GUI_TEXTURED, skinTex, bx + pad, by + pad, 40.0f, 8.0f, headSize, headSize, 8, 8, 64, 64);
        g.text(font, authorName, bx + pad + headSize + 4, by + pad + 1, 0xFFF3E5C8, true);
    }

    private static void drawTooltipBox(GuiGraphicsExtractor g, int bx, int by, int boxW, int boxH) {
        g.fill(bx, by, bx + boxW, by + boxH, 0xE01A120B);
        g.fill(bx, by, bx + boxW, by + 1, 0xFF8B6B47);
        g.fill(bx, by + boxH - 1, bx + boxW, by + boxH, 0xFF8B6B47);
        g.fill(bx, by, bx + 1, by + boxH, 0xFF8B6B47);
        g.fill(bx + boxW - 1, by, bx + boxW, by + boxH, 0xFF8B6B47);
    }
}
