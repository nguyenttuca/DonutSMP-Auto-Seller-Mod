package com.donutsell.task;

import com.donutsell.config.DonutSellConfig;
import com.donutsell.inventory.InventoryUtils;
import com.donutsell.util.ChatUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Core state machine managing the auto-sell workflow.
 *
 * State flow (normal):
 *   IDLE → PREPARING_ITEM → [ADJUSTING_QUANTITY | SWITCHING_HOTBAR] →
 *   SENDING_COMMAND → WAITING_FOR_GUI → CLICKING_CONFIRM → COOLDOWN → (loop)
 *
 * State flow (auto-order) – 3-screen GUI navigation:
 *   (when out of items)
 *   → FETCHING_ORDER          : gửi /order (hoặc config.orderCommand)
 *   → WAITING_ORDER_GUI       : chờ màn hình "ORDERS (Page X)" mở, tìm order hoàn thành → click
 *   → NAVIGATING_TO_ORDER_EDIT: chờ màn hình "ORDERS -> Your Orders" / "Edit Order" → click COLLECT
 *   → NAVIGATING_TO_COLLECT   : chờ màn hình "ORDERS -> Collect Items" xác nhận sẵn sàng
 *   → COLLECTING_ORDER_ITEMS  : Shift+Click từng slot để lấy đồ về inventory
 *   → PREPARING_ITEM          : tiếp tục bán
 *
 * Safety features:
 *   - Randomized timing for anti-ban
 *   - Break simulator (rest periods)
 *   - World/dimension change detection
 *   - Disconnect detection
 *   - GUI timeout
 *
 * @author nguyenttuca
 */
public class SellTaskManager {
    private SellState state = SellState.IDLE;
    private int price = 0;
    private int tickCounter = 0;
    private int itemsSold = 0;
    private int adjustDropCount = 0;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 3;

    private final DonutSellConfig config;
    private String lastWorldKey = null;

    // Randomized delay targets (for anti-ban)
    private int currentCommandDelay = 0;
    private int currentGuiClickDelay = 0;
    private int currentItemDelay = 0;

    // Break simulator fields
    private int sellsSinceLastBreak = 0;
    private int sellsTargetForBreak = 15;
    private boolean isOnBreak = false;
    private int breakTicksRemaining = 0;

    // Dynamic delay extension (ticks)
    private int itemPickupProtectionDelay = 0;

    // Auto-order fields
    private boolean hasTriedOrder = false;
    private int orderCollectIndex = 0;
    private int orderFoundSlot = -1;  // slot index of completed order in order-list GUI

    // AH full wait fields
    private int ahFullWaitTicks = 0;

    private int randomizeDelay(int baseDelay) {
        if (baseDelay <= 5) return baseDelay;
        double jitterPercent = -0.15 + Math.random() * 0.50;
        int jitter = (int) (baseDelay * jitterPercent);
        return Math.max(5, baseDelay + jitter);
    }

    public SellTaskManager(DonutSellConfig config) {
        this.config = config;
    }

    // ========================= Public API =========================

    public void start(int sellPrice) {
        if (isRunning()) {
            ChatUtils.sendWarning("Đang chạy! Dùng /asell stop để dừng trước.");
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            ChatUtils.sendError("Bạn phải đang ở trong game!");
            return;
        }

        // Warn about Pause on Lost Focus
        if (mc.options.pauseOnLostFocus) {
            ChatUtils.sendWarning("⚠ 'Pause on Lost Focus' đang BẬT!");
            ChatUtils.sendInfo("Mod sẽ không chạy khi alt-tab. Vào Options → Video Settings → Pause on Lost Focus = OFF.");
        }

        this.price = sellPrice;
        this.tickCounter = 0;
        this.itemsSold = 0;
        this.adjustDropCount = 0;
        this.retryCount = 0;
        this.lastWorldKey = mc.world.getRegistryKey().getValue().toString();
        this.sellsSinceLastBreak = 0;
        this.sellsTargetForBreak = 10 + (int) (Math.random() * 11);
        this.itemPickupProtectionDelay = 0;
        this.isOnBreak = false;
        this.breakTicksRemaining = 0;
        this.hasTriedOrder = false;
        this.orderCollectIndex = 0;
        this.orderFoundSlot = -1;
        this.ahFullWaitTicks = 0;

        int totalItems = InventoryUtils.getTotalCount(config.targetItem);
        if (totalItems == 0 && !config.autoOrder) {
            ChatUtils.sendError("Không tìm thấy item: " + config.targetItem);
            state = SellState.ERROR;
            return;
        }

        ChatUtils.sendSuccess("═══ Bắt đầu bán tự động ═══");
        ChatUtils.sendInfo("Item: §f" + config.targetItem);
        ChatUtils.sendInfo("Giá: §f" + sellPrice);
        ChatUtils.sendInfo("Số lượng/lần: §f" + config.desiredQuantity);
        if (totalItems > 0) {
            ChatUtils.sendInfo("Tổng trong inventory: §f" + totalItems);
        }
        if (config.autoOrder) {
            ChatUtils.sendInfo("Auto-order: §aBẬT §7(/" + config.orderCommand + ")");
        }
        ChatUtils.sendInfo("Dùng §f/asell stop §7để dừng.");

        state = SellState.PREPARING_ITEM;
    }

    public void stop() {
        if (state == SellState.IDLE) {
            ChatUtils.sendWarning("Không có tác vụ nào đang chạy.");
            return;
        }

        SellState previousState = state;
        state = SellState.IDLE;
        tickCounter = 0;
        adjustDropCount = 0;
        isOnBreak = false;
        breakTicksRemaining = 0;
        ahFullWaitTicks = 0;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }

        ChatUtils.sendWarning("═══ Đã dừng tác vụ ═══");
        ChatUtils.sendInfo("Trạng thái trước: §f" + previousState);
        ChatUtils.sendInfo("Tổng đã bán: §f" + itemsSold + " lần");
    }

    public boolean isRunning() {
        return state != SellState.IDLE && state != SellState.FINISHED && state != SellState.ERROR;
    }

    public SellState getState() { return state; }
    public int getItemsSold() { return itemsSold; }

    // ========================= Tick Handler =========================

    public void tick() {
        if (!isRunning()) return;

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.world == null) {
            if (config.chatNotifications) ChatUtils.sendError("Đã ngắt kết nối! Dừng tác vụ.");
            state = SellState.IDLE;
            return;
        }

        String currentWorldKey = mc.world.getRegistryKey().getValue().toString();
        if (lastWorldKey != null && !lastWorldKey.equals(currentWorldKey)) {
            if (config.chatNotifications) ChatUtils.sendWarning("Đã chuyển world/dimension! Dừng tác vụ.");
            state = SellState.IDLE;
            return;
        }
        lastWorldKey = currentWorldKey;

        switch (state) {
            case PREPARING_ITEM       -> handlePreparingItem(mc);
            case ADJUSTING_QUANTITY   -> handleAdjustingQuantity(mc);
            case SWITCHING_HOTBAR     -> handleSwitchingHotbar();
            case SENDING_COMMAND      -> handleSendingCommand(mc);
            case WAITING_FOR_GUI      -> handleWaitingForGui(mc);
            case CLICKING_CONFIRM     -> handleClickingConfirm(mc);
            case COOLDOWN             -> handleCooldown(mc);
            case FETCHING_ORDER            -> handleFetchingOrder(mc);
            case WAITING_ORDER_GUI         -> handleWaitingOrderGui(mc);
            case NAVIGATING_TO_ORDER_EDIT  -> handleNavigatingToOrderEdit(mc);
            case NAVIGATING_TO_COLLECT     -> handleNavigatingToCollect(mc);
            case COLLECTING_ORDER_ITEMS    -> handleCollectingOrderItems(mc);
            case WAITING_FOR_AH_SLOT       -> handleWaitingAhSlot();
            default -> { /* IDLE, FINISHED, ERROR */ }
        }
    }

    public void onDisconnect() {
        if (isRunning()) {
            state = SellState.IDLE;
            tickCounter = 0;
            isOnBreak = false;
        }
    }

    // ========================= Core Sell States =========================

    private void handlePreparingItem(MinecraftClient mc) {
        int totalCount = InventoryUtils.getTotalCount(config.targetItem);
        if (totalCount == 0) {
            if (config.autoOrder && !hasTriedOrder) {
                ChatUtils.sendInfo("📦 Hết đồ! Đang lấy thêm từ /" + config.orderCommand + "...");
                hasTriedOrder = true;
                state = SellState.FETCHING_ORDER;
                tickCounter = 0;
                return;
            }
            ChatUtils.sendSuccess("═══ Đã bán hết tất cả item! ═══");
            ChatUtils.sendInfo("Tổng: §f" + itemsSold + " lần bán");
            state = SellState.FINISHED;
            return;
        }

        hasTriedOrder = false;

        // Close any stale screen
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            tickCounter = 0;
            return;
        }

        if (InventoryUtils.isHoldingTargetItem(config.targetItem)) {
            int mainHandCount = InventoryUtils.getMainHandCount(config.targetItem);

            if (mainHandCount == config.desiredQuantity) {
                if (config.chatNotifications) {
                    ChatUtils.sendAction("Item sẵn sàng: " + config.desiredQuantity + "x " + config.targetItem);
                }
                state = SellState.SENDING_COMMAND;
                tickCounter = 0;

            } else if (mainHandCount > config.desiredQuantity) {
                adjustDropCount = mainHandCount - config.desiredQuantity;
                if (config.chatNotifications) {
                    ChatUtils.sendInfo("Giảm số lượng: " + mainHandCount + " → " + config.desiredQuantity);
                }
                state = SellState.ADJUSTING_QUANTITY;
                tickCounter = 0;

            } else {
                int goodSlot = InventoryUtils.findSlotWithMinCount(
                        config.targetItem, config.desiredQuantity,
                        mc.player.getInventory().selectedSlot);
                if (goodSlot >= 0) {
                    if (config.chatNotifications) {
                        ChatUtils.sendInfo("Tìm thấy stack phù hợp tại slot " + goodSlot + ", đang chuyển...");
                    }
                    InventoryUtils.swapToMainHand(goodSlot);
                    state = SellState.SWITCHING_HOTBAR;
                    tickCounter = 0;
                } else {
                    if (config.chatNotifications) {
                        ChatUtils.sendWarning("Không đủ " + config.desiredQuantity + " item, bán với " + mainHandCount);
                    }
                    state = SellState.SENDING_COMMAND;
                    tickCounter = 0;
                }
            }
        } else {
            int slot = InventoryUtils.findItemSlot(config.targetItem);
            if (slot >= 0) {
                if (config.chatNotifications) {
                    ChatUtils.sendAction("Chuyển item từ slot " + slot + " lên tay...");
                }
                InventoryUtils.swapToMainHand(slot);
                state = SellState.SWITCHING_HOTBAR;
                tickCounter = 0;
            } else {
                retryCount++;
                if (retryCount > MAX_RETRIES) {
                    ChatUtils.sendError("Lỗi: Không thể tìm item sau " + MAX_RETRIES + " lần thử!");
                    state = SellState.ERROR;
                }
            }
        }
    }

    private void handleAdjustingQuantity(MinecraftClient mc) {
        if (mc.player == null) return;
        int selectedSlot = mc.player.getInventory().selectedSlot;
        int screenSlot = InventoryUtils.toScreenSlot(selectedSlot);

        if (adjustDropCount <= 0) {
            if (config.chatNotifications) ChatUtils.sendInfo("Đã điều chỉnh xong số lượng.");
            state = SellState.SENDING_COMMAND;
            tickCounter = 0;
            return;
        }

        if (tickCounter == 0) {
            if (InventoryUtils.isHoldingTargetItem(config.targetItem)) {
                InventoryUtils.clickScreenSlot(screenSlot, 0, SlotActionType.PICKUP);
                tickCounter = 1;
            } else {
                state = SellState.PREPARING_ITEM;
                tickCounter = 0;
                adjustDropCount = 0;
            }
        } else if (tickCounter == 1) {
            for (int i = 0; i < config.desiredQuantity; i++) {
                InventoryUtils.clickScreenSlot(screenSlot, 1, SlotActionType.PICKUP);
            }
            tickCounter = 2;
        } else if (tickCounter == 2) {
            int emptySlot = InventoryUtils.findEmptySlot();
            if (emptySlot != -1) {
                int emptyScreenSlot = InventoryUtils.toScreenSlot(emptySlot);
                InventoryUtils.clickScreenSlot(emptyScreenSlot, 0, SlotActionType.PICKUP);
                if (config.chatNotifications) ChatUtils.sendInfo("Đã chuyển đồ thừa vào slot " + emptySlot);
                itemPickupProtectionDelay = 0;
            } else {
                InventoryUtils.clickScreenSlot(-999, 0, SlotActionType.PICKUP);
                if (config.chatNotifications) ChatUtils.sendWarning("Kho đầy! Vứt đồ thừa xuống đất.");
                itemPickupProtectionDelay = 10 + (int) (Math.random() * 11);
            }
            adjustDropCount = 0;
            tickCounter = 3;
        } else {
            state = SellState.SENDING_COMMAND;
            tickCounter = 0;
        }
    }

    private void handleSwitchingHotbar() {
        tickCounter++;
        if (tickCounter >= 5) {
            retryCount = 0;
            state = SellState.PREPARING_ITEM;
            tickCounter = 0;
        }
    }

    private void handleSendingCommand(MinecraftClient mc) {
        if (tickCounter == 0) {
            currentCommandDelay = randomizeDelay(config.commandDelay);
        }
        tickCounter++;
        if (tickCounter < currentCommandDelay) return;

        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            tickCounter = currentCommandDelay - 5;
            return;
        }

        int sellPrice = this.price;

        String command = "ah sell " + sellPrice;
        if (config.chatNotifications) ChatUtils.sendAction("Gửi lệnh: /" + command);

        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendCommand(command);
        }

        state = SellState.WAITING_FOR_GUI;
        tickCounter = 0;
    }

    private void handleWaitingForGui(MinecraftClient mc) {
        tickCounter++;

        if (tickCounter > config.guiTimeout) {
            if (config.chatNotifications)
                ChatUtils.sendWarning("Timeout chờ GUI! Chuyển sang item tiếp theo...");
            state = SellState.COOLDOWN;
            tickCounter = 0;
            itemsSold++;
            return;
        }

        if (mc.currentScreen instanceof HandledScreen<?>) {
            if (!config.guiTitleContains.isEmpty()) {
                String title = mc.currentScreen.getTitle().getString();
                if (!title.toLowerCase().contains(config.guiTitleContains.toLowerCase())) {
                    return;
                }
            }
            if (config.chatNotifications) ChatUtils.sendInfo("GUI đã mở! Chuẩn bị xác nhận...");
            if (config.autoConfirmGui) {
                state = SellState.CLICKING_CONFIRM;
                tickCounter = 0;
            } else {
                ChatUtils.sendWarning("Chế độ thủ công: hãy tự xác nhận trong GUI.");
                state = SellState.COOLDOWN;
                tickCounter = 0;
            }
        }
    }

    private void handleClickingConfirm(MinecraftClient mc) {
        if (tickCounter == 0) {
            currentGuiClickDelay = randomizeDelay(config.guiClickDelay);
        }
        tickCounter++;
        if (tickCounter < currentGuiClickDelay) return;

        if (mc.currentScreen instanceof HandledScreen<?>) {
            int slotToClick = -1;

            if (mc.player != null && mc.player.currentScreenHandler != null) {
                int totalSlots = mc.player.currentScreenHandler.slots.size();
                int containerSize = totalSlots - 36;
                for (int i = 0; i < containerSize; i++) {
                    net.minecraft.screen.slot.Slot slot = mc.player.currentScreenHandler.getSlot(i);
                    if (slot != null && slot.hasStack()) {
                        ItemStack stack = slot.getStack();
                        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

                        // Match green/lime stained glass pane (confirm button)
                        if (itemId.equals("minecraft:green_stained_glass_pane") ||
                            itemId.equals("minecraft:lime_stained_glass_pane")) {
                            slotToClick = i;
                            break;
                        }

                        // Match by display name keywords
                        String displayName = stack.getName().getString().toLowerCase();
                        if (displayName.contains("xác nhận") || displayName.contains("confirm") ||
                            displayName.contains("đồng ý") || displayName.contains("chấp nhận")) {
                            slotToClick = i;
                            break;
                        }
                    }
                }
            }

            if (slotToClick == -1) slotToClick = config.confirmSlotIndex;

            InventoryUtils.clickScreenSlot(slotToClick);
            if (config.chatNotifications) ChatUtils.sendSuccess("✅ Xác nhận bán tại slot " + slotToClick);
            itemsSold++;
        } else {
            if (config.chatNotifications)
                ChatUtils.sendWarning("GUI đóng trước khi click! Có thể đã bán thành công.");
            itemsSold++;
        }

        state = SellState.COOLDOWN;
        tickCounter = 0;
    }

    private void handleCooldown(MinecraftClient mc) {
        // Handle break state separately (fixed break bug)
        if (isOnBreak) {
            breakTicksRemaining--;
            if (breakTicksRemaining <= 0) {
                isOnBreak = false;
                ChatUtils.sendInfo("☕ Nghỉ giải lao xong! Tiếp tục bán...");
                state = SellState.PREPARING_ITEM;
                tickCounter = 0;
            }
            return;
        }

        if (tickCounter == 0) {
            currentItemDelay = randomizeDelay(config.itemDelay) + itemPickupProtectionDelay;
            itemPickupProtectionDelay = 0;
        }
        tickCounter++;

        // Close screen after short delay if still open
        if (tickCounter == 10 && mc.currentScreen != null) {
            mc.player.closeHandledScreen();
        }

        if (tickCounter >= currentItemDelay) {
            if (config.chatNotifications) {
                int remaining = InventoryUtils.getTotalCount(config.targetItem);
                ChatUtils.sendInfo("Đã bán: §f" + itemsSold + " §7| Còn lại: §f" + remaining);
            }
            retryCount = 0;

            // Anti-ban break simulator
            sellsSinceLastBreak++;
            if (sellsSinceLastBreak >= sellsTargetForBreak) {
                sellsSinceLastBreak = 0;
                sellsTargetForBreak = 10 + (int) (Math.random() * 11);
                breakTicksRemaining = 100 + (int) (Math.random() * 101); // 5–10 seconds
                isOnBreak = true;
                ChatUtils.sendWarning("☕ Nghỉ giải lao "
                        + String.format("%.1f", breakTicksRemaining / 20.0) + "s...");
                return;
            }

            state = SellState.PREPARING_ITEM;
            tickCounter = 0;
        }
    }

    // ========================= Order Fetching States =========================

    /**
     * Bước 1: Đóng mọi GUI đang mở, đợi 10 tick rồi gửi lệnh /order.
     */
    private void handleFetchingOrder(MinecraftClient mc) {
        if (mc.currentScreen != null) {
            mc.player.closeHandledScreen();
            tickCounter = 0;
            return;
        }

        tickCounter++;
        if (tickCounter < 10) return;

        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendCommand(config.orderCommand);
        }
        if (config.chatNotifications) ChatUtils.sendAction("Gửi lệnh: /" + config.orderCommand);

        state = SellState.WAITING_ORDER_GUI;
        tickCounter = 0;
        orderFoundSlot = -1;
    }

    /**
     * Bước 1→2: GUI chính của /order đã mở.
     * Tìm nút "Your Orders" và click vào.
     */
    private void handleWaitingOrderGui(MinecraftClient mc) {
        tickCounter++;

        if (tickCounter > config.guiTimeout) {
            ChatUtils.sendWarning("Timeout chờ GUI order! Dừng tác vụ.");
            if (mc.player != null) mc.player.closeHandledScreen();
            state = SellState.FINISHED;
            tickCounter = 0;
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?>)) return;
        if (tickCounter < 8) return; // chờ GUI render
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        String title = mc.currentScreen.getTitle().getString();
        if (config.chatNotifications) ChatUtils.sendInfo("GUI order đã mở: §f" + title);

        int totalSlots = mc.player.currentScreenHandler.slots.size();
        int containerSize = totalSlots - 36;

        // Tìm nút "Your Orders": item có tên chứa "your orders" (không phân biệt hoa/thường)
        for (int i = 0; i < containerSize; i++) {
            net.minecraft.screen.slot.Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (slot == null || !slot.hasStack() || slot.getStack().isEmpty()) continue;

            String displayName = slot.getStack().getName().getString().toLowerCase();
            if (displayName.contains("your orders") || displayName.contains("your order")) {
                if (config.chatNotifications)
                    ChatUtils.sendInfo("Click nút §f" + slot.getStack().getName().getString()
                            + " §7(slot " + i + ")");
                InventoryUtils.clickScreenSlot(i);
                state = SellState.NAVIGATING_TO_ORDER_EDIT;
                tickCounter = 0;
                return;
            }
        }

        // Chưa thấy nút "Your Orders" → tiếp tục chờ (GUI có thể chưa render xong)
    }

    /**
     * Bước 2→3: Màn hình "Your Orders" đang hiển thị danh sách các order.
     * Tìm item khớp với config.targetItem và click vào.
     */
    private void handleNavigatingToOrderEdit(MinecraftClient mc) {
        tickCounter++;

        if (tickCounter > config.guiTimeout) {
            ChatUtils.sendWarning("Timeout chờ màn hình Your Orders! Dừng tác vụ.");
            if (mc.player != null) mc.player.closeHandledScreen();
            state = SellState.FINISHED;
            tickCounter = 0;
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            tickCounter = 0;
            return;
        }

        if (tickCounter < 5) return; // chờ GUI render
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        int totalSlots = mc.player.currentScreenHandler.slots.size();
        int containerSize = totalSlots - 36;

        // Tìm item khớp với target (blast_furnace) trong danh sách order
        for (int i = 0; i < containerSize; i++) {
            net.minecraft.screen.slot.Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (slot == null || !slot.hasStack() || slot.getStack().isEmpty()) continue;

            String itemId = InventoryUtils.getItemId(slot.getStack());
            if (itemId.equals(config.targetItem)) {
                if (config.chatNotifications)
                    ChatUtils.sendInfo("Chọn order §f" + slot.getStack().getName().getString()
                            + " §7(slot " + i + ")");
                InventoryUtils.clickScreenSlot(i);
                orderFoundSlot = i;
                state = SellState.NAVIGATING_TO_COLLECT;
                tickCounter = 0;
                return;
            }
        }

        // Chưa thấy item target → tiếp tục chờ
    }

    /**
     * Bước 3→4: Màn hình chi tiết order đã mở.
     * Tìm nút "Collect" và click vào.
     */
    private void handleNavigatingToCollect(MinecraftClient mc) {
        tickCounter++;

        if (tickCounter > config.guiTimeout) {
            ChatUtils.sendWarning("Timeout chờ nút Collect! Dừng tác vụ.");
            if (mc.player != null) mc.player.closeHandledScreen();
            state = SellState.FINISHED;
            tickCounter = 0;
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            // GUI đóng bất ngờ → có thể server đã tự collect
            int totalCount = InventoryUtils.getTotalCount(config.targetItem);
            if (totalCount > 0) {
                ChatUtils.sendSuccess("Đã lấy được §f" + totalCount + " §7item (server tự collect)!");
                state = SellState.PREPARING_ITEM;
            } else {
                ChatUtils.sendWarning("GUI đóng bất ngờ, không lấy được item. Dừng.");
                state = SellState.FINISHED;
            }
            tickCounter = 0;
            return;
        }

        if (tickCounter < 5) return; // chờ GUI render
        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        int totalSlots = mc.player.currentScreenHandler.slots.size();
        int containerSize = totalSlots - 36;

        // Tìm nút Collect: tên hoặc lore chứa "collect"
        for (int i = 0; i < containerSize; i++) {
            net.minecraft.screen.slot.Slot slot = mc.player.currentScreenHandler.getSlot(i);
            if (slot == null || !slot.hasStack() || slot.getStack().isEmpty()) continue;

            ItemStack stack = slot.getStack();
            String displayName = stack.getName().getString().toLowerCase();

            boolean isCollect = displayName.contains("collect");
            if (!isCollect) {
                for (net.minecraft.text.Text loreLine : InventoryUtils.getItemLore(stack)) {
                    if (loreLine.getString().toLowerCase().contains("collect")) {
                        isCollect = true;
                        break;
                    }
                }
            }

            if (isCollect) {
                if (config.chatNotifications)
                    ChatUtils.sendInfo("Nhấn §fCollect §7(slot " + i + ")");
                InventoryUtils.clickScreenSlot(i);
                // Sau khi click Collect, GUI mới sẽ mở ra
                orderCollectIndex = 0;
                state = SellState.COLLECTING_ORDER_ITEMS;
                tickCounter = 0;
                return;
            }
        }

        // Chưa thấy nút Collect → tiếp tục chờ
    }

    /**
     * Bước 5: Màn hình "Collect Items" đang mở.
     * Shift+Click từng slot để chuyển nhanh toàn bộ đồ về inventory.
     * Sau khi hết slot → đóng GUI và quay lại bán.
     */
    private void handleCollectingOrderItems(MinecraftClient mc) {
        tickCounter++;

        // GUI đóng → chờ inventory sync rồi mới kiểm tra
        if (!(mc.currentScreen instanceof HandledScreen<?>)) {
            // orderCollectIndex == -999: đã lấy xong, đang chờ server→client sync
            // orderCollectIndex != -999: GUI đóng bất ngờ giữa chừng
            if (tickCounter < 25) return; // chờ 25 tick (~1.25s) cho packet inventory về

            int totalCount = InventoryUtils.getTotalCount(config.targetItem);
            if (totalCount > 0) {
                ChatUtils.sendSuccess("Đã lấy được §f" + totalCount + " §7item từ order! Tiếp tục bán...");
            } else {
                ChatUtils.sendWarning("Không tìm thấy item trong inventory. Thử tiếp tục...");
            }
            // Luôn để PREPARING_ITEM tự xử lý (sẽ FINISH nếu thực sự hết đồ)
            state = SellState.PREPARING_ITEM;
            tickCounter = 0;
            return;
        }

        // Đợi GUI ổn định
        if (tickCounter < 8) return;
        // Mỗi 3 tick click 1 slot (tránh spam)
        if (tickCounter % 3 != 0) return;

        if (mc.player == null || mc.player.currentScreenHandler == null) return;

        int totalSlots = mc.player.currentScreenHandler.slots.size();
        int containerSize = totalSlots - 36;

        // Theo yêu cầu: Chỉ lấy 1 stack đầu tiên tìm thấy rồi đóng GUI
        for (int i = 0; i < containerSize; i++) {
            net.minecraft.screen.slot.Slot slot = mc.player.currentScreenHandler.getSlot(i);

            if (slot != null && slot.hasStack() && !slot.getStack().isEmpty()) {
                if (config.chatNotifications) {
                    String itemName = slot.getStack().getName().getString();
                    int count = slot.getStack().getCount();
                    ChatUtils.sendInfo("Lấy 1 stack: §f" + count + "x " + itemName
                            + " §7(slot " + i + "). Đóng GUI chờ sync...");
                }
                // Shift+Click 1 lần duy nhất để lấy 1 stack
                InventoryUtils.clickScreenSlot(i, 0, SlotActionType.QUICK_MOVE);
                
                // Đóng GUI ngay lập tức
                mc.player.closeHandledScreen();
                orderCollectIndex = -999; // sentinel: chờ inventory sync
                tickCounter = 0;         // bắt đầu đếm 25 tick delay
                return;
            }
        }

        // Không tìm thấy slot nào có item
        if (config.chatNotifications) ChatUtils.sendWarning("GUI Collect trống. Đóng GUI...");
        mc.player.closeHandledScreen();
        orderCollectIndex = -999;
        tickCounter = 0;
    }

    // ========================= Helpers =========================

    /**
     * Kiểm tra xem item có phải decoration (glass pane, arrow, barrier, air) không.
     * Dùng để lọc các slot trang trí trong GUI.
     */
    private boolean isNotDecoration(String itemId) {
        if (itemId == null) return false;
        return !itemId.contains("glass_pane")
            && !itemId.contains("arrow")
            && !itemId.contains("barrier")
            && !itemId.contains("air")
            && !itemId.contains("gray_stained")
            && !itemId.contains("black_stained")
            && !itemId.contains("white_stained");
    }

    // ========================= AH Full Handler =========================

    private void handleWaitingAhSlot() {
        ahFullWaitTicks--;
        
        if (ahFullWaitTicks % 1200 == 0 && ahFullWaitTicks > 0) {
            int minutesLeft = (ahFullWaitTicks / 20) / 60;
            if (config.chatNotifications) {
                ChatUtils.sendInfo("Đang chờ gian hàng... (Sẽ tự dậy sau khoảng " + minutesLeft + " phút, hoặc khi có người mua).");
            }
        }

        if (ahFullWaitTicks <= 0) {
            if (config.chatNotifications) {
                ChatUtils.sendSuccess("Đã hết thời gian AFK, đang thử thức dậy bán lại...");
            }
            state = SellState.PREPARING_ITEM;
            tickCounter = 0;
        }
    }

    public void triggerAhFull() {
        if (isRunning() && state != SellState.WAITING_FOR_AH_SLOT) {
            state = SellState.WAITING_FOR_AH_SLOT;
            
            // Chờ ngẫu nhiên từ 3 đến 4 phút (3600 đến 4800 ticks)
            ahFullWaitTicks = 3600 + new java.util.Random().nextInt(1201); 
            
            int minutes = (ahFullWaitTicks / 20) / 60;
            int seconds = (ahFullWaitTicks / 20) % 60;

            if (config.chatNotifications) {
                ChatUtils.sendWarning("Gian hàng ĐẦY! Đóng băng bot trong " + minutes + " phút " + seconds + " giây...");
            }
        }
    }

    /** 
     * Được gọi khi bắt được chat báo có người mua hoặc đồ hết hạn 
     */
    public void triggerItemSold() {
        if (state == SellState.WAITING_FOR_AH_SLOT) {
            state = SellState.PREPARING_ITEM;
            tickCounter = 0;
            ahFullWaitTicks = 0;
            
            if (config.chatNotifications) {
                ChatUtils.sendSuccess("Slot chợ đã trống! Giật mình tỉnh dậy bán tiếp...");
            }
        }
    }

    // ========================= Alert =========================

    public void stopAndAlert(String reason) {
        if (!isRunning()) return;
        stop();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            for (int i = 0; i < 5; i++) {
                mc.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_ANVIL_LAND, 1.0f, 1.0f);
            }
            ChatUtils.sendError("🚨 CẢNH BÁO: " + reason);
        }
    }
}
