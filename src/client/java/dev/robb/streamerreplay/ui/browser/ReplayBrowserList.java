package dev.robb.streamerreplay.ui.browser;

import dev.robb.streamerreplay.config.ReplayConfig;
import dev.robb.streamerreplay.format.Marker;
import dev.robb.streamerreplay.format.ReplayMeta;
import dev.robb.streamerreplay.library.ReplayInfo;
import dev.robb.streamerreplay.ui.GameLocale;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;

/** Scrollable list of replays with thumbnail, name, the most useful facts and clickable markers. */
public final class ReplayBrowserList extends ObjectSelectionList<ReplayBrowserList.Entry> {
	static final int THUMB_W = 64;
	static final int THUMB_H = 36;

	private ThumbnailCache thumbnails = new ThumbnailCache();
	private final Consumer<ReplayInfo> onOpen;
	private final ObjLongConsumer<ReplayInfo> onOpenAt;

	public ReplayBrowserList(Minecraft minecraft, int width, int height, int y, Consumer<ReplayInfo> onOpen, ObjLongConsumer<ReplayInfo> onOpenAt) {
		super(minecraft, width, height, y, THUMB_H + 8);
		this.onOpen = onOpen;
		this.onOpenAt = onOpenAt;
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
		Entry first = null;
		for (ReplayInfo info : replays) {
			Entry entry = new Entry(info);
			addEntry(entry);
			if (first == null && info.readable()) {
				first = entry;
			}
			if (keepSelected != null && info.file().equals(keepSelected.file())) {
				select = entry;
			}
		}
		// The newest replay is pre-selected, so "Watch" works right away (the list is sorted newest first).
		setSelected(select != null ? select : first);
		setScrollAmount(scrollAmount());
	}

	@Override
	public int getRowWidth() {
		return Math.min(360, width - 20);
	}

	private static final Component UNREADABLE = Component.translatable("streamerreplay.browser.unreadable");
	private static final int CHIP_HEIGHT = 11;

	/** Where a marker chip was drawn in the last frame, for clicks. */
	private record Chip(int x0, int x1, int y0, Marker marker) {
		boolean contains(double x, double y) {
			return x >= x0 && x < x1 && y >= y0 && y < y0 + CHIP_HEIGHT;
		}
	}

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		final ReplayInfo info;
		private final String subtitle;
		private final String details;
		private final List<Chip> chips = new ArrayList<>();

		Entry(ReplayInfo info) {
			this.info = info;
			ReplayMeta meta = info.meta();
			if (meta == null) {
				subtitle = "";
				details = "";
			} else {
				String date = GameLocale.dateFormat().format(Instant.ofEpochMilli(meta.recordedAt > 0 ? meta.recordedAt : info.modifiedMs()));
				subtitle = date + "  \u00B7  " + TimeFormat.clock(meta.durationMs) + (meta.worldName.isEmpty() ? "" : "  \u00B7  " + meta.worldName);
				details = GameLocale.bytes(info.sizeBytes())
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
				g.text(font, font.plainSubstrByWidth(UNREADABLE.getString(), maxWidth), tx, y + 13, Theme.ERROR, false);
				return;
			}
			g.text(font, font.plainSubstrByWidth(subtitle, maxWidth), tx, y + 13, Theme.TEXT_DIM, false);
			int detailsX = drawChips(g, font, tx, y + 24, getContentRight(), mouseX, mouseY);
			if (detailsX < getContentRight() - 30) {
				g.text(font, font.plainSubstrByWidth(details, getContentRight() - detailsX), detailsX, y + 25, Theme.TEXT_FAINT, false);
			}
			Component badge = info.meta().recovered ? Component.translatable("streamerreplay.browser.recovered")
				: ReplayConfig.get().cleanupDays > 0 && info.kept() ? Component.translatable("streamerreplay.browser.kept") : null;
			if (badge != null) {
				g.text(font, badge, getContentRight() - font.width(badge), y + 1, Theme.MARKER, false);
			}
		}

		/** One chip per marker ("\u2691 12:34"), as many as fit; returns where the rest of the line can start. */
		private int drawChips(GuiGraphicsExtractor g, Font font, int x, int y, int right, int mouseX, int mouseY) {
			chips.clear();
			List<Marker> markers = info.markerList();
			for (int i = 0; i < markers.size(); i++) {
				Marker marker = markers.get(i);
				String label = "\u2691 " + TimeFormat.clock(marker.timeMs());
				int w = font.width(label) + 6;
				int more = markers.size() - i - 1;
				int reserve = more > 0 ? font.width("+" + more) + 4 : 0;
				if (x + w + reserve > right) {
					String rest = "+" + (markers.size() - i);
					g.text(font, rest, x, y + 2, Theme.MARKER, false);
					return x + font.width(rest) + 8;
				}
				Chip chip = new Chip(x, x + w, y, marker);
				boolean hovered = chip.contains(mouseX, mouseY);
				g.fill(x, y, x + w, y + CHIP_HEIGHT, Theme.withAlpha(Theme.MARKER, hovered ? 0.45F : 0.16F));
				g.text(font, label, x + 3, y + 2, hovered ? Theme.TEXT : Theme.MARKER, false);
				if (hovered) {
					g.setTooltipForNextFrame(Component.translatable("streamerreplay.browser.marker_chip", marker.name(),
						TimeFormat.clock(marker.startMs())), mouseX, mouseY);
				}
				chips.add(chip);
				x += w + 3;
			}
			return markers.isEmpty() ? x : x + 5;
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			for (Chip chip : chips) {
				if (chip.contains(event.x(), event.y()) && info.readable()) {
					onOpenAt.accept(info, chip.marker().startMs());
					return true;
				}
			}
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
