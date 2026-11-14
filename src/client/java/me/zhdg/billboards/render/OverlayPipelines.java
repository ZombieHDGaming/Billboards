package me.zhdg.billboards.render;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import me.zhdg.billboards.Billboards;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Optional;

/**
 * Pipelines for drawing overlays: textured quads ({@code POSITION_TEX_COLOR}) with translucent blending, no face
 * culling (overlays are visible from both sides), and Billboards' own shader which has no alpha cut-off.
 */
public final class OverlayPipelines {

	/** Depth-tested against the world but doesn't write depth, so overlapping overlays never z-fight. */
	private static final DepthStencilState TEST_NO_WRITE = new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false);

	public static final RenderPipeline DEPTH = create("overlay_depth", true, false);
	public static final RenderPipeline DEPTH_GRAYSCALE = create("overlay_depth_grayscale", true, true);
	public static final RenderPipeline NO_DEPTH = create("overlay_no_depth", false, false);
	public static final RenderPipeline NO_DEPTH_GRAYSCALE = create("overlay_no_depth_grayscale", false, true);

	private OverlayPipelines() {
	}

	public static RenderPipeline get(boolean depthTest, boolean grayscale) {
		if (depthTest) {
			return grayscale ? DEPTH_GRAYSCALE : DEPTH;
		}
		return grayscale ? NO_DEPTH_GRAYSCALE : NO_DEPTH;
	}

	private static RenderPipeline create(String name, boolean depthTest, boolean grayscale) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
			.withLocation(Billboards.id("pipeline/" + name))
			.withVertexShader(Billboards.id("core/overlay"))
			.withFragmentShader(Billboards.id("core/overlay"))
			.withCull(false)
			.withDepthStencilState(depthTest ? Optional.of(TEST_NO_WRITE) : Optional.empty());
		if (grayscale) {
			builder.withShaderDefine("GRAYSCALE");
		}
		return builder.build();
	}
}
