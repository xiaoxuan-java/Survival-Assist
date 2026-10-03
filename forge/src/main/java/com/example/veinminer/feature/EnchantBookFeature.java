package com.example.veinminer.feature;

import com.example.veinminer.SurvivalAssistMod;
import com.example.veinminer.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动刷附魔书（Forge 版）：单机与服务器通用的统一客户端驱动状态机。
 */
public class EnchantBookFeature {

    private enum Phase {
        IDLE, BREAK, BREAKING, WAIT_LOSE, PLACE, WAIT_CLAIM,
        OPEN_TRADE, WAIT_SCREEN, CHECKING,
        HOPPER_OPEN, HOPPER_TAKE, HOPPER_PLACE
    }

    private static final int LOSE_TIMEOUT = 300;
    private static final int CLAIM_TIMEOUT = 300;
    private static final int SCREEN_TIMEOUT = 40;
    private static final int PICKUP_TIMEOUT = 100;
    private static final int MAX_ROUNDS = 300;
    private static final double MAX_DISTANCE = 48.0;

    private static volatile boolean active = false;
    private static Phase phase = Phase.IDLE;
    private static UUID villagerUuid = null;
    private static int villagerEntityId = -1;
    private static BlockPos lecternPos = null;
    private static String targetEnchantId = "";
    private static int targetLevel = 0;
    private static int waitTicks = 0;
    private static int rounds = 0;
    private static boolean isSingleplayer = false;

    private static BlockPos hopperPos = null;

    private static volatile boolean queryPending = false;
    private static volatile boolean replyReady = false;
    private static volatile boolean replyFound = false;

    private static volatile boolean placeInFlight = false;
    private static volatile boolean placeReplyReady = false;
    private static volatile boolean placeReplyOk = false;

    public static void init() {
    }

    public static void onTick(Minecraft client) {
        if (SurvivalAssistMod.ENCHANT_BOOK_KEY.consumeClick()) {
            handleKey(client);
            return;
        }

        if (!active) {
            return;
        }
        if (client.player == null || client.level == null) {
            stop(client, "世界已关闭");
            return;
        }
        if (!ModConfig.get().enableEnchantBook) {
            stop(client, "功能已关闭");
            return;
        }

        if (isSingleplayer && replyReady) {
            boolean found = replyFound;
            replyReady = false;
            queryPending = false;
            onCheckResult(client, found);
        }
        if (isSingleplayer && placeReplyReady) {
            boolean ok = placeReplyOk;
            placeReplyReady = false;
            placeInFlight = false;
            onPlaceResult(client, ok);
        }
        if (!active) {
            return;
        }

        advance(client);
    }

    private static void handleKey(Minecraft client) {
        if (client.player == null || client.level == null) {
            return;
        }
        if (active) {
            stop(client, "已停止刷取");
            return;
        }

        if (villagerUuid == null) {
            Entity target = client.crosshairPickEntity;
            if (target instanceof Villager) {
                villagerUuid = target.getUUID();
                villagerEntityId = target.getId();
                client.player.displayClientMessage(Component.literal("§a[附魔书刷取] 已标记村民，请对准讲台再按一次 "
                        + SurvivalAssistMod.ENCHANT_BOOK_KEY.getTranslatedKeyMessage().getString()), false);
            } else {
                client.player.displayClientMessage(Component.literal("§e[附魔书刷取] 请先对准一个村民再按"), false);
            }
            return;
        }

        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult bhr) || hit.getType() != HitResult.Type.BLOCK) {
            client.player.displayClientMessage(Component.literal("§e[附魔书刷取] 请对准讲台方块"), false);
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        if (!client.level.getBlockState(pos).is(Blocks.LECTERN)) {
            client.player.displayClientMessage(Component.literal("§e[附魔书刷取] 请对准讲台方块"), false);
            return;
        }

        Entity e = client.level.getEntity(villagerEntityId);
        if (e instanceof Villager v) {
            double d = v.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d > MAX_DISTANCE * MAX_DISTANCE) {
                client.player.displayClientMessage(Component.literal("§e[附魔书刷取] 讲台离村民太远（超过 48 格），请靠近后再试"), false);
                return;
            }
        }

        lecternPos = pos.immutable();
        hopperPos = null;
        BlockPos below = pos.below();
        if (client.level.getBlockState(below).is(Blocks.HOPPER)) {
            hopperPos = below.immutable();
        }
        String rawTarget = ModConfig.get().enchantBookTargetId;
        targetEnchantId = resolveEnchantId(rawTarget);
        if (targetEnchantId == null) {
            targetEnchantId = "minecraft:mending";
        }
        targetLevel = ModConfig.get().enchantBookTargetLevel;
        isSingleplayer = client.getSingleplayerServer() != null;
        phase = Phase.BREAK;
        rounds = 0;
        waitTicks = 0;
        replyReady = false;
        queryPending = false;
        active = true;

        String levelText = targetLevel > 0 ? (" " + targetLevel + " 级") : "（任意等级）";
        client.player.displayClientMessage(Component.literal("§a[附魔书刷取] 开始刷取 "
                + displayName(targetEnchantId).getString() + levelText), false);
    }

    private static void advance(Minecraft client) {
        MultiPlayerGameMode im = client.gameMode;
        Level world = client.level;
        if (im == null) {
            return;
        }

        switch (phase) {
            case BREAK: {
                if (isSingleplayer) {
                    issueServerBreak(client);
                    phase = Phase.WAIT_LOSE;
                    waitTicks = 0;
                } else {
                    if (world.getBlockState(lecternPos).isAir()) {
                        phase = Phase.WAIT_LOSE;
                        waitTicks = 0;
                    } else {
                        im.startDestroyBlock(lecternPos, Direction.UP);
                        phase = Phase.BREAKING;
                    }
                }
                break;
            }
            case BREAKING: {
                if (world.getBlockState(lecternPos).isAir()) {
                    im.stopDestroyBlock();
                    phase = Phase.WAIT_LOSE;
                    waitTicks = 0;
                } else {
                    im.continueDestroyBlock(lecternPos, Direction.UP);
                }
                break;
            }
            case WAIT_LOSE: {
                Villager v = findVillagerClient(client);
                if (v == null) {
                    stop(client, "找不到已标记的村民，请重新标记");
                    return;
                }
                if (v.getVillagerData().getProfession() == VillagerProfession.NONE) {
                    phase = Phase.PLACE;
                    waitTicks = 0;
                } else {
                    waitTicks++;
                    if (waitTicks > LOSE_TIMEOUT) {
                        stop(client, "村民长时间未失去职业，可能已交易过（经验/等级非初始）");
                    }
                }
                break;
            }
            case PLACE: {
                if (isSingleplayer && hopperPos != null) {
                    if (placeInFlight) {
                        break;
                    }
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "漏斗里没有讲台（请确认漏斗在讲台正下方且能吸入掉落物）");
                        break;
                    }
                    if (waitTicks % 5 == 0) {
                        issueHopperPlace(client);
                    }
                } else if (!isSingleplayer && hopperPos != null) {
                    if (waitTicks == 0) {
                        im.useItemOn(client.player, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(hopperPos), Direction.UP, hopperPos, false));
                        phase = Phase.HOPPER_OPEN;
                    }
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "漏斗里没有讲台（请确认漏斗在讲台正下方且能吸入掉落物）");
                    }
                } else {
                    if (placeLectern(client)) {
                        phase = Phase.WAIT_CLAIM;
                        waitTicks = 0;
                    } else {
                        waitTicks++;
                        if (waitTicks > PICKUP_TIMEOUT) {
                            stop(client, "背包里没有讲台（请靠近拾取破坏掉落的讲台）");
                        }
                    }
                }
                break;
            }
            case HOPPER_OPEN: {
                if (client.player.containerMenu instanceof HopperMenu hsh) {
                    int lecternSlot = -1;
                    for (int i = 0; i < 5; i++) {
                        if (hsh.getSlot(i).getItem().is(Items.LECTERN)) {
                            lecternSlot = i;
                            break;
                        }
                    }
                    if (lecternSlot < 0) {
                        client.player.closeContainer();
                        phase = Phase.PLACE;
                        waitTicks = 0;
                    } else {
                        im.handleInventoryMouseClick(hsh.containerId, lecternSlot, 0, ClickType.QUICK_MOVE, client.player);
                        client.player.closeContainer();
                        phase = Phase.HOPPER_TAKE;
                        waitTicks = 0;
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > SCREEN_TIMEOUT) {
                        stop(client, "打开漏斗界面超时，请靠近漏斗后重试");
                    }
                }
                break;
            }
            case HOPPER_TAKE: {
                if (hasLecternInInventory(client)) {
                    sendSneak(client, true);
                    placeLectern(client);
                    sendSneak(client, false);
                    phase = Phase.WAIT_CLAIM;
                    waitTicks = 0;
                } else {
                    waitTicks++;
                    if (waitTicks > PICKUP_TIMEOUT) {
                        stop(client, "讲台未转移到背包");
                    }
                }
                break;
            }
            case HOPPER_PLACE:
                break;
            case WAIT_CLAIM: {
                Villager v = findVillagerClient(client);
                if (v == null) {
                    stop(client, "找不到已标记的村民");
                    return;
                }
                if (v.getVillagerData().getProfession() == VillagerProfession.LIBRARIAN) {
                    if (isSingleplayer) {
                        issueServerCheck(client);
                        phase = Phase.CHECKING;
                    } else {
                        im.interact(client.player, v, InteractionHand.MAIN_HAND);
                        phase = Phase.WAIT_SCREEN;
                        waitTicks = 0;
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > CLAIM_TIMEOUT) {
                        stop(client, "村民长时间未认领讲台，请把村民放到讲台附近");
                    }
                }
                break;
            }
            case WAIT_SCREEN: {
                if (client.player.containerMenu instanceof MerchantMenu msh) {
                    if (checkOffers(msh.getOffers())) {
                        playFoundSound(client);
                        stop(client, "已刷到目标附魔书「" + displayName(targetEnchantId).getString()
                                + "」！请直接交易锁定");
                    } else {
                        rounds++;
                        client.player.closeContainer();
                        int maxRounds = ModConfig.get().enchantBookMaxRounds;
                        if (maxRounds > 0 && rounds >= maxRounds) {
                            stop(client, "已刷 " + rounds + " 轮仍未命中，已停止");
                        } else {
                            phase = Phase.BREAK;
                        }
                    }
                } else {
                    waitTicks++;
                    if (waitTicks > SCREEN_TIMEOUT) {
                        stop(client, "打开交易界面超时，请靠近村民后重试");
                    }
                }
                break;
            }
            case CHECKING:
                break;
            default:
                break;
        }
    }

    private static void onCheckResult(Minecraft client, boolean found) {
        if (!active) {
            return;
        }
        if (found) {
            playFoundSound(client);
            stop(client, "已刷到目标附魔书「" + displayName(targetEnchantId).getString()
                    + "」！请右键村民交易锁定");
        } else {
            rounds++;
            int maxRounds = ModConfig.get().enchantBookMaxRounds;
            if (maxRounds > 0 && rounds >= maxRounds) {
                stop(client, "已刷 " + rounds + " 轮仍未命中，已停止");
            } else {
                phase = Phase.BREAK;
            }
        }
    }

    private static void issueServerBreak(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dk = client.level.dimension();
        final BlockPos p = lecternPos;
        final UUID pid = client.player.getUUID();
        server.execute(() -> {
            ServerLevel sw = server.getLevel(dk);
            if (sw == null) {
                return;
            }
            ServerPlayer spe = server.getPlayerList().getPlayer(pid);
            if (spe == null) {
                return;
            }
            if (sw.getBlockState(p).is(Blocks.LECTERN)) {
                spe.gameMode.destroyBlock(p);
            }
        });
    }

    private static void issueServerCheck(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) {
            return;
        }
        final ResourceKey<Level> dk = client.level.dimension();
        final UUID vid = villagerUuid;
        queryPending = true;
        server.execute(() -> {
            ServerLevel sw = server.getLevel(dk);
            boolean found = false;
            if (sw != null && vid != null) {
                Entity e = sw.getEntity(vid);
                if (e instanceof Villager v) {
                    found = checkOffers(v.getOffers());
                }
            }
            replyFound = found;
            queryPending = false;
            replyReady = true;
        });
    }

    private static boolean placeLectern(Minecraft client) {
        MultiPlayerGameMode im = client.gameMode;
        if (im == null || client.player == null) {
            return false;
        }
        Inventory inv = client.player.getInventory();
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && stack.is(Items.LECTERN)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return false;
        }

        BlockPos groundPos = lecternPos.below();
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(groundPos), Direction.UP, groundPos, false);

        int original = inv.selected;
        inv.selected = slot;
        im.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (inv.selected != original) {
            inv.selected = original;
        }
        return true;
    }

    private static boolean hasLecternInInventory(Minecraft client) {
        if (client.player == null) {
            return false;
        }
        Inventory inv = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && stack.is(Items.LECTERN)) {
                return true;
            }
        }
        return false;
    }

    private static void sendSneak(Minecraft client, boolean sneak) {
        if (client.player == null) {
            return;
        }
        ServerboundPlayerCommandPacket.Action action = sneak
                ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY;
        client.player.connection.send(new ServerboundPlayerCommandPacket(client.player, action));
    }

    private static void issueHopperPlace(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || hopperPos == null) {
            return;
        }
        final ResourceKey<Level> dk = client.level.dimension();
        final BlockPos hp = hopperPos;
        final BlockPos lp = lecternPos;
        placeInFlight = true;
        server.execute(() -> {
            ServerLevel sw = server.getLevel(dk);
            boolean ok = false;
            if (sw != null) {
                BlockEntity be = sw.getBlockEntity(hp);
                if (be instanceof HopperBlockEntity hopper) {
                    int slot = -1;
                    for (int i = 0; i < hopper.getContainerSize(); i++) {
                        ItemStack s = hopper.getItem(i);
                        if (!s.isEmpty() && s.is(Items.LECTERN)) {
                            slot = i;
                            break;
                        }
                    }
                    if (slot >= 0 && sw.getBlockState(lp).isAir()) {
                        hopper.removeItem(slot, 1);
                        sw.setBlock(lp, Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
                        ok = true;
                    }
                }
            }
            placeReplyOk = ok;
            placeInFlight = false;
            placeReplyReady = true;
        });
    }

    private static void onPlaceResult(Minecraft client, boolean ok) {
        if (!active) {
            return;
        }
        if (ok) {
            phase = Phase.WAIT_CLAIM;
            waitTicks = 0;
        }
    }

    private static void playFoundSound(Minecraft client) {
        if (client.player != null) {
            client.player.playSound(SoundEvents.PLAYER_LEVELUP, 1.0f, 1.0f);
        }
    }

    private static boolean checkOffers(MerchantOffers offers) {
        for (MerchantOffer offer : offers) {
            ItemStack sell = offer.getResult();
            if (sell.is(Items.ENCHANTED_BOOK)) {
                Map<Enchantment, Integer> enchs = EnchantmentHelper.getEnchantments(sell);
                for (Map.Entry<Enchantment, Integer> e : enchs.entrySet()) {
                    ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(e.getKey());
                    if (id != null && id.toString().equals(targetEnchantId)
                            && (targetLevel == 0 || e.getValue() == targetLevel)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Villager findVillagerClient(Minecraft client) {
        if (villagerUuid == null || client.level == null) {
            return null;
        }
        Entity e = client.level.getEntity(villagerEntityId);
        return e instanceof Villager v ? v : null;
    }

    private static void stop(Minecraft client, String msg) {
        boolean wasActive = active;
        active = false;
        phase = Phase.IDLE;
        villagerUuid = null;
        villagerEntityId = -1;
        queryPending = false;
        replyReady = false;

        if (wasActive && lecternPos != null && client.level != null) {
            final BlockPos p = lecternPos;
            if (client.level.getBlockState(p).isAir()) {
                if (client.getSingleplayerServer() != null) {
                    final MinecraftServer server = client.getSingleplayerServer();
                    final ResourceKey<Level> dk = client.level.dimension();
                    server.execute(() -> {
                        ServerLevel sw = server.getLevel(dk);
                        if (sw != null && sw.getBlockState(p).isAir()) {
                            sw.setBlock(p, Blocks.LECTERN.defaultBlockState(), Block.UPDATE_ALL);
                        }
                    });
                } else {
                    placeLectern(client);
                }
            }
        }
        lecternPos = null;

        if (client.player != null && msg != null) {
            client.player.displayClientMessage(Component.literal("§c[附魔书刷取] " + msg), false);
        }
    }

    public static List<String> getAvailableEnchantIds() {
        List<String> ids = new ArrayList<>();
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) {
            if (e.isTradeable()) {
                ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(e);
                if (id != null) {
                    ids.add(id.toString());
                }
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    public static Component displayName(String id) {
        ResourceLocation ident = ResourceLocation.tryParse(id);
        if (ident == null) {
            return Component.literal(id);
        }
        Enchantment e = BuiltInRegistries.ENCHANTMENT.get(ident);
        return e == null ? Component.literal(id) : Component.translatable(e.getDescriptionId());
    }

    public static String resolveEnchantId(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        ResourceLocation ident = ResourceLocation.tryParse(input);
        if (ident != null && BuiltInRegistries.ENCHANTMENT.containsKey(ident)) {
            return input;
        }
        for (Enchantment e : BuiltInRegistries.ENCHANTMENT) {
            if (Component.translatable(e.getDescriptionId()).getString().equals(input)) {
                ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(e);
                return id == null ? null : id.toString();
            }
        }
        return null;
    }
}
