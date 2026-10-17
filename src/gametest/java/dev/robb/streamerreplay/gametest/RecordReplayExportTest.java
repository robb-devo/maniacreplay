package dev.robb.streamerreplay.gametest;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.record.RecordingManager;
import dev.robb.streamerreplay.render.ExportSettings;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.ExportScreen;
import dev.robb.streamerreplay.ui.ReplayBrowserScreen;
import dev.robb.streamerreplay.ui.ReplayConfigScreen;
import dev.robb.streamerreplay.ui.ReplayEditorScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
		java.util.concurrent.CompletableFuture<Void> reload = ctx.computeOnClient(mc -> {
			mc.getLanguageManager().setSelected(code);
			mc.options.languageCode = code;
			return mc.reloadResourcePacks();
		});
		ctx.waitFor(mc -> reload.isDone(), 20 * 120);
		ctx.waitForScreen(TitleScreen.class);
		ctx.waitTicks(20);
		String sample = ctx.computeOnClient(mc -> net.minecraft.network.chat.Component.translatable("streamerreplay.browser.watch").getString());
		StreamerReplay.LOGGER.info("[gametest] language {} active, sample label: {}", code, sample);
	}

	/**
	 * Fails if a button or slider label is wider than its widget (vanilla would scroll it, which looks broken).
	 * 8 px are reserved for the button border.
	 */
	private static void checkLabelsFit(ClientGameTestContext ctx, String where) {
		String problems = ctx.computeOnClient(mc -> {
			StringBuilder sb = new StringBuilder();
			var all = net.fabricmc.fabric.api.client.screen.v1.Screens.getWidgets(mc.gui.screen());
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
				if (widget instanceof net.minecraft.client.gui.components.AbstractButton
					|| widget instanceof net.minecraft.client.gui.components.AbstractSliderButton) {
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
		if (MODE.equals("seekbench")) {
			seekBenchmark(ctx);
			return;
		}
		ctx.waitForScreen(TitleScreen.class);
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
				sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %d %.2f %.1f 10",
					x + Math.cos(angle) * 3, y, z + Math.sin(angle) * 3, (float) Math.toDegrees(angle) + 90));
				ctx.waitTicks(10);
				if (i == 6) {
					ctx.runOnClient(mc -> RecordingManager.addMarker());
				}
			}
			sp.getServer().runCommand(String.format("setblock %d %d %d minecraft:emerald_block", x, y + 3, z + 3));
			ctx.waitTicks(40);
			// stop with the key, press it again: the toast must explain how to record (joining mid-session is not supported)
			ctx.runOnClient(dev.robb.streamerreplay.input.ReplayKeys::stopRecordingPressed);
			ctx.waitFor(mc -> !RecordingManager.isRecording(), 100);
			ctx.waitTicks(10);
			ctx.runOnClient(mc -> mc.gui.toastManager().clear());
			ctx.runOnClient(dev.robb.streamerreplay.input.ReplayKeys::stopRecordingPressed);
			ctx.waitTicks(15);
			String hint = ctx.computeOnClient(mc -> {
				var toast = mc.gui.toastManager().getToast(net.minecraft.client.gui.components.toasts.SystemToast.class, dev.robb.streamerreplay.ui.Notifications.RECORDING);
				var why = RecordingManager.notRecordingHint(mc);
				return (toast == null ? "no toast" : "toast") + " " + (why == null ? "-" : ((net.minecraft.network.chat.contents.TranslatableContents) why.getContents()).getKey());
			});
			StreamerReplay.LOGGER.info("[gametest] not-recording hint: {}", hint);
			check(hint.equals("toast streamerreplay.toast.not_recording.rejoin"), "pressing stop without a recording should explain how to record: " + hint);
			ctx.takeScreenshot("02b_not_recording_hint");
		}
		// leaving the world stops and saves the recording in the background
		Path recorded = waitForReplay(ctx);
		StreamerReplay.LOGGER.info("[gametest] recorded {}", recorded);
		check(recorded.getFileName().toString().contains(WORLD_NAME), "file name should keep the world name with umlauts: " + recorded);
		check(ctx.computeOnClient(mc -> dev.robb.streamerreplay.library.ReplayLibrary.scan().stream()
			.anyMatch(i -> i.meta() != null && WORLD_NAME.equals(i.meta().worldName))), "world name with umlauts should survive in the metadata");
		// rename to a name full of non-ASCII characters; the rest of the test uses the renamed file
		Path replay;
		try {
			replay = dev.robb.streamerreplay.library.ReplayLibrary.rename(recorded, RENAMED);
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
			var info = dev.robb.streamerreplay.library.ReplayLibrary.scan().getFirst();
			mc.gui.setScreen(new dev.robb.streamerreplay.ui.RenameReplayScreen(mc.gui.screen(), info, () -> { }));
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
			dev.robb.streamerreplay.render.PathPreviewRenderer.describeProjection(ReplayManager.session(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())));
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
			var progress = new dev.robb.streamerreplay.ui.ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session()));
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
		cleanFramesExport(ctx, b);

		ctx.runOnClient(mc -> ReplayManager.exit());
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
		java.util.Properties props = new java.util.Properties();
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
			}
		}
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == before + 1
			&& list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 30);
		Path mp = ctx.computeOnClient(mc -> dev.robb.streamerreplay.library.ReplayLibrary.scan().stream()
			.filter(i -> i.meta() != null && !i.meta().singleplayer).map(dev.robb.streamerreplay.library.ReplayInfo::file)
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
		ctx.runOnClient(mc -> {
			ReplayManager.session().clock().setSpeed(0.1);
			// 1.5 s of replay at 0.1x leaves 30 game ticks before the end; the checks below need fewer.
			ReplayManager.session().seek(ReplayManager.session().clock().durationMs() - 1500);
		});
		waitCaughtUp(ctx);
		ctx.waitTicks(4);
		boolean seesBlocks = ctx.computeOnClient(mc -> {
			Vec3 p = mc.player.position();
			var pos = net.minecraft.core.BlockPos.containing(p.x + 4, p.y + 1, p.z + 4);
			for (int dx = -10; dx <= 10; dx++) {
				for (int dz = -10; dz <= 10; dz++) {
					for (int dy = -6; dy <= 6; dy++) {
						if (mc.level.getBlockState(pos.offset(dx, dy, dz)).is(net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK)) {
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
		sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "fill %d %d %d %d %d %d minecraft:gold_block",
			(int) p.x + 3, (int) p.y, (int) p.z + 3, (int) p.x + 5, (int) p.y + 3, (int) p.z + 5));
		for (int i = 0; i < 16; i++) {
			sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %d 10", p.x + Math.cos(i / 2.5) * 3, p.y, p.z + Math.sin(i / 2.5) * 3, i * 22));
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
		var info = ctx.computeOnClient(mc -> dev.robb.streamerreplay.library.ReplayLibrary.scan().getFirst());
		check(info.meta() != null && CRASH_WORLD.equals(info.meta().worldName), "metadata should survive the crash: " + info.meta());
		StreamerReplay.LOGGER.info("[gametest] recovered meta: world={} duration={} ms markers={}", info.meta().worldName, info.meta().durationMs, info.markers());
		check(info.markers() == 1, "the marker set before the crash should survive");
		try (var leftovers = Files.list(folder)) {
			java.util.List<String> names = leftovers.map(f -> f.getFileName().toString()).toList();
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
				sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "summon minecraft:%s %.1f %.1f %.1f", i % 2 == 0 ? "sheep" : "chicken",
					p.x + (i % 8) * 2 - 8, p.y, p.z + (i / 8) * 2 + 4));
			}
			for (int i = 0; i < 90; i++) {
				sp.getServer().runCommand(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %d 10", p.x + Math.cos(i / 6.0) * 12, p.y, p.z + Math.sin(i / 6.0) * 12, i * 4));
				ctx.waitTicks(20);
			}
		}
		ctx.waitFor(mc -> list(folder, ReplayFormat.EXTENSION).size() == 1 && list(folder, ReplayFormat.RECORDING_EXTENSION).isEmpty(), 20 * 60);
		Path replay = list(folder, ReplayFormat.EXTENSION).getFirst();
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
			waitCaughtUp(ctx);
			ctx.waitTicks(5);
			long start = System.nanoTime();
			ctx.runOnClient(mc -> ReplayManager.session().seek(duration * seek[1]));
			waitCaughtUp(ctx);
			long ms = (System.nanoTime() - start) / 1_000_000;
			results.append(String.format(java.util.Locale.ROOT, " %d%%->%d%%: %d ms;", (int) (seek[0] * 100), (int) (seek[1] * 100), ms));
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
		check(Math.abs(net.minecraft.util.Mth.wrapDegrees(yawAfter - yawBefore)) > 5, "mouse look should work while paused");

		CameraState before = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose());
		ctx.getInput().holdKeyFor(o -> o.keyUp, 15);
		ctx.waitTicks(5);
		CameraState after = ctx.computeOnClient(mc -> ReplayManager.session().camera().pose());
		double moved = Math.sqrt(Math.pow(after.x() - before.x(), 2) + Math.pow(after.z() - before.z(), 2));
		StreamerReplay.LOGGER.info("[gametest] paused flight: moved {} blocks", String.format(java.util.Locale.ROOT, "%.2f", moved));
		check(moved > 1, "W should move the camera while paused (moved " + moved + ")");

		// aim at the pig from above (nothing can stand in between), then a real left click
		boolean aimed = ctx.computeOnClient(mc -> {
			net.minecraft.world.entity.Entity pig = findPig(mc);
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
		ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.getCameraEntity() == findPig(mc)), "left click should follow the pig while paused");
		ctx.takeScreenshot("07b_following_entity");

		// onboarding toasts are not shown in a replay
		boolean toastsHidden = ctx.computeOnClient(mc -> {
			mc.gui.addSocialInteractionsToast();
			net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(),
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.UNSECURE_SERVER_WARNING,
				net.minecraft.network.chat.Component.literal("test"), null);
			return mc.gui.toastManager().getToast(net.minecraft.client.gui.components.toasts.TutorialToast.class,
				net.minecraft.client.gui.components.toasts.Toast.NO_TOKEN) == null
				&& mc.gui.toastManager().getToast(net.minecraft.client.gui.components.toasts.SystemToast.class,
				net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.UNSECURE_SERVER_WARNING) == null;
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

	private static net.minecraft.world.entity.@org.jspecify.annotations.Nullable Entity findPig(net.minecraft.client.Minecraft mc) {
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e.getType() == net.minecraft.world.entity.EntityTypes.PIG) {
				return e;
			}
		}
		return null;
	}

	/** Runs one export to completion and returns its final state line. */
	private static String export(ClientGameTestContext ctx, ExportSettings settings, Runnable whileRendering) {
		ctx.runOnClient(mc -> {
			VideoExporter.start(ReplayManager.session(), settings, true);
			mc.gui.setScreen(new dev.robb.streamerreplay.ui.ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session())));
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
		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Native 1080p.mp4"));
		String result = export(ctx, new ExportSettings(video, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 1920, 1080, true), () -> {
			int rendering = ctx.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width);
			StreamerReplay.LOGGER.info("[gametest] render target during 1080p export: {} wide", rendering);
			check(rendering == 1920, "the game should render at 1920 wide during the export, was " + rendering);
			ctx.takeScreenshot("10b_export_1080p_progress");
			ctx.getInput().resizeWindow(700, 520);
			ctx.waitTicks(20);
		});
		StreamerReplay.LOGGER.info("[gametest] 1080p export (window resized mid-way): {}", result);
		check(result.startsWith("DONE"), "1080p export failed: " + result);
		String probe = ffprobe(video);
		StreamerReplay.LOGGER.info("[gametest] ffprobe 1080p: {}", probe);
		check(probe.startsWith("h264,1920,1080"), "video should be native 1920x1080 H.264: " + probe);
		// sharpness: a native 1080p frame has much more fine detail than the same frame squeezed through 480p
		java.awt.image.BufferedImage nativeFrame = frame(video, "1", "");
		java.awt.image.BufferedImage via480 = frame(video, "1", "scale=854:480,scale=1920:1080");
		double sharp = laplacianVariance(nativeFrame);
		double soft = laplacianVariance(via480);
		double brightness = meanLuma(nativeFrame);
		StreamerReplay.LOGGER.info("[gametest] 1080p sharpness: native {} vs 480p-upscaled {} (ratio {}), mean luma {}",
			String.format("%.1f", sharp), String.format("%.1f", soft), String.format("%.2f", sharp / soft), String.format("%.1f", brightness));
		check(sharp > soft * 1.3, "the 1080p video should be clearly sharper than an upscaled 480p frame");
		check(brightness > 40, "the 1080p video should not be dark (sky/fog lost after the world rebuild?)");
		float sky = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().attributeProbe()
			.getValue(net.minecraft.world.attribute.EnvironmentAttributes.SKY_COLOR, 0).length());
		check(sky > 0.1f, "the sky colour must be known again after the export's world rebuild");
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(20);
		int[] after = ctx.computeOnClient(mc -> new int[] {mc.gameRenderer.mainRenderTarget().width, mc.getWindow().getWidth(), mc.getWindow().getGuiScaledWidth()});
		StreamerReplay.LOGGER.info("[gametest] after export: target {} window {} gui {}", after[0], after[1], after[2]);
		check(after[0] == 854 && after[1] == 854, "rendering should return to the window size after the export");
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
			mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES,
				net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.ALWAYS_ON);
		});
		Path plain = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Overlays on.mp4"));
		Path clean = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("Overlays hidden.mp4"));
		String r1 = export(ctx, new ExportSettings(plain, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0, false), () -> { });
		String r2 = export(ctx, new ExportSettings(clean, ReplayConfig.get().ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0, true), () -> { });
		ctx.runOnClient(mc -> mc.debugEntries.setStatus(net.minecraft.client.gui.components.debug.DebugScreenEntries.ENTITY_HITBOXES,
			net.minecraft.client.gui.components.debug.DebugScreenEntryStatus.NEVER));
		check(r1.startsWith("DONE") && r2.startsWith("DONE"), "clean-frame exports failed: " + r1 + " / " + r2);
		long changed = differingPixels(firstFrame(plain), firstFrame(clean));
		StreamerReplay.LOGGER.info("[gametest] clean frames: {} pixels differ between overlays on/hidden", changed);
		check(changed > 300, "hiding name tags and hitboxes should visibly change the frame (" + changed + " px)");
	}

	private static String ffprobe(Path video) {
		return run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", video.toString()).strip();
	}

	private static java.awt.image.BufferedImage firstFrame(Path video) {
		Path png = video.resolveSibling(video.getFileName() + ".frame0.png");
		run("ffmpeg", "-v", "error", "-y", "-i", video.toString(), "-frames:v", "1", png.toString());
		try {
			return javax.imageio.ImageIO.read(png.toFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static long differingPixels(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b) {
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
			String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			process.waitFor();
			return out;
		} catch (IOException e) {
			throw new AssertionError(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static java.awt.image.BufferedImage frame(Path video, String second, String filter) {
		Path png = video.resolveSibling(video.getFileName() + ".frame" + second + (filter.isEmpty() ? "" : "-filtered") + ".png");
		java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of("ffmpeg", "-v", "error", "-y", "-ss", second, "-i", video.toString(), "-frames:v", "1"));
		if (!filter.isEmpty()) {
			cmd.addAll(java.util.List.of("-vf", filter + ":flags=bicubic"));
		}
		cmd.add(png.toString());
		run(cmd.toArray(String[]::new));
		try {
			return javax.imageio.ImageIO.read(png.toFile());
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static double luma(int rgb) {
		return 0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255);
	}

	private static double meanLuma(java.awt.image.BufferedImage img) {
		double sum = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				sum += luma(img.getRGB(x, y));
			}
		}
		return sum / ((double) img.getWidth() * img.getHeight());
	}

	/** Variance of the 4-neighbour Laplacian: the standard "how much fine detail" measure. */
	private static double laplacianVariance(java.awt.image.BufferedImage img) {
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

	private static void waitOrDump(ClientGameTestContext ctx, String what, java.util.function.Predicate<net.minecraft.client.Minecraft> condition, int timeout) {
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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
