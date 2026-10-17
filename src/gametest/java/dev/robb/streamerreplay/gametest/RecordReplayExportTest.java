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
	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.waitForScreen(TitleScreen.class);
		ctx.takeScreenshot("01_title_screen");

		Vec3 base;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
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
			sp.getServer().runCommand(String.format("summon minecraft:armor_stand %d %d %d", x - 2, y, z + 8));
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
		}
		// leaving the world stops and saves the recording in the background
		Path replay = waitForReplay(ctx);
		StreamerReplay.LOGGER.info("[gametest] recorded {}", replay);

		ctx.setScreen(() -> new ReplayBrowserScreen(new TitleScreen()));
		ctx.waitTicks(40);
		ctx.takeScreenshot("03_replay_browser");

		ctx.setScreen(() -> new ReplayConfigScreen(new TitleScreen()));
		ctx.waitTicks(5);
		ctx.takeScreenshot("04_settings");

		ctx.runOnClient(mc -> ReplayManager.open(replay, new ReplayBrowserScreen(new TitleScreen())));
		ctx.waitFor(mc -> {
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
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.2));
		waitCaughtUp(ctx);
		ctx.waitTicks(10);
		ctx.takeScreenshot("07_after_seek_back");

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
		ctx.runOnClient(mc -> mc.gui.setScreen(new ReplayEditorScreen(ReplayManager.session())));
		ctx.waitTicks(10);
		ctx.takeScreenshot("08_editor_with_path");
		int keyframes = ctx.computeOnClient(mc -> ReplayManager.session().path().size());
		check(keyframes == 3, "expected 3 keyframes, got " + keyframes);

		ctx.runOnClient(mc -> mc.gui.setScreen(new ExportScreen(ReplayManager.session(), mc.gui.screen())));
		ctx.waitTicks(40);
		ctx.takeScreenshot("09_export_settings");

		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("gametest.mp4"));
		ctx.runOnClient(mc -> {
			ReplayConfig cfg = ReplayConfig.get();
			VideoExporter.start(ReplayManager.session(), new ExportSettings(video, cfg.ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0), true);
			mc.gui.setScreen(new dev.robb.streamerreplay.ui.ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session())));
		});
		ctx.waitTicks(20);
		ctx.takeScreenshot("10_export_progress");
		ctx.waitFor(mc -> {
			VideoExporter e = VideoExporter.current();
			return e != null && (e.state() == VideoExporter.State.DONE || e.state() == VideoExporter.State.FAILED || e.state() == VideoExporter.State.CANCELLED);
		}, 20 * 600);
		ctx.takeScreenshot("11_export_done");
		String error = ctx.computeOnClient(mc -> VideoExporter.current().state() + " " + VideoExporter.current().error()
			+ " frames=" + VideoExporter.current().framesDone() + "/" + VideoExporter.current().totalFrames());
		StreamerReplay.LOGGER.info("[gametest] export result: {}", error);
		check(error.startsWith("DONE"), "export failed: " + error);
		try {
			check(Files.size(video) > 10_000, "video too small");
		} catch (IOException e) {
			throw new AssertionError(e);
		}

		ctx.runOnClient(mc -> ReplayManager.exit());
		ctx.waitForScreen(ReplayBrowserScreen.class);
		ctx.waitTicks(20);
		ctx.takeScreenshot("12_back_in_browser");
		StreamerReplay.LOGGER.info("[gametest] ALL CHECKS PASSED");
	}

	private static void key(ReplaySession s, CameraState pose) {
		s.camera().setPose(pose);
		s.camera().moveToPose(pose);
		s.addKeyframeAtCamera();
	}

	private static void waitCaughtUp(ClientGameTestContext ctx) {
		ctx.waitTicks(2);
		ctx.waitFor(mc -> ReplayManager.session() != null && ReplayManager.session().isCaughtUp(mc), 20 * 60);
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
