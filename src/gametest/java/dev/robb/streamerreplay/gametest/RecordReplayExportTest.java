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
	/**
	 * The gametest framework only runs test code on frames that tick. A finished export leaves the replay paused
	 * (no ticks), so while this flag is set a per-frame screen callback nudges the replay clock.
	 */
	private static volatile boolean KEEP_TICKING;

	private static void installTickNudge() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
			net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.beforeExtract(screen).register((sc, g, mx, my, a) -> {
				ReplaySession s = ReplayManager.session();
				if (KEEP_TICKING && !VideoExporter.isExporting() && s != null && !s.clock().isPlaying()) {
					s.clock().setSpeed(0.25);
					s.clock().setPlaying(true);
				}
			}));
	}
	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.waitForScreen(TitleScreen.class);
		ctx.runOnClient(mc -> installTickNudge());
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
		ctx.runOnClient(mc -> ReplayManager.session().seek(duration * 0.2));
		waitCaughtUp(ctx);
		ctx.waitTicks(10);
		ctx.takeScreenshot("07_after_seek_back");

		// follow: aim the free camera at the pig, pick it like a left click would, then leave again.
		// Near the end of the recording the pig is guaranteed to exist (it was summoned early on).
		// Slow playback keeps the game ticking (a replay that reached its end pauses, and paused replays do not tick).
		ctx.runOnClient(mc -> {
			ReplayManager.session().clock().setSpeed(0.25);
			ReplayManager.session().seek(duration * 0.85);
		});
		waitCaughtUp(ctx);
		ctx.waitTicks(5);
		boolean picked = ctx.computeOnClient(mc -> {
			ReplaySession s = ReplayManager.session();
			net.minecraft.world.entity.Entity pig = null;
			for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
				if (e.getType() == net.minecraft.world.entity.EntityTypes.PIG) {
					pig = e;
				}
			}
			if (pig == null) {
				StreamerReplay.LOGGER.error("[gametest] no pig in the replay world");
				return false;
			}
			Vec3 target = pig.getBoundingBox().getCenter();
			Vec3 eye = target.add(0.3, 4, 0.3); // from above: nothing can stand between camera and pig
			Vec3 d = target.subtract(eye);
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
			s.camera().setPose(new CameraState(eye.x, eye.y, eye.z, yaw, pitch, 0, 70));
			net.minecraft.world.entity.Entity hit = dev.robb.streamerreplay.replay.EntityPicker.pick(mc, mc.player, s.camera());
			if (hit != pig) {
				StreamerReplay.LOGGER.error("[gametest] pick hit {} instead of {}", hit, pig);
				return false;
			}
			s.camera().follow(mc, pig);
			return true;
		});
		check(picked, "camera ray should pick the recorded pig");
		ctx.waitTicks(20);
		check(ctx.computeOnClient(mc -> mc.getCameraEntity() != mc.player), "should be following the pig");
		ctx.takeScreenshot("07b_following_entity");
		ctx.runOnClient(mc -> ReplayManager.session().camera().stopFollowing(mc));
		ctx.waitTicks(2);
		check(ctx.computeOnClient(mc -> mc.getCameraEntity() == mc.player), "should be back in free camera");

		// Esc in a replay opens the replay menu instead of the vanilla pause menu
		ctx.runOnClient(mc -> mc.pauseGame(false));
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> mc.gui.screen() != null
			&& mc.gui.screen().getClass().getSimpleName().equals("ReplayPauseScreen")), "Esc should open the replay menu");
		ctx.takeScreenshot("07c_replay_menu");
		ctx.runOnClient(mc -> {
			mc.gui.setScreen(null);
			ReplayManager.session().clock().setSpeed(1);
		});
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
		StreamerReplay.LOGGER.info("[gametest] path overlay: {}", (Object) ctx.<String, RuntimeException>computeOnClient(mc ->
			dev.robb.streamerreplay.render.PathPreviewRenderer.describeProjection(ReplayManager.session(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight())));
		int keyframes = ctx.computeOnClient(mc -> ReplayManager.session().path().size());
		check(keyframes == 3, "expected 3 keyframes, got " + keyframes);

		ctx.runOnClient(mc -> mc.gui.setScreen(new ExportScreen(ReplayManager.session(), mc.gui.screen())));
		ctx.waitTicks(40);
		ctx.takeScreenshot("09_export_settings");

		Path video = ctx.computeOnClient(mc -> StreamerReplay.videoFolder().resolve("gametest.mp4"));
		ctx.runOnClient(mc -> {
			ReplayConfig cfg = ReplayConfig.get();
			VideoExporter.start(ReplayManager.session(), new ExportSettings(video, cfg.ffmpegPath, 30, ReplayConfig.ExportQuality.FAST, 0, 0), true);
			var progress = new dev.robb.streamerreplay.ui.ExportProgressScreen(new ReplayEditorScreen(ReplayManager.session()));
			mc.gui.setScreen(progress);
			KEEP_TICKING = true;
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

		KEEP_TICKING = false;
		ctx.runOnClient(mc -> ReplayManager.exit());
		ctx.waitForScreen(ReplayBrowserScreen.class);
		ctx.waitTicks(20);
		ctx.takeScreenshot("12_back_in_browser");
		ctx.setScreen(TitleScreen::new);

		multiplayer(ctx);

		ctx.setScreen(TitleScreen::new);
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
