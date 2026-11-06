package com.freshorangeslices.enshining.core;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Particle shapes for skills and bursts, built from vanilla particles (server side, seen by everyone nearby). */
public final class Fx {
	private Fx() {
	}

	/** Points spread over a sphere (a shell), e.g. a shield around someone. */
	public static void sphere(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int points) {
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < points; i++) {
			double y = 1 - (i / (double) (points - 1)) * 2;
			double r = Math.sqrt(1 - y * y);
			double theta = golden * i;
			level.sendParticles(particle, center.x + Math.cos(theta) * r * radius, center.y + y * radius, center.z + Math.sin(theta) * r * radius,
					1, 0, 0, 0, 0);
		}
	}

	/** A few random points on a sphere: a shimmering shell when sent every few ticks. */
	public static void shimmer(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int points, RandomSource random) {
		for (int i = 0; i < points; i++) {
			double y = random.nextDouble() * 2 - 1;
			double r = Math.sqrt(1 - y * y);
			double theta = random.nextDouble() * Math.PI * 2;
			level.sendParticles(particle, center.x + Math.cos(theta) * r * radius, center.y + y * radius, center.z + Math.sin(theta) * r * radius,
					1, 0, 0, 0, 0);
		}
	}

	/** A flat ring at a height. */
	public static void ring(ServerLevel level, ParticleOptions particle, Vec3 center, double radius, int points) {
		for (int i = 0; i < points; i++) {
			double angle = (Math.PI * 2 * i) / points;
			level.sendParticles(particle, center.x + Math.cos(angle) * radius, center.y, center.z + Math.sin(angle) * radius, 1, 0, 0.02, 0, 0);
		}
	}

	/** A horizontal crescent in front of a point, facing yaw (degrees, Minecraft's): a weapon's swing trail. */
	public static void arc(ServerLevel level, ParticleOptions particle, Vec3 origin, float yaw, double radius, double spanDegrees, int points, double tilt) {
		double facing = Math.toRadians(yaw + 90);
		double half = Math.toRadians(spanDegrees / 2);
		for (int i = 0; i < points; i++) {
			double t = -half + (2 * half) * i / (points - 1.0);
			double angle = facing + t;
			level.sendParticles(particle, origin.x + Math.cos(angle) * radius, origin.y + Math.sin(t) * tilt, origin.z + Math.sin(angle) * radius,
					1, 0, 0, 0, 0);
		}
	}

	/** Shards of a block flying out, e.g. gold for Geo. */
	public static void shards(ServerLevel level, BlockState block, Vec3 at, int count, double spread) {
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block), at.x, at.y, at.z, count, spread, spread * 0.6, spread, 0.15);
	}
}
