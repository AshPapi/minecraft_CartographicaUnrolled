package com.cartographica_unrolled.client.ui;

import com.cartographica_unrolled.client.event.ClientEvents;
import com.cartographica_unrolled.client.map.AtlasRouteManager;
import com.cartographica_unrolled.client.network.ClientNetworking;
import com.cartographica_unrolled.client.state.AtlasLiveTracker;
import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.data.AtlasSavedData;
import com.cartographica_unrolled.data.MapMarker;
import com.cartographica_unrolled.data.MapStroke;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.cartographica_unrolled.registry.ModSounds;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.DyeColor;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLMouse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.cartographica_unrolled.client.ui.AtlasViewport.FRAME_PAD;

public class AtlasScreen extends Screen {
    private static final float ERASE_RADIUS_PX = 9.0f;

    private final AtlasViewport viewport = new AtlasViewport();
    private final AtlasMarkerLabelEditor labelEditor = new AtlasMarkerLabelEditor();
    private final AtlasScreenMapRenderer mapRenderer = new AtlasScreenMapRenderer();
    private final AtlasScreenHud hud = new AtlasScreenHud();

    private AtlasEditorState.Tool activeTool = AtlasEditorState.Tool.NONE;
    private DyeColor selectedDye = DyeColor.RED;
    private DyeColor selectedStamp = DyeColor.RED;
    private boolean showGrid;

    private boolean draggingPan;
    private int panButton = -1;
    private long lastLeftClickNanos;
    private double lastLeftClickX;
    private double lastLeftClickY;
    private boolean drawingStroke;
    private boolean erasing;
    private boolean removingMarkers;
    private boolean erasedAnyStroke;
    private boolean erasedAnyAction;
    private float lastEraseWorldX;
    private float lastEraseWorldZ;
    private long lastDrawSoundMillis;
    private long lastEraseSoundMillis;
    private double lastDrawSoundX;
    private double lastDrawSoundY;
    private double lastEraseSoundX;
    private double lastEraseSoundY;
    private @Nullable MapStroke activeStroke;
    private boolean closedOnce;

    private final AtlasSidebar.Callbacks sidebarCallbacks = new AtlasSidebar.Callbacks() {
        @Override
        public void onSelectTool(AtlasEditorState.Tool tool) {
            finishActiveInteractions();
            AtlasScreen.this.labelEditor.commit(AtlasScreen.this, AtlasScreen.this.minecraft);
            AtlasScreen.this.activeTool = tool;
        }

        @Override
        public void onSelectDye(DyeColor dye) {
            finishActiveInteractions();
            AtlasScreen.this.selectedDye = dye;
        }

        @Override
        public void onSelectStamp(DyeColor stamp) {
            finishActiveInteractions();
            AtlasScreen.this.selectedStamp = stamp;
        }

        @Override
        public void onBeforeToggle() {
            finishActiveInteractions();
            AtlasScreen.this.labelEditor.commit(AtlasScreen.this, AtlasScreen.this.minecraft);
        }
    };

    public AtlasScreen() {
        super(Component.translatable("screen.cartographica_unrolled.atlas"));
    }

    @Override
    protected void init() {
        super.init();
        AtlasSidebar.updateLayout(this.width, this.height);
        Minecraft mc = Minecraft.getInstance();
        this.viewport.initForScreen(this.width, this.height, mc.player);
        this.labelEditor.onScreenInit(this, this.minecraft);
    }

    @Override
    public boolean isInputCaptured() {
        return this.labelEditor.isEditing() || super.isInputCaptured();
    }

    @Override
    public void removed() {
        if (!AtlasStateManager.isDisconnecting()) {
            this.viewport.saveLastViewState();
            finishActiveInteractions();
            this.labelEditor.commit(this, this.minecraft);
        } else {
            this.draggingPan = false;
            this.drawingStroke = false;
            this.activeStroke = null;
            this.erasing = false;
            this.erasedAnyStroke = false;
            this.erasedAnyAction = false;
            this.removingMarkers = false;
            this.labelEditor.abortOnDisconnect(this, this.minecraft);
        }
        this.mapRenderer.clearScratch();
        AtlasEditorState.clearUndoHistory();
        super.removed();
    }

    @Override
    public void tick() {
        super.tick();
        finishUnheldMouseActions();
        this.labelEditor.onTick(this, this.minecraft);
    }

    @Override
    public void onClose() {
        this.viewport.saveLastViewState();
        this.labelEditor.commit(this, this.minecraft);
        super.onClose();
        if (!this.closedOnce) {
            this.closedOnce = true;
            AtlasStateManager.startFolding();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        finishUnheldMouseActions();
        this.viewport.stepAnimation();
        this.hud.updateStatusBarMetrics(this.font, this.viewport, mouseX, mouseY);

        Minecraft mc = Minecraft.getInstance();
        List<AtlasPayloads.RemotePlayerPos> visibleOthers = mc.level != null && mc.player != null
                ? AtlasLiveTracker.getVisibleOtherPlayers(mc.level, mc.player, partialTick)
                : List.of();

        guiGraphics.fill(0, 0, this.width, this.height, 0xFF140C06);
        guiGraphics.enableScissor(FRAME_PAD, FRAME_PAD, this.width - FRAME_PAD, this.height - FRAME_PAD);

        boolean overUi = this.hud.isMouseOverAnyPanel(this.viewport, this.activeTool, mouseX, mouseY);
        this.mapRenderer.renderCanvas(
                guiGraphics,
                this.font,
                this.minecraft,
                this.viewport,
                this.labelEditor,
                this.activeTool,
                this.activeStroke,
                this.showGrid,
                overUi,
                mouseX,
                mouseY,
                partialTick,
                visibleOthers
        );
        if (!overUi) {
            this.hud.drawToolCursor(guiGraphics, this.activeTool, this.draggingPan, ERASE_RADIUS_PX, mouseX, mouseY);
        }

        guiGraphics.disableScissor();
        guiGraphics.nextStratum();

        this.hud.renderOverlayChrome(
                guiGraphics,
                this.mapRenderer.batcher(),
                this.font,
                this.viewport,
                this.activeTool,
                this.selectedDye,
                this.selectedStamp,
                !overUi && !this.labelEditor.isEditing(),
                mouseX,
                mouseY,
                visibleOthers,
                this.mapRenderer.visibleMarkers(),
                this.mapRenderer.visibleStrokes()
        );

        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
    }

    public double screenToWorldX(double screenX) {
        return this.viewport.screenToWorldX(screenX);
    }

    public double screenToWorldZ(double screenY) {
        return this.viewport.screenToWorldZ(screenY);
    }

    public double worldToScreenX(double worldX) {
        return this.viewport.worldToScreenX(worldX);
    }

    public double worldToScreenY(double worldZ) {
        return this.viewport.worldToScreenY(worldZ);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (AtlasSidebar.isMouseOver(mouseX, mouseY, this.activeTool)) {
            return true;
        }
        this.viewport.onScrollZoom(mouseX, mouseY, scrollY);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.labelEditor.isEditing() && event.button() > 1 && ClientEvents.OPEN_ATLAS_KEY.matchesMouse(event)) {
            this.labelEditor.commit(this, this.minecraft);
            onClose();
            return true;
        }

        double mx = event.x();
        double my = event.y();

        if (AtlasSidebar.mouseClicked(mx, my, event.button(), this.activeTool, this.sidebarCallbacks)) {
            return true;
        }

        if (this.hud.isMouseOverAnyPanel(this.viewport, this.activeTool, mx, my)) {
            finishActiveInteractions();
            this.labelEditor.commit(this, this.minecraft);
            return true;
        }

        boolean wasEditing = this.labelEditor.isEditing();
        finishActiveInteractions();
        this.labelEditor.commit(this, this.minecraft);

        if (!wasEditing && ClientEvents.OPEN_ATLAS_KEY.matchesMouse(event)) {
            onClose();
            return true;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE) {
            startPanDrag(event.button());
            return true;
        }

        float zoom = this.viewport.zoom();
        double worldClickX = screenToWorldX(mx);
        double worldClickZ = screenToWorldZ(my);
        double markerHitWorldR = (9.5 * this.viewport.getMarkerScale()) / zoom;
        double routeHitWorldR = Math.max(4.5, 6.0 / zoom);

        if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            MapMarker hit = AtlasEditorState.findMarkerAt(worldClickX, worldClickZ, markerHitWorldR);
            if (hit != null) {
                this.removingMarkers = true;
                AtlasEditorState.removeMarker(hit.getId());
                playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
                return true;
            }
            if (AtlasRouteManager.isNearTarget(worldClickX, worldClickZ, routeHitWorldR)) {
                AtlasRouteManager.clear();
                playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
                return true;
            }
            String dimKey = ClientNetworking.currentDimensionKey();
            AtlasSavedData.DeathPos death = AtlasLiveTracker.getDeathPos(dimKey);
            if (death != null && Math.hypot(worldClickX - death.x(), worldClickZ - death.z()) <= markerHitWorldR) {
                AtlasLiveTracker.clearDeathPosLocal(dimKey);
                ClientNetworking.sendClearDeathPos();
                playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
                return true;
            }
            startPanDrag(event.button());
            return true;
        }

        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            AtlasEditorState.Tool tool = this.activeTool;
            if (tool == AtlasEditorState.Tool.NONE) {
                long nowNanos = System.nanoTime();
                boolean isDoubleClick = doubleClick
                        || (nowNanos - this.lastLeftClickNanos <= 350_000_000L
                        && Math.hypot(mx - this.lastLeftClickX, my - this.lastLeftClickY) <= 8.0);
                MapMarker hit = AtlasEditorState.findMarkerAt(worldClickX, worldClickZ, markerHitWorldR);
                if (hit != null) {
                    this.lastLeftClickNanos = 0L;
                    this.labelEditor.beginEditing(this, this.minecraft, hit, false);
                    return true;
                }
                if (isDoubleClick) {
                    this.lastLeftClickNanos = 0L;
                    if (AtlasRouteManager.isNearTarget(worldClickX, worldClickZ, routeHitWorldR)) {
                        AtlasRouteManager.clear();
                        playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
                    } else {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null && mc.level != null) {
                            AtlasRouteManager.setTarget(
                                    (float) worldClickX,
                                    (float) worldClickZ,
                                    mc.player.getX(),
                                    mc.player.getZ(),
                                    mc.level.dimension().identifier().toString()
                            );
                            playUiSound(ModSounds.MARKER_PLACE.get(), 0.96f, 1.05f, 0.85f);
                        }
                    }
                    return true;
                }
                this.lastLeftClickNanos = nowNanos;
                this.lastLeftClickX = mx;
                this.lastLeftClickY = my;
                startPanDrag(event.button());
                return true;
            }
            if (tool == AtlasEditorState.Tool.DYE) {
                int strokeColor = 0xFF000000 | this.selectedDye.getMapColor().col;
                this.drawingStroke = true;
                float width = Mth.clamp(3.6f / zoom, 0.6f, 14.4f);
                List<MapStroke.Point> pts = new ArrayList<>();
                pts.add(new MapStroke.Point((float) worldClickX, (float) worldClickZ));
                Minecraft mc = Minecraft.getInstance();
                UUID authorId = mc.player != null ? mc.player.getUUID() : null;
                String authorName = mc.player != null ? mc.player.getName().getString() : null;
                this.activeStroke = new MapStroke(pts, strokeColor, width, true, authorId, authorName);
                playUiSound(ModSounds.BRUSH_PAINT.get(), 0.94f, 1.08f, 0.70f);
                this.lastDrawSoundMillis = System.nanoTime() / 1_000_000L;
                this.lastDrawSoundX = mx;
                this.lastDrawSoundY = my;
                return true;
            }
            if (tool == AtlasEditorState.Tool.ERASER) {
                this.erasing = true;
                this.erasedAnyStroke = false;
                this.erasedAnyAction = false;
                this.lastEraseWorldX = (float) worldClickX;
                this.lastEraseWorldZ = (float) worldClickZ;
                int erased = eraseAt(this.lastEraseWorldX, this.lastEraseWorldZ, getEraseWorldRadius());
                if (erased != 0) {
                    playUiSound(ModSounds.ERASER_RUB.get(), 0.92f, 1.10f, (erased & 1) != 0 ? 0.85f : 0.65f);
                    this.lastEraseSoundMillis = System.nanoTime() / 1_000_000L;
                    this.lastEraseSoundX = mx;
                    this.lastEraseSoundY = my;
                }
                if ((erased & 2) != 0) {
                    playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
                }
                return true;
            }
            if (tool == AtlasEditorState.Tool.STAMP) {
                MapMarker existing = AtlasEditorState.findMarkerAt(worldClickX, worldClickZ, markerHitWorldR * 0.85);
                if (existing != null) {
                    this.labelEditor.beginEditing(this, this.minecraft, existing, false);
                    return true;
                }
                Minecraft mc = Minecraft.getInstance();
                UUID authorId = mc.player != null ? mc.player.getUUID() : null;
                String authorName = mc.player != null ? mc.player.getName().getString() : null;
                MapMarker marker = new MapMarker(
                        UUID.randomUUID(),
                        (float) worldClickX,
                        (float) worldClickZ,
                        MapMarker.BannerType.valueOf(this.selectedStamp.name()),
                        "",
                        authorId,
                        authorName
                );
                AtlasEditorState.addMarker(marker);
                playUiSound(ModSounds.MARKER_PLACE.get(), 0.96f, 1.05f, 0.85f);
                this.labelEditor.beginEditing(this, this.minecraft, marker, true);
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingPan && event.button() == this.panButton) {
            this.draggingPan = false;
            this.panButton = -1;
        }
        if (this.removingMarkers && event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            this.removingMarkers = false;
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && finishActiveInteractions()) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        finishUnheldMouseActions();
        if (this.draggingPan && event.button() == this.panButton) {
            this.viewport.dragBy(dragX, dragY);
            return true;
        }
        if (this.removingMarkers && event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
            double worldX = screenToWorldX(event.x());
            double worldZ = screenToWorldZ(event.y());
            double markerHitWorldR = (9.5 * this.viewport.getMarkerScale()) / this.viewport.zoom();
            MapMarker hit = AtlasEditorState.findMarkerAt(worldX, worldZ, markerHitWorldR);
            if (hit != null) {
                AtlasEditorState.removeMarker(hit.getId());
                playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
            }
            return true;
        }
        if (this.activeTool == AtlasEditorState.Tool.DYE
                && this.drawingStroke
                && this.activeStroke != null
                && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float wx = (float) screenToWorldX(event.x());
            float wz = (float) screenToWorldZ(event.y());
            this.activeStroke.addPoint(wx, wz);
            if (this.activeStroke.getPoints().size() >= 1800) {
                MapStroke finished = this.activeStroke;
                AtlasEditorState.addCompletedStroke(finished);
                var pts = finished.getPoints();
                MapStroke.Point tail = pts.get(pts.size() - 1);
                MapStroke next = new MapStroke(
                        new ArrayList<>(),
                        finished.getColor(),
                        finished.getWidth(),
                        finished.isDye(),
                        finished.getAuthorUuid(),
                        finished.getAuthorName()
                );
                next.addPoint(tail.x(), tail.z());
                this.activeStroke = next;
            }
            long now = System.nanoTime() / 1_000_000L;
            if (now - this.lastDrawSoundMillis >= 125
                    && Math.hypot(event.x() - this.lastDrawSoundX, event.y() - this.lastDrawSoundY) >= 7.0) {
                playUiSound(ModSounds.BRUSH_PAINT.get(), 0.90f, 1.15f, 0.60f);
                this.lastDrawSoundMillis = now;
                this.lastDrawSoundX = event.x();
                this.lastDrawSoundY = event.y();
            }
            return true;
        }
        if (this.activeTool == AtlasEditorState.Tool.ERASER
                && this.erasing
                && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float wx = (float) screenToWorldX(event.x());
            float wz = (float) screenToWorldZ(event.y());
            float eraseRadius = getEraseWorldRadius();
            double dx = (double) wx - this.lastEraseWorldX;
            double dz = (double) wz - this.lastEraseWorldZ;
            int steps = Mth.clamp((int) Math.ceil(Math.hypot(dx, dz) / (eraseRadius * 0.5)), 1, 64);
            int erased = 0;
            for (int i = 1; i <= steps; i++) {
                double t = i / (double) steps;
                erased |= eraseAt((float) (this.lastEraseWorldX + dx * t), (float) (this.lastEraseWorldZ + dz * t), eraseRadius);
            }
            if ((erased & 2) != 0) {
                playUiSound(ModSounds.MARKER_REMOVE.get(), 0.96f, 1.06f, 0.80f);
            }
            long now = System.nanoTime() / 1_000_000L;
            if (erased != 0
                    && now - this.lastEraseSoundMillis >= 120
                    && Math.hypot(event.x() - this.lastEraseSoundX, event.y() - this.lastEraseSoundY) >= 6.0) {
                playUiSound(ModSounds.ERASER_RUB.get(), 0.92f, 1.10f, (erased & 1) != 0 ? 0.85f : 0.65f);
                this.lastEraseSoundMillis = now;
                this.lastEraseSoundX = event.x();
                this.lastEraseSoundY = event.y();
            }
            this.lastEraseWorldX = wx;
            this.lastEraseWorldZ = wz;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.labelEditor.charTyped(event)) {
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == InputConstants.KEY_Z) {
            finishErasing();
            this.drawingStroke = false;
            this.activeStroke = null;
            this.labelEditor.commit(this, this.minecraft);
            AtlasEditorState.undo();
            return true;
        }
        if (!this.labelEditor.isEditing() && this.minecraft != null && this.minecraft.options.keyInventory.matches(event)) {
            onClose();
            return true;
        }
        if (this.labelEditor.handleKeyPressed(this, this.minecraft, event)) {
            return true;
        }
        if (ClientEvents.OPEN_ATLAS_KEY.matches(event)) {
            onClose();
            return true;
        }
        if (event.key() == InputConstants.KEY_SPACE || event.key() == InputConstants.KEY_HOME) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                this.viewport.panSmoothlyTo(mc.player.getX(), mc.player.getZ());
            }
            return true;
        }
        if (event.key() == InputConstants.KEY_G) {
            this.showGrid = !this.showGrid;
            return true;
        }
        return super.keyPressed(event);
    }

    private void startPanDrag(int button) {
        this.viewport.stopSmoothing();
        this.draggingPan = true;
        this.panButton = button;
    }

    private boolean finishActiveInteractions() {
        finishErasing();
        return finishActiveStroke();
    }

    private void finishUnheldMouseActions() {
        if ((!this.drawingStroke && this.activeStroke == null && !this.erasing && !this.draggingPan && !this.removingMarkers)
                || this.minecraft == null) {
            return;
        }
        boolean windowActive = this.minecraft.isWindowActive();
        int buttons = windowActive ? SDLMouse.SDL_GetMouseState(null, null) : 0;
        if ((this.drawingStroke || this.activeStroke != null || this.erasing)
                && (!windowActive || (buttons & SDLMouse.SDL_BUTTON_LMASK) == 0)) {
            finishActiveInteractions();
        }
        if (this.removingMarkers && (!windowActive || (buttons & SDLMouse.SDL_BUTTON_RMASK) == 0)) {
            this.removingMarkers = false;
        }
        if (this.draggingPan && this.panButton >= 0) {
            int panMask = switch (this.panButton) {
                case InputConstants.MOUSE_BUTTON_LEFT -> SDLMouse.SDL_BUTTON_LMASK;
                case InputConstants.MOUSE_BUTTON_RIGHT -> SDLMouse.SDL_BUTTON_RMASK;
                case InputConstants.MOUSE_BUTTON_MIDDLE -> SDLMouse.SDL_BUTTON_MMASK;
                default -> this.panButton < 32 ? (1 << this.panButton) : 0;
            };
            if (!windowActive || (buttons & panMask) == 0) {
                this.draggingPan = false;
                this.panButton = -1;
            }
        }
    }

    private boolean finishActiveStroke() {
        boolean hadStroke = this.drawingStroke || this.activeStroke != null;
        this.drawingStroke = false;
        if (this.activeStroke != null) {
            if (!this.activeStroke.getPoints().isEmpty()) {
                if (this.activeStroke.getPoints().size() >= 2) {
                    this.activeStroke.simplify(0.45f);
                }
                AtlasEditorState.addCompletedStroke(this.activeStroke);
            }
            this.activeStroke = null;
        }
        return hadStroke;
    }

    private float getEraseWorldRadius() {
        return ERASE_RADIUS_PX / this.viewport.zoom();
    }

    private int eraseAt(float worldX, float worldZ, float radius) {
        int erased = 0;
        if (AtlasEditorState.eraseStrokesAt(worldX, worldZ, radius, !this.erasedAnyAction)) {
            this.erasedAnyStroke = true;
            this.erasedAnyAction = true;
            erased |= 1;
        }
        if (AtlasEditorState.eraseMarkersAt(worldX, worldZ, radius, null, !this.erasedAnyAction)) {
            this.erasedAnyAction = true;
            erased |= 2;
        }
        String dimKey = ClientNetworking.currentDimensionKey();
        AtlasSavedData.DeathPos death = AtlasLiveTracker.getDeathPos(dimKey);
        if (death != null && Math.hypot(worldX - death.x(), worldZ - death.z()) <= radius) {
            AtlasLiveTracker.clearDeathPosLocal(dimKey);
            ClientNetworking.sendClearDeathPos();
            erased |= 2;
        }
        return erased;
    }

    private void finishErasing() {
        this.erasing = false;
        if (this.erasedAnyStroke) {
            this.erasedAnyStroke = false;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && mc.getConnection() != null) {
                AtlasEditorState.flushErasedStrokes();
            }
        }
    }

    private static void playUiSound(SoundEvent sound, float minPitch, float maxPitch, float volume) {
        float pitch = minPitch + ThreadLocalRandom.current().nextFloat() * (maxPitch - minPitch);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }
}
