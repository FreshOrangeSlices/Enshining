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
	public static final KeyMapping[] PARTY = {key("party_1", GLFW.GLFW_KEY_1), key("party_2", GLFW.GLFW_KEY_2),
			key("party_3", GLFW.GLFW_KEY_3), key("party_4", GLFW.GLFW_KEY_4)};

	/** Ticks of dash at the start of each sprint (the server keeps the same count for the animation). */
	private static final int DASH_TICKS = 10;
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
	private static final Map<KeyMapping, Boolean> HELD = new HashMap<>();

	private PlayAsClient() {
	}

	private static KeyMapping key(String name, int glfwKey) {
		return new KeyMapping("key.enshining." + name, WHILE_PLAYING, InputConstants.Type.KEYSYM, glfwKey, CATEGORY);
	}

	static List<KeyMapping> keys() {
		List<KeyMapping> all = new ArrayList<>(List.of(HOP_OUT, SPRINT));
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
			if (sprint && !sprintWas) {
				dashTicks = DASH_TICKS;
			} else if (!sprint) {
				dashTicks = 0;
			} else if (dashTicks > 0) {
				dashTicks--;
			}
			sprintWas = sprint;
			companion.pilotSprinting = sprint;
			companion.pilotDashTicks = dashTicks;
			companion.pilotJump = !screenOpen && player.input.jumping;
			byte flags = (byte) ((sprint ? PlayNetwork.Input.SPRINT : 0) | (moving ? PlayNetwork.Input.MOVING : 0));
			if (flags != lastFlags) {
				lastFlags = flags;
				PlayNetwork.toServer(new PlayNetwork.Input(flags));
			}
		}
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
