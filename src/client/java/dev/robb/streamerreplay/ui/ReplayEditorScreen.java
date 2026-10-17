package dev.robb.streamerreplay.ui;

import dev.robb.streamerreplay.render.PathPreviewRenderer;
import dev.robb.streamerreplay.input.ReplayKeys;
import dev.robb.streamerreplay.path.CameraPath;
import dev.robb.streamerreplay.replay.CameraControlScreen;
import dev.robb.streamerreplay.replay.ReplayManager;
import dev.robb.streamerreplay.replay.ReplaySession;
import dev.robb.streamerreplay.ui.hud.ReplayHud;
import dev.robb.streamerreplay.ui.widget.TimelineWidget;
import dev.robb.streamerreplay.ui.widget.ValueSlider;
import dev.robb.streamerreplay.util.TimeFormat;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The replay editor: a bottom panel over the live world with transport controls, the timeline and the camera
 * path tools. The world keeps running behind it and the camera stays controllable (WASD, hold right mouse to look).
 */
public final class ReplayEditorScreen extends Screen implements CameraControlScreen {
	private static final double[] SPEEDS = {0.1, 0.25, 0.5, 1, 2, 4, 8, 16};
	private static final int PANEL_HEIGHT = 74;
	private static final double LOOK_SENSITIVITY = 0.6;

	private final ReplaySession session;
	private TimelineWidget timeline;
	private Button playButton;
	private Button removeButton;
	private Button prevButton;
	private Button nextButton;
	private Button previewButton;
	private Button clearButton;
	private Button exportButton;
	private ValueSlider fovSlider;
	private ValueSlider rollSlider;
	private int selected = -1;
	private boolean looking;

	public ReplayEditorScreen(ReplaySession session) {
		super(Component.translatable("streamerreplay.editor.title"));
		this.session = session;
	}

	@Override
	protected void init() {
		int top = height - PANEL_HEIGHT;
		int timeWidth = font.width("00:00:00 / 00:00:00") + 8;
		timeline = addRenderableWidget(new TimelineWidget(8 + timeWidth, top + 8, width - 16 - timeWidth, session, this::selectKeyframe));
		timeline.setSelectedKeyframe(selected);

		// row 2: transport (left), camera (right)
		int y = top + 26;
		int x = 8;
		addRenderableWidget(small("\u00AB", x, y, "streamerreplay.editor.back", ReplayKeys.SEEK_BACK, b -> seekBy(-ReplayKeys.SEEK_STEP_MS)));
		x += 22;
		playButton = addRenderableWidget(small("\u25B6", x, y, "streamerreplay.editor.play", ReplayKeys.PLAY_PAUSE, b -> ReplayKeys.togglePlayback(session)));
		x += 22;
		addRenderableWidget(small("\u00BB", x, y, "streamerreplay.editor.forward", ReplayKeys.SEEK_FORWARD, b -> seekBy(ReplayKeys.SEEK_STEP_MS)));
		x += 26;
		ValueSlider speed = new ValueSlider(x, y, 84, 20, 0, SPEEDS.length - 1, 1, speedIndex(session.clock().speed()),
			v -> Component.translatable("streamerreplay.editor.speed", ReplayHud.formatSpeed(SPEEDS[(int) v])),
			v -> session.clock().setSpeed(SPEEDS[(int) v]));
		speed.setTooltip(Tooltip.create(Component.translatable("streamerreplay.editor.speed.tooltip")));
		addRenderableWidget(speed);

		int right = width - 8;
		Button exit = Button.builder(Component.translatable("streamerreplay.editor.exit"), b -> ReplayManager.exit())
			.bounds(right - 60, y, 60, 20).tooltip(Tooltip.create(Component.translatable("streamerreplay.editor.exit.tooltip"))).build();
		addRenderableWidget(exit);
		int sliderWidth = Math.min(80, Math.max(56, (right - 64 - x - 90) / 2 - 4));
		rollSlider = addRenderableWidget(new ValueSlider(right - 64 - sliderWidth, y, sliderWidth, 20, -90, 90, 1, session.camera().roll(),
			v -> Component.translatable("streamerreplay.editor.roll", (int) v), v -> session.camera().setRoll((float) v)));
		rollSlider.setTooltip(Tooltip.create(Component.translatable("streamerreplay.editor.roll.tooltip")));
		fovSlider = addRenderableWidget(new ValueSlider(right - 68 - 2 * sliderWidth, y, sliderWidth, 20, 30, 110, 1, session.camera().fov(),
			v -> Component.translatable("streamerreplay.editor.fov", (int) v), v -> session.camera().setFov((float) v)));
		fovSlider.setTooltip(Tooltip.create(Component.translatable("streamerreplay.editor.fov.tooltip")));

		// row 3: camera path
		y = top + 50;
		x = 8;
		Button add = button("streamerreplay.editor.add_keyframe", x, y, ReplayKeys.KEYFRAME, b -> selectKeyframe(ReplayKeys.addKeyframe(session)));
		addRenderableWidget(add);
		x += add.getWidth() + 4;
		removeButton = addRenderableWidget(button("streamerreplay.editor.remove_keyframe", x, y, null, b -> removeSelected()));
		x += removeButton.getWidth() + 4;
		prevButton = addRenderableWidget(small("\u25C0", x, y, "streamerreplay.editor.prev_keyframe", null, b -> step(-1)));
		x += 22;
		nextButton = addRenderableWidget(small("\u25B6", x, y, "streamerreplay.editor.next_keyframe", null, b -> step(1)));
		x += 26;
		previewButton = addRenderableWidget(button("streamerreplay.editor.preview", x, y, null, b -> togglePreview()));
		x += previewButton.getWidth() + 4;
		clearButton = addRenderableWidget(button("streamerreplay.editor.clear", x, y, null, b -> confirmClear()));
		exportButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.editor.export"),
				b -> minecraft.gui.setScreen(new ExportScreen(session, this)))
			.bounds(right - 90, y, 90, 20).tooltip(Tooltip.create(Component.translatable("streamerreplay.editor.export.tooltip"))).build());
		refreshState();
	}

	private Button small(String label, int x, int y, String tooltipKey, @Nullable KeyMapping key, Button.OnPress press) {
		return Button.builder(Component.literal(label), press).bounds(x, y, 20, 20).tooltip(tooltip(tooltipKey, key)).build();
	}

	private Button button(String key, int x, int y, @Nullable KeyMapping hotkey, Button.OnPress press) {
		Component label = Component.translatable(key);
		return Button.builder(label, press).bounds(x, y, font.width(label) + 12, 20).tooltip(tooltip(key, hotkey)).build();
	}

	private static Tooltip tooltip(String key, @Nullable KeyMapping hotkey) {
		Component text = Component.translatable(key + ".tooltip");
		if (hotkey != null && !hotkey.isUnbound()) {
			text = text.copy().append(Component.literal("\n")).append(Component.translatable("streamerreplay.editor.hotkey", hotkey.getTranslatedKeyMessage()).withColor(Theme.TEXT_DIM));
		}
		return Tooltip.create(text);
	}

	private static int speedIndex(double speed) {
		int best = 0;
		for (int i = 0; i < SPEEDS.length; i++) {
			if (Math.abs(SPEEDS[i] - speed) < Math.abs(SPEEDS[best] - speed)) {
				best = i;
			}
		}
		return best;
	}

	// ------------------------------------------------------------------------------------------------ actions

	private void seekBy(double deltaMs) {
		session.seek(Math.max(0, Math.min(session.clock().durationMs(), session.clock().timeMs() + deltaMs)));
	}

	private void selectKeyframe(int index) {
		selected = index;
		timeline.setSelectedKeyframe(index);
		session.jumpToKeyframe(index);
		refreshState();
	}

	private void step(int direction) {
		CameraPath path = session.path();
		if (path.isEmpty()) {
			return;
		}
		int index = selected < 0 ? path.nearestByReplayTime((long) session.clock().timeMs()) : selected + direction;
		selectKeyframe(Math.max(0, Math.min(path.size() - 1, index)));
	}

	private void removeSelected() {
		if (selected >= 0) {
			session.removeKeyframe(selected);
			selected = Math.min(selected, session.path().size() - 1);
			timeline.setSelectedKeyframe(selected);
			refreshState();
		}
	}

	private void togglePreview() {
		if (session.isPlayingPath()) {
			session.stopPath();
		} else {
			session.playPath();
		}
	}

	private void confirmClear() {
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if (confirmed) {
				session.clearPath();
				selected = -1;
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("streamerreplay.editor.clear.confirm_title"), Component.translatable("streamerreplay.editor.clear.confirm_text")));
	}

	private void refreshState() {
		CameraPath path = session.path();
		if (selected >= path.size()) {
			selected = path.size() - 1;
		}
		removeButton.active = selected >= 0;
		prevButton.active = !path.isEmpty() && selected != 0;
		nextButton.active = !path.isEmpty() && selected < path.size() - 1;
		previewButton.active = path.isPlayable();
		previewButton.setMessage(Component.translatable(session.isPlayingPath() ? "streamerreplay.editor.stop_preview" : "streamerreplay.editor.preview"));
		clearButton.active = !path.isEmpty();
		exportButton.active = path.isPlayable();
		boolean playing = session.clock().isPlaying() || session.isPlayingPath();
		playButton.setMessage(Component.literal(playing ? "||" : "\u25B6"));
	}

	@Override
	public void tick() {
		refreshState();
		if (!minecraft.mouseHandler.isLeftPressed()) {
			fovSlider.set(session.camera().fov());
			rollSlider.set(session.camera().roll());
		}
	}

	// ------------------------------------------------------------------------------------------------ input

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof net.minecraft.client.gui.components.EditBox) {
			return super.keyPressed(event);
		}
		if (ReplayKeys.EDITOR.matches(event)) {
			onClose();
			return true;
		}
		if (ReplayKeys.PLAY_PAUSE.matches(event)) {
			ReplayKeys.togglePlayback(session);
			return true;
		}
		if (ReplayKeys.KEYFRAME.matches(event)) {
			selectKeyframe(ReplayKeys.addKeyframe(session));
			return true;
		}
		if (ReplayKeys.SEEK_BACK.matches(event)) {
			seekBy(-ReplayKeys.SEEK_STEP_MS);
			return true;
		}
		if (ReplayKeys.SEEK_FORWARD.matches(event)) {
			seekBy(ReplayKeys.SEEK_STEP_MS);
			return true;
		}
		if (event.key() == InputConstants.KEY_DELETE && selected >= 0) {
			removeSelected();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 1 && event.y() < height - PANEL_HEIGHT) {
			looking = true;
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (looking && event.button() == 1 && minecraft.player != null && !session.isPlayingPath()) {
			double sensitivity = minecraft.options.sensitivity().get() * LOOK_SENSITIVITY + 0.2;
			// same curve as vanilla's MouseHandler; drag deltas are in GUI pixels, so scale back to screen pixels
			double factor = sensitivity * sensitivity * sensitivity * 8.0 * minecraft.getWindow().getGuiScale();
			minecraft.player.turn(dx * factor, dy * factor * (minecraft.options.invertMouseY().get() ? -1 : 1));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (event.button() == 1) {
			looking = false;
		}
		return super.mouseReleased(event);
	}

	// ------------------------------------------------------------------------------------------------ rendering

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// The world stays fully visible behind the editor; only the camera path is drawn over it.
		PathPreviewRenderer.draw(g, font, session, width, height);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int top = height - PANEL_HEIGHT;
		Theme.panel(g, 0, top, width, height);
		String time = TimeFormat.clock((long) session.clock().timeMs()) + " / " + TimeFormat.clock(session.clock().durationMs());
		g.text(font, time, 8, top + 9, Theme.TEXT, false);

		CameraPath path = session.path();
		Component info = path.isEmpty()
			? Component.translatable("streamerreplay.editor.no_keyframes", ReplayKeys.KEYFRAME.getTranslatedKeyMessage())
			: Component.translatable("streamerreplay.editor.path_info", path.size(), TimeFormat.clock(path.durationMs()));
		g.text(font, info, 8, top - 11, Theme.TEXT, true);
		Component hint = Component.translatable("streamerreplay.editor.camera_hint");
		g.text(font, hint, width - 8 - font.width(hint), top - 11, Theme.TEXT_DIM, true);
		if (session.isFastForwarding()) {
			Component seeking = Component.translatable("streamerreplay.hud.seeking", Math.round(session.catchUpProgress() * 100));
			g.centeredText(font, seeking, width / 2, top / 2, Theme.TEXT);
		}
		super.extractRenderState(g, mouseX, mouseY, a);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

}
