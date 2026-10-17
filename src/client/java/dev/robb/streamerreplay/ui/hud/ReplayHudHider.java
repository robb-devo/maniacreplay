package dev.robb.streamerreplay.ui.hud;

import dev.robb.streamerreplay.replay.ReplayManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Hides the camera player's own HUD (hotbar, crosshair, health...) while watching a replay. Chat, titles,
 * boss bars, scoreboard and the tab list stay, because they show what happened in the recording.
 * Wraps the vanilla elements instead of removing them, so other mods' replacements keep working.
 */
public final class ReplayHudHider {
	private static final List<Identifier> HIDDEN = List.of(
		VanillaHudElements.CROSSHAIR, VanillaHudElements.HOTBAR, VanillaHudElements.SPECTATOR_MENU,
		VanillaHudElements.SPECTATOR_TOOLTIP, VanillaHudElements.HELD_ITEM_TOOLTIP, VanillaHudElements.ARMOR_BAR,
		VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR,
		VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL,
		VanillaHudElements.MOB_EFFECTS, VanillaHudElements.SLEEP, VanillaHudElements.DEMO_TIMER);

	private ReplayHudHider() {
	}

	public static void register() {
		for (Identifier id : HIDDEN) {
			HudElementRegistry.replaceElement(id, original -> (HudElement) (graphics, deltaTracker) -> {
				if (!ReplayManager.isActive()) {
					original.extractRenderState(graphics, deltaTracker);
				}
			});
		}
	}
}
