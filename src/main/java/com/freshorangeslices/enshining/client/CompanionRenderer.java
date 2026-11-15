package com.freshorangeslices.enshining.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.freshorangeslices.enshining.core.CompanionEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.BlockAndItemGeoLayer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

/**
 * Every companion drawn the normal (cutout) way, which keeps overlapping cloth and armor sorting correctly, plus:
 * a "weapon_aura" bone drawn in its own see-through glowing pass while {@link CompanionEntity#weaponAuraActive()},
 * a swapped-in weapon (her menu's weapon slot) drawn in her hand or hovering at "weapon_back",
 * and the "skirt" drawn as one connected ring of panels (see {@link SkirtRing}), from its own texture
 * ("NAME_skirt.png" next to "NAME.png") when the character has one.
 */
public class CompanionRenderer<T extends CompanionEntity> extends GeoEntityRenderer<T> {
	private static final String AURA = "weapon_aura";
	private static final String SKIRT = "skirt";
	private static final int FULL_BRIGHT = 0xF000F0;
	/**
	 * Most the skirt swings with the legs (radians, 20 degrees) when not flaring; the more it flares, the less it may
	 * swing (none at the full 12-degree flare). Tested gap-free on the 2-px panel skirt with 25% overlap.
	 */
	private static final float SKIRT_MAX_SWING = 0.349F, SKIRT_SWING_PER_FLARE = 1.9F;
	private boolean auraPass;
	private boolean skirtPass;
	private final Map<ResourceLocation, Optional<ResourceLocation>> skirtTextures = new HashMap<>();
	@Nullable
	private BakedGeoModel ringModel;
	@Nullable
	private SkirtRing ring;

	public CompanionRenderer(EntityRendererProvider.Context context, CompanionModel<T> model) {
		super(context, model);
		addRenderLayer(new GeoRenderLayer<>(this) {
			@Override
			public void render(PoseStack poseStack, T companion, BakedGeoModel model, RenderType renderType,
					MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
				ResourceLocation texture = skirtTexture(companion);
				if (texture == null || model.getBone(SKIRT).isEmpty()) {
					return;
				}
				// Culled, so each panel shows its outside from outside and its lining from inside (no flicker).
				RenderType skirt = RenderType.entityCutout(texture);
				skirtPass = true;
				try {
					getRenderer().reRender(model, poseStack, bufferSource, companion, skirt, bufferSource.getBuffer(skirt), partialTick,
							packedLight, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
				} finally {
					skirtPass = false;
				}
			}
		});
		addRenderLayer(new GeoRenderLayer<>(this) {
			@Override
			public void render(PoseStack poseStack, T companion, BakedGeoModel model, RenderType renderType,
					MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
				if (!companion.weaponAuraActive() || model.getBone(AURA).isEmpty()) {
					return;
				}
				RenderType aura = RenderType.entityTranslucentEmissive(getTextureLocation(companion));
				auraPass = true;
				try {
					getRenderer().reRender(model, poseStack, bufferSource, companion, aura, bufferSource.getBuffer(aura), partialTick,
							FULL_BRIGHT, packedOverlay, 1.0F, 1.0F, 1.0F, 1.0F);
				} finally {
					auraPass = false;
				}
			}
		});
		addRenderLayer(new BlockAndItemGeoLayer<>(this) {
			@Nullable
			@Override
			protected ItemStack getStackForBone(GeoBone bone, T companion) {
				if (!companion.holdsSwappedWeapon() || !companion.setting(CompanionEntity.SHOW_WEAPON)) {
					return null;
				}
				return switch (bone.getName()) {
					case "right_hand" -> companion.showsWeaponInHand() ? companion.getMainHandItem() : null;
					case "weapon_back" -> companion.showBackWeapon() ? companion.getMainHandItem() : null;
					default -> null;
				};
			}

			@Override
			protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, T companion) {
				return ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			}

			@Override
			protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T companion,
					MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
				poseStack.pushPose();
				if ("right_hand".equals(bone.getName())) {
					// Held like a player holds it: blade forward out of the fist.
					poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
					poseStack.translate(0.0, 0.1, 0.0);
				} else {
					// Hovering behind her: point down.
					poseStack.mulPose(Axis.XP.rotationDegrees(180.0F));
				}
				super.renderStackForBone(poseStack, bone, stack, companion, bufferSource, partialTick, packedLight, packedOverlay);
				poseStack.popPose();
			}
		});
	}

	private static boolean isOwnWeaponModel(GeoBone bone) {
		for (GeoBone b = bone; b != null; b = b.getParent()) {
			if ("weapon".equals(b.getName()) || "weapon_back".equals(b.getName())) {
				return true;
			}
		}
		return false;
	}

	private static boolean inSkirt(GeoBone bone) {
		for (GeoBone b = bone; b != null; b = b.getParent()) {
			if (SKIRT.equals(b.getName())) {
				return true;
			}
		}
		return false;
	}

	/** "NAME_skirt.png" beside the character's texture, if the resource pack has one. */
	@Nullable
	private ResourceLocation skirtTexture(T companion) {
		ResourceLocation main = getTextureLocation(companion);
		return skirtTextures.computeIfAbsent(main, tex -> {
			ResourceLocation skirt = new ResourceLocation(tex.getNamespace(), tex.getPath().replace(".png", "_skirt.png"));
			return Minecraft.getInstance().getResourceManager().getResource(skirt).map(found -> skirt);
		}).orElse(null);
	}

	@Override
	public void actuallyRender(PoseStack poseStack, T companion, BakedGeoModel model, RenderType renderType,
			MultiBufferSource bufferSource, VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
			int packedOverlay, float red, float green, float blue, float alpha) {
		if (model != ringModel) {  // first frame, or the resources were reloaded
			ringModel = model;
			ring = SkirtRing.of(model.getBone(SKIRT).orElse(null));
			skirtTextures.clear();
		}
		super.actuallyRender(poseStack, companion, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight,
				packedOverlay, red, green, blue, alpha);
	}

	@Override
	public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		// Each pass draws only its own cubes: the body skips the aura, the aura pass skips the body.
		if (AURA.equals(bone.getName()) != auraPass) {
			return;
		}
		// A swapped-in weapon replaces her own weapon model, in hand and hovering behind her.
		if (animatable != null && animatable.holdsSwappedWeapon() && isOwnWeaponModel(bone)) {
			return;
		}
		boolean skirt = !auraPass && ring != null && inSkirt(bone);
		// With its own texture, the skirt is drawn in its own pass only.
		if (animatable != null && skirtTexture(animatable) != null && skirt != skirtPass) {
			return;
		}
		if (skirt && animatable != null) {
			renderSkirtCubes(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
			return;
		}
		super.renderCubesOfBone(poseStack, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
	}

	/**
	 * Each skirt panel hinges at its own spot on the waist (the lower tiers and frill hang from that same spot, so
	 * they follow the tier above). How far it swings changes smoothly around the ring, so neighbours always move
	 * nearly the same and the panels' overlap covers the difference: no gaps open.
	 */
	private void renderSkirtCubes(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		if (bone.isHidden()) {
			return;
		}
		float flare = animatable.skirtFlare;
		float limit = Math.max(0.0F, SKIRT_MAX_SWING - SKIRT_SWING_PER_FLARE * flare);
		float front = Math.min(animatable.skirtFront, limit);
		float back = Math.max(animatable.skirtBack, -limit);
		Quaternionf turn = new Quaternionf();
		for (GeoCube cube : bone.getCubes()) {
			poseStack.pushPose();
			float[] c = ring.columns.get(cube);
			if (c != null) {
				// Front columns take the forward leg's swing, back ones the back leg's, fading to none at the sides.
				float swing = front * c[5] + back * c[6];
				if (swing != 0 || flare != 0) {
					poseStack.translate(c[0], c[1], c[2]);
					// Flare: tip the column outward about the waist line it hangs from.
					poseStack.mulPose(turn.rotationAxis(flare, -c[4], 0.0F, c[3]));
					poseStack.mulPose(Axis.XP.rotation(swing));
					poseStack.translate(-c[0], -c[1], -c[2]);
				}
			}
			renderCube(poseStack, cube, buffer, packedLight, packedOverlay, red, green, blue, alpha);
			poseStack.popPose();
		}
	}

	/**
	 * Where each skirt panel hangs from: the top ring (the highest panels) gives the waist line; every panel gets
	 * the point on it straight above, and the outward direction it faces. Worked out once per model.
	 */
	static final class SkirtRing {
		/**
		 * Per cube: hinge x, y, z (in render units), the outward direction it faces dx, dz, and how much of the
		 * front / back swing it takes: (how far front or back it sits)^2, so the sides barely move and the swing
		 * fades in gently there (where neighbouring panels would otherwise slide apart).
		 */
		final Map<GeoCube, float[]> columns = new IdentityHashMap<>();

		@Nullable
		static SkirtRing of(@Nullable GeoBone skirt) {
			if (skirt == null) {
				return null;
			}
			List<GeoCube> cubes = new ArrayList<>();
			collect(skirt, cubes);
			List<float[]> tops = new ArrayList<>();   // x, y, z: middle of each panel's top edge
			for (GeoCube cube : cubes) {
				tops.add(top(cube));
			}
			if (tops.isEmpty()) {
				return null;
			}
			float topY = Float.NEGATIVE_INFINITY;
			for (float[] t : tops) {
				topY = Math.max(topY, t[1]);
			}
			// The waist: panels whose top is within a third of a pixel of the highest.
			List<float[]> waist = new ArrayList<>();
			float cx = 0, cz = 0, y = 0;
			for (float[] t : tops) {
				if (t[1] >= topY - 0.3F / 16.0F) {
					waist.add(t);
					cx += t[0];
					cz += t[2];
					y += t[1];
				}
			}
			cx /= waist.size();
			cz /= waist.size();
			y /= waist.size();
			float centerX = cx, centerZ = cz;
			waist.sort((a, b) -> Float.compare(angle(a, centerX, centerZ), angle(b, centerX, centerZ)));
			float[] angles = new float[waist.size()];
			for (int i = 0; i < angles.length; i++) {
				angles[i] = angle(waist.get(i), cx, cz);
			}
			float depth = 1.0E-4F;
			for (float[] t : waist) {
				depth = Math.max(depth, Math.abs(t[2] - cz));
			}
			SkirtRing ring = new SkirtRing();
			for (int i = 0; i < cubes.size(); i++) {
				float[] t = tops.get(i);
				float[] hinge = onWaist(waist, angles, angle(t, cx, cz));
				float rx = t[0] - cx, rz = t[2] - cz;
				float len = Mth.sqrt(rx * rx + rz * rz);
				if (len < 1.0E-4F) {
					continue;
				}
				rx /= len;
				rz /= len;
				float[] facing = facing(cubes.get(i), rx, rz);
				float side = Mth.clamp((hinge[1] - cz) / depth, -1.0F, 1.0F);
				ring.columns.put(cubes.get(i), new float[] {hinge[0], y, hinge[1], facing[0], facing[1],
						side < 0 ? side * side : 0.0F, side > 0 ? side * side : 0.0F});
			}
			return ring;
		}

		private static void collect(GeoBone bone, List<GeoCube> out) {
			out.addAll(bone.getCubes());
			for (GeoBone child : bone.getChildBones()) {
				collect(child, out);
			}
		}

		/**
		 * Which way a flat panel faces (the normal of its flat side, turned the way it's drawn), pointed outward;
		 * anything not flat just faces away from the middle.
		 */
		private static float[] facing(GeoCube cube, float rx, float rz) {
			Vec3 size = cube.size();
			Vector3f n;
			if (size.z() == 0) {
				n = new Vector3f(0, 0, 1);
			} else if (size.x() == 0) {
				n = new Vector3f(1, 0, 0);
			} else {
				return new float[] {rx, rz};
			}
			Vec3 r = cube.rotation();
			new Quaternionf().rotationZYX((float) r.z(), (float) r.y(), (float) r.x()).transform(n);
			float len = Mth.sqrt(n.x() * n.x() + n.z() * n.z());
			if (len < 0.2F) {
				return new float[] {rx, rz};
			}
			float dx = n.x() / len, dz = n.z() / len;
			return dx * rx + dz * rz >= 0 ? new float[] {dx, dz} : new float[] {-dx, -dz};
		}

		/** The middle of a cube's top edge (before its own rotation, which turns it about that edge). */
		private static float[] top(GeoCube cube) {
			float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			for (GeoQuad quad : cube.quads()) {
				if (quad == null) {
					continue;
				}
				for (GeoVertex vertex : quad.vertices()) {
					Vector3f p = vertex.position();
					minX = Math.min(minX, p.x());
					maxX = Math.max(maxX, p.x());
					minZ = Math.min(minZ, p.z());
					maxZ = Math.max(maxZ, p.z());
					maxY = Math.max(maxY, p.y());
				}
			}
			return new float[] {(minX + maxX) / 2, maxY, (minZ + maxZ) / 2};
		}

		private static float angle(float[] p, float cx, float cz) {
			return (float) Math.atan2(p[0] - cx, p[2] - cz);
		}

		/** The waist point at an angle: between the two waist panels either side of it (sorted by angle). */
		private static float[] onWaist(List<float[]> waist, float[] angles, float a) {
			int n = waist.size();
			int next = 0;
			while (next < n && angles[next] <= a) {
				next++;
			}
			float[] p, q;
			float from, to, at = a;
			if (next == 0 || next == n) {   // past the last panel: between it and the first, across the wrap
				p = waist.get(n - 1);
				q = waist.get(0);
				from = angles[n - 1];
				to = angles[0] + Mth.TWO_PI;
				if (at < from) {
					at += Mth.TWO_PI;
				}
			} else {
				p = waist.get(next - 1);
				q = waist.get(next);
				from = angles[next - 1];
				to = angles[next];
			}
			float f = to - from < 1.0E-5F ? 0.0F : Mth.clamp((at - from) / (to - from), 0.0F, 1.0F);
			return new float[] {Mth.lerp(f, p[0], q[0]), Mth.lerp(f, p[2], q[2])};
		}
	}
}
