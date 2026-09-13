package dev.squire.client.gui;

/** Pure geometry used by the scrollable panel and its headless layout tests. */
public final class PanelLayout {

	public record Rect(int left, int top, int right, int bottom) {
		public boolean intersects(Rect other) {
			return left < other.right && right > other.left
				&& top < other.bottom && bottom > other.top;
		}

		public boolean contains(Rect other) {
			return other.left >= left && other.right <= right
				&& other.top >= top && other.bottom <= bottom;
		}
	}

	public static int scrollLimit(int contentBottom, int viewportBottom) {
		return Math.max(0, contentBottom - viewportBottom);
	}

	/** Profile form: one text field followed by Save and Reset, with distinct hit areas. */
	public static java.util.List<Rect> nameEditorRow(int left, int top, int width, int height) {
		int buttonWidth = 44;
		int gap = 4;
		int fieldRight = left + width - 2 * (buttonWidth + gap);
		return java.util.List.of(
			new Rect(left, top, fieldRight, top + height),
			new Rect(fieldRight + gap, top, fieldRight + gap + buttonWidth, top + height),
			new Rect(left + width - buttonWidth, top, left + width, top + height));
	}

	public static int scrollBy(int current, int direction, int step, int limit) {
		return Math.max(0, Math.min(limit, current + direction * step));
	}

	private PanelLayout() {
	}
}
