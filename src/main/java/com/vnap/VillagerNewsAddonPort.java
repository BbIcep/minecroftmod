package com.vnap;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants. The mod is client-only; there is no server entrypoint any more. */
public final class VillagerNewsAddonPort {
   public static final String MOD_ID = "villager-news-addon-port";
   public static final Logger LOGGER = LoggerFactory.getLogger("villager-news-addon-port");

   private VillagerNewsAddonPort() {
   }

   public static Identifier id(String path) {
      return Identifier.fromNamespaceAndPath("villager-news-addon-port", path);
   }
}
