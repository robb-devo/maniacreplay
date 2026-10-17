package dev.robb.streamerreplay.ui.browser;

import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.library.ReplayInfo;
import dev.robb.streamerreplay.ui.Theme;
import dev.robb.streamerreplay.util.TimeFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.function.Consumer;

/** Scrollable list of replays with thumbnail, name and the most useful facts. */
public final class ReplayBrowserList extends ObjectSelectionList<ReplayBrowserList.Entry> {
	static final int THUMB_W = 64;
	static final int THUMB_H = 36;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault());

	private ThumbnailCache thumbnails = new ThumbnailCache();
	private final Consumer<ReplayInfo> onOpen;

	public ReplayBrowserList(Minecraft minecraft, int width, int height, int y, Consumer<ReplayInfo> onOpen) {
		super(minecraft, width, height, y, THUMB_H + 8);
		this.onOpen = onOpen;
	}

	public @Nullable ReplayInfo selectedInfo() {
		Entry selected = getSelected();
		return selected == null ? null : selected.info;
	}

	/** Frees the thumbnail textures (screen closed or covered by another screen). */
	public void close() {
		thumbnails.close();
	}

	/** Thumbnails are re-uploaded lazily after {@link #close()}. */
	public void reopen() {
		thumbnails = new ThumbnailCache();
	}

	public void setReplays(List<ReplayInfo> replays, @Nullable ReplayInfo keepSelected) {
		clearEntries();
		Entry select = null;
		for (ReplayInfo info : replays) {
			Entry entry = new Entry(info);
			addEntry(entry);
			if (keepSelected != null && info.file().equals(keepSelected.file())) {
				select = entry;
			}
		}
		setSelected(select);
		setScrollAmount(scrollAmount());
	}

	@Override
	public int getRowWidth() {
		return Math.min(360, width - 20);
	}

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		final ReplayInfo info;
		private final String subtitle;
		private final String details;

		Entry(ReplayInfo info) {
			this.info = info;
			ReplayMeta meta = info.meta();
			if (meta == null) {
				subtitle = "";
				details = "";
			} else {
				String date = DATE.format(Instant.ofEpochMilli(meta.recordedAt > 0 ? meta.recordedAt : info.modifiedMs()));
				subtitle = date + "  \u00B7  " + TimeFormat.clock(meta.durationMs) + (meta.worldName.isEmpty() ? "" : "  \u00B7  " + meta.worldName);
				details = TimeFormat.bytes(info.sizeBytes())
					+ (info.markers() > 0 ? "  \u00B7  " + Component.translatable("streamerreplay.browser.markers", info.markers()).getString() : "")
					+ "  \u00B7  " + Component.translatable(meta.singleplayer ? "streamerreplay.browser.singleplayer" : "streamerreplay.browser.multiplayer").getString()
					+ "  \u00B7  " + meta.minecraftVersion;
			}
		}

		@Override
		public void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = minecraft.font;
			int x = getContentX();
			int y = getContentY();
			Identifier thumb = thumbnails.get(info.file().toString(), info.thumbnailPng());
			if (thumb != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, thumb, x, y, 0, 0, THUMB_W, THUMB_H, 384, 216, 384, 216);
			} else {
				g.fill(x, y, x + THUMB_W, y + THUMB_H, Theme.TRACK);
				g.centeredText(font, "\u25B6", x + THUMB_W / 2, y + THUMB_H / 2 - 4, Theme.TEXT_FAINT);
			}
			if (hovered && info.readable()) {
				g.fill(x, y, x + THUMB_W, y + THUMB_H, 0x60000000);
				g.centeredText(font, "\u25B6", x + THUMB_W / 2, y + THUMB_H / 2 - 4, Theme.TEXT);
			}
			int tx = x + THUMB_W + 6;
			int maxWidth = getContentRight() - tx;
			g.text(font, font.plainSubstrByWidth(info.name(), maxWidth), tx, y + 1, Theme.TEXT, false);
			if (!info.readable()) {
				g.text(font, font.plainSubstrByWidth(Component.translatable("streamerreplay.browser.unreadable", info.error()).getString(), maxWidth), tx, y + 13, Theme.ERROR, false);
				return;
			}
			g.text(font, font.plainSubstrByWidth(subtitle, maxWidth), tx, y + 13, Theme.TEXT_DIM, false);
			String line = details;
			int color = Theme.TEXT_FAINT;
			g.text(font, font.plainSubstrByWidth(line, maxWidth), tx, y + 25, color, false);
			if (info.meta().recovered) {
				Component recovered = Component.translatable("streamerreplay.browser.recovered");
				g.text(font, recovered, getContentRight() - font.width(recovered), y + 1, Theme.MARKER, false);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			boolean onThumb = event.x() >= getContentX() && event.x() < getContentX() + THUMB_W;
			if ((doubleClick || onThumb) && info.readable()) {
				onOpen.accept(info);
				return true;
			}
			return super.mouseClicked(event, doubleClick);
		}

		@Override
		public Component getNarration() {
			return Component.translatable("narrator.select", info.name());
		}
	}
}
