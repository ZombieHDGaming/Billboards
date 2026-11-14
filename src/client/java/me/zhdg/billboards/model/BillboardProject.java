package me.zhdg.billboards.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything Billboards stores for one replay, apart from keyframes (which Flashback stores in its editor state).
 */
public class BillboardProject {

	public static final int CURRENT_VERSION = 1;

	public int version = CURRENT_VERSION;
	/** Copied-in asset files: asset id (file name in the assets folder) to the original file name. */
	public Map<String, String> assets = new LinkedHashMap<>();
	public List<Element> elements = new ArrayList<>();

	/** Bumped on every change so caches and the save loop can notice edits. Not saved. */
	public transient int modCount = 0;

	public @Nullable Element get(UUID id) {
		for (Element element : this.elements) {
			if (element.id.equals(id)) {
				return element;
			}
		}
		return null;
	}

	/** Elements directly inside {@code groupId} (null for the top level), in list order. */
	public List<Element> children(@Nullable UUID groupId) {
		List<Element> result = new ArrayList<>();
		for (Element element : this.elements) {
			if (java.util.Objects.equals(element.parentId, groupId)) {
				result.add(element);
			}
		}
		return result;
	}

	/** Whether {@code ancestorId} is {@code element} itself or one of the groups it is (indirectly) in. */
	public boolean isWithin(Element element, UUID ancestorId) {
		Element current = element;
		for (int depth = 0; current != null && depth < 64; depth++) {
			if (current.id.equals(ancestorId)) {
				return true;
			}
			current = current.parentId == null ? null : this.get(current.parentId);
		}
		return false;
	}

	public void markDirty() {
		this.modCount++;
	}

	public void validate() {
		if (this.assets == null) this.assets = new LinkedHashMap<>();
		if (this.elements == null) this.elements = new ArrayList<>();
		this.elements.removeIf(e -> e == null);
		this.elements.forEach(Element::validate);
		// Drop references to groups that no longer exist (or aren't groups, or would form a loop).
		for (Element element : this.elements) {
			if (element.parentId != null) {
				Element parent = this.get(element.parentId);
				if (parent == null || parent.type != ElementType.GROUP || this.isWithin(parent, element.id)) {
					element.parentId = null;
				}
			}
		}
	}
}
