package me.zhdg.billboards.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import me.zhdg.billboards.model.Element;
import me.zhdg.billboards.model.Gradient;
import me.zhdg.billboards.model.ShapeKind;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns shape elements into quads (triangles are emitted as quads with a repeated vertex). Works in the element's
 * local frame: +X right, +Y up, within {@link ElementGeometry.Bounds}.
 */
final class ShapeTessellator {

	private static final int ELLIPSE_SEGMENTS = 64;
	private static final int CORNER_SEGMENTS = 10;

	private ShapeTessellator() {
	}

	/**
	 * @param unit    size multiplier (see {@link ElementGeometry})
	 * @param tint    ARGB multiplied into every colour (the element's appearance: tint and opacity)
	 */
	static void draw(VertexConsumer buffer, Matrix4f pose, Element element, ElementGeometry.Bounds bounds, float unit, int tint) {
		float stroke = Math.max(0, element.strokeWidth * unit);
		switch (element.shapeKind) {
			case LINE -> drawLine(buffer, pose, element, bounds, stroke, tint);
			case ARROW -> drawArrow(buffer, pose, element, bounds, stroke, tint);
			default -> {
				List<float[]> outer = contour(element.shapeKind, bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY(), element.cornerRadius * unit);
				fill(buffer, pose, element, bounds, outer, tint);
				float width = bounds.maxX() - bounds.minX();
				float height = bounds.maxY() - bounds.minY();
				if (stroke > 0 && stroke * 2 < Math.min(width, height)) {
					List<float[]> inner = contour(element.shapeKind, bounds.minX() + stroke, bounds.minY() + stroke,
						bounds.maxX() - stroke, bounds.maxY() - stroke, Math.max(0, element.cornerRadius * unit - stroke));
					ring(buffer, pose, outer, inner, multiply(element.strokeColor, tint));
				} else if (stroke > 0) {
					fillSolid(buffer, pose, outer, multiply(element.strokeColor, tint));
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------------

	/** Points around a rectangle, rounded rectangle or ellipse, counter-clockwise, with matching point counts for rings. */
	private static List<float[]> contour(ShapeKind kind, float minX, float minY, float maxX, float maxY, float radius) {
		List<float[]> points = new ArrayList<>();
		float cx = (minX + maxX) / 2;
		float cy = (minY + maxY) / 2;
		float hw = (maxX - minX) / 2;
		float hh = (maxY - minY) / 2;
		if (kind == ShapeKind.ELLIPSE) {
			for (int i = 0; i < ELLIPSE_SEGMENTS; i++) {
				double angle = 2 * Math.PI * i / ELLIPSE_SEGMENTS;
				points.add(new float[]{cx + hw * (float) Math.cos(angle), cy + hh * (float) Math.sin(angle)});
			}
			return points;
		}
		float r = kind == ShapeKind.ROUNDED_RECTANGLE ? Math.max(0, Math.min(radius, Math.min(hw, hh))) : 0;
		// Corner centres, counter-clockwise from bottom-right; each corner sweeps 90°.
		float[][] corners = {{maxX - r, minY + r}, {maxX - r, maxY - r}, {minX + r, maxY - r}, {minX + r, minY + r}};
		double[] startAngles = {-Math.PI / 2, 0, Math.PI / 2, Math.PI};
		int segments = r > 0 ? CORNER_SEGMENTS : 0;
		for (int corner = 0; corner < 4; corner++) {
			for (int i = 0; i <= segments; i++) {
				double angle = startAngles[corner] + (Math.PI / 2) * (segments == 0 ? 0 : i / (double) segments);
				points.add(new float[]{corners[corner][0] + r * (float) Math.cos(angle), corners[corner][1] + r * (float) Math.sin(angle)});
			}
		}
		return points;
	}

	private static void fill(VertexConsumer buffer, Matrix4f pose, Element element, ElementGeometry.Bounds bounds, List<float[]> contour, int tint) {
		float cx = (bounds.minX() + bounds.maxX()) / 2;
		float cy = (bounds.minY() + bounds.maxY()) / 2;
		int centre = multiply(fillColor(element, bounds, cx, cy), tint);
		for (int i = 0; i < contour.size(); i++) {
			float[] a = contour.get(i);
			float[] b = contour.get((i + 1) % contour.size());
			int colourA = multiply(fillColor(element, bounds, a[0], a[1]), tint);
			int colourB = multiply(fillColor(element, bounds, b[0], b[1]), tint);
			vertex(buffer, pose, cx, cy, centre);
			vertex(buffer, pose, a[0], a[1], colourA);
			vertex(buffer, pose, b[0], b[1], colourB);
			vertex(buffer, pose, b[0], b[1], colourB);
		}
	}

	private static void fillSolid(VertexConsumer buffer, Matrix4f pose, List<float[]> contour, int colour) {
		float cx = 0;
		float cy = 0;
		for (float[] p : contour) {
			cx += p[0];
			cy += p[1];
		}
		cx /= contour.size();
		cy /= contour.size();
		for (int i = 0; i < contour.size(); i++) {
			float[] a = contour.get(i);
			float[] b = contour.get((i + 1) % contour.size());
			vertex(buffer, pose, cx, cy, colour);
			vertex(buffer, pose, a[0], a[1], colour);
			vertex(buffer, pose, b[0], b[1], colour);
			vertex(buffer, pose, b[0], b[1], colour);
		}
	}

	private static void ring(VertexConsumer buffer, Matrix4f pose, List<float[]> outer, List<float[]> inner, int colour) {
		int count = Math.min(outer.size(), inner.size());
		for (int i = 0; i < count; i++) {
			int next = (i + 1) % count;
			vertex(buffer, pose, outer.get(i)[0], outer.get(i)[1], colour);
			vertex(buffer, pose, outer.get(next)[0], outer.get(next)[1], colour);
			vertex(buffer, pose, inner.get(next)[0], inner.get(next)[1], colour);
			vertex(buffer, pose, inner.get(i)[0], inner.get(i)[1], colour);
		}
	}

	private static void drawLine(VertexConsumer buffer, Matrix4f pose, Element element, ElementGeometry.Bounds bounds, float stroke, int tint) {
		float cy = (bounds.minY() + bounds.maxY()) / 2;
		float half = (stroke > 0 ? stroke : bounds.maxY() - bounds.minY()) / 2;
		quad(buffer, pose, element, bounds, bounds.minX(), cy - half, bounds.maxX(), cy + half, tint);
	}

	private static void drawArrow(VertexConsumer buffer, Matrix4f pose, Element element, ElementGeometry.Bounds bounds, float stroke, int tint) {
		float height = bounds.maxY() - bounds.minY();
		float width = bounds.maxX() - bounds.minX();
		float cy = (bounds.minY() + bounds.maxY()) / 2;
		float shaftHalf = (stroke > 0 ? stroke : height / 3) / 2;
		float headLength = Math.min(width * 0.5f, height * 0.9f);
		float headStart = bounds.maxX() - headLength;
		quad(buffer, pose, element, bounds, bounds.minX(), cy - shaftHalf, headStart, cy + shaftHalf, tint);
		int top = multiply(fillColor(element, bounds, headStart, bounds.maxY()), tint);
		int bottom = multiply(fillColor(element, bounds, headStart, bounds.minY()), tint);
		int tip = multiply(fillColor(element, bounds, bounds.maxX(), cy), tint);
		vertex(buffer, pose, headStart, bounds.minY(), bottom);
		vertex(buffer, pose, bounds.maxX(), cy, tip);
		vertex(buffer, pose, bounds.maxX(), cy, tip);
		vertex(buffer, pose, headStart, bounds.maxY(), top);
	}

	private static void quad(VertexConsumer buffer, Matrix4f pose, Element element, ElementGeometry.Bounds bounds,
	                         float x0, float y0, float x1, float y1, int tint) {
		vertex(buffer, pose, x0, y1, multiply(fillColor(element, bounds, x0, y1), tint));
		vertex(buffer, pose, x0, y0, multiply(fillColor(element, bounds, x0, y0), tint));
		vertex(buffer, pose, x1, y0, multiply(fillColor(element, bounds, x1, y0), tint));
		vertex(buffer, pose, x1, y1, multiply(fillColor(element, bounds, x1, y1), tint));
	}

	private static void vertex(VertexConsumer buffer, Matrix4f pose, float x, float y, int colour) {
		buffer.addVertex(pose, x, y, 0).setUv(0.5f, 0.5f).setColor(colour);
	}

	/** The fill colour at a local point, following the gradient. */
	static int fillColor(Element element, ElementGeometry.Bounds bounds, float x, float y) {
		float t = switch (element.gradient) {
			case NONE -> 0;
			case HORIZONTAL -> (x - bounds.minX()) / Math.max(1e-6f, bounds.maxX() - bounds.minX());
			case VERTICAL -> (bounds.maxY() - y) / Math.max(1e-6f, bounds.maxY() - bounds.minY());
		};
		return element.gradient == Gradient.NONE ? element.fillColor : lerpColor(element.fillColor, element.fillColor2, Math.max(0, Math.min(1, t)));
	}

	static int lerpColor(int a, int b, float t) {
		int alpha = Math.round(((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * t);
		int red = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
		int green = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
		int blue = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	/** Component-wise multiply of two ARGB colours. */
	static int multiply(int a, int b) {
		int alpha = ((a >>> 24) & 0xFF) * ((b >>> 24) & 0xFF) / 255;
		int red = ((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255;
		int green = ((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255;
		int blue = (a & 0xFF) * (b & 0xFF) / 255;
		return alpha << 24 | red << 16 | green << 8 | blue;
	}
}
