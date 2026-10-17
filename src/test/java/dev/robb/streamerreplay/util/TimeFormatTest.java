package dev.robb.streamerreplay.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeFormatTest {
	@Test
	void formatsClock() {
		assertEquals("0:00", TimeFormat.clock(-5));
		assertEquals("0:05", TimeFormat.clock(5_999));
		assertEquals("1:05", TimeFormat.clock(65_000));
		assertEquals("1:02:03", TimeFormat.clock(3_723_000));
		assertEquals("1:05.2", TimeFormat.precise(65_250));
	}

	@Test
	void formatsBytes() {
		assertEquals("512 B", TimeFormat.bytes(512));
		assertEquals("1.5 KB", TimeFormat.bytes(1536));
		assertEquals("250 MB", TimeFormat.bytes(250L * 1024 * 1024));
	}
}
