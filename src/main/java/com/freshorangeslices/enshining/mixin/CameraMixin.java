package com.freshorangeslices.enshining.mixin;

import com.freshorangeslices.enshining.client.PlayAsClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While you play as a companion, the third-person camera sits where {@link PlayAsClient#cameraDistance} says (6 blocks
 * back to start, pulled in only by solid walls) instead of vanilla's 4 blocks that stop at any block. If this can't
 * apply (another mod changed the camera), the camera is simply vanilla's.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true, require = 0)
	private void enshining$playAsDistance(double startingDistance, CallbackInfoReturnable<Double> cir) {
		if (PlayAsClient.inControl()) {
			cir.setReturnValue(PlayAsClient.cameraDistance((Camera) (Object) this));
		}
	}
}
