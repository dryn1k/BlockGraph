package com.dryn1k.blockgraph.client.config;

/**
 * How the graph edges are chosen.
 */
public enum GraphMode {
	/**
	 * Each selected block is connected to its nearest selected blocks, with at
	 * most two automatic lines per block. Pairs are added from shortest to
	 * longest, so a block gets its closest available neighbors first. Manual
	 * links are drawn in addition and do not count toward the two-line limit.
	 * The enum name is kept for config compatibility.
	 */
	CLOSEST_PAIR,
	/**
	 * Only the shortest set of lines that keeps every selected block connected
	 * (a minimum spanning tree), plus any links the player forced manually.
	 */
	NEAREST,
	/**
	 * Every selected block is connected to every other one.
	 */
	ALL
}
