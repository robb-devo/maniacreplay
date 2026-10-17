package dev.robb.streamerreplay.path;

/**
 * A complete camera pose: eye position, rotation in degrees (Minecraft conventions: yaw around Y, pitch
 * positive = looking down) plus roll and field of view in degrees.
 */
public record CameraState(double x, double y, double z, float yaw, float pitch, float roll, float fov) {
	public static final float DEFAULT_FOV = 70.0F;

	public CameraState withPosition(double nx, double ny, double nz) {
		return new CameraState(nx, ny, nz, yaw, pitch, roll, fov);
	}
}
