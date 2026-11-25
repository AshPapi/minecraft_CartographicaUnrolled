package com.cartographica_unrolled.client.render;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.cartographica_unrolled.client.state.ClientAtlasAnimTracker;
import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.registry.ModAttachments;
import com.cartographica_unrolled.util.AtlasMath;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class AtlasPlayerLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    public static final ContextKey<RenderData> KEY = new ContextKey<>(
            Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "atlas_render_data")
    );

    public static final float BELT_SCALE = 0.86f;
    public static final float ATLAS_SCALE_3P = 0.9375f * BELT_SCALE;

    private static final Matrix4f GROUND_TO_MODEL_MAT = buildModelToGroundMatrix(new Matrix4f()).invert();
    private static final Matrix4f SCRATCH_BELT_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_SHEET_MAT = new Matrix4f();
    private static final Matrix4f SCRATCH_START_MAT = new Matrix4f();
    private static final Quaternionf SCRATCH_ROT_Q = new Quaternionf();
    private static final Quaternionf SCRATCH_Q_BELT = new Quaternionf();
    private static final Quaternionf SCRATCH_Q_START = new Quaternionf();
    private static final Vector3f SCRATCH_P_BELT = new Vector3f();
    private static final Vector3f SCRATCH_P_BELT_GRIP = new Vector3f();
    private static final Vector3f SCRATCH_P_START = new Vector3f();
    private static final Vector3f SCRATCH_DOM_GRIP = new Vector3f();
    private static final Vector3f SCRATCH_OFF_GRIP = new Vector3f();
    private static final SheetPose SCRATCH_SHEET0 = new SheetPose();

    public static final class RenderData {
        public boolean hasEquippedAtlas;
        public boolean isLocalPlayer;
        public AtlasPhase phase = AtlasPhase.CLOSED;
        public float progress;
        public double worldX;
        public double worldZ;
        public float playerYaw;
        public boolean kinematicsComputed;
        public final KinematicsResult kinematics = new KinematicsResult();
        public final ItemStackRenderState rolledState = new ItemStackRenderState();
        public final ItemStackRenderState unrolledState = new ItemStackRenderState();
    }

    public static final class SheetPose {
        public float mapY;
        public float mapZ;
        public float mapPitch;
        public float atlasScale;
        public float u;
        public float scaleX;
        public float gripModelX;
        public float gripLocalY;
    }

    public static final class KinematicsResult {
        public final Vector3f domGripG = new Vector3f();
        public final Vector3f offGripG = new Vector3f();
        public boolean hasOffGrip;
        public final Vector3f activeRollPos = new Vector3f();
        public final Quaternionf activeRollRot = new Quaternionf();
        public final Vector3f activeRollScale = new Vector3f();
        public final SheetPose sheet = new SheetPose();
    }

    public static final AvatarRenderStateModifier STATE_MODIFIER = new AvatarRenderStateModifier() {
        @Override
        public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState renderState) {
            ItemStack equipped = avatar.getData(ModAttachments.ATLAS_SLOT);
            AtlasPhase phase = ClientAtlasAnimTracker.getPhase(avatar);
            float progress = ClientAtlasAnimTracker.getProgress(avatar, renderState.partialTick);

            if (equipped.isEmpty() && phase == AtlasPhase.CLOSED && progress <= 0.001f) {
                renderState.setRenderData(KEY, null);
                return;
            }

            RenderData data = renderState.getRenderData(KEY);
            if (data == null) {
                data = new RenderData();
            }

            data.hasEquippedAtlas = !equipped.isEmpty();
            data.isLocalPlayer = avatar == Minecraft.getInstance().player;
            data.phase = phase;
            data.progress = progress;
            data.worldX = renderState.x;
            data.worldZ = renderState.z;
            data.playerYaw = avatar.getViewYRot(renderState.partialTick);
            data.kinematicsComputed = false;

            AtlasSurfaceRenderer.extractRolledModel(data.rolledState, equipped, avatar.level(), avatar, avatar.getId());
            if (phase != AtlasPhase.CLOSED || progress > 0.001f) {
                AtlasSurfaceRenderer.extractUnrolledModel(data.unrolledState, avatar.level(), avatar, avatar.getId());
            } else {
                data.unrolledState.clear();
            }

            if ((phase != AtlasPhase.CLOSED || progress > 0.02f) && !renderState.isSpectator) {
                renderState.rightHandItemState.clear();
                renderState.leftHandItemState.clear();
                renderState.rightHandItemStack = ItemStack.EMPTY;
                renderState.leftHandItemStack = ItemStack.EMPTY;
                renderState.rightArmPose = HumanoidModel.ArmPose.EMPTY;
                renderState.leftArmPose = HumanoidModel.ArmPose.EMPTY;
            }

            renderState.setRenderData(KEY, data);
        }
    };

    public static Matrix4f buildModelToGroundMatrix(Matrix4f dest) {
        dest.identity();
        dest.scale(-1.0f, -1.0f, 1.0f);
        dest.scale(0.9375f, 0.9375f, 0.9375f);
        dest.translate(0.0f, -1.501f, 0.0f);
        return dest;
    }

    public static Vector3f transformGroundToModel(Vector3f src, Vector3f dest) {
        return GROUND_TO_MODEL_MAT.transformPosition(src, dest);
    }

    public static void modelToEntityGround(PoseStack poseStack) {
        poseStack.translate(0.0f, 1.501f, 0.0f);
        float invScale = 1.0f / 0.9375f;
        poseStack.scale(invScale, invScale, invScale);
        poseStack.scale(-1.0f, -1.0f, 1.0f);
    }

    public static void applyBeltLocalTransform(PoseStack poseStack, boolean isRightMain, boolean hasArmor, float walkSwingDeg) {
        appendBeltLocalMatrix(poseStack.last().pose(), isRightMain, hasArmor, walkSwingDeg);
        float side = isRightMain ? -1.0f : 1.0f;
        poseStack.last().normal()
                .rotateX((-125.0f + walkSwingDeg) * Mth.DEG_TO_RAD)
                .rotateY((side * 90.0f) * Mth.DEG_TO_RAD);
    }

    public static void appendBeltLocalMatrix(Matrix4f mat, boolean isRightMain, boolean hasArmor, float walkSwingDeg) {
        float side = isRightMain ? -1.0f : 1.0f;
        float armorOutset = hasArmor ? 0.024f : 0.0f;
        mat.translate(side * (0.278f + armorOutset), 0.83f, 0.015f);
        mat.rotateX((-125.0f + walkSwingDeg) * Mth.DEG_TO_RAD);
        mat.rotateY((side * 90.0f) * Mth.DEG_TO_RAD);
        mat.scale(BELT_SCALE, BELT_SCALE, BELT_SCALE);
    }

    private static float computeBeltWalkSwing(AvatarRenderState state, RenderData data, float t) {
        float walkFade = (data.phase == AtlasPhase.CLOSED && t <= 0.001f) ? 1.0f : (1.0f - AtlasMath.smoothstep(t / 0.10f));
        return Mth.cos(state.walkAnimationPos * 0.6662f) * state.walkAnimationSpeed * 2.5f * walkFade;
    }

    public static KinematicsResult ensureKinematics(AvatarRenderState state, RenderData data, ModelPart bodyPart) {
        if (!data.kinematicsComputed) {
            computeKinematics(state, data, bodyPart, data.kinematics);
            data.kinematicsComputed = true;
        }
        return data.kinematics;
    }

    public static void computeSheetPoseGround(float t, SheetPose out) {
        float sy = 0.9375f * (1.501f - 0.125f);
        float sz = 0.0f;

        float u = t >= 0.28f ? AtlasMath.easeOutCubic(Mth.clamp((t - 0.28f) / 0.60f, 0.0f, 1.0f)) : 0.0f;
        float atlasScale = ATLAS_SCALE_3P * Mth.lerp(u, 1.0f, 1.3f);
        float scaleX = Mth.lerp(u, 0.07f, 1.0f);
        float outerEdgeX = AtlasSurfaceRenderer.MODEL_HALF_W * atlasScale * scaleX;
        float gripWorldX = outerEdgeX * 0.96f;
        float gripLocalY = 0.0f;

        float dx = Math.abs(gripWorldX - 0.293f);
        float armLen = 0.509f;
        float yzReach = Mth.sqrt(Math.max(0.04f, armLen * armLen - dx * dx));
        float mapPitch = -24.0f;
        float pitchRad = mapPitch * Mth.DEG_TO_RAD;

        float gyScaled = gripLocalY * atlasScale;
        float gripDyFromCenter = gyScaled * Mth.cos(pitchRad);
        float gripDzFromCenter = gyScaled * Mth.sin(pitchRad);

        float minCornerDrop = AtlasSurfaceRenderer.MODEL_HALF_H * atlasScale * Math.max(0.0f, Mth.cos(pitchRad));
        float minCenterY = 0.026f + minCornerDrop;
        float angleBelow = 26.0f * Mth.DEG_TO_RAD;
        float desiredHandY = sy - yzReach * Mth.sin(angleBelow);
        float mapY = Math.max(minCenterY, desiredHandY - gripDyFromCenter);
        float handY = mapY + gripDyFromCenter;
        float actualDy = sy - handY;
        float actualDz = Mth.sqrt(Math.max(0.01f, yzReach * yzReach - actualDy * actualDy));
        float handZ = sz - actualDz;
        float mapZ = handZ - gripDzFromCenter - 0.10f;

        out.mapY = mapY;
        out.mapZ = mapZ;
        out.mapPitch = mapPitch;
        out.atlasScale = atlasScale;
        out.u = u;
        out.scaleX = scaleX;
        out.gripModelX = gripWorldX / atlasScale;
        out.gripLocalY = gripLocalY;
    }

    public static void computeKinematics(
            AvatarRenderState state,
            RenderData data,
            ModelPart bodyPart,
            KinematicsResult out
    ) {
        float t = data.progress;
        boolean isRight = state.mainArm == HumanoidArm.RIGHT;
        float domSign = isRight ? 1.0f : -1.0f;
        float side = isRight ? -1.0f : 1.0f;
        boolean hasArmor = !state.chestEquipment.isEmpty() || !state.legsEquipment.isEmpty();

        Matrix4f mBeltG = SCRATCH_BELT_MAT.identity();
        buildModelToGroundMatrix(mBeltG);
        mBeltG.translate(bodyPart.x / 16.0f, bodyPart.y / 16.0f, bodyPart.z / 16.0f);
        if (bodyPart.xRot != 0.0f || bodyPart.yRot != 0.0f || bodyPart.zRot != 0.0f) {
            mBeltG.rotate(SCRATCH_ROT_Q.rotationZYX(bodyPart.zRot, bodyPart.yRot, bodyPart.xRot));
        }
        if (bodyPart.xScale != 1.0f || bodyPart.yScale != 1.0f || bodyPart.zScale != 1.0f) {
            mBeltG.scale(bodyPart.xScale, bodyPart.yScale, bodyPart.zScale);
        }
        appendBeltLocalMatrix(mBeltG, isRight, hasArmor, computeBeltWalkSwing(state, data, t));

        Vector3f pBelt = mBeltG.getTranslation(SCRATCH_P_BELT);
        Vector3f pBeltGrip = mBeltG.transformPosition(SCRATCH_P_BELT_GRIP.set(side * 0.11f, 0.18f, 0.04f));
        Quaternionf qBelt = mBeltG.getNormalizedRotation(SCRATCH_Q_BELT);
        float sBelt = 0.9375f * BELT_SCALE;

        computeSheetPoseGround(t, out.sheet);

        if (t < 0.12f) {
            out.domGripG.set(pBeltGrip);
            out.hasOffGrip = false;
            out.activeRollPos.set(pBelt);
            out.activeRollRot.set(qBelt);
            out.activeRollScale.set(sBelt, sBelt, sBelt);
            return;
        }

        if (t < 0.28f) {
            SheetPose sheet0 = SCRATCH_SHEET0;
            computeSheetPoseGround(0.28f, sheet0);
            Matrix4f mSheet0 = SCRATCH_SHEET_MAT.identity()
                    .translate(0.0f, sheet0.mapY, sheet0.mapZ)
                    .rotateX(sheet0.mapPitch * Mth.DEG_TO_RAD)
                    .scale(sheet0.atlasScale, sheet0.atlasScale, sheet0.atlasScale);

            float startHalfOuter = AtlasSurfaceRenderer.MODEL_HALF_W * sheet0.scaleX;
            float rad0 = 0.82f;
            float rollZ0 = 0.038f * rad0 + 0.008f;

            Matrix4f mStartG = SCRATCH_START_MAT.set(mSheet0)
                    .translate(domSign * startHalfOuter, 0.0f, rollZ0)
                    .scale(rad0, 1.0f, rad0);

            Vector3f pStart = mStartG.getTranslation(SCRATCH_P_START);
            Quaternionf qStart = mStartG.getNormalizedRotation(SCRATCH_Q_START);
            if (qBelt.x * qStart.x + qBelt.y * qStart.y + qBelt.z * qStart.z + qBelt.w * qStart.w < 0.0f) {
                qStart.set(-qStart.x, -qStart.y, -qStart.z, -qStart.w);
            }

            Vector3f domStartGrip = mSheet0.transformPosition(SCRATCH_DOM_GRIP.set(domSign * sheet0.gripModelX, sheet0.gripLocalY, 0.0f));
            Vector3f offStartGrip = mSheet0.transformPosition(SCRATCH_OFF_GRIP.set(-domSign * sheet0.gripModelX, sheet0.gripLocalY, 0.0f));

            float k = AtlasMath.smoothstep((t - 0.12f) / 0.16f);
            float arcZ = -0.04f * Mth.sin(k * (float) Math.PI);

            out.activeRollPos.set(
                    Mth.lerp(k, pBelt.x, pStart.x),
                    Mth.lerp(k, pBelt.y, pStart.y),
                    Mth.lerp(k, pBelt.z, pStart.z) + arcZ
            );
            qBelt.slerp(qStart, k, out.activeRollRot);
            out.activeRollScale.set(
                    Mth.lerp(k, sBelt, sheet0.atlasScale * rad0),
                    Mth.lerp(k, sBelt, sheet0.atlasScale * 1.0f),
                    Mth.lerp(k, sBelt, sheet0.atlasScale * rad0)
            );

            out.domGripG.set(
                    Mth.lerp(k, pBeltGrip.x, domStartGrip.x),
                    Mth.lerp(k, pBeltGrip.y, domStartGrip.y),
                    Mth.lerp(k, pBeltGrip.z, domStartGrip.z) + arcZ
            );
            out.offGripG.set(
                    Mth.lerp(k, -domSign * 0.18f, offStartGrip.x),
                    Mth.lerp(k, pBelt.y + 0.10f, offStartGrip.y),
                    Mth.lerp(k, -0.22f, offStartGrip.z) + arcZ
            );
            out.hasOffGrip = true;
            return;
        }

        Matrix4f mSheet = SCRATCH_SHEET_MAT.identity()
                .translate(0.0f, out.sheet.mapY, out.sheet.mapZ)
                .rotateX(out.sheet.mapPitch * Mth.DEG_TO_RAD)
                .scale(out.sheet.atlasScale, out.sheet.atlasScale, out.sheet.atlasScale);

        mSheet.transformPosition(SCRATCH_DOM_GRIP.set(domSign * out.sheet.gripModelX, out.sheet.gripLocalY, 0.0f), out.domGripG);
        mSheet.transformPosition(SCRATCH_OFF_GRIP.set(-domSign * out.sheet.gripModelX, out.sheet.gripLocalY, 0.0f), out.offGripG);
        out.hasOffGrip = true;
    }

    public AtlasPlayerLayer(RenderLayerParent<AvatarRenderState, PlayerModel> renderer) {
        super(renderer);
    }

    @Override
    public void submit(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            AvatarRenderState state,
            float yRot,
            float xRot
    ) {
        if (state.isInvisible || state.isSpectator) {
            return;
        }
        RenderData data = state.getRenderData(KEY);
        if (data == null) {
            return;
        }

        float t = data.progress;
        if (data.phase == AtlasPhase.CLOSED && t <= 0.001f) {
            if (data.hasEquippedAtlas && !data.rolledState.isEmpty()) {
                submitBeltScroll(poseStack, collector, packedLight, state, data, 0.0f);
            }
            return;
        }

        if (t < 0.12f) {
            if (!data.rolledState.isEmpty()) {
                submitBeltScroll(poseStack, collector, packedLight, state, data, t);
            }
            return;
        }

        KinematicsResult kin = ensureKinematics(state, data, this.getParentModel().body);

        if (t < 0.28f) {
            submitHandTransferScroll(poseStack, collector, packedLight, data, kin);
            return;
        }

        submitGroundUnrollingAtlas(poseStack, collector, packedLight, state, data, kin);
    }

    private void submitBeltScroll(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            AvatarRenderState state,
            RenderData data,
            float t
    ) {
        boolean isRight = state.mainArm == HumanoidArm.RIGHT;
        boolean hasArmor = !state.chestEquipment.isEmpty() || !state.legsEquipment.isEmpty();
        float walkSwing = computeBeltWalkSwing(state, data, t);
        ModelPart bodyPart = this.getParentModel().body;

        poseStack.pushPose();
        bodyPart.translateAndRotate(poseStack);
        applyBeltLocalTransform(poseStack, isRight, hasArmor, walkSwing);
        data.rolledState.submit(poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private void submitHandTransferScroll(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            RenderData data,
            KinematicsResult kin
    ) {
        if (data.rolledState.isEmpty()) {
            return;
        }
        poseStack.pushPose();
        modelToEntityGround(poseStack);
        poseStack.translate(kin.activeRollPos.x, kin.activeRollPos.y, kin.activeRollPos.z);
        poseStack.rotate(kin.activeRollRot);
        poseStack.scale(kin.activeRollScale.x, kin.activeRollScale.y, kin.activeRollScale.z);
        data.rolledState.submit(poseStack, collector, packedLight, OverlayTexture.NO_OVERLAY, 0);
        poseStack.popPose();
    }

    private void submitGroundUnrollingAtlas(
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            AvatarRenderState state,
            RenderData data,
            KinematicsResult kin
    ) {
        float domSign = state.mainArm == HumanoidArm.RIGHT ? 1.0f : -1.0f;
        float anchorSign = -domSign;
        SheetPose sheet = kin.sheet;
        float localMidX = (anchorSign * 0.68f) * (1.0f - sheet.scaleX);

        poseStack.pushPose();
        modelToEntityGround(poseStack);
        poseStack.translate(0.0f, sheet.mapY, sheet.mapZ);
        poseStack.rotateDegrees(Axis.XP, sheet.mapPitch);
        poseStack.scale(sheet.atlasScale, sheet.atlasScale, sheet.atlasScale);
        poseStack.translate(-localMidX, 0.0f, 0.0f);

        AtlasSurfaceRenderer.submitUnrolledWithMap(
                poseStack,
                collector,
                data.unrolledState,
                data.rolledState,
                packedLight,
                sheet.u,
                anchorSign,
                data.worldX,
                data.worldZ,
                data.playerYaw,
                true,
                data.isLocalPlayer
        );
        poseStack.popPose();
    }
}
