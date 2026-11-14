package me.zhdg.billboards.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
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

	private record Key(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler) {
	}

	private record Draw(Key key, GpuBuffer vertices, int indexCount, PrimitiveTopology topology) {
	}

	private final ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
	private final List<Draw> draws = new ArrayList<>();
	private @Nullable Key currentKey = null;
	private @Nullable BufferBuilder currentBuilder = null;

	/** Returns a vertex consumer for quads drawn with this pipeline and texture; vertices are POSITION_TEX_COLOR. */
	public BufferBuilder begin(RenderPipeline pipeline, GpuTextureView texture, GpuSampler sampler) {
		Key key = new Key(pipeline, texture, sampler);
		if (!key.equals(this.currentKey) || this.currentBuilder == null) {
			this.finishCurrent();
			this.currentKey = key;
			this.currentBuilder = new BufferBuilder(this.bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		}
		return this.currentBuilder;
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
	public void draw(String label, boolean useDepthBuffer) {
		this.finishCurrent();
		if (this.draws.isEmpty()) {
			return;
		}

		RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView depthView = useDepthBuffer && target.hasDepth() ? target.getDepthTextureView() : null;
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f());

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
			() -> label, Objects.requireNonNull(target.getColorTextureView()), Optional.empty(), depthView, OptionalDouble.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);

			for (Draw draw : this.draws) {
				RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(draw.topology());
				pass.setPipeline(RenderSystem.getCompiledPipeline(draw.key().pipeline()));
				pass.setUniform("Sampler0", draw.key().texture(), draw.key().sampler());
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
