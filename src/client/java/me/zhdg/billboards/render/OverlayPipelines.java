package me.zhdg.billboards.render;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.model.BlendMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pipelines for drawing overlays: textured quads ({@code POSITION_TEX_COLOR}, plus the light level for lit
 * pipelines), no face culling (overlays are visible from both sides), and Billboards' own shader, which has no alpha
 * cut-off. Created on first use for each combination of depth testing, lighting, shader variant and blend mode.
 */
public final class OverlayPipelines {

	public enum Variant {
		NORMAL(),
		GRAYSCALE("GRAYSCALE"),
		SILHOUETTE("SILHOUETTE"),
		SILHOUETTE_GRAYSCALE("SILHOUETTE", "GRAYSCALE"),
		BLUR("BLUR");

		private final String[] defines;

		Variant(String... defines) {
			this.defines = defines;
		}

		public static Variant of(boolean silhouette, boolean grayscale) {
			if (silhouette) {
				return grayscale ? SILHOUETTE_GRAYSCALE : SILHOUETTE;
			}
			return grayscale ? GRAYSCALE : NORMAL;
		}
	}

	/** Depth-tested against the world but doesn't write depth, so overlapping overlays never z-fight. */
	private static final DepthStencilState TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

	private record Key(boolean depthTest, boolean lit, Variant variant, BlendMode blend) {
	}

	private static final Map<Key, RenderPipeline> PIPELINES = new HashMap<>();

	private OverlayPipelines() {
	}

	/**
	 * @param lit multiply the colour by the lightmap at each vertex's light level, like vanilla text displays
	 */
	public static RenderPipeline get(boolean depthTest, boolean lit, Variant variant, BlendMode blend) {
		return PIPELINES.computeIfAbsent(new Key(depthTest, lit, variant, blend), OverlayPipelines::create);
	}

	/** Whether a pipeline from {@link #get} samples the lightmap (bound as {@code Sampler2}). */
	public static boolean isLit(RenderPipeline pipeline) {
		return pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR;
	}

	private static RenderPipeline create(Key key) {
		String name = "overlay_" + (key.depthTest() ? "depth" : "no_depth") + (key.lit() ? "_lit" : "") + "_" + key.variant().name().toLowerCase(Locale.ROOT)
			+ "_" + key.blend().name().toLowerCase(Locale.ROOT);
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Billboards.id("pipeline/" + name))
			.withVertexShader(Billboards.id("core/overlay"))
			.withFragmentShader(Billboards.id("core/overlay"))
			.withCull(false)
			.withDepthStencilState(key.depthTest() ? Optional.of(TEST_NO_WRITE) : Optional.empty())
			.withColorTargetState(new ColorTargetState(blendFunction(key.blend())));
		if (key.lit()) {
			builder.withBindGroupLayout(BindGroupLayouts.SAMPLER2)
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR)
				.withShaderDefine("LIT");
		}
		for (String define : key.variant().defines) {
			builder.withShaderDefine(define);
		}
		if (key.blend() != BlendMode.NORMAL) {
			builder.withShaderDefine("PREMULTIPLY");
		}
		return builder.build();
	}

	/**
	 * Normal blending uses straight alpha; the others get premultiplied colour (src' = src * a) from the shader and
	 * leave the destination alpha alone:
	 * <ul>
	 *     <li>additive: dst + src'</li>
	 *     <li>multiply: dst * (1 - a + src * a) = src' * dst + dst * (1 - a)</li>
	 *     <li>screen: src' + dst * (1 - src')</li>
	 * </ul>
	 */
	private static BlendFunction blendFunction(BlendMode blend) {
		return switch (blend) {
			case NORMAL -> BlendFunction.TRANSLUCENT;
			case ADDITIVE -> new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE);
			case MULTIPLY -> new BlendFunction(BlendFactor.DST_COLOR, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE);
			case SCREEN -> new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE);
		};
	}
}
