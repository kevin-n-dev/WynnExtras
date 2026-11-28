package julianh06.wynnextras.features.misc;

import julianh06.wynnextras.config.WynnExtrasConfig;
import julianh06.wynnextras.core.WynnExtras;
import julianh06.wynnextras.utils.MinecraftUtils;
import julianh06.wynnextras.wynncraft.item.WynnItemData;
import julianh06.wynnextras.wynncraft.item.WynnItemParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class DurabilityHud {
    private static final int POPUP_TICKS = 100;
    private static final int POPUP_FADE_TICKS = 20;
    private static final int BROKEN_TICKS = 70;
    private static final int BROKEN_FADE_TICKS = 20;
    private static final Pattern DURABILITY_AFTER_LABEL = Pattern.compile("(?i)(durability\\D*)\\d+(\\s*/\\s*\\d+)");
    private static final Pattern DURABILITY_BEFORE_LABEL = Pattern.compile("(?i)\\d+(\\s*/\\s*\\d+)(\\D{0,8}durability)");

    private record DisplayItem(ItemStack stack, WynnItemData.Amount durability, String text) {}
    private record WarningItem(Item item, int componentsHash, int count, String key, String name) {}

    private static List<List<DisplayItem>> currentColumns = List.of();
    private static final Map<String, WarningItem> warningItemCache = new HashMap<>();
    private static final Set<String> warnedItems = new HashSet<>();
    private static final Set<String> brokenItems = new HashSet<>();
    private static String popupText = null;
    private static int popupTicksLeft = 0;
    private static String brokenText = null;
    private static int brokenTicksLeft = 0;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(DurabilityHud::tick);
        HudRenderCallback.EVENT.register(DurabilityHud::render);
    }

    private static void tick(MinecraftClient client) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        if (popupTicksLeft > 0) popupTicksLeft--;
        if (brokenTicksLeft > 0) brokenTicksLeft--;
        currentColumns = List.of();
        if (client.player == null) {
            warnedItems.clear();
            brokenItems.clear();
            warningItemCache.clear();
            return;
        }
        if (!cfg.durabilityHudToggle) return;

        List<List<DisplayItem>> columns = new ArrayList<>();
        for (List<String> configuredColumn : cfg.getDurabilityItemColumns()) {
            List<DisplayItem> items = new ArrayList<>();
            for (String itemId : configuredColumn) {
                ItemStack stack = stackFor(client, itemId);
                WynnItemData.Amount durability = WynnItemParser.parse(stack).flatMap(d -> d.durability()).orElse(null);
                if (durability == null) continue;
                items.add(new DisplayItem(stack, durability, displayText(cfg, durability)));
                updateWarnings(cfg, itemId, stack, durability);
            }
            columns.add(List.copyOf(items));
        }
        currentColumns = List.copyOf(columns);
    }

    private static ItemStack stackFor(MinecraftClient client, String itemId) {
        return switch (itemId) {
            case WynnExtrasConfig.DURABILITY_ITEM_WEAPON -> client.player.getMainHandStack();
            case WynnExtrasConfig.DURABILITY_ITEM_HELMET -> client.player.getEquippedStack(EquipmentSlot.HEAD);
            case WynnExtrasConfig.DURABILITY_ITEM_CHESTPLATE -> client.player.getEquippedStack(EquipmentSlot.CHEST);
            case WynnExtrasConfig.DURABILITY_ITEM_LEGGINGS -> client.player.getEquippedStack(EquipmentSlot.LEGS);
            case WynnExtrasConfig.DURABILITY_ITEM_BOOTS -> client.player.getEquippedStack(EquipmentSlot.FEET);
            case WynnExtrasConfig.DURABILITY_ITEM_RING_1 -> client.player.getInventory().getStack(9);
            case WynnExtrasConfig.DURABILITY_ITEM_RING_2 -> client.player.getInventory().getStack(10);
            case WynnExtrasConfig.DURABILITY_ITEM_BRACELET -> client.player.getInventory().getStack(11);
            case WynnExtrasConfig.DURABILITY_ITEM_NECKLACE -> client.player.getInventory().getStack(12);
            default -> ItemStack.EMPTY;
        };
    }

    private static void updateWarnings(WynnExtrasConfig cfg, String itemId, ItemStack stack,
                                       WynnItemData.Amount durability) {
        WarningItem warningItem = warningItem(itemId, stack);
        String name = warningItem.name();
        String itemKey = warningItem.key();
        boolean broken = durability.current() <= 0;
        boolean low = durability.percentage() <= cfg.durabilityWarnPercent;

        if (!broken) brokenItems.remove(itemKey);
        if (!low) warnedItems.remove(itemKey);

        if (broken && cfg.durabilityBrokenToggle) {
            if (brokenItems.add(itemKey)) {
                warnedItems.add(itemKey);
                brokenText = name;
                brokenTicksLeft = BROKEN_TICKS;
                MinecraftUtils.sendMessageToClient(WynnExtras.addWynnExtrasPrefix(
                        "§4§l" + name + " §cis BROKEN! §7Repair it before you continue."));
                playSound(SoundEvents.BLOCK_ANVIL_USE, 1.0f);
            }
            return;
        }

        if (low && cfg.durabilityWarnToggle && warnedItems.add(itemKey)) {
            String text = name + " durability is low: " + durability.current() + "/" + durability.maximum()
                    + " (" + durability.percentage() + "%)";
            if (cfg.durabilityWarnChat) {
                MinecraftUtils.sendMessageToClient(WynnExtras.addWynnExtrasPrefix("§c" + text));
            }
            if (cfg.durabilityWarnPopup) {
                popupText = text;
                popupTicksLeft = POPUP_TICKS;
            }
            if (cfg.durabilityWarnSound) playSound(SoundEvents.BLOCK_ANVIL_USE, 1.0f);
        }
    }

    private static WarningItem warningItem(String itemId, ItemStack stack) {
        int componentsHash = stack.getComponents().hashCode();
        WarningItem cached = warningItemCache.get(itemId);
        if (cached != null && cached.item() == stack.getItem()
                && cached.componentsHash() == componentsHash && cached.count() == stack.getCount()) {
            return cached;
        }
        WarningItem result = new WarningItem(stack.getItem(), componentsHash, stack.getCount(),
                itemId + "|" + itemKey(stack), cleanItemName(stack));
        warningItemCache.put(itemId, result);
        return result;
    }

    private static String itemKey(ItemStack stack) {
        ItemStack keyStack = stack.copy();
        CustomModelDataComponent modelData = keyStack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData != null && modelData.floats().size() > 1) {
            float durabilityModelData = modelData.floats().get(1);
            if (durabilityModelData >= 1 && durabilityModelData <= 15) {
                List<Float> floats = new ArrayList<>(modelData.floats());
                floats.remove(1);
                keyStack.set(DataComponentTypes.CUSTOM_MODEL_DATA, new CustomModelDataComponent(
                        floats,
                        new ArrayList<>(modelData.flags()),
                        new ArrayList<>(modelData.strings()),
                        new ArrayList<>(modelData.colors())
                ));
            }
        }

        String components = keyStack.getComponents().toString();
        components = DURABILITY_AFTER_LABEL.matcher(components).replaceAll("$1*$2");
        components = DURABILITY_BEFORE_LABEL.matcher(components).replaceAll("*$1$2");
        return stack.getItem() + "|" + components;
    }

    private static void playSound(SoundEvent sound, float pitch) {
        MinecraftUtils.playSoundAmbient(sound, 1.0f, pitch);
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.durabilityHudToggle || mc.player == null || mc.options.hudHidden) return;

        renderPopup(ctx, mc);
        renderBroken(ctx, mc);
        for (int column = 0; column < currentColumns.size(); column++) {
            List<DisplayItem> items = currentColumns.get(column);
            if (items.isEmpty()) continue;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cfg.getDurabilityHudColumnX(column), cfg.getDurabilityHudColumnY(column));
            ctx.getMatrices().scale(cfg.durabilityHudScale, cfg.durabilityHudScale);
            int contentWidth = items.stream()
                    .mapToInt(item -> 20 + mc.textRenderer.getWidth(item.text()))
                    .max().orElse(0);
            WynnExtrasConfig.Align alignment = cfg.getDurabilityHudColumnAlignment(column);
            ctx.getMatrices().translate(switch (alignment) {
                case CENTER -> -contentWidth / 2f;
                case RIGHT -> -contentWidth;
                default -> 0;
            }, 0);
            boolean growUp = cfg.getDurabilityHudColumnGrowDirection(column) == WynnExtrasConfig.GrowDirection.UP;
            int y = growUp ? -(items.size() - 1) * 18 : 0;
            for (DisplayItem item : items) {
                WynnItemData.Amount durability = item.durability();
                String text = item.text();
                int rowWidth = 20 + mc.textRenderer.getWidth(text);
                int rowX = switch (alignment) {
                    case CENTER -> (contentWidth - rowWidth) / 2;
                    case RIGHT -> contentWidth - rowWidth;
                    default -> 0;
                };
                boolean low = cfg.durabilityWarnToggle && durability.percentage() <= cfg.durabilityWarnPercent;
                ctx.drawItem(item.stack(), rowX, y);
                ctx.drawText(mc.textRenderer, text, rowX + 20, y + 4, low ? 0xFFFF5555 : 0xFFFFFFFF, true);
                y += 18;
            }
            ctx.getMatrices().popMatrix();
        }
    }

    private static String displayText(WynnExtrasConfig config, WynnItemData.Amount durability) {
        return switch (config.durabilityFormat) {
            case RAW -> durability.current() + "/" + durability.maximum();
            case PERCENT -> durability.percentage() + "%";
            case BOTH -> durability.current() + "/" + durability.maximum() + " (" + durability.percentage() + "%)";
        };
    }

    private static void renderPopup(DrawContext ctx, MinecraftClient mc) {
        if (popupText == null || popupTicksLeft <= 0) return;
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        int alpha = fade(popupTicksLeft, POPUP_FADE_TICKS);
        int w = mc.textRenderer.getWidth(popupText);
        int x = cfg.durabilityWarnHudX == -1
                ? mc.getWindow().getScaledWidth() / 2 : cfg.durabilityWarnHudX;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, cfg.durabilityWarnHudY);
        ctx.getMatrices().scale(cfg.durabilityWarnHudScale, cfg.durabilityWarnHudScale);
        ctx.drawText(mc.textRenderer, popupText, -w / 2, 0,
                (alpha << 24) | 0xFF5555, true);
        ctx.getMatrices().popMatrix();
    }

    private static void renderBroken(DrawContext ctx, MinecraftClient mc) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        boolean persistent = cfg.durabilityBrokenToggle && cfg.durabilityBrokenPersistent
                && currentColumns.stream().flatMap(List::stream)
                .anyMatch(item -> item.durability().current() <= 0);

        String itemName;
        int alpha;
        if (persistent) {
            itemName = currentColumns.stream().flatMap(List::stream)
                    .filter(item -> item.durability().current() <= 0)
                    .findFirst().map(item -> cleanItemName(item.stack())).orElseThrow();
            alpha = 212 + (int) (43 * Math.sin(System.currentTimeMillis() / 250.0));
        } else if (brokenText != null && brokenTicksLeft > 0) {
            itemName = brokenText;
            alpha = fade(brokenTicksLeft, BROKEN_FADE_TICKS);
        } else {
            return;
        }

        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        String title = "BROKEN";
        String sub = itemName + " has 0 durability";
        int fontH = mc.textRenderer.fontHeight;
        int titleW = mc.textRenderer.getWidth(title);
        int subW = mc.textRenderer.getWidth(sub);
        float titleScale = Math.min(12f, screenW * 0.6f / titleW);
        float subScale = 2f;

        float titleH = fontH * titleScale;
        float subH = fontH * subScale;
        float gap = 8f;
        float top = screenH / 2f - (titleH + gap + subH) / 2f;

        int bandAlpha = Math.max(4, alpha * 0x88 / 255);
        ctx.fill(0, (int) (top - 14), screenW, (int) (top + titleH + gap + subH + 14), (bandAlpha << 24) | 0x300000);

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(screenW / 2f, top);
        ctx.getMatrices().scale(titleScale, titleScale);
        ctx.drawText(mc.textRenderer, title, -titleW / 2, 0, (alpha << 24) | 0xFF2222, true);
        ctx.getMatrices().popMatrix();

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(screenW / 2f, top + titleH + gap);
        ctx.getMatrices().scale(subScale, subScale);
        ctx.drawText(mc.textRenderer, sub, -subW / 2, 0, (alpha << 24) | 0xFFFFFF, true);
        ctx.getMatrices().popMatrix();
    }

    private static int fade(int ticksLeft, int fadeTicks) {
        // Very low alpha renders text incorrectly.
        return ticksLeft >= fadeTicks ? 255 : Math.max(8, ticksLeft * 255 / fadeTicks);
    }

    private static String cleanItemName(ItemStack stack) {
        return stack.getName().getString()
                .replace("À", "")
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("[^\\x20-\\x7E]", "")
                .replaceAll("^\\s*Shiny\\s+", "")
                .strip();
    }
}
