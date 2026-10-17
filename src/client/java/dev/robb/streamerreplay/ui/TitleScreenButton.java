package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.StreamerReplay;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;

import java.util.List;

/** Adds a small "Replays" icon button to the title screen, next to the accessibility button. */
public final class TitleScreenButton {
	private static final Identifier ICON = StreamerReplay.id("icon/replays");

	private TitleScreenButton() {
	}

	public static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
		if (!(screen instanceof TitleScreen)) {
			return;
		}
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		Component label = Component.translatable("streamerreplay.browser.title");
		SpriteIconButton button = SpriteIconButton.builder(label, b -> client.gui.setScreen(new ReplayBrowserScreen(screen)), true)
			.width(20)
			.sprite(ICON, 15, 15)
			.build();
		button.setTooltip(Tooltip.create(label));
		AbstractWidget anchor = find(widgets, "options.accessibility");
		if (anchor != null) {
			button.setPosition(anchor.getX() + anchor.getWidth() + 4, anchor.getY());
		} else {
			AbstractWidget singleplayer = find(widgets, "menu.singleplayer");
			if (singleplayer != null) {
				button.setPosition(singleplayer.getX() + singleplayer.getWidth() + 4, singleplayer.getY());
			} else {
				button.setPosition(4, 4);
			}
		}
		widgets.add(button);
	}

	private static AbstractWidget find(List<AbstractWidget> widgets, String translationKey) {
		for (AbstractWidget widget : widgets) {
			if (widget.getMessage().getContents() instanceof TranslatableContents contents && contents.getKey().equals(translationKey)) {
				return widget;
			}
		}
		return null;
	}
}
