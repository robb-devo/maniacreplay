package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.library.RecoveryService;
import dev.robb.streamerreplay.library.ReplayInfo;
import dev.robb.streamerreplay.library.ReplayLibrary;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.ui.browser.ReplayBrowserList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/** "Replays" screen: every recording with thumbnail, plus watch / rename / delete. */
public final class ReplayBrowserScreen extends Screen {
	private final Screen parent;
	private @Nullable ReplayBrowserList list;
	private @Nullable List<ReplayInfo> replays;
	private boolean loading;
	private Button watchButton;
	private Button renameButton;
	private Button deleteButton;

	public ReplayBrowserScreen(Screen parent) {
		super(Component.translatable("streamerreplay.browser.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (list == null) {
			list = new ReplayBrowserList(minecraft, width, height - 32 - 64, 32, this::watch);
			refresh(true);
		} else {
			list.updateSizeAndPosition(width, height - 32 - 64, 32);
		}
		addRenderableWidget(list);

		int cx = width / 2;
		int y = height - 54;
		watchButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.watch"), b -> watchSelected())
			.bounds(cx - 154, y, 100, 20).build());
		renameButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.rename"), b -> renameSelected())
			.bounds(cx - 50, y, 100, 20).build());
		deleteButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.delete"), b -> deleteSelected())
			.bounds(cx + 54, y, 100, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.open_folder"), b -> Blaze3D.openPath(StreamerReplay.replayFolder()))
			.bounds(cx - 154, y, 100, 20).tooltip(Tooltip.create(Component.translatable("streamerreplay.browser.open_folder.tooltip"))).build());
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.browser.settings"), b -> minecraft.gui.setScreen(new ReplayConfigScreen(this)))
			.bounds(cx - 50, y, 100, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(cx + 54, y, 100, 20).build());
		updateButtons();
	}

	private void refresh(boolean recoverFirst) {
		loading = true;
		var scan = recoverFirst
			? RecoveryService.recoverAsync().thenCompose(n -> ReplayLibrary.scanAsync())
			: ReplayLibrary.scanAsync();
		scan.thenAccept(result -> minecraft.execute(() -> {
			loading = false;
			replays = result;
			if (list != null) {
				list.setReplays(result, list.selectedInfo());
			}
			updateButtons();
		}));
	}

	private void updateButtons() {
		if (watchButton == null || list == null) {
			return;
		}
		ReplayInfo selected = list.selectedInfo();
		watchButton.active = selected != null && selected.readable();
		renameButton.active = selected != null;
		deleteButton.active = selected != null;
	}

	@Override
	public void tick() {
		updateButtons();
	}

	private void watchSelected() {
		ReplayInfo selected = list == null ? null : list.selectedInfo();
		if (selected != null) {
			watch(selected);
		}
	}

	private void watch(ReplayInfo info) {
		if (info.readable()) {
			ReplayManager.open(info.file(), this);
		}
	}

	private void renameSelected() {
		ReplayInfo selected = list == null ? null : list.selectedInfo();
		if (selected != null) {
			minecraft.gui.setScreen(new RenameReplayScreen(this, selected, () -> refresh(false)));
		}
	}

	private void deleteSelected() {
		ReplayInfo selected = list == null ? null : list.selectedInfo();
		if (selected == null) {
			return;
		}
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				try {
					ReplayLibrary.delete(selected.file());
				} catch (IOException e) {
					StreamerReplay.LOGGER.warn("Could not delete {}", selected.file(), e);
					Notifications.replay(Component.translatable("streamerreplay.toast.delete_failed"), LocalizedIOException.describe(e));
				}
				refresh(false);
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("streamerreplay.browser.delete.confirm_title"),
			Component.translatable("streamerreplay.browser.delete.confirm_text", selected.name()),
			Component.translatable("streamerreplay.browser.delete"), CommonComponents.GUI_CANCEL));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 12, Theme.TEXT);
		if (loading && replays == null) {
			g.centeredText(font, Component.translatable("streamerreplay.browser.loading"), width / 2, height / 2 - 20, Theme.TEXT_DIM);
		} else if (replays != null && replays.isEmpty()) {
			g.centeredText(font, Component.translatable("streamerreplay.browser.empty"), width / 2, height / 2 - 30, Theme.TEXT);
			g.centeredText(font, Component.translatable("streamerreplay.browser.empty_hint"), width / 2, height / 2 - 16, Theme.TEXT_DIM);
		}
	}

	@Override
	public void removed() {
		if (list != null) {
			list.close();
		}
	}

	@Override
	public void added() {
		if (list != null && replays != null) {
			list.reopen();
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
