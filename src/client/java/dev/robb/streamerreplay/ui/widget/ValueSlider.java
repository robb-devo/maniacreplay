package dev.robb.streamerreplay.ui.widget;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** Slider over an arbitrary numeric range with a label function. */
public final class ValueSlider extends AbstractSliderButton {
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<Component> label;
	private final DoubleConsumer onChange;

	public ValueSlider(int x, int y, int width, int height, double min, double max, double step, double value,
					   DoubleFunction<Component> label, DoubleConsumer onChange) {
		super(x, y, width, height, Component.empty(), (clamp(value, min, max) - min) / (max - min));
		this.min = min;
		this.max = max;
		this.step = step;
		this.label = label;
		this.onChange = onChange;
		updateMessage();
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	public double get() {
		double raw = min + value * (max - min);
		return step > 0 ? Math.round(raw / step) * step : raw;
	}

	/** Updates the slider from outside without firing the change listener. */
	public void set(double newValue) {
		value = (clamp(newValue, min, max) - min) / (max - min);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		setMessage(label.apply(get()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(get());
	}
}
