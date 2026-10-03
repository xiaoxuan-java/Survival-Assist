package com.example.veinminer;

import com.example.veinminer.config.ModConfig;
import com.example.veinminer.config.ModConfigScreen;
import com.example.veinminer.feature.AutoExitFeature;
import com.example.veinminer.feature.AutoFishFeature;
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
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@Mod(SurvivalAssistMod.MODID)
public class SurvivalAssistMod {

    public static final String MODID = "survivalassist";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 4 个快捷键
    public static KeyMapping VEIN_MINE_KEY;
    public static KeyMapping ENCHANT_BOOK_KEY;
    public static KeyMapping AUTO_MINE_KEY;
    public static KeyMapping XRAY_KEY;
    public static KeyMapping SORT_INVENTORY_KEY;

    public SurvivalAssistMod() {
        // 配置在两端都可用
        ModConfig.init();

        // 纯客户端模组：仅在客户端执行客户端初始化
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> this::initClient);
    }

    private void initClient() {
        // 按键（连锁挖掘 · / 附魔书 V / 自动挖矿 G / 矿透 X）
        VEIN_MINE_KEY = new KeyMapping("key.survivalassist.vein_mine",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, "category.survivalassist");
        ENCHANT_BOOK_KEY = new KeyMapping("key.survivalassist.enchant_book",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.survivalassist");
        AUTO_MINE_KEY = new KeyMapping("key.survivalassist.auto_mine",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.survivalassist");
        XRAY_KEY = new KeyMapping("key.survivalassist.xray",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "category.survivalassist");
        SORT_INVENTORY_KEY = new KeyMapping("key.survivalassist.sort_inventory",
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.survivalassist");

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::registerKeys);

        // 配置屏幕（替代 ModMenu）
        ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> ModConfigScreen.create(parent)));

        // 各功能初始化（Forge 版大多为空方法，保留接口一致性）
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

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(VEIN_MINE_KEY);
        event.register(ENCHANT_BOOK_KEY);
        event.register(AUTO_MINE_KEY);
        event.register(XRAY_KEY);
        event.register(SORT_INVENTORY_KEY);
    }

    /** 客户端 tick（对应 Fabric START_CLIENT_TICK / END_CLIENT_TICK）。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            // START 阶段：先于玩家移动逻辑，让移动设置当 tick 生效
            ToggleSprintFeature.onTick(client);
            AutoMiningFeature.onTick(client);
        } else {
            VeinMinerFeature.onTick(client);
            AutoReplantFeature.onTick(client);
            EggThrowerFeature.onTick(client);
            GammaFeature.onTick(client);
            AutoFishFeature.onTick(client);
            EnchantBookFeature.onTick(client);
            XRayFeature.onTick(client);
            AutoExitFeature.onTick(client);
            InventorySortFeature.onTick(client);
        }
    }

    /** 世界渲染（对应 Fabric WorldRenderEvents.AFTER_ENTITIES）。 */
    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        HealthDisplayFeature.render(event);
        VeinMinerFeature.renderOutline(event);
        XRayFeature.renderOres(event);
    }

    /** HUD 渲染（对应 Fabric HudRenderCallback）。 */
    @SubscribeEvent
    public void onRenderGui(RenderGuiOverlayEvent.Post event) {
        NearestPlayerFeature.renderHud(event.getGuiGraphics(), event.getPartialTick());
    }

    /** 左键攻击方块（对应 Fabric AttackBlockCallback，用于只采成熟作物）。 */
    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (MatureOnlyHarvestFeature.shouldCancel(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    /** 右键使用物品（对应 Fabric UseItemCallback，用于鸡蛋连点）。 */
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        EggThrowerFeature.onItemUse(event.getEntity(), event.getHand());
    }
}
