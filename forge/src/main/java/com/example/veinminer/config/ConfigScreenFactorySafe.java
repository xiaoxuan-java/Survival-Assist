package com.example.veinminer.config;

import net.minecraft.client.gui.screens.Screen;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Method;

/**
 * Cloth Config 是否可用的安全探测 + 配置界面创建的隔离层。
 *
 * <p>关键点：本类<b>不直接 import 任何 me.shedaniel.clothconfig2.* 类</b>，
 * 并且通过<b>反射</b>调用 {@link ModConfigScreen#create}，
 * 确保 JVM 永远不会在本类被加载/验证时去解析 Cloth Config 相关类型。
 * 这样即使玩家没装 Cloth Config，也不会抛 {@link NoClassDefFoundError}。</p>
 */
public final class ConfigScreenFactorySafe {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ConfigScreenFactorySafe() {
    }

    /** Cloth Config 的探测标记类（只以字符串形式引用，不 import）。 */
    private static final String CLOTH_MARKER = "me.shedaniel.clothconfig2.api.ConfigBuilder";

    /** 真正的配置界面构建类（延迟到反射时才加载）。 */
    private static final String SCREEN_CLASS = "com.example.veinminer.config.ModConfigScreen";

    /** Cloth Config 可用性缓存（null = 尚未探测）。 */
    private static Boolean clothAvailable = null;

    /**
     * 探测 Cloth Config 是否已安装。
     *
     * @return true 表示可用，可以安全打开图形配置界面
     */
    public static boolean isClothConfigAvailable() {
        if (clothAvailable == null) {
            boolean found;
            try {
                Class.forName(CLOTH_MARKER, false,
                        ConfigScreenFactorySafe.class.getClassLoader());
                found = true;
                LOGGER.info("[SurvivalAssist] 检测到 Cloth Config，配置界面已启用");
            } catch (Throwable t) {
                found = false;
                LOGGER.warn("[SurvivalAssist] 未检测到 Cloth Config，"
                        + "已跳过配置界面注册（Mod 其余功能不受影响）。"
                        + "如需图形配置界面，请安装 Cloth Config。");
            }
            clothAvailable = found;
        }
        return clothAvailable;
    }

    /**
     * 通过反射创建配置界面，避免本类在验证期解析 Cloth Config 类型。
     *
     * @return 配置界面；创建失败时返回 {@code null}（不崩游戏）
     */
    public static Screen create(Screen parent) {
        try {
            Class<?> cls = Class.forName(SCREEN_CLASS, true,
                    ConfigScreenFactorySafe.class.getClassLoader());
            Method m = cls.getMethod("create", Screen.class);
            return (Screen) m.invoke(null, parent);
        } catch (Throwable t) {
            LOGGER.error("[SurvivalAssist] 创建配置界面失败，已忽略", t);
            return null;
        }
    }
}
