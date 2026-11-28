package julianh06.wynnextras.config.configoptions;

import julianh06.wynnextras.config.WynnExtrasConfig;
import julianh06.wynnextras.utils.MinecraftUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

import static julianh06.wynnextras.config.ConfigTheme.*;

public class DurabilityLayoutOption extends ConfigOption {
    private static final int ROW_HEIGHT = 18;
    private static final int TITLE_HEIGHT = 16;
    private static final int INSTRUCTION_HEIGHT = 14;
    private static final int GAP = 6;

    private final WynnExtrasConfig config;
    private String draggedItem;
    private int dragSourceColumn = -2;
    private int dragSourceIndex = -1;
    private int lastX;
    private int lastY;
    private int lastW;

    public DurabilityLayoutOption(String name, String desc, WynnExtrasConfig config) {
        super(name, desc);
        this.config = config;
    }

    @Override
    public int getHeight(int contentW) {
        List<List<String>> columns = config.getDurabilityItemColumns();
        int maxColumnItems = Math.max(1, columns.stream().mapToInt(List::size).max().orElse(0));
        int hiddenRows = Math.max(1, (config.getHiddenDurabilityItems().size() + 2) / 3);
        return headerHeight(contentW) + INSTRUCTION_HEIGHT + TITLE_HEIGHT + maxColumnItems * ROW_HEIGHT
                + 10 + TITLE_HEIGHT + hiddenRows * ROW_HEIGHT + 12;
    }

    @Override
    public void render(DrawContext ctx, int x, int y, int w, int h, int mx, int my,
                       boolean hovered, int categoryColor) {
        lastX = x;
        lastY = y;
        lastW = w;
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.fill(x, y, x + w, y + h - 5, hovered ? PARCHMENT_HOVER : PARCHMENT);
        ctx.fill(x, y, x + w, y + 1, BORDER_LIGHT);
        ctx.fill(x, y + h - 6, x + w, y + h - 5, BORDER_DARK);
        drawWrappedTexts(ctx, x, y, w, 0, name, desc, richDesc, TEXT_LIGHT, TEXT_DIM);

        List<List<String>> columns = config.getDurabilityItemColumns();
        List<String> hidden = config.getHiddenDurabilityItems();
        Layout layout = layout(x, y, w, columns, hidden);
        ctx.drawTextWithShadow(tr, "Drag items to arrange them. Move items to Hidden to disable them.",
                layout.listX, y + headerHeight(w) + 1, TEXT_DIM);
        for (int column = 0; column < 3; column++) {
            int columnX = layout.listX + column * (layout.columnW + GAP);
            ctx.drawTextWithShadow(tr, "Column " + (column + 1), columnX + 4, layout.columnsY + 4, categoryColor);
            int rowY = layout.columnsY + TITLE_HEIGHT;
            List<String> items = columns.get(column);
            if (items.isEmpty()) {
                drawEmptyRow(ctx, columnX, rowY, layout.columnW);
            } else {
                for (String id : items) {
                    drawItemRow(ctx, columnX, rowY, layout.columnW, id, mx, my);
                    rowY += ROW_HEIGHT;
                }
            }
        }

        ctx.drawTextWithShadow(tr, "Hidden", layout.listX + 4, layout.hiddenY + 4, categoryColor);
        int hiddenRows = Math.max(1, (hidden.size() + 2) / 3);
        if (hidden.isEmpty()) {
            drawEmptyRow(ctx, layout.listX, layout.hiddenY + TITLE_HEIGHT,
                    layout.columnW * 3 + GAP * 2);
        } else {
            for (int i = 0; i < hidden.size(); i++) {
                int column = i % 3;
                int row = i / 3;
                int itemX = layout.listX + column * (layout.columnW + GAP);
                int itemY = layout.hiddenY + TITLE_HEIGHT + row * ROW_HEIGHT;
                drawItemRow(ctx, itemX, itemY, layout.columnW, hidden.get(i), mx, my);
            }
        }

        if (draggedItem != null) {
            DropTarget target = dropTarget(mx, my, layout, columns, hidden);
            if (target != null && !target.hidden) {
                int indicatorX = layout.listX + target.column * (layout.columnW + GAP);
                int indicatorY = layout.columnsY + TITLE_HEIGHT + target.index * ROW_HEIGHT;
                ctx.fill(indicatorX, indicatorY, indicatorX + layout.columnW, indicatorY + 2, GOLD);
            } else if (target != null) {
                int hiddenW = layout.columnW * 3 + GAP * 2;
                ctx.fill(layout.listX, layout.hiddenY + TITLE_HEIGHT,
                        layout.listX + hiddenW, layout.hiddenY + TITLE_HEIGHT + hiddenRows * ROW_HEIGHT,
                        0x3344AA44);
            }
            drawGhostRow(ctx, mx - layout.columnW / 2, my - ROW_HEIGHT / 2,
                    layout.columnW, draggedItem);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int x, int y, int w, int h, int btn) {
        if (btn != 0) return false;
        lastX = x;
        lastY = y;
        lastW = w;
        List<List<String>> columns = config.getDurabilityItemColumns();
        List<String> hidden = config.getHiddenDurabilityItems();
        Layout layout = layout(x, y, w, columns, hidden);

        for (int column = 0; column < 3; column++) {
            int columnX = layout.listX + column * (layout.columnW + GAP);
            int index = rowIndexAt(mx, my, columnX, layout.columnsY + TITLE_HEIGHT,
                    layout.columnW, columns.get(column).size());
            if (index >= 0) {
                draggedItem = columns.get(column).get(index);
                dragSourceColumn = column;
                dragSourceIndex = index;
                return true;
            }
        }

        int hiddenIndex = hiddenIndexAt(mx, my, layout, hidden.size());
        if (hiddenIndex >= 0) {
            draggedItem = hidden.get(hiddenIndex);
            dragSourceColumn = -1;
            dragSourceIndex = hiddenIndex;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        if (draggedItem == null) return false;
        List<List<String>> columns = mutableColumns();
        List<String> hidden = new ArrayList<>(config.getHiddenDurabilityItems());
        Layout layout = layout(lastX, lastY, lastW, columns, hidden);
        DropTarget target = dropTarget(mx, my, layout, columns, hidden);

        if (target != null) {
            for (List<String> column : columns) column.remove(draggedItem);
            hidden.remove(draggedItem);
            if (target.hidden) {
                hidden.add(draggedItem);
            } else {
                int index = target.index;
                if (dragSourceColumn == target.column && index > dragSourceIndex) index--;
                List<String> destination = columns.get(target.column);
                destination.add(Math.clamp(index, 0, destination.size()), draggedItem);
            }
            config.setDurabilityItemLayout(columns, hidden);
            MinecraftUtils.playSoundUI(SoundEvents.UI_BUTTON_CLICK.value());
        }

        draggedItem = null;
        dragSourceColumn = -2;
        dragSourceIndex = -1;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int x, int y, int w, int h) {
        return draggedItem != null;
    }

    private void drawItemRow(DrawContext ctx, int x, int y, int w, String id, int mx, int my) {
        var tr = MinecraftClient.getInstance().textRenderer;
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW_HEIGHT - 2;
        int bg = id.equals(draggedItem) ? BG_DARK : (hover ? BG_LIGHT : BG_MEDIUM);
        ctx.fill(x, y, x + w, y + ROW_HEIGHT - 2, bg);
        ctx.fill(x, y, x + w, y + 1, BORDER_LIGHT);
        ctx.drawTextWithShadow(tr, "=", x + 5, y + 5, TEXT_DIM);
        String label = WynnExtrasConfig.DURABILITY_ITEM_NAMES.getOrDefault(id, id);
        int availableWidth = Math.max(1, w - 19);
        String visible = tr.trimToWidth(label, availableWidth);
        ctx.drawTextWithShadow(tr, visible, x + 16, y + 5, TEXT_LIGHT);
    }

    private void drawEmptyRow(DrawContext ctx, int x, int y, int w) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.fill(x, y, x + w, y + ROW_HEIGHT - 2, BG_DARK);
        ctx.drawCenteredTextWithShadow(tr, "Drop here", x + w / 2, y + 5, TEXT_DIM);
    }

    private void drawGhostRow(DrawContext ctx, int x, int y, int w, String id) {
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.fill(x, y, x + w, y + ROW_HEIGHT - 2, 0xDD4d3c2d);
        ctx.fill(x, y, x + w, y + 1, GOLD);
        String label = WynnExtrasConfig.DURABILITY_ITEM_NAMES.getOrDefault(id, id);
        ctx.drawTextWithShadow(tr, tr.trimToWidth(label, Math.max(1, w - 12)), x + 6, y + 5, TEXT_LIGHT);
    }

    private int headerHeight(int width) {
        var tr = MinecraftClient.getInstance().textRenderer;
        int textWidth = Math.max(20, width - 16);
        int nameLines = Math.max(1, tr.wrapLines(Text.literal(name), textWidth).size());
        int descLines = desc == null || desc.isEmpty()
                ? 0 : Math.max(1, tr.wrapLines(Text.literal(desc), textWidth).size());
        return 8 + nameLines * 10 + 4 + descLines * 10 + 8;
    }

    private Layout layout(int x, int y, int w, List<List<String>> columns, List<String> hidden) {
        int listX = x + 8;
        int columnW = Math.max(40, (w - 16 - GAP * 2) / 3);
        int columnsY = y + headerHeight(w) + INSTRUCTION_HEIGHT;
        int maxColumnItems = Math.max(1, columns.stream().mapToInt(List::size).max().orElse(0));
        int hiddenY = columnsY + TITLE_HEIGHT + maxColumnItems * ROW_HEIGHT + 10;
        return new Layout(listX, columnW, columnsY, hiddenY);
    }

    private DropTarget dropTarget(double mx, double my, Layout layout,
                                  List<List<String>> columns, List<String> hidden) {
        for (int column = 0; column < 3; column++) {
            int x = layout.listX + column * (layout.columnW + GAP);
            int y = layout.columnsY + TITLE_HEIGHT;
            int count = columns.get(column).size();
            int height = Math.max(1, count) * ROW_HEIGHT;
            if (mx >= x && mx < x + layout.columnW && my >= y - 3 && my < y + height + 3) {
                int index = count == 0 ? 0
                        : Math.clamp((int) ((my - y + ROW_HEIGHT / 2.0) / ROW_HEIGHT), 0, count);
                return new DropTarget(column, index, false);
            }
        }

        int hiddenRows = Math.max(1, (hidden.size() + 2) / 3);
        int hiddenWidth = layout.columnW * 3 + GAP * 2;
        int hiddenTop = layout.hiddenY + TITLE_HEIGHT;
        if (mx >= layout.listX && mx < layout.listX + hiddenWidth
                && my >= hiddenTop && my < hiddenTop + hiddenRows * ROW_HEIGHT) {
            return new DropTarget(-1, hidden.size(), true);
        }
        return null;
    }

    private int rowIndexAt(double mx, double my, int x, int y, int w, int count) {
        if (count == 0 || mx < x || mx >= x + w || my < y || my >= y + count * ROW_HEIGHT) return -1;
        return Math.clamp((int) ((my - y) / ROW_HEIGHT), 0, count - 1);
    }

    private int hiddenIndexAt(double mx, double my, Layout layout, int count) {
        if (count == 0) return -1;
        int hiddenTop = layout.hiddenY + TITLE_HEIGHT;
        int hiddenRows = (count + 2) / 3;
        if (mx < layout.listX || my < hiddenTop || my >= hiddenTop + hiddenRows * ROW_HEIGHT) return -1;
        int column = (int) ((mx - layout.listX) / (layout.columnW + GAP));
        int row = (int) ((my - hiddenTop) / ROW_HEIGHT);
        if (column < 0 || column >= 3 || row < 0) return -1;
        int cellX = layout.listX + column * (layout.columnW + GAP);
        if (mx < cellX || mx >= cellX + layout.columnW) return -1;
        int index = row * 3 + column;
        return index < count ? index : -1;
    }

    private List<List<String>> mutableColumns() {
        List<List<String>> result = new ArrayList<>();
        for (List<String> column : config.getDurabilityItemColumns()) result.add(new ArrayList<>(column));
        return result;
    }

    private record Layout(int listX, int columnW, int columnsY, int hiddenY) {}
    private record DropTarget(int column, int index, boolean hidden) {}
}
