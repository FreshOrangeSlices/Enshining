package com.freshorangeslices.enshining.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.freshorangeslices.enshining.core.CompanionEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/**
 * Moving eyes (an experiment, on the test dummy only; no character uses it yet). The build, from the Linky Playz
 * short: each eye is a white plane ("eye_left" / "eye_right") with a pupil plane in front of it ("pupil_left" /
 * "pupil_right", inside the eye group). The behaviour, after Fresh Animations' mobs: the head turns toward what
 * she looks at as far as it may and the pupils take the rest; she meets your eyes when you look at her (or her
 * owner comes close), glances around when nothing holds her attention, and blinks, dozes and sleeps by squashing
 * the eye flat, on the same timeline as the painted eye sheets. Pupils shrink when she's hurt, grow when happy,
 * and never leave the white: how far they travel comes from the two planes' sizes.
 */
final class EyeRig {
	private static final float SIDE_ANGLE = 30.0F * Mth.DEG_TO_RAD, UP_ANGLE = 25.0F * Mth.DEG_TO_RAD;

	private EyeRig() {
	}

	static <T extends CompanionEntity> void apply(CompanionModel<T> model, T companion, float time, float partialTick, int expression) {
		var bones = model.getAnimationProcessor();
		CoreGeoBone[] eyes = {bones.getBone("eye_left"), bones.getBone("eye_right")};
		CoreGeoBone[] pupils = {bones.getBone("pupil_left"), bones.getBone("pupil_right")};
		if (eyes[0] == null && eyes[1] == null) {
			return;
		}
		float dt = time - companion.eyeLastTime;
		boolean snap = companion.eyeLastTime < 0 || dt < 0 || dt > 4.0F;
		if (dt == 0) {
			dt = 0.001F;
		}
		companion.eyeLastTime = time;

		// ---- where to look: your eyes, else straight where her head means to look, plus idle glances ----
		float fx = 0, fy = 0;
		boolean held = false;
		Matrix4f head = companion.eyeHeadMatrix;
		if (head != null && companion.level().getEntity(companion.getId()) == companion) {
			Player contact = eyeContact(companion, partialTick);
			Vec3 point;
			if (contact != null) {
				point = contact.getEyePosition(partialTick);
				held = true;
			} else {
				Vec3 dir = Vec3.directionFromRotation(companion.getViewXRot(partialTick), companion.getViewYRot(partialTick));
				point = companion.getEyePosition(partialTick).add(dir.scale(8.0));
				held = companion.inCombat();
			}
			Vector4f local = new Matrix4f(head).invert().transform(new Vector4f((float) point.x, (float) point.y, (float) point.z, 1));
			float lx = local.x(), ly = local.y() - 3.0F / 16, lz = local.z() + 4.0F / 16;   // from between her eyes
			fx = Mth.clamp((float) Math.atan2(lx, -lz) / SIDE_ANGLE, -1, 1);
			fy = Mth.clamp((float) Math.atan2(ly, Math.sqrt(lx * lx + lz * lz)) / UP_ANGLE, -1, 1);
		}
		if (!held) {
			if (companion.tickCount >= companion.eyeGlanceUntil) {
				var random = companion.getRandom();
				boolean ahead = random.nextFloat() < 0.4F;
				companion.eyeGlanceX = ahead ? 0 : (random.nextFloat() * 2 - 1) * 0.8F;
				companion.eyeGlanceY = ahead ? 0 : (random.nextFloat() * 2 - 1) * 0.5F;
				companion.eyeGlanceUntil = companion.tickCount + 25 + random.nextInt(45);
			}
			fx = Mth.clamp(fx + companion.eyeGlanceX, -1, 1);
			fy = Mth.clamp(fy + companion.eyeGlanceY, -1, 1);
		}

		// ---- how open, and how big the pupils ----
		float open = switch (expression) {
			case CompanionModel.CLOSED -> 0.08F;
			case CompanionModel.HALF -> 0.45F;
			case CompanionModel.HAPPY -> 0.5F;
			default -> 1.0F;
		};
		float size = companion.hurtTime > 0 ? 0.6F : expression == CompanionModel.HAPPY ? 1.15F : 1.0F;

		float quick = snap ? 1 : 1 - (float) Math.exp(-1.4 * dt);   // eyes dart
		float lids = snap ? 1 : 1 - (float) Math.exp(-1.6 * dt);
		float slow = snap ? 1 : 1 - (float) Math.exp(-0.5 * dt);
		companion.eyeLookX += (fx - companion.eyeLookX) * quick;
		companion.eyeLookY += (fy - companion.eyeLookY) * quick;
		companion.eyeOpen += (open - companion.eyeOpen) * lids;
		companion.pupilSize += (size - companion.pupilSize) * slow;

		for (int i = 0; i < 2; i++) {
			CoreGeoBone eye = eyes[i];
			if (eye == null) {
				continue;
			}
			eye.setScaleY(eye.getInitialSnapshot().getScaleY() * companion.eyeOpen);
			CoreGeoBone pupil = pupils[i];
			if (!(pupil instanceof GeoBone p) || !(eye instanceof GeoBone w)) {
				continue;
			}
			Vector3f white = size(w), dot = size(p);
			// pixels the pupil may travel each way, at its current size, without leaving the white
			float rangeX = Math.max(0, (white.x() - dot.x() * companion.pupilSize) / 2) * 16;
			float rangeY = Math.max(0, (white.y() - dot.y() * companion.pupilSize) / 2) * 16;
			// GeckoLib moves bones by -X, so her right (+X) is a negative offset
			pupil.setPosX(pupil.getInitialSnapshot().getOffsetX() - companion.eyeLookX * rangeX);
			pupil.setPosY(pupil.getInitialSnapshot().getOffsetY() + companion.eyeLookY * rangeY);
			pupil.setScaleX(pupil.getInitialSnapshot().getScaleX() * companion.pupilSize);
			pupil.setScaleY(pupil.getInitialSnapshot().getScaleY() * companion.pupilSize);
		}
	}

	/**
	 * Who she meets the eyes of: a player within 8 blocks looking right at her, or her owner within 4 blocks;
	 * held for 2 seconds after they look away. Null: nobody.
	 */
	private static Player eyeContact(CompanionEntity companion, float partialTick) {
		Vec3 her = companion.getEyePosition(partialTick);
		Player best = null;
		double bestDist = Double.MAX_VALUE;
		for (Player player : companion.level().players()) {
			if (player.isSpectator()) {
				continue;
			}
			Vec3 eye = player.getEyePosition(partialTick);
			double dist = eye.distanceTo(her);
			boolean looking = dist < 8 && player.getViewVector(partialTick).dot(her.subtract(eye).normalize()) > 0.94;
			boolean owner = player == companion.getOwner() && dist < 4;
			if ((looking || owner) && dist < bestDist) {
				best = player;
				bestDist = dist;
			}
		}
		if (best != null) {
			companion.eyeContactId = best.getId();
			companion.eyeContactUntil = companion.tickCount + 40;
			return best;
		}
		if (companion.tickCount < companion.eyeContactUntil
				&& companion.level().getEntity(companion.eyeContactId) instanceof Player held && held.distanceTo(companion) < 10) {
			return held;
		}
		return null;
	}

	/** A bone's first cube's size (render units), before any rotation. */
	private static Vector3f size(GeoBone bone) {
		if (bone.getCubes().isEmpty()) {
			return new Vector3f();
		}
		GeoCube cube = bone.getCubes().get(0);
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (GeoQuad quad : cube.quads()) {
			if (quad == null) {
				continue;
			}
			for (GeoVertex v : quad.vertices()) {
				b[0] = Math.min(b[0], v.position().x());
				b[1] = Math.min(b[1], v.position().y());
				b[2] = Math.min(b[2], v.position().z());
				b[3] = Math.max(b[3], v.position().x());
				b[4] = Math.max(b[4], v.position().y());
				b[5] = Math.max(b[5], v.position().z());
			}
		}
		return new Vector3f(b[3] - b[0], b[4] - b[1], b[5] - b[2]);
	}
}
