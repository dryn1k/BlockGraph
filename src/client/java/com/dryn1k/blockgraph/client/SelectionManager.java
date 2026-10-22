package com.dryn1k.blockgraph.client;

import com.dryn1k.blockgraph.client.config.BlockGraphConfig;
import com.dryn1k.blockgraph.client.config.GraphMode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SelectionManager {
	private static final List<SelectedBlock> SELECTED = new ArrayList<>();
	private static final List<Link> FORCED_LINKS = new ArrayList<>();
	private static BlockPos pendingLinkSource;
	private static ResourceKey<Level> dimension;

	private SelectionManager() {
	}

	public record SelectedBlock(BlockPos pos, Direction face) {
		public Vec3 center() {
			return Vec3.atCenterOf(pos);
		}

		public Vec3 connectionPoint() {
			return center().add(face.getUnitVec3().scale(0.5));
		}
	}

	public record Link(BlockPos first, BlockPos second) {
	}

	/**
	 * A pair of block indices that should be connected by a line.
	 */
	public record Edge(int first, int second) {
	}

	private record EdgeCandidate(int first, int second, double distanceSqr) {
	}

	public record Graph(List<SelectedBlock> blocks, List<Edge> edges) {
	}

	public enum ToggleResult {
		ADDED,
		REMOVED,
		LIMIT_REACHED
	}

	public enum LinkResult {
		NOT_SELECTED,
		SOURCE_SET,
		SOURCE_CLEARED,
		LINK_CREATED
	}

	public static void init() {
		SELECTED.clear();
		FORCED_LINKS.clear();
		pendingLinkSource = null;
		dimension = null;
	}

	public static List<SelectedBlock> blocks() {
		return List.copyOf(SELECTED);
	}

	public static int size() {
		return SELECTED.size();
	}

	public static BlockPos pendingLinkSource() {
		return pendingLinkSource;
	}

	public static ToggleResult toggle(BlockPos pos, Direction face) {
		for (int i = 0; i < SELECTED.size(); i++) {
			if (SELECTED.get(i).pos().equals(pos)) {
				SELECTED.remove(i);
				removeLinksFor(pos);
				if (pos.equals(pendingLinkSource)) {
					pendingLinkSource = null;
				}
				return ToggleResult.REMOVED;
			}
		}

		if (SELECTED.size() >= BlockGraphConfig.get().maxBlocks) {
			return ToggleResult.LIMIT_REACHED;
		}

		SELECTED.add(new SelectedBlock(pos.immutable(), face));
		return ToggleResult.ADDED;
	}

	/**
	 * Link-modifier + single click flow: the first click on a selected block marks
	 * it as the link source, the next click on another selected block forces a
	 * connection.
	 */
	public static LinkResult toggleLink(BlockPos pos) {
		if (findIndex(pos) < 0) {
			return LinkResult.NOT_SELECTED;
		}

		if (pendingLinkSource == null) {
			pendingLinkSource = pos.immutable();
			return LinkResult.SOURCE_SET;
		}

		if (pendingLinkSource.equals(pos)) {
			pendingLinkSource = null;
			return LinkResult.SOURCE_CLEARED;
		}

		BlockPos first = pendingLinkSource;
		pendingLinkSource = null;

		if (hasLink(first, pos)) {
			return LinkResult.SOURCE_CLEARED;
		}

		FORCED_LINKS.add(new Link(first, pos));
		return LinkResult.LINK_CREATED;
	}

	public static boolean isSelected(BlockPos pos) {
		return findIndex(pos) >= 0;
	}

	public static int forcedLinkCount() {
		return FORCED_LINKS.size();
	}

	/**
	 * A consistent copy of the selected blocks and the lines between them.
	 * Both lists must come from the same snapshot: otherwise an edge could point
	 * past the end of the block list while the selection changes.
	 */
	public static Graph snapshot() {
		List<SelectedBlock> blocks = List.copyOf(SELECTED);
		return new Graph(blocks, computeEdges(blocks));
	}

	/**
	 * Computes the pairs of blocks that should be connected by lines in the current mode.
	 */
	public static List<Edge> edges() {
		return computeEdges(List.copyOf(SELECTED));
	}

	private static List<Edge> computeEdges(List<SelectedBlock> blocks) {
		int count = blocks.size();
		List<Edge> result = new ArrayList<>();
		if (count == 0) {
			return result;
		}

		GraphMode mode = BlockGraphConfig.get().graphMode;
		Set<Long> seen = new HashSet<>();

		if (mode == GraphMode.ALL) {
			for (int i = 0; i < count; i++) {
				for (int j = i + 1; j < count; j++) {
					addEdge(result, seen, i, j);
				}
			}
		} else if (mode == GraphMode.CLOSEST_PAIR) {
			appendTwoNearest(result, seen, blocks);
		} else {
			appendMinimumSpanningTree(result, seen, blocks);
		}

		for (Link link : List.copyOf(FORCED_LINKS)) {
			int first = findIndex(blocks, link.first());
			int second = findIndex(blocks, link.second());
			if (first >= 0 && second >= 0) {
				addEdge(result, seen, first, second);
			}
		}

		return result;
	}

	/**
	 * Connects selected blocks to their nearest neighbors, with at most two
	 * automatic edges per block. Only pairs that are among the two nearest
	 * neighbors of at least one endpoint are considered, so a free slot never
	 * causes a long line to a distant block. The candidates are added from the
	 * shortest to the longest while both endpoints still have a free automatic
	 * slot. Manual links are added after this pass and do not count toward the
	 * two-edge limit.
	 */
	private static void appendTwoNearest(List<Edge> result, Set<Long> seen, List<SelectedBlock> blocks) {
		int count = blocks.size();
		if (count < 2) {
			return;
		}

		List<EdgeCandidate> candidates = new ArrayList<>(count * 2);
		Set<Long> candidateKeys = new HashSet<>();
		int nearestCount = Math.min(2, count - 1);
		for (int i = 0; i < count; i++) {
			List<EdgeCandidate> nearest = new ArrayList<>(count - 1);
			Vec3 a = blocks.get(i).center();
			for (int j = 0; j < count; j++) {
				if (i == j) {
					continue;
				}
				nearest.add(new EdgeCandidate(Math.min(i, j), Math.max(i, j),
						a.distanceToSqr(blocks.get(j).center())));
			}
			nearest.sort(Comparator.comparingDouble(EdgeCandidate::distanceSqr));

			for (int k = 0; k < nearestCount; k++) {
				EdgeCandidate candidate = nearest.get(k);
				if (candidateKeys.add(edgeKey(candidate.first(), candidate.second()))) {
					candidates.add(candidate);
				}
			}
		}
		candidates.sort(Comparator.comparingDouble(EdgeCandidate::distanceSqr));

		int[] automaticDegree = new int[count];
		for (EdgeCandidate candidate : candidates) {
			if (automaticDegree[candidate.first()] >= 2 || automaticDegree[candidate.second()] >= 2) {
				continue;
			}
			if (addEdge(result, seen, candidate.first(), candidate.second())) {
				automaticDegree[candidate.first()]++;
				automaticDegree[candidate.second()]++;
			}
		}
	}

	private static void appendMinimumSpanningTree(List<Edge> result, Set<Long> seen, List<SelectedBlock> blocks) {
		int count = blocks.size();
		if (count < 2) {
			return;
		}

		boolean[] visited = new boolean[count];
		visited[0] = true;

		for (int step = 1; step < count; step++) {
			int bestFrom = -1;
			int bestTo = -1;
			double bestDistance = Double.MAX_VALUE;

			for (int from = 0; from < count; from++) {
				if (!visited[from]) {
					continue;
				}

				Vec3 a = blocks.get(from).center();
				for (int to = 0; to < count; to++) {
					if (visited[to]) {
						continue;
					}

					double distance = a.distanceToSqr(blocks.get(to).center());
					if (distance < bestDistance) {
						bestDistance = distance;
						bestFrom = from;
						bestTo = to;
					}
				}
			}

			if (bestTo < 0) {
				break;
			}

			visited[bestTo] = true;
			addEdge(result, seen, bestFrom, bestTo);
		}
	}

	private static boolean addEdge(List<Edge> result, Set<Long> seen, int a, int b) {
		int first = Math.min(a, b);
		int second = Math.max(a, b);
		if (seen.add(edgeKey(first, second))) {
			result.add(new Edge(first, second));
			return true;
		}
		return false;
	}

	private static long edgeKey(int a, int b) {
		int first = Math.min(a, b);
		int second = Math.max(a, b);
		return ((long) first << 32) | (second & 0xFFFFFFFFL);
	}

	private static boolean hasLink(BlockPos a, BlockPos b) {
		for (Link link : FORCED_LINKS) {
			if ((link.first().equals(a) && link.second().equals(b))
					|| (link.first().equals(b) && link.second().equals(a))) {
				return true;
			}
		}
		return false;
	}

	private static void removeLinksFor(BlockPos pos) {
		FORCED_LINKS.removeIf(link -> link.first().equals(pos) || link.second().equals(pos));
	}

	private static int findIndex(List<SelectedBlock> blocks, BlockPos pos) {
		for (int i = 0; i < blocks.size(); i++) {
			if (blocks.get(i).pos().equals(pos)) {
				return i;
			}
		}
		return -1;
	}

	private static int findIndex(BlockPos pos) {
		return findIndex(SELECTED, pos);
	}

	public static void clear() {
		SELECTED.clear();
		FORCED_LINKS.clear();
		pendingLinkSource = null;
	}

	public static void checkWorld(Minecraft client) {
		if (client.level == null) {
			clear();
			dimension = null;
			return;
		}

		ResourceKey<Level> current = client.level.dimension();
		if (dimension == null) {
			dimension = current;
		} else if (!dimension.equals(current)) {
			clear();
			dimension = current;
		}
	}
}
