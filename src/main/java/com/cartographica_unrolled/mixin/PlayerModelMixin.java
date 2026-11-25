package com.cartographica_unrolled.mixin;

import com.cartographica_unrolled.client.render.AtlasPlayerLayer;
import com.cartographica_unrolled.data.AtlasPhase;
import com.cartographica_unrolled.util.AtlasMath;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin extends HumanoidModel<AvatarRenderState> {
    @Shadow
    @Final
    private boolean slim;

    @Unique
    private final Vector3f cartographica_unrolled$targetModel = new Vector3f();

    @Unique
    private final Vector3f cartographica_unrolled$palmCheck = new Vector3f();

    @Unique
    private final Vector3f cartographica_unrolled$ikAngles = new Vector3f();

    @Unique
    private final Quaternionf cartographica_unrolled$qArm = new Quaternionf();

    @Unique
    private final Quaternionf cartographica_unrolled$qPitch = new Quaternionf();

    protected PlayerModelMixin(ModelPart root, Function<Identifier, RenderType> renderType) {
        super(root, renderType);
    }

    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
    private void cartographica_unrolled$animateAtlasPose(AvatarRenderState state, CallbackInfo ci) {
        if ((Object) this instanceof PlayerCapeModel) {
            return;
        }

        AtlasPlayerLayer.RenderData data = state.getRenderData(AtlasPlayerLayer.KEY);
        if (data == null || (data.phase == AtlasPhase.CLOSED && data.progress <= 0.001f)) {
            return;
        }

        float t = data.progress;
        boolean isRight = state.mainArm == HumanoidArm.RIGHT;
        ModelPart domArm = isRight ? this.rightArm : this.leftArm;
        ModelPart offArm = isRight ? this.leftArm : this.rightArm;

        AtlasPlayerLayer.KinematicsResult kin = AtlasPlayerLayer.ensureKinematics(state, data, this.body);

        float domPalmLocalX = (isRight ? -1.0f : 1.0f) * (this.slim ? 0.03125f : 0.0625f);
        float offPalmLocalX = -domPalmLocalX;
        float domPalmLocalY = 0.60f;

        cartographica_unrolled$solveArmIk(
                domArm,
                kin.domGripG,
                domPalmLocalX,
                domPalmLocalY,
                this.cartographica_unrolled$ikAngles
        );
        float wDom = t < 0.12f ? AtlasMath.smoothstep(t / 0.12f) : 1.0f;
        domArm.xRot = cartographica_unrolled$lerpAngleRad(wDom, domArm.xRot, this.cartographica_unrolled$ikAngles.x);
        domArm.yRot = cartographica_unrolled$lerpAngleRad(wDom, domArm.yRot, this.cartographica_unrolled$ikAngles.y);
        domArm.zRot = cartographica_unrolled$lerpAngleRad(wDom, domArm.zRot, this.cartographica_unrolled$ikAngles.z);

        if (kin.hasOffGrip && t >= 0.12f) {
            cartographica_unrolled$solveArmIk(
                    offArm,
                    kin.offGripG,
                    offPalmLocalX,
                    0.60f,
                    this.cartographica_unrolled$ikAngles
            );
            float wOff = t < 0.24f ? AtlasMath.smoothstep((t - 0.12f) / 0.12f) : 1.0f;
            offArm.xRot = cartographica_unrolled$lerpAngleRad(wOff, offArm.xRot, this.cartographica_unrolled$ikAngles.x);
            offArm.yRot = cartographica_unrolled$lerpAngleRad(wOff, offArm.yRot, this.cartographica_unrolled$ikAngles.y);
            offArm.zRot = cartographica_unrolled$lerpAngleRad(wOff, offArm.zRot, this.cartographica_unrolled$ikAngles.z);
        }

        float headEnter = AtlasMath.smoothstep(Mth.clamp(t / 0.14f, 0.0f, 1.0f));
        this.head.xRot = cartographica_unrolled$lerpAngleRad(headEnter, this.head.xRot, 0.13f);
        this.head.yRot = cartographica_unrolled$lerpAngleRad(headEnter, this.head.yRot, 0.0f);
    }

    @Unique
    private static float cartographica_unrolled$lerpAngleRad(float t, float startRad, float endRad) {
        float diffDeg = Mth.wrapDegrees((endRad - startRad) * Mth.RAD_TO_DEG);
        return startRad + t * diffDeg * Mth.DEG_TO_RAD;
    }

    @Unique
    private void cartographica_unrolled$anatomicalArmQuat(float dx, float dy, float dz, Quaternionf outQuat) {
        float rYz = Mth.sqrt(dy * dy + dz * dz);
        if (rYz < 1.0e-5f) {
            float ang = dx > 0.0f ? -((float) Math.PI * 0.5f) : ((float) Math.PI * 0.5f);
            outQuat.rotationZ(ang);
            return;
        }
        float pitch = (float) Mth.atan2(dz, dy);
        this.cartographica_unrolled$qPitch.rotationX(pitch);
        float my = dy / rYz;
        float mz = dz / rYz;
        float cy = mz * dx;
        float cz = -my * dx;
        float cNorm = Mth.sqrt(cy * cy + cz * cz);
        if (cNorm < 1.0e-6f) {
            outQuat.set(this.cartographica_unrolled$qPitch);
            return;
        }
        float latAng = (float) Mth.atan2(cNorm, rYz);
        outQuat.rotationAxis(latAng, 0.0f, cy / cNorm, cz / cNorm);
        outQuat.mul(this.cartographica_unrolled$qPitch);
    }

    @Unique
    private void cartographica_unrolled$solveArmIk(
            ModelPart arm,
            Vector3f targetGround,
            float palmLocalX,
            float palmLocalY,
            Vector3f outAngles
    ) {
        AtlasPlayerLayer.transformGroundToModel(targetGround, this.cartographica_unrolled$targetModel);
        float pivX = arm.x / 16.0f;
        float pivY = arm.y / 16.0f;
        float pivZ = arm.z / 16.0f;

        float vx = this.cartographica_unrolled$targetModel.x - pivX;
        float vy = this.cartographica_unrolled$targetModel.y - pivY;
        float vz = this.cartographica_unrolled$targetModel.z - pivZ;
        float dist = Mth.sqrt(vx * vx + vy * vy + vz * vz);
        if (dist < 1.0e-5f) {
            outAngles.set(0.0f, 0.0f, 0.0f);
            return;
        }

        float palmRadius = Mth.sqrt(palmLocalX * palmLocalX + palmLocalY * palmLocalY);
        float invScale = palmRadius / dist;
        float tx = vx * invScale;
        float ty = vy * invScale;
        float tz = vz * invScale;

        float aimX = tx - palmLocalX;
        float aimY = ty;
        float aimZ = tz;
        this.cartographica_unrolled$qArm.identity();

        for (int i = 0; i < 4; i++) {
            float aimDist = Mth.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
            if (aimDist < 1.0e-5f) {
                break;
            }
            cartographica_unrolled$anatomicalArmQuat(
                    aimX / aimDist,
                    aimY / aimDist,
                    aimZ / aimDist,
                    this.cartographica_unrolled$qArm
            );
            this.cartographica_unrolled$palmCheck.set(palmLocalX, palmLocalY, 0.0f);
            this.cartographica_unrolled$qArm.transform(this.cartographica_unrolled$palmCheck);
            aimX += tx - this.cartographica_unrolled$palmCheck.x;
            aimY += ty - this.cartographica_unrolled$palmCheck.y;
            aimZ += tz - this.cartographica_unrolled$palmCheck.z;
        }

        float qx = this.cartographica_unrolled$qArm.x;
        float qy = this.cartographica_unrolled$qArm.y;
        float qz = this.cartographica_unrolled$qArm.z;
        float qw = this.cartographica_unrolled$qArm.w;

        float r00 = 1.0f - 2.0f * (qy * qy + qz * qz);
        float r10 = 2.0f * (qx * qy + qz * qw);
        float r20 = 2.0f * (qx * qz - qy * qw);
        float r21 = 2.0f * (qy * qz + qx * qw);
        float r22 = 1.0f - 2.0f * (qx * qx + qy * qy);

        float yRot = (float) Math.asin(Mth.clamp(-r20, -1.0f, 1.0f));
        float xRot = (float) Mth.atan2(r21, r22);
        float zRot = (float) Mth.atan2(r10, r00);
        outAngles.set(xRot, yRot, zRot);
    }
}
