package com.example.addon.modules;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;

public class SMCSmithingDupe extends Module {
    // 作者: 你的名字
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Double> exampleDelay = sgGeneral.add(new DoubleSetting.Builder()
        .name("example-delay")
        .description("延迟（毫秒）")
        .defaultValue(1000)
        .min(0)
        .sliderMax(5000)
        .build()
    );
    private final Setting<Integer> stateId = sgGeneral.add(new IntSetting.Builder()
        .name("state-id")
        .description("状态ID")
        .defaultValue(1)
        .min(0)
        .sliderMax(100)
        .build()
    );
    private final Setting<Integer> slotNum = sgGeneral.add(new IntSetting.Builder()
        .name("slot-num")
        .description("槽位编号")
        .defaultValue(81)
        .min(0)
        .sliderMax(100)
        .build()
    );
    private final Setting<Integer> buttonNum = sgGeneral.add(new IntSetting.Builder()
        .name("button-num")
        .description("按钮编号")
        .defaultValue(0)
        .min(0)
        .sliderMax(10)
        .build()
    );
    private final Setting<SlotActionType> slotActionType = sgGeneral.add(new EnumSetting.Builder<SlotActionType>()
        .name("slot-action-type")
        .description("物品栏操作类型")
        .defaultValue(SlotActionType.QUICK_MOVE)
        .build()
    );
    private final Setting<Integer> blockX = sgGeneral.add(new IntSetting.Builder()
        .name("block-x")
        .description("破坏方块X坐标")
        .defaultValue(0)
        .min(-30000000)
        .sliderMax(30000000)
        .build()
    );
    private final Setting<Integer> blockY = sgGeneral.add(new IntSetting.Builder()
        .name("block-y")
        .description("破坏方块Y坐标")
        .defaultValue(64)
        .min(0)
        .sliderMax(320)
        .build()
    );
    private final Setting<Integer> blockZ = sgGeneral.add(new IntSetting.Builder()
        .name("block-z")
        .description("破坏方块Z坐标")
        .defaultValue(0)
        .min(-30000000)
        .sliderMax(30000000)
        .build()
    );
    private long lastQuickMoveTime = 0;
    private volatile boolean running = false;
    private Thread loopThread;

    public SMCSmithingDupe() {
        // 调用父类构造方法，指定模块分类、名称和描述
        super(com.example.addon.AddonTemplate.CATEGORY, "SMCSmithingDupe", "这是一个示例模块，演示如何通过熔炉dupe物品。");
    }

    @Override
    public void onActivate() {
        info("ExampleModule 已激活！");
        running = true;
        info("发包循环线程启动");
        loopThread = new Thread(() -> {
            while (running) {
                try {
                    info("准备发包...");
                    sendQuickMovePacket();
                    
                } catch (Exception e) {
                    info("发包循环异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
                info("等待下一次发包，延迟：" + exampleDelay.get().longValue() + " ms");
                try { Thread.sleep(exampleDelay.get().longValue()); } catch (InterruptedException e) { info("线程被中断"); break; }
            }
            info("发包循环线程结束");
        });
        loopThread.start();
    }

    @Override
    public void onDeactivate() {
        info("ExampleModule 已停用！");
        running = false;
        if (loopThread != null) {
            info("尝试中断发包循环线程");
            loopThread.interrupt();
            loopThread = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        // 已无演示开关逻辑，保留空实现或可删除
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        toggle();
    }

    @EventHandler
    private void onScreenOpen(OpenScreenEvent event) {
        if (event.screen instanceof DisconnectedScreen) {
            toggle();
        }
    }

    /**
     * 单独封装的发包方法
     */
    private void sendQuickMovePacket() {
        try {
            if (mc.player == null || mc.getNetworkHandler() == null) {
                info("发包失败：mc.player 或 networkHandler 为空");
                return;
            }
            int cid = mc.player.currentScreenHandler.syncId;
            int sid = stateId.get();
            int snum = slotNum.get();
            int bnum = buttonNum.get();
            SlotActionType actionType = slotActionType.get();
            info(String.format("发包参数: containerId=%d, stateId=%d, slotNum=%d, buttonNum=%d, actionType=%s", cid, sid, snum, bnum, actionType));
            int slotCount = mc.player.currentScreenHandler.slots.size();
            info("当前容器总槽位数: " + slotCount);
            if (snum < 0 || snum >= slotCount) {
                info("发包失败：slotNum(" + snum + ") 超出当前容器槽位范围 (0-" + (slotCount-1) + ")");
                return;
            }
            ItemStack stack = mc.player.currentScreenHandler.getSlot(snum).getStack();
            if (stack == null || stack.isEmpty()) {
                info("目标槽(" + snum + ")为空，无物品，不发包");
                return;
            }
            info("目标槽物品: " + stack);
            ClickSlotC2SPacket packet = new ClickSlotC2SPacket(
                cid,
                sid,
                snum,
                bnum,
                actionType,
                stack.copy(),
                new Int2ObjectOpenHashMap<>()
            );
            mc.getNetworkHandler().sendPacket(packet);
            info("已发送自定义参数的快速移动物品栏物品数据包");
            sendBreakBlockPacket();
        } catch (Exception e) {
            info("sendQuickMovePacket异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 发包破坏指定位置的方块
     */
    private void sendBreakBlockPacket() {
        try {
            if (mc.player == null || mc.getNetworkHandler() == null) {
                info("破坏方块发包失败：mc.player 或 networkHandler 为空");
                return;
            }
            // 自动获取玩家脚下坐标
            BlockPos pos = new BlockPos((int)Math.floor(mc.player.getX()), (int)Math.floor(mc.player.getY() - 1), (int)Math.floor(mc.player.getZ()));
            Direction direction = Direction.DOWN;
            info(String.format("准备发包破坏方块，坐标: (%d, %d, %d), 方向: %s", pos.getX(), pos.getY(), pos.getZ(), direction));
            PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                pos,
                direction,
                0 // 序号参数，通常为0
            );
            mc.getNetworkHandler().sendPacket(packet);
            info("已发送破坏方块数据包");
            // 挥手动画
            mc.player.swingHand(Hand.MAIN_HAND);
            info("已发送主手挥手动画");
        } catch (Exception e) {
            info("sendBreakBlockPacket异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
} 