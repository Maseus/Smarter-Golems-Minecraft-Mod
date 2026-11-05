package com.smartergolems.test;

final class TestUtil {
	/**
	 * Space between test structures. A golem looks for work and chests up to ~11 blocks from
	 * its anchor (15x15 area + chest search), which is larger than the 8x8 test structure,
	 * so without padding golems wander into neighboring tests.
	 */
	static final int PADDING = 24;

	private TestUtil() {}
}
