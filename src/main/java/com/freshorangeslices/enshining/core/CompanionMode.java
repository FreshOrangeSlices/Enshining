package com.freshorangeslices.enshining.core;

/** What a befriended companion does: follows its owner, roams near where it was told to, or stays put. */
public enum CompanionMode {
	FOLLOW("Following"),
	WANDER("Wandering"),
	STAY("Staying");

	private final String label;

	CompanionMode(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	public CompanionMode next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
