package com.vnap.client;

import com.vnap.VillagerNewsAddonPort;
import com.vnap.config.VillagerNewsSettings;
import com.vnap.dialogue.DialogueCatalog;
import com.vnap.sound.SupplementalSoundCatalog;
import java.io.IOException;
import java.util.function.Supplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Disconnect;
import traben.entity_model_features.EMFAnimationApi;

public final class VillagerNewsAddonPortClient implements ClientModInitializer {
   public void onInitializeClient() {
      VillagerNewsSettings.load();
      VillagerNewsClientSettings.load();
      SupplementalSoundCatalog.register();
      DialogueCatalog.register();

      try {
         DialogueAnimationState.load();
         registerFloat("vnap_speaking", DialogueAnimationState::speaking, "Whether the Villager News character is speaking");
         registerFloat("vnap_mouth_open", DialogueAnimationState::mouthOpen, "Current Villager News mouth opening");
         registerFloat("vnap_mouth_width", DialogueAnimationState::mouthWidth, "Current Villager News mouth width");
         registerFloat("vnap_mouth_closed", DialogueAnimationState::mouthClosed, "Current Villager News closed-mouth layer");
         registerFloat("vnap_has_nose", DialogueAnimationState::hasNose, "Villager News nose visibility");
         registerFloat("vnap_cosmetic_mayor_hat", () -> DialogueAnimationState.cosmetic(1), "Villager News mayor hat visibility");
         registerFloat("vnap_cosmetic_helmet", () -> DialogueAnimationState.cosmetic(2), "Villager News helmet visibility");
         registerFloat("vnap_cosmetic_microphone", () -> DialogueAnimationState.cosmetic(3), "Villager News microphone visibility");
         registerFloat("vnap_cosmetic_moustache", () -> DialogueAnimationState.cosmetic(4), "Villager News moustache visibility");

         for (String variable : DialogueAnimationState.animationVariables()) {
            registerFloat(variable, () -> DialogueAnimationState.transform(variable), "Synchronized Villager News dialogue transform");
         }
      } catch (RuntimeException | IOException var3) {
         throw new IllegalStateException("Could not load Villager News animations", var3);
      } catch (Exception var4) {
         throw new IllegalStateException("Could not register Villager News EMF animation variables", var4);
      }

      ClientDialogueController.register();
      ClientPlayConnectionEvents.DISCONNECT.register((Disconnect)(handler, client) -> {
         DialogueSoundState.clear(client);
         DialogueAnimationState.clear();
         DialogueSubtitleState.clear();
         ClientDialogueController.clear();
      });
      DialogueSubtitleState.register();
      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
         ClientDialogueController.tick(client);
         DialogueSoundState.tick(client);
         DialogueAnimationState.tick(client);
         DialogueSubtitleState.tick(client);
      });
      VillagerNewsAddonPort.LOGGER.info("Villager News (client-only) is ready; dialogue is driven locally.");
   }

   private static void registerFloat(String name, Supplier<Float> supplier, String description) throws Exception {
      EMFAnimationApi.registerSingletonAnimationVariable("villager-news-addon-port", name, description, supplier);
   }
}
