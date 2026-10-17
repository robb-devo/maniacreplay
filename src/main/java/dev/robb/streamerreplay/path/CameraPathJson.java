package dev.robb.streamerreplay.path;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/** Stable JSON representation of a {@link CameraPath} (independent of record field naming). */
public final class CameraPathJson {
	public int version = 1;
	public List<Key> keyframes = new ArrayList<>();

	public static String write(Gson gson, CameraPath path) {
		CameraPathJson json = new CameraPathJson();
		for (Keyframe k : path.keyframes()) {
			Key key = new Key();
			key.pathTime = k.pathTimeMs();
			key.replayTime = k.replayTimeMs();
			CameraState c = k.camera();
			key.x = c.x();
			key.y = c.y();
			key.z = c.z();
			key.yaw = c.yaw();
			key.pitch = c.pitch();
			key.roll = c.roll();
			key.fov = c.fov();
			json.keyframes.add(key);
		}
		return gson.toJson(json);
	}

	public static void read(Gson gson, String text, CameraPath into) {
		CameraPathJson json = gson.fromJson(text, CameraPathJson.class);
		List<Keyframe> list = new ArrayList<>();
		if (json != null && json.keyframes != null) {
			for (Key k : json.keyframes) {
				float fov = k.fov <= 0 ? CameraState.DEFAULT_FOV : k.fov;
				list.add(new Keyframe(k.pathTime, k.replayTime, new CameraState(k.x, k.y, k.z, k.yaw, k.pitch, k.roll, fov)));
			}
		}
		into.setAll(list);
	}

	public static final class Key {
		public long pathTime;
		public long replayTime;
		public double x;
		public double y;
		public double z;
		public float yaw;
		public float pitch;
		public float roll;
		public float fov;
	}
}
