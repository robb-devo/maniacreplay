package dev.robb.streamerreplay.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.library.ReplayInfo;
import dev.robb.streamerreplay.library.ReplayLibrary;
import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.render.ExportSettings;
import dev.robb.streamerreplay.render.PathPreviewRenderer;
import dev.robb.streamerreplay.render.RenderResolution;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.ExportProgressScreen;
import dev.robb.streamerreplay.ui.ExportScreen;
import dev.robb.streamerreplay.ui.FfmpegHelpScreen;
import dev.robb.streamerreplay.ui.Notifications;
import dev.robb.streamerreplay.ui.RenameReplayScreen;
import dev.robb.streamerreplay.ui.ReplayBrowserScreen;
import dev.robb.streamerreplay.ui.ReplayConfigScreen;
import dev.robb.streamerreplay.ui.ReplayEditorScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * End-to-end test: record a singleplayer session, open it in the browser, watch it, seek, build a camera path
 * and export it to MP4 with ffmpeg. Screenshots are written for every step.
 */
public final class RecordReplayExportTest implements FabricClientGameTest {
	/** Run with -PgametestLang=de_de to test the German UI (screenshots and label fit). */
	private static final String LANGUAGE = System.getProperty("streamerreplay.gametest.lang", "en_us");
	private static final String WORLD_NAME = "Prüfwelt Äöü ß";
	private static final String RENAMED = "Grüße aus der Öl-Straße – Übung";
	private static final String VIDEO_NAME = "Kamerafahrt Übung.mp4";

	private static void switchLanguage(ClientGameTestContext ctx, String code) {
		if (code.equals(ctx.computeOnClient(mc -> mc.getLanguageManager().getSelected()))) {
			return;
		}
		CompletableFuture<Void> reload = ctx.computeOnClient(mc -> {
			mc.getLanguageManager().setSelected(code);
			mc.options.languageCode = code;
			return mc.reloadResourcePacks();
		});
		ctx.waitFor(mc -> reload.isDone(), 20 * 120);
		ctx.waitForScreen(TitleScreen.class);
		ctx.waitTicks(20);
		String sample = ctx.computeOnClient(mc -> Component.translatable("streamerreplay.browser.watch").getString());
		StreamerReplay.LOGGER.info("[gametest] language {} active, sample label: {}", code, sample);
	}

	/**
	 * Fails if a button or slider label is wider than its widget (vanilla would scroll it, which looks broken).
	 * 8 px are reserved for the button border.
	 */
	private static void checkLabelsFit(ClientGameTestContext ctx, String where) {
		String problems = ctx.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			var all = Screens.getWidgets(mc.gui.screen());
			for (int i = 0; i < all.size(); i++) {
				for (int j = i + 1; j < all.size(); j++) {
					var a = all.get(i);
					var b = all.get(j);
					if (a.visible && b.visible && a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
						&& a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight()) {
						sb.append("overlap '").append(a.getMessage().getString()).append("' / '").append(b.getMessage().getString()).append("'; ");
					}
				}
			}
			for (var widget : all) {
				if (widget instanceof AbstractButton
					|| widget instanceof AbstractSliderButton) {
					int text = mc.font.width(widget.getMessage());
					if (widget.visible && text > widget.getWidth() - 8) {
						sb.append("'").append(widget.getMessage().getString()).append("' ").append(text).append("px > ")
							.append(widget.getWidth() - 8).append("px; ");
					}
				}
			}
			return sb.toString();
		});
		StreamerReplay.LOGGER.info("[gametest] label fit {} ({}): {}", where, LANGUAGE, problems.isEmpty() ? "ok" : problems);
		check(problems.isEmpty(), "labels overflow on " + where + ": " + problems);
	}

	/** "crash" / "recover" for the two halves of scripts/crash-recovery-test.sh; empty for the normal run. */
	private static final String MODE = System.getProperty("streamerreplay.gametest.mode", "");
	private static final String CRASH_WORLD = "Absturz Test";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (MODE.equals("crash")) {
			crashPhase(ctx);
			return;
		}
		if (MODE.equals("recover")) {
			recoverPhase(ctx);
			return;
		}
		if (MODE.equals("seekfile")) {
			ctx.waitForScreen(TitleScreen.class);
			measureSeeks(ctx, Path.of(System.getProperty("streamerreplay.gametest.replay")));
			return;
		}
		if (MODE.equals("seekbench")) {
			seekBenchmark(ctx);
			return;
		}
		ctx.waitForScreen(TitleScreen.class);
		String conflicts = ctx.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			for (KeyMapping ours : mc.options.keyMappings) {
				if (!ours.getName().startsWith("key.streamerreplay.") || ours.isUnbound()) {
					continue;
				}
				for (KeyMapping other : mc.options.keyMappings) {
					if (other != ours && other.same(ours)) {
						sb.append(ours.getName()).append(" = ").append(other.getName()).append("; ");
					}
				}
			}
			return sb.toString();
		});
		StreamerReplay.LOGGER.info("[gametest] key conflicts: {}", conflicts.isEmpty() ? "none" : conflicts);
		String backend = ctx.computeOnClient(mc -> RenderSystem.getDevice().getDeviceInfo().backendName());
		StreamerReplay.LOGGER.info("[gametest] graphics backend: {}", backend);
		check(conflicts.isEmpty(), "default keys conflict: " + conflicts);
		switchLanguage(ctx, LANGUAGE);
		ctx.takeScreenshot("01_title_screen");

		Vec3 base;
		// Umlauts and ß in the world name end up in the replay file name and metadata.
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setName(WORLD_NAME)).create()) {
			sp.getConnection().waitForChunksRender();
			check(ctx.computeOnClient(mc -> RecordingManager.isRecording()), "recording should start automatically");
			base = ctx.computeOnClient(mc -> mc.player.position());
			int x = (int) Math.floor(base.x), y = (int) Math.floor(base.y), z = (int) Math.floor(base.z);
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("gamerule advance_time false");
			sp.getServer().runCommand(String.format("fill %d %d %d %d %d %d minecraft:gold_block", x + 4, y, z + 4, x + 6, y + 4, z + 6));
			sp.getServer().runCommand(String.format("fill %d %d %d %d %d %d minecraft:diamond_block", x - 6, y, z + 4, x - 4, y + 2, z + 6));
			sp.getServer().runCommand(String.format("summon minecraft:pig %d %d %d", x + 2, y, z + 8));
			sp.getServer().runCommand(String.format("summon minecraft:armor_stand %d %d %d {CustomName:\"Streamer\",CustomNameVisible:1b}", x - 2, y, z + 8));
			ctx.waitTicks(40);
			ctx.takeScreenshot("02_recording_indicator");
			// walk around a bit (server side teleports produce position packets for the recording)
			for (int i = 0; i < 12; i++) {
				double angle = i * Math.PI / 6;
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f 10",
					x + Math.cos(angle) * 3, y, z + Math.sin(angle) * 3, (float) Math.toDegrees(angle) + 90));
				ctx.waitTicks(10);
				if (i == 6) {
					ctx.runOnClient(mc -> RecordingManager.addMarker());
					ctx.waitTicks(4);
					ctx.takeScreenshot("02a_marker_flash_in_badge");
				}
			}
			sp.getServer().runCommand(String.format("setblock %d %d %d minecraft:emerald_block", x, y + 3, z + 3));
			ctx.waitTicks(40);
			// the recording switch in the pause menu
			ctx.runOnClient(mc -> mc.gui.setScreen(new PauseScreen(true)));
			ctx.waitTicks(5);
			ctx.takeScreenshot("02c_pause_menu_recording_switch");
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			// switching recording off stops and saves; switching it back on explains that the next join records
			// (joining mid-session is not supported)
			ctx.runOnClient(mc -> RecordingManager.setRecordingEnabled(false));
			ctx.waitFor(mc -> !RecordingManager.isRecording(), 100);
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> mc.gui.toastManager().clear());
			ctx.runOnClient(mc -> RecordingManager.setRecordingEnabled(true));
			ctx.waitTicks(15);
			String hint = ctx.computeOnClient(mc -> {
				var toast = mc.gui.toastManager().getToast(SystemToast.class, Notifications.RECORDING);
				var why = RecordingManager.notRecordingHint(mc);
				return (toast == null ? "no toast" : "toast") + " " + (why == null ? "-" : ((TranslatableContents) why.getContents()).getKey());
			});
			StreamerReplay.LOGGER.info("[gametest] not-recording hint: {}", hint);
			check(hint.equals("toast streamerreplay.toast.not_recording.rejoin"), "switching recording on mid-session should explain when it starts: " + hint);
			ctx.takeScreenshot("02b_not_recording_hint");
		}
		// leaving the world stops and saves the recording in the background
		Path recorded = waitForReplay(ctx);
		StreamerReplay.LOGGER.info("[gametest] recorded {}", recorded);
		check(recorded.getFileName().toString().contains(WORLD_NAME), "file name should keep the world name with umlauts: " + recorded);
		check(ctx.computeOnClient(mc -> ReplayLibrary.scan().stream()
			.anyMatch(i -> i.meta() != null && WORLD_NAME.equals(i.meta().worldName))), "world name with umlauts should survive in the metadata");
		// rename to a name full of non-ASCII characters; the rest of the test uses the renamed file
		Path replay;
		try {
			replay = ReplayLibrary.rename(recorded, RENAMED);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		check(Files.exists(replay) && replay.getFileName().toString().startsWith(RENAMED), "rename with umlauts failed: " + replay);

		ctx.setScreen(() -> new ReplayBrowserScreen(new TitleScreen()));
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> mc.gui.toastManager().clear()); // clean gallery shots
		ctx.waitTicks(2);
		ctx.takeScreenshot("03_replay_browser");
		checkLabelsFit(ctx, "browser");
		ctx.runOnClient(mc -> {
			var info = ReplayLibrary.scan().getFirst();
			mc.gui.setScreen(new RenameReplayScreen(mc.gui.screen(), info, () -> { }));
		});
		ctx.waitTicks(5);
		ctx.takeScreenshot("03b_rename");
		checkLabelsFit(ctx, "rename");

		ctx.setScreen(() -> new ReplayConfigScreen(new TitleScreen()));
		ctx.waitTicks(5);
		ctx.takeScreenshot("04_settings");
		checkLabelsFit(ctx, "settings");

		ctx.runOnClient(mc -> ReplayManager.open(replay, new ReplayBrowserScreen(new TitleScreen())));
		waitOrDump(ctx, "replay open", mc -> {
			ReplaySession s = ReplayManager.session();
			return s != null && s.isCaughtUp(mc) && mc.gui.screen() == null;
		}, 20 * 60);
		ctx.waitTicks(30);
		ctx.takeScreenshot("05_replay_watching");

		double duration = ctx.computeOnClient(mc -> (double) ReplayManager.session().clock().durationMs());
		check(duration > 5000, "replay should be longer than 5 s but was " + duration);

		// seek forward, then backwards (forces a world rebuild + fast forward)
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.7));
		waitCaughtUp(ctx);
		ctx.waitTicks(10);
		ctx.takeScreenshot("06_after_seek_forward");
		long seekStart = System.nanoTime();
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.2));
		ctx.waitTicks(2);
		ctx.takeScreenshot("06b_rewinding_keeps_last_picture");
		waitCaughtUp(ctx);
		StreamerReplay.LOGGER.info("[gametest] backward seek 70% -> 20% (world rebuild) took {} ms", (System.nanoTime() - seekStart) / 1_000_000);
		seekStart = System.nanoTime();
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.95));
		waitCaughtUp(ctx);
		StreamerReplay.LOGGER.info("[gametest] forward seek 20% -> 95% took {} ms", (System.nanoTime() - seekStart) / 1_000_000);
		seekStart = System.nanoTime();
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.9));
		waitCaughtUp(ctx);
		StreamerReplay.LOGGER.info("[gametest] backward seek 95% -> 90% (world rebuild) took {} ms", (System.nanoTime() - seekStart) / 1_000_000);
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.2));
		waitCaughtUp(ctx);
		ctx.waitTicks(10);
		ctx.takeScreenshot("07_after_seek_back");

		pausedInteraction(ctx, duration);

		// Esc in a replay opens the replay menu instead of the vanilla pause menu
		ctx.runOnClient(mc -> mc.pauseGame(false));
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.gui.screen() != null
			&& mc.gui.screen().getClass().getSimpleName().equals("ReplayPauseScreen")), "Esc should open the replay menu");
		ctx.takeScreenshot("07c_replay_menu");
		checkLabelsFit(ctx, "replay menu");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		ctx.waitTicks(2);

		// camera path: three keyframes around the build
		Vec3 b = base;
		ctx.runOnClient(mc -> {
			ReplaySession s = ReplayManager.session();
			s.seek(duration * 0.25);
		});
		waitCaughtUp(ctx);
		ctx.runOnClient(mc -> {
			ReplaySession s = ReplayManager.session();
			s.clock().setPlaying(true);
			key(s, new CameraState(b.x - 8, b.y + 6, b.z - 4, -35, 25, 0, 70));
		});
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> key(ReplayManager.session(), new CameraState(b.x, b.y + 9, b.z - 9, 0, 35, 5, 60)));
		ctx.waitTicks(40);
		ctx.runOnClient(mc -> key(ReplayManager.session(), new CameraState(b.x + 9, b.y + 5, b.z - 2, 45, 20, 0, 80)));
		ctx.runOnClient(mc -> {
			// step back so the whole path (drawn in the world while the editor is open) is in view
			CameraState overview = new CameraState(b.x, b.y + 14, b.z - 22, 0, 30, 0, 70);
			ReplayManager.session().camera().setPose(overview);
			ReplayManager.session().camera().moveToPose(overview);
			mc.gui.setScreen(new ReplayEditorScreen(ReplayManager.session()));
		});
		ctx.waitTicks(10);
		ctx.takeScreenshot("08_editor_with_path");
		checkLabelsFit(ctx, "editor");
		StreamerReplay.LOGGER.info("[gametest] path overlay: {}", (Object) ctx.<String, RuntimeException>computeOnClient(mc ->
			PathPreviewRenderer.describeProjection(ReplayManager.session(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())));
		// right-drag over the world turns the camera in the editor
		float yawBeforeDrag = ctx.computeOnClient(mc -> mc.player.getYRot());
		ctx.getInput().setCursorPos(300, 200);
		ctx.waitTicks(1);
		ctx.getInput().holdMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		ctx.waitTicks(1);
		ctx.getInput().moveCursor(80, 0);
		ctx.waitTicks(1);
		ctx.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT);
		ctx.waitTicks(2);
		float yawAfterDrag = ctx.computeOnClient(mc -> mc.player.getYRot());
		StreamerReplay.LOGGER.info("[gametest] editor right-drag turned the camera by {} degrees", yawAfterDrag - yawBeforeDrag);
		check(Math.abs(yawAfterDrag - yawBeforeDrag) > 1, "right-drag in the editor should turn the camera");
		int keyframes = ctx.computeOnClient(mc -> ReplayManager.session().path().size());
		check(keyframes == 3, "expected 3 keyframes, got " + keyframes);

		ctx.runOnClient(mc -> mc.gui.setScreen(new ExportScreen(ReplayManager.session(), mc.gui.screen())));
		ctx.waitTicks(40);
		ctx.takeScreenshot("09_export_settings");
		checkLabelsFit(ctx, "export");

		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve(VIDEO_NAME));
		ctx.runOnClient(mc -> {
			ReplayConfig cfg = ReplayConfig.get();
			VideoExporter.start(ReplayManager.session(), new ExportSettings(video, cfg.ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0, true), true);
			var progress = new ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session()));
			mc.gui.setScreen(progress);
		});
		ctx.waitTicks(20);
		ctx.takeScreenshot("10_export_progress");
		ctx.waitFor(mc -> {
			VideoExporter e = VideoExporter.current();
			return e != null && (e.state() == VideoExporter.State.DONE || e.state() == VideoExporter.State.FAILED || e.state() == VideoExporter.State.CANCELLED);
		}, 20 * 600);
		ctx.waitTicks(3);
		ctx.takeScreenshot("11_export_done");
		checkLabelsFit(ctx, "export done");
		String error = ctx.computeOnClient(mc -> VideoExporter.current().state() + " "
			+ (VideoExporter.current().error() == null ? "-" : VideoExporter.current().error().getString())
			+ " frames=" + VideoExporter.current().framesDone() + "/" + VideoExporter.current().totalFrames());
		StreamerReplay.LOGGER.info("[gametest] export result: {}", error);
		check(error.startsWith("DONE"), "export failed: " + error);
		try {
			check(Files.size(video) > 10_000, "video too small");
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		nativeResolutionExport(ctx);
		verticalExport(ctx);
		cleanFramesExport(ctx, b);
		noFfmpegNoPathExport(ctx);

		// leave with a real click on the editor's Exit button (26.3 numbers the left mouse button 1, which the editor
		// once mistook for the right one and swallowed every left click above its panel)
		ctx.runOnClient(mc -> mc.gui.setScreen(new ReplayEditorScreen(ReplayManager.session())));
		ctx.waitTicks(5);
		clickButton(ctx, "streamerreplay.editor.exit");
		ctx.waitForScreen(ReplayBrowserScreen.class);
		ctx.waitTicks(20);
		ctx.takeScreenshot("12_back_in_browser");
		ctx.setScreen(TitleScreen::new);

		multiplayer(ctx);

		ctx.setScreen(TitleScreen::new);
		ctx.waitTicks(60);
		ctx.runOnClient(mc -> mc.gui.toastManager().clear());
		ctx.waitTicks(2);
		ctx.takeScreenshot("15_title_screen_button");
		StreamerReplay.LOGGER.info("[gametest] ALL CHECKS PASSED");
	}

	/** Records on a real dedicated server (TCP, compression enabled) and plays that recording back. */
	private static void multiplayer(ClientGameTestContext ctx) {
		Path folder = ctx.computeOnClient(mc -> StreamerReplay.replayFolder());
		int before = list(folder, ReplayFormat.EXTENSION).size();
		long[] netherAt = {0};
		Properties props = new Properties();
		props.setProperty("network-compression-threshold", "64");
		try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
			try (TestDedicatedServerConnection connection = server.connect()) {
				connection.waitForChunksRender();
				check(ctx.computeOnClient(mc -> RecordingManager.isRecording()), "recording should start on a server too");
				server.runCommand("time set noon");
				server.runCommand("gamerule advance_time false");
				Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
				int x = (int) Math.floor(p.x), y = (int) Math.floor(p.y), z = (int) Math.floor(p.z);
				server.runCommand(String.format("fill %d %d %d %d %d %d minecraft:redstone_block", x + 3, y, z + 3, x + 5, y + 3, z + 5));
				server.runCommand(String.format("summon minecraft:cow %d %d %d", x - 2, y, z + 5));
				ctx.waitTicks(60);
				ctx.takeScreenshot("13_multiplayer_recording");
				// to the Nether and back, so the replay contains dimension switches
				server.runCommand("gamemode creative @a");
				netherAt[0] = ctx.computeOnClient(mc -> (long) RecordingManager.active().elapsedMs());
				server.runCommand("execute in minecraft:the_nether run tp @a 0.5 128 0.5");
				ctx.waitTicks(60);
				server.runCommand(String.format("execute in minecraft:overworld run tp @a %d %d %d", x, y, z));
				ctx.waitTicks(60);
			}
		}
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == before + 1
			&& list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 30);
		Path mp = ctx.computeOnClient(mc -> ReplayLibrary.scan().stream()
			.filter(i -> i.meta() != null && !i.meta().singleplayer).map(ReplayInfo::file)
			.findFirst().orElse(null));
		check(mp != null, "multiplayer replay should be listed with singleplayer=false");
		StreamerReplay.LOGGER.info("[gametest] multiplayer recording {}", mp);

		// This recording is short: play slowly so it does not reach its end (and pause) while the test waits.
		ctx.runOnClient(mc -> {
			ReplayManager.open(mp, new ReplayBrowserScreen(new TitleScreen()));
			ReplayManager.session().clock().setSpeed(0.25);
		});
		waitOrDump(ctx, "mp replay open", mc -> {
			ReplaySession s = ReplayManager.session();
			return s != null && s.isCaughtUp(mc) && mc.gui.screen() == null;
		}, 20 * 60);
		// a dimension switch during normal playback: the camera follows the recorded player into the Nether
		ctx.runOnClient(mc -> {
			ReplaySession s = ReplayManager.session();
			s.clock().setSpeed(1);
			s.seek(Math.max(0, netherAt[0] - 1500));
			s.clock().setPlaying(true);
		});
		waitOrDump(ctx, "replay reaches the Nether", mc -> mc.level != null && mc.level.dimension() == Level.NETHER, 20 * 30);
		waitOrDump(ctx, "camera follows the recorded player into the Nether", mc -> {
			var entity = mc.getCameraEntity();
			return entity != null && entity != mc.player && entity.getUUID().toString().equals(ReplayManager.session().meta().playerUuid);
		}, 20 * 10);
		ctx.takeScreenshot("14a_followed_into_the_nether");
		StreamerReplay.LOGGER.info("[gametest] dimension switch: the camera follows the recorded player into the Nether");
		ctx.runOnClient(mc -> {
			ReplayManager.session().clock().setSpeed(0.1);
			// 1.5 s of replay at 0.1x leaves 30 game ticks before the end; the checks below need fewer.
			ReplayManager.session().seek(ReplayManager.session().clock().durationMs() - 1500);
		});
		waitCaughtUp(ctx);
		ctx.waitTicks(4);
		boolean seesBlocks = ctx.computeOnClient(mc -> {
			Vec3 p = mc.player.position();
			var pos = BlockPos.containing(p.x + 4, p.y + 1, p.z + 4);
			for (int dx = -10; dx <= 10; dx++) {
				for (int dz = -10; dz <= 10; dz++) {
					for (int dy = -6; dy <= 6; dy++) {
						if (mc.level.getBlockState(pos.offset(dx, dy, dz)).is(Blocks.REDSTONE_BLOCK)) {
							return true;
						}
					}
				}
			}
			return false;
		});
		ctx.takeScreenshot("14_multiplayer_replay");
		check(seesBlocks, "multiplayer replay should contain the placed redstone blocks");
		ctx.runOnClient(mc -> ReplayManager.exit());
		ctx.waitForScreen(ReplayBrowserScreen.class);
	}

	/**
	 * With the replay paused, real input events (mouse movement, WASD, left click, Shift) must still look around,
	 * fly, follow an entity and stop following. The test itself only advances on client ticks, so it also proves
	 * that a paused replay keeps ticking its UI ("frozen ticks").
	 */
	/**
	 * First half of the crash test: record a world for a while, add a marker, then announce the point at which the
	 * script kills the JVM with SIGKILL (no shutdown hooks, no save). The test never finishes on its own.
	 */
	private static void crashPhase(ClientGameTestContext ctx) {
		ctx.waitForScreen(TitleScreen.class);
		TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setName(CRASH_WORLD)).create();
		sp.getConnection().waitForChunksRender();
		check(ctx.computeOnClient(mc -> RecordingManager.isRecording()), "recording should start automatically");
		Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
		sp.getServer().runCommand("time set noon");
		sp.getServer().runCommand(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gold_block",
			(int) p.x + 3, (int) p.y, (int) p.z + 3, (int) p.x + 5, (int) p.y + 3, (int) p.z + 5));
		for (int i = 0; i < 16; i++) {
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %d 10", p.x + Math.cos(i / 2.5) * 3, p.y, p.z + Math.sin(i / 2.5) * 3, i * 22));
			ctx.waitTicks(10);
			if (i == 8) {
				ctx.runOnClient(mc -> RecordingManager.addMarker());
			}
		}
		ctx.waitTicks(40); // > the 1 s flush interval
		Path rec = ctx.computeOnClient(mc -> RecordingManager.activeRecordingFile());
		long size = 0;
		try {
			size = Files.size(rec);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ctx.takeScreenshot("crash_01_recording_before_kill");
		StreamerReplay.LOGGER.info("[gametest] CRASH-POINT recording {} ({} bytes on disk) - kill me now", rec.getFileName(), size);
		ctx.waitTicks(20 * 600); // the script kills the game long before this ends
		throw new AssertionError("the crash script did not kill the game");
	}

	/** Second half: a fresh start on the same game folder must turn the unfinished recording into a working replay. */
	private static void recoverPhase(ClientGameTestContext ctx) {
		ctx.waitForScreen(TitleScreen.class);
		Path folder = ctx.computeOnClient(mc -> StreamerReplay.replayFolder());
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == 1 && list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 30);
		Path replay = list(folder, ReplayFormat.EXTENSION).getFirst();
		StreamerReplay.LOGGER.info("[gametest] recovered file: {}", replay.getFileName());
		var info = ctx.computeOnClient(mc -> ReplayLibrary.scan().getFirst());
		check(info.meta() != null && CRASH_WORLD.equals(info.meta().worldName), "metadata should survive the crash: " + info.meta());
		StreamerReplay.LOGGER.info("[gametest] recovered meta: world={} duration={} ms markers={}", info.meta().worldName, info.meta().durationMs, info.markers());
		check(info.markers() == 1, "the marker set before the crash should survive");
		try (var leftovers = Files.list(folder)) {
			List<String> names = leftovers.map(f -> f.getFileName().toString()).toList();
			StreamerReplay.LOGGER.info("[gametest] replay folder after recovery: {}", names);
			check(names.size() == 1, "sidecar files should be cleaned up after recovery: " + names);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ctx.setScreen(() -> new ReplayBrowserScreen(new TitleScreen()));
		ctx.waitTicks(30);
		ctx.takeScreenshot("crash_02_recovered_in_browser");
		ctx.runOnClient(mc -> ReplayManager.open(replay, new ReplayBrowserScreen(new TitleScreen())));
		waitOrDump(ctx, "recovered replay open", mc -> {
			ReplaySession s = ReplayManager.session();
			return s != null && s.isCaughtUp(mc) && mc.gui.screen() == null;
		}, 20 * 60);
		double duration = ctx.computeOnClient(mc -> (double) ReplayManager.session().clock().durationMs());
		StreamerReplay.LOGGER.info("[gametest] recovered replay plays, duration {} ms", duration);
		check(duration > 5000, "recovered replay should contain the recorded time, was " + duration + " ms");
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.9));
		waitCaughtUp(ctx);
		ctx.waitTicks(20);
		ctx.takeScreenshot("crash_03_recovered_replay_playing");
		ctx.runOnClient(mc -> ReplayManager.exit());
		ctx.waitForScreen(ReplayBrowserScreen.class);
		ctx.setScreen(TitleScreen::new);
		StreamerReplay.LOGGER.info("[gametest] CRASH RECOVERY CHECKS PASSED");
	}

	/**
	 * Measures backward seeks in a longer, busier replay (90 s, 40 wandering mobs, the player moving):
	 * -PcrashPhase is not used; run with -PgametestProps=streamerreplay.gametest.mode=seekbench.
	 * Also guards against a paused replay getting stuck on the loading screen after a backward seek.
	 */
	private static void seekBenchmark(ClientGameTestContext ctx) {
		ctx.waitForScreen(TitleScreen.class);
		Path folder = ctx.computeOnClient(mc -> StreamerReplay.replayFolder());
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setName("Seek Bench")).create()) {
			sp.getConnection().waitForChunksRender();
			Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
			sp.getServer().runCommand("time set noon");
			for (int i = 0; i < 40; i++) {
				sp.getServer().runCommand(String.format(Locale.ROOT, "summon minecraft:%s %.1f %.1f %.1f", i % 2 == 0 ? "sheep" : "chicken",
					p.x + (i % 8) * 2 - 8, p.y, p.z + (i / 8) * 2 + 4));
			}
			for (int i = 0; i < 90; i++) {
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f %d 10", p.x + Math.cos(i / 6.0) * 12, p.y, p.z + Math.sin(i / 6.0) * 12, i * 4));
				ctx.waitTicks(20);
			}
		}
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == 1 && list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 60);
		measureSeeks(ctx, list(folder, ReplayFormat.EXTENSION).getFirst());
	}

	/**
	 * Opens {@code replay} and measures how long seeks take until everything up to the target is delivered. With
	 * -PgametestProps=streamerreplay.gametest.mode=seekfile,streamerreplay.gametest.replay=/path/x.sreplay it runs on
	 * any real recording.
	 */
	private static void measureSeeks(ClientGameTestContext ctx, Path replay) {
		ctx.runOnClient(mc -> ReplayManager.open(replay, new ReplayBrowserScreen(new TitleScreen())));
		waitOrDump(ctx, "bench replay open", mc -> ReplayManager.session() != null && ReplayManager.session().isCaughtUp(mc) && mc.gui.screen() == null, 20 * 120);
		double duration = ctx.computeOnClient(mc -> (double) ReplayManager.session().clock().durationMs());
		long size;
		try {
			size = Files.size(replay);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		ctx.runOnClient(mc -> ReplayManager.session().clock().setPlaying(false));
		StringBuilder results = new StringBuilder();
		for (double[] seek : new double[][] {{0.98, 0.9}, {0.98, 0.5}, {0.98, 0.1}, {0.1, 0.98}}) {
			ctx.runOnClient(mc -> ReplayManager.session().seek(duration * seek[0]));
			waitOrDump(ctx, "seek setup", mc -> ReplayManager.session().isCaughtUp(mc), 20 * 600);
			ctx.waitTicks(5);
			long start = System.nanoTime();
			ctx.runOnClient(mc -> ReplayManager.session().seek(duration * seek[1]));
			waitOrDump(ctx, "measured seek", mc -> ReplayManager.session().isCaughtUp(mc), 20 * 600);
			long ms = (System.nanoTime() - start) / 1_000_000;
			results.append(String.format(Locale.ROOT, " %d%%->%d%%: %d ms;", (int) (seek[0] * 100), (int) (seek[1] * 100), ms));
		}
		StreamerReplay.LOGGER.info("[gametest] SEEK BENCH replay {} s, {} KB:{}",
			(long) (duration / 1000), size / 1024, results);
		ctx.runOnClient(mc -> ReplayManager.exit());
		ctx.waitForScreen(ReplayBrowserScreen.class);
		ctx.setScreen(TitleScreen::new);
	}

	private static void pausedInteraction(ClientGameTestContext ctx, double duration) {
		// Near the end of the recording the pig is guaranteed to exist (it was summoned early on).
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.85));
		waitCaughtUp(ctx);
		ctx.runOnClient(mc -> ReplayManager.session().clock().setPlaying(false));
		ctx.waitTicks(5);
		double frozenAt = ctx.computeOnClient(mc -> ReplayManager.session().clock().timeMs());

		float yawBefore = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose().yaw());
		// Fabric's moveCursor only sets the absolute position, but with a grabbed mouse vanilla reads SDL's relative
		// motion, so feed the same SDL motion event vanilla's event loop would deliver.
		// (The first event after grabbing the mouse is ignored by vanilla, so send a zero move first.)
		ctx.runOnClient(mc -> mc.mouseHandler.onMove(mc.getWindow().handle(), mc.mouseHandler.xpos(), mc.mouseHandler.ypos(), 0, 0));
		ctx.runOnClient(mc -> mc.mouseHandler.onMove(mc.getWindow().handle(), mc.mouseHandler.xpos() + 150, mc.mouseHandler.ypos(), 150, 0));
		ctx.waitTicks(3);
		float yawAfter = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose().yaw());
		StreamerReplay.LOGGER.info("[gametest] paused mouse look: yaw {} -> {}", yawBefore, yawAfter);
		check(Math.abs(Mth.wrapDegrees(yawAfter - yawBefore)) > 5, "mouse look should work while paused");

		CameraState before = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose());
		ctx.getInput().holdKeyFor(o -> o.keyUp, 15);
		ctx.waitTicks(5);
		CameraState after = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose());
		double moved = Math.sqrt(Math.pow(after.x() - before.x(), 2) + Math.pow(after.z() - before.z(), 2));
		StreamerReplay.LOGGER.info("[gametest] paused flight: moved {} blocks", String.format(Locale.ROOT, "%.2f", moved));
		check(moved > 1, "W should move the camera while paused (moved " + moved + ")");

		// aim at the pig from above (nothing can stand in between), then a real left click
		boolean aimed = ctx.computeOnClient(mc -> {
			Entity pig = findPig(mc);
			if (pig == null) {
				StreamerReplay.LOGGER.error("[gametest] no pig in the replay world");
				return false;
			}
			Vec3 target = pig.getBoundingBox().getCenter();
			Vec3 eye = target.add(0.3, 4, 0.3);
			Vec3 d = target.subtract(eye);
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			CameraState aim = new CameraState(eye.x, eye.y, eye.z, yaw, pitch, 0, 70);
			ReplayManager.session().camera().setPose(aim);
			ReplayManager.session().camera().moveToPose(aim);
			return true;
		});
		check(aimed, "the recorded pig should exist");
		ctx.waitTicks(2);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.getCameraEntity() == findPig(mc)), "left click should follow the pig while paused");
		ctx.takeScreenshot("07b_following_entity");

		// onboarding toasts are not shown in a replay
		boolean toastsHidden = ctx.computeOnClient(mc -> {
			mc.gui.addSocialInteractionsToast();
			SystemToast.add(mc.gui.toastManager(),
				SystemToast.SystemToastId.UNSECURE_SERVER_WARNING,
				Component.literal("test"), null);
			return mc.gui.toastManager().getToast(TutorialToast.class,
				Toast.NO_TOKEN) == null
				&& mc.gui.toastManager().getToast(SystemToast.class,
				SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) == null;
		});
		check(toastsHidden, "tutorial / unsecure-server toasts should be suppressed in a replay");

		ctx.getInput().holdKeyFor(o -> o.keyShift, 3);
		ctx.waitTicks(3);
		check(ctx.computeOnClient(mc -> mc.getCameraEntity() == mc.player), "Shift should return to the free camera");
		double timeNow = ctx.computeOnClient(mc -> ReplayManager.session().clock().timeMs());
		check(Math.abs(timeNow - frozenAt) < 1, "replay time should not move while paused: " + frozenAt + " -> " + timeNow);
		// a backward seek while paused rebuilds the world; it must leave the loading screen and stay paused
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.5));
		waitCaughtUp(ctx);
		check(ctx.computeOnClient(mc -> !ReplayManager.session().clock().isPlaying()), "the replay should stay paused after seeking back");
		ctx.runOnClient(mc -> ReplayManager.session().clock().setPlaying(true));
	}

	private static net.minecraft.world.entity.@Nullable Entity findPig(Minecraft mc) {
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e.getType() == EntityTypes.PIG) {
				return e;
			}
		}
		return null;
	}

	/** Runs one export to completion and returns its final state line. */
	private static String export(ClientGameTestContext ctx, ExportSettings settings, Runnable whileRendering) {
		ctx.runOnClient(mc -> {
			VideoExporter.start(ReplayManager.session(), settings, true);
			mc.gui.setScreen(new ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session())));
		});
		ctx.waitTicks(20);
		whileRendering.run();
		ctx.waitFor(mc -> {
			VideoExporter e = VideoExporter.current();
			return e != null && (e.state() == VideoExporter.State.DONE || e.state() == VideoExporter.State.FAILED || e.state() == VideoExporter.State.CANCELLED);
		}, 20 * 900);
		String result = ctx.computeOnClient(mc -> VideoExporter.current().state() + " "
			+ (VideoExporter.current().error() == null ? "-" : VideoExporter.current().error().getString())
			+ " frames=" + VideoExporter.current().framesDone() + "/" + VideoExporter.current().totalFrames());
		ctx.runOnClient(mc -> mc.gui.setScreen(null));
		ctx.waitTicks(2);
		return result;
	}

	/**
	 * 1080p export from a 854×480 window, and the window is resized in the middle of it: the video must still be
	 * a native 1920×1080 file, and the window must return to normal rendering afterwards.
	 */
	private static void nativeResolutionExport(ClientGameTestContext ctx) {
		// On Vulkan, Minecraft 26.3 loses the device when the test resizes the window, even on the title screen with no
		// replay open; so the window keeps its size there and the letterbox check runs on OpenGL only.
		boolean resizable = !ctx.computeOnClient(mc -> RenderSystem.getDevice().getDeviceInfo().backendName().toLowerCase(Locale.ROOT).contains("vulkan"));
		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Native 1080p.mp4"));
		String result = export(ctx, new ExportSettings(video, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 1920, 1080, true), () -> {
			int rendering = ctx.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width);
			StreamerReplay.LOGGER.info("[gametest] render target during 1080p export: {} wide", rendering);
			check(rendering == 1920, "the game should render at 1920 wide during the export, was " + rendering);
			// resize while frames are really being captured (not during the world rebuild at the start)
			ctx.waitFor(mc -> VideoExporter.current().framesDone() >= 10, 20 * 120);
			// The harness screenshot renders one extra frame at window size with the 1080p GUI layout (so it looks
			// cropped); it is not a gallery shot, it proves such foreign renders do not break the export.
			ctx.takeScreenshot("zz_harness_extra_render_during_1080p_export");
			if (resizable) {
				ctx.getInput().resizeWindow(700, 520);
				ctx.waitTicks(20);
				checkPreview(ctx, "1080p in 700x520", 1920, 1080); // bars above and below
			} else {
				checkPreview(ctx, "1080p in 854x480", 1920, 1080); // same shape: fills the window
			}
		});
		StreamerReplay.LOGGER.info("[gametest] 1080p export (window resized mid-way): {}", result);
		check(result.startsWith("DONE"), "1080p export failed: " + result);
		String probe = ffprobe(video);
		StreamerReplay.LOGGER.info("[gametest] ffprobe 1080p: {}", probe);
		check(probe.startsWith("h264,1920,1080"), "video should be native 1920x1080 H.264: " + probe);
		// sharpness: a native 1080p frame has much more fine detail than the same frame squeezed through 480p
		BufferedImage nativeFrame = frame(video, "1", "");
		BufferedImage via480 = frame(video, "1", "scale=854:480,scale=1920:1080");
		double sharp = laplacianVariance(nativeFrame);
		double soft = laplacianVariance(via480);
		double brightness = meanLuma(nativeFrame);
		StreamerReplay.LOGGER.info("[gametest] 1080p sharpness: native {} vs 480p-upscaled {} (ratio {}), mean luma {}",
			String.format("%.1f", sharp), String.format("%.1f", soft), String.format("%.2f", sharp / soft), String.format("%.1f", brightness));
		check(sharp > soft * 1.3, "the 1080p video should be clearly sharper than an upscaled 480p frame");
		check(brightness > 40, "the 1080p video should not be dark (sky/fog lost after the world rebuild?)");
		float sky = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().attributeProbe()
			.getValue(EnvironmentAttributes.SKY_COLOR, 0).length());
		check(sky > 0.1f, "the sky colour must be known again after the export's world rebuild");
		if (resizable) {
			ctx.getInput().resizeWindow(854, 480);
			ctx.waitTicks(20);
		}
		int[] after = ctx.computeOnClient(mc -> new int[] {mc.gameRenderer.mainRenderTarget().width, mc.getWindow().getWidth(), mc.getWindow().getGuiScaledWidth()});
		StreamerReplay.LOGGER.info("[gametest] after export: target {} window {} gui {}", after[0], after[1], after[2]);
		check(after[0] == 854 && after[1] == 854, "rendering should return to the window size after the export");
	}

	/** 9:16 for Shorts: a native 1080×1920 video, previewed upright (letterboxed) in the 16:9 window. */
	private static void verticalExport(ClientGameTestContext ctx) {
		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Vertical 9x16.mp4"));
		String result = export(ctx, new ExportSettings(video, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 1080, 1920, true), () -> {
			ctx.waitFor(mc -> VideoExporter.current().framesDone() >= 5, 20 * 120);
			checkPreview(ctx, "1080x1920 in 854x480", 1080, 1920); // bars left and right
		});
		StreamerReplay.LOGGER.info("[gametest] vertical export: {}", result);
		check(result.startsWith("DONE"), "vertical export failed: " + result);
		String probe = ffprobe(video);
		StreamerReplay.LOGGER.info("[gametest] ffprobe vertical: {}", probe);
		check(probe.startsWith("h264,1080,1920"), "video should be native 1080x1920 H.264: " + probe);
		int[] filled = contentBounds(frame(video, "1", ""));
		StreamerReplay.LOGGER.info("[gametest] vertical video frame content: {},{} to {},{}", filled[0], filled[1], filled[2], filled[3]);
		check(filled[0] <= 2 && filled[1] <= 2 && filled[2] >= 1077 && filled[3] >= 1917, "the vertical video itself must be filled edge to edge");
	}

	/**
	 * Two exports of the same short shot looking at the named armor stand, with hitboxes switched on (F3+B):
	 * with "hide name tags & overlays" the frame must differ clearly from the one without.
	 */
	private static void cleanFramesExport(ClientGameTestContext ctx, Vec3 b) {
		double t = ctx.computeOnClient(mc -> ReplayManager.session().clock().durationMs() * 0.8);
		ctx.runOnClient(mc -> {
			ReplaySession s = ReplayManager.session();
			s.clearPath();
			s.seek(t);
		});
		waitCaughtUp(ctx);
		CameraState shot = new CameraState(b.x - 2.5, b.y + 2.2, b.z + 4.5, 0, 25, 0, 70); // just in front of the armor stand
		ctx.runOnClient(mc -> key(ReplayManager.session(), shot));
		ctx.runOnClient(mc -> ReplayManager.session().seek(t + 400));
		waitCaughtUp(ctx);
		ctx.runOnClient(mc -> {
			key(ReplayManager.session(), shot);
			mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES,
				DebugScreenEntryStatus.ALWAYS_ON);
		});
		Path plain = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Overlays on.mp4"));
		Path clean = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Overlays hidden.mp4"));
		String r1 = export(ctx, new ExportSettings(plain, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0, false), () -> { });
		String r2 = export(ctx, new ExportSettings(clean, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0, true), () -> { });
		ctx.runOnClient(mc -> mc.debugEntries.setStatus(DebugScreenEntries.ENTITY_HITBOXES,
			DebugScreenEntryStatus.NEVER));
		check(r1.startsWith("DONE") && r2.startsWith("DONE"), "clean-frame exports failed: " + r1 + " / " + r2);
		long changed = differingPixels(firstFrame(plain), firstFrame(clean));
		StreamerReplay.LOGGER.info("[gametest] clean frames: {} pixels differ between overlays on/hidden", changed);
		check(changed > 300, "hiding name tags and hitboxes should visibly change the frame (" + changed + " px)");
	}

	/**
	 * A beginner's first export: no camera path and no ffmpeg. The help screen must open by itself, the start button
	 * must stay clickable ("Export PNG Images") and produce a 10 s PNG sequence from the current camera.
	 */
	private static void noFfmpegNoPathExport(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			ReplayManager.session().clearPath();
			ReplayManager.session().seek(1000);
			ReplayConfig.get().ffmpegPath = "/nonexistent/ffmpeg";
			ReplayConfig.get().exportFps = 30;
		});
		waitCaughtUp(ctx);
		ctx.runOnClient(mc -> mc.gui.setScreen(new ExportScreen(ReplayManager.session(), null)));
		ctx.waitForScreen(FfmpegHelpScreen.class);
		ctx.waitTicks(10);
		ctx.takeScreenshot("11b_ffmpeg_help");
		checkLabelsFit(ctx, "ffmpeg help");
		ctx.runOnClient(mc -> mc.gui.screen().onClose());
		ctx.waitForScreen(ExportScreen.class);
		ctx.waitTicks(10);
		ctx.takeScreenshot("11c_export_without_path_and_ffmpeg");
		checkLabelsFit(ctx, "export without path/ffmpeg");
		ctx.clickScreenButton("streamerreplay.export.start_png");
		ctx.waitFor(mc -> {
			VideoExporter e = VideoExporter.current();
			return e != null && e.isPngSequence() && (e.state() == VideoExporter.State.DONE || e.state() == VideoExporter.State.FAILED);
		}, 20 * 600);
		String result = ctx.computeOnClient(mc -> VideoExporter.current().state() + " " + VideoExporter.current().output());
		Path folder = ctx.computeOnClient(mc -> VideoExporter.current().output());
		long pngs = list(folder, ".png").size();
		StreamerReplay.LOGGER.info("[gametest] PNG export without ffmpeg/path: {} -> {} PNG files", result, pngs);
		check(result.startsWith("DONE") && pngs == 301, "PNG export should write 301 frames (10 s at 30 FPS): " + result + ", " + pngs);
		ctx.runOnClient(mc -> {
			ReplayConfig.get().ffmpegPath = "ffmpeg";
			mc.gui.setScreen(null);
		});
		ctx.waitTicks(5);
	}

	private static String ffprobe(Path video) {
		return run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", video.toString()).strip();
	}

	private static BufferedImage firstFrame(Path video) {
		Path png = video.resolveSibling(video.getFileName() + ".frame0.png");
		run("ffmpeg", "-v", "error", "-y", "-i", video.toString(), "-frames:v", "1", png.toString());
		try {
			return ImageIO.read(png.toFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static long differingPixels(BufferedImage a, BufferedImage b) {
		long count = 0;
		for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
			for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)), Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
				if (d > 40) {
					count++;
				}
			}
		}
		return count;
	}

	private static String run(String... command) {
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.waitFor();
			return out;
		} catch (IOException e) {
			throw new AssertionError(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static BufferedImage frame(Path video, String second, String filter) {
		Path png = video.resolveSibling(video.getFileName() + ".frame" + second + (filter.isEmpty() ? "" : "-filtered") + ".png");
		List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-v", "error", "-y", "-ss", second, "-i", video.toString(), "-frames:v", "1"));
		if (!filter.isEmpty()) {
			cmd.addAll(List.of("-vf", filter + ":flags=bicubic"));
		}
		cmd.add(png.toString());
		run(cmd.toArray(String[]::new));
		try {
			return ImageIO.read(png.toFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static double luma(int rgb) {
		return 0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255);
	}

	private static double meanLuma(BufferedImage img) {
		double sum = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				sum += luma(img.getRGB(x, y));
			}
		}
		return sum / ((double) img.getWidth() * img.getHeight());
	}

	/** Variance of the 4-neighbour Laplacian: the standard "how much fine detail" measure. */
	private static double laplacianVariance(BufferedImage img) {
		double sum = 0;
		double sumSq = 0;
		long n = 0;
		for (int y = 1; y < img.getHeight() - 1; y++) {
			for (int x = 1; x < img.getWidth() - 1; x++) {
				double l = 4 * luma(img.getRGB(x, y)) - luma(img.getRGB(x - 1, y)) - luma(img.getRGB(x + 1, y))
					- luma(img.getRGB(x, y - 1)) - luma(img.getRGB(x, y + 1));
				sum += l;
				sumSq += l * l;
				n++;
			}
		}
		double mean = sum / n;
		return sumSq / n - mean * mean;
	}

	private static void key(ReplaySession s, CameraState pose) {
		s.camera().setPose(pose);
		s.camera().moveToPose(pose);
		s.addKeyframeAtCamera();
	}

	private static void waitCaughtUp(ClientGameTestContext ctx) {
		ctx.waitTicks(2);
		waitOrDump(ctx, "catch up", mc -> ReplayManager.session() != null && ReplayManager.session().isCaughtUp(mc), 20 * 60);
	}

	private static void waitOrDump(ClientGameTestContext ctx, String what, Predicate<Minecraft> condition, int timeout) {
		try {
			ctx.waitFor(condition, timeout);
		} catch (AssertionError e) {
			String state = ctx.computeOnClient(mc -> ReplayManager.session() == null ? "no session, screen=" + mc.gui.screen()
				: ReplayManager.session().debugState(mc));
			StreamerReplay.LOGGER.error("[gametest] timed out waiting for {}: {}", what, state);
			ctx.takeScreenshot("timeout_" + what.replace(' ', '_'));
			throw e;
		}
	}

	private static Path waitForReplay(ClientGameTestContext ctx) {
		Path folder = ctx.computeOnClient(mc -> StreamerReplay.replayFolder());
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == 1 && list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 30);
		return list(folder, ReplayFormat.EXTENSION).getFirst();
	}

	private static List<Path> list(Path folder, String ext) {
		try (Stream<Path> files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().endsWith(ext)).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	/** A real mouse click on the button whose label has the given translation key, as a player would click it. */
	private static void clickButton(ClientGameTestContext ctx, String key) {
		double[] pos = ctx.computeOnClient(mc -> {
			for (var child : mc.gui.screen().children()) {
				if (child instanceof AbstractWidget widget
					&& widget.getMessage().getContents() instanceof TranslatableContents label
					&& label.getKey().equals(key)) {
					double scale = mc.getWindow().getGuiScale();
					return new double[] {(widget.getX() + widget.getWidth() / 2.0) * scale, (widget.getY() + widget.getHeight() / 2.0) * scale};
				}
			}
			return null;
		});
		check(pos != null, "no button labelled " + key + " on the current screen");
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTicks(1);
		ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
	}

	/**
	 * Reads the picture the window shows during a running export (what RenderResolution hands to the screen) and
	 * checks that the export image sits upright and centred where {@link RenderResolution#fit} puts it.
	 */
	private static void checkPreview(ClientGameTestContext ctx, String name, int exportWidth, int exportHeight) {
		Path file = ctx.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("preview " + name + ".png"));
		int[] window = ctx.computeOnClient(mc -> {
			var size = mc.getWindow().queryFramebufferSize();
			return new int[] {size.width(), size.height()};
		});
		ctx.runOnClient(mc -> {
			try {
				var field = RenderResolution.class.getDeclaredField("presentTarget");
				field.setAccessible(true);
				var target = (RenderTarget) field.get(null);
				Screenshot.takeScreenshot(target, image -> {
					try (image) {
						image.writeToFile(file);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				});
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		});
		ctx.waitTicks(10);
		BufferedImage picture;
		try {
			picture = ImageIO.read(file.toFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		var expected = RenderResolution.fit(exportWidth, exportHeight, window[0], window[1]);
		int[] box = contentBounds(picture);
		String found = box[0] + "," + box[1] + " " + (box[2] - box[0] + 1) + "x" + (box[3] - box[1] + 1);
		StreamerReplay.LOGGER.info("[gametest] preview {}: picture at {}, expected {},{} {}x{}", name, found,
			expected.x(), expected.y(), expected.width(), expected.height());
		check(Math.abs(box[0] - expected.x()) <= 3 && Math.abs(box[1] - expected.y()) <= 3
			&& Math.abs(box[2] - box[0] + 1 - expected.width()) <= 4 && Math.abs(box[3] - box[1] + 1 - expected.height()) <= 4,
			"the export preview (" + name + ") should show the picture at " + expected + " but it is at " + found);
	}

	/** The smallest rectangle {x0, y0, x1, y1} that contains every pixel brighter than near-black. */
	private static int[] contentBounds(BufferedImage image) {
		int x0 = image.getWidth();
		int y0 = image.getHeight();
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < image.getHeight(); y++) {
			for (int x = 0; x < image.getWidth(); x++) {
				if (luma(image.getRGB(x, y)) > 12) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		return new int[] {x0, y0, x1, y1};
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
