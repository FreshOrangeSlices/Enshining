package com.freshorangeslices.enshining.play;

import com.freshorangeslices.enshining.Enshining;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Messages for playing as a companion: your keys go to the server (hop out, switch, sprint), and the server tells you
 * your party and whether she just fell.
 */
public final class PlayNetwork {
	private static final String VERSION = "1";
	public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Enshining.MODID, "play"),
			() -> VERSION, VERSION::equals, VERSION::equals);

	private PlayNetwork() {
	}

	public static void register() {
		int id = 0;
		CHANNEL.messageBuilder(Input.class, id++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(Input::write).decoder(Input::read).consumerMainThread(Input::handle).add();
		CHANNEL.messageBuilder(Action.class, id++, NetworkDirection.PLAY_TO_SERVER)
				.encoder(Action::write).decoder(Action::read).consumerMainThread(Action::handle).add();
		CHANNEL.messageBuilder(State.class, id++, NetworkDirection.PLAY_TO_CLIENT)
				.encoder(State::write).decoder(State::read).consumerMainThread(State::handle).add();
	}

	public static void toServer(Object message) {
		CHANNEL.sendToServer(message);
	}

	public static void toPlayer(ServerPlayer player, Object message) {
		if (!(player instanceof FakePlayer) && player.connection != null) {
			CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
		}
	}

	/** Held movement keys that change how she moves: bit 1 sprint, bit 2 moving at all, bit 4 dashing. */
	public record Input(byte flags) {
		public static final byte SPRINT = 1, MOVING = 2, DASH = 4;

		void write(FriendlyByteBuf buf) {
			buf.writeByte(flags);
		}

		static Input read(FriendlyByteBuf buf) {
			return new Input(buf.readByte());
		}

		void handle(Supplier<NetworkEvent.Context> context) {
			ServerPlayer player = context.get().getSender();
			if (player != null) {
				PlayAs.input(player, (flags & SPRINT) != 0, (flags & MOVING) != 0, (flags & DASH) != 0);
			}
		}
	}

	/** A key press: hop out, switch to party slot {@code slot}, or one of her moves. */
	public record Action(byte action, byte slot) {
		public static final byte HOP_OUT = 0, SWITCH = 1, ATTACK = 2, CHARGE_BEGIN = 3, CHARGE_END = 4, SKILL = 5, BURST = 6,
				CANCEL = 7, INTERACT = 8;

		public Action(byte action) {
			this(action, (byte) 0);
		}

		void write(FriendlyByteBuf buf) {
			buf.writeByte(action);
			buf.writeByte(slot);
		}

		static Action read(FriendlyByteBuf buf) {
			return new Action(buf.readByte(), buf.readByte());
		}

		void handle(Supplier<NetworkEvent.Context> context) {
			ServerPlayer player = context.get().getSender();
			if (player == null) {
				return;
			}
			switch (action) {
				case HOP_OUT -> PlayAs.hopOut(player);
				case SWITCH -> PlayAs.switchTo(player, slot);
				default -> PlayAs.move(player, action);
			}
		}
	}

	/** Your party (entity ids, in slot order), which slot you are, and whether she just fell (empty party: not playing). */
	public record State(int[] party, int active, boolean fallen) {
		void write(FriendlyByteBuf buf) {
			buf.writeVarIntArray(party);
			buf.writeVarInt(active);
			buf.writeBoolean(fallen);
		}

		static State read(FriendlyByteBuf buf) {
			return new State(buf.readVarIntArray(), buf.readVarInt(), buf.readBoolean());
		}

		void handle(Supplier<NetworkEvent.Context> context) {
			DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.freshorangeslices.enshining.client.PlayAsClient.onState(party, active, fallen));
		}
	}
}
