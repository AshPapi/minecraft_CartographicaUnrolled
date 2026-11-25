package com.cartographica_unrolled.client.state;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.render.AtlasSurfaceRenderer;
import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.mixin.ClientInputAccessor;
import com.cartographica_unrolled.network.AtlasPayloads;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import com.cartographica_unrolled.client.ui.AtlasScreen;
import com.cartographica_unrolled.client.network.ClientNetworking;

@EventBusSubscriber(modid = CartographicaUnrolled.MOD_ID, value = Dist.CLIENT)
public class AtlasStateManager {
    private static final float UNFOLD_TURN_LOCK_PROGRESS = 0.86f;
    private static AtlasPhase phase = AtlasPhase.CLOSED;
    private static float progress = 0.0f;
    private static float prevProgress = 0.0f;
    private static double lastViewX = 0.0;
    private static double lastViewZ = 0.0;
    private static float lastViewBlocksAcross = AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS;
    private static boolean hasLastView = false;
    private static boolean disconnecting = false;

    public static boolean isDisconnecting() {
        return disconnecting;
    }

    public static void setLastView(double x, double z, float blocksAcross) {
        lastViewX = x;
        lastViewZ = z;
        lastViewBlocksAcross = Math.max(16.0f, blocksAcross);
        hasLastView = true;
    }

    public static boolean hasLastView() {
        return hasLastView;
    }

    public static double getLastViewX() {
        return lastViewX;
    }

    public static double getLastViewZ() {
        return lastViewZ;
    }

    public static float getLastViewBlocksAcross() {
        return lastViewBlocksAcross;
    }

    public static AtlasPhase getPhase() {
        return phase;
    }

    public static float getInterpolatedProgress(float partialTick) {
        return Mth.lerp(partialTick, prevProgress, progress);
    }

    public static boolean tryStartUnfolding() {
        if (phase != AtlasPhase.CLOSED && phase != AtlasPhase.FOLDING) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return false;
        }
        if (!AtlasPhase.canStartUnfolding(mc.player)) {
            mc.player.sendOverlayMessage(Component.translatable("message.cartographica_unrolled.cannot_unroll"));
            return false;
        }
        mc.player.setSprinting(false);
        boolean wasFolding = (phase == AtlasPhase.FOLDING);
        phase = AtlasPhase.UNFOLDING;
        if (!wasFolding) {
            progress = 0.0f;
            prevProgress = 0.0f;
            hasLastView = false;
            lastViewBlocksAcross = AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS;
            mc.player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.55f, 1.15f);
        }
        mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 1.0f, 0.9f);
        ClientNetworking.sendAtlasAction(com.cartographica_unrolled.network.AtlasPayloads.SetAtlasActionPayload.Action.START_UNFOLD);
        return true;
    }

    public static void startFolding() {
        if (phase == AtlasPhase.READING_UI || phase == AtlasPhase.UNFOLDING) {
            phase = AtlasPhase.FOLDING;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.9f, 1.15f);
            }
            ClientNetworking.sendAtlasAction(com.cartographica_unrolled.network.AtlasPayloads.SetAtlasActionPayload.Action.START_FOLD);
        }
    }

    public static void forceClose() {
        onServerForceClose();
        ClientNetworking.sendAtlasAction(com.cartographica_unrolled.network.AtlasPayloads.SetAtlasActionPayload.Action.FORCE_CLOSE);
    }

    public static void onServerForceClose() {
        closeImmediate(false);
    }

    public static void onClientDisconnect() {
        closeImmediate(true);
    }

    private static void closeImmediate(boolean isDisconnect) {
        phase = AtlasPhase.CLOSED;
        progress = 0.0f;
        prevProgress = 0.0f;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null && mc.gui.screen() instanceof AtlasScreen) {
            disconnecting = isDisconnect;
            try {
                mc.setScreenAndShow(null);
            } finally {
                disconnecting = false;
            }
        }
        hasLastView = false;
        lastViewBlocksAcross = AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            if (phase != AtlasPhase.CLOSED) {
                phase = AtlasPhase.CLOSED;
                progress = 0.0f;
                prevProgress = 0.0f;
            }
            hasLastView = false;
            lastViewBlocksAcross = AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS;
            ClientAtlasAnimTracker.clear();
            return;
        }

        ClientAtlasAnimTracker.tick(mc.level);

        prevProgress = progress;
        if (phase == AtlasPhase.CLOSED) {
            progress = 0.0f;
            prevProgress = 0.0f;
            return;
        }

        if (!AtlasPhase.canKeepUsing(mc.player)) {
            forceClose();
            return;
        }

        mc.player.setSprinting(false);

        if (phase == AtlasPhase.UNFOLDING) {
            if (mc.gui.screen() != null && !(mc.gui.screen() instanceof AtlasScreen)) {
                forceClose();
                return;
            }
            progress = Math.min(1.0f, progress + (1.0f / AtlasPhase.UNFOLD_TICKS));
            if (progress >= 1.0f) {
                phase = AtlasPhase.READING_UI;
                mc.setScreenAndShow(new AtlasScreen());
            }
        } else if (phase == AtlasPhase.READING_UI) {
            progress = 1.0f;
            if (!(mc.gui.screen() instanceof AtlasScreen)) {
                startFolding();
            }
        } else if (phase == AtlasPhase.FOLDING) {
            progress = Math.max(0.0f, progress - (1.0f / AtlasPhase.FOLD_TICKS));
            if (progress <= 0.0f) {
                progress = 0.0f;
                phase = AtlasPhase.CLOSED;
                mc.player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.45f, 0.95f);
            }
        }
    }

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (phase != AtlasPhase.CLOSED) {
            event.getInput().keyPresses = Input.EMPTY;
            ((ClientInputAccessor) event.getInput()).cartographica_unrolled$setMoveVector(Vec2.ZERO);
        }
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (phase != AtlasPhase.CLOSED) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    private static boolean isCameraTurnLocked() {
        if (phase == AtlasPhase.CLOSED) {
            return false;
        }
        if (phase == AtlasPhase.UNFOLDING || phase == AtlasPhase.FOLDING) {
            Minecraft mc = Minecraft.getInstance();
            float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            return getInterpolatedProgress(partialTick) >= UNFOLD_TURN_LOCK_PROGRESS;
        }
        return true;
    }

    @SubscribeEvent
    public static void onCalculatePlayerTurn(CalculatePlayerTurnEvent event) {
        if (isCameraTurnLocked()) {
            event.setMouseSensitivity(-((double) 0.2F) / ((double) 0.6F));
            event.setCinematicCameraEnabled(false);
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        float p = getInterpolatedProgress(event.getPartialTick());
        if (phase == AtlasPhase.CLOSED && p <= 0.001f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        float dive = AtlasMath.smoothstep((p - 0.78f) / 0.22f);
        float armSign = (mc.player != null && mc.player.getMainArm() == HumanoidArm.LEFT) ? -1.0f : 1.0f;
        float braceRoll = Mth.sin(p * (float) Math.PI) * 0.6f * armSign * (1.0f - dive);
        event.setRoll(event.getRoll() + braceRoll);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        float p = getInterpolatedProgress(event.getPartialTick());
        if (phase == AtlasPhase.CLOSED && p <= 0.001f) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        if (p > 0.82f) {
            float dive = AtlasMath.smoothstep((p - 0.82f) / 0.18f);
            event.setFOV(event.getFOV() * Mth.lerp(dive, 1.0f, 0.78f));
        }
    }

    @SubscribeEvent
    public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
        if (VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            if (phase != AtlasPhase.CLOSED || getInterpolatedProgress(partialTick) > 0.001f) {
                event.setCanceled(true);
            }
        }
    }
}
