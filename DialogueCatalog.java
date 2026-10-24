package com.vnap.dialogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vnap.VillagerNewsAddonPort;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class DialogueCatalog {
   private static final String CATALOG_PATH = "/assets/villager-news-addon-port/dialogues.json";
   private static final Map<String, DialogueCatalog.DialogueGroup> GROUPS = new LinkedHashMap<>();
   private static final Map<String, List<DialogueCatalog.DialogueGroup>> TITLES = new LinkedHashMap<>();

   private DialogueCatalog() {
   }

   public static void register() {
      try {
         try (InputStream stream = DialogueCatalog.class.getResourceAsStream("/assets/villager-news-addon-port/dialogues.json")) {
            if (stream == null) {
               throw new IOException("Missing /assets/villager-news-addon-port/dialogues.json");
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            int variantCount = 0;

            for (Entry<String, JsonElement> entry : root.getAsJsonObject("groups").entrySet()) {
               String groupId = entry.getKey();
               JsonObject value = entry.getValue().getAsJsonObject();
               List<DialogueCatalog.DialogueVariant> variants = new ArrayList<>();

               for (JsonElement variantElement : value.getAsJsonArray("variants")) {
                  JsonObject variantValue = variantElement.getAsJsonObject();
                  int index = variantValue.get("index").getAsInt();
                  List<DialogueCatalog.SubtitleFrame> subtitles = new ArrayList<>();

                  for (JsonElement subtitleElement : variantValue.getAsJsonArray("subtitles")) {
                     JsonObject subtitleValue = subtitleElement.getAsJsonObject();
                     subtitles.add(new DialogueCatalog.SubtitleFrame(subtitleValue.get("time").getAsDouble(), subtitleValue.get("key").getAsString()));
                  }

                  Identifier soundId = VillagerNewsAddonPort.id("dialogue." + groupId + "." + index);
                  SoundEvent sound = SoundEvent.createVariableRangeEvent(soundId);
                  variants.add(
                     new DialogueCatalog.DialogueVariant(
                        index,
                        variantValue.get("duration").getAsDouble(),
                        variantValue.get("weight").getAsInt(),
                        variantValue.get("animation").getAsString(),
                        sound,
                        List.copyOf(subtitles)
                     )
                  );
                  variantCount++;
               }

               DialogueCatalog.DialogueGroup group = new DialogueCatalog.DialogueGroup(
                  groupId,
                  value.get("title").getAsString(),
                  value.get("body").getAsString(),
                  value.get("speaker").getAsString(),
                  value.get("maximumDuration").getAsDouble(),
                  List.copyOf(variants)
               );
               GROUPS.put(groupId, group);
               if (!group.title().isBlank()) {
                  TITLES.computeIfAbsent(group.title(), ignored -> new ArrayList<>()).add(group);
               }
            }

            VillagerNewsAddonPort.LOGGER.info("Registered {} contextual dialogue groups with {} synchronized variants", GROUPS.size(), variantCount);
         }
      } catch (RuntimeException | IOException var18) {
         throw new IllegalStateException("Could not load Villager News dialogue catalog", var18);
      }
   }

   public static DialogueCatalog.DialogueGroup byId(String id) {
      return GROUPS.get(id);
   }

   public static DialogueCatalog.DialogueGroup byTitle(String title) {
      List<DialogueCatalog.DialogueGroup> matches = TITLES.get(title);
      return matches != null && !matches.isEmpty() ? matches.getFirst() : null;
   }

   public static DialogueCatalog.DialogueGroup byTitle(String title, String speaker) {
      List<DialogueCatalog.DialogueGroup> matches = TITLES.get(title);
      return matches == null ? null : matches.stream().filter(group -> group.speaker().equals(speaker)).findFirst().orElse(null);
   }

   public static Map<String, DialogueCatalog.DialogueGroup> groups() {
      return Collections.unmodifiableMap(GROUPS);
   }

   public record DialogueGroup(String id, String title, String body, String speaker, double maximumDuration, List<DialogueCatalog.DialogueVariant> variants) {
      public long durationTicks() {
         return Math.max(20L, (long)Math.ceil(this.maximumDuration * 20.0));
      }

      public DialogueCatalog.DialogueVariant chooseVariant() {
         return this.chooseVariant(1);
      }

      public DialogueCatalog.DialogueVariant chooseVariant(int rareVoicelines) {
         return this.chooseVariant(rareVoicelines, Set.of());
      }

      public DialogueCatalog.DialogueVariant chooseVariant(int rareVoicelines, Set<Integer> excludedVariants) {
         if (this.variants.isEmpty()) {
            return null;
         } else {
            int minimum = this.variants.stream().mapToInt(DialogueCatalog.DialogueVariant::weight).min().orElse(1);
            int maximum = this.variants.stream().mapToInt(DialogueCatalog.DialogueVariant::weight).max().orElse(1);
            int[] weights = new int[this.variants.size()];
            int totalWeight = 0;

            for (int index = 0; index < this.variants.size(); index++) {
               int weight = this.variants.get(index).weight();
               if (rareVoicelines == 0 && weight < maximum * 0.8) {
                  weight = 0;
               } else if (rareVoicelines == 2) {
                  weight = maximum + minimum - weight;
               }

               if (excludedVariants.contains(this.variants.get(index).index())) {
                  weight = 0;
               }

               weights[index] = Math.max(0, weight);
               totalWeight += weights[index];
            }

            if (totalWeight > 0) {
               int choice = ThreadLocalRandom.current().nextInt(Math.max(1, totalWeight));

               for (int index = 0; index < this.variants.size(); index++) {
                  choice -= weights[index];
                  if (choice < 0) {
                     return this.variants.get(index);
                  }
               }

               return this.variants.getLast();
            } else {
               return !excludedVariants.isEmpty() ? this.chooseVariant(rareVoicelines, Set.of()) : this.variants.getFirst();
            }
         }
      }
   }

   public record DialogueVariant(int index, double duration, int weight, String animation, SoundEvent sound, List<DialogueCatalog.SubtitleFrame> subtitles) {
      public long durationTicks() {
         return Math.max(20L, (long)Math.ceil(this.duration * 20.0));
      }
   }

   public record SubtitleFrame(double time, String key) {
   }
}
