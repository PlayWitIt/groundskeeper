package com.greysworkshop.groundskeeper;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Which columns of the world are selected: one bit per column, 256 bits a chunk, kept only for chunks that have any.
 * A chunk that's all in shares one array, so a 10,000 × 10,000 rectangle (about 400,000 chunks) takes a few tens of
 * megabytes at most, and a big rectangle with a few cut-outs far less than that.
 */
final class Mask {
	/** A chunk with every column in. Shared: copied before it's changed. */
	private static final long[] FULL = {-1L, -1L, -1L, -1L};

	private final Map<Long, long[]> chunks = new HashMap<>();
	private long count = -1;

	static long key(int cx, int cz) {
		return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
	}

	static int keyX(long key) {
		return (int) (key >> 32);
	}

	static int keyZ(long key) {
		return (int) key;
	}

	boolean contains(int x, int z) {
		long[] b = chunks.get(key(x >> 4, z >> 4));
		if (b == null) return false;
		if (b == FULL) return true;
		int i = ((z & 15) << 4) | (x & 15);
		return (b[i >> 6] >>> (i & 63) & 1L) != 0;
	}

	boolean chunkAny(int cx, int cz) {
		return chunks.containsKey(key(cx, cz));
	}

	/** Columns in one chunk. */
	int chunkCount(int cx, int cz) {
		long[] b = chunks.get(key(cx, cz));
		if (b == null) return 0;
		if (b == FULL) return 256;
		return Long.bitCount(b[0]) + Long.bitCount(b[1]) + Long.bitCount(b[2]) + Long.bitCount(b[3]);
	}

	void set(int x, int z, boolean on) {
		setRun(z, x, x, on);
	}

	/** Columns {@code from} to {@code to} (inclusive) of row {@code z}, in or out. */
	void setRun(int z, int from, int to, boolean on) {
		count = -1;
		int cz = z >> 4, row = z & 15;
		int word = row >> 2, shift = (row & 3) << 4;
		for (int cx = from >> 4; cx <= to >> 4; cx++) {
			int lo = Math.max(from, cx << 4) & 15, hi = Math.min(to, (cx << 4) + 15) & 15;
			long bits = ((hi == 15 ? 0xFFFFL : (1L << (hi + 1)) - 1) & ~((1L << lo) - 1)) << shift;
			long k = key(cx, cz);
			long[] b = chunks.get(k);
			if (on) {
				if (b == FULL) continue;
				if (b == null) {
					b = new long[4];
					chunks.put(k, b);
				}
				b[word] |= bits;
			} else {
				if (b == null) continue;
				if (b == FULL) {
					b = FULL.clone();
					chunks.put(k, b);
				}
				b[word] &= ~bits;
			}
		}
	}

	/** Shares full chunks and drops empty ones. */
	void compact() {
		var it = chunks.entrySet().iterator();
		while (it.hasNext()) {
			var e = it.next();
			long[] b = e.getValue();
			if (b == FULL) continue;
			if ((b[0] | b[1] | b[2] | b[3]) == 0) it.remove();
			else if ((b[0] & b[1] & b[2] & b[3]) == -1L) e.setValue(FULL);
		}
	}

	/** The selected columns. */
	long count() {
		if (count < 0) {
			long n = 0;
			for (long[] b : chunks.values()) n += b == FULL ? 256 : Long.bitCount(b[0]) + Long.bitCount(b[1]) + Long.bitCount(b[2]) + Long.bitCount(b[3]);
			count = n;
		}
		return count;
	}

	int chunkCount() {
		return chunks.size();
	}

	boolean isEmpty() {
		return chunks.isEmpty();
	}

	Set<Long> chunkKeys() {
		return chunks.keySet();
	}

	/** In, with one of its 8 neighbours out: the edge, where the border path goes. */
	boolean edge(int x, int z) {
		if (!contains(x, z)) return false;
		for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if ((dx != 0 || dz != 0) && !contains(x + dx, z + dz)) return true;
		return false;
	}

	/** Columns on the edge: how long the border is. */
	long edgeCount() {
		long n = 0;
		for (long k : chunks.keySet()) {
			int bx = keyX(k) << 4, bz = keyZ(k) << 4;
			for (int x = bx; x < bx + 16; x++) for (int z = bz; z < bz + 16; z++) if (edge(x, z)) n++;
		}
		return n;
	}
}
