package dev.robb.streamerreplay.replay;

import dev.robb.streamerreplay.StreamerReplay;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.PacketStreamReader;
import dev.robb.streamerreplay.format.ReplayArchive;
import dev.robb.streamerreplay.format.ReplayFormat;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.path.CameraPath;
import dev.robb.streamerreplay.path.CameraPathJson;
import dev.robb.streamerreplay.path.CameraState;
import dev.robb.streamerreplay.path.Keyframe;
import dev.robb.streamerreplay.ui.ReplayLoadingScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * One open replay: owns the file, the fake connection, the clock and the camera, and drives them once per
 * rendered frame from {@link ReplayManager#onFrame()}.
 *
 * <p>Seeking forward delivers packets as fast as a per-frame time budget allows. Seeking backwards is not
 * possible with a packet stream, so the world is rebuilt from the start and fast-forwarded to the target.
 */
public final class ReplaySession {
	private static final long FAST_FORWARD_BUDGET_NANOS = 25_000_000L;
	private static final long CATCH_UP_THRESHOLD_MS = 1500;

	private final Path file;
	private final ZipFile zip;
	private final ReplayMeta meta;
	private final List<Marker> markers;
	private final CameraPath path = new CameraPath();
	private final ReplayClock clock;
	private final ReplayCamera camera = new ReplayCamera();
	private final Screen parent;

	private ReplayPacketFilter filter;
	private ReplayChannel channel;
	private PacketStreamReader reader;
	private boolean hasFrame;
	private long deliveredUntil;
	private boolean pathDirty;
	private boolean closed;
	private boolean restarting;
	private @Nullable CameraState restorePose;
	private @Nullable Screen restoreScreen;
	private @Nullable PathPlayer pathPlayer;
	private long lastFrameNanos = -1;

	// Tick bookkeeping for the DeltaTracker mixin.
	private long ticksDone;
	private double lastTicks;
	private float partialTick;
	private float deltaTicks;

	private ReplaySession(Path file, ZipFile zip, ReplayMeta meta, List<Marker> markers, Screen parent) {
		this.file = file;
		this.zip = zip;
		this.meta = meta;
		this.markers = markers;
		this.parent = parent;
		this.clock = new ReplayClock(Math.max(1, meta.durationMs));
		this.clock.setPlaying(true); // opening a replay starts watching it right away
		loadPath();
	}

	static ReplaySession open(Path file, Screen parent) throws IOException {
		ZipFile zip = new ZipFile(file.toFile());
		try {
			ReplayMeta meta = ReplayArchive.readMeta(zip);
			if (meta.formatVersion > ReplayFormat.FORMAT_VERSION) {
				throw new IOException("This replay was made with a newer version of Streamer Replay");
			}
			return new ReplaySession(file, zip, meta, ReplayArchive.readMarkers(zip), parent);
		} catch (IOException | RuntimeException e) {
			zip.close();
			throw e;
		}
	}

	private Path pathSidecar() {
		return file.resolveSibling(file.getFileName() + ".paths");
	}

	private void loadPath() {
		try {
			String json = null;
			if (Files.exists(pathSidecar())) {
				json = Files.readString(pathSidecar()); // unsaved edits from a crashed session
				pathDirty = true;
			} else {
				byte[] stored = ReplayArchive.readEntry(zip, ReplayFormat.ENTRY_PATHS);
				if (stored != null) {
					json = new String(stored, StandardCharsets.UTF_8);
				}
			}
			if (json != null) {
				CameraPathJson.read(ReplayArchive.GSON, json, path);
			}
		} catch (IOException | RuntimeException e) {
			StreamerReplay.LOGGER.warn("Could not load camera path of {}", file.getFileName(), e);
		}
	}

	/** Builds a fresh connection and starts delivering from the beginning of the recording. */
	void connect() throws IOException {
		Minecraft mc = Minecraft.getInstance();
		if (reader != null) {
			reader.close();
		}
		reader = ReplayArchive.openPackets(zip);
		hasFrame = reader.next();
		deliveredUntil = 0;
		ticksDone = 0;
		lastTicks = 0;
		filter = new ReplayPacketFilter();
		channel = new ReplayChannel(filter);
		ClientHandshakePacketListenerImpl listener = new ClientHandshakePacketListenerImpl(
			channel.connection(), mc, null, parent, false, null, status -> {
			}, new LevelLoadTracker(), null);
		channel.connection().initiateServerboundPlayConnection("replay.local", 0, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, listener, false);
		mc.gui.setScreen(new ReplayLoadingScreen(this));
	}

	// ------------------------------------------------------------------------------------------------ frame loop

	/** Render thread, once per frame before the game ticks. */
	void onFrame(Minecraft mc) {
		if (closed) {
			return;
		}
		long now = System.nanoTime();
		double dtSeconds = lastFrameNanos < 0 ? 0 : Math.min(0.25, (now - lastFrameNanos) / 1.0e9);
		lastFrameNanos = now;
		channel.pump();
		if (!channel.isOpen()) {
			ReplayManager.onSessionLost(this);
			return;
		}
		boolean worldVisible = isInWorld(mc) && !(mc.gui.screen() instanceof LevelLoadingScreen);
		if (pathPlayer != null) {
			pathPlayer.update(this, dtSeconds * 1000.0);
		} else if (worldVisible) {
			clock.update(now);
		} else {
			// While the world is being built the clock follows the data: it neither runs away nor stalls on the
			// gap before the next recorded packet (e.g. login at 0 ms, first chunks at 600 ms).
			clock.hold(now);
			if (hasFrame && reader.time() > clock.timeMs() && !channel.awaitingProtocolSwitch()) {
				clock.setTime(reader.time());
			}
		}
		if (isInWorld(mc) && clock.timeMs() < deliveredUntil - 1) {
			restart();
			return;
		}
		deliver((long) Math.floor(clock.timeMs()), pathPlayer != null && pathPlayer.isExact() && !pathPlayer.isWaitingForSeek());
		if (worldVisible) {
			enforceSpectator(mc);
			if (restorePose != null) {
				camera.setPose(restorePose);
				camera.moveToPose(restorePose);
				restorePose = null;
			}
			if (restoreScreen != null) {
				Screen screen = restoreScreen;
				restoreScreen = null;
				if (mc.gui.screen() == null) {
					mc.gui.setScreen(screen); // e.g. the editor that was open when seeking backwards
				}
			}
			camera.update(mc, dtSeconds);
		}
	}

	/** Delivers all frames up to {@code untilMs}, within a time budget unless {@code exact} is requested. */
	private void deliver(long untilMs, boolean exact) {
		long deadline = System.nanoTime() + FAST_FORWARD_BUDGET_NANOS;
		filter.setSkipEffects(isFastForwarding());
		try {
			while (hasFrame && reader.time() <= untilMs) {
				if (channel.awaitingProtocolSwitch() || !channel.isOpen()) {
					return; // the client handles the switch in its next tick
				}
				channel.deliver(reader.data(), reader.length());
				deliveredUntil = reader.time();
				hasFrame = reader.next();
				if (!exact && System.nanoTime() > deadline) {
					return;
				}
			}
		} catch (IOException e) {
			StreamerReplay.LOGGER.error("Error reading replay {}", file.getFileName(), e);
			hasFrame = false;
		} finally {
			channel.pump();
		}
	}

	private void enforceSpectator(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.gameMode == null) {
			return;
		}
		if (mc.gameMode.getPlayerMode() != GameType.SPECTATOR) {
			mc.gameMode.setLocalMode(GameType.SPECTATOR);
		}
		player.getAbilities().mayfly = true;
		player.getAbilities().flying = true;
		player.getAbilities().invulnerable = true;
	}

	/** Rebuilds the world from the start and fast-forwards to the current clock time. */
	private void restart() {
		Minecraft mc = Minecraft.getInstance();
		restarting = true;
		try {
			if (camera.isInitialized()) {
				restorePose = camera.pose();
			}
			Screen current = mc.gui.screen();
			if (current != null && !(current instanceof ReplayLoadingScreen) && !(current instanceof LevelLoadingScreen)) {
				restoreScreen = current;
			}
			ReplayChannel old = channel;
			mc.disconnect(new ReplayLoadingScreen(this), true, false);
			old.close();
			connect();
		} catch (IOException e) {
			StreamerReplay.LOGGER.error("Could not restart replay", e);
			ReplayManager.exit();
		} finally {
			restarting = false;
		}
	}

	// ------------------------------------------------------------------------------------------------ ticks

	/** Called from the DeltaTracker mixin: how many client ticks to run this frame, derived from replay time. */
	public int advanceTicks() {
		double ticks = clock.timeMs() / 50.0;
		long whole = (long) Math.floor(ticks);
		long toRun = whole - ticksDone;
		if (toRun < 0) {
			toRun = 0; // time went backwards (restart): just re-anchor
		}
		ticksDone = whole;
		deltaTicks = (float) Math.max(0, ticks - lastTicks);
		lastTicks = ticks;
		partialTick = (float) (ticks - whole);
		return (int) Math.min(10, toRun);
	}

	public float partialTick() {
		return partialTick;
	}

	public float deltaTicks() {
		return deltaTicks;
	}

	// ------------------------------------------------------------------------------------------------ state queries

	public boolean isInWorld(Minecraft mc) {
		ClientPacketListener connection = mc.getConnection();
		return !restarting && mc.level != null && mc.player != null && connection != null && connection.getConnection() == channel.connection();
	}

	/** True while the delivered data lags far behind the clock (after a seek). */
	public boolean isFastForwarding() {
		return hasFrame && reader.time() < clock.timeMs() - CATCH_UP_THRESHOLD_MS;
	}

	/** True when everything up to the current time was delivered and the world is visible. */
	public boolean isCaughtUp(Minecraft mc) {
		return isInWorld(mc) && !(mc.gui.screen() instanceof LevelLoadingScreen) && !channel.awaitingProtocolSwitch()
			&& (!hasFrame || reader.time() > clock.timeMs());
	}

	/** 0..1 progress of a running fast-forward, or 1 if not seeking. */
	public float catchUpProgress() {
		double target = clock.timeMs();
		return target <= 0 ? 1 : (float) Math.min(1.0, deliveredUntil / target);
	}

	/** One-line state summary for logs and bug reports. */
	public String debugState(Minecraft mc) {
		return "time=" + (long) clock.timeMs() + " playing=" + clock.isPlaying() + " delivered=" + deliveredUntil
			+ " next=" + (hasFrame ? reader.time() : -1) + " inWorld=" + isInWorld(mc)
			+ " awaitingSwitch=" + (channel != null && channel.awaitingProtocolSwitch()) + " open=" + (channel != null && channel.isOpen())
			+ " screen=" + (mc.gui.screen() == null ? "none" : mc.gui.screen().getClass().getSimpleName())
			+ " level=" + (mc.level != null) + " player=" + (mc.player != null) + " listener=" + (mc.getConnection() != null);
	}

	public ReplayChannel channel() {
		return channel;
	}

	public ReplayClock clock() {
		return clock;
	}

	public ReplayCamera camera() {
		return camera;
	}

	public ReplayMeta meta() {
		return meta;
	}

	public List<Marker> markers() {
		return markers;
	}

	public CameraPath path() {
		return path;
	}

	public Path file() {
		return file;
	}

	// ------------------------------------------------------------------------------------------------ controls

	public void togglePlaying() {
		stopPath();
		clock.setPlaying(!clock.isPlaying());
	}

	public void seek(double timeMs) {
		stopPath();
		clock.setTime(timeMs);
	}

	/** Starts playing the camera path in real time (preview). */
	public void playPath() {
		if (!path.isPlayable()) {
			return;
		}
		clock.setPlaying(false);
		pathPlayer = new PathPlayer(path, false);
		pathPlayer.begin(this);
	}

	/** Starts deterministic path playback driven by an external frame clock (video export). */
	public PathPlayer playPathForExport() {
		clock.setPlaying(false);
		pathPlayer = new PathPlayer(path, true);
		pathPlayer.begin(this);
		return pathPlayer;
	}

	public void stopPath() {
		if (pathPlayer != null) {
			pathPlayer = null;
			camera.setPathPose(null);
		}
	}

	public boolean isPlayingPath() {
		return pathPlayer != null;
	}

	public @Nullable PathPlayer pathPlayer() {
		return pathPlayer;
	}

	void onPathFinished() {
		pathPlayer = null;
		camera.setPathPose(null);
	}

	/** Adds a keyframe at the current replay time from the current camera. Returns its index. */
	public int addKeyframeAtCamera() {
		stopPath();
		int index = path.addAt((long) clock.timeMs(), camera.pose());
		markPathChanged();
		return index;
	}

	/** Overwrites the camera of a keyframe with the current camera. */
	public void updateKeyframe(int index) {
		if (index >= 0 && index < path.size()) {
			path.replaceCamera(index, camera.pose());
			markPathChanged();
		}
	}

	public void removeKeyframe(int index) {
		if (index >= 0 && index < path.size()) {
			stopPath();
			path.remove(index);
			markPathChanged();
		}
	}

	public void clearPath() {
		stopPath();
		path.clear();
		markPathChanged();
	}

	/** Seeks to a keyframe and places the camera there. */
	public void jumpToKeyframe(int index) {
		if (index < 0 || index >= path.size()) {
			return;
		}
		Keyframe keyframe = path.get(index);
		seek(keyframe.replayTimeMs());
		clock.setPlaying(false);
		camera.setPose(keyframe.camera());
		camera.moveToPose(keyframe.camera()); // survives a world rebuild via restorePose
	}

	/** Marks the camera path as edited; it is autosaved next to the replay and merged into it on exit. */
	public void markPathChanged() {
		pathDirty = true;
		try {
			Files.writeString(pathSidecar(), CameraPathJson.write(ReplayArchive.GSON, path));
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not autosave camera path", e);
		}
	}

	/** Releases files. Must be called after the world was left. */
	void close() {
		if (closed) {
			return;
		}
		closed = true;
		pathPlayer = null;
		if (channel != null) {
			channel.close();
		}
		try {
			if (reader != null) {
				reader.close();
			}
			zip.close();
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Error closing replay", e);
		}
		if (pathDirty) {
			savePathIntoArchive();
		}
	}

	private void savePathIntoArchive() {
		try {
			Map<String, byte[]> entries = new HashMap<>();
			entries.put(ReplayFormat.ENTRY_PATHS, path.isEmpty() ? null : CameraPathJson.write(ReplayArchive.GSON, path).getBytes(StandardCharsets.UTF_8));
			ReplayArchive.updateEntries(file, entries);
			Files.deleteIfExists(pathSidecar());
		} catch (IOException e) {
			StreamerReplay.LOGGER.warn("Could not save camera path into {}; it stays in the sidecar file", file.getFileName(), e);
		}
	}

	public boolean isClosed() {
		return closed;
	}
}
