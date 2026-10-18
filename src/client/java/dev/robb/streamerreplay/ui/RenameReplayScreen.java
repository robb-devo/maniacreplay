package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.robb.streamerreplay.library.ReplayInfo;
import dev.robb.streamerreplay.library.ReplayLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/** Renames a replay file. */
public final class RenameReplayScreen extends Screen {
	private final Screen parent;
	private final ReplayInfo replay;
	private final Runnable onRenamed;
	private EditBox nameBox;
	private Button saveButton;
	private Component error;

	public RenameReplayScreen(Screen parent, ReplayInfo replay, Runnable onRenamed) {
		super(Component.translatable("streamerreplay.rename.title"));
		this.parent = parent;
		this.replay = replay;
		this.onRenamed = onRenamed;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 2 - 20;
		String value = nameBox == null ? replay.name() : nameBox.getValue();
		nameBox = addRenderableWidget(new EditBox(font, cx - 150, y, 300, 20, Component.translatable("streamerreplay.rename.name")));
		nameBox.setMaxLength(120);
		nameBox.setValue(value);
		nameBox.setResponder(text -> validate());
		saveButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.rename.save"), b -> save())
			.bounds(cx - 150, y + 34, 146, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(cx + 4, y + 34, 146, 20).build());
		setInitialFocus(nameBox);
		validate();
	}

	private void validate() {
		boolean valid = ReplayLibrary.isValidName(nameBox.getValue());
		saveButton.active = valid;
		error = valid ? null : Component.translatable("streamerreplay.rename.invalid");
	}

	private void save() {
		if (!ReplayLibrary.isValidName(nameBox.getValue())) {
			return;
		}
		try {
			ReplayLibrary.rename(replay.file(), nameBox.getValue());
			onRenamed.run();
			onClose();
		} catch (IOException e) {
			error = LocalizedIOException.describe(e);
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if ((event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) && saveButton.active) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, height / 2 - 50, Theme.TEXT);
		if (error != null) {
			g.centeredText(font, error, width / 2, height / 2 + 4, Theme.ERROR);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
