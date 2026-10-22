package com.dryn1k.blockgraph.client;

import com.dryn1k.blockgraph.client.config.BlockGraphConfig;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BlockGraphRenderer {
	private static final int MAX_RENDER_DISTANCE_SQR = 256 * 256;
	private static final int LABEL_LIGHT = 0xF000F0;
	private static final int OUTLINE_COLOR = 0xFF000000;
	private static final float[] LABEL_POSITIONS = {0.42f, 0.50f, 0.58f};

	private BlockGraphRenderer() {
	}

	public static void register() {
		WorldRenderEvents.AFTER_ENTITIES.register(BlockGraphRenderer::render);
	}

	private static void render(WorldRenderContext context) {
		BlockGraphConfig config = BlockGraphConfig.get();
		Minecraft client = Minecraft.getInstance();
		SelectionManager.Graph graph = SelectionManager.snapshot();
		List<SelectionManager.SelectedBlock> blocks = graph.blocks();

		if (client.level == null || client.player == null || blocks.isEmpty()) {
			return;
		}

		PoseStack matrices = context.matrices();
		if (matrices == null) {
			return;
		}

		Vec3 camera = client.gameRenderer.getMainCamera().position();
		Vec3 playerPos = client.player.position();
		float time = (System.currentTimeMillis() % 10000L) / 10000.0f;
		List<SelectionManager.Edge> edges = graph.edges();

		// The level's shared buffer may already be closed at this point, so use our own
		// immediate buffer and flush it as soon as the overlay has been submitted.
		try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(1 << 20)) {
			MultiBufferSource.BufferSource consumers = MultiBufferSource.immediate(byteBuffer);
			VertexConsumer lines = consumers.getBuffer(RenderTypes.lines());

			if (config.showOutlines) {
				drawOutlines(lines, matrices, blocks, camera, playerPos, time, config);
			}

			if (config.showLines) {
				drawLines(matrices, lines, blocks, edges, camera, playerPos, time, config);
			}

			if (config.showDistances && !edges.isEmpty()) {
				// Distance labels follow the graph mode and are independent of Alt/Shift.
				drawLabels(client, matrices, consumers, blocks, edges, camera, playerPos, config);
			}

			consumers.endBatch();
		}
	}

	private static void drawOutlines(VertexConsumer lines, PoseStack matrices,
									 List<SelectionManager.SelectedBlock> blocks, Vec3 camera,
									 Vec3 playerPos, float time, BlockGraphConfig config) {
		for (int index = 0; index < blocks.size(); index++) {
			SelectionManager.SelectedBlock block = blocks.get(index);
			if (block.connectionPoint().distanceToSqr(playerPos) > MAX_RENDER_DISTANCE_SQR) {
				continue;
			}

			float pulse = 0.75f + 0.25f * Mth.sin((time * 6.2831855f) + block.pos().hashCode() * 0.3f);
			int baseColor = config.rainbow ? rainbowColor(index, 0.85f, 1.0f) : config.fixedColor;
			int color = scaleColor(baseColor, pulse);
			drawBox(lines, matrices, block.pos(), camera, color, config.lineWidth);
		}
	}

	private static void drawLines(PoseStack matrices, VertexConsumer lines,
								  List<SelectionManager.SelectedBlock> blocks,
								  List<SelectionManager.Edge> edges,
								  Vec3 camera, Vec3 playerPos, float time, BlockGraphConfig config) {
		for (int index = 0; index < edges.size(); index++) {
			SelectionManager.Edge edge = edges.get(index);
			if (edge.first() < 0 || edge.first() >= blocks.size()
					|| edge.second() < 0 || edge.second() >= blocks.size()) {
				continue;
			}
			SelectionManager.SelectedBlock first = blocks.get(edge.first());
			SelectionManager.SelectedBlock second = blocks.get(edge.second());

			if (first.connectionPoint().distanceToSqr(playerPos) > MAX_RENDER_DISTANCE_SQR
					|| second.connectionPoint().distanceToSqr(playerPos) > MAX_RENDER_DISTANCE_SQR) {
				continue;
			}

			EdgePoints points = edgePoints(first, second);
			int firstColor = config.rainbow ? rainbowColor(index + time, 0.95f, 1.0f) : config.fixedColor;
			int secondColor = config.rainbow ? rainbowColor(index + 1 + time, 0.95f, 1.0f) : config.fixedColor;

			drawLine(lines, matrices, points.first(), points.second(), camera,
					firstColor, secondColor, config.lineWidth);
		}
	}

	private static void drawLabels(Minecraft client, PoseStack matrices, MultiBufferSource consumers,
								   List<SelectionManager.SelectedBlock> blocks,
								   List<SelectionManager.Edge> edges, Vec3 camera,
								   Vec3 playerPos, BlockGraphConfig config) {
		List<LabelInfo> labels = new ArrayList<>();

		for (int index = 0; index < edges.size(); index++) {
			SelectionManager.Edge edge = edges.get(index);
			if (edge.first() < 0 || edge.first() >= blocks.size()
					|| edge.second() < 0 || edge.second() >= blocks.size()) {
				continue;
			}
			SelectionManager.SelectedBlock first = blocks.get(edge.first());
			SelectionManager.SelectedBlock second = blocks.get(edge.second());

			if (first.connectionPoint().distanceToSqr(playerPos) > MAX_RENDER_DISTANCE_SQR
					|| second.connectionPoint().distanceToSqr(playerPos) > MAX_RENDER_DISTANCE_SQR) {
				continue;
			}

			EdgePoints points = edgePoints(first, second);
			Vec3 a = points.first();
			Vec3 b = points.second();
			double distance = points.distance();
			// Shift labels slightly along their line so numbers on crossing lines
			// do not always land on the same spot.
			float along = LABEL_POSITIONS[index % LABEL_POSITIONS.length];
			Vec3 position = a.add(b.subtract(a).scale(along));
			int color = config.textRainbow ? rainbowColor(index, 0.85f, 1.0f) : config.textColor;
			labels.add(new LabelInfo(position, String.format(Locale.ROOT, "%.2f", distance), color));
		}

		// Stack labels that are close to each other in the world so they stay readable.
		float[] extraHeights = new float[labels.size()];
		for (int i = 0; i < labels.size(); i++) {
			Vec3 position = labels.get(i).position();
			for (int j = 0; j < i; j++) {
				if (position.distanceToSqr(labels.get(j).position()) < 2.25) {
					extraHeights[i] = Math.max(extraHeights[i], extraHeights[j] + 0.3f);
				}
			}
			extraHeights[i] = Math.min(extraHeights[i], 1.5f);
		}

		for (int i = 0; i < labels.size(); i++) {
			LabelInfo label = labels.get(i);
			drawLabel(client, matrices, consumers, label.position(), camera, label.text(), label.color(),
					extraHeights[i], config);
		}
	}

	private record LabelInfo(Vec3 position, String text, int color) {
	}

	private record EdgePoints(Vec3 first, Vec3 second, double distance) {
	}

	/**
	 * Returns the visible endpoints of an edge and the distance shown on its label.
	 * The distance is measured between block centers, so it does not depend on
	 * which face happened to be clicked. When the clicked face centers coincide
	 * (adjacent blocks facing each other), the connector is lifted slightly above
	 * the blocks; otherwise the line would be zero-length and invisible.
	 */
	private static EdgePoints edgePoints(SelectionManager.SelectedBlock first,
										 SelectionManager.SelectedBlock second) {
		Vec3 a = first.connectionPoint();
		Vec3 b = second.connectionPoint();
		Vec3 centerA = first.center();
		Vec3 centerB = second.center();
		double centerDistance = centerA.distanceTo(centerB);

		if (a.distanceToSqr(b) >= 0.25) {
			return new EdgePoints(a, b, centerDistance);
		}

		double top = Math.max(first.pos().getY(), second.pos().getY()) + 1.4;
		double dx = centerB.x - centerA.x;
		double dz = centerB.z - centerA.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 0.1) {
			// Vertically stacked blocks: choose any horizontal direction.
			dx = 1.0;
			dz = 0.0;
			horizontal = 1.0;
		}

		double length = Math.max(horizontal, 1.0);
		double nx = dx / horizontal;
		double nz = dz / horizontal;
		Vec3 midpoint = new Vec3((centerA.x + centerB.x) * 0.5, top,
				(centerA.z + centerB.z) * 0.5);
		Vec3 liftedFirst = midpoint.add(-nx * length * 0.5, 0.0, -nz * length * 0.5);
		Vec3 liftedSecond = midpoint.add(nx * length * 0.5, 0.0, nz * length * 0.5);
		return new EdgePoints(liftedFirst, liftedSecond, centerDistance);
	}

	private static void drawBox(VertexConsumer buffer, PoseStack matrices, BlockPos pos, Vec3 camera,
								int color, float width) {
		double x = pos.getX() - camera.x;
		double y = pos.getY() - camera.y;
		double z = pos.getZ() - camera.z;
		double minX = x - 0.002;
		double minY = y - 0.002;
		double minZ = z - 0.002;
		double maxX = x + 1.002;
		double maxY = y + 1.002;
		double maxZ = z + 1.002;

		// Bottom
		line(buffer, matrices, minX, minY, minZ, maxX, minY, minZ, color, color, width);
		line(buffer, matrices, maxX, minY, minZ, maxX, minY, maxZ, color, color, width);
		line(buffer, matrices, maxX, minY, maxZ, minX, minY, maxZ, color, color, width);
		line(buffer, matrices, minX, minY, maxZ, minX, minY, minZ, color, color, width);
		// Top
		line(buffer, matrices, minX, maxY, minZ, maxX, maxY, minZ, color, color, width);
		line(buffer, matrices, maxX, maxY, minZ, maxX, maxY, maxZ, color, color, width);
		line(buffer, matrices, maxX, maxY, maxZ, minX, maxY, maxZ, color, color, width);
		line(buffer, matrices, minX, maxY, maxZ, minX, maxY, minZ, color, color, width);
		// Sides
		line(buffer, matrices, minX, minY, minZ, minX, maxY, minZ, color, color, width);
		line(buffer, matrices, maxX, minY, minZ, maxX, maxY, minZ, color, color, width);
		line(buffer, matrices, maxX, minY, maxZ, maxX, maxY, maxZ, color, color, width);
		line(buffer, matrices, minX, minY, maxZ, minX, maxY, maxZ, color, color, width);
	}

	private static void drawLine(VertexConsumer buffer, PoseStack matrices, Vec3 a, Vec3 b, Vec3 camera,
								 int colorA, int colorB, float width) {
		line(buffer, matrices,
				a.x - camera.x, a.y - camera.y, a.z - camera.z,
				b.x - camera.x, b.y - camera.y, b.z - camera.z,
				colorA, colorB, width);
	}

	private static void line(VertexConsumer buffer, PoseStack matrices,
							 double x1, double y1, double z1,
							 double x2, double y2, double z2,
							 int colorA, int colorB, float width) {
		Matrix4f matrix = matrices.last().pose();
		// The line shader uses the normal as the direction to the other endpoint,
		// so both vertices must carry the segment delta for a correct, crisp width.
		float nx = (float) (x2 - x1);
		float ny = (float) (y2 - y1);
		float nz = (float) (z2 - z1);
		buffer.addVertex(matrix, (float) x1, (float) y1, (float) z1)
				.setColor(colorA | 0xFF000000)
				.setNormal(nx, ny, nz)
				.setLineWidth(width);
		buffer.addVertex(matrix, (float) x2, (float) y2, (float) z2)
				.setColor(colorB | 0xFF000000)
				.setNormal(nx, ny, nz)
				.setLineWidth(width);
	}

	private static void drawLabel(Minecraft client, PoseStack matrices, MultiBufferSource consumers,
								  Vec3 position, Vec3 camera, String text, int color, float extraHeight,
								  BlockGraphConfig config) {
		matrices.pushPose();
		matrices.translate(position.x - camera.x,
				position.y - camera.y + config.labelVerticalOffset + extraHeight,
				position.z - camera.z);
		matrices.mulPose(client.gameRenderer.getMainCamera().rotation());
		matrices.scale(config.textScale, -config.textScale, config.textScale);

		Matrix4f matrix = matrices.last().pose();
		Font font = client.font;
		float x = -font.width(text) / 2.0f;

		int outline = config.textOutline;
		if (outline > 0) {
			for (int dx = -outline; dx <= outline; dx++) {
				for (int dy = -outline; dy <= outline; dy++) {
					if (dx == 0 && dy == 0) {
						continue;
					}
					font.drawInBatch(text, x + dx, dy, OUTLINE_COLOR, false, matrix, consumers,
							Font.DisplayMode.SEE_THROUGH, 0, LABEL_LIGHT);
				}
			}
		}

		int backgroundColor = config.labelBackground ? 0x66000000 : 0;
		for (int pass = 0; pass < config.textWeight; pass++) {
			float offset = pass * 0.5f;
			int background = pass == 0 ? backgroundColor : 0;
			font.drawInBatch(text, x + offset, 0.0f, 0xFF000000 | (color & 0xFFFFFF), false, matrix,
					consumers, Font.DisplayMode.SEE_THROUGH, background, LABEL_LIGHT);
		}

		matrices.popPose();
	}

	private static int rainbowColor(float index, float saturation, float brightness) {
		float hue = (index * 0.137f) % 1.0f;
		return Mth.hsvToRgb(hue, saturation, brightness) & 0xFFFFFF;
	}

	private static int scaleColor(int rgb, float factor) {
		int r = (int) (((rgb >> 16) & 0xFF) * factor);
		int g = (int) (((rgb >> 8) & 0xFF) * factor);
		int b = (int) ((rgb & 0xFF) * factor);
		return (Mth.clamp(r, 0, 255) << 16) | (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255);
	}
}
