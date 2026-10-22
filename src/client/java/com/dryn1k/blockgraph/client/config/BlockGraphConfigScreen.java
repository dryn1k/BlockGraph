package com.dryn1k.blockgraph.client.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Self-contained settings screen built only from vanilla widgets.
 * No Mod Menu or Cloth Config is required.
 */
public final class BlockGraphConfigScreen extends Screen {
	private static final int ROW_HEIGHT = 24;
	private static final int CONTROL_HEIGHT = 20;
	private static final int PRESET_WIDTH = 132;
	private static final int LABEL_MIN_X = 140;

	private enum Kind {
		MODE,
		LINK,
		SHOW_LINES,
		SHOW_DISTANCES,
		SHOW_OUTLINES,
		CANCEL_INTERACTIONS,
		RAINBOW,
		FIXED_COLOR,
		LINE_WIDTH,
		MAX_BLOCKS,
		TEXT_SCALE,
		LABEL_OFFSET,
		LABEL_BACKGROUND,
		TEXT_WEIGHT,
		TEXT_OUTLINE,
		TEXT_RAINBOW,
		TEXT_COLOR
	}

	private record Setting(Kind kind, String labelKey, String tooltipKey) {
	}

	private record Row(Setting setting, AbstractWidget control) {
	}

	private static final List<Setting> SETTINGS = List.of(
			new Setting(Kind.MODE, "blockgraph.config.graph_mode", "blockgraph.config.graph_mode.tooltip"),
			new Setting(Kind.LINK, "blockgraph.config.link_modifier", "blockgraph.config.link_modifier.tooltip"),
			new Setting(Kind.SHOW_LINES, "blockgraph.config.show_lines", "blockgraph.config.show_lines.tooltip"),
			new Setting(Kind.SHOW_DISTANCES, "blockgraph.config.show_distances", "blockgraph.config.show_distances.tooltip"),
			new Setting(Kind.SHOW_OUTLINES, "blockgraph.config.show_outlines", "blockgraph.config.show_outlines.tooltip"),
			new Setting(Kind.CANCEL_INTERACTIONS, "blockgraph.config.cancel_interactions",
					"blockgraph.config.cancel_interactions.tooltip"),
			new Setting(Kind.RAINBOW, "blockgraph.config.rainbow", "blockgraph.config.rainbow.tooltip"),
			new Setting(Kind.FIXED_COLOR, "blockgraph.config.fixed_color", "blockgraph.config.fixed_color.tooltip"),
			new Setting(Kind.LINE_WIDTH, "blockgraph.config.line_width", "blockgraph.config.line_width.tooltip"),
			new Setting(Kind.MAX_BLOCKS, "blockgraph.config.max_blocks", "blockgraph.config.max_blocks.tooltip"),
			new Setting(Kind.TEXT_SCALE, "blockgraph.config.text_scale", "blockgraph.config.text_scale.tooltip"),
			new Setting(Kind.LABEL_OFFSET, "blockgraph.config.label_vertical_offset",
					"blockgraph.config.label_vertical_offset.tooltip"),
			new Setting(Kind.LABEL_BACKGROUND, "blockgraph.config.label_background",
					"blockgraph.config.label_background.tooltip"),
			new Setting(Kind.TEXT_WEIGHT, "blockgraph.config.text_weight", "blockgraph.config.text_weight.tooltip"),
			new Setting(Kind.TEXT_OUTLINE, "blockgraph.config.text_outline", "blockgraph.config.text_outline.tooltip"),
			new Setting(Kind.TEXT_RAINBOW, "blockgraph.config.text_rainbow", "blockgraph.config.text_rainbow.tooltip"),
			new Setting(Kind.TEXT_COLOR, "blockgraph.config.text_color", "blockgraph.config.text_color.tooltip")
	);

	private final Screen parent;
	private final BlockGraphConfig config;
	private final List<Row> rows = new ArrayList<>();
	private int page;
	private int rowsPerPage = 6;
	private int pageCount = 1;

	private BlockGraphConfigScreen(Screen parent, BlockGraphConfig config, int page) {
		super(Component.translatable("blockgraph.config.title"));
		this.parent = parent;
		this.config = config;
		this.page = page;
	}

	public static Screen create(Screen parent) {
		return new BlockGraphConfigScreen(parent, BlockGraphConfig.get().copy(), 0);
	}

	public static Screen create(Screen parent, int page) {
		return new BlockGraphConfigScreen(parent, BlockGraphConfig.get().copy(), page);
	}

	@Override
	protected void init() {
		int labelX = Math.max(LABEL_MIN_X, width / 2 - 220);
		int controlWidth = Math.min(190, Math.max(120, width - labelX - 12 - 110));
		int controlX = width - 12 - controlWidth;

		rowsPerPage = Mth.clamp((height - 92) / ROW_HEIGHT, 4, 8);
		pageCount = Math.max(1, (SETTINGS.size() + rowsPerPage - 1) / rowsPerPage);
		page = Mth.clamp(page, 0, pageCount - 1);
		rows.clear();

		int titleX = (width - font.width(title)) / 2;
		addRenderableWidget(new StringWidget(titleX, 12, title, font));

		Button prev = addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
				.bounds(width - 78, 32, 24, CONTROL_HEIGHT).build());
		Button next = addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
				.bounds(width - 50, 32, 24, CONTROL_HEIGHT).build());
		prev.active = page > 0;
		next.active = page < pageCount - 1;
		addRenderableWidget(new StringWidget(width - 134, 38,
				Component.literal((page + 1) + " / " + pageCount), font));

		addPresetButtons();

		int start = page * rowsPerPage;
		int end = Math.min(SETTINGS.size(), start + rowsPerPage);
		for (int index = start; index < end; index++) {
			Setting setting = SETTINGS.get(index);
			int rowY = 64 + (index - start) * ROW_HEIGHT;
			Tooltip tooltip = Tooltip.create(Component.translatable(setting.tooltipKey()));

			AbstractWidget control = createControl(setting.kind(), controlX, rowY, controlWidth, CONTROL_HEIGHT);
			control.setTooltip(tooltip);
			addRenderableWidget(control);

			StringWidget label = new StringWidget(labelX, rowY + 6,
					Component.translatable(setting.labelKey()), font);
			label.setMaxWidth(Math.max(40, controlX - labelX - 8));
			label.setTooltip(tooltip);
			addRenderableWidget(label);

			rows.add(new Row(setting, control));

			if (setting.kind() == Kind.FIXED_COLOR || setting.kind() == Kind.TEXT_COLOR) {
				Supplier<Integer> color = setting.kind() == Kind.FIXED_COLOR
						? () -> config.fixedColor
						: () -> config.textColor;
				addRenderableWidget(new ColorSwatch(controlX + controlWidth - 22, rowY + 2, 18, 16, color));
			}
		}

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
				.bounds(width / 2 - 102, height - 28, 100, CONTROL_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
				.bounds(width / 2 + 2, height - 28, 100, CONTROL_HEIGHT).build());
	}

	private void addPresetButtons() {
		int stackHeight = 3 * CONTROL_HEIGHT + 2 * 4;
		int y = Math.max(56, height - 30 - stackHeight);

		addRenderableWidget(Button.builder(Component.translatable("blockgraph.config.actions.save_preset"), button -> {
			button.setMessage(Component.translatable(config.savePreset()
					? "blockgraph.config.actions.preset_saved"
					: "blockgraph.config.actions.preset_failed"));
		}).bounds(4, y, PRESET_WIDTH, CONTROL_HEIGHT).build());

		y += CONTROL_HEIGHT + 4;
		addRenderableWidget(Button.builder(Component.translatable("blockgraph.config.actions.load_preset"), button -> {
			if (BlockGraphConfig.loadPreset()) {
				Minecraft.getInstance().setScreen(create(parent));
			} else {
				button.setMessage(Component.translatable("blockgraph.config.actions.preset_missing"));
			}
		}).bounds(4, y, PRESET_WIDTH, CONTROL_HEIGHT).build());

		y += CONTROL_HEIGHT + 4;
		addRenderableWidget(Button.builder(Component.translatable("blockgraph.config.actions.reset"), button -> {
			BlockGraphConfig.resetToDefaults();
			Minecraft.getInstance().setScreen(create(parent));
		}).bounds(4, y, PRESET_WIDTH, CONTROL_HEIGHT).build());
	}

	private void changePage(int delta) {
		int target = Mth.clamp(page + delta, 0, pageCount - 1);
		Minecraft.getInstance().setScreen(new BlockGraphConfigScreen(parent, config, target));
	}

	private AbstractWidget createControl(Kind kind, int x, int y, int width, int height) {
		return switch (kind) {
			case MODE -> Button.builder(modeName(config.graphMode), button -> {
				GraphMode[] values = GraphMode.values();
				config.graphMode = values[(config.graphMode.ordinal() + 1) % values.length];
				button.setMessage(modeName(config.graphMode));
			}).bounds(x, y, width, height).build();
			case LINK -> Button.builder(linkName(config.linkModifier), button -> {
				config.linkModifier = config.linkModifier == LinkModifier.ALT
						? LinkModifier.SHIFT
						: LinkModifier.ALT;
				button.setMessage(linkName(config.linkModifier));
			}).bounds(x, y, width, height).build();
			case SHOW_LINES -> toggleButton(config.showLines, value -> config.showLines = value,
					x, y, width, height);
			case SHOW_DISTANCES -> toggleButton(config.showDistances, value -> config.showDistances = value,
					x, y, width, height);
			case SHOW_OUTLINES -> toggleButton(config.showOutlines, value -> config.showOutlines = value,
					x, y, width, height);
			case CANCEL_INTERACTIONS -> toggleButton(config.cancelInteractions,
					value -> config.cancelInteractions = value, x, y, width, height);
			case RAINBOW -> toggleButton(config.rainbow, value -> config.rainbow = value,
					x, y, width, height);
			case FIXED_COLOR -> colorField(config.fixedColor, value -> config.fixedColor = value,
					x, y, width, height);
			case LINE_WIDTH -> new ValueSlider(x, y, width, height, 1, 16, config.lineWidth, true,
					value -> config.lineWidth = (float) value);
			case MAX_BLOCKS -> new ValueSlider(x, y, width, height, 2, 64, config.maxBlocks, true,
					value -> config.maxBlocks = (int) Math.round(value));
			case TEXT_SCALE -> new ValueSlider(x, y, width, height, 0.01, 0.1, config.textScale, false,
					value -> config.textScale = (float) value);
			case LABEL_OFFSET -> new ValueSlider(x, y, width, height, 0.0, 1.0, config.labelVerticalOffset, false,
					value -> config.labelVerticalOffset = (float) value);
			case LABEL_BACKGROUND -> toggleButton(config.labelBackground,
					value -> config.labelBackground = value, x, y, width, height);
			case TEXT_WEIGHT -> new ValueSlider(x, y, width, height, 1, 3, config.textWeight, true,
					value -> config.textWeight = (int) Math.round(value));
			case TEXT_OUTLINE -> new ValueSlider(x, y, width, height, 0, 3, config.textOutline, true,
					value -> config.textOutline = (int) Math.round(value));
			case TEXT_RAINBOW -> toggleButton(config.textRainbow, value -> config.textRainbow = value,
					x, y, width, height);
			case TEXT_COLOR -> colorField(config.textColor, value -> config.textColor = value,
					x, y, width, height);
		};
	}

	private Button toggleButton(boolean initial, Consumer<Boolean> setter,
								int x, int y, int width, int height) {
		boolean[] state = {initial};
		return Button.builder(onOff(state[0]), button -> {
			state[0] = !state[0];
			setter.accept(state[0]);
			button.setMessage(onOff(state[0]));
		}).bounds(x, y, width, height).build();
	}

	private EditBox colorField(int initial, IntConsumer setter, int x, int y, int width, int height) {
		EditBox box = new EditBox(font, x, y, width - 24, height, Component.empty());
		box.setMaxLength(7);
		box.setValue(String.format(Locale.ROOT, "#%06X", initial & 0xFFFFFF));
		box.setResponder(text -> {
			int color = parseColor(text);
			if (color >= 0) {
				setter.accept(color);
			}
		});
		return box;
	}

	private void saveAndClose() {
		BlockGraphConfig.replace(config);
		BlockGraphConfig.get().save();
		onClose();
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().setScreen(parent);
	}

	private static Component modeName(GraphMode mode) {
		return Component.translatable("blockgraph.config.graph_mode."
				+ mode.name().toLowerCase(Locale.ROOT));
	}

	private static Component linkName(LinkModifier modifier) {
		return Component.translatable("blockgraph.config.link_modifier."
				+ modifier.name().toLowerCase(Locale.ROOT));
	}

	private static Component onOff(boolean value) {
		return Component.translatable(value ? "blockgraph.config.on" : "blockgraph.config.off");
	}

	private static int parseColor(String text) {
		if (text == null) {
			return -1;
		}
		String value = text.trim();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		if (value.length() != 6) {
			return -1;
		}
		try {
			return Integer.parseInt(value, 16) & 0xFFFFFF;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	/**
	 * Vanilla slider that maps a normalized 0..1 value to a real range.
	 */
	private static final class ValueSlider extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final boolean integer;
		private final DoubleConsumer setter;

		private ValueSlider(int x, int y, int width, int height, double min, double max,
							double value, boolean integer, DoubleConsumer setter) {
			super(x, y, width, height, Component.empty(),
					Math.max(0.0, Math.min(1.0, (value - min) / (max - min))));
			this.min = min;
			this.max = max;
			this.integer = integer;
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(format(realValue())));
		}

		@Override
		protected void applyValue() {
			double actual = realValue();
			setter.accept(integer ? Math.round(actual) : actual);
		}

		private double realValue() {
			return min + value * (max - min);
		}

		private String format(double value) {
			if (integer) {
				return Integer.toString((int) Math.round(value));
			}
			return String.format(Locale.ROOT, "%.3f", value);
		}
	}

	/**
	 * Small color preview next to the hex color field.
	 */
	private static final class ColorSwatch extends AbstractWidget {
		private final Supplier<Integer> color;

		private ColorSwatch(int x, int y, int width, int height, Supplier<Integer> color) {
			super(x, y, width, height, Component.empty());
			this.color = color;
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0xFF000000);
			graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1,
					0xFF000000 | (color.get() & 0xFFFFFF));
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
		}
	}
}
