package dev.fricker.lodestore.menu;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;

import dev.fricker.lodestore.Lodestore;

/**
 * Menu registration.
 *
 * <p>An {@link ExtendedMenuType} rather than a plain one: vanilla's open packet carries nothing but
 * the menu type and a title, and a crate's menu cannot be built without knowing its row count and
 * its tier's upgrade slot count. Both are server-side facts.
 */
public final class LodestoreMenus {
	public static final ExtendedMenuType<CrateMenu, CrateMenuData> CRATE =
			new ExtendedMenuType<>(CrateMenu::new, CrateMenuData.STREAM_CODEC);

	private LodestoreMenus() {
	}

	/** Forces class-loading and performs registration. */
	public static void initialize() {
		Registry.register(BuiltInRegistries.MENU, Lodestore.id("crate"), CRATE);
	}
}
