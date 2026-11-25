package com.freshorangeslices.enshining.client;

import com.freshorangeslices.enshining.Enshining;
import com.freshorangeslices.enshining.core.CompanionEntity;
import com.freshorangeslices.enshining.play.PlayNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

/**
 * Playing as a companion, on your side: Genshin's keys while you're her (the vanilla ones that would clash are off),
 * a camera 6 blocks back that only stops at solid walls, Steve hidden, and the party down the right of the screen.
 */
@Mod.EventBusSubscriber(modid = Enshining.MODID, value = Dist.CLIENT)
public final class PlayAsClient {
	private static final String CATEGORY = "key.categories.enshining";

	/** Our keys count only while you're her, and never clash with vanilla's (those are off then). */
	private static final IKeyConflictContext WHILE_PLAYING = new IKeyConflictContext() {
		@Override
		public boolean isActive() {
			return inControl();
		}

		@Override
		public boolean conflicts(IKeyConflictContext other) {
			return false;
		}
	};

	public static final KeyMapping HOP_OUT = key("hop_out", GLFW.GLFW_KEY_J);
	public static final KeyMapping SPRINT = key("sprint", GLFW.GLFW_KEY_LEFT_SHIFT);
	public static final KeyMapping ATTACK = new KeyMapping("key.enshining.attack", WHILE_PLAYING, InputConstants.Type.MOUSE,
			GLFW.GLFW_MOUSE_BUTTON_LEFT, CATEGORY);
	public static final KeyMapping SKILL = key("skill", GLFW.GLFW_KEY_E);
	public static final KeyMapping BURST = key("burst", GLFW.GLFW_KEY_Q);
	public static final KeyMapping INTERACT = key("interact", GLFW.GLFW_KEY_F);
	public static final KeyMapping[] PARTY = {key("party_1", GLFW.GLFW_KEY_1), key("party_2", GLFW.GLFW_KEY_2),
			key("party_3", GLFW.GLFW_KEY_3), key("party_4", GLFW.GLFW_KEY_4)};

	/** Ticks of dash at the start of each sprint (she has i-frames for them), and the least time between two dashes. */
	private static final int DASH_TICKS = 10, DASH_COOLDOWN = 20;
	private static final float ZOOM_MIN = 3.0F, ZOOM_MAX = 10.0F;

	private static int[] party = new int[0];
	private static int active;
	private static boolean fallen;
	private static boolean wasInControl;
	private static CameraType cameraBefore = CameraType.FIRST_PERSON;
	private static float zoom = 6.0F;
	private static byte lastFlags = -1;
	private static int dashTicks;
	private static boolean sprintWas;
	private static int lastDash = -1000;
	/** Left click: held this many ticks becomes the charged attack (a shorter press is a normal hit). */
	private static final int CHARGE_HOLD = 6;
	private static int attackHeld;
	private static boolean charging;
	private static boolean jumpWas;
	private static int holdingCancelSent = -1000;
	private static final Map<KeyMapping, Boolean> HELD = new HashMap<>();

	private PlayAsClient() {
	}

	private static KeyMapping key(String name, int glfwKey) {
		return new KeyMapping("key.enshining." + name, WHILE_PLAYING, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
	}

	static List<KeyMapping> keys() {
		List<KeyMapping> all = new ArrayList<>(List.of(HOP_OUT, SPRINT, ATTACK, SKILL, BURST, INTERACT));
		all.addAll(Arrays.asList(PARTY));
		return all;
	}

	/** You're her right now (riding inside her). */
	public static boolean playing() {
		Player player = Minecraft.getInstance().player;
		return player != null && player.getVehicle() instanceof CompanionEntity;
	}

	/**
	 * You're her, or she just fell and Steve is about to pop out, or you're mid-switch between two of them (the party
	 * from the server stays until you're Steve again).
	 */
	public static boolean inControl() {
		return playing() || fallen || party.length > 0;
	}

	/** From the server: your party, which slot you are, whether she fell (an empty party: you're Steve again). */
	public static void onState(int[] newParty, int newActive, boolean newFallen) {
		party = newParty;
		active = newActive;
		fallen = newFallen && newParty.length > 0;
	}

	// ---- Keys -----------------------------------------------------------------------------------------------

	/** The vanilla keys that are off while you're her: everything but moving, jumping, chat, the pause menu and F1-F3. */
	private static List<KeyMapping> blocked(Options o) {
		List<KeyMapping> list = new ArrayList<>(List.of(o.keyInventory, o.keyDrop, o.keySwapOffhand, o.keyShift, o.keySprint,
				o.keyTogglePerspective, o.keyAttack, o.keyUse, o.keyPickItem, o.keyAdvancements, o.keySaveHotbarActivator,
				o.keyLoadHotbarActivator, o.keySocialInteractions, o.keySpectatorOutlines));
		list.addAll(Arrays.asList(o.keyHotbarSlots));
		return list;
	}

	/** Read straight from the keyboard, so a vanilla key on the same button can't swallow it. */
	private static boolean down(KeyMapping mapping) {
		InputConstants.Key key = mapping.getKey();
		long window = Minecraft.getInstance().getWindow().getWindow();
		if (key.getValue() < 0) {
			return false;
		}
		if (key.getType() == InputConstants.Type.MOUSE) {
			return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
		}
		return InputConstants.isKeyDown(window, key.getValue());
	}

	/** Pressed since last tick (and no menu open). */
	private static boolean pressed(KeyMapping mapping, boolean screenOpen) {
		boolean now = down(mapping);
		Boolean was = HELD.put(mapping, now);
		return now && (was == null || !was) && !screenOpen;
	}

	@SubscribeEvent
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.START) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			party = new int[0];
			fallen = false;
			wasInControl = false;
			return;
		}
		boolean on = inControl();
		if (on && !wasInControl) {
			cameraBefore = mc.options.getCameraType();
			lastFlags = -1;
		} else if (!on && wasInControl) {
			mc.options.setCameraType(cameraBefore);
			dashTicks = 0;
		}
		wasInControl = on;
		if (!on) {
			return;
		}
		if (mc.options.getCameraType() != CameraType.THIRD_PERSON_BACK) {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		}
		for (KeyMapping mapping : blocked(mc.options)) {
			while (mapping.consumeClick()) {
				// swallowed
			}
			mapping.setDown(false);
		}
		boolean screenOpen = mc.screen != null;
		if (pressed(HOP_OUT, screenOpen)) {
			PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.HOP_OUT, (byte) 0));
		}
		for (int i = 0; i < PARTY.length; i++) {
			if (pressed(PARTY[i], screenOpen) && i < party.length && i != active) {
				PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.SWITCH, (byte) i));
			}
		}
		if (player.getVehicle() instanceof CompanionEntity companion) {
			boolean moving = Math.abs(player.zza) > 1.0E-3F || Math.abs(player.xxa) > 1.0E-3F;
			boolean sprint = !screenOpen && down(SPRINT) && moving;
			fight(mc, player, companion, screenOpen, moving);
			if (sprint && !sprintWas) {
				// Genshin's dash, once a second at most (stamina will limit it later). It cuts a hit short.
				if (player.tickCount - lastDash >= DASH_COOLDOWN || player.tickCount < lastDash) {
					dashTicks = DASH_TICKS;
					lastDash = player.tickCount;
					cancel(companion);
				}
			} else if (!sprint) {
				dashTicks = 0;
			} else if (dashTicks > 0) {
				dashTicks--;
			}
			sprintWas = sprint;
			companion.pilotSprinting = sprint;
			companion.pilotDashTicks = dashTicks;
			companion.pilotJump = !screenOpen && player.input.jumping;
			byte flags = (byte) ((sprint ? PlayNetwork.Input.SPRINT : 0) | (moving ? PlayNetwork.Input.MOVING : 0)
					| (dashTicks > 0 ? PlayNetwork.Input.DASH : 0));
			if (flags != lastFlags) {
				lastFlags = flags;
				PlayNetwork.toServer(new PlayNetwork.Input(flags));
			}
		}
	}

	/**
	 * Her moves from your keys. Left click: a tap is her next hit, held past {@link #CHARGE_HOLD} ticks it's the charged
	 * attack (let go to finish). E skill, Q burst, F the door or chest you look at. A jump cuts a hit short, and so does
	 * walking off while she holds a pose or plays her ending.
	 */
	private static void fight(Minecraft mc, LocalPlayer player, CompanionEntity companion, boolean screenOpen, boolean moving) {
		boolean attack = !screenOpen && down(ATTACK);
		if (attack) {
			attackHeld++;
			if (!charging && attackHeld == CHARGE_HOLD) {
				charging = true;
				aim(player, companion);
				companion.pilotCancelLocal();
				PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.CHARGE_BEGIN));
			}
		} else if (attackHeld > 0) {
			if (charging) {
				PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.CHARGE_END));
			} else {
				aim(player, companion);
				PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.ATTACK));
			}
			attackHeld = 0;
			charging = false;
		}
		if (pressed(SKILL, screenOpen)) {
			companion.pilotCancelLocal();
			PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.SKILL));
		}
		if (pressed(BURST, screenOpen)) {
			aim(player, companion);
			companion.pilotCancelLocal();
			PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.BURST));
		}
		if (pressed(INTERACT, screenOpen)) {
			PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.INTERACT));
		}
		boolean jump = !screenOpen && player.input.jumping;
		if (jump && !jumpWas) {
			cancel(companion);
		}
		jumpWas = jump;
		// Walking off while she holds a hit's pose or plays her ending: that's over (asked again at most every 5 ticks,
		// while the server's answer is on its way).
		if (moving && companion.pilotHolding() && (player.tickCount - holdingCancelSent >= 5 || player.tickCount < holdingCancelSent)) {
			holdingCancelSent = player.tickCount;
			cancel(companion);
		}
	}

	/** Cut her hit (or held pose, or ending) short: the combo starts over at hit 1. */
	private static void cancel(CompanionEntity companion) {
		if (companion.pilotInHit() || companion.pilotHolding()) {
			companion.pilotCancelLocal();
			PlayNetwork.toServer(new PlayNetwork.Action(PlayNetwork.Action.CANCEL));
		}
	}

	/**
	 * Where a hit, charge or burst goes, Genshin-style: at the nearest enemy within 5 blocks in front of the camera
	 * (60 degrees either side), else the way your keys point, else the way she already faces.
	 */
	private static void aim(LocalPlayer player, CompanionEntity companion) {
		float camera = player.getYRot();
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity e : companion.level().getEntities(companion, companion.getBoundingBox().inflate(5.0),
				e -> e instanceof net.minecraft.world.entity.monster.Enemy && e.isAlive())) {
			double yaw = Mth.atan2(e.getZ() - companion.getZ(), e.getX() - companion.getX()) * Mth.RAD_TO_DEG - 90.0;
			double distance = companion.distanceToSqr(e);
			if (Math.abs(Mth.wrapDegrees(yaw - camera)) <= 60.0 && distance < bestDistance && distance <= 25.0) {
				best = e;
				bestDistance = distance;
			}
		}
		float want;
		if (best != null) {
			want = (float) (Mth.atan2(best.getZ() - companion.getZ(), best.getX() - companion.getX()) * Mth.RAD_TO_DEG) - 90.0F;
		} else if (Math.abs(player.zza) > 1.0E-3F || Math.abs(player.xxa) > 1.0E-3F) {
			want = CompanionEntity.inputYaw(camera, player.zza, player.xxa);
		} else {
			return;
		}
		companion.pilotAimYaw = want;
		companion.pilotAimTicks = 4;
	}

	/** Never sneak (that would hop you out the vanilla way); while she's fallen, Steve stays put. */
	@SubscribeEvent
	public static void onMovementInput(MovementInputUpdateEvent event) {
		if (!inControl()) {
			return;
		}
		event.getInput().shiftKeyDown = false;
		if (fallen) {
			event.getInput().forwardImpulse = 0;
			event.getInput().leftImpulse = 0;
			event.getInput().jumping = false;
		}
	}

	/** Vanilla attack, use and pick-block are off while you're her (her own attacks come with the next step). */
	@SubscribeEvent
	public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
		if (inControl()) {
			event.setCanceled(true);
			event.setSwingHand(false);
		}
	}

	/** The scroll wheel zooms the camera instead of changing the hotbar slot. */
	@SubscribeEvent
	public static void onScroll(InputEvent.MouseScrollingEvent event) {
		if (inControl() && Minecraft.getInstance().screen == null) {
			zoom = Mth.clamp(zoom - (float) event.getScrollDelta(), ZOOM_MIN, ZOOM_MAX);
			event.setCanceled(true);
		}
	}

	// ---- Camera ---------------------------------------------------------------------------------------------

	/**
	 * How far back the camera sits (the camera mixin asks): your zoom (6 blocks to start), pulled in only by solid,
	 * full blocks between her and it. Glass, leaves, plants, fences and the like don't stop it.
	 */
	public static double cameraDistance(Camera camera) {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return zoom;
		}
		Vec3 pivot = camera.getPosition();
		Vector3f look = camera.getLookVector();
		double best = zoom;
		for (int i = 0; i < 8; i++) {
			double ox = ((i & 1) * 2 - 1) * 0.1;
			double oy = ((i >> 1 & 1) * 2 - 1) * 0.1;
			double oz = ((i >> 2 & 1) * 2 - 1) * 0.1;
			for (double d = 0.3; d < best; d += 0.1) {
				BlockPos pos = BlockPos.containing(pivot.x - look.x() * d + ox, pivot.y - look.y() * d + oy, pivot.z - look.z() * d + oz);
				if (level.getBlockState(pos).isSolidRender(level, pos)) {
					best = Math.max(0.3, d - 0.2);
					break;
				}
			}
		}
		return best;
	}

	// ---- Steve hidden, and the HUD --------------------------------------------------------------------------

	/** Steve isn't drawn while he's her (anyone's Steve: other players see only her), nor while she's fallen. */
	@SubscribeEvent
	public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
		Player player = event.getEntity();
		if (player.getVehicle() instanceof CompanionEntity || player == Minecraft.getInstance().player && fallen) {
			event.setCanceled(true);
		}
	}

	/** Steve's hotbar, hearts, food, armor, XP and crosshair go; her health (vanilla's mount hearts) stays. */
	@SubscribeEvent
	public static void onOverlay(RenderGuiOverlayEvent.Pre event) {
		if (!inControl()) {
			return;
		}
		var overlay = event.getOverlay();
		if (overlay == VanillaGuiOverlay.HOTBAR.type() || overlay == VanillaGuiOverlay.PLAYER_HEALTH.type()
				|| overlay == VanillaGuiOverlay.FOOD_LEVEL.type() || overlay == VanillaGuiOverlay.ARMOR_LEVEL.type()
				|| overlay == VanillaGuiOverlay.EXPERIENCE_BAR.type() || overlay == VanillaGuiOverlay.AIR_LEVEL.type()
				|| overlay == VanillaGuiOverlay.CROSSHAIR.type() || overlay == VanillaGuiOverlay.ITEM_NAME.type()
				|| overlay == VanillaGuiOverlay.JUMP_BAR.type()) {
			event.setCanceled(true);
		}
	}

	/** Everything on screen while you're her: the party down the right, her E and Q in the corner. */
	static void renderHud(GuiGraphics g, int screenWidth, int screenHeight) {
		renderParty(g, screenWidth, screenHeight);
		renderAbilities(g, screenWidth, screenHeight);
	}

	/** Her skill (E: seconds of cooldown left, or ready) and burst (Q: fills with energy, gold when ready). */
	static void renderAbilities(GuiGraphics g, int screenWidth, int screenHeight) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !(mc.player.getVehicle() instanceof CompanionEntity companion) || mc.options.hideGui) {
			return;
		}
		int size = 22;
		int y = screenHeight - size - 10;
		int qx = screenWidth - size - 10;
		int ex = qx - size - 6;
		int cooldown = companion.skillCooldownShown();
		g.fill(ex, y, ex + size, y + size, 0xA0000000);
		g.renderOutline(ex, y, size, size, cooldown > 0 ? 0xFF555555 : 0xFFF2D27A);
		String eText = cooldown > 0 ? String.valueOf((cooldown + 19) / 20) : "E";
		g.drawCenteredString(mc.font, eText, ex + size / 2, y + 7, cooldown > 0 ? 0xFFAAAAAA : 0xFFF2D27A);
		int cost = Math.max(1, companion.burstEnergyCost());
		float fill = Math.min(1.0F, companion.energyShown() / (float) cost);
		boolean ready = fill >= 1.0F;
		g.fill(qx, y, qx + size, y + size, 0xA0000000);
		int fillTop = y + size - Math.round(size * fill);
		g.fill(qx + 1, fillTop, qx + size - 1, y + size - 1, ready ? 0xC0D6AA40 : 0x806A5A30);
		g.renderOutline(qx, y, size, size, ready ? 0xFFF2D27A : 0xFF555555);
		g.drawCenteredString(mc.font, "Q", qx + size / 2, y + 7, ready ? 0xFFFFFFFF : 0xFFAAAAAA);
		if (cooldown > 0) {
			g.drawString(mc.font, "E", ex + 2, y + size - 9, 0x80FFFFFF);
		}
	}

	/** The party down the right, Genshin-style: slot number and name, the one you are in gold. */
	static void renderParty(GuiGraphics g, int screenWidth, int screenHeight) {
		Minecraft mc = Minecraft.getInstance();
		if (!inControl() || party.length == 0 || mc.level == null || mc.options.hideGui) {
			return;
		}
		int lineHeight = 14;
		int top = screenHeight / 2 - party.length * lineHeight / 2;
		for (int i = 0; i < party.length; i++) {
			Entity member = mc.level.getEntity(party[i]);
			Component name = member != null ? member.getDisplayName() : Component.literal("?");
			boolean me = i == active;
			boolean down = member == null || !member.isAlive();
			Component line = Component.literal((i + 1) + "  ").append(name);
			int width = mc.font.width(line);
			int x = screenWidth - width - 8;
			int y = top + i * lineHeight;
			g.fill(x - 4, y - 2, screenWidth - 4, y + 10, me ? 0x80403418 : 0x60000000);
			g.drawString(mc.font, line, x, y, down ? 0xFF777777 : me ? 0xFFF2D27A : 0xFFEDE6D6);
		}
	}
}
