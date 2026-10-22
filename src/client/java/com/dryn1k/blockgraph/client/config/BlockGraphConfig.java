package com.dryn1k.blockgraph.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.dryn1k.blockgraph.BlockGraph;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class BlockGraphConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path PATH = CONFIG_DIR.resolve("blockgraph.json");
	private static final Path PRESET_PATH = CONFIG_DIR.resolve("blockgraph-preset.json");

	private static BlockGraphConfig instance = new BlockGraphConfig();

	/**
	 * {@code null} means the file was written before config versioning. Such
	 * files used "nearest blocks" as the implicit default, so they are migrated
	 * to the new "two nearest blocks" default once.
	 */
	public Integer configVersion;

	public GraphMode graphMode = GraphMode.CLOSEST_PAIR;
	public LinkModifier linkModifier = LinkModifier.ALT;

	public boolean showLines = true;
	public boolean showDistances = true;
	public boolean showOutlines = true;
	public boolean rainbow = true;
	public int fixedColor = 0x00E5FF;
	public float lineWidth = 4.0f;
	public int maxBlocks = 24;
	public boolean cancelInteractions = true;

	public float textScale = 0.025f;
	public float labelVerticalOffset = 0.45f;
	public boolean labelBackground = false;
	public int textWeight = 2;
	public int textOutline = 1;
	public boolean textRainbow = true;
	public int textColor = 0xFFFFFF;

	public static BlockGraphConfig get() {
		return instance;
	}

	/**
	 * Deep copy used by the settings screen so Cancel can discard edits.
	 */
	public BlockGraphConfig copy() {
		BlockGraphConfig copy = GSON.fromJson(GSON.toJson(this), BlockGraphConfig.class);
		if (copy == null) {
			copy = new BlockGraphConfig();
		}
		copy.validate();
		return copy;
	}

	/**
	 * Replaces the active settings with {@code config} (used by the vanilla screen).
	 */
	public static void replace(BlockGraphConfig config) {
		if (config != null) {
			instance = config;
			instance.validate();
		}
	}

	public static void load() {
		boolean legacyConfig = false;
		try {
			if (Files.exists(PATH)) {
				BlockGraphConfig loaded = GSON.fromJson(Files.readString(PATH), BlockGraphConfig.class);
				if (loaded != null) {
					instance = loaded;
					if (loaded.configVersion == null) {
						legacyConfig = true;
						// Old files used NEAREST as the default. Move them to the
						// new default unless the user had deliberately chosen ALL.
						if (loaded.graphMode == GraphMode.NEAREST) {
							loaded.graphMode = GraphMode.CLOSEST_PAIR;
						}
					}
				}
			}
		} catch (Exception e) {
			BlockGraph.LOGGER.warn("Could not load config, using defaults", e);
		}

		instance.validate();
		if (legacyConfig) {
			instance.save();
		}
	}

	public void save() {
		validate();
		write(PATH, this);
	}

	/**
	 * Writes the current settings to {@code blockgraph-preset.json}.
	 */
	public boolean savePreset() {
		validate();
		return write(PRESET_PATH, this);
	}

	/**
	 * Replaces the current settings with the saved preset and stores them as active settings.
	 */
	public static boolean loadPreset() {
		try {
			if (!Files.exists(PRESET_PATH)) {
				return false;
			}

			BlockGraphConfig preset = GSON.fromJson(Files.readString(PRESET_PATH), BlockGraphConfig.class);
			if (preset == null) {
				return false;
			}

			preset.validate();
			instance = preset;
			instance.save();
			return true;
		} catch (Exception e) {
			BlockGraph.LOGGER.warn("Could not load preset", e);
			return false;
		}
	}

	public static void resetToDefaults() {
		instance = new BlockGraphConfig();
		instance.save();
	}

	private static boolean write(Path path, BlockGraphConfig config) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(config));
			return true;
		} catch (IOException e) {
			BlockGraph.LOGGER.warn("Could not save " + path.getFileName(), e);
			return false;
		}
	}

	private void validate() {
		lineWidth = Math.max(1.0f, Math.min(16.0f, lineWidth));
		maxBlocks = Math.max(2, Math.min(64, maxBlocks));
		fixedColor &= 0xFFFFFF;
		textScale = Math.max(0.01f, Math.min(0.1f, textScale));
		labelVerticalOffset = Math.max(0.0f, Math.min(1.0f, labelVerticalOffset));
		textWeight = Math.max(1, Math.min(3, textWeight));
		textOutline = Math.max(0, Math.min(3, textOutline));
		textColor &= 0xFFFFFF;
		if (graphMode == null) {
			graphMode = GraphMode.CLOSEST_PAIR;
		}
		if (linkModifier == null) {
			linkModifier = LinkModifier.ALT;
		}
		configVersion = 2;
	}
}
