package com.cartographica_unrolled.client.render;

import com.cartographica_unrolled.CartographicaUnrolled;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AtlasRenderPipelines {
    private static final RenderPipeline.Snippet ATLAS_OIT_BASE_SNIPPET = RenderPipeline.builder()
            .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
            .withBindGroupLayout(BindGroupLayouts.FOG)
            .withBindGroupLayout(BindGroupLayouts.LIGHTING)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
            .withVertexShader("core/entity")
            .withFragmentShader("core/entity")
            .withVertexBinding(0, DefaultVertexFormat.ENTITY)
            .withPrimitiveTopology(PrimitiveTopology.QUADS)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withShaderDefine("NO_OVERLAY")
            .withCull(false)
            .buildSnippet();

    private static final RenderPipeline ATLAS_MAP_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "pipeline/atlas_map"))
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withShaderDefine("NO_OVERLAY")
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static final OitPipelineSet OIT_ATLAS_MAP_PIPELINE = OitPipelineSet.builder(
                    Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "atlas_map"),
                    RenderPipeline.builder(ATLAS_OIT_BASE_SNIPPET))
            .withAccumulateModifier(accumulate -> accumulate.withBindGroupLayout(BindGroupLayouts.SAMPLER2))
            .build();

    private static final RenderPipeline ATLAS_LIGHT_PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_NO_LIGHTMAP_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "pipeline/atlas_light"))
            .withShaderDefine("EMISSIVE")
            .withShaderDefine("NO_CARDINAL_LIGHTING")
            .withShaderDefine("NO_OVERLAY")
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .withCull(false)
            .build();

    private static final OitPipelineSet OIT_ATLAS_LIGHT_PIPELINE = OitPipelineSet.builder(
                    Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "atlas_light"),
                    RenderPipeline.builder(ATLAS_OIT_BASE_SNIPPET).withShaderDefine("EMISSIVE").withShaderDefine("OIT_ADDITIVE"))
            .build();

    public static final RenderPipeline GUI_LIGHT_PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath(CartographicaUnrolled.MOD_ID, "pipeline/gui_atlas_light"))
            .withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))
            .build();

    private static final Map<Identifier, RenderType> MAP_RENDER_TYPES = new ConcurrentHashMap<>();
    private static final Map<Identifier, RenderType> LIGHT_RENDER_TYPES = new ConcurrentHashMap<>();

    private AtlasRenderPipelines() {
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(ATLAS_MAP_PIPELINE);
        event.registerOitPipelineSet(OIT_ATLAS_MAP_PIPELINE);
        event.registerPipeline(ATLAS_LIGHT_PIPELINE);
        event.registerOitPipelineSet(OIT_ATLAS_LIGHT_PIPELINE);
        event.registerPipeline(GUI_LIGHT_PIPELINE);
    }

    public static void clearRenderTypeCache() {
        MAP_RENDER_TYPES.clear();
        LIGHT_RENDER_TYPES.clear();
    }

    public static RenderType atlasMapLayer(Identifier texture) {
        return MAP_RENDER_TYPES.computeIfAbsent(texture, id -> RenderType.create(
                "cartographica_atlas_map",
                RenderSetup.builder(ATLAS_MAP_PIPELINE)
                        .setOitPipelines(OIT_ATLAS_MAP_PIPELINE)
                        .withTexture("Sampler0", id)
                        .useLightmap()
                        .createRenderSetup()
        ));
    }

    public static RenderType atlasLightLayer(Identifier texture) {
        return LIGHT_RENDER_TYPES.computeIfAbsent(texture, id -> RenderType.create(
                "cartographica_atlas_light",
                RenderSetup.builder(ATLAS_LIGHT_PIPELINE)
                        .setOitPipelines(OIT_ATLAS_LIGHT_PIPELINE)
                        .withTexture("Sampler0", id)
                        .createRenderSetup()
        ));
    }
}
