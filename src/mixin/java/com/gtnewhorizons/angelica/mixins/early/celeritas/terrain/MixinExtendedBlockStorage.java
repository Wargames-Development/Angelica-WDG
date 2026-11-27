package com.gtnewhorizons.angelica.mixins.early.celeritas.terrain;

import com.gtnewhorizons.angelica.compat.ModStatus;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ExtendedBlockStorage.class)
public abstract class MixinExtendedBlockStorage {
    @Shadow private byte[] blockLSBArray;
    @Shadow private NibbleArray blockMSBArray;
    @Shadow private int blockRefCount;
    @Shadow private int tickRefCount;

    @Inject(method = "removeInvalidBlocks", at = @At("HEAD"), cancellable = true)
    private void angelica$recountSection(CallbackInfo ci) {
        if (!ExtendedBlockStorage.class.equals(getClass())
                || blockLSBArray.length != 4096 || ModStatus.isNEIDLoaded || ModStatus.isEIDLoaded) return;

        int blocks = 0;
        int ticking = 0;
        int previousId = -1;
        Block block = Blocks.air;
        final byte[] lowIds = blockLSBArray;
        final NibbleArray highIds = blockMSBArray;
        // Section storage is x-major. Air and repeated IDs need no registry lookup.
        for (int index = 0; index < lowIds.length; index++) {
            int id = lowIds[index] & 255;
            if (highIds != null) id |= highIds.get(index & 15, index >> 8, (index >> 4) & 15) << 8;
            if (id == 0) continue;
            if (id != previousId) {
                block = Block.getBlockById(id);
                previousId = id;
            }
            if (block != Blocks.air) {
                blocks++;
                if (block.getTickRandomly()) ticking++;
            }
        }
        blockRefCount = blocks;
        tickRefCount = ticking;
        ci.cancel();
    }
}
