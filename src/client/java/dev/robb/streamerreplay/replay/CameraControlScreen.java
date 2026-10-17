package dev.robb.streamerreplay.replay;

/**
 * Marker for screens that keep the free camera controllable (WASD flight is read from the physical keyboard
 * because vanilla does not update key mappings while a screen is open).
 */
public interface CameraControlScreen {
	/** False while e.g. a text field has focus. */
	default boolean allowsCameraMovement() {
		return true;
	}
}
