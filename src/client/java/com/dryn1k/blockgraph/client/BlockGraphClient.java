package com.dryn1k.blockgraph.client;

import com.dryn1k.blockgraph.BlockGraph;
import com.dryn1k.blockgraph.client.config.BlockGraphConfig;
import com.dryn1k.blockgraph.client.config.BlockGraphConfigScreen;
import com.dryn1k.blockgraph.client.config.LinkModifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public class BlockGraphClient implements ClientModInitializer {
	/**
	 * Shift+right-clicks are held back for this long so a second click can turn
	 * the gesture into a selection instead of two normal world interactions.
	 */
	private static final long DOUBLE_CLICK_MS = 250L;

	private static KeyMapping clearKey;
	private static KeyMapping configKey;
	private static long lastClickTime;
	private static BlockPos lastClickPos;
	private static long lastLinkClickTime;
	private static BlockPos lastLinkClickPos;
	private static PendingUse pendingUse;
	private static boolean replaying;
	private static boolean useWasDown;

	@Override
	public void onInitializeClient() {
		BlockGraphConfig.load();
		SelectionManager.init();
		BlockGraphRenderer.register();

		clearKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.blockgraph.clear",
				com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_BACKSPACE,
				KeyMapping.Category.MISC
		));

		configKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.blockgraph.config",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				KeyMapping.Category.MISC
		));

		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (hand == InteractionHand.OFF_HAND || replaying) {
				return InteractionResult.PASS;
			}
			return onUseBlock(player, hand, hitResult);
		});
		ClientTickEvents.END_CLIENT_TICK.register(BlockGraphClient::onEndTick);

		BlockGraph.LOGGER.info("Block Graph ready: Shift + double right-click to select, Alt/Shift + right-click to link");
	}

	private static InteractionResult onUseBlock(Player player, InteractionHand hand, BlockHitResult hit) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player != player) {
			return InteractionResult.PASS;
		}

		boolean freshPress = !useWasDown;
		useWasDown = client.options.keyUse.isDown();

		if (!freshPress) {
			// Holding the button uses vanilla's repeat behaviour; it must not turn
			// into a chain of "double clicks" or links.
			pendingUse = null;
			resetDoubleClick();
			resetLinkClick();
			return InteractionResult.PASS;
		}

		BlockGraphConfig config = BlockGraphConfig.get();
		BlockPos pos = hit.getBlockPos();
		Direction face = hit.getDirection();
		long now = System.currentTimeMillis();
		boolean shiftDown = player.isShiftKeyDown();

		// Modifier + single right-click links two already selected blocks.
		if (isLinkModifierDown(client, player, config)) {
			pendingUse = null;

			if (SelectionManager.isSelected(pos)) {
				// Shift is also the selection key. A quick second Shift+click on the
				// same block is a double-click removal, not another link toggle.
				if (config.linkModifier == LinkModifier.SHIFT
						&& pos.equals(lastLinkClickPos)
						&& now - lastLinkClickTime <= DOUBLE_CLICK_MS) {
					resetDoubleClick();
					resetLinkClick();
					toggleSelection(player, pos, face, config);
					return InteractionResult.FAIL;
				}

				SelectionManager.LinkResult linkResult = SelectionManager.toggleLink(pos);
				Component message = switch (linkResult) {
					case SOURCE_SET -> Component.translatable("blockgraph.message.link_source");
					case LINK_CREATED -> Component.translatable("blockgraph.message.link_created",
							SelectionManager.forcedLinkCount());
					case SOURCE_CLEARED -> Component.translatable("blockgraph.message.link_cancelled");
					case NOT_SELECTED -> null;
				};
				if (message != null) {
					player.displayClientMessage(message, true);
				}

				resetDoubleClick();
				if (config.linkModifier == LinkModifier.SHIFT) {
					// Remember this click so a quick second click can remove the block.
					lastLinkClickPos = pos.immutable();
					lastLinkClickTime = now;
				} else {
					resetLinkClick();
				}
				return config.cancelInteractions ? InteractionResult.FAIL : InteractionResult.PASS;
			}

			// An unselected block in Alt link mode keeps its vanilla interaction.
			// In Shift link mode Shift is also the selection key, so the click
			// must fall through to the double-click selection logic.
			if (config.linkModifier == LinkModifier.ALT) {
				resetDoubleClick();
				resetLinkClick();
				return InteractionResult.PASS;
			}
			resetLinkClick();
		}

		// Selection is Shift-only. Without Shift, right-clicks go straight to the
		// game so normal building and container use are not delayed.
		if (!shiftDown) {
			resetLinkClick();
			return InteractionResult.PASS;
		}

		boolean doubleClick = pos.equals(lastClickPos) && now - lastClickTime <= DOUBLE_CLICK_MS;

		if (doubleClick) {
			// The first click of the pair is still waiting; drop it, the player
			// wanted a selection rather than a world interaction.
			pendingUse = null;
			resetDoubleClick();
			resetLinkClick();

			toggleSelection(player, pos, face, config);
			return InteractionResult.FAIL;
		}

		// This is the first click of a possible double-click. If an older click
		// is still waiting, let it happen now so rapid interactions do not pile up.
		if (pendingUse != null) {
			replayPendingUse();
		}

		lastClickPos = pos.immutable();
		lastClickTime = now;
		resetLinkClick();

		if (!config.cancelInteractions) {
			return InteractionResult.PASS;
		}

		pendingUse = new PendingUse(client.player, hand, hit, now);
		return InteractionResult.FAIL;
	}

	private static void toggleSelection(Player player, BlockPos pos, Direction face, BlockGraphConfig config) {
		SelectionManager.ToggleResult result = SelectionManager.toggle(pos, face);
		Component message = switch (result) {
			case ADDED -> Component.translatable("blockgraph.message.added",
					SelectionManager.size(), config.maxBlocks);
			case REMOVED -> Component.translatable("blockgraph.message.removed",
					SelectionManager.size());
			case LIMIT_REACHED -> Component.translatable("blockgraph.message.limit",
					config.maxBlocks);
		};
		player.displayClientMessage(message, true);
	}

	private static boolean isLinkModifierDown(Minecraft client, Player player, BlockGraphConfig config) {
		LinkModifier modifier = config.linkModifier;
		return switch (modifier) {
			case SHIFT -> player.isShiftKeyDown();
			case ALT -> InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_ALT)
					|| InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
		};
	}

	private static void replayPendingUse() {
		PendingUse pending = pendingUse;
		pendingUse = null;
		if (pending == null) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null || client.player != pending.player()
				|| client.gameMode == null || client.player.isHandsBusy()) {
			return;
		}

		replaying = true;
		try {
			replayVanillaUse(client, pending);
		} finally {
			replaying = false;
		}
		resetDoubleClick();
	}

	/**
	 * Repeats what {@code Minecraft.startUseItem()} would have done with the
	 * intercepted click: try both hands on the block, then use items in both hands.
	 */
	private static void replayVanillaUse(Minecraft client, PendingUse pending) {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		for (InteractionHand hand : InteractionHand.values()) {
			InteractionResult result = client.gameMode.useItemOn(player, hand, pending.hit());
			if (result instanceof InteractionResult.Success || result instanceof InteractionResult.Fail) {
				return;
			}
		}

		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.isEmpty()
					&& client.gameMode.useItem(player, hand) instanceof InteractionResult.Success) {
				return;
			}
		}
	}

	private static void resetDoubleClick() {
		lastClickPos = null;
		lastClickTime = 0L;
	}

	private static void resetLinkClick() {
		lastLinkClickPos = null;
		lastLinkClickTime = 0L;
	}

	private static void onEndTick(Minecraft client) {
		// Never replay an intercepted click behind an open screen.
		if (client.screen != null) {
			pendingUse = null;
			resetLinkClick();
		}

		// Let a lone click through once the double-click window has passed.
		if (pendingUse != null && System.currentTimeMillis() - pendingUse.time() >= DOUBLE_CLICK_MS) {
			replayPendingUse();
		}

		useWasDown = client.options.keyUse.isDown();

		if (configKey != null && configKey.consumeClick() && client.screen == null) {
			client.setScreen(BlockGraphConfigScreen.create(null));
		}

		if (clearKey != null && clearKey.consumeClick() && client.options.keyShift.isDown()) {
			SelectionManager.clear();
			pendingUse = null;
			resetDoubleClick();
			resetLinkClick();
			if (client.player != null) {
				client.player.displayClientMessage(
						Component.translatable("blockgraph.message.cleared"), true);
			}
		}

		SelectionManager.checkWorld(client);
	}

	private record PendingUse(LocalPlayer player, InteractionHand hand, BlockHitResult hit, long time) {
	}
}
