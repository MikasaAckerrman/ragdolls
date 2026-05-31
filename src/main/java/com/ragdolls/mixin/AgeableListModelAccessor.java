package com.ragdolls.mixin;

import net.minecraft.client.model.AgeableListModel;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link AgeableListModel}'s protected part accessors so the limb skeleton can read them.
 * Covers humanoids and most animals (which extend {@code AgeableListModel}).
 */
@Mixin(AgeableListModel.class)
public interface AgeableListModelAccessor {

    @Invoker("headParts")
    Iterable<ModelPart> ragdolls$headParts();

    @Invoker("bodyParts")
    Iterable<ModelPart> ragdolls$bodyParts();
}
