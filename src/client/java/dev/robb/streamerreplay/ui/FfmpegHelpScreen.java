package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.util.FfmpegLocator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-language "how to get ffmpeg" page. Nothing is downloaded by the mod: the link button only opens the
 * download page in the browser after the vanilla confirmation, and only when the player clicks it.
 */
public final class FfmpegHelpScreen extends Screen {
	private static final URI WINDOWS_DOWNLOAD = URI.create("https://www.gyan.dev/ffmpeg/builds/");
	private static final URI OTHER_DOWNLOAD = URI.create("https://ffmpeg.org/download.html");

	private final Screen parent;
	private final List<FormattedCharSequence> lines = new ArrayList<>();

	public FfmpegHelpScreen(Screen parent) {
		super(Component.translatable("streamerreplay.ffmpeg_help.title"));
		this.parent = parent;
	}

	private static boolean windows() {
		return FfmpegLocator.Os.current() == FfmpegLocator.Os.WINDOWS;
	}

	@Override
	protected void init() {
		int textWidth = Math.min(width - 40, 380);
		lines.clear();
		String os = windows() ? "windows" : FfmpegLocator.Os.current() == FfmpegLocator.Os.MAC ? "mac" : "linux";
		for (String key : List.of("intro", "steps." + os, "folder", "png")) {
			lines.addAll(font.split(Component.translatable("streamerreplay.ffmpeg_help." + key), textWidth));
			lines.add(FormattedCharSequence.EMPTY);
		}
		int w = 150;
		int y = Math.min(height - 52, 40 + lines.size() * 10 + 6);
		int cx = width / 2;
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.ffmpeg_help.download"),
				b -> ConfirmLinkScreen.confirmLinkNow(this, windows() ? WINDOWS_DOWNLOAD : OTHER_DOWNLOAD))
			.bounds(cx - w - 4, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.ffmpeg_help.open_folder"), b -> {
			Path folder = FabricLoader.getInstance().getGameDir().resolve("ffmpeg");
			try {
				Files.createDirectories(folder);
			} catch (java.io.IOException ignored) {
				// opening the game folder still helps
			}
			Blaze3D.openPath(Files.isDirectory(folder) ? folder : FabricLoader.getInstance().getGameDir());
		}).bounds(cx + 4, y, w, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(cx - w / 2, y + 24, w, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 20, Theme.TEXT);
		int y = 40;
		for (FormattedCharSequence line : lines) {
			g.centeredText(font, line, width / 2, y, Theme.TEXT);
			y += 10;
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
