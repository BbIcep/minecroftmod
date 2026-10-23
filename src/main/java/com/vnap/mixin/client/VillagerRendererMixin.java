package com.vnap.mixin.client;

import com.vnap.client.DialogueAnimationState;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({VillagerRenderer.class})
public abstract class VillagerRendererMixin {
   private static final Map<Villager, VillagerRendererMixin.StableVillagerData> VNAP$STABLE_DATA = new WeakHashMap<>();

   @Inject(
      method = {"extractRenderState(Lnet/minecraft/world/entity/npc/villager/Villager;Lnet/minecraft/client/renderer/entity/state/VillagerRenderState;F)V"},
      at = {@At("TAIL")}
   )
   private void vnap$extractRenderState(Villager villager, VillagerRenderState state, float partialTick, CallbackInfo ci) {
      VillagerRendererMixin.StableVillagerData stableData = VNAP$STABLE_DATA.computeIfAbsent(
         villager, ignored -> new VillagerRendererMixin.StableVillagerData(state.villagerData)
      );
      state.villagerData = stableData.resolve(state.villagerData, villager.tickCount);
      DialogueAnimationState.trackBodyRotation(villager, state.bodyRot, villager.tickCount + partialTick);
   }

   private static final class StableVillagerData {
      private VillagerData displayed;
      private VillagerData pending;
      private int pendingSince;

      private StableVillagerData(VillagerData displayed) {
         this.displayed = displayed;
      }

      private VillagerData resolve(VillagerData current, int tick) {
         if (current.equals(this.displayed)) {
            this.pending = null;
            return this.displayed;
         } else if (!current.equals(this.pending)) {
            this.pending = current;
            this.pendingSince = tick;
            return this.displayed;
         } else {
            if (tick - this.pendingSince >= 2) {
               this.displayed = current;
               this.pending = null;
            }

            return this.displayed;
         }
      }
   }
}
