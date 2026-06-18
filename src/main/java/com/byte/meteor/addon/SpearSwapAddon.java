package com.byte.meteor.addon;

import meteordevelopment.meteorclient.*;
import meteordevelopment.meteorclient.events.entity.player.PlayerUseItemEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.item.ItemUtils;
import meteordevelopment.meteorclient.utils.misc.InputUtils;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.world.DistanceUtils;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.registry.Registries;               // <-- ADDED
import net.minecraft.util.Identifier;                 // <-- ADDED
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * SpearSwapAddon – Meteor Client 1.21.11 Fabric addon.
 * When the player swings (left‑click) while the item in the configured hot‑bar slot
 * matches a spear (configurable list), the addon temporarily replaces it with a
 * random "swap" item (also configurable) for a randomized delay of 2‑6 ticks.
 *
 * All parameters are exposed in the Meteor client settings GUI.
 */
public class SpearSwapAddon extends Module {

    /* ---------- SETTINGS GROUPS ---------- */
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDelay = settings.createGroup("Delay");
    private final SettingGroup sgItems = settings.createGroup("Items");
    private final SettingGroup sgStealth = settings.createGroup("Stealth");

    /* ---------- GENERAL SETTINGS ---------- */
    private final Setting<Integer> hotbarSlot = sgGeneral.add(new IntSetting.Builder()
        .name("hotbar-slot")
        .description("Hot‑bar slot to monitor for spear (1‑9)")
        .defaultValue(6)
        .min(1)
        .max(9)
        .build()
    );

    private final Setting<List<String>> spearItems = sgItems.add(new StringListSetting.Builder()
        .name("spear-items")
        .description("Item IDs considered as spears for swapping")
        .defaultValue(List.of(
            "minecraft:trident",
            "minecraft:wooden_sword",
            "minecraft:stone_sword",
            "minecraft:iron_sword",
            "minecraft:golden_sword",
            "minecraft:diamond_sword",
            "minecraft:netherite_sword"
        ))
        .build()
    );

    private final Setting<List<String>> swapItems = sgItems.add(new StringListSetting.Builder()
        .name("swap-items")
        .description("Item IDs to swap with when spear detected")
        .defaultValue(List.of(
            "minecraft:shield",
            "minecraft:bow",
            "minecraft:fishing_rod",
            "minecraft:carrot_on_a_stick"
        ))
        .build()
    );

    /* ---------- DELAY SETTINGS ---------- */
    private final Setting<Integer> minDelayTicks = sgDelay.add(new IntSetting.Builder()
        .name("min-delay-ticks")
        .description("Minimum delay ticks before swapping back")
        .defaultValue(2)
        .min(1)
        .build()
    );

    private final Setting<Integer> maxDelayTicks = sgDelay.add(new IntSetting.Builder()
        .name("max-delay-ticks")
        .description("Maximum delay ticks before swapping back")
        .defaultValue(6)
        .min(1)
        .build()
    );

    private final Setting<Boolean> randomizeDelay = sgDelay.add(new BoolSetting.Builder()
        .name("randomize-delay")
        .description("Use random delay within the min‑max range")
        .defaultValue(true)
        .build()
    );

    /* ---------- STEALTH SETTINGS ---------- */
    private final Setting<Boolean> onlyWhenSprinting = sgStealth.add(new BoolSetting.Builder()
        .name("only-when-sprinting")
        .description("Only perform swap when sprinting (reduces predictability)")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swapMainHand = sgStealth.add(new BoolSetting.Builder()
        .name("swap-main-hand")
        .description("Swap the main hand instead of offhand")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> playSound = sgStealth.add(new BoolSetting.Builder()
        .name("play-sound")
        .description("Play item swap sound (may increase detectability)")
        .defaultValue(false)
        .build()
    );

    /* ---------- INTERNAL STATE ---------- */
    private int swapTimer = 0;            // ticks remaining before restore
    private int targetDelay = 0;          // the delay we are counting down from
    private boolean isSwapped = false;    // true while the swap is active
    private final Random random = new Random();
    private ItemStack originalItem = ItemStack.EMPTY; // backup of the spear

    public SpearSwapAddon() {
        super("SpearSwap", "Swaps spear in hotbar slot when swung with evasion timing");
    }

    /* ---------- LIFECYCLE ---------- */
    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        if (isSwapped && mc.player != null) {
            restoreOriginalItem();
        }
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        // If we are currently swapped, count down the timer
        if (isSwapped) {
            if (swapTimer <= 0) {
                restoreOriginalItem();
            } else {
                swapTimer--;
            }
            return;
        }

        // Otherwise, look for a swing that triggers a swap
        if (shouldConsiderSwap()) {
            int slotIndex = hotbarSlot.get() - 1; // convert to 0‑based
            if (slotIndex < 0 || slotIndex >= 9) return;

            ItemStack hotbarStack = mc.player.getInventory().main.get(slotIndex);
            if (isSpear(hotbarStack)) {
                prepareSwap(hotbarStack);
            }
        }
    }

    /* ---------- HELPERS ---------- */
    private boolean shouldConsiderSwap() {
        if (onlyWhenSprinting.get() && !mc.player.isSprinting()) return false;
        if (mc.player.isUsingItem() || mc.player.isBlocking()) return false;
        return true;
    }

    private boolean isSpear(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        return spearItems.get().contains(itemId);
    }

    private void prepareSwap(ItemStack spearStack) {
        // Backup the original item
        originalItem = spearStack.copy();

        // Choose a random swap item
        ItemStack swapStack = getRandomSwapItem();
        if (swapStack.isEmpty()) return;

        int slotIndex = hotbarSlot.get() - 1;

        if (swapMainHand.get()) {
            mc.player.getInventory().main.set(slotIndex, swapStack);
        } else {
            // Offhand slot 0 (main offhand)
            mc.player.getInventory().offHand.set(0, swapStack);
        }

        if (playSound.get()) {
            // Play the swap item's use sound (can be silent for stealth)
            mc.player.playSound(swapStack.getItem().useSound, 1.0f, 1.0f);
        }

        isSwapped = true;
        targetDelay = randomizeDelay.get()
                ? random.nextInt(maxDelayTicks.get() - minDelayTicks.get() + 1) + minDelayTicks.get()
                : maxDelayTicks.get();
        swapTimer = targetDelay;
    }

    private ItemStack getRandomSwapItem() {
        List<String> items = swapItems.get();
        if (items.isEmpty()) return ItemStack.EMPTY;

        String chosen = items.get(random.nextInt(items.size()));
        try {
            return new ItemStack(Registries.ITEM.get(new Identifier(chosen)));
        } catch (Exception e) {
            // Fallback to empty stack if ID is invalid
            return ItemStack.EMPTY;
        }
    }

    private void restoreOriginalItem() {
        if (mc.player == null || originalItem.isEmpty()) return;

        int slotIndex = hotbarSlot.get() - 1;

        if (swapMainHand.get()) {
            mc.player.getInventory().main.set(slotIndex, originalItem);
        } else {
            mc.player.getInventory().offHand.set(0, originalItem);
        }

        if (playSound.get()) {
            mc.player.playSound(originalItem.getItem().useSound, 1.0f, 1.0f);
        }

        isSwapped = false;
        originalItem = ItemStack.EMPTY;
        swapTimer = 0;
        targetDelay = 0;
    }

    /* ---------- MIXIN TO HOOK INTO SWING EVENT ---------- */
    @Mixin(PlayerUseItemEvent.Pre.class)
    public static class SwingListener {
        @Inject(method = "<init>(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/Hand;)V",
                at = @At("TAIL"))
        private void onUseItemPre(PlayerUseItemEvent.Pre event, CallbackInfo ci) {
            SpearSwapAddon addon = (SpearSwapAddon) Modules.get().get(SpearSwapAddon.class);
            if (addon.isActive()) {
                // When the player starts using the main hand (i.e., swing), trigger a check
                if (event.hand == Hand.MAIN_HAND && mc.player != null) {
                    addon.onTick(); // reuse the tick logic – cheap and safe
                }
            }
        }
    }

    /* ---------- STATE RESET ---------- */
    private void resetState() {
        swapTimer = 0;
        targetDelay = 0;
        isSwapped = false;
        originalItem = ItemStack.EMPTY;
    }
}
