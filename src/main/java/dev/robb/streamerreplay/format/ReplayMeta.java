package dev.robb.streamerreplay.format;

/**
 * Descriptive metadata stored in every replay. Plain mutable bean so Gson can (de)serialise it without adapters.
 */
public final class ReplayMeta {
	public int formatVersion = ReplayFormat.FORMAT_VERSION;
	public String modVersion = "";
	public String minecraftVersion = "";
	public int protocolVersion;
	/** Epoch millis of the recording start. */
	public long recordedAt;
	public long durationMs;
	public long packetCount;
	public boolean singleplayer;
	/** World name (singleplayer) or server list name (multiplayer). */
	public String worldName = "";
	/** Only stored if the user enabled it; empty otherwise (privacy for streamers). */
	public String serverAddress = "";
	public String playerName = "";
	public String playerUuid = "";
	/** True if this replay was salvaged from a crashed/unfinished recording. */
	public boolean recovered;

	public ReplayMeta copy() {
		ReplayMeta copy = new ReplayMeta();
		copy.formatVersion = formatVersion;
		copy.modVersion = modVersion;
		copy.minecraftVersion = minecraftVersion;
		copy.protocolVersion = protocolVersion;
		copy.recordedAt = recordedAt;
		copy.durationMs = durationMs;
		copy.packetCount = packetCount;
		copy.singleplayer = singleplayer;
		copy.worldName = worldName;
		copy.serverAddress = serverAddress;
		copy.playerName = playerName;
		copy.playerUuid = playerUuid;
		copy.recovered = recovered;
		return copy;
	}
}
