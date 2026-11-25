package com.freshorangeslices.enshining.play;

import com.freshorangeslices.enshining.core.CompanionEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Playing as a companion, Genshin-style: for that moment you ARE her. Under the hood you ride inside her, hidden, so
 * her own body, moves and health do the work: hits meant for you land on her, and her movement comes from your keys.
 * Your party (your companions within 32 blocks, in the order you befriended them, up to 4) comes along off-field,
 * hidden, and number keys swap who you are. J hops out: Steve is back where she stands, and the party with him. If she
 * falls, Steve pops out 2 seconds later.
 */
public final class PlayAs {
	public static final int PARTY_SIZE = 4;
	public static final double PARTY_RANGE = 32.0;
	/** Ticks between her falling and Steve popping out. */
	public static final int FALL_DELAY = 40;
	/** Ticks before you can switch again (Genshin's is about a second). */
	public static final int SWITCH_COOLDOWN = 20;

	private static final class Session {
		final ServerPlayer player;
		final List<UUID> party = new ArrayList<>();
		int active;
		int fallenTicks;
		int cooldown;
		boolean sprint, moving, dash;
		boolean hitsAll;   // X: her hits land on animals too

		Session(ServerPlayer player) {
			this.player = player;
		}
	}

	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private PlayAs() {
	}

	// ---- Asking about it ----------------------------------------------------------------------------------------

	/** Whether this player is playing as a companion right now (riding inside her). */
	public static boolean isPlaying(Player player) {
		return player.getVehicle() instanceof CompanionEntity;
	}

	/** Server side: whether her fall is holding this player hidden for a moment before Steve pops out. */
	public static boolean isFallen(Player player) {
		Session session = SESSIONS.get(player.getUUID());
		return session != null && session.fallenTicks > 0;
	}

	/** Server side: the party of a player playing as a companion, in slot order (empty if not playing). */
	public static List<CompanionEntity> party(ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		List<CompanionEntity> out = new ArrayList<>();
		if (session != null) {
			for (UUID id : session.party) {
				CompanionEntity member = member(player, id);
				if (member != null) {
					out.add(member);
				}
			}
		}
		return out;
	}

	// ---- Starting, switching, stopping --------------------------------------------------------------------------

	/** Become this companion (from her menu). False if she can't be played right now. */
	public static boolean start(ServerPlayer player, CompanionEntity chosen) {
		if (!chosen.isAlive() || !chosen.isOwnedBy(player) || SESSIONS.containsKey(player.getUUID()) || player.isPassenger()
				|| chosen.isVehicle() || player.isSpectator() || player.isSleeping()) {
			return false;
		}
		List<CompanionEntity> near = new ArrayList<>(player.level().getEntitiesOfClass(CompanionEntity.class,
				player.getBoundingBox().inflate(PARTY_RANGE), c -> c.isAlive() && c.isOwnedBy(player) && !c.isVehicle()));
		Comparator<CompanionEntity> order = Comparator.comparingLong(CompanionEntity::befriendedAt).thenComparing(Entity::getUUID);
		near.sort(order);
		List<CompanionEntity> party = new ArrayList<>(near.subList(0, Math.min(PARTY_SIZE, near.size())));
		if (!party.contains(chosen)) {
			if (party.size() == PARTY_SIZE) {
				party.remove(PARTY_SIZE - 1);
			}
			party.add(chosen);
			party.sort(order);
		}
		Session session = new Session(player);
		for (CompanionEntity member : party) {
			session.party.add(member.getUUID());
		}
		session.active = party.indexOf(chosen);
		SESSIONS.put(player.getUUID(), session);
		for (CompanionEntity member : party) {
			if (member != chosen) {
				member.setOffField(true);
			}
		}
		board(player, chosen);
		player.displayClientMessage(Component.translatable("enshining.play.started", chosen.getDisplayName(),
				Component.keybind("key.enshining.hop_out")), true);
		sendState(session);
		return true;
	}

	/** Swap who you are: the one in that slot takes your place, the one you were goes off-field. */
	public static void switchTo(ServerPlayer player, int slot) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || session.fallenTicks > 0 || session.cooldown > 0 || slot < 0 || slot >= session.party.size()
				|| slot == session.active) {
			return;
		}
		CompanionEntity from = active(session);
		CompanionEntity to = member(player, session.party.get(slot));
		if (from == null || to == null || !to.isAlive()) {
			return;
		}
		to.setOffField(false);
		to.moveTo(from.getX(), from.getY(), from.getZ(), from.getYRot(), 0.0F);
		to.setYHeadRot(from.getYRot());
		to.setYBodyRot(from.getYRot());
		player.stopRiding();
		from.setOffField(true);
		board(player, to);
		session.active = slot;
		session.cooldown = SWITCH_COOLDOWN;
		sendState(session);
	}

	/** Back to Steve, where she stands; the rest of the party comes back too. */
	public static void hopOut(ServerPlayer player) {
		Session session = SESSIONS.get(player.getUUID());
		if (session == null || session.fallenTicks > 0) {
			return;
		}
		SESSIONS.remove(player.getUUID());
		finish(session);
	}

	/** Called by a companion as she dies with you inside: Steve pops out 2 s later. */
	public static void fell(ServerPlayer player, CompanionEntity companion) {
		Session session = SESSIONS.get(player.getUUID());
		Vec3 at = companion.position();
		player.stopRiding();
		player.teleportTo(at.x, at.y, at.z);
		if (session == null) {
			return;
		}
		session.fallenTicks = FALL_DELAY;
		sendState(session);
	}

	/** One of her moves from your keys (left click, hold and let go, E, Q, a cancel, F). */
	public static void move(ServerPlayer player, byte action) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null && (action == PlayNetwork.Action.HITS_ALL || action == PlayNetwork.Action.HITS_HOSTILE)) {
			session.hitsAll = action == PlayNetwork.Action.HITS_ALL;
			return;
		}
		CompanionEntity active = session == null || session.fallenTicks > 0 ? null : active(session);
		if (active == null || player.getVehicle() != active) {
			return;
		}
		switch (action) {
			case PlayNetwork.Action.ATTACK -> active.playerAttack();
			case PlayNetwork.Action.CHARGE_BEGIN -> active.playerChargeBegin();
			case PlayNetwork.Action.CHARGE_END -> active.playerChargeEnd();
			case PlayNetwork.Action.SKILL -> active.playerSkill();
			case PlayNetwork.Action.BURST -> active.playerBurst();
			case PlayNetwork.Action.CANCEL -> active.playerCancel();
			case PlayNetwork.Action.INTERACT -> interact(player, active);
			default -> {
			}
		}
	}

	/** F: the door, lever, button or chest you're looking at, from her eyes, within 4.5 blocks. */
	private static void interact(ServerPlayer player, CompanionEntity companion) {
		Vec3 from = companion.getEyePosition();
		Vec3 to = from.add(player.getLookAngle().scale(4.5));
		var hit = player.level().clip(new net.minecraft.world.level.ClipContext(from, to, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.NONE, companion));
		if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			var state = player.level().getBlockState(hit.getBlockPos());
			state.use(player.level(), player, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
		}
	}

	/** The held keys that change how she moves (sprint, whether you're moving at all, and the dash). */
	public static void input(ServerPlayer player, boolean sprint, boolean moving, boolean dash) {
		Session session = SESSIONS.get(player.getUUID());
		if (session != null) {
			session.sprint = sprint;
			session.moving = moving;
			session.dash = dash;
		}
	}

	private static void board(ServerPlayer player, CompanionEntity companion) {
		companion.setOrderedToSit(false);
		companion.setInSittingPose(false);
		companion.getNavigation().stop();
		companion.setTarget(null);
		companion.setOffField(false);
		companion.pilotRunning = false;
		companion.pilotDashing = false;
		player.startRiding(companion, true);
		companion.refreshControl();
		sparkle(companion);
	}

	/** Steve back out where the one he played stands, and the whole party back on-field. */
	private static void finish(Session session) {
		ServerPlayer player = session.player;
		CompanionEntity active = session.fallenTicks > 0 ? null : active(session);
		if (player.getVehicle() instanceof CompanionEntity) {
			player.stopRiding();
		}
		Vec3 at = active != null ? active.position() : player.position();
		for (UUID id : session.party) {
			CompanionEntity member = member(player, id);
			if (member != null && member.isOffField()) {
				member.moveTo(at.x, at.y, at.z, member.getYRot(), 0.0F);
				member.setOffField(false);
				member.refreshControl();
			}
		}
		if (active != null) {
			active.refreshControl();
		}
		if (player.level() instanceof ServerLevel level) {
			com.freshorangeslices.enshining.core.Fx.shimmer(level, net.minecraft.core.particles.ParticleTypes.END_ROD,
					player.position().add(0, 1.0, 0), 0.8, 16, player.getRandom());
		}
		PlayNetwork.toPlayer(player, new PlayNetwork.State(new int[0], 0, false));
	}

	private static void sparkle(CompanionEntity companion) {
		if (companion.level() instanceof ServerLevel level) {
			com.freshorangeslices.enshining.core.Fx.shimmer(level, companion.element().dust(), companion.position().add(0, 1.0, 0), 0.8,
					20, companion.getRandom());
		}
	}

	private static void sendState(Session session) {
		int[] ids = new int[session.party.size()];
		for (int i = 0; i < ids.length; i++) {
			CompanionEntity member = member(session.player, session.party.get(i));
			ids[i] = member != null ? member.getId() : -1;
		}
		PlayNetwork.toPlayer(session.player, new PlayNetwork.State(ids, session.active, session.fallenTicks > 0));
	}

	@Nullable
	private static CompanionEntity active(Session session) {
		return session.active >= 0 && session.active < session.party.size() ? member(session.player, session.party.get(session.active)) : null;
	}

	@Nullable
	private static CompanionEntity member(ServerPlayer player, UUID id) {
		return player.level() instanceof ServerLevel level && level.getEntity(id) instanceof CompanionEntity companion ? companion : null;
	}

	// ---- Every tick, and the events that end it ------------------------------------------------------------------

	/** Server side, every tick: the fall countdown, the hidden party keeping up, her sprint. */
	public static void tick() {
		for (Iterator<Session> it = SESSIONS.values().iterator(); it.hasNext(); ) {
			Session session = it.next();
			ServerPlayer player = session.player;
			if (player.isRemoved() && !(player instanceof net.minecraftforge.common.util.FakePlayer)) {
				it.remove();
				finish(session);
				continue;
			}
			if (session.cooldown > 0) {
				session.cooldown--;
			}
			if (session.fallenTicks > 0) {
				player.setDeltaMovement(Vec3.ZERO);
				if (--session.fallenTicks == 0) {
					it.remove();
					finish(session);
				}
				continue;
			}
			CompanionEntity active = active(session);
			if (active == null || !active.isAlive() || player.getVehicle() != active) {
				// Something else got you out (a command, another mod): wrap it up the same way.
				it.remove();
				finish(session);
				continue;
			}
			active.pilotRunning = session.sprint && session.moving;
			active.pilotHitsAll = session.hitsAll;
			active.pilotDashing = active.pilotRunning && session.dash;
			for (UUID id : session.party) {
				CompanionEntity member = member(player, id);
				if (member != null && member != active && member.isOffField()) {
					member.moveTo(active.getX(), active.getY(), active.getZ(), active.getYRot(), 0.0F);
					member.setDeltaMovement(Vec3.ZERO);
				}
			}
		}
	}

	/**
	 * A hit meant for a player playing as a companion: it never lands on Steve (true: cancel it); a blow or a shot
	 * from something lands on her instead. Also true while her fall holds Steve hidden.
	 */
	public static boolean shield(Player player, DamageSource source, float amount) {
		if (isFallen(player)) {
			return true;
		}
		if (!(player.getVehicle() instanceof CompanionEntity companion)) {
			return false;
		}
		if (source.getDirectEntity() != null && !source.is(DamageTypeTags.IS_EXPLOSION) && source.getEntity() != companion) {
			companion.hurt(source, amount);
		}
		return true;
	}

	@SubscribeEvent
	public static void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase == TickEvent.Phase.END) {
			tick();
		}
	}

	@SubscribeEvent
	public static void onAttacked(LivingAttackEvent event) {
		if (event.getEntity() instanceof Player player && !player.level().isClientSide && shield(player, event.getSource(), event.getAmount())) {
			event.setCanceled(true);
		}
	}

	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		// Inside a companion with no session (came back that way after a crash): out you get.
		if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player && player.getVehicle() instanceof CompanionEntity
				&& !SESSIONS.containsKey(player.getUUID())) {
			player.stopRiding();
		}
	}

	@SubscribeEvent
	public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		Session session = SESSIONS.remove(event.getEntity().getUUID());
		if (session != null) {
			finish(session);
		}
	}

	@SubscribeEvent
	public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
		Session session = SESSIONS.remove(event.getEntity().getUUID());
		if (session != null) {
			finish(session);
		}
	}
}
