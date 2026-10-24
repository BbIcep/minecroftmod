package com.vnap.client;

import com.vnap.dialogue.DialogueCatalog;
import com.vnap.network.DialogueAnimationPayload;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

public final class DialogueSoundState {
   private static final Map<UUID, DialogueSoundState.ActiveSound> ACTIVE = new HashMap<>();
   private static final Map<UUID, DialogueSoundState.PendingSound> PENDING = new HashMap<>();
   private static final long PENDING_TIMEOUT_NANOS = 5000000000L;

   private DialogueSoundState() {
   }

   public static void start(DialogueAnimationPayload payload) {
      Minecraft minecraft = Minecraft.getInstance();
      stop(minecraft, payload.entityId());
      PENDING.remove(payload.entityId());
      if (!payload.groupId().isEmpty() && minecraft.level != null) {
         if (!tryStart(minecraft, payload)) {
            PENDING.put(payload.entityId(), new DialogueSoundState.PendingSound(payload, System.nanoTime() + 5000000000L));
         }
      }
   }

   private static boolean tryStart(Minecraft minecraft, DialogueAnimationPayload payload) {
      DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(payload.groupId());
      if (group == null) {
         return true;
      } else {
         DialogueCatalog.DialogueVariant variant = group.variants()
            .stream()
            .filter(candidate -> candidate.index() == payload.variantIndex())
            .findFirst()
            .orElse(null);
         Entity entity = minecraft.level.getEntity(payload.entityId());
         if (variant == null) {
            return true;
         } else if (entity == null) {
            return false;
         } else {
            boolean followsEntity = entity.isAlive() && !entity.isSilent() && !payload.groupId().equals("hivgme") && !payload.groupId().equals("ecslqo");
            SoundInstance sound = (SoundInstance)(followsEntity
               ? new EntityBoundSoundInstance(variant.sound(), SoundSource.NEUTRAL, 1.0F, 1.0F, entity, entity.getRandom().nextLong())
               : new SimpleSoundInstance(variant.sound(), SoundSource.NEUTRAL, 1.0F, 1.0F, RandomSource.create(), entity.getX(), entity.getY(), entity.getZ()));
            minecraft.getSoundManager().play(sound);
            ACTIVE.put(payload.entityId(), new DialogueSoundState.ActiveSound(sound, followsEntity, System.nanoTime() + payload.durationTicks() * 50000000L));
            return true;
         }
      }
   }

   public static void tick(Minecraft minecraft) {
      if (minecraft.level != null && minecraft.player != null) {
         long now = System.nanoTime();
         Iterator<Entry<UUID, DialogueSoundState.PendingSound>> pendingIterator = PENDING.entrySet().iterator();

         while (pendingIterator.hasNext()) {
            DialogueSoundState.PendingSound pending = pendingIterator.next().getValue();
            if (now >= pending.expiresAtNanos() || tryStart(minecraft, pending.payload())) {
               pendingIterator.remove();
            }
         }

         Iterator<Entry<UUID, DialogueSoundState.ActiveSound>> iterator = ACTIVE.entrySet().iterator();

         while (iterator.hasNext()) {
            Entry<UUID, DialogueSoundState.ActiveSound> entry = iterator.next();
            Entity entity = minecraft.level.getEntity(entry.getKey());
            if (now >= entry.getValue().endNanos() || entry.getValue().followsEntity() && (entity == null || !entity.isAlive())) {
               minecraft.getSoundManager().stop(entry.getValue().instance());
               iterator.remove();
            }
         }
      } else {
         clear(minecraft);
      }
   }

   public static void clear(Minecraft minecraft) {
      PENDING.clear();

      for (UUID id : ACTIVE.keySet().toArray(UUID[]::new)) {
         stop(minecraft, id);
      }
   }

   private static void stop(Minecraft minecraft, UUID id) {
      DialogueSoundState.ActiveSound active = ACTIVE.remove(id);
      if (active != null) {
         minecraft.getSoundManager().stop(active.instance());
      }
   }

   private record ActiveSound(SoundInstance instance, boolean followsEntity, long endNanos) {
   }

   private record PendingSound(DialogueAnimationPayload payload, long expiresAtNanos) {
   }
}
