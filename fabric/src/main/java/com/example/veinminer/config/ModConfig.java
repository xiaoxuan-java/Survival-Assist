package com.example.veinminer.config;

import com.example.veinminer.VeinMinerMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configFile;

    // 功能总开关
    public boolean enableHealthDisplay = true;
    public boolean enableVeinMiner = true;
    public boolean enableNearestPlayer = true;
    public boolean enableAutoReplant = true;
    public boolean enableEggThrower = true;
    public boolean enableMatureOnlyHarvest = true;
    public boolean enableAutoSprint = true;
    public boolean enableAutoFish = true;
    public boolean enableEnchantBook = true;
    public boolean enableAutoMine = true;
    public boolean enableXRay = true;
    public boolean enableAutoExit = true;
    public boolean enableInventorySort = true;

    // 连锁挖掘设置
    public int veinMinerMaxBlocks = 64;

    // 血量显示设置
    public boolean healthShowNumbers = false;
    public int healthBarWidth = 28;      // 血条长度（像素，未缩放前总宽度）
    public int healthDisplayRange = 64;  // 血条显示距离（方块）

    // 最近玩家距离设置
    public boolean distanceShowName = true;

    // 自动补种设置
    public int autoReplantDelay = 3;     // 补种延迟（tick）

    // 鸡蛋连点设置
    public int eggThrowInterval = 1;     // 投掷间隔（tick）

    // 伽马值（亮度）设置
    public boolean enableGamma = true;
    public int gamma = 50;               // 总亮度（0~500）：0~100 映射原版 gamma（100=原版最亮），>100 额外乘性提亮光照贴图

    // 附魔书刷取设置
    public String enchantBookTargetId = "minecraft:mending"; // 目标附魔书 ID
    public int enchantBookTargetLevel = 0;                    // 目标等级，0 表示任意等级
    public int enchantBookMaxLevel = 5;                       // 目标等级滑块上限
    public int enchantBookMaxRounds = 0;                      // 刷取轮数上限，0 表示无上限

    // 自动挖矿设置
    public int autoMineRange = 64;         // 扫描范围（方块，8~64）
    public boolean autoMineCoal = true;         // 煤矿石
    public boolean autoMineIron = true;         // 铁矿石
    public boolean autoMineCopper = true;       // 铜矿石
    public boolean autoMineGold = true;         // 金矿石
    public boolean autoMineRedstone = true;     // 红石矿石
    public boolean autoMineLapis = true;        // 青金石矿石
    public boolean autoMineDiamond = true;      // 钻石矿石
    public boolean autoMineEmerald = true;      // 绿宝石矿石
    public boolean autoMineNetherQuartz = false;// 下界石英矿石
    public boolean autoMineNetherGold = false;  // 下界金矿石
    public boolean autoMineAncientDebris = false;// 远古残骸

    // 矿透（X-Ray）设置
    public int xrayRange = 64; // 矿透扫描/渲染范围（方块，8~64）
    public String customOreList = ""; // 自定义矿石方块 ID，逗号分隔

    // 自动退出设置
    public int exitHealthPercent = 30;     // 血量低于该百分比自动退出（0~100）
    public int exitPickaxePercent = 30;    // 镐子耐久低于该百分比自动退出（0~100）

    private static ModConfig INSTANCE;

    public static ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = new ModConfig();
        }
        return INSTANCE;
    }

    public static void init() {
        configFile = FabricLoader.getInstance().getConfigDir()
                .resolve(VeinMinerMod.MOD_ID + ".json");
        load();
    }

    public static void load() {
        ModConfig cfg = get();
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile)) {
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) {
                    if (loaded.customOreList == null) loaded.customOreList = "";
                    if (loaded.enchantBookMaxLevel < 1) loaded.enchantBookMaxLevel = 5;
                    INSTANCE = loaded;
                    return;
                }
            } catch (IOException | RuntimeException e) {
                VeinMinerMod.LOGGER.error("Failed to load config", e);
            }
        }
        // 首次运行或无配置文件时保存默认配置
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile)) {
                GSON.toJson(get(), writer);
            }
        } catch (IOException e) {
            VeinMinerMod.LOGGER.error("Failed to save config", e);
        }
    }
}
