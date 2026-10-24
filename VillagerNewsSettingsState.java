package com.vnap.client;

import com.vnap.config.VillagerNewsSettings;

/** Thin wrapper kept so the settings screen stays unchanged. Everything is local now. */
public final class VillagerNewsSettingsState {
   private VillagerNewsSettingsState() {
   }

   public static void prepareConfigScreen() {
   }

   public static int chattiness() {
      return VillagerNewsSettings.chattiness();
   }

   public static int rareVoicelines() {
      return VillagerNewsSettings.rareVoicelines();
   }

   public static boolean spawnSpecialVillagers() {
      return false;
   }

   public static boolean canEdit() {
      return true;
   }

   public static boolean localSettings() {
      return true;
   }

   public static void setChattiness(int value) {
      VillagerNewsSettings.update(Math.floorMod(value, 4), VillagerNewsSettings.rareVoicelines(), false);
   }

   public static void setRareVoicelines(int value) {
      VillagerNewsSettings.update(VillagerNewsSettings.chattiness(), Math.floorMod(value, 3), false);
   }

   public static void setSpawnSpecialVillagers(boolean value) {
   }

   public static void reset() {
   }
}
