package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.render.FfmpegInstaller;
import dev.robb.streamerreplay.util.FfmpegLocator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Video export needs ffmpeg". On Windows the main button installs it in one click ({@link FfmpegInstaller});
 * the manual ways stay available. Nothing is downloaded unless the player clicks Install.
 */
public final class FfmpegHelpScreen extends Screen {
	private static final URI WINDOWS_DOWNLOAD = URI.create("https://www.gyan.dev/ffmpeg/builds/");
	private static final URI OTHER_DOWNLOAD = URI.create("https://ffmpeg.org/download.html");

	private final Screen parent;
	private final List<FormattedCharSequence> lines = new ArrayList<>();
	private @Nullable Button installButton;
	private int progressY;

	public FfmpegHelpScreen(Screen parent) {
		super(Component.translatable("streamerreplay.ffmpeg_help.title"));
		this.parent = parent;
	}

	private static String os() {
		return switch (FfmpegLocator.Os.current()) {
			case WINDOWS -> "windows";
			case MAC -> "mac";
			case LINUX -> "linux";
		};
	}

	@Override
	protected void init() {
		int textWidth = Math.min(width - 40, 380);
		boolean oneClick = FfmpegInstaller.isSupported();
		lines.clear();
		List<String> paragraphs = oneClick ? List.of("intro", "install", "manual.windows", "png") : List.of("intro", "steps." + os(), "folder", "png");
		for (String key : paragraphs) {
			Component text = key.equals("install")
				? Component.translatable("streamerreplay.ffmpeg_help.install", FfmpegInstaller.VERSION, FfmpegInstaller.DOWNLOAD_BYTES / (1024 * 1024))
				: Component.translatable("streamerreplay.ffmpeg_help." + key);
			lines.addAll(font.split(text, textWidth));
			lines.add(FormattedCharSequence.EMPTY);
		}
		int w = 150;
		int cx = width / 2;
		int y = Math.min(height - 76, 40 + lines.size() * 10 + 12);
		progressY = y - 9;
		if (oneClick) {
			installButton = addRenderableWidget(Button.builder(Component.empty(), b -> toggleInstall())
				.bounds(cx - w - 4, y, w, 20)
				.tooltip(Tooltip.create(Component.translatable("streamerreplay.ffmpeg_help.install_button.tooltip")))
				.build());
			addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(cx + 4, y, w, 20).build());
			y += 24;
		}
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.ffmpeg_help.download"),
				b -> ConfirmLinkScreen.confirmLinkNow(this, oneClick ? WINDOWS_DOWNLOAD : OTHER_DOWNLOAD))
			.bounds(cx - w - 4, y, w, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("streamerreplay.ffmpeg_help.open_folder"), b -> openFfmpegFolder())
			.bounds(cx + 4, y, w, 20).build());
		if (!oneClick) {
			addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(cx - w / 2, y + 24, w, 20).build());
		}
		updateInstallButton();
	}

	private void toggleInstall() {
		if (FfmpegInstaller.isRunning()) {
			FfmpegInstaller.cancel();
		} else {
			FfmpegInstaller.start();
		}
		updateInstallButton();
	}

	private void updateInstallButton() {
		if (installButton == null) {
			return;
		}
		installButton.setMessage(Component.translatable(switch (FfmpegInstaller.state()) {
			case DOWNLOADING, UNPACKING -> "streamerreplay.ffmpeg_help.install_cancel";
			case FAILED -> "streamerreplay.ffmpeg_help.install_retry";
			default -> "streamerreplay.ffmpeg_help.install_button";
		}));
	}

	private static void openFfmpegFolder() {
		Path folder = FabricLoader.getInstance().getGameDir().resolve("ffmpeg");
		try {
			Files.createDirectories(folder);
		} catch (IOException ignored) {
			// opening the game folder still helps
		}
		Blaze3D.openPath(Files.isDirectory(folder) ? folder : FabricLoader.getInstance().getGameDir());
	}

	@Override
	public void tick() {
		if (FfmpegInstaller.state() == FfmpegInstaller.State.DONE) {
			Notifications.replay(Component.translatable("streamerreplay.toast.ffmpeg_installed", FfmpegInstaller.VERSION), null);
			FfmpegInstaller.acknowledge();
			onClose(); // the export screen checks ffmpeg again when it reopens
			return;
		}
		updateInstallButton();
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
		if (installButton == null) {
			return;
		}
		FfmpegInstaller.State state = FfmpegInstaller.state();
		if (FfmpegInstaller.isRunning()) {
			int barWidth = 2 * 150 + 8;
			Component status = state == FfmpegInstaller.State.UNPACKING
				? Component.translatable("streamerreplay.ffmpeg_help.unpacking")
				: Component.translatable("streamerreplay.ffmpeg_help.downloading", Math.round(FfmpegInstaller.progress() * 100));
			g.centeredText(font, status, width / 2, progressY - 12, Theme.TEXT_DIM);
			Theme.progressBar(g, width / 2 - barWidth / 2, progressY, barWidth, 3, FfmpegInstaller.progress(), Theme.TRACK_PLAYED);
		} else if (state == FfmpegInstaller.State.FAILED && FfmpegInstaller.error() != null) {
			g.centeredText(font, FfmpegInstaller.error(), width / 2, progressY - 6, Theme.ERROR);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
