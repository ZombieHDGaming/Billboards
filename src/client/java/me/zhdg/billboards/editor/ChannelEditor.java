package me.zhdg.billboards.editor;

import com.moulberry.flashback.editor.ui.ImGuiHelper;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImDouble;
import imgui.moulberry90.type.ImString;
import me.zhdg.billboards.Lang;
import me.zhdg.billboards.model.Channel;
import me.zhdg.billboards.model.ChannelValues;

import java.util.function.Consumer;

/**
 * ImGui fields for every value of a channel. Shared by the Billboards inspector and Flashback's
 * "edit keyframe" popup so both look and behave the same.
 */
public final class ChannelEditor {

	public interface ComponentSetter {
		void set(int component, double value);
	}

	private static final ImDouble DOUBLE = new ImDouble();
	private static ImString textBuffer = ImGuiHelper.createResizableImString("");

	private ChannelEditor() {
	}

	/** Draws the fields; calls {@code set}/{@code setText} for each edited value. Returns true if anything changed. */
	public static boolean render(ChannelValues values, ComponentSetter set, Consumer<String> setText) {
		return switch (values.channel) {
			case TRANSFORM -> transform(values, set);
			case APPEARANCE -> appearance(values, set);
			case CONTENT -> content(values, set, setText);
		};
	}

	private static boolean transform(ChannelValues values, ComponentSetter set) {
		boolean changed = false;
		ImGui.pushItemWidth(220);
		String[] labels = {"X##pos_x", "Y##pos_y", "Z##pos_z"};
		for (int i = 0; i < 3; i++) {
			DOUBLE.set(values.get(Channel.X + i));
			if (ImGui.inputDouble(labels[i], DOUBLE, 0.1, 1.0, "%.3f")) {
				set.set(Channel.X + i, DOUBLE.get());
				changed = true;
			}
		}
		float[] rotation = {values.getFloat(Channel.PITCH), values.getFloat(Channel.YAW), values.getFloat(Channel.ROLL)};
		if (ImGui.dragFloat3(Lang.tr("Rotation (pitch, yaw, roll)##rotation"), rotation, 0.5f, -100000f, 100000f, "%.1f°")) {
			set.set(Channel.PITCH, rotation[0]);
			set.set(Channel.YAW, rotation[1]);
			set.set(Channel.ROLL, rotation[2]);
			changed = true;
		}
		float[] scale = {values.getFloat(Channel.SCALE_X), values.getFloat(Channel.SCALE_Y)};
		if (ImGui.dragFloat2(Lang.tr("Scale (x, y)##scale"), scale, 0.01f, -100f, 100f, "%.3f")) {
			set.set(Channel.SCALE_X, scale[0]);
			set.set(Channel.SCALE_Y, scale[1]);
			changed = true;
		}
		ImGui.popItemWidth();
		return changed;
	}

	private static boolean appearance(ChannelValues values, ComponentSetter set) {
		boolean changed = false;
		ImGui.pushItemWidth(220);
		if (ImGui.checkbox(Lang.tr("Visible##visible"), values.get(Channel.VISIBLE) >= 0.5)) {
			set.set(Channel.VISIBLE, values.get(Channel.VISIBLE) >= 0.5 ? 0 : 1);
			changed = true;
		}
		float[] opacity = {values.getFloat(Channel.OPACITY)};
		if (ImGui.sliderFloat(Lang.tr("Opacity##opacity"), opacity, 0f, 1f, "%.2f")) {
			set.set(Channel.OPACITY, opacity[0]);
			changed = true;
		}
		float[] color = {values.getFloat(Channel.RED), values.getFloat(Channel.GREEN), values.getFloat(Channel.BLUE)};
		if (ImGui.colorEdit3(Lang.tr("Tint / colour##tint"), color)) {
			set.set(Channel.RED, color[0]);
			set.set(Channel.GREEN, color[1]);
			set.set(Channel.BLUE, color[2]);
			changed = true;
		}
		ImGui.popItemWidth();
		return changed;
	}

	private static boolean content(ChannelValues values, ComponentSetter set, Consumer<String> setText) {
		boolean changed = false;
		ImGui.pushItemWidth(220);

		// Callers pass fresh copies every frame, so key the edit buffer on the text itself: it is only rebuilt when the
		// text changes from outside (another element selected, undo, keyframe change), never while typing.
		String text = values.text == null ? "" : values.text;
		if (!text.equals(textBuffer.get())) {
			textBuffer = ImGuiHelper.createResizableImString(text);
		}
		if (ImGui.inputTextMultiline(Lang.tr("Text##text"), textBuffer, 220, 70)) {
			setText.accept(ImGuiHelper.getString(textBuffer));
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Text can be styled with MiniMessage tags (<gradient:#ff8800:#ff0044>Hi</gradient>, <rainbow>, <bold>, <#33ccff>), & formatting codes (&6 gold, &l bold, &r reset), or a JSON text component. Long lines wrap at the line width."));

		float[] reveal = {values.getFloat(Channel.REVEAL)};
		if (ImGui.sliderFloat(Lang.tr("Reveal##reveal"), reveal, 0f, 1f, "%.2f")) {
			set.set(Channel.REVEAL, reveal[0]);
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Fraction of the text shown, for typewriter-style reveals. Images ignore this."));

		float[] cropMin = {values.getFloat(Channel.CROP_LEFT), values.getFloat(Channel.CROP_TOP)};
		if (ImGui.dragFloat2(Lang.tr("Crop left, top##crop_min"), cropMin, 0.002f, 0f, 1f, "%.3f")) {
			set.set(Channel.CROP_LEFT, cropMin[0]);
			set.set(Channel.CROP_TOP, cropMin[1]);
			changed = true;
		}
		float[] cropMax = {values.getFloat(Channel.CROP_RIGHT), values.getFloat(Channel.CROP_BOTTOM)};
		if (ImGui.dragFloat2(Lang.tr("Crop right, bottom##crop_max"), cropMax, 0.002f, 0f, 1f, "%.3f")) {
			set.set(Channel.CROP_RIGHT, cropMax[0]);
			set.set(Channel.CROP_BOTTOM, cropMax[1]);
			changed = true;
		}
		ImGuiHelper.tooltip(Lang.tr("Crop is in 0-1 of the image. Text ignores this."));
		ImGui.popItemWidth();
		return changed;
	}
}
