package me.zhdg.billboards.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Collects textured quads in draw order, merging consecutive quads that share pipeline, texture and sampler, then
 * draws them all in one render pass. Each finished batch is uploaded straight away, because the shared
 * {@link ByteBufferBuilder} may reallocate (invalidating earlier meshes) as more vertices are added.
 */
public final class OverlayBatch implements AutoCloseable {

	/** {@code blurU}/{@code blurV}: blur radius in UV units for the BLUR shader variant (0 otherwise). */
	private record Key(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler, float blurU, float blurV) {
	}

	private record Draw(Key key, GpuBuffer vertices, int indexCount, PrimitiveTopology topology) {
	}

	private final ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
	private final List<Draw> draws = new ArrayList<>();
	private @Nullable Key currentKey = null;
	private @Nullable BufferBuilder currentBuilder = null;

	/**
	 * Returns a vertex consumer for quads drawn with this pipeline and texture. Callers write position, UV and
	 * colour; every vertex gets {@code light} (packed block/sky light), which lit pipelines use and others ignore.
	 */
	public VertexConsumer begin(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler, int light) {
		return this.begin(pipeline, texture, sampler, 0, 0, light);
	}

	public VertexConsumer begin(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler, float blurU, float blurV, int light) {
		Key key = new Key(pipeline, texture, sampler, blurU, blurV);
		if (!key.equals(this.currentKey) || this.currentBuilder == null) {
			this.finishCurrent();
			this.currentKey = key;
			this.currentBuilder = new BufferBuilder(this.bytes, PrimitiveTopology.QUADS, pipeline.getVertexFormatBinding(0));
		}
		return new LitVertices(this.currentBuilder, light);
	}

	/** Adds the light level to every vertex (a no-op for vertex formats without one). */
	private record LitVertices(BufferBuilder builder, int light) implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.builder.addVertex(x, y, z);
			this.builder.setLight(this.light);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			this.builder.setColor(r, g, b, a);
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.builder.setColor(color);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.builder.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			this.builder.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.builder.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			this.builder.setUv3(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			this.builder.setNormal(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			this.builder.setLineWidth(width);
			return this;
		}
	}

	private void finishCurrent() {
		if (this.currentBuilder != null && this.currentKey != null) {
			try (MeshData mesh = this.currentBuilder.build()) {
				if (mesh != null && mesh.drawState().indexCount() > 0) {
					GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "billboards overlay",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, mesh.vertexBuffer());
					this.draws.add(new Draw(this.currentKey, vertices, mesh.drawState().indexCount(), mesh.drawState().primitiveTopology()));
				}
			}
		}
		this.currentBuilder = null;
		this.currentKey = null;
	}

	/** Draws everything into the main render target. Vertices must already be in view space (model-view is identity). */
	public void draw(String label) {
		this.finishCurrent();
		if (this.draws.isEmpty()) {
			return;
		}

		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView depthView = target.hasDepth() ? target.getDepthTextureView() : null;
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> label, Objects.requireNonNull(target.getColorTextureView()), Optional.empty(), depthView, OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);

			for (Draw draw : this.draws) {
				RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(draw.topology());
				pass.setPipeline(RenderSystem.getCompiledPipeline(draw.key().pipeline()));
				if (draw.key().blurU() != 0 || draw.key().blurV() != 0) {
					// The blur radius travels in the texture matrix, which the overlay shader doesn't otherwise use.
					Matrix4f radius = new Matrix4f().scaling(draw.key().blurU(), draw.key().blurV(), 1);
					pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), radius));
				} else {
					pass.setUniform("DynamicTransforms", transforms);
				}
				pass.setUniform("Sampler0", draw.key().texture(), draw.key().sampler());
				if (OverlayPipelines.isLit(draw.key().pipeline())) {
					pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(),
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				}
				pass.setVertexBuffer(0, draw.vertices().slice());
				pass.setIndexBuffer(indices.getBuffer(draw.indexCount()), indices.type());
				pass.drawIndexed(draw.indexCount(), 1, 0, 0, 0);
			}
		}
	}

	@Override
	public void close() {
		this.currentBuilder = null;
		this.currentKey = null;
		this.draws.forEach(draw -> draw.vertices().close());
		this.draws.clear();
		this.bytes.close();
	}
}
