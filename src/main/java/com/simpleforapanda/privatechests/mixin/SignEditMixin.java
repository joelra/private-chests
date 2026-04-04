package com.simpleforapanda.privatechests.mixin;

import com.simpleforapanda.privatechests.service.SignEditService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mixin to intercept sign editing and handle [private] lock creation/updates.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignEditMixin {

    /**
     * Inject at the head of updateSignText to process [private] locks.
     * Only runs when the editing player is a ServerPlayer (i.e. on the server).
     */
    @Inject(method = "updateSignText", at = @At("HEAD"), cancellable = true)
    private void onSignUpdate(Player player, boolean isFrontText, List<FilteredText> filteredLines, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        SignBlockEntity signEntity = (SignBlockEntity) (Object) this;
        boolean allowed = SignEditService.handleSignEdit(serverPlayer, signEntity.getBlockPos(), signEntity, filteredLines, isFrontText);

        if (!allowed) {
            ci.cancel();
        }
    }
}
