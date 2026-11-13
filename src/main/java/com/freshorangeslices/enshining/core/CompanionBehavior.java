package com.freshorangeslices.enshining.core;

/** How readily a companion fights. */
public enum CompanionBehavior {
	/** Goes after any hostile she sees. */
	AGGRESSIVE,
	/** Only fights what her leader attacks, or what attacks her leader or her. */
	WAIT_FOR_LEADER,
	/** Never fights. */
	PASSIVE;

	public CompanionBehavior next() {
		return values()[(ordinal() + 1) % values().length];
	}
}
