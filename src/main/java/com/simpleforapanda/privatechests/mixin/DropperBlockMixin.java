package com.simpleforapanda.privatechests.mixin;

import com.simpleforapanda.privatechests.service.AutomationBlockService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to block droppers from inserting items into locked containers.
 */
@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {

    /**
     * Cancel the dispense when the dropper faces a locked container,
     * so it behaves as if the container were full.
     */
    @Inject(method = "dispenseFrom", at = @At("HEAD"), cancellable = true)
    private void onDispenseFrom(ServerLevel level, BlockState state, BlockPos pos, CallbackInfo ci) {
        Direction facing = state.getValue(DispenserBlock.FACING);
        BlockPos targetPos = pos.relative(facing);

        if (AutomationBlockService.isAutomationBlocked(level, targetPos)) {
            ci.cancel();
        }
    }
}
