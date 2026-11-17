package com.greysworkshop.groundskeeper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * A selection made of shapes, applied in order: each one adds its columns to the area, or cuts them out. Rectangles,
 * circles, ellipses, polygons and brush strokes. The same JSON is what Base Insurance's map hands back when you draw
 * an area on it, so the two mods agree exactly on which columns are in.
 * <p>
 * All the geometry works on column centres (x + 0.5, z + 0.5), one row of columns at a time: {@link #spans} gives the
 * runs of columns a shape covers in a row, which is fast for huge areas (a row of a 10,000-block rectangle is one run).
 * A circle of radius r has the same columns as the old circle job: dx² + dz² ≤ r² + r.
 */
final class Shapes {
	static final Gson GSON = new GsonBuilder().create();
	/** The most chunks a selection may cover: 16,000 × 16,000 blocks. Bigger would need more memory than is wise. */
	static final long MAX_CHUNKS = 1_000_000;

	/** One shape. Which fields are used depends on {@link #type}. */
	static final class Shape {
		/** "add" or "cut". */
		String op = "add";
		/** "rect", "ellipse" (a circle when rx = rz), "polygon" or "brush". */
		String type = "rect";
		/** rect: two opposite corners (blocks, inclusive). */
		int x1, z1, x2, z2;
		/** ellipse: the middle column and the radii. */
		int cx, cz, rx, rz;
		/** brush: the radius of each dab. */
		int r;
		/** polygon: corners; brush: dab centres. x, z, x, z, ... */
		int[] points = new int[0];

		boolean cut() {
			return "cut".equals(op);
		}

		static Shape rect(int x1, int z1, int x2, int z2) {
			Shape s = new Shape();
			s.type = "rect";
			s.x1 = Math.min(x1, x2);
			s.z1 = Math.min(z1, z2);
			s.x2 = Math.max(x1, x2);
			s.z2 = Math.max(z1, z2);
			return s;
		}

		static Shape ellipse(int cx, int cz, int rx, int rz) {
			Shape s = new Shape();
			s.type = "ellipse";
			s.cx = cx;
			s.cz = cz;
			s.rx = Math.max(1, rx);
			s.rz = Math.max(1, rz);
			return s;
		}

		static Shape polygon(int[] points) {
			Shape s = new Shape();
			s.type = "polygon";
			s.points = points;
			return s;
		}

		static Shape brush(int r, int[] points) {
			Shape s = new Shape();
			s.type = "brush";
			s.r = Math.max(1, r);
			s.points = points;
			return s;
		}

		Shape cutting() {
			op = "cut";
			return this;
		}

		/** {minX, minZ, maxX, maxZ} of the columns it can touch, or null if it has none. */
		int[] bounds() {
			switch (type) {
				case "rect":
					return new int[] {x1, z1, x2, z2};
				case "ellipse":
					return new int[] {cx - rx, cz - rz, cx + rx, cz + rz};
				case "polygon":
				case "brush": {
					if (points == null || points.length < 2) return null;
					int grow = "brush".equals(type) ? r : 0;
					int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
					for (int i = 0; i + 1 < points.length; i += 2) {
						minX = Math.min(minX, points[i]);
						maxX = Math.max(maxX, points[i]);
						minZ = Math.min(minZ, points[i + 1]);
						maxZ = Math.max(maxZ, points[i + 1]);
					}
					return new int[] {minX - grow, minZ - grow, maxX + grow, maxZ + grow};
				}
				default:
					return null;
			}
		}

		/** Something it can't draw (a polygon with fewer than 3 corners, an unknown type). */
		boolean valid() {
			if (bounds() == null) return false;
			if ("polygon".equals(type)) return points.length >= 6;
			return true;
		}
	}

	/** What goes in the file and comes back from the map. */
	static final class Data {
		int version = 1;
		List<Shape> shapes = new ArrayList<>();
	}

	final List<Shape> list = new ArrayList<>();

	Shapes() {}

	Shapes(List<Shape> shapes) {
		for (Shape s : shapes) if (s != null && s.valid()) list.add(s);
	}

	static Shapes fromJson(String json) {
		if (json == null || json.isBlank()) return new Shapes();
		try {
			Data d = GSON.fromJson(json, Data.class);
			return d == null || d.shapes == null ? new Shapes() : new Shapes(d.shapes);
		} catch (JsonParseException | IllegalStateException e) {
			return new Shapes();
		}
	}

	String toJson() {
		Data d = new Data();
		d.shapes = list;
		return GSON.toJson(d);
	}

	boolean isEmpty() {
		return list.isEmpty();
	}

	/** {minX, minZ, maxX, maxZ} of everything added, or null. */
	int[] bounds() {
		int[] b = null;
		for (Shape s : list) {
			if (s.cut()) continue;
			int[] sb = s.bounds();
			if (sb == null) continue;
			if (b == null) b = sb.clone();
			else {
				b[0] = Math.min(b[0], sb[0]);
				b[1] = Math.min(b[1], sb[1]);
				b[2] = Math.max(b[2], sb[2]);
				b[3] = Math.max(b[3], sb[3]);
			}
		}
		return b;
	}

	/** Why it's too big to use, or null. Checked before drawing it into a mask. */
	String tooBig() {
		long chunks = 0;
		for (Shape s : list) {
			if (s.cut()) continue;
			int[] b = s.bounds();
			chunks += (long) ((b[2] >> 4) - (b[0] >> 4) + 1) * ((b[3] >> 4) - (b[1] >> 4) + 1);
		}
		return chunks > MAX_CHUNKS ? String.format(java.util.Locale.ROOT, "That's about %,d chunks; the most is %,d (16,000 × 16,000 blocks).", chunks, MAX_CHUNKS) : null;
	}

	/** Draws every shape into a new mask, in order. */
	Mask rasterize() {
		Mask m = new Mask();
		for (Shape s : list) apply(m, s);
		m.compact();
		return m;
	}

	static void apply(Mask m, Shape s) {
		int[] b = s.bounds();
		if (b == null) return;
		boolean on = !s.cut();
		List<double[]> runs = new ArrayList<>();
		for (int z = b[1]; z <= b[3]; z++) {
			runs.clear();
			spans(s, z + 0.5, runs);
			for (double[] run : runs) {
				// Columns whose centre x + 0.5 lies in [a, b].
				int from = (int) Math.ceil(run[0] - 0.5 - 1e-9), to = (int) Math.floor(run[1] - 0.5 + 1e-9);
				if (from <= to) m.setRun(z, from, to, on);
			}
		}
	}

	/**
	 * The runs of column centres the shape covers on the line z = {@code zc} (a row's centre), as [from, to] in x.
	 * Base Insurance's map draws areas with the same sums, so what you see is what gets done.
	 */
	static void spans(Shape s, double zc, List<double[]> out) {
		switch (s.type) {
			case "rect" -> {
				if (zc >= s.z1 && zc <= s.z2 + 1) out.add(new double[] {s.x1 + 0.5, s.x2 + 0.5});
			}
			case "ellipse" -> ellipseSpan(s.cx, s.cz, s.rx, s.rz, zc, out);
			case "brush" -> {
				List<double[]> dabs = new ArrayList<>();
				for (int i = 0; i + 1 < s.points.length; i += 2) ellipseSpan(s.points[i], s.points[i + 1], s.r, s.r, zc, dabs);
				union(dabs, out);
			}
			case "polygon" -> polygonSpans(s.points, zc, out);
			default -> {}
		}
	}

	private static void ellipseSpan(int cx, int cz, int rx, int rz, double zc, List<double[]> out) {
		double dz = zc - (cz + 0.5);
		double kz = (double) rz * rz + rz, kx = (double) rx * rx + rx;
		double left = 1 - dz * dz / kz;
		if (left < 0) return;
		double w = Math.sqrt(kx * left);
		out.add(new double[] {cx + 0.5 - w, cx + 0.5 + w});
	}

	/** Even-odd: corners at block centres, crossings with the row's centre line. */
	private static void polygonSpans(int[] pts, double zc, List<double[]> out) {
		int n = pts.length / 2;
		if (n < 3) return;
		List<Double> xs = new ArrayList<>();
		for (int i = 0, j = n - 1; i < n; j = i++) {
			double xi = pts[2 * i] + 0.5, zi = pts[2 * i + 1] + 0.5, xj = pts[2 * j] + 0.5, zj = pts[2 * j + 1] + 0.5;
			if ((zi > zc) != (zj > zc)) xs.add(xi + (zc - zi) * (xj - xi) / (zj - zi));
		}
		xs.sort(Double::compare);
		for (int k = 0; k + 1 < xs.size(); k += 2) out.add(new double[] {xs.get(k), xs.get(k + 1)});
	}

	/** Merges overlapping runs. */
	static void union(List<double[]> in, List<double[]> out) {
		if (in.isEmpty()) return;
		in.sort((a, b) -> Double.compare(a[0], b[0]));
		double[] cur = in.get(0).clone();
		for (int i = 1; i < in.size(); i++) {
			double[] r = in.get(i);
			if (r[0] <= cur[1] + 1) cur[1] = Math.max(cur[1], r[1]);
			else {
				out.add(cur);
				cur = r.clone();
			}
		}
		out.add(cur);
	}

	/**
	 * Brush strokes are dabs: adds dabs between {@code from} and {@code to} so there's no gap, at most half a radius
	 * apart. For the brush tool, here and on the map.
	 */
	static void stroke(List<Integer> points, int r, int x, int z) {
		int n = points.size();
		if (n < 2) {
			points.add(x);
			points.add(z);
			return;
		}
		int px = points.get(n - 2), pz = points.get(n - 1);
		double d = Math.hypot(x - px, z - pz);
		double step = Math.max(1, r / 2.0);
		if (d < step) return;
		int steps = (int) Math.ceil(d / step);
		for (int i = 1; i <= steps; i++) {
			points.add((int) Math.round(px + (x - px) * i / (double) steps));
			points.add((int) Math.round(pz + (z - pz) * i / (double) steps));
		}
	}
}
