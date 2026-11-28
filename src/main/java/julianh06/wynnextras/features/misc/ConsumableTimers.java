package julianh06.wynnextras.features.misc;

import julianh06.wynnextras.config.WynnExtrasConfig;
import julianh06.wynnextras.core.WynnExtras;
import julianh06.wynnextras.utils.MinecraftUtils;
import julianh06.wynnextras.wynncraft.item.ItemCategory;
import julianh06.wynnextras.wynncraft.item.WynnItemParser;
import julianh06.wynnextras.wynncraft.state.StatusEffectState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsumableTimers {
    private static final int TAG_WINDOW_TICKS = 160;  //8 seconds, food and potions take a while to actually get consumed
    private static final int POPUP_TICKS = 70;
    private static final int POPUP_FADE_TICKS = 20;
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_TEXT_WIDTH = 180;
    private static final RenderSnapshot EMPTY_RENDER_SNAPSHOT = new RenderSnapshot(List.of(), 0);
    private static final Pattern NON_LETTERS = Pattern.compile("[^a-z ]+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern TIME_PART = Pattern.compile("(\\d+)\\s*(hours?|h|minutes?|min|m|seconds?|sec|s)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_SUFFIX = Pattern.compile("\\s*\\(\\s*(?:\\d+:)?(?:\\d{2,3}|\\*{2}):(?:\\d{2}|\\*{2})\\s*\\)\\s*$");
    private static final int MISSING_GRACE_TICKS = 40;         //2 seconds without a matching entry and we forget the effect
    private static final int DURATION_TOLERANCE_SECONDS = 15;  //how far off the timer can be from our guess and still count as the same effect

    private enum Type {
        POTION, FOOD, SCROLL, OTHER
    }

    //one status effect entry we keep track of. effects are identified by their text (like
    //"+24% Walk Speed") and time left, not just the name, so two items with the same stat don't collide
    private static final class Slot {
        final String name;   //like "Walk Speed"
        final String key;    //display text without the "(mm:ss)", like "+24% Walk Speed"
        long endMillis;      //when we expect it to run out, Long.MAX_VALUE if the tab list has no timer
        int missingTicks = 0;
        Slot(StatusEffectState.Effect e, long now) {
            this.name = e.name(); this.key = keyOf(e); this.endMillis = endOf(e, now);
        }
    }

    private record LoreStat(String text, double number) {}

    //one use of a consumable. a single potion can give a few effects and they all belong to this one use
    private static final class Use {
        final int id;
        final Type type;
        final ItemStack icon;
        final List<LoreStat> stats;
        final int loreSeconds;  //duration written in the item lore, -1 if there isn't one
        final List<Slot> slots = new ArrayList<>();
        int waitedTicks = 0;
        Use(int id, Type type, ItemStack icon, List<LoreStat> stats, int loreSeconds) {
            this.id = id; this.type = type; this.icon = icon; this.stats = stats; this.loreSeconds = loreSeconds;
        }
    }
    private record Row(Use use, int duration, String label) {}
    private record RenderRow(Row row, List<OrderedText> lines, int width, int height) {}
    private record RenderSnapshot(List<RenderRow> rows, int contentWidth) {}
    private record Seen(String key, int duration, long at) {}
    private static final class Popup {
        final String text; int ticksLeft = POPUP_TICKS;
        Popup(String text) { this.text = text; }
    }

    private static final List<Use> uses = new ArrayList<>();  //in drink order, only uses that still have effects
    private static final List<Use> waiting = new ArrayList<>(); //used, but their effects haven't shown up yet (oldest first)
    private static final Set<Integer> warned = new HashSet<>(); //uses we already warned about
    private static final List<Popup> popups = new ArrayList<>();
    private static List<Seen> baseline = List.of();   //what the tab list showed when the first waiting item was used
    private static RenderSnapshot renderSnapshot = EMPTY_RENDER_SNAPSHOT;
    private static List<Row> renderedRows = List.of();
    private static boolean renderedShowEffectName;
    private static int nextId = 0;

    public static void register() {
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient()) onUse(player.getStackInHand(hand));
            return ActionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ConsumableTimers::tick);
        HudRenderCallback.EVENT.register(ConsumableTimers::render);
    }

    private static void onUse(ItemStack stack) {
        if (!WynnExtrasConfig.INSTANCE.consumableTimersToggle || stack == null || stack.isEmpty()) return;
        Type type = classify(stack);
        if (type == null) return;

        if (waiting.isEmpty()) {
            long now = System.currentTimeMillis();
            List<Seen> snapshot = new ArrayList<>();
            for (var e : StatusEffectState.effects()) snapshot.add(new Seen(keyOf(e), e.duration(), now));
            baseline = snapshot;
        }
        waiting.add(new Use(nextId++, type, stack.copyWithCount(1), loreStats(stack), loreSeconds(stack)));
    }

    private static void tick(MinecraftClient client) {
        if (client.player == null || !WynnExtrasConfig.INSTANCE.consumableTimersToggle) {
            clearState();
            return;
        }

        long now = System.currentTimeMillis();
        List<StatusEffectState.Effect> entries = StatusEffectState.effects();
        boolean[] taken = new boolean[entries.size()];

        //1. match every effect we follow to an entry in the tab list (same text, similar time left)
        for (Use u : uses) {
            for (Slot s : u.slots) {
                int idx = findEntry(entries, taken, s.key, remainingSeconds(s, now));
                if (idx >= 0) {
                    taken[idx] = true;
                    s.endMillis = endOf(entries.get(idx), now);
                    s.missingTicks = 0;
                } else {
                    s.missingTicks++;
                }
            }
        }

        //2. forget effects that have been gone for a bit, and uses with nothing left
        for (Iterator<Use> it = uses.iterator(); it.hasNext(); ) {
            Use u = it.next();
            u.slots.removeIf(s -> s.missingTicks > MISSING_GRACE_TICKS);
            if (u.slots.isEmpty()) { warned.remove(u.id); it.remove(); }
        }

        //3. entries nobody follows that weren't there before are the new effects of the items we're waiting on.
        //each one goes to the waiting item that fits it best
        if (!waiting.isEmpty()) {
            boolean[] baselineUsed = new boolean[baseline.size()];
            for (int i = 0; i < entries.size(); i++) {
                if (taken[i]) continue;
                StatusEffectState.Effect e = entries.get(i);
                if (wasThereBefore(e, now, baselineUsed)) continue;
                Use owner = bestOwner(e);
                if (owner != null) owner.slots.add(new Slot(e, now));
            }

            for (Iterator<Use> it = waiting.iterator(); it.hasNext(); ) {
                Use u = it.next();
                if (!u.slots.isEmpty()) {
                    uses.add(u);
                    it.remove();
                } else if (++u.waitedTicks > TAG_WINDOW_TICKS) {
                    it.remove();
                }
            }
            uses.sort(Comparator.comparingInt(u -> u.id)); //keep drink order
        }

        List<Row> rows = buildRows();
        checkExpiryWarnings(rows);
        renderSnapshot = buildRenderSnapshot(client, rows);

        for (Iterator<Popup> it = popups.iterator(); it.hasNext(); ) {
            if (--it.next().ticksLeft <= 0) it.remove();
        }
    }

    private static void clearState() {
        uses.clear();
        waiting.clear();
        warned.clear();
        popups.clear();
        baseline = List.of();
        renderedRows = List.of();
        renderSnapshot = EMPTY_RENDER_SNAPSHOT;
    }

    //the waiting item whose lore fits this effect best. ties go to the one used first, which is also
    //what happens when the lore doesn't mention the stat at all
    private static Use bestOwner(StatusEffectState.Effect e) {
        Use best = null;
        int bestScore = -1;
        for (Use u : waiting) {
            if (hasStat(u, e.name())) continue; //an item gives each stat only once
            int score = score(u, e);
            if (score > bestScore) { best = u; bestScore = score; }
        }
        return best;
    }

    private static boolean hasStat(Use use, String name) {
        for (Slot s : use.slots) if (s.name.equals(name)) return true;
        return false;
    }

    //how well this effect fits the item: +1 if the lore has the same stat name, +1 more if the number is the same
    //too, and +1 if the timer matches the duration in the lore. that last one is what tells apart two items that
    //give the exact same effect but last different amounts of time
    private static int score(Use use, StatusEffectState.Effect e) {
        int best = 0;
        String name = cleanLine(e.name());
        if (!name.isEmpty()) {
            Matcher m = NUMBER.matcher(keyOf(e));
            double number = m.find() ? Double.parseDouble(m.group()) : Double.NaN;
            for (LoreStat stat : use.stats) {
                if (!(" " + stat.text() + " ").contains(" " + name + " ")) continue;
                boolean sameNumber = !Double.isNaN(number) && Math.abs(stat.number() - number) < 0.51;
                best = Math.max(best, sameNumber ? 2 : 1);
            }
        }
        if (durationFits(use.loreSeconds, e.duration())) best++;
        return best;
    }

    //the timer should be a bit under the lore duration (it already ticked down while the item was being used)
    private static boolean durationFits(int loreSeconds, int entrySeconds) {
        if (loreSeconds < 0 || entrySeconds < 0) return false;
        return entrySeconds <= loreSeconds + 10 && entrySeconds >= loreSeconds - 40;
    }

    //duration from a lore line like "22m 25s Duration", "Duration: 300 Seconds" or "Duration: 6 minutes", -1 if none
    private static int loreSeconds(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String raw = line.getString();
            if (!raw.toLowerCase(Locale.ROOT).contains("duration")) continue;
            int total = 0;
            boolean found = false;
            Matcher m = TIME_PART.matcher(raw);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                char unit = Character.toLowerCase(m.group(2).charAt(0));
                total += unit == 'h' ? n * 3600 : unit == 'm' ? n * 60 : n;
                found = true;
            }
            if (found) return total;
        }
        return -1;
    }

    private static List<LoreStat> loreStats(ItemStack stack) {
        List<LoreStat> out = new ArrayList<>();
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return out;
        for (Text line : lore.lines()) {
            String raw = line.getString();
            Matcher m = NUMBER.matcher(raw);
            if (m.find()) out.add(new LoreStat(cleanLine(raw), Double.parseDouble(m.group())));
        }
        return out;
    }

    private static String keyOf(StatusEffectState.Effect e) {
        return TIME_SUFFIX.matcher(e.display()).replaceFirst("").trim();
    }

    private static long endOf(StatusEffectState.Effect e, long now) {
        return e.duration() < 0 ? Long.MAX_VALUE : now + e.duration() * 1000L;
    }

    //seconds left by our own clock, or -1 when the tab list has no timer
    private static int remainingSeconds(Slot s, long now) {
        return s.endMillis == Long.MAX_VALUE ? -1 : (int) Math.max(0, (s.endMillis - now + 999) / 1000);
    }

    private static boolean durationClose(int actual, int expected) {
        return actual < 0 || expected < 0 || Math.abs(actual - expected) <= DURATION_TOLERANCE_SECONDS;
    }

    //index of the free entry with this text whose time left is closest to what we expect, or -1
    private static int findEntry(List<StatusEffectState.Effect> entries, boolean[] taken, String key, int expected) {
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            if (taken[i]) continue;
            StatusEffectState.Effect e = entries.get(i);
            if (!keyOf(e).equals(key) || !durationClose(e.duration(), expected)) continue;
            int diff = (e.duration() < 0 || expected < 0) ? 0 : Math.abs(e.duration() - expected);
            if (diff < bestDiff) { best = i; bestDiff = diff; }
        }
        return best;
    }

    //true if this entry is (a later state of) one the tab list already showed before the first item was used
    private static boolean wasThereBefore(StatusEffectState.Effect e, long now, boolean[] used) {
        String key = keyOf(e);
        for (int j = 0; j < baseline.size(); j++) {
            if (used[j]) continue;
            Seen seen = baseline.get(j);
            if (!seen.key().equals(key)) continue;
            int expected = seen.duration() < 0 ? -1 : (int) Math.max(0, seen.duration() - (now - seen.at()) / 1000);
            if (durationClose(e.duration(), expected)) { used[j] = true; return true; }
        }
        return false;
    }

    private static Type classify(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        lines.add(cleanLine(stack.getName().getString()));
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) lines.add(cleanLine(line.getString()));
        }
        String all = String.join("\n", lines);

        //crafted consumables have a plain text "Right-Click to Consume" line, with charges and a duration
        boolean consumable = all.contains("right click to consume")
                || (all.contains("charges") && all.contains("duration"));

        Type frameType = typeFromFrameSprite(lore);
        if (frameType != null) return frameType;

        if (all.contains("crafted food")) return Type.FOOD;
        if (all.contains("crafted potion")) return Type.POTION;
        if (all.contains("crafted scroll")) return Type.SCROLL;

        //1. the type word at the start or end of the name or a lore line: "Potion of Agility", "Scroll of ..."
        for (String line : lines) {
            if (line.isEmpty()) continue;
            Type first = typeOfWord(line.indexOf(' ') < 0 ? line : line.substring(0, line.indexOf(' ')));
            if (first != null) return first;
            Type last = typeOfWord(line.substring(line.lastIndexOf(' ') + 1));
            if (last != null) return last;
        }

        if (!consumable) {
            return typeOfCategory(WynnItemParser.parse(stack).map(d -> d.category()).orElse(ItemCategory.UNKNOWN));
        }

        //2. only scrolls have an area of effect
        if (all.contains("area of effect")) return Type.SCROLL;

        //3. the type badge is a sprite font line, so its font id or the item model strings might hold the type
        String hints = hints(stack);
        if (hints.contains("potion")) return Type.POTION;
        if (hints.contains("food")) return Type.FOOD;
        if (hints.contains("scroll")) return Type.SCROLL;

        Type parsed = typeOfCategory(WynnItemParser.parse(stack).map(d -> d.category()).orElse(ItemCategory.UNKNOWN));
        if (parsed != null) return parsed;

        return Type.OTHER;
    }

    private static Type typeFromFrameSprite(LoreComponent lore) {
        if (lore == null) return null;
        Type[] result = new Type[1];
        for (Text line : lore.lines()) {
            line.visit((style, string) -> {
                if (!(style.getFont() instanceof StyleSpriteSource.Font(Identifier id))
                        || !id.getPath().equals("tooltip/emblem/sprite")) return Optional.empty();
                if (string.indexOf('\uE027') >= 0) result[0] = Type.POTION;
                else if (string.indexOf('\uE033') >= 0) result[0] = Type.FOOD;
                else if (string.indexOf('\uE032') >= 0) result[0] = Type.SCROLL;
                return Optional.empty();
            }, Style.EMPTY);
            if (result[0] != null) return result[0];
        }
        return null;
    }

    private static Type typeOfWord(String word) {
        return switch (word) {
            case "food" -> Type.FOOD;
            case "potion" -> Type.POTION;
            case "scroll" -> Type.SCROLL;
            default -> null;
        };
    }

    private static Type typeOfCategory(ItemCategory category) {
        return switch (category) {
            case FOOD -> Type.FOOD;
            case POTION -> Type.POTION;
            case TELEPORT_SCROLL -> Type.SCROLL;
            default -> null;
        };
    }

    //lower case text from the item model strings and the sprite font ids used by the name + lore (not the item id)
    private static String hints(ItemStack stack) {
        StringBuilder sb = new StringBuilder();
        CustomModelDataComponent modelData = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
        if (modelData != null) {
            for (String value : modelData.strings()) sb.append("model:").append(value).append(' ');
        }
        List<Text> texts = new ArrayList<>();
        texts.add(stack.getName());
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) texts.addAll(lore.lines());
        Set<String> fonts = new LinkedHashSet<>();
        for (Text text : texts) {
            text.visit((style, string) -> {
                if (style.getFont() instanceof StyleSpriteSource.Font(Identifier id)) fonts.add(id.getPath());
                return Optional.empty();
            }, Style.EMPTY);
        }
        for (String font : fonts) sb.append("font:").append(font).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    //lower case, letters and spaces only (drops colour codes, icons, numbers, punctuation) with single spaces
    private static String cleanLine(String s) {
        return NON_LETTERS.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private static void checkExpiryWarnings(List<Row> rows) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        if (!cfg.consumableWarnChat && !cfg.consumableWarnSound && !cfg.consumableWarnPopup) return;

        for (Row row : rows) {
            int secs = row.duration();
            if (secs < 0) continue;
            if (secs > cfg.consumableWarnSeconds) { warned.remove(row.use().id); continue; } //re-arm if it got refreshed
            if (!warned.add(row.use().id)) continue;

            String text = row.label() + " ends in " + secs + "s";
            if (cfg.consumableWarnChat) {
                MinecraftUtils.sendMessageToClient(WynnExtras.addWynnExtrasPrefix("§6" + text));
            }
            if (cfg.consumableWarnPopup) popups.add(new Popup(text));
            if (cfg.consumableWarnSound) playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
        }
    }

    private static void playSound(SoundEvent sound) {
        MinecraftUtils.playSoundAmbient(sound, 1.0f, 1.0f);
    }

    private static void playSound(RegistryEntry<SoundEvent> sound) {
        playSound(sound.value());
    }

    //category toggles are checked here so switching one on later shows effects that are already running
    private static boolean categoryEnabled(Type t) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        return switch (t) {
            case POTION -> cfg.consumableShowPotions;
            case FOOD -> cfg.consumableShowFood;
            case SCROLL -> cfg.consumableShowScrolls;
            case OTHER -> true; //type unknown, always shown
        };
    }

    //longest time left over the use's effects, -1 if they're all unknown
    private static int durationOf(Use use) {
        long now = System.currentTimeMillis();
        int best = -1;
        for (Slot slot : use.slots) best = Math.max(best, remainingSeconds(slot, now));
        return best;
    }

    private static String effectNames(Use use) {
        Set<String> names = new LinkedHashSet<>();
        for (Slot slot : use.slots) names.add(slot.name);
        return String.join(", ", names);
    }

    private static List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        for (Use u : uses) {
            int d = durationOf(u);
            if (!isVisible(u, d)) continue;
            rows.add(new Row(u, d, effectNames(u)));
        }

        if (WynnExtrasConfig.INSTANCE.consumableSortMode == WynnExtrasConfig.ConsumableSort.TIME_LEFT) {
            //shortest first, effects with no timer last
            rows.sort(Comparator.comparingInt(r -> r.duration() < 0 ? Integer.MAX_VALUE : r.duration()));
        }
        return rows;
    }

    private static boolean isVisible(Use u, int duration) {
        if (!categoryEnabled(u.type)) return false;
        return !(WynnExtrasConfig.INSTANCE.consumableHideNoTimer && duration < 0);
    }

    private static RenderSnapshot buildRenderSnapshot(MinecraftClient client, List<Row> rows) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        if (rows.equals(renderedRows) && cfg.consumableShowEffectName == renderedShowEffectName) return renderSnapshot;
        List<RenderRow> renderRows = new ArrayList<>();
        int contentWidth = 0;
        for (Row row : rows) {
            int secs = row.duration();
            String time = secs < 0 ? "active" : formatTime(secs);
            String text = cfg.consumableShowEffectName ? row.label() + ": " + time : time;
            List<OrderedText> lines = client.textRenderer.wrapLines(Text.literal(text), MAX_TEXT_WIDTH);
            int textWidth = lines.stream().mapToInt(client.textRenderer::getWidth).max().orElse(0);
            int textHeight = lines.size() * (client.textRenderer.fontHeight + 1);
            RenderRow renderRow = new RenderRow(row, lines, 20 + textWidth, Math.max(ROW_HEIGHT, textHeight));
            renderRows.add(renderRow);
            contentWidth = Math.max(contentWidth, renderRow.width());
        }
        renderedRows = List.copyOf(rows);
        renderedShowEffectName = cfg.consumableShowEffectName;
        return new RenderSnapshot(List.copyOf(renderRows), contentWidth);
    }

    private static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.consumableTimersToggle || mc.player == null || mc.options.hudHidden) return;

        renderPopups(ctx, mc);
        if (cfg.consumableHideInMenus && mc.currentScreen != null) return;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cfg.consumableTimersX, cfg.consumableTimersY);
        ctx.getMatrices().scale(cfg.consumableTimersScale, cfg.consumableTimersScale);

        boolean growUp = cfg.consumableGrowDirection == WynnExtrasConfig.GrowDirection.UP;
        RenderSnapshot snapshot = renderSnapshot;
        int contentWidth = snapshot.contentWidth();
        ctx.getMatrices().translate(switch (cfg.consumableTimersAlignment) {
            case CENTER -> -contentWidth / 2f;
            case RIGHT -> -contentWidth;
            default -> 0;
        }, 0);

        int yOffset = 0;
        for (RenderRow renderRow : snapshot.rows()) {
            Row row = renderRow.row();
            int secs = row.duration();
            int rowHeight = renderRow.height();
            int y = growUp ? yOffset - rowHeight + ROW_HEIGHT : yOffset;
            int rowX = switch (cfg.consumableTimersAlignment) {
                case CENTER -> (contentWidth - renderRow.width()) / 2;
                case RIGHT -> contentWidth - renderRow.width();
                default -> 0;
            };

            ctx.drawItem(row.use().icon, rowX, y);
            for (int line = 0; line < renderRow.lines().size(); line++) {
                ctx.drawText(mc.textRenderer, renderRow.lines().get(line), rowX + 20,
                        y + 4 + line * (mc.textRenderer.fontHeight + 1), timeColor(secs), true);
            }
            yOffset += growUp ? -rowHeight : rowHeight;
        }
        ctx.getMatrices().popMatrix();
    }

    private static String formatTime(int secs) {
        if (secs >= 3600) return String.format("%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60);
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    private static int timeColor(int secs) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        if (!cfg.consumableColorByTime || secs < 0) return 0xFFFFFFFF;
        if (secs <= cfg.consumableRedSeconds) return 0xFFFF5555;
        if (secs <= cfg.consumableYellowSeconds) return 0xFFFFFF55;
        return 0xFF55FF55;
    }

    private static void renderPopups(DrawContext ctx, MinecraftClient mc) {
        WynnExtrasConfig cfg = WynnExtrasConfig.INSTANCE;
        int x = cfg.consumableWarnHudX == -1
                ? mc.getWindow().getScaledWidth() / 2 : cfg.consumableWarnHudX;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, cfg.consumableWarnHudY);
        ctx.getMatrices().scale(cfg.consumableWarnHudScale, cfg.consumableWarnHudScale);
        int y = 0;
        for (Popup p : popups) {
            int alpha = p.ticksLeft >= POPUP_FADE_TICKS ? 255 : Math.max(8, p.ticksLeft * 255 / POPUP_FADE_TICKS);
            int color = (alpha << 24) | 0xFFAA00;
            int w = mc.textRenderer.getWidth(p.text);
            ctx.drawText(mc.textRenderer, p.text, -w / 2, y, color, true);
            y += 12;
        }
        ctx.getMatrices().popMatrix();
    }
}
