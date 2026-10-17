package dev.robb.streamerreplay.replay;

import dev.robb.streamerreplay.path.CameraPath;
import net.minecraft.client.Minecraft;

/**
 * Plays a {@link CameraPath}: moves path time forward, sets the replay clock and camera pose from the path.
 *
 * <p>In preview mode path time follows real time. In export mode ({@code exact}) the exporter advances it one
 * video frame at a time and every packet up to the frame time is delivered before the frame renders.
 */
public final class PathPlayer {
	private final CameraPath path;
	private final boolean exact;
	private double pathTime;
	private boolean waitingForSeek;
	private boolean finished;

	PathPlayer(CameraPath path, boolean exact) {
		this.path = path;
		this.exact = exact;
	}

	void begin(ReplaySession session) {
		pathTime = path.startPathTime();
		CameraPath.Sample first = path.sample(pathTime);
		session.clock().setTime(first.replayTimeMs());
		session.camera().setPathPose(first.camera());
		waitingForSeek = true;
	}

	/** Preview mode: called every frame with the real frame time. */
	void update(ReplaySession session, double dtMs) {
		if (waitingForSeek) {
			if (!session.isCaughtUp(Minecraft.getInstance())) {
				return;
			}
			waitingForSeek = false;
		}
		if (!exact) {
			advance(session, dtMs);
		}
	}

	/** Export mode: moves exactly one step. Returns false once the end was passed. */
	public boolean step(ReplaySession session, double stepMs) {
		if (finished) {
			return false;
		}
		advance(session, stepMs);
		return !finished;
	}

	private void advance(ReplaySession session, double dtMs) {
		pathTime += dtMs;
		long end = path.startPathTime() + path.durationMs();
		if (pathTime > end) {
			pathTime = end;
			finished = true;
		}
		CameraPath.Sample sample = path.sample(pathTime);
		session.clock().setTime(sample.replayTimeMs());
		session.camera().setPathPose(sample.camera());
		if (finished && !exact) {
			session.onPathFinished();
		}
	}

	public boolean isWaitingForSeek() {
		return waitingForSeek;
	}

	public boolean isExact() {
		return exact;
	}

	public boolean isFinished() {
		return finished;
	}

	/** Progress 0..1 along the path. */
	public float progress() {
		long duration = path.durationMs();
		return duration <= 0 ? 1 : (float) ((pathTime - path.startPathTime()) / duration);
	}

	public double pathTime() {
		return pathTime;
	}
}
