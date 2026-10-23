package com.vnap.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.vnap.network.DialogueAnimationPayload;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import traben.entity_model_features.EMFAnimationApi;
import traben.entity_model_features.utils.EMFEntity;

public final class DialogueAnimationState {
   private static final String DATA_PATH = "/assets/villager-news-addon-port/dialogue_animations.json";
   private static final String[] TARGETS = new String[]{
      "root",
      "waist",
      "body",
      "head",
      "head_inner",
      "arms",
      "left_leg_root",
      "left_leg",
      "right_leg_root",
      "right_leg",
      "brow",
      "eye_group",
      "lower_face",
      "pupil_left",
      "pupil_right",
      "eye_left",
      "eye_right",
      "nose"
   };
   private static final String[] COMPONENTS = new String[]{"rx", "ry", "rz", "tx", "ty", "tz", "sx", "sy", "sz"};
   private static final Map<String, List<DialogueAnimationState.VariantTimeline>> TIMELINES = new HashMap<>();
   private static final List<DialogueAnimationState.Gesture> GESTURES = new ArrayList<>();
   private static final List<DialogueAnimationState.Gesture> IDLES = new ArrayList<>();
   private static final DialogueAnimationState.VariantTimeline EMPTY_TIMELINE = new DialogueAnimationState.VariantTimeline(List.of(), List.of());
   private static final Map<UUID, DialogueAnimationState.ActiveDialogue> ACTIVE = new ConcurrentHashMap<>();
   private static final Map<UUID, DialogueAnimationState.IdleState> IDLE_STATES = new ConcurrentHashMap<>();
   private static final Map<UUID, DialogueAnimationState.LookState> LOOK_STATES = new ConcurrentHashMap<>();
   private static final Map<UUID, DialogueAnimationState.TurnState> TURN_STATES = new ConcurrentHashMap<>();
   private static final Map<UUID, DialogueAnimationState.LocomotionState> LOCOMOTION_STATES = new ConcurrentHashMap<>();
   private static final float BLEND_SECONDS = 0.3F;
   private static final float IDLE_BLEND_SECONDS = 0.24F;
   private static final float MOUTH_BLEND_SECONDS = 0.15F;
   private static final float TURN_SECONDS = 0.5F;
   private static final float LOCOMOTION_BLEND_SECONDS = 0.2F;
   private static final float RUN_ENTER_SPEED = 0.6F;
   private static final float RUN_EXIT_SPEED = 0.3F;
   private static DialogueAnimationState.Gesture locomotion = new DialogueAnimationState.Gesture(0.0F, Map.of());
   private static DialogueAnimationState.Gesture runLocomotion = new DialogueAnimationState.Gesture(0.0F, Map.of());
   private static float framesPerSecond = 24.0F;

   private DialogueAnimationState() {
   }

   static void load() throws IOException {
      try (InputStream stream = DialogueAnimationState.class.getResourceAsStream("/assets/villager-news-addon-port/dialogue_animations.json")) {
         if (stream == null) {
            throw new IOException("Missing /assets/villager-news-addon-port/dialogue_animations.json");
         }

         JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
         framesPerSecond = root.get("framesPerSecond").getAsFloat();

         for (JsonElement gestureElement : root.getAsJsonArray("gestures")) {
            GESTURES.add(readGesture(gestureElement.getAsJsonObject()));
         }

         locomotion = readGesture(root.getAsJsonObject("locomotion"));
         runLocomotion = readGesture(root.getAsJsonObject("runLocomotion"));

         for (JsonElement idleElement : root.getAsJsonArray("idles")) {
            IDLES.add(readGesture(idleElement.getAsJsonObject()));
         }

         for (Entry<String, JsonElement> group : root.getAsJsonObject("groups").entrySet()) {
            List<DialogueAnimationState.VariantTimeline> variants = new ArrayList<>();

            for (JsonElement variantElement : group.getValue().getAsJsonArray()) {
               JsonObject variant = variantElement.getAsJsonObject();
               List<DialogueAnimationState.MouthFrame> mouth = new ArrayList<>();

               for (JsonElement frameElement : variant.getAsJsonArray("mouth")) {
                  JsonArray frame = frameElement.getAsJsonArray();
                  mouth.add(
                     new DialogueAnimationState.MouthFrame(
                        frame.get(0).getAsFloat(), frame.get(1).getAsFloat(), frame.get(2).getAsFloat(), frame.get(3).getAsFloat()
                     )
                  );
               }

               List<DialogueAnimationState.GestureFrame> gestures = new ArrayList<>();

               for (JsonElement frameElement : variant.getAsJsonArray("gestures")) {
                  JsonArray frame = frameElement.getAsJsonArray();
                  gestures.add(new DialogueAnimationState.GestureFrame(frame.get(0).getAsFloat(), frame.get(1).getAsInt()));
               }

               variants.add(new DialogueAnimationState.VariantTimeline(List.copyOf(mouth), List.copyOf(gestures)));
            }

            TIMELINES.put(group.getKey(), List.copyOf(variants));
         }
      }
   }

   private static DialogueAnimationState.Gesture readGesture(JsonObject value) {
      Map<String, float[]> tracks = new HashMap<>();

      for (Entry<String, JsonElement> track : value.getAsJsonObject("tracks").entrySet()) {
         JsonArray samples = track.getValue().getAsJsonArray();
         float[] values = new float[samples.size()];

         for (int index = 0; index < values.length; index++) {
            values[index] = samples.get(index).getAsFloat();
         }

         tracks.put(track.getKey(), values);
      }

      return new DialogueAnimationState.Gesture(value.get("duration").getAsFloat(), Map.copyOf(tracks));
   }

   static List<String> animationVariables() {
      List<String> variables = new ArrayList<>(TARGETS.length * COMPONENTS.length + 2);

      for (String target : TARGETS) {
         for (String component : COMPONENTS) {
            variables.add("vnap_" + target + "_" + component);
         }
      }

      variables.add("vnap_look_pitch");
      variables.add("vnap_look_yaw");
      return variables;
   }

   static void tick(Minecraft minecraft) {
      if (minecraft.level != null && minecraft.player != null) {
         long now = System.nanoTime();
         ACTIVE.entrySet().removeIf(entry -> now > entry.getValue().endNanos());
         IDLE_STATES.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
         LOOK_STATES.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
         TURN_STATES.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
         LOCOMOTION_STATES.keySet().removeIf(id -> minecraft.level.getEntity(id) == null);
      } else {
         clear();
      }
   }

   static void clear() {
      ACTIVE.clear();
      IDLE_STATES.clear();
      LOOK_STATES.clear();
      TURN_STATES.clear();
      LOCOMOTION_STATES.clear();
   }

   static void start(DialogueAnimationPayload payload) {
      if (payload.groupId().isEmpty()) {
         DialogueAnimationState.ActiveDialogue previous = ACTIVE.get(payload.entityId());
         if (previous != null && !previous.poseSnapshot().isEmpty()) {
            long now = System.nanoTime();
            ACTIVE.put(
               payload.entityId(), new DialogueAnimationState.ActiveDialogue(now, now, now + 300000000L, EMPTY_TIMELINE, previous.poseSnapshot(), false)
            );
         } else {
            ACTIVE.remove(payload.entityId());
         }
      } else {
         List<DialogueAnimationState.VariantTimeline> variants = TIMELINES.get(payload.groupId());
         if (variants != null && payload.variantIndex() >= 0 && payload.variantIndex() < variants.size()) {
            long now = System.nanoTime();
            DialogueAnimationState.VariantTimeline timeline = variants.get(payload.variantIndex());
            float audioSeconds = Math.max(1, payload.durationTicks()) / 20.0F;
            float totalSeconds = Math.max(audioSeconds + 0.15F, timeline.poseEndSeconds());
            DialogueAnimationState.ActiveDialogue previous = ACTIVE.get(payload.entityId());
            Map<String, Float> previousPose = previous == null ? Map.of() : previous.poseSnapshot();
            ACTIVE.put(
               payload.entityId(),
               new DialogueAnimationState.ActiveDialogue(
                  now, now + (long)(audioSeconds * 1.0E9F), now + (long)(totalSeconds * 1.0E9F), timeline, previousPose, true
               )
            );
         }
      }
   }

   static float speaking() {
      DialogueAnimationState.ActiveDialogue active = active();
      return active == null ? 0.0F : active.speechWeight();
   }

   static float mouthOpen() {
      DialogueAnimationState.MouthFrame frame = mouthFrame();
      return frame == null ? 0.0F : frame.open();
   }

   static float mouthWidth() {
      DialogueAnimationState.MouthFrame frame = mouthFrame();
      return frame == null ? 1.0F : frame.width();
   }

   static float mouthClosed() {
      DialogueAnimationState.MouthFrame frame = mouthFrame();
      return frame == null ? 1.0F : frame.closed();
   }

   static float hasNose() {
      EMFEntity entity = EMFAnimationApi.getCurrentEntity();
      boolean rainbow = entity instanceof Villager villager && "jeb_".equals(villager.getName().getString());
      RainbowNoseRenderState.update(rainbow, entity == null ? 0.0F : animationTick(entity), entity == null ? null : entity.etf$getUuid());
      return entity instanceof Villager ? 1.0F : 0.0F;
   }

   static float cosmetic(int cosmetic) {
      return 0.0F;
   }

   static float transform(String variableName) {
      DialogueAnimationState.ActiveDialogue active = active();
      if (!variableName.equals("vnap_look_pitch") && !variableName.equals("vnap_look_yaw")) {
         boolean scale = variableName.endsWith("_sx") || variableName.endsWith("_sy") || variableName.endsWith("_sz");
         float fallback = scale ? 1.0F : 0.0F;
         String trackName = variableName.substring("vnap_".length());
         float base = baseTransform(trackName, fallback, active);
         float dialogue = active == null ? fallback : active.timeline().transformAt(active.elapsedSeconds(), trackName, fallback);
         float result = scale ? base * dialogue : base + dialogue;
         return active == null ? result : active.transition(variableName, result);
      } else {
         return look(variableName.endsWith("pitch"));
      }
   }

   public static void trackBodyRotation(Villager villager, float bodyRotation, float age) {
      TURN_STATES.computeIfAbsent(villager.getUUID(), ignored -> new DialogueAnimationState.TurnState())
         .update(age, bodyRotation, villager.isAlive() && !villager.isSleeping() && villager.onGround());
   }

   private static float baseTransform(String trackName, float fallback, DialogueAnimationState.ActiveDialogue active) {
      EMFEntity emfEntity = EMFAnimationApi.getCurrentEntity();
      if (emfEntity instanceof LivingEntity entity && (entity instanceof Villager || entity instanceof WanderingTrader)) {
         UUID id = entity.getUUID();
         float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
         float age = emfEntity.emf$age() + partialTick;
         float speed = entity.walkAnimation.speed(partialTick);
         DialogueAnimationState.IdleState idle = IDLE_STATES.computeIfAbsent(id, ignored -> new DialogueAnimationState.IdleState());
         DialogueAnimationState.TurnState turn = TURN_STATES.computeIfAbsent(id, ignored -> new DialogueAnimationState.TurnState());
         DialogueAnimationState.LocomotionState locomotionState = LOCOMOTION_STATES.computeIfAbsent(id, ignored -> new DialogueAnimationState.LocomotionState());
         double horizontalDistanceSqr = entity.getDeltaMovement().horizontalDistanceSqr();
         boolean moving = speed > 0.01F && horizontalDistanceSqr > 1.0E-4;
         boolean groundedMovement = !entity.isSleeping() && entity.onGround() && moving;
         boolean canIdle = !entity.isSleeping() && entity.onGround() && !moving && !IDLES.isEmpty();
         idle.update(age, canIdle);
         locomotionState.update(age, speed, groundedMovement);
         if (!(entity instanceof Villager)) {
            turn.update(age, entity.yBodyRot, !entity.isSleeping() && entity.onGround());
         }

         float base = idle.valueAt(age, trackName, fallback);
         if (groundedMovement && locomotion.duration() > 0.0F) {
            float phase = entity.walkAnimation.position(partialTick) * 0.6662F / (float) (Math.PI * 2);
            float cycle = phase - (float)Math.floor(phase);
            float walkValue = locomotion.valueAt(cycle * locomotion.duration(), trackName, fallback);
            float runValue = runLocomotion.duration() > 0.0F ? runLocomotion.valueAt(cycle * runLocomotion.duration(), trackName, fallback) : walkValue;
            float value = DialogueAnimationState.VariantTimeline.lerp(walkValue, runValue, locomotionState.runWeight());
            float weight = Math.min(1.0F, speed * 0.9F);
            base = !trackName.endsWith("_sx") && !trackName.endsWith("_sy") && !trackName.endsWith("_sz")
               ? base + (value - fallback) * weight
               : base * DialogueAnimationState.VariantTimeline.lerp(fallback, value, weight);
         }

         float turnValue = turn.valueAt(age, trackName, fallback);
         float turnWeight = Mth.clamp(1.1F - speed, 0.01F, 1.0F);
         return !trackName.endsWith("_sx") && !trackName.endsWith("_sy") && !trackName.endsWith("_sz")
            ? base + (turnValue - fallback) * turnWeight
            : base * DialogueAnimationState.VariantTimeline.lerp(fallback, turnValue, turnWeight);
      } else {
         return fallback;
      }
   }

   private static float look(boolean pitch) {
      EMFEntity emfEntity = EMFAnimationApi.getCurrentEntity();
      if (emfEntity instanceof LivingEntity entity && (entity instanceof Villager || entity instanceof WanderingTrader) && !entity.isSleeping()) {
         float partialTick = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
         float age = emfEntity.emf$age() + partialTick;
         DialogueAnimationState.LookState state = LOOK_STATES.computeIfAbsent(entity.getUUID(), ignored -> new DialogueAnimationState.LookState());
         state.update(age, Mth.clamp(entity.getXRot(), -90.0F, 90.0F), Mth.clamp(Mth.wrapDegrees(entity.getYHeadRot() - entity.yBodyRot), -90.0F, 90.0F));
         return pitch ? state.pitch : state.yaw;
      } else {
         return 0.0F;
      }
   }

   private static float animationTick(EMFEntity entity) {
      return entity.emf$age() + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
   }

   private static DialogueAnimationState.MouthFrame mouthFrame() {
      DialogueAnimationState.ActiveDialogue active = active();
      return active != null && !(active.speechWeight() <= 0.0F) ? active.timeline().mouthAt(active.elapsedSeconds()) : null;
   }

   private static DialogueAnimationState.ActiveDialogue active() {
      EMFEntity entity = EMFAnimationApi.getCurrentEntity();
      if (entity != null && entity.etf$getUuid() != null) {
         UUID id = entity.etf$getUuid();
         DialogueAnimationState.ActiveDialogue value = ACTIVE.get(id);
         if (value == null) {
            return null;
         } else {
            value.beginFrame(animationTick(entity), System.nanoTime());
            if (value.frameNanos() > value.endNanos()) {
               ACTIVE.remove(id, value);
               return null;
            } else {
               return value;
            }
         }
      } else {
         return null;
      }
   }

   private static final class ActiveDialogue {
      private final long startNanos;
      private final long audioEndNanos;
      private final long endNanos;
      private final DialogueAnimationState.VariantTimeline timeline;
      private final Map<String, Float> previousPose;
      private final boolean hasAudio;
      private final Map<String, Float> renderedPose = new ConcurrentHashMap<>();
      private int frameAgeBits = Integer.MIN_VALUE;
      private long frameNanos;

      private ActiveDialogue(
         long startNanos, long audioEndNanos, long endNanos, DialogueAnimationState.VariantTimeline timeline, Map<String, Float> previousPose, boolean hasAudio
      ) {
         this.startNanos = startNanos;
         this.audioEndNanos = audioEndNanos;
         this.endNanos = endNanos;
         this.timeline = timeline;
         this.previousPose = previousPose;
         this.hasAudio = hasAudio;
         this.frameNanos = startNanos;
      }

      void beginFrame(float entityAge, long now) {
         int ageBits = Float.floatToIntBits(entityAge);
         if (ageBits != this.frameAgeBits) {
            this.frameAgeBits = ageBits;
            this.frameNanos = now;
         }
      }

      long frameNanos() {
         return this.frameNanos;
      }

      long endNanos() {
         return this.endNanos;
      }

      DialogueAnimationState.VariantTimeline timeline() {
         return this.timeline;
      }

      float elapsedSeconds() {
         return (float)(this.frameNanos - this.startNanos) / 1.0E9F;
      }

      float transition(String variableName, float value) {
         Float previous = this.previousPose.get(variableName);
         float result = previous == null
            ? value
            : DialogueAnimationState.VariantTimeline.lerp(previous, value, DialogueAnimationState.VariantTimeline.blendCurve(this.elapsedSeconds() / 0.3F));
         this.renderedPose.put(variableName, result);
         return result;
      }

      Map<String, Float> poseSnapshot() {
         return Map.copyOf(this.renderedPose);
      }

      float speechWeight() {
         if (!this.hasAudio) {
            return 0.0F;
         } else {
            long now = this.frameNanos;
            float fadeIn = (float)(now - this.startNanos) / 1.5E8F;
            float fadeOut = (float)(this.endNanos - now) / 1.5E8F;
            if (now <= this.audioEndNanos) {
               fadeOut = 1.0F;
            } else {
               fadeOut = (float)(this.audioEndNanos + 150000000L - now) / 1.5E8F;
            }

            return DialogueAnimationState.VariantTimeline.blendCurve(Math.min(fadeIn, fadeOut));
         }
      }
   }

   private record Gesture(float duration, Map<String, float[]> tracks) {
      float valueAt(float time, String trackName, float fallback) {
         return DialogueAnimationState.VariantTimeline.sample(this, trackName, Math.max(0.0F, Math.min(time, this.duration)), fallback);
      }
   }

   private record GestureFrame(float time, int gestureIndex) {
   }

   private static final class IdleState {
      private boolean active;
      private int activeIndex = -1;
      private int lastIndex = -1;
      private int blendFromIndex = -1;
      private float startTick;
      private float lastUpdateTick = Float.NaN;
      private float weight;

      void update(float tick, boolean shouldPlay) {
         if (Float.compare(this.lastUpdateTick, tick) != 0) {
            float elapsedTicks = Float.isNaN(this.lastUpdateTick) ? 0.0F : Math.max(0.0F, tick - this.lastUpdateTick);
            this.lastUpdateTick = tick;
            if (shouldPlay && !this.active) {
               this.active = true;
               this.startNext(tick, -1);
            }

            if (this.active) {
               this.advance(tick);
            }

            float step = elapsedTicks / 4.7999997F;
            this.weight = shouldPlay ? Math.min(1.0F, this.weight + step) : Math.max(0.0F, this.weight - step);
            if (!shouldPlay && this.weight == 0.0F && this.active) {
               this.active = false;
               if (this.activeIndex >= 0) {
                  this.lastIndex = this.activeIndex;
               }

               this.activeIndex = -1;
               this.blendFromIndex = -1;
            }
         }
      }

      float valueAt(float tick, String trackName, float fallback) {
         if (this.active && this.activeIndex >= 0 && !DialogueAnimationState.IDLES.isEmpty()) {
            DialogueAnimationState.Gesture activeGesture = DialogueAnimationState.IDLES.get(this.activeIndex);
            float elapsed = (tick - this.startTick) / 20.0F;
            float value = activeGesture.valueAt(elapsed, trackName, fallback);
            if (elapsed < 0.3F) {
               float previous = this.blendFromIndex < 0
                  ? fallback
                  : DialogueAnimationState.IDLES
                     .get(this.blendFromIndex)
                     .valueAt(DialogueAnimationState.IDLES.get(this.blendFromIndex).duration(), trackName, fallback);
               value = DialogueAnimationState.VariantTimeline.lerp(previous, value, DialogueAnimationState.VariantTimeline.blendCurve(elapsed / 0.3F));
            }

            return DialogueAnimationState.VariantTimeline.lerp(fallback, value, this.weight);
         } else {
            return fallback;
         }
      }

      private void advance(float tick) {
         for (int transitions = 0; transitions < 32; transitions++) {
            DialogueAnimationState.Gesture gesture = DialogueAnimationState.IDLES.get(this.activeIndex);
            float durationTicks = gesture.duration() * 20.0F;
            if (tick - this.startTick <= durationTicks) {
               return;
            }

            float nextStartTick = this.startTick + durationTicks;
            int previous = this.activeIndex;
            this.startNext(nextStartTick, previous);
         }

         this.startNext(tick, this.activeIndex);
      }

      private void startNext(float tick, int blendFromIndex) {
         int next = ThreadLocalRandom.current().nextInt(DialogueAnimationState.IDLES.size());
         if (DialogueAnimationState.IDLES.size() > 1 && next == this.lastIndex) {
            next = (next + 1) % DialogueAnimationState.IDLES.size();
         }

         this.blendFromIndex = blendFromIndex;
         this.activeIndex = next;
         this.lastIndex = next;
         this.startTick = tick;
      }
   }

   private static final class LocomotionState {
      private boolean running;
      private float smoothedSpeed;
      private float runWeight;
      private float lastUpdateTick = Float.NaN;

      void update(float tick, float movementSpeed, boolean canMove) {
         if (Float.compare(this.lastUpdateTick, tick) != 0) {
            if (!Float.isNaN(this.lastUpdateTick) && !(tick < this.lastUpdateTick) && !(tick - this.lastUpdateTick > 5.0F)) {
               float elapsedTicks = tick - this.lastUpdateTick;
               this.lastUpdateTick = tick;
               float speedBlend = 1.0F - (float)Math.pow(0.3, elapsedTicks);
               this.smoothedSpeed = DialogueAnimationState.VariantTimeline.lerp(this.smoothedSpeed, movementSpeed, speedBlend);
               this.running = canMove && (this.running ? this.smoothedSpeed >= 0.3F : this.smoothedSpeed > 0.6F);
               float step = elapsedTicks / 4.0F;
               this.runWeight = this.running ? Math.min(1.0F, this.runWeight + step) : Math.max(0.0F, this.runWeight - step);
            } else {
               this.smoothedSpeed = movementSpeed;
               this.running = canMove && movementSpeed > 0.6F;
               this.runWeight = this.running ? 1.0F : 0.0F;
               this.lastUpdateTick = tick;
            }
         }
      }

      float runWeight() {
         return this.runWeight;
      }
   }

   private static final class LookState {
      private float pitch;
      private float yaw;
      private float lastUpdateTick = Float.NaN;

      void update(float tick, float targetPitch, float targetYaw) {
         if (Float.compare(this.lastUpdateTick, tick) != 0) {
            if (!Float.isNaN(this.lastUpdateTick) && !(tick < this.lastUpdateTick) && !(tick - this.lastUpdateTick > 5.0F)) {
               float elapsedTicks = tick - this.lastUpdateTick;
               this.lastUpdateTick = tick;
               float yawBlend = 1.0F - (float)Math.pow(0.95, elapsedTicks * 3.0F);
               float pitchBlend = 1.0F - (float)Math.pow(0.98, elapsedTicks * 3.0F);
               this.yaw = this.yaw + Mth.wrapDegrees(targetYaw - this.yaw) * yawBlend;
               this.pitch = DialogueAnimationState.VariantTimeline.lerp(this.pitch, targetPitch, pitchBlend);
            } else {
               this.pitch = targetPitch;
               this.yaw = targetYaw;
               this.lastUpdateTick = tick;
            }
         }
      }
   }

   private record MouthFrame(float time, float open, float width, float closed) {
   }

   private static final class TurnState {
      private boolean playing;
      private float anchorYaw;
      private float lastBodyYaw;
      private float lastUpdateTick = Float.NaN;
      private float startTick;
      private float signal;

      void update(float tick, float bodyYaw, boolean canTurn) {
         if (Float.compare(this.lastUpdateTick, tick) != 0) {
            if (!Float.isNaN(this.lastUpdateTick) && !(tick < this.lastUpdateTick) && !(tick - this.lastUpdateTick > 5.0F)) {
               float bodyDelta = Mth.wrapDegrees(bodyYaw - this.lastBodyYaw);
               this.lastUpdateTick = tick;
               if (!canTurn) {
                  this.playing = false;
                  this.anchorYaw = bodyYaw;
                  this.lastBodyYaw = bodyYaw;
                  this.signal = 0.0F;
               } else {
                  if (this.playing && tick - this.startTick >= 10.0F) {
                     this.playing = false;
                     this.anchorYaw = this.lastBodyYaw;
                  }

                  if (!this.playing && Math.abs(bodyDelta) > 0.1F) {
                     this.playing = true;
                     this.anchorYaw = this.lastBodyYaw;
                     this.startTick = tick;
                  }

                  if (this.playing) {
                     this.signal = Mth.sin(Mth.wrapDegrees(bodyYaw - this.anchorYaw) * (float) (Math.PI / 180.0)) * 90.0F;
                     if (this.signal * bodyDelta < -0.1F) {
                        this.anchorYaw = this.lastBodyYaw;
                        this.startTick = tick;
                        this.signal = Mth.sin(bodyDelta * (float) (Math.PI / 180.0)) * 90.0F;
                     }
                  } else {
                     this.signal = 0.0F;
                  }

                  this.lastBodyYaw = bodyYaw;
               }
            } else {
               this.playing = false;
               this.anchorYaw = bodyYaw;
               this.lastBodyYaw = bodyYaw;
               this.lastUpdateTick = tick;
            }
         }
      }

      float valueAt(float tick, String trackName, float fallback) {
         if (this.playing && this.signal != 0.0F) {
            float time = Mth.clamp((tick - this.startTick) / 20.0F, 0.0F, 0.5F);
            if (trackName.equals("waist_rz")) {
               return time <= 0.25F ? -Mth.sin(time * 720.0F * (float) (Math.PI / 180.0)) * this.signal * 0.1F * (float) (Math.PI / 180.0) : 0.0F;
            } else if (!trackName.equals("waist_ty")) {
               boolean positive = this.signal > 0.0F;
               if (trackName.equals("left_leg_root_ry")) {
                  return this.legRotation(time, positive ? 0.0F : 0.2083F, positive ? 0.1667F : 0.375F);
               } else if (trackName.equals("right_leg_root_ry")) {
                  return this.legRotation(time, positive ? 0.2083F : 0.0F, positive ? 0.375F : 0.1667F);
               } else if (trackName.equals("left_leg_root_ty")) {
                  return this.legLift(time, positive ? 0.0F : 0.2083F, positive ? 0.0833F : 0.2917F, positive ? 0.1667F : 0.375F, positive ? 0.06F : 0.05F);
               } else {
                  return trackName.equals("right_leg_root_ty")
                     ? this.legLift(time, positive ? 0.2083F : 0.0F, positive ? 0.2917F : 0.0833F, positive ? 0.375F : 0.1667F, positive ? 0.05F : 0.06F)
                     : fallback;
               }
            } else {
               return time <= 0.25F && Math.abs(this.signal) > 12.0F ? Mth.sin(time * 1440.0F * (float) (Math.PI / 180.0)) * 0.3F : 0.0F;
            }
         } else {
            return fallback;
         }
      }

      private float legRotation(float time, float holdUntil, float end) {
         if (time <= holdUntil) {
            return -this.signal * (float) (Math.PI / 180.0);
         } else {
            return time >= end ? 0.0F : -this.signal * (float) (Math.PI / 180.0) * (1.0F - (time - holdUntil) / (end - holdUntil));
         }
      }

      private float legLift(float time, float start, float peak, float end, float multiplier) {
         if (!(time < start) && !(time > end)) {
            float height = Math.min(Math.abs(this.signal) * multiplier, 1.0F);
            float weight = time <= peak ? (time - start) / (peak - start) : (end - time) / (end - peak);
            return -height * Mth.clamp(weight, 0.0F, 1.0F);
         } else {
            return 0.0F;
         }
      }
   }

   private record VariantTimeline(List<DialogueAnimationState.MouthFrame> mouth, List<DialogueAnimationState.GestureFrame> gestures) {
      DialogueAnimationState.MouthFrame mouthAt(float time) {
         DialogueAnimationState.MouthFrame selected = this.mouth.isEmpty() ? null : this.mouth.getFirst();

         for (DialogueAnimationState.MouthFrame frame : this.mouth) {
            if (frame.time() > time) {
               break;
            }

            selected = frame;
         }

         return selected;
      }

      float transformAt(float time, String trackName, float fallback) {
         int selected = -1;
         int index = 0;

         while (index < this.gestures.size() && !(this.gestures.get(index).time() > time)) {
            selected = index++;
         }

         if (selected < 0) {
            return fallback;
         } else {
            DialogueAnimationState.GestureFrame current = this.gestures.get(selected);
            float currentValue = stateValue(current, time, trackName, fallback);
            float transitionTime = time - current.time();
            if (transitionTime >= 0.3F) {
               return currentValue;
            } else {
               float previousValue = selected == 0 ? fallback : stateValue(this.gestures.get(selected - 1), time, trackName, fallback);
               return lerp(previousValue, currentValue, blendCurve(transitionTime / 0.3F));
            }
         }
      }

      float poseWeightAt(float time) {
         int selected = -1;
         int index = 0;

         while (index < this.gestures.size() && !(this.gestures.get(index).time() > time)) {
            selected = index++;
         }

         if (selected < 0) {
            return 0.0F;
         } else {
            DialogueAnimationState.GestureFrame current = this.gestures.get(selected);
            float currentWeight = stateWeight(current, time);
            float transitionTime = time - current.time();
            if (transitionTime >= 0.3F) {
               return currentWeight;
            } else {
               float previousWeight = selected == 0 ? 0.0F : stateWeight(this.gestures.get(selected - 1), time);
               return lerp(previousWeight, currentWeight, blendCurve(transitionTime / 0.3F));
            }
         }
      }

      float poseEndSeconds() {
         if (this.gestures.isEmpty()) {
            return 0.0F;
         } else {
            DialogueAnimationState.GestureFrame last = this.gestures.getLast();
            return last.gestureIndex() >= 0 && last.gestureIndex() < DialogueAnimationState.GESTURES.size()
               ? last.time() + DialogueAnimationState.GESTURES.get(last.gestureIndex()).duration() + 0.3F
               : last.time() + 0.3F;
         }
      }

      private static float stateValue(DialogueAnimationState.GestureFrame frame, float time, String trackName, float fallback) {
         if (frame.gestureIndex() >= 0 && frame.gestureIndex() < DialogueAnimationState.GESTURES.size()) {
            DialogueAnimationState.Gesture gesture = DialogueAnimationState.GESTURES.get(frame.gestureIndex());
            float localTime = Math.max(0.0F, time - frame.time());
            float value = sample(gesture, trackName, Math.min(localTime, gesture.duration()), fallback);
            if (localTime <= gesture.duration()) {
               return value;
            } else {
               float out = blendCurve((localTime - gesture.duration()) / 0.3F);
               return lerp(value, fallback, out);
            }
         } else {
            return fallback;
         }
      }

      private static float stateWeight(DialogueAnimationState.GestureFrame frame, float time) {
         if (frame.gestureIndex() >= 0 && frame.gestureIndex() < DialogueAnimationState.GESTURES.size()) {
            DialogueAnimationState.Gesture gesture = DialogueAnimationState.GESTURES.get(frame.gestureIndex());
            float localTime = Math.max(0.0F, time - frame.time());
            return localTime <= gesture.duration() ? 1.0F : 1.0F - blendCurve((localTime - gesture.duration()) / 0.3F);
         } else {
            return 0.0F;
         }
      }

      private static float sample(DialogueAnimationState.Gesture gesture, String trackName, float localTime, float fallback) {
         float[] samples = gesture.tracks().get(trackName);
         if (samples != null && samples.length != 0) {
            float sample = Math.max(0.0F, localTime) * DialogueAnimationState.framesPerSecond;
            int lower = Math.min(samples.length - 1, (int)Math.floor(sample));
            int upper = Math.min(samples.length - 1, lower + 1);
            float progress = Math.min(1.0F, sample - lower);
            return lerp(samples[lower], samples[upper], progress);
         } else {
            return fallback;
         }
      }

      private static float blendCurve(float progress) {
         float clamped = Math.max(0.0F, Math.min(1.0F, progress));
         float sine = (float)Math.sin(clamped * Math.PI * 0.5);
         return sine * sine;
      }

      private static float lerp(float from, float to, float progress) {
         return from + (to - from) * progress;
      }
   }
}
