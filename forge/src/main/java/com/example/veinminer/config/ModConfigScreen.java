package com.example.veinminer.config;

import com.example.veinminer.feature.EnchantBookFeature;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class ModConfigScreen {

    private static Component t(String key) {
        return Component.translatable(key);
    }

    public static Screen create(Screen parent) {
        ModConfig cfg = ModConfig.get();
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(t("config.survivalassist.title"));

        ConfigEntryBuilder entryBuilder = builder.entryBuilder();

        ConfigCategory general = builder.getOrCreateCategory(t("config.survivalassist.category.general"));

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableHealthDisplay"), cfg.enableHealthDisplay)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableHealthDisplay = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableVeinMiner"), cfg.enableVeinMiner)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableVeinMiner = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableNearestPlayer"), cfg.enableNearestPlayer)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableNearestPlayer = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableAutoReplant"), cfg.enableAutoReplant)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableAutoReplant = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableEggThrower"), cfg.enableEggThrower)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableEggThrower = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableMatureOnlyHarvest"), cfg.enableMatureOnlyHarvest)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableMatureOnlyHarvest = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableGamma"), cfg.enableGamma)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableGamma = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableAutoSprint"), cfg.enableAutoSprint)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableAutoSprint = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableAutoFish"), cfg.enableAutoFish)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableAutoFish = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableEnchantBook"), cfg.enableEnchantBook)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableEnchantBook = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableAutoMine"), cfg.enableAutoMine)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableAutoMine = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableXRay"), cfg.enableXRay)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableXRay = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableAutoExit"), cfg.enableAutoExit)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableAutoExit = v)
                .build());

        general.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.enableInventorySort"), cfg.enableInventorySort)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.enableInventorySort = v)
                .build());

        ConfigCategory veinMiner = builder.getOrCreateCategory(t("config.survivalassist.category.veinminer"));

        veinMiner.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.veinMinerMaxBlocks"), cfg.veinMinerMaxBlocks, 1, 1024)
                .setDefaultValue(64)
                .setTooltip(t("config.survivalassist.veinMinerMaxBlocks.tooltip"))
                .setSaveConsumer(v -> cfg.veinMinerMaxBlocks = v)
                .build());

        ConfigCategory health = builder.getOrCreateCategory(t("config.survivalassist.category.health"));

        health.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.healthShowNumbers"), cfg.healthShowNumbers)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.healthShowNumbers = v)
                .build());

        health.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.healthBarWidth"), cfg.healthBarWidth, 12, 60)
                .setDefaultValue(28)
                .setTooltip(t("config.survivalassist.healthBarWidth.tooltip"))
                .setSaveConsumer(v -> cfg.healthBarWidth = v)
                .build());

        health.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.healthDisplayRange"), cfg.healthDisplayRange, 8, 128)
                .setDefaultValue(64)
                .setTooltip(t("config.survivalassist.healthDisplayRange.tooltip"))
                .setSaveConsumer(v -> cfg.healthDisplayRange = v)
                .build());

        ConfigCategory nearest = builder.getOrCreateCategory(t("config.survivalassist.category.nearest"));

        nearest.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.distanceShowName"), cfg.distanceShowName)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.distanceShowName = v)
                .build());

        ConfigCategory autoReplant = builder.getOrCreateCategory(t("config.survivalassist.category.autoreplant"));

        autoReplant.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.autoReplantDelay"), cfg.autoReplantDelay, 0, 20)
                .setDefaultValue(3)
                .setTooltip(t("config.survivalassist.autoReplantDelay.tooltip"))
                .setSaveConsumer(v -> cfg.autoReplantDelay = v)
                .build());

        ConfigCategory egg = builder.getOrCreateCategory(t("config.survivalassist.category.egg"));

        egg.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.eggThrowInterval"), cfg.eggThrowInterval, 0, 20)
                .setDefaultValue(1)
                .setTooltip(t("config.survivalassist.eggThrowInterval.tooltip"))
                .setSaveConsumer(v -> cfg.eggThrowInterval = v)
                .build());

        ConfigCategory gamma = builder.getOrCreateCategory(t("config.survivalassist.category.gamma"));

        gamma.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.gamma"), cfg.gamma, 0, 1500)
                .setDefaultValue(50)
                .setTooltip(t("config.survivalassist.gamma.tooltip"))
                .setSaveConsumer(v -> cfg.gamma = v)
                .build());

        ConfigCategory enchant = builder.getOrCreateCategory(t("config.survivalassist.category.enchant"));

        List<String> enchantIds = EnchantBookFeature.getAvailableEnchantIds();
        String[] enchantArr = enchantIds.toArray(new String[0]);
        String currentEnchant = cfg.enchantBookTargetId;
        boolean inList = false;
        for (String id : enchantArr) {
            if (id.equals(currentEnchant)) {
                inList = true;
                break;
            }
        }
        if (!inList) {
            currentEnchant = "minecraft:mending";
        }
        enchant.addEntry(entryBuilder.startSelector(
                        t("config.survivalassist.enchantBookTarget"),
                        enchantArr,
                        currentEnchant)
                .setNameProvider(EnchantBookFeature::displayName)
                .setDefaultValue("minecraft:mending")
                .setTooltip(t("config.survivalassist.enchantBookTarget.tooltip"))
                .setSaveConsumer(v -> cfg.enchantBookTargetId = v)
                .build());

        enchant.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.enchantBookTargetLevel"), cfg.enchantBookTargetLevel, 0, Math.max(1, cfg.enchantBookMaxLevel))
                .setDefaultValue(0)
                .setTooltip(t("config.survivalassist.enchantBookTargetLevel.tooltip"))
                .setSaveConsumer(v -> cfg.enchantBookTargetLevel = v)
                .build());

        enchant.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.enchantBookMaxLevel"), cfg.enchantBookMaxLevel, 1, 10)
                .setDefaultValue(5)
                .setTooltip(t("config.survivalassist.enchantBookMaxLevel.tooltip"))
                .setSaveConsumer(v -> cfg.enchantBookMaxLevel = v)
                .build());

        enchant.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.enchantBookMaxRounds"), cfg.enchantBookMaxRounds, 0, 1000)
                .setDefaultValue(0)
                .setTooltip(t("config.survivalassist.enchantBookMaxRounds.tooltip"))
                .setSaveConsumer(v -> cfg.enchantBookMaxRounds = v)
                .build());

        ConfigCategory autoMine = builder.getOrCreateCategory(t("config.survivalassist.category.automine"));

        autoMine.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.autoMineRange"), cfg.autoMineRange, 8, 64)
                .setDefaultValue(64)
                .setTooltip(t("config.survivalassist.autoMineRange.tooltip"))
                .setSaveConsumer(v -> cfg.autoMineRange = v)
                .build());

        ConfigCategory xray = builder.getOrCreateCategory(t("config.survivalassist.category.xray"));

        xray.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.xrayRange"), cfg.xrayRange, 8, 64)
                .setDefaultValue(64)
                .setTooltip(t("config.survivalassist.xrayRange.tooltip"))
                .setSaveConsumer(v -> cfg.xrayRange = v)
                .build());

        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreCoal"), cfg.autoMineCoal)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineCoal = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreIron"), cfg.autoMineIron)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineIron = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreCopper"), cfg.autoMineCopper)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineCopper = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreGold"), cfg.autoMineGold)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineGold = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreRedstone"), cfg.autoMineRedstone)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineRedstone = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreLapis"), cfg.autoMineLapis)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineLapis = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreDiamond"), cfg.autoMineDiamond)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineDiamond = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreEmerald"), cfg.autoMineEmerald)
                .setDefaultValue(true)
                .setSaveConsumer(v -> cfg.autoMineEmerald = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreNetherQuartz"), cfg.autoMineNetherQuartz)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.autoMineNetherQuartz = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreNetherGold"), cfg.autoMineNetherGold)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.autoMineNetherGold = v)
                .build());
        xray.addEntry(entryBuilder.startBooleanToggle(
                        t("config.survivalassist.oreAncientDebris"), cfg.autoMineAncientDebris)
                .setDefaultValue(false)
                .setSaveConsumer(v -> cfg.autoMineAncientDebris = v)
                .build());

        xray.addEntry(entryBuilder.startStrField(
                        t("config.survivalassist.customOreList"), cfg.customOreList)
                .setDefaultValue("")
                .setTooltip(t("config.survivalassist.customOreList.tooltip"))
                .setSaveConsumer(v -> cfg.customOreList = v)
                .build());

        ConfigCategory autoExit = builder.getOrCreateCategory(t("config.survivalassist.category.autoexit"));

        autoExit.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.exitHealthPercent"), cfg.exitHealthPercent, 0, 100)
                .setDefaultValue(30)
                .setTooltip(t("config.survivalassist.exitHealthPercent.tooltip"))
                .setSaveConsumer(v -> cfg.exitHealthPercent = v)
                .build());

        autoExit.addEntry(entryBuilder.startIntSlider(
                        t("config.survivalassist.exitPickaxePercent"), cfg.exitPickaxePercent, 0, 100)
                .setDefaultValue(30)
                .setTooltip(t("config.survivalassist.exitPickaxePercent.tooltip"))
                .setSaveConsumer(v -> cfg.exitPickaxePercent = v)
                .build());

        builder.setSavingRunnable(ModConfig::save);

        return builder.build();
    }
}
