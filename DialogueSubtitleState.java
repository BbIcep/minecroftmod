package com.vnap.client;

import com.vnap.VillagerNewsAddonPort;
import com.vnap.dialogue.DialogueCatalog;
import com.vnap.network.DialogueAnimationPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;

public final class DialogueSubtitleState {
   private static final double RANGE = 16.0;
   private static final double RANGE_SQUARED = 256.0;
   private static final int MAX_LINES = 4;
   private static final Map<UUID, DialogueSubtitleState.ActiveSubtitle> ACTIVE = new HashMap<>();

   private DialogueSubtitleState() {
   }

   public static void register() {
      HudElementRegistry.attachElementAfter(VanillaHudElements.OVERLAY_MESSAGE, VillagerNewsAddonPort.id("dialogue_subtitles"), DialogueSubtitleState::render);
   }

   public static void start(DialogueAnimationPayload payload) {
      if (!payload.groupId().isEmpty() && payload.durationTicks() > 0) {
         DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(payload.groupId());
         if (group != null) {
            DialogueCatalog.DialogueVariant variant = group.variants()
               .stream()
               .filter(candidate -> candidate.index() == payload.variantIndex())
               .findFirst()
               .orElse(null);
            if (variant != null && !variant.subtitles().isEmpty()) {
               long startNanos = System.nanoTime();
               ACTIVE.put(
                  payload.entityId(),
                  new DialogueSubtitleState.ActiveSubtitle(startNanos, startNanos + payload.durationTicks() * 50000000L, variant.subtitles())
               );
            }
         }
      } else {
         ACTIVE.remove(payload.entityId());
      }
   }

   public static void tick(Minecraft minecraft) {
      if (minecraft.level != null && minecraft.player != null) {
         long now = System.nanoTime();
         Iterator<Entry<UUID, DialogueSubtitleState.ActiveSubtitle>> iterator = ACTIVE.entrySet().iterator();

         while (iterator.hasNext()) {
            Entry<UUID, DialogueSubtitleState.ActiveSubtitle> entry = iterator.next();
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (now >= entry.getValue().endNanos() || entity != null && !entity.isAlive()) {
               iterator.remove();
            }
         }
      } else {
         clear();
      }
   }

   public static void clear() {
      ACTIVE.clear();
   }

   private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level != null && minecraft.player != null && VillagerNewsClientSettings.showSubtitles()) {
         long now = System.nanoTime();
         List<DialogueSubtitleState.VisibleSubtitle> visible = new ArrayList<>();

         for (Entry<UUID, DialogueSubtitleState.ActiveSubtitle> entry : ACTIVE.entrySet()) {
            DialogueSubtitleState.ActiveSubtitle active = entry.getValue();
            if (now < active.endNanos()) {
               Entity entity = minecraft.level.getEntity(entry.getKey());
               if (entity != null && entity.isAlive()) {
                  double distanceSquared = minecraft.player.distanceToSqr(entity);
                  if (!(distanceSquared > 256.0)) {
                     int frame = active.frame(now);
                     if (frame >= 0) {
                        Component transcript = Component.translatable(active.subtitles().get(frame).key());
                        visible.add(new DialogueSubtitleState.VisibleSubtitle(distanceSquared, subtitleLine(entity, transcript)));
                     }
                  }
               }
            }
         }

         visible.sort(Comparator.comparingDouble(DialogueSubtitleState.VisibleSubtitle::distanceSquared));
         float y = graphics.guiHeight() - 59.0F;

         for (int index = 0; index < Math.min(4, visible.size()); index++) {
            DialogueSubtitleState.VisibleSubtitle subtitle = visible.get(index);
            float scale = subtitleScale(index, subtitle.distanceSquared());
            drawCentered(graphics, minecraft, subtitle.text(), y, scale);
            y -= 9.0F + 3.0F;
         }
      }
   }

   private static Component subtitleLine(Entity entity, Component transcript) {
      Component name = entity.getName();
      if (entity instanceof Villager villager && !villager.hasCustomName()) {
         name = ((VillagerProfession)villager.getVillagerData().profession().value()).name();
      }

      MutableComponent line = Component.empty();
      line.append(name.copy().withStyle(ChatFormatting.YELLOW));
      line.append(Component.literal(": ").withStyle(ChatFormatting.YELLOW));
      line.append(transcript.copy().withStyle(ChatFormatting.WHITE));
      return line;
   }

   private static float subtitleScale(int index, double distanceSquared) {
      if (index == 0) {
         return 1.0F;
      } else {
         double distance = Math.sqrt(distanceSquared);
         return (float)Math.max(0.65, Math.min(0.9, 0.95 - distance / 16.0 * 0.3));
      }
   }

   private static void drawCentered(GuiGraphicsExtractor graphics, Minecraft minecraft, Component text, float y, float scale) {
      int width = minecraft.font.width(text);
      graphics.pose().pushMatrix();
      graphics.pose().translate(graphics.guiWidth() / 2.0F, y);
      graphics.pose().scale(scale, scale);
      graphics.text(minecraft.font, text, -width / 2, 0, -1, true);
      graphics.pose().popMatrix();
   }

   private record ActiveSubtitle(long startNanos, long endNanos, List<DialogueCatalog.SubtitleFrame> subtitles) {
      int frame(long now) {
         double elapsed = (now - this.startNanos) / 1.0E9;
         int frame = -1;
         int index = 0;

         while (index < this.subtitles.size() && !(this.subtitles.get(index).time() > elapsed)) {
            frame = index++;
         }

         return frame;
      }
   }

   private record VisibleSubtitle(double distanceSquared, Component text) {
   }
}
