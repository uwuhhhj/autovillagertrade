package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.entity.Target;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import java.util.Comparator;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.TradeOffer;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.SelectMerchantTradeC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.network.packet.Packet;

public class AutoTrade extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final Setting<RotationMode> rotation = sgGeneral.add(new EnumSetting.Builder<RotationMode>()
        .name("rotate")
        .description("Determines when you should rotate towards the target.")
        .defaultValue(RotationMode.Always)
        .build()
    );
    private final Setting<Double> interactDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("interact-delay")
        .description("与村民交互的冷却时间（毫秒）")
        .defaultValue(1000)
        .min(0)
        .sliderMax(5000)
        .build()
    );
    private final Setting<Double> searchRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("search-range")
        .description("自动寻找村民的范围（格）")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(20.0)
        .build()
    );
    private final Setting<String> tradePairs = sgGeneral.add(new StringSetting.Builder()
        .name("trade-pairs")
        .description("多组买入-卖出物品ID对，格式如：minecraft:emerald->minecraft:experience_bottle,minecraft:gold_ingot->minecraft:emerald")
        .defaultValue("minecraft:emerald->minecraft:experience_bottle")
        .build()
    );
    private long lastInteractTime = 0;
    // 黑名单Map，key为村民UUID，value为拉黑时间戳
    private final Map<UUID, Long> villagerBlacklist = new HashMap<>();
    private final Setting<Integer> blacklistDurationSeconds = sgGeneral.add(new meteordevelopment.meteorclient.settings.IntSetting.Builder()
        .name("blacklist-seconds")
        .description("村民黑名单持续时间（秒）")
        .defaultValue(300)
        .min(10)
        .sliderMax(1800)
        .build()
    );
    // 记录最近一次交互的村民UUID
    private UUID lastInteractedVillagerUuid = null;
    private final Setting<Boolean> showLines = sgGeneral.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
        .name("show-villager-lines")
        .description("是否显示村民到准星的连线。")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> allowDirectPacket = sgGeneral.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
        .name("allow-direct-packet")
        .description("是否直接发包与村民交互（部分服务器可能只支持此方式）。关闭则模拟玩家右键。")
        .defaultValue(false)
        .build()
    );
    // 记录最近一次旋转目标点
    private Vec3d lastRotationTarget = null;
    // 新增：调试用朝向白线开关
    private final Setting<Boolean> showRotationDebugLine = sgGeneral.add(new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
        .name("show-rotation-debug-line")
        .description("是否显示最近一次旋转目标的调试白线。")
        .defaultValue(false)
        .build()
    );
    // 新增：转头速度设置（度/每tick）
    private final Setting<Double> rotationSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("每tick最大转头速度（度），用于模拟玩家正常转头。")
        .defaultValue(10.0)
        .min(1.0)
        .sliderMax(90.0)
        .build()
    );
    // 记录目标yaw/pitch
    private Float targetYaw = null;
    private Float targetPitch = null;
    private boolean waitingForRotation = false;

    public AutoTrade() {
        super(AddonTemplate.CATEGORY, "auto-trade", "自动化村民交易模块。");
    }
    // 你可以在这里添加设置和事件处理方法


    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 先处理转头
        handleSmoothRotation();
        rightClickNearestVillager();
        autoTradeWithVillager();
    }

    /**
     * 平滑转头逻辑，每tick调用
     */
    private void handleSmoothRotation() {
        if (targetYaw == null || targetPitch == null || mc.player == null) return;
        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();
        float maxStep = rotationSpeed.get().floatValue();
        float newYaw = approachAngle(currentYaw, targetYaw, maxStep);
        float newPitch = approachAngle(currentPitch, targetPitch, maxStep);
        mc.player.setYaw(newYaw);
        mc.player.setPitch(newPitch);
        // 判断是否已对准目标
        if (Math.abs(newYaw - targetYaw) < 1.0f && Math.abs(newPitch - targetPitch) < 1.0f) {
            // 已对准
            targetYaw = null;
            targetPitch = null;
            boolean wasWaiting = waitingForRotation;
            waitingForRotation = false;
            // 新增：转头完成后自动交互
            if (wasWaiting) rightClickNearestVillager();
        } else {
            waitingForRotation = true;
        }
    }
    // 角度插值（考虑360度环绕）
    private float approachAngle(float current, float target, float maxStep) {
        float delta = wrapDegrees(target - current);
        if (Math.abs(delta) <= maxStep) return target;
        return current + Math.copySign(maxStep, delta);
    }
    // 角度归一化到[-180,180]
    private float wrapDegrees(float degrees) {
        degrees = degrees % 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }

    /**
     * 自动右键附近最近的村民，前提是玩家主手拿着绿宝石。
     */
    private void rightClickNearestVillager() {
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        if (now - lastInteractTime < interactDelay.get()) return;
        Box range = mc.player.getBoundingBox().expand(searchRange.get());
        VillagerEntity villager = mc.world
            .getEntitiesByClass(VillagerEntity.class, range, v -> v.isAlive() && !isVillagerBlacklisted(v))
            .stream()
            .min(Comparator.comparingDouble(v -> v.squaredDistanceTo(mc.player)))
            .orElse(null);
        if (villager == null) {
            info("[AutoTrade] 附近没有可交互村民");
            return;
        }
        boolean canSee = PlayerUtils.canSeeEntity(villager);
        Target targetPart = canSee ? Target.Head : Target.Body;
        // 详细Debug信息
        info(String.format(
            "[AutoTrade] 玩家(%.2f,%.2f,%.2f) UUID=%s 状态:alive=%s spectator=%s creative=%s\n村民(%.2f,%.2f,%.2f) UUID=%s 状态:alive=%s removed=%s\n距离=%.2f 主手物品=%s canSee=%s 旋转模式=%s",
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getUuid(),
            mc.player.isAlive(), mc.player.isSpectator(), mc.player.isCreative(),
            villager.getX(), villager.getY(), villager.getZ(),
            villager.getUuid(),
            villager.isAlive(), villager.isRemoved(),
            mc.player.distanceTo(villager),
            mc.player.getMainHandStack().getItem().toString(),
            canSee, rotation.get()
        ));
        if (rotation.get() == RotationMode.Always) {
            // 计算目标yaw/pitch
            float yaw = (float) Rotations.getYaw(villager);
            float pitch = (float) Rotations.getPitch(villager, targetPart);
            targetYaw = yaw;
            targetPitch = pitch;
            // 记录旋转目标点
            lastRotationTarget = new Vec3d(villager.getX(), villager.getY() + villager.getHeight() / 2.0, villager.getZ());
            waitingForRotation = true;
        }
        if (!mc.player.getMainHandStack().isOf(Items.EMERALD)) {
            info("[AutoTrade] 主手不是绿宝石，跳过交互");
            return;
        }
        if (rotation.get() == RotationMode.OnInteract) {
            float yaw = (float) Rotations.getYaw(villager);
            float pitch = (float) Rotations.getPitch(villager, targetPart);
            targetYaw = yaw;
            targetPitch = pitch;
            lastRotationTarget = new Vec3d(villager.getX(), villager.getY() + villager.getHeight() / 2.0, villager.getZ());
            waitingForRotation = true;
        }
        // 只要能看到村民就立即交互
        if (canSee) {
            info("[AutoTrade] 已能看到村民，立即交互");
            if (allowDirectPacket.get()) {
                info("[AutoTrade] 执行右键交互：严格数据包顺序");
                sendVillagerInteractPackets(villager);
            } else {
                info("[AutoTrade] 执行右键交互：模拟玩家右键村民");
                mc.interactionManager.interactEntity(mc.player, villager, Hand.MAIN_HAND);
                info("[AutoTrade] 已调用interactEntity");
            }
            lastInteractTime = now;
            lastInteractedVillagerUuid = villager.getUuid();
            return;
        }
        // 其余情况继续等待转头
        if (waitingForRotation) {
            info("[AutoTrade] 等待转头完成，暂不交互");
            return;
        }
    }

    /**
     * 自动检测村民交易界面并执行交易
     */
    private void autoTradeWithVillager() {
        if (!(mc.currentScreen instanceof MerchantScreen)) return;
        MerchantScreen screen = (MerchantScreen) mc.currentScreen;
        MerchantScreenHandler handler = screen.getScreenHandler();
        TradeOfferList offers = handler.getRecipes();
        String config = tradePairs.get();
        if (config == null || config.isEmpty()) {
            info("未配置买入-卖出物品ID对，自动交易跳过。");
            return;
        }
        String[] pairs = config.split(",");
        boolean traded = false;
        for (int i = 0; i < offers.size(); i++) {
            TradeOffer offer = offers.get(i);
            String buyId = Registries.ITEM.getId(offer.getFirstBuyItem().itemStack().getItem()).toString();
            String sellId = Registries.ITEM.getId(offer.getSellItem().getItem()).toString();
            for (String pair : pairs) {
                String[] ids = pair.trim().split("->");
                if (ids.length != 2) continue;
                String configBuy = ids[0].trim();
                String configSell = ids[1].trim();
                if (buyId.equals(configBuy) && sellId.equals(configSell)) {
                    if (!offer.isDisabled() && offer.getUses() < offer.getMaxUses()) {
                        handler.switchTo(i);
                        handler.setRecipeIndex(i);
                        mc.getNetworkHandler().sendPacket(new SelectMerchantTradeC2SPacket(i));
                        // shift最大次数交易
                        int slotId = handler.getSlot(2).id; // 输出槽通常为2
                        mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
                        info("已自动交易：" + configBuy + " -> " + configSell);
                        traded = true;
                    } else {
                        info("交易栏已用尽或被禁用：" + configBuy + " -> " + configSell);
                        // 用最近一次交互的村民UUID加入黑名单
                        if (lastInteractedVillagerUuid != null) {
                            villagerBlacklist.put(lastInteractedVillagerUuid, System.currentTimeMillis());
                        }
                    }
                }
            }
        }
        if (!traded) info("未找到符合条件的交易对，未执行交易。");
        screen.close();
        // 交易界面关闭后清空UUID
        lastInteractedVillagerUuid = null;
    }

    /**
     * 检查村民是否在黑名单且未过期
     */
    private boolean isVillagerBlacklisted(VillagerEntity villager) {
        Long time = villagerBlacklist.get(villager.getUuid());
        if (time == null) return false;
        if (System.currentTimeMillis() - time > getBlacklistDuration()) {
            villagerBlacklist.remove(villager.getUuid());
            //info("这个村民可以交易");
            return false;
        }
        //info("附近村民还没补货呢，拉黑状态");
        return true;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!showLines.get()) return;
        if (mc.world == null || mc.player == null) return;
        Vec3d cameraPos = mc.gameRenderer.getCamera().getPos();
        Vec3d lookVec = mc.player.getRotationVecClient();
        double dist = 50.0;
        Vec3d crosshairPos = cameraPos.add(lookVec.multiply(dist));
        // 绘制最近一次旋转目标的白线
        if (showRotationDebugLine.get() && lastRotationTarget != null) {
            // 玩家头部位置
            double px = mc.player.getX();
            double py = mc.player.getY() + mc.player.getStandingEyeHeight();
            double pz = mc.player.getZ();
            event.renderer.line(px, py, pz, lastRotationTarget.x, lastRotationTarget.y, lastRotationTarget.z, new Color(255, 255, 255, 255));
        }
        for (VillagerEntity villager : mc.world.getEntitiesByClass(VillagerEntity.class, mc.player.getBoundingBox().expand(32), v -> true)) {
            Long time = villagerBlacklist.get(villager.getUuid());
            double x = villager.getX();
            double y = villager.getY() + villager.getHeight() + 0.5;
            double z = villager.getZ();
            if (time != null) {
                long left = getBlacklistDuration() - (System.currentTimeMillis() - time);
                if (left > 0) {
                    float percent = (float) left / (float) getBlacklistDuration();
                    int alpha = (int) (percent * 255);
                    if (alpha < 30) alpha = 30;
                    if (alpha > 255) alpha = 255;
                    Color dynamicColor = new Color(255, 0, 0, alpha);
                    event.renderer.line(x, y, z, crosshairPos.x, crosshairPos.y, crosshairPos.z, dynamicColor);
                    continue;
                }
            }
            // 非黑名单村民显示绿色连线
            event.renderer.line(x, y, z, crosshairPos.x, crosshairPos.y, crosshairPos.z, new Color(0, 255, 0, 180));
        }
    }

    private long getBlacklistDuration() {
        return blacklistDurationSeconds.get() * 1000L;
    }

    // 封装：严格顺序发送村民交互相关数据包
    private void sendVillagerInteractPackets(VillagerEntity villager) {
        // 1. INTERACT 动作
        Packet<?> interactPacket = PlayerInteractEntityC2SPacket.interact(
            villager,
            false,
            Hand.MAIN_HAND
        );
        mc.getNetworkHandler().sendPacket(interactPacket);
        // 2. INTERACT_AT 动作
        Vec3d hitPos = villager.getPos().add(0, villager.getStandingEyeHeight() / 2.0, 0);
        Packet<?> interactAtPacket = PlayerInteractEntityC2SPacket.interactAt(
            villager,
            false,
            Hand.MAIN_HAND,
            hitPos
        );
        mc.getNetworkHandler().sendPacket(interactAtPacket);
        // 3. SWING 动画
        mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.HandSwingC2SPacket(Hand.MAIN_HAND));
        info("[AutoTrade] 成功模拟完整右键交互（三个包已发送）");
    }

    public enum RotationMode {
        Always,
        OnInteract,
        None
    }
}