package me.zhdg.billboards.render;

import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import me.zhdg.billboards.Billboards;
import me.zhdg.billboards.model.BlendMode;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pipelines for drawing overlays: textured quads ({@code POSITION_TEX_COLOR}), no face culling (overlays are
 * visible from both sides), and Billboards' own shader, which has no alpha cut-off. Created on first use for each
 * combination of depth testing, shader variant and blend mode.
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

	private record Key(boolean depthTest, Variant variant, BlendMode blend) {
	}

	private static final Map<Key, RenderPipeline> PIPELINES = new HashMap<>();

	private OverlayPipelines() {
	}

	public static RenderPipeline get(boolean depthTest, boolean grayscale) {
		return get(depthTest, grayscale ? Variant.GRAYSCALE : Variant.NORMAL, BlendMode.NORMAL);
	}

	public static RenderPipeline get(boolean depthTest, Variant variant, BlendMode blend) {
		return PIPELINES.computeIfAbsent(new Key(depthTest, variant, blend), OverlayPipelines::create);
	}

	private static RenderPipeline create(Key key) {
		String name = "overlay_" + (key.depthTest() ? "depth" : "no_depth") + "_" + key.variant().name().toLowerCase(Locale.ROOT)
			+ "_" + key.blend().name().toLowerCase(Locale.ROOT);
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Billboards.id("pipeline/" + name))
			.withVertexShader(Billboards.id("core/overlay"))
			.withFragmentShader(Billboards.id("core/overlay"))
			.withCull(false)
			.withDepthStencilState(key.depthTest() ? Optional.of(TEST_NO_WRITE) : Optional.empty())
			.withColorTargetState(new ColorTargetState(blendFunction(key.blend())));
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
