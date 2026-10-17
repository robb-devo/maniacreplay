package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

/** Replaces the vanilla pause menu while watching a replay. Pausing the game here does not stop the replay clock. */
public final class ReplayPauseScreen extends Screen {
	private final ReplaySession session;

	public ReplayPauseScreen(ReplaySession session) {
		super(Component.translatable("streamerreplay.pause.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 4 + 32;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.pause.resume"), b -> onClose()).bounds(cx - 102, y, 204, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.pause.editor"), b -> minecraft.gui.setScreen(new ReplayEditorScreen(session)))
			.bounds(cx - 102, y, 204, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.settings"), b -> minecraft.gui.setScreen(new ReplayConfigScreen(this)))
			.bounds(cx - 102, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("menu.options"), b -> minecraft.gui.setScreen(new OptionsScreen(this, minecraft.options)))
			.bounds(cx + 2, y, 100, 20).build());
		y += 36;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.editor.exit"), b -> ReplayManager.exit())
			.bounds(cx - 102, y, 204, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, height / 4 + 8, Theme.TEXT);
		String name = session.file().getFileName().toString();
		g.centeredText(font, Component.literal(name), width / 2, height / 4 + 18, Theme.TEXT_DIM);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
