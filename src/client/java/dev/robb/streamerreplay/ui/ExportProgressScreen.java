package dev.robb.streamerreplay.ui;

import com.mojang.blaze3d.Blaze3D;
import dev.robb.streamerreplay.render.VideoExporter;
import dev.robb.streamerreplay.util.TimeFormat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Progress of a running export, then the result with an "Open folder" button. */
public final class ExportProgressScreen extends Screen {
	private final Screen parent;
	private Button cancelButton;
	private Button folderButton;
	private Button doneButton;

	public ExportProgressScreen(Screen parent) {
		super(Component.translatable("streamerreplay.export.progress.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int y = height / 2 + 30;
		cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> VideoExporter.cancel())
			.bounds(cx - 75, y, 150, 20).build());
		folderButton = addRenderableWidget(Button.builder(Component.translatable("streamerreplay.export.open_folder"), b -> {
			VideoExporter exporter = VideoExporter.current();
			if (exporter != null) {
				Blaze3D.openPath(exporter.output().getParent());
			}
		}).bounds(cx - 154, y, 150, 20).build());
		doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(cx + 4, y, 150, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		VideoExporter exporter = VideoExporter.current();
		boolean running = exporter != null && (exporter.state() == VideoExporter.State.PREPARING
			|| exporter.state() == VideoExporter.State.RENDERING || exporter.state() == VideoExporter.State.ENCODING);
		cancelButton.visible = running && exporter.state() != VideoExporter.State.ENCODING;
		folderButton.visible = !running && exporter != null && exporter.state() == VideoExporter.State.DONE;
		doneButton.visible = !running;
	}

	@Override
	public void tick() {
		updateButtons();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// keep the world visible: the streamer sees the shot being rendered
		g.fill(0, 0, width, height, 0x60000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int cx = width / 2;
		int cy = height / 2;
		updateButtons(); // state changes come from the encoder thread; reflect them immediately
		Theme.panel(g, cx - 170, cy - 50, cx + 170, cy + 58);
		super.extractRenderState(g, mouseX, mouseY, a);
		VideoExporter exporter = VideoExporter.current();
		if (exporter == null) {
			return;
		}
		Component heading;
		int color = Theme.TEXT;
		switch (exporter.state()) {
			case PREPARING -> heading = Component.translatable("streamerreplay.export.progress.preparing");
			case RENDERING -> heading = Component.translatable("streamerreplay.export.progress.rendering");
			case ENCODING -> heading = Component.translatable("streamerreplay.export.progress.encoding");
			case DONE -> {
				heading = Component.translatable("streamerreplay.export.progress.done");
				color = Theme.SUCCESS;
			}
			case FAILED -> {
				heading = Component.translatable("streamerreplay.export.progress.failed");
				color = Theme.ERROR;
			}
			default -> heading = Component.translatable("streamerreplay.export.progress.cancelled");
		}
		g.centeredText(font, heading, cx, cy - 40, color);
		Theme.progressBar(g, cx - 150, cy - 22, 300, 6, exporter.progress(), exporter.state() == VideoExporter.State.FAILED ? Theme.ERROR : Theme.TRACK_PLAYED);
		long elapsed = exporter.elapsedMs();
		String frames = exporter.framesDone() + " / " + exporter.totalFrames();
		String line = frames + "   " + Math.round(exporter.progress() * 100) + "%   " + TimeFormat.clock(elapsed);
		if (exporter.state() == VideoExporter.State.RENDERING && exporter.framesDone() > 10) {
			long remaining = (long) (elapsed / (double) exporter.framesDone() * (exporter.totalFrames() - exporter.framesDone()));
			line += "   ~" + TimeFormat.clock(remaining);
		}
		g.centeredText(font, line, cx, cy - 10, Theme.TEXT_DIM);
		if (exporter.state() == VideoExporter.State.DONE) {
			g.centeredText(font, Component.literal(exporter.output().getFileName().toString()), cx, cy + 6, Theme.TEXT);
		} else if (exporter.state() == VideoExporter.State.FAILED && exporter.error() != null) {
			g.textWithWordWrap(font, exporter.error(), cx - 160, cy + 2, 320, Theme.ERROR);
		} else if (exporter.renderWidth() > 0) {
			g.centeredText(font, Component.translatable("streamerreplay.export.progress.info", exporter.renderWidth(), exporter.renderHeight(),
				exporter.usesX264() ? "H.264" : "MPEG-4"), cx, cy + 6, Theme.TEXT_FAINT);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		VideoExporter exporter = VideoExporter.current();
		return exporter == null || !VideoExporter.isExporting();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
