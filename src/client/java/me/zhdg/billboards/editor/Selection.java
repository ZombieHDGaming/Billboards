package me.zhdg.billboards.editor;

import me.zhdg.billboards.model.BillboardProject;
import me.zhdg.billboards.model.Element;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** The overlay selected in the Billboards window and the viewport. Render thread only. */
public final class Selection {

	private static @Nullable UUID selected = null;

	private Selection() {
	}

	public static @Nullable UUID id() {
		return selected;
	}

	public static void set(@Nullable UUID id) {
		selected = id;
	}

	public static boolean is(UUID id) {
		return id.equals(selected);
	}

	/** The selected element, or null if nothing is selected or it no longer exists. */
	public static @Nullable Element get(@Nullable BillboardProject project) {
		if (project == null || selected == null) {
			return null;
		}
		return project.get(selected);
	}
}
