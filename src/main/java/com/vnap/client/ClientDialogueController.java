package com.vnap.client;

import com.vnap.config.VillagerNewsSettings;
import com.vnap.dialogue.DialogueCatalog;
import com.vnap.network.DialogueAnimationPayload;
import com.vnap.network.HurtEffectPayload;
import com.vnap.sound.SupplementalSoundCatalog;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side replacement for the original server dialogue controller.
 * Everything here only looks at what the client already knows (entities in view,
 * the local player, the local clock) and starts the same voice / animation /
 * subtitle playback the server used to trigger with packets.
 */
public final class ClientDialogueController {
   private static final Set<String> BABY_DIALOGUES = Set.of(
      "abfwiv", "aezdiy", "ahcvzd", "cmrqhw", "durjjd", "ecslqo", "fzyrfm", "ggitzq", "gotjxf", "gzsztp", "hbalps", "hcdvqm", "jfuftm",
      "lgjtnf", "mqnapy", "msemoe", "nxalcz", "qrdzmt", "rfnirh", "saxuwk", "svdjdk", "vbclem", "vhwksn", "wkwcrf", "wsxfok", "wtuguc",
      "zeykfp", "cxeziv", "riezum", "rlkdqd"
   );
   private static final Map<String, DialogueCatalog.DialogueGroup> INDEX = new HashMap<>();
   private static final Map<String, Long> COOLDOWNS = new HashMap<>();
   private static final Map<UUID, Long> BUSY_UNTIL = new HashMap<>();
   private static final Map<String, List<Integer>> RECENT_VARIANTS = new HashMap<>();
   private static final Map<UUID, VillagerState> STATES = new HashMap<>();
   private static final Map<UUID, Long> PLAYER_HITS = new HashMap<>();
   private static final Map<UUID, String> PLAYER_HIT_WEAPON = new HashMap<>();

   private static long ticks;
   private static long lastAnyDialogue = -1000L;
   private static long lastAmbient = -1000L;
   private static long pendingBellTick = -1L;
   private static UUID interactedId;
   private static UUID lastInteractedId;
   private static long interactedTick = -1000L;
   private static boolean tradingOpen;
   private static boolean boughtAny;
   private static long inventoryHash;
   private static UUID tradingSpeaker;
   private static boolean wasPlayerDead;
   private static int stareTicks;

   private ClientDialogueController() {
   }

   // ------------------------------------------------------------------ setup

   public static void register() {
      buildIndex();

      UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
         if (level.isClientSide() && player == Minecraft.getInstance().player) {
            onUseBlock(player, level, hand, hitResult.getBlockPos());
         }

         return InteractionResult.PASS;
      });
      UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
         if (level.isClientSide() && player == Minecraft.getInstance().player) {
            onUseEntity(player, hand, entity);
         }

         return InteractionResult.PASS;
      });
      AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
         if (level.isClientSide() && player == Minecraft.getInstance().player && entity instanceof Villager) {
            PLAYER_HITS.put(entity.getUUID(), ticks);
            PLAYER_HIT_WEAPON.put(entity.getUUID(), itemPath(player.getMainHandItem()));
         }

         return InteractionResult.PASS;
      });
      ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> onEntityLoad(entity));
   }

   public static void clear() {
      COOLDOWNS.clear();
      BUSY_UNTIL.clear();
      RECENT_VARIANTS.clear();
      STATES.clear();
      PLAYER_HITS.clear();
      PLAYER_HIT_WEAPON.clear();
      tradingOpen = false;
      boughtAny = false;
      interactedId = null;
      lastInteractedId = null;
      pendingBellTick = -1L;
      wasPlayerDead = false;
      stareTicks = 0;
   }

   private static void buildIndex() {
      INDEX.clear();

      for (DialogueCatalog.DialogueGroup group : DialogueCatalog.groups().values()) {
         if (group.title().isBlank()) {
            continue;
         }

         String kind;
         if (group.speaker().equals("villager")) {
            kind = BABY_DIALOGUES.contains(group.id()) ? "baby" : "adult";
         } else if (group.speaker().equals("wandering_trader")) {
            kind = "trader";
         } else {
            continue;
         }

         INDEX.putIfAbsent(kind + "|" + group.title(), group);
      }
   }

   // ------------------------------------------------------------------- tick

   public static void tick(Minecraft minecraft) {
      ClientLevel level = minecraft.level;
      LocalPlayer player = minecraft.player;
      if (level == null || player == null) {
         return;
      }

      ticks++;
      trackPlayerDeath(level, player);
      trackTrading(minecraft, level, player);
      trackPendingInteraction(minecraft, level, player);
      trackBell(level, player);
      if (ticks % 5L == 0L) {
         scan(level, player);
      }
   }

   private static void scan(ClientLevel level, LocalPlayer player) {
      AABB area = player.getBoundingBox().inflate(28.0);
      List<Villager> villagers = level.getEntitiesOfClass(Villager.class, area, candidate -> true);
      List<WanderingTrader> traders = level.getEntitiesOfClass(WanderingTrader.class, area, candidate -> true);

      Set<UUID> seen = new HashSet<>();
      for (Villager villager : villagers) {
         seen.add(villager.getUUID());
         trackVillager(level, player, villager);
      }

      STATES.keySet().retainAll(seen);

      List<Villager> awake = new ArrayList<>();
      for (Villager villager : villagers) {
         if (villager.isAlive() && !villager.isSleeping()) {
            awake.add(villager);
         }
      }

      awake.sort((a, b) -> Double.compare(a.distanceToSqr(player), b.distanceToSqr(player)));

      greetPlayer(level, player, awake);
      stare(player, awake);
      if (ticks % 10L == 0L) {
         ambient(level, player, awake, traders);
      }
   }

   // --------------------------------------------------- per-villager tracking

   private static void trackVillager(ClientLevel level, LocalPlayer player, Villager villager) {
      VillagerState state = STATES.computeIfAbsent(villager.getUUID(), id -> new VillagerState(villager));
      boolean dead = villager.isDeadOrDying();
      if (dead && !state.dead) {
         state.dead = true;
         sayId(villager, "hivgme", "death:" + villager.getUUID(), 0L, true);
         for (Villager other : nearbyVillagers(level, villager.position(), 14.0)) {
            if (other != villager && !other.isDeadOrDying() && other.hasLineOfSight(villager)) {
               say(other, "See Another Villager Die", "seedeath", 200L, false);
               break;
            }
         }
      } else if (!dead) {
         if (villager.hurtTime > state.hurtTime && villager.hurtTime > 0) {
            onVillagerHurt(player, villager);
         }

         if (villager.isSleeping() && !state.sleeping) {
            say(villager, "Go to Bed", "bed:" + villager.getUUID(), 600L, false);
         } else if (!villager.isSleeping() && state.sleeping) {
            long dayTime = Math.floorMod(level.getOverworldClockTime(), 24000L)
            if (dayTime < 1500L) {
               say(villager, "Wake Up Naturally", "wake:" + villager.getUUID(), 600L, false);
            } else if (villager.distanceTo(player) < 8.0F) {
               say(villager, "Wake a Sleeping Villager", "wake2:" + villager.getUUID(), 600L, false);
            }
         } else if (villager.isSleeping() && ticks % 1200L == 0L) {
            sayId(villager, "asqzby", "sleeping:" + villager.getUUID(), 2400L, false);
         }

         if (state.baby && !villager.isBaby()) {
            say(villager, "Grow Up", "grow:" + villager.getUUID(), 0L, false);
         }
      }

      state.hurtTime = villager.hurtTime;
      state.sleeping = villager.isSleeping();
      state.baby = villager.isBaby();
   }

   private static void onVillagerHurt(LocalPlayer player, Villager villager) {
      Long hit = PLAYER_HITS.get(villager.getUUID());
      boolean byPlayer = hit != null && ticks - hit <= 8L;
      String id;
      if (villager.isBaby()) {
         id = byPlayer ? "ahcvzd" : "ecslqo";
      } else if (byPlayer) {
         String weapon = PLAYER_HIT_WEAPON.getOrDefault(villager.getUUID(), "");
         if (weapon.endsWith("_sword")) {
            id = "rueszy";
         } else if (weapon.endsWith("_axe")) {
            id = "yjctyw";
         } else if (weapon.endsWith("_hoe")) {
            id = "qqyjjg";
         } else if (weapon.endsWith("_shovel")) {
            id = "hpnsfu";
         } else {
            id = "vevdkl";
         }
      } else {
         id = "wyvzhk";
      }

      boolean spoke = sayId(villager, id, "hurt:" + villager.getUUID(), 20L, true);
      if (!spoke) {
         SupplementalSoundState.play(new HurtEffectPayload(villager.getUUID(), SupplementalSoundCatalog.chooseHurtEffect(villager.isBaby())));
      }
   }

   // ------------------------------------------------------- reactive events

   private static void greetPlayer(ClientLevel level, LocalPlayer player, List<Villager> awake) {
      for (Villager villager : awake) {
         if (villager.distanceTo(player) > 6.0F) {
            break;
         }

         if (!villager.hasLineOfSight(player)) {
            continue;
         }

         String key = "greet:" + villager.getUUID();
         if (villager.isBaby()) {
            String id = player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) ? "fzyrfm" : "wtuguc";
            if (sayId(villager, id, key, 12000L, false)) {
               return;
            }
         } else {
            String title = player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) ? "Hero of the Village" : "Approach a Villager";
            if (say(villager, title, key, 12000L, false)) {
               return;
            }
         }
      }
   }

   private static void stare(LocalPlayer player, List<Villager> awake) {
      Vec3 eye = player.getEyePosition();
      Vec3 look = player.getLookAngle();
      Villager target = null;
      for (Villager villager : awake) {
         if (villager.distanceTo(player) > 6.0F || villager.isBaby()) {
            continue;
         }

         Vec3 toVillager = villager.getEyePosition().subtract(eye).normalize();
         if (toVillager.dot(look) > 0.985) {
            target = villager;
            break;
         }
      }

      if (target == null) {
         stareTicks = 0;
         return;
      }

      stareTicks += 5;
      if (stareTicks >= 80) {
         if (say(target, "Stare at a Villager", "stare", 3600L, false)) {
            stareTicks = 0;
         }
      }
   }

   private static void trackPlayerDeath(ClientLevel level, LocalPlayer player) {
      boolean dead = player.isDeadOrDying();
      if (dead && !wasPlayerDead) {
         List<Villager> near = nearbyVillagers(level, player.position(), 24.0);
         for (Villager villager : near) {
            if (!villager.isBaby() && villager.isAlive() && say(villager, "Player Dies", "playerdies", 6000L, false)) {
               break;
            }
         }
      }

      wasPlayerDead = dead;
   }

   private static void trackBell(ClientLevel level, LocalPlayer player) {
      if (pendingBellTick < 0L || ticks < pendingBellTick) {
         return;
      }

      pendingBellTick = -1L;
      for (Villager villager : nearbyVillagers(level, player.position(), 32.0)) {
         if (villager.isAlive() && !villager.isSleeping()) {
            if (villager.isBaby()) {
               if (say(villager, "Ring a Bell Near a Baby", "bellbaby", 400L, false)) {
                  return;
               }
            } else if (say(villager, "Hear a Bell Ring", "bell", 400L, false)) {
               return;
            }
         }
      }
   }

   private static void onEntityLoad(Entity entity) {
      Minecraft minecraft = Minecraft.getInstance();
      ClientLevel level = minecraft.level;
      LocalPlayer player = minecraft.player;
      if (level == null || player == null || entity == player) {
         return;
      }

      if (entity instanceof Villager baby && baby.isBaby() && baby.getAge() <= -23900) {
         say(baby, "Baby Villager Is Born", "born:" + baby.getUUID(), 0L, false);
         return;
      }

      String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
      String title = switch (path) {
         case "lightning_bolt" -> "See Lightning";
         case "firework_rocket" -> "See a Firework";
         case "tnt" -> "See TNT";
         default -> null;
      };
      if (title == null) {
         return;
      }

      for (Villager villager : nearbyVillagers(level, entity.position(), 40.0)) {
         if (villager.isAlive() && !villager.isSleeping() && !villager.isBaby() && say(villager, title, "seen:" + path, 200L, false)) {
            return;
         }
      }
   }

   private static void onUseBlock(Player player, Level level, net.minecraft.world.InteractionHand hand, BlockPos pos) {
      BlockState clicked = level.getBlockState(pos);
      String blockPath = BuiltInRegistries.BLOCK.getKey(clicked.getBlock()).getPath();
      if (blockPath.contains("bell") && !blockPath.contains("bell_")) {
         pendingBellTick = ticks + 12L;
         return;
      }

      Villager witness = nearestWitness(player);
      if (witness == null) {
         return;
      }

      String useTitle = useTitle(blockPath);
      if (useTitle != null && !player.isShiftKeyDown()) {
         say(witness, useTitle, "use:" + useTitle, 600L, false);
         return;
      }

      ItemStack held = player.getItemInHand(hand);
      if (!held.isEmpty() && held.getItem() instanceof BlockItem) {
         String itemPath = itemPath(held);
         for (String title : placeTitles(itemPath)) {
            if (say(witness, title, "place:" + title, 900L, false)) {
               return;
            }
         }
      }
   }

   private static void onUseEntity(Player player, net.minecraft.world.InteractionHand hand, Entity target) {
      ItemStack held = player.getItemInHand(hand);
      String heldPath = itemPath(held);
      String targetPath = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
      if (target instanceof Villager || target instanceof WanderingTrader) {
         interactedId = target.getUUID();
         lastInteractedId = target.getUUID();
         interactedTick = ticks;
      }

      if (target instanceof Villager villager) {
         if (heldPath.equals("name_tag")) {
            say(villager, villager.isBaby() ? "Name a Baby Villager" : "Name a Villager", "name", 200L, false);
         } else if (villager.isBaby()) {
            String title = switch (heldPath) {
               case "bread" -> "Give a Baby Bread";
               case "carrot" -> "Give a Baby Carrots";
               case "potato" -> "Give a Baby Potatoes";
               case "beetroot" -> "Give a Baby Beetroot";
               default -> null;
            };
            if (title != null) {
               say(villager, title, "babyfood", 200L, false);
            }
         }
      } else if (targetPath.equals("sheep") && heldPath.equals("shears")) {
         Villager witness = nearestWitness(player);
         if (witness != null) {
            say(witness, "Shear a Sheep", "shear", 600L, false);
         }
      } else if (heldPath.equals("lead")) {
         Villager witness = nearestWitness(player);
         if (witness != null) {
            say(witness, "Use a Lead", "lead", 600L, false);
         }
      }
   }

   // --------------------------------------------------------------- trading

   private static void trackPendingInteraction(Minecraft minecraft, ClientLevel level, LocalPlayer player) {
      if (interactedId == null || ticks - interactedTick < 10L) {
         return;
      }

      UUID id = interactedId;
      interactedId = null;
      if (tradingOpen || player.containerMenu instanceof MerchantMenu || !player.getMainHandItem().isEmpty()) {
         return;
      }

      for (Villager villager : nearbyVillagers(level, player.position(), 8.0)) {
         if (!villager.getUUID().equals(id)) {
            continue;
         }

         if (villager.isBaby()) {
            say(villager, "Try to Trade with a Baby Villager", "trybaby", 400L, false);
         } else {
            String profession = profession(villager);
            if (profession.equals("nitwit")) {
               say(villager, "Try to Trade with a Nitwit", "trynitwit", 400L, false);
            } else if (profession.equals("none")) {
               say(villager, "Try to Trade with an Unemployed Villager", "tryunemployed", 400L, false);
            }
         }

         return;
      }
   }

   private static void trackTrading(Minecraft minecraft, ClientLevel level, LocalPlayer player) {
      boolean open = player.containerMenu instanceof MerchantMenu
      if (open && !tradingOpen) {
         tradingOpen = true;
         boughtAny = false;
         inventoryHash = inventoryHash(player);
         LivingEntity trader = findTrader(level, player);
         tradingSpeaker = trader == null ? null : trader.getUUID();
         if (trader instanceof WanderingTrader) {
            say(trader, "Open Trade Window", "tradeopen", 200L, false);
         } else if (trader != null) {
            say(trader, "Start Trading with Neutral Reputation", "tradeopen", 200L, false);
         }
      } else if (open) {
         long hash = inventoryHash(player);
         if (hash != inventoryHash) {
            inventoryHash = hash;
            boughtAny = true;
            LivingEntity trader = tradingSpeaker == null ? null : entityById(level, tradingSpeaker);
            if (trader != null) {
               say(trader, "Complete a Trade", "tradedone", 120L, false);
            }
         }
      } else if (tradingOpen) {
         tradingOpen = false;
         LivingEntity trader = tradingSpeaker == null ? null : entityById(level, tradingSpeaker);
         tradingSpeaker = null;
         if (trader instanceof WanderingTrader) {
            say(trader, boughtAny ? "Close Trades After Buying" : "Leave Without Trading", "tradeclose", 200L, false);
         } else if (trader != null) {
            say(trader, boughtAny ? "Close Trading After Buying" : "Close Trading Without Buying", "tradeclose", 200L, false);
         }
      }
   }

   private static LivingEntity findTrader(ClientLevel level, LocalPlayer player) {
      if (lastInteractedId != null && ticks - interactedTick < 200L) {
         LivingEntity recent = entityById(level, lastInteractedId);
         if (recent != null) {
            return recent;
         }
      }

      LivingEntity best = null;
      double bestDistance = 64.0;
      AABB area = player.getBoundingBox().inflate(8.0);
      for (Villager villager : level.getEntitiesOfClass(Villager.class, area, candidate -> true)) {
         double distance = villager.distanceToSqr(player);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = villager;
         }
      }

      for (WanderingTrader trader : level.getEntitiesOfClass(WanderingTrader.class, area, candidate -> true)) {
         double distance = trader.distanceToSqr(player);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = trader;
         }
      }

      return best;
   }

   private static long inventoryHash(LocalPlayer player) {
      long hash = 0L;
      for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
         hash += stackHash(player.getInventory().getItem(slot));
      }

      hash += stackHash(player.containerMenu.getCarried());
      for (int slot = 0; slot < 2 && slot < player.containerMenu.slots.size(); slot++) {
         hash += stackHash(player.containerMenu.getSlot(slot).getItem());
      }

      return hash;
   }

   private static long stackHash(ItemStack stack) {
      if (stack.isEmpty()) {
         return 0L;
      }

      return (long)itemPath(stack).hashCode() * stack.getCount();
   }

   // --------------------------------------------------------------- ambient

   private static void ambient(ClientLevel level, LocalPlayer player, List<Villager> awake, List<WanderingTrader> traders) {
      if (!VillagerNewsSettings.dialogueEnabled()) {
         return;
      }

      long ambientGap = VillagerNewsSettings.scaleCooldown(900L);
      if (ticks - lastAmbient < ambientGap || ThreadLocalRandom.current().nextInt(4) != 0) {
         return;
      }

      // Wandering trader gets a few lines of his own.
      for (WanderingTrader trader : traders) {
         if (trader.isAlive() && trader.distanceTo(player) < 14.0F && trader.hasLineOfSight(player)) {
            String title = level.isRainingAt(trader.blockPosition()) ? "Caught in the Rain" : (trader.distanceTo(player) < 8.0F ? "Sees the Player" : "Wandering");
            if (say(trader, title, "trader:" + title, 6000L, false)) {
               lastAmbient = ticks;
               return;
            }
         }
      }

      Villager speaker = null;
      for (Villager villager : awake) {
         if (villager.distanceTo(player) <= 14.0F && !villager.isBaby() && villager.hasLineOfSight(player)) {
            speaker = villager;
            break;
         }
      }

      if (speaker == null) {
         return;
      }

      List<Candidate> candidates = new ArrayList<>();
      String playerTitle = playerContext(player);
      if (playerTitle != null) {
         candidates.add(new Candidate(playerTitle, null, "ctx:" + playerTitle, 4800L));
      }

      String environment = environmentContext(level, speaker);
      if (environment != null) {
         candidates.add(new Candidate(environment, null, "env:" + environment, 6000L));
      }

      candidates.add(new Candidate(timeContext(level), null, "time:" + timeContext(level), 12000L));
      String ambientId = ambientDialogue(speaker);
      if (ambientId != null) {
         candidates.add(new Candidate(null, ambientId, "ambient:" + ambientId, 9000L));
      }

      candidates.addAll(mobCandidates(level, speaker));

      int crowd = 0;
      for (Villager villager : awake) {
         if (villager.distanceTo(player) < 6.0F) {
            crowd++;
         }
      }

      if (crowd >= 8) {
         candidates.add(new Candidate("Crowd Too Many Villagers Together", null, "crowd", 6000L));
      } else if (crowd >= 2) {
         candidates.add(new Candidate("Villagers Gossip", null, "gossip", 7200L));
      }

      Collections.shuffle(candidates);
      for (Candidate candidate : candidates) {
         boolean spoke = candidate.id() != null
            ? sayId(speaker, candidate.id(), candidate.key(), candidate.cooldown(), false)
            : say(speaker, candidate.title(), candidate.key(), candidate.cooldown(), false);
         if (spoke) {
            lastAmbient = ticks;
            return;
         }
      }
   }

   private static List<Candidate> mobCandidates(ClientLevel level, Villager speaker) {
      List<Candidate> result = new ArrayList<>();
      AABB area = speaker.getBoundingBox().inflate(8.0);
      List<LivingEntity> entities = level.getEntitiesOfClass(
         LivingEntity.class,
         area,
         candidate -> candidate != speaker && candidate.isAlive() && !(candidate instanceof Villager) && !(candidate instanceof Player)
            && !(candidate instanceof WanderingTrader)
      );
      for (LivingEntity entity : entities) {
         if (!speaker.hasLineOfSight(entity)) {
            continue;
         }

         String path = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
         String name = switch (path) {
            case "zombified_piglin" -> "Zombie Piglin";
            case "cave_spider" -> "Spider";
            case "mooshroom" -> "Cow";
            case "trader_llama" -> "Llama";
            default -> words(path);
         };
         if (path.equals("iron_golem")) {
            result.add(new Candidate("See an Iron Golem", null, "mob:golem", 4800L));
         }

         String title = entity.isBaby() ? "Baby " + name : name;
         result.add(new Candidate(title, null, "mob:" + title, 4800L));
      }

      return result;
   }

   private static String environmentContext(ClientLevel level, Villager villager) {
      Entity vehicle = villager.getVehicle();
      if (vehicle != null) {
         String vehiclePath = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
         if (vehiclePath.contains("minecart")) {
            return vehicle.getDeltaMovement().horizontalDistanceSqr() > 0.001 ? "Ride in a Moving Minecart" : "Sit in a Minecart";
         }

         if (vehiclePath.contains("boat")) {
            return vehicle.isInWater() ? "Boat on Water" : "Boat on Land";
         }
      }

      if (level.dimension() == Level.NETHER) {
         return "Wander in the Nether";
      } else if (level.dimension() == Level.END) {
         return "Wander in the End";
      } else if (level.dimension() != Level.OVERWORLD) {
         return "Wander in Another Dimension";
      } else if (level.isRainingAt(villager.blockPosition())) {
         return "Caught in the Rain";
      } else if (villager.isInWater()) {
         return "Stand in Shallow Water";
      } else {
         String biome = level.getBiome(villager.blockPosition()).unwrapKey().map(key -> key.identifier().getPath()).orElse("");
         if (biome.contains("desert") || biome.contains("badlands") || biome.contains("savanna")) {
            return "Wander Somewhere Hot";
         } else if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice") || biome.contains("cold")) {
            return "Wander Somewhere Cold";
         } else {
            BlockState below = level.getBlockState(villager.blockPosition().below());
            if (below.is(Blocks.ICE) || below.is(Blocks.PACKED_ICE) || below.is(Blocks.BLUE_ICE)) {
               return "Stand on Ice";
            } else if (below.is(Blocks.SNOW_BLOCK) || below.is(Blocks.POWDER_SNOW)) {
               return "Stand on Snow";
            } else if (below.is(Blocks.MAGMA_BLOCK)) {
               return "Stand on Magma";
            } else if (villager.getY() < level.getSeaLevel() - 30) {
               return "Wander Deep Underground";
            } else {
               return villager.getY() > level.getSeaLevel() + 75 ? "Wander High Above the Ground" : null;
            }
         }
      }
   }

   private static String timeContext(ClientLevel level) {
      long time = Math.floorMod(level.getOverworldClockTime(), 24000L)
      if (time < 4000L || time >= 23000L) {
         return "Morning";
      } else if (time < 10000L) {
         return "Afternoon";
      } else {
         return time < 13000L ? "Evening" : "Night";
      }
   }

   private static String ambientDialogue(Villager villager) {
      String profession = profession(villager);
      if (profession.equals("nitwit")) {
         return "uookqp";
      } else if (profession.equals("none")) {
         return "gbxzxv";
      }

      LocalDate date = LocalDate.now();
      List<String> choices = new ArrayList<>();
      if (date.getMonthValue() == 10) {
         choices.add("mltyge");
      }

      if (date.getMonthValue() == 12) {
         choices.add("tkkegl");
      }

      if (date.getMonthValue() == 1 && date.getDayOfMonth() == 1) {
         choices.add("uyqiwv");
      }

      if (date.getMonthValue() == 2 && date.getDayOfMonth() == 14) {
         choices.add("fabiyx");
      }

      if (date.getMonthValue() == 4 && date.getDayOfMonth() == 1) {
         choices.add("obitls");
      }

      if (date.getMonthValue() == 5 && date.getDayOfMonth() == 17) {
         choices.add("iriuqa");
      }

      if (date.getMonthValue() == 10 && date.getDayOfMonth() == 31) {
         choices.add("adhxce");
      }

      if (date.getMonthValue() == 12 && date.getDayOfMonth() == 24) {
         choices.add("zoqxvy");
      }

      if (date.getMonthValue() == 12 && date.getDayOfMonth() == 25) {
         choices.add("rclyrl");
      }

      if (date.getMonthValue() == 12 && date.getDayOfMonth() == 31) {
         choices.add("xljknt");
      }

      if (date.getDayOfMonth() == 13 && date.getDayOfWeek() == DayOfWeek.FRIDAY) {
         choices.add("qfcwvz");
      }

      if (LocalDateTime.now().getMinute() == 0) {
         choices.add("jqgkhy");
      }

      if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
         choices.add("bkyidl");
         choices.add("ckngck");
      }

      boolean first = ticks / 3000L % 2L == 0L;
      choices.add(switch (date.getDayOfWeek()) {
         case MONDAY -> first ? "gkvlqc" : "jpucos";
         case TUESDAY -> first ? "dkpihl" : "lgeeem";
         case WEDNESDAY -> first ? "gwakiz" : "qiqiez";
         case THURSDAY -> first ? "zglkgp" : "caiyte";
         case FRIDAY -> first ? "ypyumu" : "cxtvsx";
         case SATURDAY -> first ? "ildosa" : "lfhnxz";
         case SUNDAY -> first ? "uzvatl" : "zckxrc";
      });
      if (villager.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
         choices.add("lvigit");
      }

      return choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
   }

   private static String playerContext(LocalPlayer player) {
      if (player.isFallFlying()) {
         return "Glide with Elytra";
      } else if (player.isCreative() && player.getAbilities().flying) {
         return "Fly in Creative Mode";
      } else if (player.isShiftKeyDown() && player.getDeltaMovement().horizontalDistanceSqr() > 1.0E-4) {
         return "Crouch-Walk";
      } else if (player.getHealth() <= player.getMaxHealth() * 0.3F) {
         return "Low Health";
      } else if (player.hasEffect(MobEffects.INVISIBILITY)) {
         return "Invisibility";
      } else if (player.hasEffect(MobEffects.DARKNESS)) {
         return "Darkness";
      } else if (player.hasEffect(MobEffects.NIGHT_VISION)) {
         return "Night Vision";
      } else if (player.hasEffect(MobEffects.WATER_BREATHING)) {
         return "Water Breathing";
      } else if (player.hasEffect(MobEffects.SPEED)) {
         return "Swiftness";
      } else if (player.hasEffect(MobEffects.SLOWNESS)) {
         return "Slowness";
      } else if (player.hasEffect(MobEffects.STRENGTH)) {
         return "Strength";
      } else if (player.hasEffect(MobEffects.WEAKNESS)) {
         return "Weakness";
      } else if (player.hasEffect(MobEffects.HUNGER)) {
         return "Hunger";
      } else if (player.hasEffect(MobEffects.NAUSEA)) {
         return "Nausea";
      } else if (player.hasEffect(MobEffects.BAD_OMEN) || player.hasEffect(MobEffects.RAID_OMEN)) {
         return "Bad Omen";
      } else if (player.hasEffect(MobEffects.OOZING)) {
         return "Oozing";
      } else if (player.getActiveEffects().size() >= 2) {
         return "Multiple Status Effects";
      } else {
         ItemStack held = player.getMainHandItem();
         if (held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage() * 0.85F) {
            return "Hold a Nearly Broken Item";
         }

         int armor = 0;
         Set<String> materials = new HashSet<>();
         for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!stack.isEmpty()) {
               armor++;
               String path = itemPath(stack);
               materials.add(path.substring(0, path.indexOf('_') > 0 ? path.indexOf('_') : path.length()));
            }
         }

         if (armor == 4 && materials.size() == 1 && materials.contains("iron")) {
            return "Wear Full Iron Armor";
         } else if (armor == 4 && materials.size() > 1) {
            return "Wear Mixed Armor";
         } else if (armor == 4 && (materials.contains("diamond") || materials.contains("netherite"))) {
            return "Wear High-Level Armor";
         } else {
            return armor > 0 ? "Wear Armor" : null;
         }
      }
   }

   // ---------------------------------------------------------------- tables

   private static String useTitle(String blockPath) {
      if (blockPath.endsWith("shulker_box")) {
         return "Use a Shulker Box";
      } else if (blockPath.endsWith("_door") && !blockPath.contains("trap")) {
         return "Use a Door";
      } else if (blockPath.endsWith("fence_gate")) {
         return "Open a Fence Gate";
      } else if (blockPath.endsWith("_button")) {
         return "Press a Button";
      } else if (blockPath.contains("cauldron")) {
         return "Use a Cauldron";
      } else if (blockPath.contains("campfire")) {
         return "Use a Campfire";
      } else if (blockPath.endsWith("anvil")) {
         return "Use an Anvil";
      }

      return switch (blockPath) {
         case "crafting_table" -> "Use a Crafting Table";
         case "furnace", "blast_furnace", "smoker" -> "Use a Furnace";
         case "enchanting_table" -> "Use an Enchanting Table";
         case "brewing_stand" -> "Use a Brewing Stand";
         case "grindstone" -> "Use a Grindstone";
         case "loom" -> "Use a Loom";
         case "stonecutter" -> "Use a Stonecutter";
         case "smithing_table" -> "Use a Smithing Table";
         case "cartography_table" -> "Use a Cartography Table";
         case "composter" -> "Use a Composter";
         case "jukebox" -> "Use a Jukebox";
         case "beacon" -> "Use a Beacon";
         case "ender_chest" -> "Use an Ender Chest";
         case "dispenser" -> "Use a Dispenser";
         case "dropper" -> "Use a Dropper";
         case "crafter" -> "Use a Crafter";
         case "chiseled_bookshelf" -> "Use a Chiseled Bookshelf";
         case "chest", "trapped_chest", "barrel" -> "Open a Chest";
         case "lever" -> "Flip a Lever";
         default -> null;
      };
   }

   private static List<String> placeTitles(String item) {
      List<String> titles = new ArrayList<>();
      if (item.endsWith("_bed")) {
         titles.add("Place a Bed");
      } else if (item.endsWith("_button")) {
         titles.add("Place a Button");
      } else if (item.endsWith("_pressure_plate")) {
         titles.add("Place a Pressure Plate");
      } else if (item.endsWith("_concrete_powder")) {
         titles.add("Place Concrete Powder");
      } else if (item.endsWith("_concrete")) {
         titles.add("Place Concrete");
      } else if (item.endsWith("glazed_terracotta")) {
         titles.add("Place Glazed Terracotta");
      } else if (item.endsWith("terracotta")) {
         titles.add("Place Terracotta");
      } else if (item.endsWith("_wool")) {
         titles.add("Place Wool");
      } else if (item.endsWith("_planks") || item.endsWith("_log") || item.endsWith("_wood")) {
         titles.add("Place Wood");
      } else if (item.endsWith("_leaves") || item.endsWith("sapling") || item.equals("short_grass") || item.equals("fern")) {
         titles.add("Place Leaves or Plants");
      } else if (item.contains("glass")) {
         titles.add("Place Glass");
      } else if (item.contains("copper")) {
         titles.add("Place a Copper Block");
      } else if (item.contains("purpur")) {
         titles.add("Place Purpur");
      } else if (item.contains("brick") && !item.contains("nether") && !item.contains("end")) {
         titles.add("Place Bricks");
      } else {
         String special = switch (item) {
            case "daylight_detector" -> "Place a Daylight Detector";
            case "detector_rail" -> "Place a Detector Rail";
            case "lightning_rod" -> "Place a Lightning Rod";
            case "melon" -> "Place a Melon";
            case "observer" -> "Place an Observer";
            case "pumpkin", "carved_pumpkin" -> "Place a Pumpkin";
            case "jack_o_lantern" -> "Place a Jack o'Lantern";
            case "redstone" -> "Place Redstone Dust";
            case "redstone_lamp" -> "Place a Redstone Lamp";
            case "repeater" -> "Place a Redstone Repeater";
            case "redstone_torch" -> "Place a Redstone Torch";
            case "sculk_sensor" -> "Place a Sculk Sensor";
            case "trapped_chest" -> "Place a Trapped Chest";
            case "chest" -> "Place a Chest";
            case "furnace" -> "Place a Furnace";
            case "tripwire_hook" -> "Place a Tripwire Hook";
            case "beacon" -> "Place a Beacon";
            case "bookshelf" -> "Place a Bookshelf";
            case "crafting_table" -> "Place a Crafting Table";
            case "jukebox" -> "Place a Jukebox";
            case "lever" -> "Place a Lever";
            case "diamond_block" -> "Place a Diamond Block";
            case "emerald_block" -> "Place an Emerald Block";
            case "iron_block" -> "Place an Iron Block";
            case "gold_block" -> "Place a Gold Block";
            case "lapis_block" -> "Place a Lapis Block";
            case "end_stone" -> "Place End Stone";
            case "ice", "packed_ice", "blue_ice" -> "Place Ice";
            case "powder_snow_bucket" -> "Place Powder Snow";
            case "snow", "snow_block" -> "Place Snow";
            case "dirt", "grass_block", "coarse_dirt", "podzol" -> "Place Dirt";
            case "light" -> "Place a Light Block";
            case "sand", "red_sand", "gravel" -> "Place a Gravity-Affected Block";
            case "netherrack", "soul_sand", "soul_soil", "nether_bricks", "basalt", "blackstone", "glowstone" -> "Place a Block from the Nether";
            case "prismarine", "sea_lantern", "dark_prismarine", "prismarine_bricks" -> "Place a Block from the Ocean";
            case "command_block", "barrier", "structure_block", "jigsaw" -> "Place a Creative-Only Block";
            case "barrel", "loom", "smithing_table", "fletching_table", "cartography_table", "brewing_stand", "grindstone", "stonecutter",
               "lectern", "composter", "blast_furnace", "smoker" -> "Place a Workstation";
            default -> null;
         };
         if (special != null) {
            titles.add(special);
         }
      }

      titles.add("Place a Block");
      return titles;
   }

   // --------------------------------------------------------------- helpers

   private static Villager nearestWitness(Player player) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.level == null) {
         return null;
      }

      for (Villager villager : nearbyVillagers(minecraft.level, player.position(), 12.0)) {
         if (villager.isAlive() && !villager.isSleeping() && !villager.isBaby() && villager.hasLineOfSight(player)) {
            return villager;
         }
      }

      return null;
   }

   private static List<Villager> nearbyVillagers(ClientLevel level, Vec3 position, double range) {
      List<Villager> result = new ArrayList<>(
         level.getEntitiesOfClass(Villager.class, AABB.ofSize(position, range * 2.0, range * 2.0, range * 2.0), candidate -> true)
      );
      result.sort((a, b) -> Double.compare(a.distanceToSqr(position), b.distanceToSqr(position)));
      return result;
   }

   private static LivingEntity entityById(ClientLevel level, UUID id) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null) {
         return null;
      }

      for (Villager villager : level.getEntitiesOfClass(Villager.class, minecraft.player.getBoundingBox().inflate(16.0), candidate -> true)) {
         if (villager.getUUID().equals(id)) {
            return villager;
         }
      }

      for (WanderingTrader trader : level.getEntitiesOfClass(WanderingTrader.class, minecraft.player.getBoundingBox().inflate(16.0), candidate -> true)) {
         if (trader.getUUID().equals(id)) {
            return trader;
         }
      }

      return null;
   }

   private static String profession(Villager villager) {
      return villager.getVillagerData().profession().unwrapKey().map(key -> key.identifier().getPath()).orElse("none");
   }

   private static String itemPath(ItemStack stack) {
      return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
   }

   private static String words(String path) {
      StringBuilder builder = new StringBuilder();
      for (String part : path.split("_")) {
         if (part.isEmpty()) {
            continue;
         }

         if (builder.length() > 0) {
            builder.append(' ');
         }

         builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
      }

      return builder.toString();
   }

   private static String kind(LivingEntity speaker) {
      if (speaker instanceof WanderingTrader) {
         return "trader";
      }

      return speaker instanceof Villager villager && villager.isBaby() ? "baby" : "adult";
   }

   private static boolean say(LivingEntity speaker, String title, String key, long cooldown, boolean force) {
      DialogueCatalog.DialogueGroup group = INDEX.get(kind(speaker) + "|" + title);
      return group != null && play(speaker, group, key, cooldown, force);
   }

   private static boolean sayId(LivingEntity speaker, String groupId, String key, long cooldown, boolean force) {
      DialogueCatalog.DialogueGroup group = DialogueCatalog.byId(groupId);
      return group != null && play(speaker, group, key, cooldown, force);
   }

   private static boolean play(LivingEntity speaker, DialogueCatalog.DialogueGroup group, String key, long cooldown, boolean force) {
      if (!VillagerNewsSettings.dialogueEnabled() || speaker == null) {
         return false;
      }

      boolean baby = speaker instanceof Villager villager && villager.isBaby();
      boolean babyGroup = BABY_DIALOGUES.contains(group.id());
      boolean villagerGroup = group.speaker().equals("villager");
      boolean traderGroup = group.speaker().equals("wandering_trader");
      boolean valid = speaker instanceof WanderingTrader ? traderGroup : speaker instanceof Villager && villagerGroup && baby == babyGroup;
      if (!valid) {
         return false;
      }

      if (!force) {
         if (!speaker.isAlive() || BUSY_UNTIL.getOrDefault(speaker.getUUID(), 0L) > ticks || ticks - lastAnyDialogue < 30L) {
            return false;
         }

         if (speaker instanceof Villager sleeper && sleeper.isSleeping() && !group.id().equals("asqzby")) {
            return false;
         }
      }

      long scaled = cooldown <= 0L ? 0L : VillagerNewsSettings.scaleCooldown(cooldown);
      Long last = COOLDOWNS.get(key);
      if (!force && last != null && ticks - last < scaled) {
         return false;
      }

      List<Integer> recent = RECENT_VARIANTS.getOrDefault(group.id(), List.of());
      DialogueCatalog.DialogueVariant variant = group.chooseVariant(VillagerNewsSettings.rareVoicelines(), Set.copyOf(recent));
      if (variant == null) {
         return false;
      }

      DialogueAnimationPayload payload = new DialogueAnimationPayload(speaker.getUUID(), group.id(), variant.index(), (int)variant.durationTicks());
      DialogueSoundState.start(payload);
      DialogueAnimationState.start(payload);
      DialogueSubtitleState.start(payload);
      COOLDOWNS.put(key, ticks);
      BUSY_UNTIL.put(speaker.getUUID(), ticks + variant.durationTicks() + 10L);
      lastAnyDialogue = ticks;
      int historySize = Math.min(8, group.variants().size() - 1);
      if (historySize > 0) {
         List<Integer> updated = new ArrayList<>(recent);
         updated.remove(Integer.valueOf(variant.index()));
         updated.add(variant.index());
         while (updated.size() > historySize) {
            updated.remove(0);
         }

         RECENT_VARIANTS.put(group.id(), updated);
      }

      return true;
   }

   private record Candidate(String title, String id, String key, long cooldown) {
   }

   private static final class VillagerState {
      private int hurtTime;
      private boolean sleeping;
      private boolean baby;
      private boolean dead;

      private VillagerState(Villager villager) {
         this.hurtTime = villager.hurtTime;
         this.sleeping = villager.isSleeping();
         this.baby = villager.isBaby();
      }
   }
}
