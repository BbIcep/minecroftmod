package com.vnap.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class HandbookScreen extends Screen {
   private static final HandbookScreen.HandbookData DATA = load();
   private static final int ROWS = 8;
   private final Screen parent;
   private final boolean settingsOnly;
   private HandbookScreen.Page page;
   private HandbookScreen.Page returnPage = HandbookScreen.Page.TRIGGERS;
   private int pageIndex;
   private int entryIndex;
   private int categoryIndex;
   private int sectionIndex;
   private String search = "";
   private HandbookScreen.Entry detail;

   public HandbookScreen() {
      this(null, HandbookScreen.Page.HOME, false);
   }

   private HandbookScreen(Screen parent, HandbookScreen.Page page, boolean settingsOnly) {
      super(Component.literal("Villager News"));
      this.parent = parent;
      this.page = page;
      this.settingsOnly = settingsOnly;
   }

   public static HandbookScreen settingsScreen(Screen parent) {
      VillagerNewsSettingsState.prepareConfigScreen();
      return new HandbookScreen(parent, HandbookScreen.Page.SETTINGS, true);
   }

   protected void init() {
      int contentWidth = Math.min(380, this.width - 32);
      int left = (this.width - contentWidth) / 2;
      this.addText(
         left, 16, contentWidth, Component.literal(this.titleForPage()).withStyle(new ChatFormatting[]{ChatFormatting.YELLOW, ChatFormatting.BOLD}), true
      );
      switch (this.page) {
         case HOME:
            this.buildHome(left, contentWidth);
            break;
         case GUIDE:
            this.buildGuide(left, contentWidth);
            break;
         case OVERVIEW:
            this.buildEntryPage(left, contentWidth, DATA.overview, HandbookScreen.Page.GUIDE);
            break;
         case SPECIALS:
            this.buildEntryPage(left, contentWidth, DATA.specialVillagers, HandbookScreen.Page.GUIDE);
            break;
         case COSMETICS:
            this.buildEntryPage(left, contentWidth, DATA.cosmetics, HandbookScreen.Page.GUIDE);
            break;
         case GENERAL:
            this.buildEntryPage(left, contentWidth, DATA.generalInformation, HandbookScreen.Page.TRIGGERS);
            break;
         case SETTINGS:
            this.buildSettings(left, contentWidth);
            break;
         case SOCIALS:
            this.buildEntryPage(left, contentWidth, DATA.socials, HandbookScreen.Page.HOME);
            break;
         case SUPPORT:
            this.buildSupport(left, contentWidth);
            break;
         case TRIGGERS:
            this.buildTriggers(left, contentWidth);
            break;
         case CATEGORY:
            this.buildCategory(left, contentWidth);
            break;
         case SECTION:
            this.buildSection(left, contentWidth);
            break;
         case DETAIL:
            this.buildDetail(left, contentWidth);
      }
   }

   private void buildHome(int left, int contentWidth) {
      this.addText(left, 48, contentWidth, Component.literal(DATA.headline), true);
      int y = 126;
      this.addMenuButton(left, y, contentWidth, "Guide", HandbookScreen.Page.GUIDE);
      this.addMenuButton(left, y + 24, contentWidth, "Settings", HandbookScreen.Page.SETTINGS);
      this.addMenuButton(left, y + 48, contentWidth, "Socials", HandbookScreen.Page.SOCIALS);
      this.addMenuButton(left, y + 72, contentWidth, "Support", HandbookScreen.Page.SUPPORT);
      this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose()).bounds(left, this.height - 30, contentWidth, 20).build());
   }

   private void buildGuide(int left, int contentWidth) {
      this.addText(left, 44, contentWidth, Component.literal(DATA.guideIntro), true);
      int y = 112;
      this.addMenuButton(left, y, contentWidth, "Overview", HandbookScreen.Page.OVERVIEW);
      this.addMenuButton(left, y + 24, contentWidth, "Special Villagers", HandbookScreen.Page.SPECIALS);
      this.addMenuButton(left, y + 48, contentWidth, "Cosmetics", HandbookScreen.Page.COSMETICS);
      this.addMenuButton(left, y + 72, contentWidth, "Triggers & Reactions", HandbookScreen.Page.TRIGGERS);
      this.addBackButton(left, contentWidth, HandbookScreen.Page.HOME);
   }

   private void buildTriggers(int left, int contentWidth) {
      EditBox field = new EditBox(this.font, left, 44, contentWidth - 62, 20, Component.literal("Search Triggers"));
      field.setValue(this.search);
      field.setMaxLength(80);
      field.setHint(Component.literal("Search Triggers"));
      this.addRenderableWidget(field);
      this.addRenderableWidget(Button.builder(Component.literal("Go"), button -> {
         this.search = field.getValue().trim();
         this.pageIndex = 0;
         this.rebuildWidgets();
      }).bounds(left + contentWidth - 58, 44, 58, 20).build());
      if (!this.search.isBlank()) {
         this.buildSearchResults(left, contentWidth);
      } else {
         this.addText(left, 70, contentWidth, Component.literal("Browse triggers and reactions by category."), true);
         this.addRenderableWidget(
            Button.builder(Component.literal("General Information"), button -> this.navigate(HandbookScreen.Page.GENERAL))
               .bounds(left, 94, contentWidth, 20)
               .build()
         );
         List<HandbookScreen.Category> categories = DATA.categories;
         int start = this.pageIndex * 8;

         for (int index = start; index < Math.min(categories.size(), start + 8); index++) {
            int selected = index;
            this.addRenderableWidget(Button.builder(Component.literal(categories.get(index).title), button -> {
               this.categoryIndex = selected;
               this.pageIndex = 0;
               this.page = HandbookScreen.Page.CATEGORY;
               this.rebuildWidgets();
            }).bounds(left, 118 + (index - start) * 22, contentWidth, 20).build());
         }

         this.addPager(left, contentWidth, categories.size(), HandbookScreen.Page.GUIDE);
      }
   }

   private void buildSearchResults(int left, int contentWidth) {
      String query = this.search.toLowerCase(Locale.ROOT);
      List<HandbookScreen.Entry> results = DATA.searchable
         .stream()
         .filter(entryx -> clean(entryx.title).toLowerCase(Locale.ROOT).contains(query) || clean(entryx.body).toLowerCase(Locale.ROOT).contains(query))
         .sorted(Comparator.comparing(HandbookScreen.Entry::title, String.CASE_INSENSITIVE_ORDER))
         .toList();
      this.addText(left, 70, contentWidth, Component.literal(results.size() + " matching triggers"), true);
      int start = this.pageIndex * 8;

      for (int index = start; index < Math.min(results.size(), start + 8); index++) {
         HandbookScreen.Entry entry = results.get(index);
         this.addRenderableWidget(
            Button.builder(Component.literal(clean(entry.title)), button -> this.openDetail(entry, HandbookScreen.Page.TRIGGERS))
               .bounds(left, 94 + (index - start) * 22, contentWidth, 20)
               .build()
         );
      }

      if (results.isEmpty()) {
         this.addText(left, 110, contentWidth, Component.literal("No triggers match your search.").withStyle(ChatFormatting.RED), true);
      }

      this.addPager(left, contentWidth, results.size(), HandbookScreen.Page.GUIDE);
   }

   private void buildCategory(int left, int contentWidth) {
      HandbookScreen.Category category = DATA.categories.get(this.categoryIndex);
      this.addText(left, 44, contentWidth, Component.literal("Choose a section below to browse its triggers."), true);
      int start = this.pageIndex * 8;

      for (int index = start; index < Math.min(category.sections.size(), start + 8); index++) {
         int selected = index;
         this.addRenderableWidget(Button.builder(Component.literal(category.sections.get(index).title), button -> {
            this.sectionIndex = selected;
            this.pageIndex = 0;
            this.page = HandbookScreen.Page.SECTION;
            this.rebuildWidgets();
         }).bounds(left, 72 + (index - start) * 22, contentWidth, 20).build());
      }

      this.addPager(left, contentWidth, category.sections.size(), HandbookScreen.Page.TRIGGERS);
   }

   private void buildSection(int left, int contentWidth) {
      HandbookScreen.Section section = DATA.categories.get(this.categoryIndex).sections.get(this.sectionIndex);
      List<HandbookScreen.Entry> groups = new ArrayList<>();

      for (String id : section.groups) {
         HandbookScreen.Entry entry = DATA.contexts.get(id);
         if (entry != null && !entry.title.isBlank()) {
            groups.add(entry);
         }
      }

      groups.addAll(section.entries);
      this.addText(left, 44, contentWidth, Component.literal("Choose a trigger to see how to activate it and what reaction it causes."), true);
      int start = this.pageIndex * 8;

      for (int index = start; index < Math.min(groups.size(), start + 8); index++) {
         HandbookScreen.Entry entry = groups.get(index);
         this.addRenderableWidget(
            Button.builder(Component.literal(clean(entry.title)), button -> this.openDetail(entry, HandbookScreen.Page.SECTION))
               .bounds(left, 76 + (index - start) * 22, contentWidth, 20)
               .build()
         );
      }

      if (groups.isEmpty()) {
         this.addText(left, 100, contentWidth, Component.literal("This section is covered by the general guide entries."), true);
      }

      this.addPager(left, contentWidth, groups.size(), HandbookScreen.Page.CATEGORY);
   }

   private void buildDetail(int left, int contentWidth) {
      if (this.detail != null) {
         this.addText(left, 52, contentWidth, Component.literal(clean(this.detail.body)), false);
      }

      this.addBackButton(left, contentWidth, this.returnPage);
   }

   private void buildEntryPage(int left, int contentWidth, List<HandbookScreen.Entry> entries, HandbookScreen.Page back) {
      HandbookScreen.Entry entry = entries.get(Math.max(0, Math.min(this.entryIndex, entries.size() - 1)));
      this.addText(
         left, 48, contentWidth, Component.literal(clean(entry.title)).withStyle(new ChatFormatting[]{ChatFormatting.YELLOW, ChatFormatting.BOLD}), true
      );
      this.addText(left, 74, contentWidth, Component.literal(clean(entry.body)), false);
      int half = (contentWidth - 6) / 2;
      Button previous = Button.builder(Component.literal("Previous"), button -> {
         this.entryIndex--;
         this.rebuildWidgets();
      }).bounds(left, this.height - 54, half, 20).build();
      previous.active = this.entryIndex > 0;
      this.addRenderableWidget(previous);
      Button next = Button.builder(Component.literal("Next"), button -> {
         this.entryIndex++;
         this.rebuildWidgets();
      }).bounds(left + half + 6, this.height - 54, half, 20).build();
      next.active = this.entryIndex + 1 < entries.size();
      this.addRenderableWidget(next);
      this.addBackButton(left, contentWidth, back);
   }

   private void buildSupport(int left, int contentWidth) {
      this.addText(left, 52, contentWidth, Component.literal(DATA.support), false);
      this.addBackButton(left, contentWidth, HandbookScreen.Page.HOME);
   }

   private void buildSettings(int left, int contentWidth) {
      boolean canEdit = true;
      this.addText(left, 42, contentWidth, Component.literal("Dialogue settings are saved on this computer."), true);
      int labelWidth = Math.min(166, contentWidth / 2);
      int buttonLeft = left + labelWidth;
      int buttonWidth = contentWidth - labelWidth;
      int y = 66;
      this.addText(left, y + 6, labelWidth - 6, Component.literal("Villager News Subtitles"), false);
      this.addRenderableWidget(Button.builder(Component.literal(toggleLabel(VillagerNewsClientSettings.showSubtitles())), button -> {
         boolean enabled = !VillagerNewsClientSettings.showSubtitles();
         VillagerNewsClientSettings.setShowSubtitles(enabled);
         button.setMessage(Component.literal(toggleLabel(enabled)));
      }).bounds(buttonLeft, y, buttonWidth, 20).build());
      y += 26;
      this.addText(left, y + 6, labelWidth - 6, Component.literal("Villager Chattiness"), false);
      Button chattiness = Button.builder(Component.literal(chattinessLabel(VillagerNewsSettingsState.chattiness())), button -> {
         VillagerNewsSettingsState.setChattiness(VillagerNewsSettingsState.chattiness() + 1);
         button.setMessage(Component.literal(chattinessLabel(VillagerNewsSettingsState.chattiness())));
      }).bounds(buttonLeft, y, buttonWidth, 20).build();
      chattiness.active = canEdit;
      this.addRenderableWidget(chattiness);
      y += 26;
      this.addText(left, y + 6, labelWidth - 6, Component.literal("Rare Voicelines"), false);
      Button rareVoicelines = Button.builder(Component.literal(rareLabel(VillagerNewsSettingsState.rareVoicelines())), button -> {
         VillagerNewsSettingsState.setRareVoicelines(VillagerNewsSettingsState.rareVoicelines() + 1);
         button.setMessage(Component.literal(rareLabel(VillagerNewsSettingsState.rareVoicelines())));
      }).bounds(buttonLeft, y, buttonWidth, 20).build();
      rareVoicelines.active = canEdit;
      this.addRenderableWidget(rareVoicelines);
      y += 26;
      this.addText(left, y + 6, labelWidth - 6, Component.literal("Villager Style"), false);
      Button style = Button.builder(Component.literal("Villager News"), button -> {}).bounds(buttonLeft, y, buttonWidth, 20).build();
      style.active = false;
      this.addRenderableWidget(style);
      if (this.settingsOnly) {
         this.addRenderableWidget(Button.builder(Component.literal("Done"), button -> this.onClose()).bounds(left, this.height - 30, contentWidth, 20).build());
      } else {
         this.addBackButton(left, contentWidth, HandbookScreen.Page.HOME);
      }
   }

   private static String toggleLabel(boolean enabled) {
      return enabled ? "On" : "Off";
   }

   private static String chattinessLabel(int value) {
      return switch (value) {
         case 0 -> "Muted";
         case 1 -> "Shy";
         default -> "Chatty";
         case 3 -> "Super Chatty";
      };
   }

   private static String rareLabel(int value) {
      return switch (value) {
         case 0 -> "Never";
         case 2 -> "Often";
         default -> "Default";
      };
   }

   private void addPager(int left, int contentWidth, int count, HandbookScreen.Page back) {
      int pages = Math.max(1, (count + 8 - 1) / 8);
      int third = (contentWidth - 12) / 3;
      Button previous = Button.builder(Component.literal("Previous"), button -> {
         this.pageIndex--;
         this.rebuildWidgets();
      }).bounds(left, this.height - 30, third, 20).build();
      previous.active = this.pageIndex > 0;
      this.addRenderableWidget(previous);
      this.addRenderableWidget(
         Button.builder(Component.literal("Back"), button -> this.navigate(back)).bounds(left + third + 6, this.height - 30, third, 20).build()
      );
      Button next = Button.builder(Component.literal("Next"), button -> {
         this.pageIndex++;
         this.rebuildWidgets();
      }).bounds(left + (third + 6) * 2, this.height - 30, third, 20).build();
      next.active = this.pageIndex + 1 < pages;
      this.addRenderableWidget(next);
   }

   private void addMenuButton(int left, int y, int contentWidth, String label, HandbookScreen.Page destination) {
      this.addRenderableWidget(Button.builder(Component.literal(label), button -> this.navigate(destination)).bounds(left, y, contentWidth, 20).build());
   }

   private void addBackButton(int left, int contentWidth, HandbookScreen.Page destination) {
      this.addRenderableWidget(
         Button.builder(Component.literal("Back"), button -> this.navigate(destination)).bounds(left, this.height - 30, contentWidth, 20).build()
      );
   }

   private void navigate(HandbookScreen.Page destination) {
      this.page = destination;
      this.pageIndex = 0;
      this.entryIndex = 0;
      if (destination != HandbookScreen.Page.TRIGGERS) {
         this.search = "";
      }

      this.rebuildWidgets();
   }

   private void openDetail(HandbookScreen.Entry entry, HandbookScreen.Page back) {
      this.detail = entry;
      this.returnPage = back;
      this.page = HandbookScreen.Page.DETAIL;
      this.rebuildWidgets();
   }

   private MultiLineTextWidget addText(int x, int y, int textWidth, Component text, boolean centered) {
      MultiLineTextWidget widget = new MultiLineTextWidget(x, y, text, this.font).setMaxWidth(textWidth).setCentered(centered);
      this.addRenderableWidget(widget);
      return widget;
   }

   private String titleForPage() {
      return switch (this.page) {
         case HOME -> "Villager News";
         case GUIDE -> "Guide";
         case OVERVIEW -> "Overview";
         case SPECIALS -> "Special Villagers";
         case COSMETICS -> "Cosmetics";
         case GENERAL -> "General Information";
         case SETTINGS -> "Settings";
         case SOCIALS -> "Socials";
         case SUPPORT -> "Support";
         case TRIGGERS -> "Triggers & Reactions";
         case CATEGORY -> DATA.categories.get(this.categoryIndex).title;
         case SECTION -> DATA.categories.get(this.categoryIndex).sections.get(this.sectionIndex).title;
         case DETAIL -> this.detail == null ? "Trigger" : clean(this.detail.title);
      };
   }

   public boolean isPauseScreen() {
      return false;
   }

   public void onClose() {
      this.minecraft.setScreenAndShow(this.parent);
   }

   private static String clean(String value) {
      return value.replace("Â", "").replaceAll("§[0-9a-fk-or]", "");
   }

   private static HandbookScreen.HandbookData load() {
      String path = "/assets/villager-news-addon-port/handbook.json";

      try {
         HandbookScreen.HandbookData var23;
         try (InputStream stream = HandbookScreen.class.getResourceAsStream(path)) {
            if (stream == null) {
               throw new IOException("Missing " + path);
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            List<HandbookScreen.Category> categories = new ArrayList<>();
            Map<String, HandbookScreen.Entry> contexts = new LinkedHashMap<>();

            for (Map.Entry<String, JsonElement> context : root.getAsJsonObject("contexts").entrySet()) {
               JsonObject value = context.getValue().getAsJsonObject();
               String title = value.get("browseTitle").getAsString();
               if (title.isBlank()) {
                  title = value.get("title").getAsString();
               }

               contexts.put(context.getKey(), new HandbookScreen.Entry(title, value.get("body").getAsString()));
            }

            List<HandbookScreen.Entry> searchable = new ArrayList<>();

            for (JsonElement categoryElement : root.getAsJsonArray("categories")) {
               JsonObject category = categoryElement.getAsJsonObject();
               List<HandbookScreen.Section> sections = new ArrayList<>();

               for (JsonElement sectionElement : category.getAsJsonArray("sections")) {
                  JsonObject section = sectionElement.getAsJsonObject();
                  List<String> groups = new ArrayList<>();

                  for (JsonElement group : section.getAsJsonArray("groups")) {
                     groups.add(group.getAsString());
                  }

                  List<HandbookScreen.Entry> sectionEntries = entries(section.getAsJsonArray("entries"));

                  for (String group : groups) {
                     HandbookScreen.Entry entry = contexts.get(group);
                     if (entry != null && !entry.title.isBlank()) {
                        searchable.add(entry);
                     }
                  }

                  searchable.addAll(sectionEntries);
                  sections.add(new HandbookScreen.Section(section.get("title").getAsString(), List.copyOf(groups), sectionEntries));
               }

               categories.add(new HandbookScreen.Category(category.get("title").getAsString(), List.copyOf(sections)));
            }

            var23 = new HandbookScreen.HandbookData(
               root.get("headline").getAsString(),
               root.get("guideIntro").getAsString(),
               entries(root.getAsJsonArray("overview")),
               entries(root.getAsJsonArray("specialVillagers")),
               entries(root.getAsJsonArray("cosmetics")),
               entries(root.getAsJsonArray("generalInformation")),
               entries(root.getAsJsonArray("socials")),
               entries(root.getAsJsonArray("settings")),
               root.get("support").getAsString(),
               List.copyOf(categories),
               Map.copyOf(contexts),
               List.copyOf(searchable)
            );
         }

         return var23;
      } catch (RuntimeException | IOException var20) {
         throw new IllegalStateException("Could not load the Villager News handbook", var20);
      }
   }

   private static List<HandbookScreen.Entry> entries(JsonArray array) {
      List<HandbookScreen.Entry> result = new ArrayList<>();

      for (JsonElement element : array) {
         JsonObject entry = element.getAsJsonObject();
         result.add(new HandbookScreen.Entry(entry.get("title").getAsString(), entry.get("body").getAsString()));
      }

      return List.copyOf(result);
   }

   private record Category(String title, List<HandbookScreen.Section> sections) {
   }

   private record Entry(String title, String body) {
   }

   private record HandbookData(
      String headline,
      String guideIntro,
      List<HandbookScreen.Entry> overview,
      List<HandbookScreen.Entry> specialVillagers,
      List<HandbookScreen.Entry> cosmetics,
      List<HandbookScreen.Entry> generalInformation,
      List<HandbookScreen.Entry> socials,
      List<HandbookScreen.Entry> settings,
      String support,
      List<HandbookScreen.Category> categories,
      Map<String, HandbookScreen.Entry> contexts,
      List<HandbookScreen.Entry> searchable
   ) {
   }

   private static enum Page {
      HOME,
      GUIDE,
      OVERVIEW,
      SPECIALS,
      COSMETICS,
      GENERAL,
      SETTINGS,
      SOCIALS,
      SUPPORT,
      TRIGGERS,
      CATEGORY,
      SECTION,
      DETAIL;
   }

   private record Section(String title, List<String> groups, List<HandbookScreen.Entry> entries) {
   }
}
