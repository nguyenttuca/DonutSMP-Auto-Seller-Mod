package com.donutsell.task;

/**
 * All possible states for the auto-sell task state machine.
 */
public enum SellState {
    /** No task running */
    IDLE,
    /** Finding and preparing the target item */
    PREPARING_ITEM,
    /** Adjusting item count in mainhand (dropping excess) */
    ADJUSTING_QUANTITY,
    /** Waiting for hotbar swap to sync */
    SWITCHING_HOTBAR,
    /** Sending /ah sell command */
    SENDING_COMMAND,
    /** Waiting for auction GUI to open */
    WAITING_FOR_GUI,
    /** Clicking confirm button in GUI */
    CLICKING_CONFIRM,
    /** Cooldown between sell cycles */
    COOLDOWN,

    // ========== Order Fetching States ==========
    /** Sending /order command to fetch items */
    FETCHING_ORDER,
    /** Waiting for order GUI to open */
    WAITING_ORDER_GUI,
    /** Clicking items in order GUI to collect them */
    COLLECTING_ORDER_ITEMS,

    /** All items sold successfully */
    FINISHED,
    /** Error occurred */
    ERROR
}
