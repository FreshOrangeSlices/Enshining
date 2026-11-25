package com.freshorangeslices.enshining.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

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
import software.bernie.geckolib.util.RenderUtils;

/**
 * Every companion drawn the normal (cutout) way, which keeps overlapping cloth and armor sorting correctly, plus:
 * a "weapon_aura" bone drawn in its own see-through glowing pass while {@link CompanionEntity#weaponAuraActive()},
 * a swapped-in weapon (her menu's weapon slot) drawn in her hand or hovering at "weapon_back",
 * the "skirt" drawn as one connected ring of panels (see {@link SkirtRing}), from its own texture
 * ("NAME_skirt.png" next to "NAME.png") when the character has one, and everything in "waist_extras" (belt, bows,
 * pouches) worn over that skirt: it moves with the skirt under it, so the skirt never comes through it.
 */
public class CompanionRenderer<T extends CompanionEntity> extends GeoEntityRenderer<T> {
	private static final String AURA = "weapon_aura";
	private static final String SKIRT = "skirt";
	/**
	 * Where a weapon you give her is held: the bone's pivot is the fist on its handle, the blade points along the
	 * bone's -Z (forward, like her claymore). Move or turn it in Blockbench to fit the hand. Without one, the old
	 * spot at "right_hand".
	 */
	private static final String GRIP = "weapon_grip";
	/** The same for a weapon you gave her hovering behind her: on the hovering claymore's handle, blade along -Z. */
	private static final String BACK_GRIP = "weapon_back_grip";
	/**
	 * A vanilla handheld item's sprite (handle bottom-left, tip top-right) turned so its diagonal points along -Z
	 * with its flat facing sideways, then moved so the point a player's fist holds (worked back from the vanilla
	 * third-person transform, onto the diagonal) sits on the grip. Scale 0.85, as in a player's hand.
	 */
	private static final Quaternionf GRIP_TURN = new Quaternionf().setFromNormalized(new Matrix3f(
			0.0F, -Mth.SQRT_OF_TWO / 2, -Mth.SQRT_OF_TWO / 2,
			0.0F, Mth.SQRT_OF_TWO / 2, -Mth.SQRT_OF_TWO / 2,
			1.0F, 0.0F, 0.0F));
	private static final float GRIP_SCALE = 0.85F, GRIP_FIST = 0.2008F;
	/** Worn over the skirt (see {@link SkirtRing#ride}). */
	private static final String WAIST = "waist_extras";
	private static final int FULL_BRIGHT = 0xF000F0;
	/**
	 * Most the skirt swings with the legs (radians, 20 degrees) when not flaring; the more it flares, the less it may
	 * swing (none at the full 12-degree flare). Tested gap-free on the 2-px panel skirt with 25% overlap.
	 */
	private static final float SKIRT_MAX_SWING = 0.349F, SKIRT_SWING_PER_FLARE = 1.9F;
	/** Most the hem twists behind a turn (15 degrees), less while it flares (7.5 at the full flare). */
	private static final float SKIRT_MAX_TWIST = 0.26F, SKIRT_TWIST_PER_FLARE = 0.6F;
	private boolean auraPass;
	private boolean skirtPass;
	/** Drawing "waist_extras": each corner follows the skirt under it, worked out in the skirt's space. */
	private boolean riding;
	private final Matrix4f rideParent = new Matrix4f(), rideParentInv = new Matrix4f(), rideMatrix = new Matrix4f();
	/** How the whole skirt bone sways this frame (CompanionModel's sway), which what's worn over it follows too. */
	private final Matrix4f skirtSway = new Matrix4f();
	@Nullable
	private GeoBone skirtBone;
	private final Matrix3f rideNormal = new Matrix3f(), rideNormalInv = new Matrix3f();
	private final Vector4f ridePos = new Vector4f();
	private final Vector3f rideDir = new Vector3f();
	/** The skirt's motion this frame: front swing, back swing, flare, twist (radians, already limited). */
	private final float[] motion = new float[4];
	/**
	 * In the world (not the menu portrait): her faces are lit a little softer than Minecraft's, each normal tipped a
	 * quarter of the way toward the sky (fronts ~0.85 bright instead of ~0.74, sides ~0.64 instead of ~0.59; halfway
	 * was far too bright). worldUp is the sky's direction in the camera's space this frame.
	 */
	private static final float SOFT_LIGHT = 0.25F;
	private boolean inWorld;
	private final Vector3f worldUp = new Vector3f(0, 1, 0);
	private final Vector4f cornerScratch = new Vector4f();
	private final Map<ResourceLocation, Optional<ResourceLocation>> skirtTextures = new HashMap<>();
	@Nullable
	private BakedGeoModel ringModel;
	@Nullable
	private SkirtRing ring;
	/** The bones holding her claymore's cubes, in hand and hovering behind her (tracked for the sparkle). */
	private final List<GeoBone> handBlade = new ArrayList<>(), backBlade = new ArrayList<>();
	@Nullable
	private GeoBone gripBone, backGripBone;
	/** Each prop (a child of "props") and the bones of it that have cubes, for its sparkle. */
	private final Map<String, List<GeoBone>> propBones = new HashMap<>();
	@Nullable
	private GeoBone headBone;

	public CompanionRenderer(EntityRendererProvider.Context context, CompanionModel<T> model) {
		super(context, model);
		addRenderLayer(new GeoRenderLayer<>(this) {
			@Override
			public void render(PoseStack poseStack, T companion, BakedGeoModel model, RenderType renderType,
					MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick, int packedLight, int packedOverlay) {
				if (ring == null) {
					return;
				}
				ResourceLocation own = skirtTexture(companion);
				ResourceLocation texture = own != null ? own : getTextureLocation(companion);
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
				// In her hand only while her claymore would be (it fades in and out the same way).
				boolean inHand = companion.handWeaponIn > 0;
				return switch (bone.getName()) {
					case GRIP -> inHand ? companion.getMainHandItem() : null;
					case "right_hand" -> gripBone == null && inHand ? companion.getMainHandItem() : null;
					case BACK_GRIP -> companion.showBackWeapon() ? companion.getMainHandItem() : null;
					case "weapon_back" -> backGripBone == null && companion.showBackWeapon() ? companion.getMainHandItem() : null;
					default -> null;
				};
			}

			@Override
			protected ItemDisplayContext getTransformTypeForStack(GeoBone bone, ItemStack stack, T companion) {
				return GRIP.equals(bone.getName()) || BACK_GRIP.equals(bone.getName()) ? ItemDisplayContext.NONE
						: ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
			}

			@Override
			protected void renderStackForBone(PoseStack poseStack, GeoBone bone, ItemStack stack, T companion,
					MultiBufferSource bufferSource, float partialTick, int packedLight, int packedOverlay) {
				poseStack.pushPose();
				if (GRIP.equals(bone.getName())) {
					// Handle in her fist, blade along the grip; grows in and fades out like her claymore.
					float in = companion.handWeaponIn;
					float grown = (in >= 1 ? 1 : in * in * (3 - 2 * in)) * GRIP_SCALE;
					poseStack.mulPose(GRIP_TURN);
					poseStack.scale(grown, grown, grown);
					poseStack.translate(GRIP_FIST, GRIP_FIST, 0.0F);
				} else if (BACK_GRIP.equals(bone.getName())) {
					// Hovering behind her like her claymore, on its handle (the bone floats and fades it in).
					poseStack.mulPose(GRIP_TURN);
					poseStack.scale(GRIP_SCALE, GRIP_SCALE, GRIP_SCALE);
					poseStack.translate(GRIP_FIST, GRIP_FIST, 0.0F);
				} else if ("right_hand".equals(bone.getName())) {
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

	private static boolean glows(GeoBone bone) {
		for (GeoBone b = bone; b != null; b = b.getParent()) {
			if (b.getName().startsWith("glow_")) {
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
			skirtBone = model.getBone(SKIRT).orElse(null);
			ring = SkirtRing.of(skirtBone);
			skirtTextures.clear();
			handBlade.clear();
			backBlade.clear();
			model.getBone("weapon").ifPresent(b -> bladeBones(b, handBlade));
			gripBone = model.getBone(GRIP).orElse(null);
			if (gripBone != null) {
				gripBone.setTrackingMatrices(true);
			}
			backGripBone = model.getBone(BACK_GRIP).orElse(null);
			if (backGripBone != null) {
				backGripBone.setTrackingMatrices(true);
			}
			propBones.clear();
			model.getBone("props").ifPresent(props -> {
				for (GeoBone prop : props.getChildBones()) {
					List<GeoBone> bones = new ArrayList<>();
					bladeBones(prop, bones);
					propBones.put(prop.getName(), bones);
				}
			});
			for (GeoBone bone : model.topLevelBones()) {
				propsNamed(bone);
			}
			headBone = model.getBone("pupil_left").or(() -> model.getBone("pupil_right")).isPresent()
					? model.getBone("head").orElse(null) : null;   // only models with moving eyes need it
			if (headBone != null) {
				headBone.setTrackingMatrices(true);
			}
			model.getBone("weapon_back").ifPresent(b -> bladeBones(b, backBlade));
		}
		inWorld = companion.level().getEntity(companion.getId()) == companion;
		if (inWorld) {
			float pitch = Minecraft.getInstance().gameRenderer.getMainCamera().getXRot() * Mth.DEG_TO_RAD;
			worldUp.set(0.0F, Mth.cos(pitch), Mth.sin(pitch));
		}
		super.actuallyRender(poseStack, companion, model, renderType, bufferSource, buffer, isReRender, partialTick, packedLight,
				packedOverlay, red, green, blue, alpha);
		// The sparkle, in the world only (not for the menu's portrait copy), from where the blade was just drawn.
		if (!isReRender && companion.level().getEntity(companion.getId()) == companion) {
			if (headBone != null) {
				companion.eyeHeadMatrix = new org.joml.Matrix4f(headBone.getWorldSpaceMatrix());
			}
			float frame = Minecraft.getInstance().getDeltaFrameTime();
			if (companion.handWeaponSpark > 0 || companion.handWeaponIn > 0 && companion.handWeaponIn < 1) {
				if (companion.holdsSwappedWeapon() && gripBone != null) {
					sparkleAlong(companion, gripBone, frame, companion.handWeaponWanted);
				} else {
					sparkle(companion, handBlade, frame, companion.handWeaponWanted);
				}
			}
			if (companion.backWeaponIn > 0 && companion.backWeaponIn < 1) {
				if (companion.holdsSwappedWeapon() && backGripBone != null) {
					sparkleAlong(companion, backGripBone, frame, companion.backWeaponWanted);
				} else {
					sparkle(companion, backBlade, frame, companion.backWeaponWanted);
				}
			}
			for (var prop : propBones.entrySet()) {
				String name = prop.getKey();
				float in = companion.propIn.getOrDefault(name, 0.0F);
				float size = companion.propScale.getOrDefault(name, 1.0F);
				if (in <= 0 || size <= 0.02F) {
					continue;   // nothing to see (a teapot its animation still holds at size 0, far off)
				}
				int pop = companion.propPop.getOrDefault(name, 0);
				if (in < 1) {
					sparkle(companion, prop.getValue(), frame, companion.propWanted.getOrDefault(name, false));
				} else if (pop != 0 && size < 0.98F) {
					sparkle(companion, prop.getValue(), frame, pop > 0);   // its animation popping it in or out
				}
			}
		}
	}

	private static void bladeBones(GeoBone bone, List<GeoBone> out) {
		if (AURA.equals(bone.getName())) {
			return;
		}
		if (!bone.getCubes().isEmpty()) {
			bone.setTrackingMatrices(true);
			out.add(bone);
		}
		for (GeoBone child : bone.getChildBones()) {
			bladeBones(child, out);
		}
	}

	/**
	 * Soft lights over the blade while it materialises or dissolves: specks of her element's colour and little
	 * sparks that drift down and fade. Spots are picked on the weapon's own cubes, wherever it is this frame.
	 */
	private static void sparkle(CompanionEntity companion, List<GeoBone> blade, float frameTicks, boolean appearing) {
		if (blade.isEmpty()) {
			return;
		}
		var random = companion.getRandom();
		float budget = frameTicks * (appearing ? 6.0F : 8.0F);
		int count = (int) budget + (random.nextFloat() < budget - (int) budget ? 1 : 0);
		for (int i = 0; i < count; i++) {
			GeoBone bone = blade.get(random.nextInt(blade.size()));
			GeoCube cube = bone.getCubes().get(random.nextInt(bone.getCubes().size()));
			float[] box = box(cube);
			Vector3f p = new Vector3f(Mth.lerp(random.nextFloat(), box[0], box[3]), Mth.lerp(random.nextFloat(), box[1], box[4]),
					Mth.lerp(random.nextFloat(), box[2], box[5]));
			Vec3 pivot = cube.pivot();
			Vec3 rot = cube.rotation();
			Vector3f cp = new Vector3f((float) pivot.x() / 16, (float) pivot.y() / 16, (float) pivot.z() / 16);
			new Quaternionf().rotationZYX((float) rot.z(), (float) rot.y(), (float) rot.x()).transform(p.sub(cp)).add(cp);
			p.sub(bone.getPivotX() / 16, bone.getPivotY() / 16, bone.getPivotZ() / 16);
			org.joml.Vector4f world = bone.getWorldSpaceMatrix().transform(new org.joml.Vector4f(p, 1));
			if (random.nextInt(3) == 0) {
				companion.level().addParticle(net.minecraft.core.particles.ParticleTypes.FIREWORK, world.x(), world.y(), world.z(),
						(random.nextFloat() - 0.5F) * 0.02F, -0.005F - random.nextFloat() * 0.015F, (random.nextFloat() - 0.5F) * 0.02F);
			} else {
				companion.level().addParticle(companion.element().dust(), world.x(), world.y(), world.z(), 0, -0.01, 0);
			}
		}
	}

	/** Every "prop_..." group, wherever it is (in her hand, say), with the bones of it that have cubes. */
	private void propsNamed(GeoBone bone) {
		if (bone.getName().startsWith("prop_")) {
			List<GeoBone> bones = new ArrayList<>();
			bladeBones(bone, bones);
			propBones.put(bone.getName(), bones);
		}
		for (GeoBone child : bone.getChildBones()) {
			propsNamed(child);
		}
	}

	/** The same soft lights for a weapon you gave her: along the first block out from the grip, where its blade is. */
	private static void sparkleAlong(CompanionEntity companion, GeoBone grip, float frameTicks, boolean appearing) {
		var random = companion.getRandom();
		float budget = frameTicks * (appearing ? 6.0F : 8.0F);
		int count = (int) budget + (random.nextFloat() < budget - (int) budget ? 1 : 0);
		for (int i = 0; i < count; i++) {
			org.joml.Vector4f world = grip.getWorldSpaceMatrix().transform(new org.joml.Vector4f((random.nextFloat() - 0.5F) * 0.08F,
					(random.nextFloat() - 0.5F) * 0.08F, 0.15F - random.nextFloat() * 0.95F, 1));
			if (random.nextInt(3) == 0) {
				companion.level().addParticle(net.minecraft.core.particles.ParticleTypes.FIREWORK, world.x(), world.y(), world.z(),
						(random.nextFloat() - 0.5F) * 0.02F, -0.005F - random.nextFloat() * 0.015F, (random.nextFloat() - 0.5F) * 0.02F);
			} else {
				companion.level().addParticle(companion.element().dust(), world.x(), world.y(), world.z(), 0, -0.01, 0);
			}
		}
	}

	/** A cube's box before its own rotation (render units): min x, y, z, max x, y, z. */
	private static float[] box(GeoCube cube) {
		float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
		for (GeoQuad quad : cube.quads()) {
			if (quad == null) {
				continue;
			}
			for (GeoVertex vertex : quad.vertices()) {
				Vector3f v = vertex.position();
				b[0] = Math.min(b[0], v.x());
				b[1] = Math.min(b[1], v.y());
				b[2] = Math.min(b[2], v.z());
				b[3] = Math.max(b[3], v.x());
				b[4] = Math.max(b[4], v.y());
				b[5] = Math.max(b[5], v.z());
			}
		}
		return b;
	}

	@Override
	public void renderRecursively(PoseStack poseStack, T companion, GeoBone bone, RenderType renderType, MultiBufferSource bufferSource,
			VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, float red, float green,
			float blue, float alpha) {
		// Entering "waist_extras": remember the space it hangs in (the skirt's), to move its corners with the skirt.
		boolean ride = !riding && !skirtPass && !auraPass && ring != null && companion != null && WAIST.equals(bone.getName());
		if (ride) {
			PoseStack.Pose parent = poseStack.last();
			rideParent.set(parent.pose());
			rideParentInv.set(parent.pose()).invert();
			rideNormal.set(parent.normal());
			rideNormalInv.set(parent.normal()).invert();
			skirtMotion(companion, motion);
			skirtSway.identity();
			if (skirtBone != null) {
				PoseStack local = new PoseStack();
				RenderUtils.prepMatrixForBone(local, skirtBone);
				skirtSway.set(local.last().pose());
			}
			riding = true;
		}
		try {
			super.renderRecursively(poseStack, companion, bone, renderType, bufferSource, buffer, isReRender, partialTick, packedLight,
					packedOverlay, red, green, blue, alpha);
		} finally {
			if (ride) {
				riding = false;
			}
		}
	}

	/**
	 * GeckoLib's own cube drawing, with its lighting of flat (zero-thickness) cubes replaced in the world: it forces
	 * their normals to point one way in the camera's space, so a sheet like her eyes caught more light than the face
	 * right behind it whenever you looked down at her (the eyes "glowed"). Here a flat cube's face is lit as the side
	 * you're looking at, like any other face, and every face gets the soft light above. The menu keeps GeckoLib's.
	 */
	@Override
	public void renderCube(PoseStack poseStack, GeoCube cube, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		RenderUtils.translateToPivotPoint(poseStack, cube);
		RenderUtils.rotateMatrixAroundCube(poseStack, cube);
		RenderUtils.translateAwayFromPivotPoint(poseStack, cube);
		Matrix3f normalState = poseStack.last().normal();
		Matrix4f poseState = poseStack.last().pose();
		Vec3 size = cube.size();
		boolean flat = size.x() == 0 || size.y() == 0 || size.z() == 0;
		for (GeoQuad quad : cube.quads()) {
			if (quad == null) {
				continue;
			}
			Vector3f normal = normalState.transform(new Vector3f(quad.normal()));
			if (!inWorld) {
				RenderUtils.fixInvertedFlatCube(cube, normal);
			} else {
				if (flat) {
					Vector3f corner = quad.vertices()[0].position();
					poseState.transform(cornerScratch.set(corner.x(), corner.y(), corner.z(), 1.0F));
					// The camera is at the origin: a normal pointing away from it is the far side's; turn it round.
					if (normal.x() * cornerScratch.x() + normal.y() * cornerScratch.y() + normal.z() * cornerScratch.z() > 0) {
						normal.negate();
					}
				}
				normal.add(worldUp.x() * SOFT_LIGHT, worldUp.y() * SOFT_LIGHT, worldUp.z() * SOFT_LIGHT);
				if (normal.lengthSquared() > 1.0E-6F) {
					normal.normalize();
				}
			}
			createVerticesOfQuad(quad, poseState, normal, buffer, packedLight, packedOverlay, red, green, blue, alpha);
		}
	}

	@Override
	public void createVerticesOfQuad(GeoQuad quad, Matrix4f poseState, Vector3f normal, VertexConsumer buffer, int packedLight,
			int packedOverlay, float red, float green, float blue, float alpha) {
		if (!riding) {
			super.createVerticesOfQuad(quad, poseState, normal, buffer, packedLight, packedOverlay, red, green, blue, alpha);
			return;
		}
		for (GeoVertex vertex : quad.vertices()) {
			Vector3f position = vertex.position();
			ridePos.set(position.x(), position.y(), position.z(), 1.0F);
			poseState.transform(ridePos);
			rideParentInv.transform(ridePos);   // where this corner is around her waist
			ring.ride(ridePos.x(), ridePos.y(), ridePos.z(), motion, skirtSway, rideMatrix);
			rideMatrix.transform(ridePos);
			rideParent.transform(ridePos);
			rideNormalInv.transform(rideDir.set(normal));
			rideMatrix.transformDirection(rideDir);
			rideNormal.transform(rideDir).normalize();
			buffer.vertex(ridePos.x(), ridePos.y(), ridePos.z(), red, green, blue, alpha, vertex.texU(), vertex.texV(), packedOverlay,
					packedLight, rideDir.x(), rideDir.y(), rideDir.z());
		}
	}

	@Override
	public void renderCubesOfBone(PoseStack poseStack, GeoBone bone, VertexConsumer buffer, int packedLight, int packedOverlay,
			float red, float green, float blue, float alpha) {
		// Each pass draws only its own cubes: the body skips the aura, the aura pass skips the body.
		if (AURA.equals(bone.getName()) != auraPass) {
			return;
		}
		// A swapped-in weapon replaces her own weapon model, in hand and hovering behind her.
		if (animatable != null && animatable.holdsSwappedWeapon() && isOwnWeaponModel(bone) && !auraPass) {
			return;
		}
		// The skirt is drawn in its own pass only (culled, with its own texture if it has one).
		boolean skirt = !auraPass && ring != null && inSkirt(bone);
		if (skirt != skirtPass) {
			return;
		}
		// "glow_..." bones (and what's under them) shine at full brightness, like a Vision or a gem.
		if (!auraPass && glows(bone)) {
			packedLight = FULL_BRIGHT;
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
		skirtMotion(animatable, motion);
		float front = motion[0], back = motion[1], flare = motion[2], twist = motion[3];
		Quaternionf turn = new Quaternionf();
		for (GeoCube cube : bone.getCubes()) {
			poseStack.pushPose();
			float[] c = ring.columns.get(cube);
			if (c != null) {
				// The lower a panel hangs, the more it lags behind a turn (the waist itself stays put).
				if (twist != 0 && c[7] > 0) {
					poseStack.translate(ring.centerX, 0.0F, ring.centerZ);
					poseStack.mulPose(Axis.YP.rotation(twist * c[7]));
					poseStack.translate(-ring.centerX, 0.0F, -ring.centerZ);
				}
				// Front columns take the front swing, back ones the back swing, fading to none at the sides.
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

	/** How the skirt moves this frame: front swing, back swing, flare, twist, each within its limits. */
	private static void skirtMotion(CompanionEntity companion, float[] out) {
		float flare = companion.skirtFlare[0];
		float flaring = Math.max(0.0F, flare);
		float limit = Math.max(0.0F, SKIRT_MAX_SWING - SKIRT_SWING_PER_FLARE * flaring);
		float lean = companion.skirtLean[0];
		float twistLimit = Math.max(0.0F, SKIRT_MAX_TWIST - SKIRT_TWIST_PER_FLARE * flaring);
		out[0] = Mth.clamp(companion.skirtFront + lean, -limit, limit);
		out[1] = Mth.clamp(companion.skirtBack + lean, -limit, limit);
		out[2] = flare;
		out[3] = Mth.clamp(companion.skirtTwist[0], -twistLimit, twistLimit);
	}

	/**
	 * Where each skirt panel hangs from: the top ring (the highest panels) gives the waist line; every panel gets
	 * the point on it straight above, and the outward direction it faces. Worked out once per model.
	 */
	static final class SkirtRing {
		/**
		 * Per cube: hinge x, y, z (in render units), the outward direction it faces dx, dz, how much of the
		 * front / back swing it takes: (how far front or back it sits)^2, so the sides barely move and the swing
		 * fades in gently there (where neighbouring panels would otherwise slide apart), and how much of the twist
		 * (0 at the waist to 1 six pixels down).
		 */
		final Map<GeoCube, float[]> columns = new IdentityHashMap<>();
		/** The middle of the waist (render units), which the hem twists around. */
		float centerX, centerZ;
		/** The waist line: its height, how far it reaches front and back, and the top panels around it by angle. */
		float waistY, depth;
		private List<float[]> waist = List.of();
		private float[] angles = new float[0];
		private final float[] spot = new float[4];
		private final Quaternionf turn = new Quaternionf();
		private final Matrix4f swayed = new Matrix4f();
		/**
		 * What's worn over the skirt starts following it half a pixel above the waist line (where the panels' top
		 * corners reach as they swing), fully half a pixel lower; and it's lifted off the skirt a little while the
		 * skirt moves (1.2 px per radian, half a pixel at the most): the panels either side of a spot move a little
		 * more than the spot itself. Tested: all of Noelle's waist pieces stay 0.12 px or more above the skirt at every
		 * extreme of its swing, flare, twist and sway.
		 */
		private static final float RIDE_TOP = 0.5F / 16.0F, RIDE_FADE = 0.5F / 16.0F, RIDE_LIFT = 1.2F / 16.0F;

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
			// The waist: panels whose top is within a third of a pixel of the highest (x, y, z, then facing x, z).
			List<float[]> waist = new ArrayList<>();
			List<GeoCube> waistCubes = new ArrayList<>();
			float cx = 0, cz = 0, y = 0;
			for (int i = 0; i < tops.size(); i++) {
				float[] t = tops.get(i);
				if (t[1] >= topY - 0.3F / 16.0F) {
					waist.add(new float[] {t[0], t[1], t[2], 0, 0});
					waistCubes.add(cubes.get(i));
					cx += t[0];
					cz += t[2];
					y += t[1];
				}
			}
			cx /= waist.size();
			cz /= waist.size();
			y /= waist.size();
			float centerX = cx, centerZ = cz;
			for (int i = 0; i < waist.size(); i++) {
				float[] w = waist.get(i);
				float rx = w[0] - cx, rz = w[2] - cz;
				float len = Math.max(1.0E-4F, Mth.sqrt(rx * rx + rz * rz));
				float[] f = facing(waistCubes.get(i), rx / len, rz / len);
				w[3] = f[0];
				w[4] = f[1];
			}
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
			ring.centerX = cx;
			ring.centerZ = cz;
			ring.waistY = y;
			ring.depth = depth;
			ring.waist = waist;
			ring.angles = angles;
			for (int i = 0; i < cubes.size(); i++) {
				float[] t = tops.get(i);
				float[] hinge = onWaist(waist, angles, angle(t, cx, cz), new float[4]);
				float rx = t[0] - cx, rz = t[2] - cz;
				float len = Mth.sqrt(rx * rx + rz * rz);
				if (len < 1.0E-4F) {
					continue;
				}
				rx /= len;
				rz /= len;
				float[] facing = facing(cubes.get(i), rx, rz);
				float side = Mth.clamp((hinge[1] - cz) / depth, -1.0F, 1.0F);
				float hang = Mth.clamp((y - t[1]) / (6.0F / 16.0F), 0.0F, 1.0F);   // how far below the waist, 6 px = fully
				ring.columns.put(cubes.get(i), new float[] {hinge[0], y, hinge[1], facing[0], facing[1],
						side < 0 ? side * side : 0.0F, side > 0 ? side * side : 0.0F, hang});
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

		/**
		 * How a spot worn over the skirt moves (x, y, z in render units, in the space the skirt hangs in): like the
		 * skirt right under it, the same swing and flare about the same waist line and the skirt bone's own sway, so
		 * it stays on top of it.
		 */
		void ride(float x, float y, float z, float[] motion, Matrix4f sway, Matrix4f out) {
			out.identity();
			float weight = Mth.clamp((waistY + RIDE_TOP - y) / RIDE_FADE, 0.0F, 1.0F);
			if (weight <= 0 || waist.isEmpty()) {
				return;
			}
			float[] w = onWaist(waist, angles, (float) Math.atan2(x - centerX, z - centerZ), spot);
			float side = Mth.clamp((w[1] - centerZ) / depth, -1.0F, 1.0F);
			float swing = (motion[0] * (side < 0 ? side * side : 0.0F) + motion[1] * (side > 0 ? side * side : 0.0F)) * weight;
			float lift = RIDE_LIFT * weight
					* (Math.max(Math.abs(motion[0]), Math.abs(motion[1])) + Math.abs(motion[2]) + 0.5F * Math.abs(motion[3]));
			float dx = x - centerX, dz = z - centerZ;
			float len = Mth.sqrt(dx * dx + dz * dz);
			if (len > 1.0E-4F) {
				out.translate(lift * dx / len, 0.0F, lift * dz / len);
			}
			out.translate(w[0], waistY, w[1])
					.rotate(turn.rotationAxis(motion[2] * weight, -w[3], 0.0F, w[2]))
					.rotateX(swing)
					.translate(-w[0], -waistY, -w[1]);
			out.mulLocal(swayed.identity().lerp(sway, weight));   // and the whole skirt's sway on top
		}

		/**
		 * The waist at an angle, between the two waist panels either side of it (sorted by angle): the point x, z
		 * and the way the skirt faces there x, z.
		 */
		private static float[] onWaist(List<float[]> waist, float[] angles, float a, float[] out) {
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
			float fx = Mth.lerp(f, p[3], q[3]), fz = Mth.lerp(f, p[4], q[4]);
			float len = Math.max(1.0E-4F, Mth.sqrt(fx * fx + fz * fz));
			out[0] = Mth.lerp(f, p[0], q[0]);
			out[1] = Mth.lerp(f, p[2], q[2]);
			out[2] = fx / len;
			out[3] = fz / len;
			return out;
		}
	}
}
