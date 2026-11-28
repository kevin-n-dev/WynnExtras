package julianh06.wynnextras.features.misc;

import julianh06.wynnextras.config.WynnExtrasConfig;
import julianh06.wynnextras.features.qol.AttackTimer;
import julianh06.wynnextras.features.raid.RaidSessionTracker;
import julianh06.wynnextras.features.raid.tna.TreeRoomMinimap;
import julianh06.wynnextras.utils.MinecraftUtils;
import julianh06.wynnextras.wynncraft.item.WynnItemParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HudEditScreen extends Screen {
    private static class HudElement {
        final String id;
        final List<Text> previewLines;
        final int defaultColor;
        int x, y, w;
        int customH = -1; // -1 = use default H
        boolean fixedSize = false; // true = don't allow scaling, don't center-shift in init
        boolean topLeft = false;  // true = position stored as top-left (not center)
        boolean alignedAnchor = false;
        boolean preserveDefaultScale = false;
        boolean scaleChanged = false;
        float scale;
        boolean dragging;
        int dragOffX, dragOffY;
        boolean snappedX, snappedY;
        int initialX, initialY;
        float initialScale;
        boolean initialStateSet;
        WynnExtrasConfig.Align alignment;
        WynnExtrasConfig.GrowDirection growDirection = WynnExtrasConfig.GrowDirection.DOWN;
        int durabilityColumn = -1;
        List<String> durabilityItems = List.of();

        HudElement(String id, String preview, int x, int y, float scale, WynnExtrasConfig.Align alignment) {
            this(id, Text.literal(preview), x, y, scale, alignment, 0xFFFFFFFF);
        }

        HudElement(String id, String preview, int x, int y, float scale, WynnExtrasConfig.Align alignment, int defaultColor) {
            this(id, Text.literal(preview), x, y, scale, alignment, defaultColor);
        }

        HudElement(String id, Text preview, int x, int y, float scale, WynnExtrasConfig.Align alignment, int defaultColor) {
            this(id, List.of(preview), x, y, scale, alignment, defaultColor);
        }

        HudElement(String id, List<Text> previewLines, int x, int y, float scale,
                   WynnExtrasConfig.Align alignment, int defaultColor) {
            this.id = id;
            this.previewLines = previewLines;
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.alignment = alignment;
            this.defaultColor = defaultColor | 0xFF000000;
        }

        int sw() { return (int) (w * scale); }
        int sh() { return (int) ((customH > 0 ? customH : H) * scale); }
        boolean hovered(double mx, double my) {
            return mx >= x - 2 && mx <= x + sw() + 2 && my >= y - 2 && my <= y + sh() + 2;
        }
    }

    private static final int H = 14;
    private static final int SNAP_DIST = 8;
    private static final int DOCK_DIST = 14;
    private static final boolean HUD_COLOR_EDITING_ENABLED = false; //temporarily disabled since its not finished yet
    private final List<HudElement> elements = new ArrayList<>();
    private HudElement selectedElement;
    private int snapGuideX = -1;
    private int snapGuideY = -1;
    private List<HudElement> pressedOverlapElements = List.of();
    private HudElement pressedElement;
    private int pendingDurabilitySplit = -1;
    private double pressMouseX;
    private double pressMouseY;
    private boolean dragMoved;
    private int dockTargetColumn = -1;
    private boolean dockBeforeTarget;
    private int dockGuideX = -1;
    private int dockGuideW;
    private int dockGuideY = -1;
    private boolean layoutInitialized;

    // Color edit popup state (HSV internally, converted to RGB on save)
    private HudElement colorEditTarget = null;
    private float colorH = 0f, colorS = 0f, colorV = 1f; // H: 0-360, S/V: 0-1
    private int colorDragMode = 0; // 0=none, 1=hue, 2=sv

    // Cached SV square (rebuilt when hue changes) and hue bar (built once).
    private static final int SV_CELL = 4; // px per cell (lower = more detail, more draws)
    private float svCacheHue = -1f;
    private int[] svCacheColors = null; // row-major, cells[row * cols + col]
    private int svCacheCols = 0, svCacheRows = 0;
    private int[] hueCacheColors = null; // 1 per row
    private int hueCacheRows = 0;

    private static final int[] PRESET_COLORS = {
            0xFFFFFF, 0xFF5555, 0xFFAA00, 0xFFFF55, 0x55FF55,
            0x55FFFF, 0x5555FF, 0xFF55FF, 0xAA55FF, 0x888888, 0x000000
    };

    private final Screen parent;

    public HudEditScreen() { this(null); }

    public HudEditScreen(Screen parent) {
        super(Text.literal("Edit HUD"));
        WynnExtrasConfig c = WynnExtrasConfig.INSTANCE;

        this.parent = parent;

        if (c.provokeTimerToggle) {
            int provokeColor = c.provokeTimerColor.getRGB();
            elements.add(new HudElement("provoke", "Provoke: " + c.provokeTimerDuration + "s",
                    c.provokeTimerX, c.provokeTimerY, c.provokeTimerScale, c.provokeTimerAlignment, provokeColor));
        }
        if (c.consumableTimersToggle) {
            String consumableText = c.consumableShowEffectName ? "Walk Speed: 4:59" : "4:59";
            //leading spaces leave room for the item icon that gets drawn in front of the text
            HudElement consumableEl = new HudElement("consumables", "     " + consumableText,
                    c.consumableTimersX, c.consumableTimersY, c.consumableTimersScale,
                    c.consumableTimersAlignment, 0xFF55FF55);
            consumableEl.topLeft = true;
            consumableEl.alignedAnchor = true;
            consumableEl.growDirection = c.consumableGrowDirection;
            consumableEl.customH = 16;
            elements.add(consumableEl);
        }
        if (c.consumableTimersToggle && c.consumableWarnPopup) {
            HudElement warning = new HudElement("consumableWarning", "Walk Speed ends in 30s",
                    c.consumableWarnHudX, c.consumableWarnHudY, c.consumableWarnHudScale,
                    WynnExtrasConfig.Align.CENTER, 0xFFFFAA00);
            warning.topLeft = true;
            warning.alignedAnchor = true;
            elements.add(warning);
        }
        if (c.durabilityHudToggle) {
            addDurabilityElements(c);
        }
        if (c.durabilityHudToggle && c.durabilityWarnToggle && c.durabilityWarnPopup) {
            HudElement warning = new HudElement("durabilityWarning", "Item durability is low: 9/52 (17%)",
                    c.durabilityWarnHudX, c.durabilityWarnHudY, c.durabilityWarnHudScale,
                    WynnExtrasConfig.Align.CENTER, 0xFFFF5555);
            warning.topLeft = true;
            warning.alignedAnchor = true;
            elements.add(warning);
        }
        if (c.totemTimerEnabled) {
            Map<String, String> previewEffects = new HashMap<>();
            previewEffects.put(WynnExtrasConfig.TOTEM_TIMER_EFFECT_DURATION, "38s");
            previewEffects.put(WynnExtrasConfig.TOTEM_TIMER_EFFECT_TOXOPLASMOSIS, "\ue011 1.6k");
            previewEffects.put(WynnExtrasConfig.TOTEM_TIMER_EFFECT_REGENERATION, "+120\u2764/s");
            previewEffects.put(WynnExtrasConfig.TOTEM_TIMER_EFFECT_ELDRITCH_TRANSFUSION, "\ue020 2");
            previewEffects.put(WynnExtrasConfig.TOTEM_TIMER_EFFECT_INVIGORATING_WAVE, "\ue013 3s");
            TotemTimer.TotemInfo previewTotem = new TotemTimer.TotemInfo("PlayerName", previewEffects, false);
            Text totemText = TotemTimer.getHudText(previewTotem);
            int totemColor = 0xFF44FF44;
            List<TotemTimer.TotemInfo> totems = TotemTimer.getTotems();
            if (!totems.isEmpty()) {
                Text liveText = TotemTimer.getHudText(totems.get(0));
                if (!liveText.getString().isEmpty()) totemText = liveText;
            }
            if (totemText.getString().isEmpty()) totemText = Text.literal("Totem timer: no effects selected");
            elements.add(new HudElement("totem", totemText,
                    c.totemTimerX, c.totemTimerY, c.totemTimerScale, c.totemTimerAlignment, totemColor));
        }
        if (c.bloodSorrowTimerEnabled) {
            elements.add(new HudElement("blood", "Blood Sorrow: 1.7s",
                    c.bloodSorrowTimerX, c.bloodSorrowTimerY, c.bloodSorrowTimerScale, c.bloodSorrowAlignment));
        }
        if (c.curseTrackerEnabled) {
            String curseText = "Curse: 3.2s";
            int curseColor = 0xFF44FF44;
            CurseTracker.CurseState state = CurseTracker.getState();
            if (state != null) {
                curseText = state.displayText();
                curseColor = state.color();
            }
            elements.add(new HudElement("curse", curseText,
                    c.curseTrackerX, c.curseTrackerY, c.curseTrackerScale, c.curseTrackerAlignment, curseColor));
        }
        if (c.totemTimerEnabled && c.totemTimerWarningText) {
            int wx = c.totemWarningX;
            if (wx == -1) wx = 200;
            int warningColor = c.totemTimerWarningTextColor.getRGB();
            elements.add(new HudElement("warning", "RECAST TOTEM!",
                    wx, c.totemWarningY, c.totemWarningScale, c.totemWarningAlignment, warningColor));
        }
        if (c.radiantHudEnabled) {
            String radiantText = "Radiant 1:30";
            int radiantColor = 0xFF44FF44;
            List<RadiantHud.CachedEntry> radiant = RadiantHud.getCachedEntries();
            if (!radiant.isEmpty()) {
                RadiantHud.CachedEntry entry = radiant.get(0);
                radiantText = entry.display();
                radiantColor = entry.color();
            }
            HudElement radiantEl = new HudElement("radiant", radiantText,
                    c.radiantHudX, c.radiantHudY, c.radiantHudScale, WynnExtrasConfig.Align.CENTER, radiantColor);
            radiantEl.topLeft = true;
            elements.add(radiantEl);
        }
        if (c.professionOverlayEnabled) {
            HudElement professionEl = new HudElement("profession", "Mining Lv. 87  1234/5678 (21.7%)",
                    c.professionOverlayX, c.professionOverlayY, c.professionOverlayScale, WynnExtrasConfig.Align.CENTER, 0xFFFFFF00);
            professionEl.topLeft = true;
            elements.add(professionEl);
        }
        if (c.tnaTreeMap) {
            HudElement treemap = new HudElement("treemap", "Tree Minimap",
                    c.treeMapX, c.treeMapY, TreeRoomMinimap.getEffectiveScale(c.tnaTreeMapScale), WynnExtrasConfig.Align.CENTER);
            treemap.customH = 130;
            treemap.w = 130;
            treemap.fixedSize = true;
            treemap.preserveDefaultScale = TreeRoomMinimap.usesDefaultScale(c.tnaTreeMapScale);
            elements.add(treemap);
        }

        int nx = c.notifierX;
        if (nx == -1) nx = 200;
        int ny = c.notifierY;
        if (ny == -1) ny = 100;
        elements.add(new HudElement("notifier", "NOTIFICATION",
                nx, ny, c.notifierScale, c.notifierAlignment));

        if (c.weeklyWarCountEnabled) {
            HudElement weeklyWarsEl = new HudElement("weeklyWars", "5 wars",
                    c.weeklyWarCountX, c.weeklyWarCountY, 1.5f, WynnExtrasConfig.Align.LEFT, 0xFFFF55FF);
            weeklyWarsEl.topLeft = true;
            elements.add(weeklyWarsEl);
        }
        if (c.serverTpsEnabled) {
            HudElement serverTpsEl = new HudElement("serverTps", "TPS: 20.0",
                    c.serverTpsX, c.serverTpsY, c.serverTpsScale, WynnExtrasConfig.Align.LEFT);
            serverTpsEl.topLeft = true;
            elements.add(serverTpsEl);
        }
        if (c.fpsHudEnabled) {
            HudElement fpsEl = new HudElement("fps", "FPS: 144",
                    c.fpsHudX, c.fpsHudY, c.fpsHudScale, WynnExtrasConfig.Align.LEFT);
            fpsEl.topLeft = true;
            elements.add(fpsEl);
        }
        if (c.warDpsEnabled) {
            HudElement warDpsEl = new HudElement("warDps", "War Info: Tower EHP 234K",
                    c.warDpsX, c.warDpsY, 1.0f, WynnExtrasConfig.Align.LEFT);
            warDpsEl.topLeft = true;
            elements.add(warDpsEl);
        }
        if (c.attackTimerMenuEnabled) {
            String attackText = "13:47 Otherworldly Monolith";
            List<String> attacks = AttackTimer.getUpcomingAttacks();
            if (!attacks.isEmpty()) attackText = attacks.get(0);
            HudElement attackTimerEl = new HudElement("attackTimer", attackText,
                    c.attackTimerX, c.attackTimerY, 1.0f, WynnExtrasConfig.Align.LEFT,
                    0xFF000000 | attackTimerNormalColor());
            attackTimerEl.topLeft = true;
            elements.add(attackTimerEl);
        }
        if (c.raidSessionEnabled) {
            String raidText = "Raids: 42 | 8.5/hr | Completed: 40 | Failed: 2";
            String live = RaidSessionTracker.getStatsString();
            if (live != null) raidText = live;
            HudElement raidSessionEl = new HudElement("raidSession", raidText,
                    c.raidSessionHudX, c.raidSessionHudY, c.raidSessionHudScale, WynnExtrasConfig.Align.LEFT, 0xFFAAFFAA);
            raidSessionEl.topLeft = true;
            elements.add(raidSessionEl);
        }
    }

    private void addDurabilityElements(WynnExtrasConfig config) {
        List<List<String>> durabilityColumns = config.getDurabilityItemColumns();
        for (int column = 0; column < durabilityColumns.size(); column++) {
            List<String> itemIds = durabilityColumns.get(column);
            if (itemIds.isEmpty()) continue;
            List<Text> previewLines = new ArrayList<>();
            for (int row = 0; row < itemIds.size(); row++) {
                previewLines.add(Text.literal("     " + durabilityPreviewText(config.durabilityFormat, row)));
            }
            HudElement durabilityElement = new HudElement("durability:" + column, previewLines,
                    config.getDurabilityHudColumnX(column), config.getDurabilityHudColumnY(column),
                    config.durabilityHudScale, config.getDurabilityHudColumnAlignment(column), 0xFFFFFFFF);
            durabilityElement.topLeft = true;
            durabilityElement.alignedAnchor = true;
            durabilityElement.customH = itemIds.size() * 18;
            durabilityElement.durabilityColumn = column;
            durabilityElement.durabilityItems = List.copyOf(itemIds);
            durabilityElement.growDirection = config.getDurabilityHudColumnGrowDirection(column);
            elements.add(durabilityElement);
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void blur() {}

    @Override
    protected void applyBlur(DrawContext context) {}

    @Override
    public void init() {
        super.init();
        if (layoutInitialized) {
            for (HudElement element : elements) {
                element.x = Math.clamp(element.x, 0, Math.max(0, width - element.sw()));
                element.y = Math.clamp(element.y, 0, Math.max(0, height - element.sh()));
            }
            return;
        }
        layoutInitialized = true;
        for (HudElement e : elements) {
            if (!e.fixedSize) {
                e.w = e.previewLines.stream().mapToInt(textRenderer::getWidth).max().orElse(1)
                        + (e.alignedAnchor ? 0 : 6);
                if (!e.topLeft) {
                    e.x = e.x - e.sw() / 2;
                    e.y = e.y - e.sh() / 2;
                } else if (e.alignedAnchor) {
                    e.x -= alignmentOffset(e);
                    e.y -= growthOffset(e);
                }
            }

            if (e.id.equals("totem") && WynnExtrasConfig.INSTANCE.totemTimerX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("provoke") && WynnExtrasConfig.INSTANCE.provokeTimerX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("blood") && WynnExtrasConfig.INSTANCE.bloodSorrowTimerX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("curse") && WynnExtrasConfig.INSTANCE.curseTrackerX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("warning") && WynnExtrasConfig.INSTANCE.totemWarningX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("notifier") && WynnExtrasConfig.INSTANCE.notifierX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("durabilityWarning") && WynnExtrasConfig.INSTANCE.durabilityWarnHudX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("consumableWarning") && WynnExtrasConfig.INSTANCE.consumableWarnHudX == -1) {
                e.x = (width - e.sw()) / 2;
            }
            if (e.id.equals("notifier") && WynnExtrasConfig.INSTANCE.notifierY == -1) {
                e.y = (int) (height * 0.3f);
            }

            // Clamp on-screen so elements placed at small coords (e.g. x=5) aren't shoved
            // off the top-left edge by the center-shift above.
            e.x = Math.max(4, Math.min(width - e.sw() - 4, e.x));
            e.y = Math.max(4, Math.min(height - e.sh() - 4, e.y));
            rememberInitialState(e);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0x55000000);

        if (snapGuideX >= 0) ctx.fill(snapGuideX, 0, snapGuideX + 1, height, 0x7700FF88);
        if (snapGuideY >= 0) ctx.fill(0, snapGuideY, width, snapGuideY + 1, 0x7700FF88);

        String hint = elements.isEmpty()
                ? "No HUD elements enabled  |  Esc to close"
                : "Drag: move  |  Scroll: resize  |  Arrows: nudge  |  Arrows + Shift: nudge 10 px  |  R: reset  |  Esc: save";
        ctx.drawText(textRenderer, hint, 4, height - 12, 0xFFaaaaaa, true);

        if (selectedElement != null) {
            int selectedX = selectedElement.alignedAnchor ? alignmentAnchorX(selectedElement) : selectedElement.x;
            int selectedY = selectedElement.alignedAnchor ? growthAnchorY(selectedElement) : selectedElement.y;
            String selected = displayName(selectedElement) + " x " + selectedX + " y "
                    + selectedY + " scale " + String.format("%.1f", selectedElement.scale);
            int infoW = textRenderer.getWidth(selected) + 12;
            ctx.fill(width / 2 - infoW / 2, 4, width / 2 + infoW / 2, 23, 0xDD111111);
            ctx.drawCenteredTextWithShadow(textRenderer, selected, width / 2, 7, 0xFFFFFFFF);
        }

        for (HudElement e : elements) {
            if (e != colorEditTarget && e != selectedElement) drawElement(ctx, e, mouseX, mouseY);
        }
        if (selectedElement != null && selectedElement != colorEditTarget) {
            drawElement(ctx, selectedElement, mouseX, mouseY);
        }
        if (dockGuideY >= 0) {
            ctx.fill(dockGuideX, dockGuideY - 1, dockGuideX + dockGuideW, dockGuideY + 1, 0xFFFFAA00);
        }
        if (selectedElement != null && supportsAlignment(selectedElement)) {
            renderWidgetToolbar(ctx, selectedElement, mouseX, mouseY);
        }
        renderOverlapHint(ctx, mouseX, mouseY);
        renderDurabilitySplitTooltip(ctx, mouseX, mouseY);
        // Dim happens inside renderColorPicker. Focused element is drawn AFTER the dim there.

        // Color picker popup
        if (colorEditTarget != null) {
            renderColorPicker(ctx);
        }
    }

    private void drawElement(DrawContext ctx, HudElement e, int mouseX, int mouseY) {
        boolean hovered = !isMouseOverWidgetToolbar(mouseX, mouseY) && e.hovered(mouseX, mouseY);
        boolean focused = e == colorEditTarget;
        boolean selected = e == selectedElement;
        int border = focused ? 0xFFFFAA00 : selected ? 0xFF55FFFF
                : (hovered || e.dragging) ? 0xFFFFFFFF : 0xFF888888;
        int sw = e.sw(), sh = e.sh();

        ctx.fill(e.x - 2, e.y - 2, e.x + sw + 2, e.y + sh + 2, 0xCC000000);
        ctx.fill(e.x - 2, e.y - 2, e.x + sw + 2, e.y - 1, border);
        ctx.fill(e.x - 2, e.y + sh + 1, e.x + sw + 2, e.y + sh + 2, border);
        ctx.fill(e.x - 2, e.y - 2, e.x - 1, e.y + sh + 2, border);
        ctx.fill(e.x + sw + 1, e.y - 2, e.x + sw + 2, e.y + sh + 2, border);

        Integer override = hudColorOverride(e.id);
        int textColor;
        if (focused) {
            textColor = 0xFF000000 | hsvToRgb(colorH, colorS, colorV);
        } else if (override != null) {
            textColor = override | 0xFF000000;
        } else if (e.id.equals("notifier")) {
            textColor = WynnExtrasConfig.INSTANCE.textColor.getRGB() | 0xFF000000;
        } else {
            textColor = e.defaultColor;
        }

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(e.x, e.y);
        ctx.getMatrices().scale(e.scale, e.scale);
        int lineStep = e.previewLines.size() > 1 ? 18 : textRenderer.fontHeight;
        int textHeight = (e.previewLines.size() - 1) * lineStep + textRenderer.fontHeight;
        int contentHeight = e.customH > 0 ? e.customH : H;
        for (int line = 0; line < e.previewLines.size(); line++) {
            Text preview = e.previewLines.get(line);
            int tw = textRenderer.getWidth(preview);
            int textX = switch (e.alignment) {
                case LEFT -> e.alignedAnchor ? 0 : 3;
                case RIGHT -> e.w - tw - (e.alignedAnchor ? 0 : 3);
                default -> (e.w - tw) / 2;
            };
            if (e.durabilityColumn >= 0) {
                drawDurabilityPreviewIcon(ctx, e.durabilityItems.get(line), textX, line * 18);
            }
            int lineColor = e.durabilityColumn >= 0
                    && equippedDurabilityPreviewStack(e.durabilityItems.get(line)).isEmpty()
                    ? darkenColor(textColor) : textColor;
            ctx.drawText(textRenderer, preview, textX, (contentHeight - textHeight) / 2 + line * lineStep,
                    lineColor, true);
        }
        ctx.getMatrices().popMatrix();

        int split = durabilitySplitAt(e, mouseX, mouseY);
        if (hovered && e == topElementAt(mouseX, mouseY) && !e.dragging && split > 0) {
            int splitY = e.y + Math.round(split * 18 * e.scale);
            ctx.fill(e.x, splitY - 1, e.x + sw, splitY + 1, 0xFFFFAA00);
        }
    }

    private int[] colorPickerBounds() {
        int popupW = 240, popupH = 220;
        int x, y;
        if (colorEditTarget != null) {
            // Try to open the picker beside the element so the element stays visible.
            int ex = colorEditTarget.x, ey = colorEditTarget.y, esw = colorEditTarget.sw(), esh = colorEditTarget.sh();
            int rightSpace = width - (ex + esw);
            if (rightSpace >= popupW + 16) {
                x = ex + esw + 8;
            } else if (ex >= popupW + 16) {
                x = ex - popupW - 8;
            } else {
                x = width / 2 - popupW / 2;
            }
            y = ey + esh / 2 - popupH / 2;
            x = Math.max(4, Math.min(width - popupW - 4, x));
            y = Math.max(4, Math.min(height - popupH - 4, y));
        } else {
            x = width / 2 - popupW / 2;
            y = height / 2 - popupH / 2;
        }
        return new int[]{x, y, popupW, popupH};
    }

    private int[] closeButtonBounds() {
        int[] b = colorPickerBounds();
        int size = 12;
        return new int[]{b[0] + b[2] - size - 4, b[1] + 4, size, size};
    }

    private int[] svBoxBounds() {
        int[] b = colorPickerBounds();
        int size = 120;
        return new int[]{b[0] + 12, b[1] + 22, size, size};
    }

    private int[] hueBarBounds() {
        int[] b = colorPickerBounds();
        int[] sv = svBoxBounds();
        return new int[]{sv[0] + sv[2] + 10, sv[1], 14, sv[3]};
    }

    private int[] presetBounds(int presetIdx) {
        int[] b = colorPickerBounds();
        int cols = PRESET_COLORS.length;
        int cellW = (b[2] - 20) / cols;
        int px = b[0] + 10 + presetIdx * cellW;
        int py = b[1] + 155;
        return new int[]{px, py, cellW - 2, 12};
    }

    private int[] buttonBounds(int btnIdx) {
        int[] b = colorPickerBounds();
        int gap = 6;
        int available = b[2] - 16; // inner padding
        int btnW = (available - gap * 2) / 3;
        int btnH = 18;
        int y = b[1] + b[3] - btnH - 8;
        int startX = b[0] + 8;
        return new int[]{startX + btnIdx * (btnW + gap), y, btnW, btnH};
    }

    private static int attackTimerNormalColor() {
        Integer color = WynnExtrasConfig.INSTANCE.attackTimerNormalColor;
        return color == null ? 0xFFAA00 : color & 0xFFFFFF;
    }

    private static Integer hudColorOverride(String id) {
        if ("attackTimer".equals(id)) return attackTimerNormalColor();
        return WynnExtrasConfig.INSTANCE.hudColorOverrides.get(id);
    }

    private static void resetHudColor(String id) {
        if ("attackTimer".equals(id)) {
            WynnExtrasConfig.INSTANCE.attackTimerNormalColor = 0xFFAA00;
            return;
        }
        WynnExtrasConfig.INSTANCE.hudColorOverrides.remove(id);
    }

    private static void setHudColor(String id, int color) {
        if ("attackTimer".equals(id)) {
            WynnExtrasConfig.INSTANCE.attackTimerNormalColor = color & 0xFFFFFF;
            return;
        }
        WynnExtrasConfig.INSTANCE.hudColorOverrides.put(id, color & 0xFFFFFF);
    }

    private void renderColorPicker(DrawContext ctx) {
        int[] b = colorPickerBounds();
        int px = b[0], py = b[1], popupW = b[2], popupH = b[3];

        ctx.fill(0, 0, width, height, 0x88000000);
        // Redraw the focused element above the dim so it stays bright.
        if (colorEditTarget != null) drawElement(ctx, colorEditTarget, -1, -1);
        ctx.fill(px, py, px + popupW, py + popupH, 0xFF222222);
        ctx.fill(px, py, px + popupW, py + 1, 0xFFFFAA00);
        ctx.fill(px, py + popupH - 1, px + popupW, py + popupH, 0xFFFFAA00);
        ctx.fill(px, py, px + 1, py + popupH, 0xFFFFAA00);
        ctx.fill(px + popupW - 1, py, px + popupW, py + popupH, 0xFFFFAA00);

        ctx.drawCenteredTextWithShadow(textRenderer, "Color: " + colorEditTarget.id, px + popupW / 2, py + 6, 0xFFFFAA00);

        // Saturation/Value square — draw from a cached grid of block colors.
        int[] sv = svBoxBounds();
        ensureSvCache(sv[2], sv[3]);
        for (int row = 0; row < svCacheRows; row++) {
            int y0 = sv[1] + row * SV_CELL;
            int y1 = Math.min(sv[1] + sv[3], y0 + SV_CELL);
            for (int col = 0; col < svCacheCols; col++) {
                int x0 = sv[0] + col * SV_CELL;
                int x1 = Math.min(sv[0] + sv[2], x0 + SV_CELL);
                ctx.fill(x0, y0, x1, y1, svCacheColors[row * svCacheCols + col]);
            }
        }
        // SV cursor: circular-ish ring (8x8 outline) around the selected point.
        int svCx = sv[0] + (int)(colorS * (sv[2] - 1));
        int svCy = sv[1] + (int)((1f - colorV) * (sv[3] - 1));
        drawColorCursor(ctx, svCx, svCy);

        // Hue bar (vertical rainbow) — cached.
        int[] hb = hueBarBounds();
        ensureHueCache(hb[3]);
        for (int row = 0; row < hueCacheRows; row++) {
            ctx.fill(hb[0], hb[1] + row, hb[0] + hb[2], hb[1] + row + 1, hueCacheColors[row]);
        }
        // Hue cursor: simple horizontal ring.
        int hueCy = hb[1] + (int)((colorH / 360f) * (hb[3] - 1));
        ctx.fill(hb[0] - 2, hueCy - 2, hb[0] + hb[2] + 2, hueCy - 1, 0xFFFFFFFF);
        ctx.fill(hb[0] - 2, hueCy + 1, hb[0] + hb[2] + 2, hueCy + 2, 0xFFFFFFFF);
        ctx.fill(hb[0] - 2, hueCy - 1, hb[0] - 1, hueCy + 1, 0xFFFFFFFF);
        ctx.fill(hb[0] + hb[2] + 1, hueCy - 1, hb[0] + hb[2] + 2, hueCy + 1, 0xFFFFFFFF);

        // Preview swatch + hex (positioned to the right of hue bar, within popup)
        int currentRgb = hsvToRgb(colorH, colorS, colorV);
        int previewColor = 0xFF000000 | currentRgb;
        int previewX = hb[0] + hb[2] + 10;
        int previewW = Math.min(40, px + popupW - previewX - 8);
        ctx.fill(previewX, sv[1], previewX + previewW, sv[1] + 30, previewColor);
        ctx.fill(previewX - 1, sv[1] - 1, previewX + previewW + 1, sv[1], 0xFFAAAAAA);
        ctx.fill(previewX - 1, sv[1] + 30, previewX + previewW + 1, sv[1] + 31, 0xFFAAAAAA);
        ctx.fill(previewX - 1, sv[1], previewX, sv[1] + 30, 0xFFAAAAAA);
        ctx.fill(previewX + previewW, sv[1], previewX + previewW + 1, sv[1] + 30, 0xFFAAAAAA);
        ctx.drawTextWithShadow(textRenderer, String.format("#%06X", currentRgb & 0xFFFFFF), previewX, sv[1] + 36, 0xFFFFFFFF);

        // Presets
        for (int i = 0; i < PRESET_COLORS.length; i++) {
            int[] pb = presetBounds(i);
            int c = 0xFF000000 | PRESET_COLORS[i];
            ctx.fill(pb[0], pb[1], pb[0] + pb[2], pb[1] + pb[3], c);
            ctx.fill(pb[0], pb[1], pb[0] + pb[2], pb[1] + 1, 0xFF555555);
            ctx.fill(pb[0], pb[1] + pb[3] - 1, pb[0] + pb[2], pb[1] + pb[3], 0xFF555555);
        }

        String[] labels = {"Reset", "Apply", "Apply to all"};
        int[] btnColors = {0xFFAA3333, 0xFF338833, 0xFF3388AA};
        for (int i = 0; i < 3; i++) {
            int[] bb = buttonBounds(i);
            ctx.fill(bb[0], bb[1], bb[0] + bb[2], bb[1] + bb[3], btnColors[i]);
            ctx.drawCenteredTextWithShadow(textRenderer, labels[i], bb[0] + bb[2] / 2, bb[1] + 5, 0xFFFFFFFF);
        }

        // Close X in top-right corner of popup
        int[] cb = closeButtonBounds();
        ctx.fill(cb[0], cb[1], cb[0] + cb[2], cb[1] + cb[3], 0xFF552222);
        ctx.drawCenteredTextWithShadow(textRenderer, "X", cb[0] + cb[2] / 2, cb[1] + 2, 0xFFFFFFFF);
    }

    private void ensureSvCache(int width, int height) {
        int cols = (int) Math.ceil((double) width / SV_CELL);
        int rows = (int) Math.ceil((double) height / SV_CELL);
        if (svCacheColors != null && cols == svCacheCols && rows == svCacheRows && colorH == svCacheHue) return;
        svCacheCols = cols;
        svCacheRows = rows;
        svCacheColors = new int[cols * rows];
        svCacheHue = colorH;
        for (int row = 0; row < rows; row++) {
            float v = 1f - (row * SV_CELL) / (float)(height - 1);
            if (v < 0) v = 0;
            for (int col = 0; col < cols; col++) {
                float s = (col * SV_CELL) / (float)(width - 1);
                if (s > 1) s = 1;
                svCacheColors[row * cols + col] = 0xFF000000 | hsvToRgb(colorH, s, v);
            }
        }
    }

    private void ensureHueCache(int height) {
        if (hueCacheColors != null && hueCacheRows == height) return;
        hueCacheRows = height;
        hueCacheColors = new int[height];
        for (int row = 0; row < height; row++) {
            float h = (row / (float)(height - 1)) * 360f;
            hueCacheColors[row] = 0xFF000000 | hsvToRgb(h, 1f, 1f);
        }
    }

    private static void drawColorCursor(DrawContext ctx, int cx, int cy) {
        // Double-ring cursor: black outer border, white inner ring for contrast on any background.
        // 9x9 outer square (black), 7x7 middle ring (white), 5x5 inner cutout.
        ctx.fill(cx - 4, cy - 4, cx + 5, cy - 3, 0xFF000000);
        ctx.fill(cx - 4, cy + 3, cx + 5, cy + 4, 0xFF000000);
        ctx.fill(cx - 4, cy - 3, cx - 3, cy + 3, 0xFF000000);
        ctx.fill(cx + 3, cy - 3, cx + 4, cy + 3, 0xFF000000);
        ctx.fill(cx - 3, cy - 3, cx + 4, cy - 2, 0xFFFFFFFF);
        ctx.fill(cx - 3, cy + 2, cx + 4, cy + 3, 0xFFFFFFFF);
        ctx.fill(cx - 3, cy - 2, cx - 2, cy + 2, 0xFFFFFFFF);
        ctx.fill(cx + 2, cy - 2, cx + 3, cy + 2, 0xFFFFFFFF);
    }

    private static int hsvToRgb(float h, float s, float v) {
        h = ((h % 360f) + 360f) % 360f;
        float c = v * s;
        float x = c * (1f - Math.abs(((h / 60f) % 2f) - 1f));
        float m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        int ri = Math.round((r + m) * 255f);
        int gi = Math.round((g + m) * 255f);
        int bi = Math.round((b + m) * 255f);
        return (ri << 16) | (gi << 8) | bi;
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h = 0;
        if (d != 0) {
            if (max == rf) h = 60 * (((gf - bf) / d) % 6);
            else if (max == gf) h = 60 * ((bf - rf) / d + 2);
            else h = 60 * ((rf - gf) / d + 4);
        }
        if (h < 0) h += 360;
        float s = max == 0 ? 0 : d / max;
        return new float[]{h, s, max};
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mx = click.x(), my = click.y();

        // Color picker handling
        if (colorEditTarget != null) {
            if (click.button() != 0) return true;

            // Close X
            int[] cb = closeButtonBounds();
            if (mx >= cb[0] && mx < cb[0] + cb[2] && my >= cb[1] && my < cb[1] + cb[3]) {
                colorEditTarget = null;
                return true;
            }

            // SV box
            int[] sv = svBoxBounds();
            if (mx >= sv[0] && mx < sv[0] + sv[2] && my >= sv[1] && my < sv[1] + sv[3]) {
                colorDragMode = 2;
                colorS = Math.max(0f, Math.min(1f, (float)(mx - sv[0]) / (sv[2] - 1)));
                colorV = Math.max(0f, Math.min(1f, 1f - (float)(my - sv[1]) / (sv[3] - 1)));
                return true;
            }

            // Hue bar
            int[] hb = hueBarBounds();
            if (mx >= hb[0] && mx < hb[0] + hb[2] && my >= hb[1] && my < hb[1] + hb[3]) {
                colorDragMode = 1;
                colorH = Math.max(0f, Math.min(360f, (float)(my - hb[1]) / (hb[3] - 1) * 360f));
                return true;
            }

            // Preset colors
            for (int i = 0; i < PRESET_COLORS.length; i++) {
                int[] pb = presetBounds(i);
                if (mx >= pb[0] && mx < pb[0] + pb[2] && my >= pb[1] && my < pb[1] + pb[3]) {
                    int c = PRESET_COLORS[i];
                    float[] hsv = rgbToHsv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                    colorH = hsv[0]; colorS = hsv[1]; colorV = hsv[2];
                    return true;
                }
            }

            // Buttons: 0=Reset, 1=Only (this), 2=All
            for (int i = 0; i < 3; i++) {
                int[] bb = buttonBounds(i);
                if (mx >= bb[0] && mx < bb[0] + bb[2] && my >= bb[1] && my < bb[1] + bb[3]) {
                    int c = hsvToRgb(colorH, colorS, colorV);
                    if (i == 0) {
                        resetHudColor(colorEditTarget.id);
                        WynnExtrasConfig.save();
                    } else if (i == 1) {
                        setHudColor(colorEditTarget.id, c);
                        WynnExtrasConfig.save();
                    } else if (i == 2) {
                        for (HudElement el : elements) {
                            setHudColor(el.id, c);
                        }
                        WynnExtrasConfig.save();
                    }
                    colorEditTarget = null;
                    return true;
                }
            }
            return true; // block other clicks while popup is open
        }

        if (isMouseOverWidgetToolbar(mx, my) && click.button() != 0) return true;
        if (click.button() == 0) {
            WynnExtrasConfig.Align alignment = alignmentAt(mx, my);
            if (alignment != null && selectedElement != null) {
                selectedElement.alignment = alignment;
                MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            }
            WynnExtrasConfig.GrowDirection direction = growDirectionAt(mx, my);
            if (direction != null && selectedElement != null) {
                int anchorY = growthAnchorY(selectedElement);
                selectedElement.growDirection = direction;
                selectedElement.y = anchorY - growthOffset(selectedElement);
                MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
                return true;
            }
            if (isMouseOverWidgetToolbar(mx, my)) return true;
            HudElement e = topElementAt(mx, my);
            if (e != null) {
                int split = e == selectedElement ? durabilitySplitAt(e, mx, my) : -1;
                selectedElement = e;
                pressedElement = e;
                pressedOverlapElements = elementsAt(mx, my);
                pendingDurabilitySplit = split;
                pressMouseX = mx;
                pressMouseY = my;
                dragMoved = false;
                e.dragging = true;
                e.dragOffX = (int) mx - e.x;
                e.dragOffY = (int) my - e.y;
                return true;
            }
            selectedElement = null;
            pressedElement = null;
            pressedOverlapElements = List.of();
            pendingDurabilitySplit = -1;
        } else if (HUD_COLOR_EDITING_ENABLED && click.button() == 1) {
            // Right-click: open color picker for hovered element
            for (int i = elements.size() - 1; i >= 0; i--) {
                HudElement e = elements.get(i);
                if (e.hovered(mx, my)) {
                    colorEditTarget = e;
                    Integer existing = hudColorOverride(e.id);
                    int c = existing != null ? existing : 0xFFFFFF;
                    float[] hsv = rgbToHsv((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF);
                    colorH = hsv[0]; colorS = hsv[1]; colorV = hsv[2];
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubleClick);
    }
    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        double mx = click.x(), my = click.y();
        if (colorEditTarget != null && colorDragMode > 0) {
            if (colorDragMode == 1) {
                int[] hb = hueBarBounds();
                colorH = Math.max(0f, Math.min(360f, (float)(my - hb[1]) / (hb[3] - 1) * 360f));
            } else if (colorDragMode == 2) {
                int[] sv = svBoxBounds();
                colorS = Math.max(0f, Math.min(1f, (float)(mx - sv[0]) / (sv[2] - 1)));
                colorV = Math.max(0f, Math.min(1f, 1f - (float)(my - sv[1]) / (sv[3] - 1)));
            }
            return true;
        }
        for (HudElement e : elements) {
            if (e.dragging) {
                if (!dragMoved && Math.hypot(mx - pressMouseX, my - pressMouseY) < 3) return true;
                dragMoved = true;
                pendingDurabilitySplit = -1;
                int newX = Math.max(0, Math.min(width - e.sw(), (int) mx - e.dragOffX));
                int newY = Math.max(0, Math.min(height - e.sh(), (int) my - e.dragOffY));

                int[] snapped = snapPosition(e, newX, newY);
                newX = snapped[0];
                newY = snapped[1];

                e.x = newX;
                e.y = newY;
                updateDurabilityDock(e, mx, my);
                return true;
            }
        }
        return super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (isMouseOverWidgetToolbar(mouseX, mouseY)) return true;
        HudElement e = topElementAt(mouseX, mouseY);
        if (e != null) {
            selectedElement = e;
            float newScale = e.scale + (float) verticalAmount * 0.1f;
            e.scale = e.id.equals("treemap")
                    ? TreeRoomMinimap.clampScale(newScale)
                    : Math.max(0.5f, Math.min(4.0f, newScale));
            e.scaleChanged = true;
            if (e.id.startsWith("durability:")) {
                for (HudElement other : elements) {
                    if (other.id.startsWith("durability:")) other.scale = e.scale;
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseReleased(Click click) {
        colorDragMode = 0;
        HudElement draggedElement = elements.stream().filter(element -> element.dragging).findFirst().orElse(null);
        if (draggedElement != null && Math.hypot(click.x() - pressMouseX, click.y() - pressMouseY) >= 3) {
            dragMoved = true;
            pendingDurabilitySplit = -1;
        }
        if (dragMoved && draggedElement != null && draggedElement.durabilityColumn >= 0 && dockTargetColumn >= 0) {
            saveDurabilityElementPositions();
            if (WynnExtrasConfig.INSTANCE.dockDurabilityColumn(
                    draggedElement.durabilityColumn, dockTargetColumn, dockBeforeTarget)) {
                rebuildDurabilityElements();
                selectedElement = findDurabilityColumn(Math.min(draggedElement.durabilityColumn, dockTargetColumn));
                MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
            }
        } else if (!dragMoved && pressedElement != null && pendingDurabilitySplit > 0) {
            saveDurabilityElementPositions();
            WynnExtrasConfig config = WynnExtrasConfig.INSTANCE;
            int sourceColumn = pressedElement.durabilityColumn;
            int newColumnX = Math.min(width - 40, pressedElement.x + pressedElement.sw() + 16);
            if (config.splitDurabilityColumn(sourceColumn, pendingDurabilitySplit)) {
                rebuildDurabilityElements();
                selectedElement = findDurabilityColumn(sourceColumn + 1);
                if (selectedElement != null) {
                    selectedElement.x = Math.clamp(newColumnX, 0, Math.max(0, width - selectedElement.sw()));
                    selectedElement.y = pressedElement.y;
                }
                MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
            }
        } else if (!dragMoved && pressedElement != null && pressedOverlapElements.size() > 1) {
            int current = pressedOverlapElements.indexOf(pressedElement);
            selectedElement = pressedOverlapElements.get((current + 1) % pressedOverlapElements.size());
            MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
        }
        for (HudElement e : elements) {
            e.dragging = false;
            e.snappedX = false;
            e.snappedY = false;
        }
        snapGuideX = -1;
        snapGuideY = -1;
        dockTargetColumn = -1;
        dockGuideX = -1;
        dockGuideY = -1;
        pressedElement = null;
        pressedOverlapElements = List.of();
        pendingDurabilitySplit = -1;
        dragMoved = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        int modifiers = input.modifiers();

        // Color picker: Esc cancels
        if (colorEditTarget != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                colorEditTarget = null;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER) {
                int c = hsvToRgb(colorH, colorS, colorV);
                setHudColor(colorEditTarget.id, c);
                WynnExtrasConfig.save();
                colorEditTarget = null;
                return true;
            }
            return true;
        }

        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (selectedElement != null && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT
                || keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            int amount = shift ? 10 : 1;
            if (keyCode == GLFW.GLFW_KEY_LEFT) selectedElement.x -= amount;
            if (keyCode == GLFW.GLFW_KEY_RIGHT) selectedElement.x += amount;
            if (keyCode == GLFW.GLFW_KEY_UP) selectedElement.y -= amount;
            if (keyCode == GLFW.GLFW_KEY_DOWN) selectedElement.y += amount;
            selectedElement.x = Math.clamp(selectedElement.x, 0, Math.max(0, width - selectedElement.sw()));
            selectedElement.y = Math.clamp(selectedElement.y, 0, Math.max(0, height - selectedElement.sh()));
            return true;
        }
        if (selectedElement != null && keyCode == GLFW.GLFW_KEY_R) {
            selectedElement.x = selectedElement.initialX;
            selectedElement.y = selectedElement.initialY;
            selectedElement.scale = selectedElement.initialScale;
            if (selectedElement.durabilityColumn >= 0) {
                for (HudElement element : elements) {
                    if (element.durabilityColumn >= 0) element.scale = selectedElement.scale;
                }
            }
            return true;
        }
        return super.keyPressed(input);
    }

    private HudElement topElementAt(double mouseX, double mouseY) {
        if (selectedElement != null && selectedElement.hovered(mouseX, mouseY)) return selectedElement;
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            if (element.hovered(mouseX, mouseY)) return element;
        }
        return null;
    }

    private List<HudElement> elementsAt(double mouseX, double mouseY) {
        List<HudElement> result = new ArrayList<>();
        for (int i = elements.size() - 1; i >= 0; i--) {
            HudElement element = elements.get(i);
            if (element.hovered(mouseX, mouseY)) result.add(element);
        }
        return result;
    }

    private void renderOverlapHint(DrawContext context, int mouseX, int mouseY) {
        if (elements.stream().anyMatch(element -> element.dragging)) return;
        if (isMouseOverWidgetToolbar(mouseX, mouseY)) return;
        if (selectedElement != null && durabilitySplitAt(selectedElement, mouseX, mouseY) > 0) return;
        List<HudElement> hovered = elementsAt(mouseX, mouseY);
        if (hovered.size() < 2) return;
        String text = hovered.size() + " overlapping! Click to cycle";
        int hintW = textRenderer.getWidth(text) + 10;
        int x = Math.clamp(mouseX + 8, 4, Math.max(4, width - hintW - 4));
        int y = Math.clamp(mouseY + 8, 4, Math.max(4, height - 17));
        context.fill(x, y, x + hintW, y + 13, 0xDD111111);
        context.drawTextWithShadow(textRenderer, text, x + 5, y + 2, 0xFFAAAAAA);
    }

    private void renderDurabilitySplitTooltip(DrawContext context, int mouseX, int mouseY) {
        if (selectedElement == null || selectedElement.dragging
                || durabilitySplitAt(selectedElement, mouseX, mouseY) <= 0) return;
        context.drawTooltip(Text.literal("Click to split the rows below into a new column"), mouseX, mouseY);
    }

    private boolean supportsAlignment(HudElement element) {
        return element != null && ("consumables".equals(element.id) || element.durabilityColumn >= 0);
    }

    private void renderWidgetToolbar(DrawContext context, HudElement element, int mouseX, int mouseY) {
        int[] toolbar = widgetToolbarBounds(element);
        drawToolbarGroup(context, toolbar[0], toolbar[1], 122, "Allignment");
        drawToolbarGroup(context, toolbar[0] + 126, toolbar[1], 84, "Direction");
        WynnExtrasConfig.Align[] alignments = WynnExtrasConfig.Align.values();
        for (int i = 0; i < alignments.length; i++) {
            int[] bounds = widgetToolbarButtonBounds(element, i);
            boolean hovered = mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
            int color = element.alignment == alignments[i] ? 0xFF267080 : hovered ? 0xFF3A3A3A : 0xFF252525;
            context.fill(bounds[0], bounds[1], bounds[0] + bounds[2], bounds[1] + bounds[3], color);
            context.drawCenteredTextWithShadow(textRenderer, alignments[i].toString(),
                    bounds[0] + bounds[2] / 2, bounds[1] + 2, 0xFFFFFFFF);
        }
        WynnExtrasConfig.GrowDirection[] directions = {
                WynnExtrasConfig.GrowDirection.UP, WynnExtrasConfig.GrowDirection.DOWN};
        for (int i = 0; i < directions.length; i++) {
            int[] bounds = widgetToolbarButtonBounds(element, i + 3);
            boolean hovered = mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
            int color = element.growDirection == directions[i] ? 0xFF806026 : hovered ? 0xFF3A3A3A : 0xFF252525;
            context.fill(bounds[0], bounds[1], bounds[0] + bounds[2], bounds[1] + bounds[3], color);
            context.drawCenteredTextWithShadow(textRenderer, directions[i].toString(),
                    bounds[0] + bounds[2] / 2, bounds[1] + 2, 0xFFFFFFFF);
        }
    }

    private WynnExtrasConfig.Align alignmentAt(double mouseX, double mouseY) {
        if (!supportsAlignment(selectedElement)) return null;
        WynnExtrasConfig.Align[] alignments = WynnExtrasConfig.Align.values();
        for (int i = 0; i < alignments.length; i++) {
            int[] bounds = widgetToolbarButtonBounds(selectedElement, i);
            if (mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3]) return alignments[i];
        }
        return null;
    }

    private WynnExtrasConfig.GrowDirection growDirectionAt(double mouseX, double mouseY) {
        if (!supportsAlignment(selectedElement)) return null;
        WynnExtrasConfig.GrowDirection[] directions = {
                WynnExtrasConfig.GrowDirection.UP, WynnExtrasConfig.GrowDirection.DOWN};
        for (int i = 0; i < directions.length; i++) {
            int[] bounds = widgetToolbarButtonBounds(selectedElement, i + 3);
            if (mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                    && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3]) return directions[i];
        }
        return null;
    }

    private int[] widgetToolbarButtonBounds(HudElement element, int index) {
        int buttonW = 38;
        int gap = 2;
        int[] toolbar = widgetToolbarBounds(element);
        int buttonX = index < 3
                ? toolbar[0] + 3 + index * (buttonW + gap)
                : toolbar[0] + 129 + (index - 3) * (buttonW + gap);
        return new int[]{buttonX, toolbar[1] + 13, buttonW, 16};
    }

    private int[] widgetToolbarBounds(HudElement element) {
        int toolbarW = 210;
        int toolbarH = 32;
        int toolbarX = Math.clamp(element.x + element.sw() / 2 - toolbarW / 2,
                4, Math.max(4, width - toolbarW - 4));
        int toolbarY = element.y - toolbarH - 4 >= 4
                ? element.y - toolbarH - 4
                : Math.min(height - toolbarH - 4, element.y + element.sh() + 4);
        return new int[]{toolbarX, toolbarY, toolbarW, toolbarH};
    }

    private boolean isMouseOverWidgetToolbar(double mouseX, double mouseY) {
        if (!supportsAlignment(selectedElement)) return false;
        int[] bounds = widgetToolbarBounds(selectedElement);
        return mouseX >= bounds[0] && mouseX < bounds[0] + bounds[2]
                && mouseY >= bounds[1] && mouseY < bounds[1] + bounds[3];
    }

    private void drawToolbarGroup(DrawContext context, int x, int y, int w, String label) {
        context.fill(x, y, x + w, y + 32, 0xDD111111);
        context.fill(x, y, x + w, y + 1, 0xFF777777);
        context.fill(x, y + 31, x + w, y + 32, 0xFF777777);
        context.fill(x, y, x + 1, y + 32, 0xFF777777);
        context.fill(x + w - 1, y, x + w, y + 32, 0xFF777777);
        context.drawCenteredTextWithShadow(textRenderer, label, x + w / 2, y + 2, 0xFFBBBBBB);
    }

    private int[] snapPosition(HudElement element, int x, int y) {
        int snappedX = x;
        int snappedY = y;
        snapGuideX = -1;
        snapGuideY = -1;
        int centerX = x + element.sw() / 2;
        int centerY = y + element.sh() / 2;
        if (Math.abs(centerX - width / 2) < SNAP_DIST) {
            snappedX = width / 2 - element.sw() / 2;
            snapGuideX = width / 2;
        }
        if (Math.abs(centerY - height / 2) < SNAP_DIST) {
            snappedY = height / 2 - element.sh() / 2;
            snapGuideY = height / 2;
        }
        element.snappedX = snapGuideX >= 0;
        element.snappedY = snapGuideY >= 0;
        return new int[]{Math.clamp(snappedX, 0, Math.max(0, width - element.sw())),
                Math.clamp(snappedY, 0, Math.max(0, height - element.sh()))};
    }

    private void rememberInitialState(HudElement element) {
        if (element.initialStateSet) return;
        element.initialX = element.x;
        element.initialY = element.y;
        element.initialScale = element.scale;
        element.initialStateSet = true;
    }

    private String displayName(HudElement element) {
        if (element.durabilityColumn >= 0) return "Durability column " + (element.durabilityColumn + 1);
        String id = element.id.replaceAll("([a-z])([A-Z])", "$1 $2");
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    private String durabilityPreviewText(WynnExtrasConfig.DurabilityFormat format, int row) {
        int[] currentValues = {350, 82, 9, 275, 41, 63, 7, 188, 24};
        int[] maximumValues = {350, 120, 52, 310, 98, 84, 65, 240, 110};
        int current = currentValues[row % currentValues.length];
        int maximum = maximumValues[row % maximumValues.length];
        int percentage = current * 100 / maximum;
        return switch (format) {
            case RAW -> current + "/" + maximum;
            case PERCENT -> percentage + "%";
            case BOTH -> current + "/" + maximum + " (" + percentage + "%)";
        };
    }

    private void drawDurabilityPreviewIcon(DrawContext context, String itemId, int x, int y) {
        ItemStack equipped = equippedDurabilityPreviewStack(itemId);
        if (!equipped.isEmpty()) {
            context.drawItem(equipped, x, y);
            return;
        }

        Identifier texture = durabilityPlaceholderTexture(itemId);
        context.drawTexture(RenderPipelines.GUI_TEXTURED, texture,
                x, y, 0, 0, 16, 16, 16, 16, 16, 16, 0xFF666666);
    }

    private ItemStack equippedDurabilityPreviewStack(String itemId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return ItemStack.EMPTY;
        ItemStack stack = switch (itemId) {
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
        return WynnItemParser.parse(stack).flatMap(item -> item.durability()).isPresent()
                ? stack : ItemStack.EMPTY;
    }

    private Identifier durabilityPlaceholderTexture(String itemId) {
        return switch (itemId) {
            case WynnExtrasConfig.DURABILITY_ITEM_WEAPON -> Identifier.ofVanilla("textures/item/bow.png");
            case WynnExtrasConfig.DURABILITY_ITEM_HELMET -> Identifier.ofVanilla("textures/item/diamond_helmet.png");
            case WynnExtrasConfig.DURABILITY_ITEM_CHESTPLATE -> Identifier.ofVanilla("textures/item/diamond_chestplate.png");
            case WynnExtrasConfig.DURABILITY_ITEM_LEGGINGS -> Identifier.ofVanilla("textures/item/diamond_leggings.png");
            case WynnExtrasConfig.DURABILITY_ITEM_BOOTS -> Identifier.ofVanilla("textures/item/diamond_boots.png");
            case WynnExtrasConfig.DURABILITY_ITEM_RING_1, WynnExtrasConfig.DURABILITY_ITEM_RING_2 ->
                    Identifier.ofVanilla("textures/wynn/gui/slot/ring.png");
            case WynnExtrasConfig.DURABILITY_ITEM_BRACELET ->
                    Identifier.ofVanilla("textures/wynn/gui/slot/bracelet.png");
            case WynnExtrasConfig.DURABILITY_ITEM_NECKLACE ->
                    Identifier.ofVanilla("textures/wynn/gui/slot/necklace.png");
            default -> Identifier.ofVanilla("textures/item/barrier.png");
        };
    }

    private int darkenColor(int color) {
        int red = (color >> 16 & 0xFF) * 2 / 5;
        int green = (color >> 8 & 0xFF) * 2 / 5;
        int blue = (color & 0xFF) * 2 / 5;
        return color & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private HudElement findDurabilityColumn(int column) {
        return elements.stream()
                .filter(element -> element.durabilityColumn == column)
                .findFirst()
                .orElse(null);
    }

    private boolean canSplitDurabilityColumn() {
        if (WynnExtrasConfig.INSTANCE.getDurabilityColumnCount() >= 3) return false;
        return elements.stream().anyMatch(element -> element.durabilityItems.size() > 1);
    }

    private int durabilitySplitAt(HudElement element, double mouseX, double mouseY) {
        if (element != selectedElement || element.durabilityColumn < 0 || !canSplitDurabilityColumn()) return -1;
        if (mouseX < element.x || mouseX >= element.x + element.sw()) return -1;
        for (int split = 1; split < element.durabilityItems.size(); split++) {
            int splitY = element.y + Math.round(split * 18 * element.scale);
            if (Math.abs(mouseY - splitY) <= 4) return split;
        }
        return -1;
    }

    private void updateDurabilityDock(HudElement dragged, double mouseX, double mouseY) {
        dockTargetColumn = -1;
        dockGuideX = -1;
        dockGuideY = -1;
        if (dragged.durabilityColumn < 0) return;

        HudElement cursorTarget = null;
        boolean cursorBefore = false;
        int cursorDistance = DOCK_DIST + 1;
        for (HudElement target : elements) {
            if (target == dragged || target.durabilityColumn < 0
                    || mouseX < target.x || mouseX >= target.x + target.sw()) continue;
            int topDistance = (int) Math.abs(mouseY - target.y);
            if (topDistance < cursorDistance) {
                cursorDistance = topDistance;
                cursorTarget = target;
                cursorBefore = true;
            }
            int bottomDistance = (int) Math.abs(mouseY - (target.y + target.sh()));
            if (bottomDistance < cursorDistance) {
                cursorDistance = bottomDistance;
                cursorTarget = target;
                cursorBefore = false;
            }
        }
        if (cursorTarget != null && cursorDistance <= DOCK_DIST) {
            setDurabilityDockTarget(cursorTarget, cursorBefore);
            return;
        }

        int bestDistance = DOCK_DIST + 1;
        HudElement elementTarget = null;
        boolean elementBefore = false;
        for (HudElement target : elements) {
            if (target == dragged || target.durabilityColumn < 0) continue;
            int overlap = Math.min(dragged.x + dragged.sw(), target.x + target.sw())
                    - Math.max(dragged.x, target.x);
            if (overlap < Math.min(dragged.sw(), target.sw()) / 3) continue;

            int aboveDistance = Math.abs(dragged.y + dragged.sh() - target.y);
            if (aboveDistance < bestDistance) {
                bestDistance = aboveDistance;
                elementTarget = target;
                elementBefore = true;
            }
            int belowDistance = Math.abs(dragged.y - (target.y + target.sh()));
            if (belowDistance < bestDistance) {
                bestDistance = belowDistance;
                elementTarget = target;
                elementBefore = false;
            }
        }
        if (elementTarget != null && bestDistance <= DOCK_DIST) {
            setDurabilityDockTarget(elementTarget, elementBefore);
        }
    }

    private void setDurabilityDockTarget(HudElement target, boolean beforeTarget) {
        dockTargetColumn = target.durabilityColumn;
        dockBeforeTarget = beforeTarget;
        dockGuideX = target.x;
        dockGuideW = target.sw();
        dockGuideY = beforeTarget ? target.y : target.y + target.sh();
    }

    private int alignmentOffset(HudElement element) {
        return switch (element.alignment) {
            case CENTER -> element.sw() / 2;
            case RIGHT -> element.sw();
            default -> 0;
        };
    }

    private int alignmentAnchorX(HudElement element) {
        return element.x + alignmentOffset(element);
    }

    private int growthOffset(HudElement element) {
        if (element.growDirection != WynnExtrasConfig.GrowDirection.UP || element.durabilityItems.size() < 2) {
            return 0;
        }
        return Math.round((element.durabilityItems.size() - 1) * 18 * element.scale);
    }

    private int growthAnchorY(HudElement element) {
        return element.y + growthOffset(element);
    }

    private void saveDurabilityElementPositions() {
        WynnExtrasConfig config = WynnExtrasConfig.INSTANCE;
        for (HudElement element : elements) {
            if (element.durabilityColumn < 0) continue;
            config.setDurabilityHudColumnPosition(
                    element.durabilityColumn, alignmentAnchorX(element), growthAnchorY(element));
            config.durabilityHudScale = element.scale;
        }
    }

    private void rebuildDurabilityElements() {
        elements.removeIf(element -> element.durabilityColumn >= 0);
        addDurabilityElements(WynnExtrasConfig.INSTANCE);
        for (HudElement element : elements) {
            if (element.durabilityColumn < 0) continue;
            element.w = element.previewLines.stream().mapToInt(textRenderer::getWidth).max().orElse(1);
            if (element.alignedAnchor) element.x -= alignmentOffset(element);
            if (element.alignedAnchor) element.y -= growthOffset(element);
            element.x = Math.max(4, Math.min(width - element.sw() - 4, element.x));
            element.y = Math.max(4, Math.min(height - element.sh() - 4, element.y));
            rememberInitialState(element);
        }
    }

    @Override
    public void close() {
        WynnExtrasConfig c = WynnExtrasConfig.INSTANCE;
        for (HudElement e : elements) {
            if (e.id.startsWith("durability:")) {
                int column = Integer.parseInt(e.id.substring("durability:".length()));
                c.setDurabilityHudColumnPosition(column, alignmentAnchorX(e), growthAnchorY(e));
                c.durabilityHudScale = e.scale;
                c.setDurabilityHudColumnAlignment(column, e.alignment);
                c.setDurabilityHudColumnGrowDirection(column, e.growDirection);
                continue;
            }
            switch (e.id) {
                case "provoke" -> {
                    c.provokeTimerX = e.x + e.sw() / 2; c.provokeTimerY = e.y + e.sh() / 2; c.provokeTimerScale = e.scale; c.provokeTimerAlignment = e.alignment;
                }
                case "totem" -> {
                    c.totemTimerX = e.x + e.sw() / 2; c.totemTimerY = e.y + e.sh() / 2; c.totemTimerScale = e.scale; c.totemTimerAlignment = e.alignment;
                }
                case "blood" -> {
                    c.bloodSorrowTimerX = e.x + e.sw() / 2; c.bloodSorrowTimerY = e.y + e.sh() / 2; c.bloodSorrowTimerScale = e.scale; c.bloodSorrowAlignment = e.alignment;
                }
                case "curse" -> {
                    c.curseTrackerX = e.x + e.sw() / 2; c.curseTrackerY = e.y + e.sh() / 2; c.curseTrackerScale = e.scale; c.curseTrackerAlignment = e.alignment;
                }
                case "warning" -> {
                    c.totemWarningX = e.x + e.sw() / 2; c.totemWarningY = e.y + e.sh() / 2; c.totemWarningScale = e.scale; c.totemWarningAlignment = e.alignment;
                }
                case "consumables" -> {
                    c.consumableTimersX = alignmentAnchorX(e); c.consumableTimersY = growthAnchorY(e); c.consumableTimersScale = e.scale;
                    c.consumableTimersAlignment = e.alignment;
                    c.consumableGrowDirection = e.growDirection;
                }
                case "durabilityWarning" -> {
                    c.durabilityWarnHudX = e.x + e.sw() / 2; c.durabilityWarnHudY = e.y; c.durabilityWarnHudScale = e.scale;
                }
                case "consumableWarning" -> {
                    c.consumableWarnHudX = e.x + e.sw() / 2; c.consumableWarnHudY = e.y; c.consumableWarnHudScale = e.scale;
                }
                case "radiant" -> {
                    c.radiantHudX = e.x; c.radiantHudY = e.y; c.radiantHudScale = e.scale;
                }
                case "profession" -> {
                    c.professionOverlayX = e.x; c.professionOverlayY = e.y; c.professionOverlayScale = e.scale;
                }
                case "notifier" -> {
                    c.notifierX = e.x + e.sw() / 2; c.notifierY = e.y + e.sh() / 2; c.notifierScale = e.scale; c.notifierAlignment = e.alignment;
                }
                case "treemap" -> {
                    c.treeMapX = e.x;
                    c.treeMapY = e.y;
                    if (!e.preserveDefaultScale || e.scaleChanged) {
                        c.tnaTreeMapScale = e.scale;
                    }
                }
                case "weeklyWars" -> {
                    c.weeklyWarCountX = e.x; c.weeklyWarCountY = e.y;
                }
                case "serverTps" -> {
                    c.serverTpsX = e.x; c.serverTpsY = e.y; c.serverTpsScale = e.scale;
                }
                case "fps" -> {
                    c.fpsHudX = e.x; c.fpsHudY = e.y; c.fpsHudScale = e.scale;
                }
                case "warDps" -> {
                    c.warDpsX = e.x; c.warDpsY = e.y;
                }
                case "attackTimer" -> {
                    c.attackTimerX = e.x; c.attackTimerY = e.y;
                }
                case "raidSession" -> {
                    c.raidSessionHudX = e.x; c.raidSessionHudY = e.y; c.raidSessionHudScale = e.scale;
                }
            }
        }
        WynnExtrasConfig.save();
        TreeRoomMinimap.syncFromConfig();
        MinecraftClient.getInstance().setScreen(parent);
    }
}
