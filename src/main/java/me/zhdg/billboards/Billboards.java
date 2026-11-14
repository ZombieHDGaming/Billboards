package me.zhdg.billboards;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants. Billboards is client-only; all behaviour lives in the client source set.
 */
public final class Billboards {
	public static final String MOD_ID = "billboards";
	public static final Logger LOGGER = LoggerFactory.getLogger("Billboards");

	private Billboards() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
