package com.example.veinminer;

import com.example.veinminer.config.ModConfig;
import com.example.veinminer.feature.AutoFishFeature;
import com.example.veinminer.feature.AutoExitFeature;
import com.example.veinminer.feature.AutoMiningFeature;
import com.example.veinminer.feature.AutoReplantFeature;
import com.example.veinminer.feature.EggThrowerFeature;
import com.example.veinminer.feature.EnchantBookFeature;
import com.example.veinminer.feature.GammaFeature;
import com.example.veinminer.feature.HealthDisplayFeature;
import com.example.veinminer.feature.InventorySortFeature;
import com.example.veinminer.feature.MatureOnlyHarvestFeature;
import com.example.veinminer.feature.NearestPlayerFeature;
import com.example.veinminer.feature.ToggleSprintFeature;
import com.example.veinminer.feature.VeinMinerFeature;
import com.example.veinminer.feature.XRayFeature;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VeinMinerMod implements ClientModInitializer {

    public static final String MOD_ID = "survivalassist";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // 连锁挖掘快捷键：使用 "·" 键（GLFW_KEY_GRAVE_ACCENT，位于键盘左上角 ESC 下方）
    public static final KeyBinding VEIN_MINE_KEY = new KeyBinding(
            "key.survivalassist.vein_mine",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_GRAVE_ACCENT,
            "category.survivalassist"
    );

    // 附魔书刷取快捷键：默认 V 键
    public static final KeyBinding ENCHANT_BOOK_KEY = new KeyBinding(
            "key.survivalassist.enchant_book",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "category.survivalassist"
    );

    // 自动挖矿快捷键：默认 G 键
    public static final KeyBinding AUTO_MINE_KEY = new KeyBinding(
            "key.survivalassist.auto_mine",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            "category.survivalassist"
    );

    // 矿透快捷键：默认 X 键
    public static final KeyBinding XRAY_KEY = new KeyBinding(
            "key.survivalassist.xray",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            "category.survivalassist"
    );

    // 一键整理背包快捷键：默认 R 键
    public static final KeyBinding SORT_INVENTORY_KEY = new KeyBinding(
            "key.survivalassist.sort_inventory",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "category.survivalassist"
    );

    @Override
    public void onInitializeClient() {
        // 注册配置
        ModConfig.init();

        // 注册按键
        KeyBindingHelper.registerKeyBinding(VEIN_MINE_KEY);
        KeyBindingHelper.registerKeyBinding(ENCHANT_BOOK_KEY);
        KeyBindingHelper.registerKeyBinding(AUTO_MINE_KEY);
        KeyBindingHelper.registerKeyBinding(XRAY_KEY);
        KeyBindingHelper.registerKeyBinding(SORT_INVENTORY_KEY);

        // 注册各功能
        HealthDisplayFeature.init();
        VeinMinerFeature.init();
        NearestPlayerFeature.init();
        AutoReplantFeature.init();
        EggThrowerFeature.init();
        MatureOnlyHarvestFeature.init();
        GammaFeature.init();
        ToggleSprintFeature.init();
        AutoFishFeature.init();
        EnchantBookFeature.init();
        AutoMiningFeature.init();
        XRayFeature.init();
        AutoExitFeature.init();
        InventorySortFeature.init();

        // 生物血量渲染（世界渲染阶段，billboard 方式）
        WorldRenderEvents.AFTER_ENTITIES.register(HealthDisplayFeature::render);

        // 连锁挖掘：按住预览边框，松开触发挖掘
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            VeinMinerFeature.onTick(client);
        });

        // 自动补种：挖掉成熟作物后自动种回种子
        ClientTickEvents.END_CLIENT_TICK.register(AutoReplantFeature::onTick);

        // 鸡蛋连点投掷
        ClientTickEvents.END_CLIENT_TICK.register(EggThrowerFeature::onTick);

        // 伽马值（亮度）调节
        ClientTickEvents.END_CLIENT_TICK.register(GammaFeature::onTick);

        // 自动疾跑（按住前进自动疾跑）——注册在 START_CLIENT_TICK，先于玩家移动逻辑执行，零延迟生效
        ClientTickEvents.START_CLIENT_TICK.register(ToggleSprintFeature::onTick);

        // 自动钓鱼（自动抛竿收竿）
        ClientTickEvents.END_CLIENT_TICK.register(AutoFishFeature::onTick);

        // 自动刷附魔书
        ClientTickEvents.END_CLIENT_TICK.register(EnchantBookFeature::onTick);

        // 自动挖矿（自动寻矿+智能寻路+挖掘），注册在 START 阶段让移动设置当 tick 生效
        ClientTickEvents.START_CLIENT_TICK.register(AutoMiningFeature::onTick);

        // 矿透（非矿石不渲染 + 矿石发光描边），tick 负责扫描缓存，渲染在 AFTER_ENTITIES
        ClientTickEvents.END_CLIENT_TICK.register(XRayFeature::onTick);

        // 自动退出：血量/镐子耐久低于阈值自动退出服务器
        ClientTickEvents.END_CLIENT_TICK.register(AutoExitFeature::onTick);

        // 一键整理背包 / 容器
        ClientTickEvents.END_CLIENT_TICK.register(InventorySortFeature::onTick);

        // 白框透视渲染（在实体层之后、粒子之前，带深度测试禁用以实现透视效果）
        WorldRenderEvents.AFTER_ENTITIES.register(VeinMinerFeature::renderOutline);

        // 矿透矿石发光描边（透视高亮）
        WorldRenderEvents.AFTER_ENTITIES.register(XRayFeature::renderOres);

        // 最近玩家距离 HUD
        HudRenderCallback.EVENT.register(NearestPlayerFeature::renderHud);
    }
}
