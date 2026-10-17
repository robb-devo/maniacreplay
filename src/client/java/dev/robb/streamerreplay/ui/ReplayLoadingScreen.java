package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Shown while the replay connection is in login/configuration, i.e. before vanilla's level loading screen. */
public final class ReplayLoadingScreen extends Screen {
	private final ReplaySession session;

	public ReplayLoadingScreen(ReplaySession session) {
		super(Component.translatable("streamerreplay.loading.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> ReplayManager.exit())
			.bounds(width / 2 - 75, height / 2 + 30, 150, 20).build());
	}

	@Override
	public void tick() {
		// Nobody else ticks a connection that is still in login/configuration.
		if (minecraft.level == null && session.channel() != null && session.channel().isOpen()) {
			session.channel().connection().tick();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, height / 2 - 30, Theme.TEXT);
		g.centeredText(font, Component.literal(session.meta().worldName == null ? "" : session.meta().worldName), width / 2, height / 2 - 16, Theme.TEXT_DIM);
		long dots = (System.currentTimeMillis() / 300) % 4;
		g.centeredText(font, Component.translatable("streamerreplay.loading.connecting").append(".".repeat((int) dots)), width / 2, height / 2 + 4, Theme.TEXT_FAINT);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
