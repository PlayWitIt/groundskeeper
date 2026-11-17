package com.greysworkshop.groundskeeper;

import java.util.List;

/**
 * How dangerous it is around the player right now, from what the client can see: monsters (and what they're doing)
 * and players (and, with Naughty or Nice, which list they're on). Plain Java, so it can be tested on its own; the game
 * side fills in {@link Seen} from the entities it can see.
 */
final class Threat {
	enum Kind {
		/** A creeper with its fuse lit. */
		CREEPER_LIT,
		CREEPER,
		/** A skeleton or other archer drawing its bow. */
		ARCHER_AIMING,
		ARCHER,
		WARDEN,
		/** Any other monster. */
		HOSTILE,
		/** On your Naughty or Look Into list. */
		PLAYER_NAUGHTY,
		/** A player on none of your lists. */
		PLAYER_STRANGER,
		/** A friend, or on your Nice list. */
		PLAYER_FRIEND
	}

	/** Which players count as a reason to hide. */
	enum Players {
		/** None: other players are left to you and your other mods. */
		OFF,
		/** Players on your Naughty or Look Into list (needs Naughty or Nice). */
		NAUGHTY,
		/** Anyone who isn't a friend or on your Nice list. */
		STRANGERS
	}

	enum Level {
		NONE, CAUTION, DANGER
	}

	/** Something seen, and how far away it is (blocks). */
	record Seen(Kind kind, double distance, String name) {}

	/**
	 * The verdict. {@code hide}: get into shelter. {@code dodge}: a creeper is about to blow up this close; back off
	 * first. {@code reason} says why, for the HUD.
	 */
	record Assessment(Level level, boolean hide, boolean dodge, String reason, Seen worst) {
		static final Assessment CLEAR = new Assessment(Level.NONE, false, false, "", null);
	}

	private Threat() {}

	static Assessment assess(List<Seen> seen, Players players) {
		double mobScore = 0;
		int close = 0;
		Seen worst = null;
		double worstScore = 0;
		boolean hide = false, dodge = false;
		String reason = "";
		for (Seen s : seen) {
			double d = Math.max(0, s.distance());
			double score = switch (s.kind()) {
				case CREEPER_LIT -> d <= 8 ? 10 : 1;
				case WARDEN -> d <= 32 ? 10 : 0;
				case PLAYER_NAUGHTY -> players != Players.OFF && d <= 96 ? 10 : 0;
				case PLAYER_STRANGER -> players == Players.STRANGERS && d <= 64 ? 8 : 0;
				case PLAYER_FRIEND -> 0;
				case ARCHER_AIMING -> d <= 20 ? 2 : 0;
				case CREEPER -> d <= 12 ? 1.5 / (1 + d / 6) : 0;
				case ARCHER, HOSTILE -> d <= 16 ? 1 / (1 + d / 6) : 0;
			};
			if (score <= 0) continue;
			if (score > worstScore) {
				worstScore = score;
				worst = s;
			}
			switch (s.kind()) {
				case CREEPER_LIT -> {
					if (d <= 8) {
						dodge = true;
						reason = "creeper about to blow";
					}
				}
				case WARDEN -> {
					hide = true;
					reason = "a warden";
				}
				case PLAYER_NAUGHTY -> {
					hide = true;
					reason = (s.name() == null ? "a player" : s.name()) + " (on your list)";
				}
				case PLAYER_STRANGER -> {
					hide = true;
					if (reason.isEmpty()) reason = (s.name() == null ? "a player" : s.name()) + " nearby";
				}
				default -> {
					mobScore += score;
					if (d <= 10) close++;
				}
			}
		}
		// A crowd of monsters close by: more than kill aura can be trusted with.
		if (!hide && close >= 4 && mobScore >= 2.5) {
			hide = true;
			reason = close + " monsters close";
		}
		if (hide || dodge) return new Assessment(Level.DANGER, hide, dodge, reason, worst);
		if (mobScore >= 0.5) return new Assessment(Level.CAUTION, false, false, Math.round(mobScore * 10) / 10.0 + " threat", worst);
		return Assessment.CLEAR;
	}
}
