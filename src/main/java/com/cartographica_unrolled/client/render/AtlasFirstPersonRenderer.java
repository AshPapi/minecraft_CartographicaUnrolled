package com.cartographica_unrolled.client.render;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.state.AtlasStateManager;
import com.cartographica_unrolled.util.AtlasMath;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.cartographica_unrolled.registry.ModAttachments;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderHandEvent;

@EventBusSubscriber(modid = CartographicaUnrolled.MOD_ID, value = Dist.CLIENT)
public final class AtlasFirstPersonRenderer {
    public static final float ATLAS_SCALE_1P_START = 1.05f;
    private static final float GRIP_ROD_X = 11.1f / 16.0f;
    private static final float GRIP_ROD_Y = -2.8f / 16.0f;
    private static final float GRIP_ROD_Z = 0.34f / 16.0f;

    private static final ItemStackRenderState ROLLED_STATE = new ItemStackRenderState();
    private static final ItemStackRenderState UNROLLED_STATE = new ItemStackRenderState();
    private static final Matrix4f SCRATCH_TARGET_POSE = new Matrix4f();
    private static final Matrix4f SCRATCH_ALIGNED_POSE = new Matrix4f();
    private static final Matrix4f SCRATCH_INITIAL_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_RIG_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_PARENT_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_TEMP_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_HAND_FRAME = new Matrix4f();
    private static final Vector3f SCRATCH_PALM = new Vector3f();
    private static final Vector3f SCRATCH_START_PALM = new Vector3f();
    private static final Vector3f SCRATCH_TARGET_PALM = new Vector3f();
    private static final Vector3f SCRATCH_PALM_OFFSET = new Vector3f();
    private static final Quaternionf SCRATCH_ORIENT = new Quaternionf();
    private static final Quaternionf SCRATCH_TARGET_ROT = new Quaternionf();
    private static final RandomSource SCRATCH_RANDOM = RandomSource.create(0L);

    private AtlasFirstPersonRenderer() {
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        float partialTick = event.getPartialTick();
        float t = AtlasStateManager.getInterpolatedProgress(partialTick);
        if (t <= 0.001f) {
            return;
        }

        PlayerRenderState playerState = event.getPlayerState();
        FirstPersonHandsAndItemsRenderState.HandRenderSelection selection = playerState.firstPersonHandsAndItems.handRenderSelection;
        boolean renderOnMain = selection == null || selection.renderMainHand;
        InteractionHand activeHand = renderOnMain ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;

        event.setCanceled(true);
        if (event.getHand() != activeHand) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        AvatarRenderState avatarState = playerState.avatarRenderState;
        if (mc.player == null || avatarState == null) {
            return;
        }

        ItemStack equipped = mc.player.getData(ModAttachments.ATLAS_SLOT);
        AtlasSurfaceRenderer.extractRolledModel(ROLLED_STATE, equipped, mc.level, mc.player, mc.player.getId());
        AtlasSurfaceRenderer.extractUnrolledModel(UNROLLED_STATE, mc.level, mc.player, mc.player.getId());

        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        int light = event.getPackedLight();

        HumanoidArm mainArm = avatarState.mainArm;
        HumanoidArm offArm = mainArm.getOpposite();
        float side = mainArm == HumanoidArm.RIGHT ? 1.0f : -1.0f;

        double playerX = Mth.lerp(partialTick, mc.player.xo, mc.player.getX());
        double playerZ = Mth.lerp(partialTick, mc.player.zo, mc.player.getZ());
        double px = playerX;
        double pz = playerZ;
        float worldBlocksAcross = AtlasSurfaceRenderer.WORLD_BLOCKS_ACROSS;
        if (AtlasStateManager.hasLastView()) {
            px = AtlasStateManager.getLastViewX();
            pz = AtlasStateManager.getLastViewZ();
            worldBlocksAcross = AtlasStateManager.getLastViewBlocksAcross();
        }
        float pyaw = mc.player.getViewYRot(partialTick);

        float centerX0 = -side * 0.14f;
        float centerY0 = -0.04f;
        float centerZ0 = -1.05f;
        float tiltX0 = -6.5f;
        float tiltRad0 = tiltX0 * Mth.DEG_TO_RAD;

        float rad0 = 0.82f;
        float rollLocalZ0 = (0.038f * rad0 + 0.008f) * ATLAS_SCALE_1P_START;
        float rollEndX = centerX0 + side * (AtlasSurfaceRenderer.MODEL_HALF_W * 0.07f * ATLAS_SCALE_1P_START);
        float rollEndY = centerY0 - rollLocalZ0 * Mth.sin(tiltRad0);
        float rollEndZ = centerZ0 + rollLocalZ0 * Mth.cos(tiltRad0);

        float mainStartGripX = centerX0 + side * 0.09f;
        float offStartGripX = centerX0 - side * 0.09f;
        float startGripY = -0.04f;
        float startGripZ = -0.90f;

        poseStack.pushPose();

        if (t < 0.28f) {
            float u = smoothstep(t / 0.28f);
            float liftArc = Mth.sin(u * (float) Math.PI) * 0.075f;
            float scrollX = Mth.lerp(u, side * 0.54f, rollEndX);
            float scrollY = Mth.lerp(u, -0.78f, rollEndY) + liftArc;
            float scrollZ = Mth.lerp(u, -0.50f, rollEndZ);

            poseStack.pushPose();
            poseStack.translate(scrollX, scrollY, scrollZ);
            poseStack.rotateDegrees(Axis.XP, Mth.lerp(u, 52.0f, tiltX0));
            poseStack.rotateDegrees(Axis.ZP, Mth.lerp(u, side * -28.0f, 0.0f));
            poseStack.rotateDegrees(Axis.YP, Mth.lerp(u, side * 42.0f, 0.0f));
            float sxz = Mth.lerp(u, 0.76f, ATLAS_SCALE_1P_START * rad0);
            float sy = ATLAS_SCALE_1P_START;
            poseStack.scale(sxz, sy, sxz);
            ROLLED_STATE.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();

            if (!avatarState.isInvisible) {
                float mainX = Mth.lerp(u, side * 0.58f, mainStartGripX);
                float mainY = Mth.lerp(u, -0.82f, startGripY) + liftArc;
                float mainZ = Mth.lerp(u, -0.47f, startGripZ);
                renderGripHand(
                        poseStack,
                        collector,
                        light,
                        mainArm,
                        playerState,
                        mainX,
                        mainY,
                        mainZ,
                        Mth.lerp(u, -24.0f, -8.0f),
                        Mth.lerp(u, side * 14.0f, 0.0f),
                        Mth.lerp(u, side * -18.0f, 0.0f)
                );

                float offEnter = smoothstep(Mth.clamp((u - 0.16f) / 0.84f, 0.0f, 1.0f));
                float offX = Mth.lerp(offEnter, -side * 0.54f, offStartGripX);
                float offY = Mth.lerp(offEnter, -0.76f, startGripY) + liftArc * offEnter;
                float offZ = Mth.lerp(offEnter, -0.52f, startGripZ);
                renderGripHand(
                        poseStack,
                        collector,
                        light,
                        offArm,
                        playerState,
                        offX,
                        offY,
                        offZ,
                        Mth.lerp(offEnter, -20.0f, -8.0f),
                        0.0f,
                        Mth.lerp(offEnter, side * 14.0f, 0.0f)
                );
            }
        } else {
            float uUnroll = easeOutCubic(Mth.clamp((t - 0.28f) / 0.54f, 0.0f, 1.0f));
            float uDive = smoothstep(Mth.clamp((t - 0.62f) / 0.28f, 0.0f, 1.0f));

            float atlasScale = ATLAS_SCALE_1P_START;
            float centerX = Mth.lerp(uUnroll, centerX0, 0.0f);
            float centerY = Mth.lerp(uUnroll, centerY0, -0.03f);
            float centerZ = centerZ0;
            float tiltX = Mth.lerp(uUnroll, tiltX0, -5.0f);
            var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            int winW = Math.max(1, mc.getWindow().getWidth());
            int winH = Math.max(1, mc.getWindow().getHeight());
            float guiWidth = Math.max(48.0f, mc.getWindow().getGuiScaledWidth());
            float guiHeight = Math.max(48.0f, mc.getWindow().getGuiScaledHeight());
            float distance = 0.65f;
            float halfHeight = distance * (float) Math.tan(camera.hudFov * Mth.DEG_TO_RAD * 0.5f);
            float aspect = (float) winW / winH;
            float targetScaleX = halfHeight * aspect * (1.0f - 24.0f / guiWidth) / AtlasSurfaceRenderer.MAP_HALF_W;
            float targetScaleY = halfHeight * (1.0f - 24.0f / guiHeight) / AtlasSurfaceRenderer.MAP_HALF_H;
            Matrix4f targetPose = SCRATCH_TARGET_POSE.set(camera.viewRotationMatrix).invert()
                    .translate(0.0f, 0.0f, -distance - 0.0091f)
                    .scale(targetScaleX, targetScaleY, 1.0f);

            poseStack.pushPose();
            poseStack.translate(centerX, centerY, centerZ);
            poseStack.rotateDegrees(Axis.XP, tiltX);
            poseStack.scale(atlasScale, atlasScale, atlasScale);
            float localMidX = (-side * 0.68f) * (1.0f - Mth.lerp(uUnroll, 0.07f, 1.0f));
            poseStack.translate(-localMidX, 0.0f, 0.0f);
            Matrix4f alignedPose = SCRATCH_ALIGNED_POSE.set(poseStack.last().pose()).lerp(targetPose, uDive);
            poseStack.setIdentity();
            poseStack.mulPose(alignedPose);
            int rawBlock = (light >> 4) & 0xF;
            int rawSky = (light >> 20) & 0xF;
            int sheetLight = (Mth.lerpInt(uDive, rawSky, 15) << 20) | (Mth.lerpInt(uDive, rawBlock, 15) << 4);
            AtlasSurfaceRenderer.submitUnrolledWithMap(
                    poseStack,
                    collector,
                    UNROLLED_STATE,
                    ROLLED_STATE,
                    sheetLight,
                    uUnroll,
                    -side,
                    px,
                    pz,
                    pyaw,
                    true,
                    true,
                    Mth.lerp(uDive, 1.0f, targetScaleX / targetScaleY * guiWidth / (aspect * guiHeight)),
                    worldBlocksAcross,
                    playerX,
                    playerZ
            );
            poseStack.popPose();

            if (!avatarState.isInvisible) {
                float attachment = smoothstep(Mth.clamp(uUnroll / 0.60f, 0.0f, 1.0f));
                float unrollScale = Mth.lerp(uUnroll, 0.07f, 1.0f);
                float anchorX = -side * 0.68f;
                float offRodX = anchorX + (-side * GRIP_ROD_X - anchorX) * unrollScale;
                float mainRodX = anchorX + (side * GRIP_ROD_X - anchorX) * unrollScale;
                renderAnchoredGripHand(poseStack, collector, light, offArm, playerState,
                        alignedPose, offRodX, attachment, offStartGripX, startGripY, startGripZ,
                        -side * 8.0f * uUnroll);
                renderAnchoredGripHand(poseStack, collector, light, mainArm, playerState,
                        alignedPose, mainRodX, attachment, mainStartGripX, startGripY, startGripZ,
                        side * 8.0f * uUnroll);
            }
        }

        poseStack.popPose();
    }

    private static void renderAnchoredGripHand(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            HumanoidArm arm,
            PlayerRenderState playerState,
            Matrix4f atlasPose,
            float rodX,
            float attachment,
            float startX,
            float startY,
            float startZ,
            float yaw
    ) {
        AvatarRenderState avatarState = playerState.avatarRenderState;
        if (avatarState == null) {
            return;
        }
        AvatarRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(avatarState);
        var armModel = arm == HumanoidArm.RIGHT ? renderer.getModel().rightArm : renderer.getModel().leftArm;
        float minX = -1.0f;
        float maxX = 3.0f;
        float maxY = 10.0f;
        float minZ = -2.0f;
        if (!armModel.isEmpty()) {
            SCRATCH_RANDOM.setSeed(0L);
            var cube = armModel.getRandomCube(SCRATCH_RANDOM);
            minX = cube.minX;
            maxX = cube.maxX;
            maxY = cube.maxY;
            minZ = cube.minZ;
        }
        var initial = armModel.getInitialPose();
        float invert = arm == HumanoidArm.RIGHT ? 1.0f : -1.0f;
        Vector3f palm = SCRATCH_PALM.set((minX + maxX) / 32.0f,
                (maxY - 1.0f) / 16.0f, minZ / 16.0f);
        SCRATCH_INITIAL_MAT.translation(initial.x() / 16.0f, initial.y() / 16.0f, initial.z() / 16.0f)
                .rotateZYX(invert * 0.1f, initial.yRot(), initial.xRot())
                .scale(initial.xScale(), initial.yScale(), initial.zScale()).transformPosition(palm);
        Matrix4f rig = SCRATCH_RIG_MAT.identity()
                .rotateY(yaw * Mth.DEG_TO_RAD)
                .rotateX(-8.0f * Mth.DEG_TO_RAD)
                .translate(invert * -0.375f, 0.482f, 0.154f)
                .rotateY((float) Math.PI)
                .rotateX(45.0f * Mth.DEG_TO_RAD)
                .rotateZ(invert * -41.0f * Mth.DEG_TO_RAD)
                .translate(invert * 0.3f, -1.1f, 0.45f);
        Matrix4f parent = SCRATCH_PARENT_MAT.set(poseStack.last().pose());
        Vector3f startPalm = SCRATCH_TEMP_MAT.set(parent).translate(startX, startY, startZ)
                .mul(rig).transformPosition(SCRATCH_START_PALM.set(palm));
        Vector3f targetPalm = atlasPose.transformPosition(SCRATCH_TARGET_PALM.set(rodX, GRIP_ROD_Y, GRIP_ROD_Z));
        startPalm.lerp(targetPalm, attachment);
        Quaternionf orientation = parent.getUnnormalizedRotation(SCRATCH_ORIENT).normalize();
        orientation.slerp(atlasPose.getUnnormalizedRotation(SCRATCH_TARGET_ROT).normalize(), attachment);
        float handScale = Mth.lerp(attachment, 1.0f, ATLAS_SCALE_1P_START);
        Matrix4f handFrame = SCRATCH_HAND_FRAME.rotation(orientation).scale(handScale);
        Vector3f palmOffset = SCRATCH_TEMP_MAT.set(handFrame).mul(rig).transformPosition(SCRATCH_PALM_OFFSET.set(palm));
        handFrame.setTranslation(startPalm.sub(palmOffset));
        poseStack.pushPose();
        poseStack.setIdentity();
        poseStack.mulPose(handFrame);
        renderGripHand(poseStack, collector, light, arm, playerState, 0.0f, 0.0f, 0.0f, -8.0f, yaw, 0.0f);
        poseStack.popPose();
    }

    private static void renderGripHand(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int light,
            HumanoidArm arm,
            PlayerRenderState playerState,
            float gripX,
            float gripY,
            float gripZ,
            float extraPitchDeg,
            float extraYawDeg,
            float extraRollDeg
    ) {
        AvatarRenderState avatarState = playerState.avatarRenderState;
        if (avatarState == null) {
            return;
        }
        float invert = arm == HumanoidArm.RIGHT ? 1.0f : -1.0f;
        poseStack.pushPose();
        poseStack.translate(gripX, gripY, gripZ);
        poseStack.rotateDegrees(Axis.YP, extraYawDeg);
        poseStack.rotateDegrees(Axis.XP, extraPitchDeg);
        poseStack.rotateDegrees(Axis.ZP, extraRollDeg);
        poseStack.translate(invert * -0.375f, 0.482f, 0.154f);
        poseStack.rotateDegrees(Axis.YP, 180.0f);
        poseStack.rotateDegrees(Axis.XP, 45.0f);
        poseStack.rotateDegrees(Axis.ZP, invert * -41.0f);
        poseStack.translate(invert * 0.3f, -1.1f, 0.45f);

        AvatarRenderer<?> avatarRenderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(avatarState);
        Identifier skinTexture = avatarState.skin.body().texturePath();
        if (arm == HumanoidArm.RIGHT) {
            if (!ClientHooks.renderSpecificFirstPersonArm(
                    poseStack,
                    collector,
                    light,
                    skinTexture,
                    avatarState.showRightSleeve,
                    playerState,
                    HumanoidArm.RIGHT,
                    avatarRenderer.getModel().rightArm
            )) {
                avatarRenderer.renderRightHand(poseStack, collector, light, skinTexture, avatarState.showRightSleeve);
            }
        } else {
            if (!ClientHooks.renderSpecificFirstPersonArm(
                    poseStack,
                    collector,
                    light,
                    skinTexture,
                    avatarState.showLeftSleeve,
                    playerState,
                    HumanoidArm.LEFT,
                    avatarRenderer.getModel().leftArm
            )) {
                avatarRenderer.renderLeftHand(poseStack, collector, light, skinTexture, avatarState.showLeftSleeve);
            }
        }
        poseStack.popPose();
    }

    private static float smoothstep(float x) {
        return AtlasMath.smoothstep(x);
    }

    private static float easeOutCubic(float x) {
        return AtlasMath.easeOutCubic(x);
    }
}
