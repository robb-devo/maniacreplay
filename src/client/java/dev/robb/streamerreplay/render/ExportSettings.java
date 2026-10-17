package dev.robb.streamerreplay.render;

import dev.robb.streamerreplay.config.ReplayConfig;

import java.nio.file.Path;

/**
 * Everything the exporter needs to know.
 *
 * @param outputWidth  target video width, or 0 to keep the window size
 * @param outputHeight target video height, or 0 to keep the window size
 * @param cleanFrames  leave name tags, block outline, hand and debug overlays out of the video
 */
public record ExportSettings(Path output, String ffmpeg, int fps, ReplayConfig.ExportQuality quality, int outputWidth, int outputHeight,
	boolean cleanFrames) {
}
