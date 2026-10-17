package com.lai.recipesender.client;

import com.lai.recipesender.config.RecipeSenderConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 游戏内配置界面：模组列表 → 选中「配方材料发送器」→ 「配置」。
 *
 * <p><b>为什么需要这个界面</b>：Forge 只按 {@code ForgeConfigSpec} 生成 toml，不生成界面。
 * 模组列表里那个「配置」按钮的可用状态直接取自
 * {@code ConfigScreenHandler.getScreenFactoryFor(modInfo).isPresent()}，
 * 没有注册扩展点就永远是灰的（本模组 1.0.32 之前一直如此）。注册见
 * {@code com.lai.recipesender.ClientBootstrap}。
 *
 * <p><b>界面只做校验与落盘</b>：取值范围、默认值、注释文案全部来自
 * {@link RecipeSenderConfig}，这里不复制一份。改完点「保存」才写配置，
 * 「取消」直接丢弃——所以输入框里的半截数字不会在玩家没确认时生效。
 *
 * <p><b>改动什么时候生效</b>：配置是 COMMON，读取方在服务端。单人存档里客户端与服务端
 * 同一个进程，保存后下一次统计/发送就用新值；多人服务器上这份配置只是客户端本地的副本，
 * 真正生效的是服务端那份（面板底部有这行说明）。
 */
public final class RecipeSenderConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 320;
    private static final int HEADER_HEIGHT = 30;
    private static final int ROW_GAP = 26;
    private static final int STATUS_HEIGHT = 14;
    private static final int NOTE_HEIGHT = 12;
    private static final int FOOTER_HEIGHT = 30;
    private static final int PADDING = 10;
    private static final int FIELD_WIDTH = 66;
    private static final int HINT_GAP = 8;
    private static final int BUTTON_GAP = 6;
    private static final int ROWS = 4;

    private final Screen parent;

    /** 配方管理距离输入框。 */
    private BoundEditBox distanceBox;
    /** 份数上限输入框。 */
    private BoundEditBox batchesBox;
    /** 单块份数输入框。 */
    private BoundEditBox chunkBox;

    /** 流体开关的草稿值：点「保存」之前不动配置。 */
    private boolean fluidDraft;

    /** 最近一次操作的结果提示（空串 = 不画）。 */
    private String status = "";
    private int statusColor = BoundUi.TEXT_DIM;

    /** 本帧自绘控件的命中区；{@code render} 里重建，{@code mouseClicked} 里消费。 */
    private final List<Hit> hits = new ArrayList<>();

    /** 本帧悬停中的提示，最后统一画。 */
    private Component pendingTooltip;

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;

    /** 一个自绘控件的命中区。 */
    private record Hit(int x, int y, int width, int height, String action) {
    }

    public RecipeSenderConfigScreen(Screen parent) {
        super(Lang.message("text.recipe_sender.config_title", "配方材料发送器 · 配置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, width - 20);
        panelHeight = HEADER_HEIGHT + ROW_GAP * ROWS + STATUS_HEIGHT + NOTE_HEIGHT + FOOTER_HEIGHT;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;

        distanceBox = numberBox(0, RecipeSenderConfig.recipeManagementDistance(),
                RecipeSenderConfig::recipeManagementDistance,
                Lang.message("text.recipe_sender.config_distance_narration", "配方管理距离"));
        batchesBox = numberBox(1, RecipeSenderConfig.maxBatches(),
                RecipeSenderConfig::maxBatches,
                Lang.message("text.recipe_sender.config_batches_narration", "单次份数上限"));
        chunkBox = numberBox(2, RecipeSenderConfig.chunkBatches(),
                RecipeSenderConfig::chunkBatches,
                Lang.message("text.recipe_sender.config_chunk_narration", "单块份数"));
        addRenderableWidget(distanceBox);
        addRenderableWidget(batchesBox);
        addRenderableWidget(chunkBox);
        fluidDraft = RecipeSenderConfig.fluidSupport();
    }

    /**
     * 造一个数字输入框。
     *
     * <p>过滤器只放行数字与一个负号（距离允许 {@code -1}），所以「abc」这类输入根本敲不进去；
     * 但空串和单独一个「-」是中间状态，解析不出来。那种状态在失焦时回落到当前生效值，
     * 免得玩家看到一格空白却不知道保存下去会是什么。
     */
    private BoundEditBox numberBox(int row, int initial, IntSupplier current, Component narration) {
        BoundEditBox box = new BoundEditBox(font, fieldX(), rowY(row) - 4, FIELD_WIDTH, 16, narration);
        box.setMaxLength(6);
        box.setFilter(text -> text.isEmpty() || text.equals("-")
                || text.chars().allMatch(character -> character >= '0' && character <= '9'));
        box.setValue(String.valueOf(initial));
        box.onBlur(() -> {
            if (parse(box.getValue()) == null) {
                box.setValue(String.valueOf(current.getAsInt()));
            }
        });
        return box;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        BoundUi.panel(graphics, left, top, panelWidth, panelHeight);
        pendingTooltip = null;
        hits.clear();

        BoundUi.centered(graphics, title, left + panelWidth / 2, top + PADDING, BoundUi.TEXT);

        row(graphics, 0, Lang.message("text.recipe_sender.config_distance_label", "配方管理距离（格）"),
                Lang.message("text.recipe_sender.config_distance_hint", "-1 = 不限"));
        row(graphics, 1, Lang.message("text.recipe_sender.config_batches_label", "单次份数上限"),
                Lang.message("text.recipe_sender.config_batches_hint", "1 ~ 16384"));
        row(graphics, 2, Lang.message("text.recipe_sender.config_chunk_label", "单块份数"),
                Lang.message("text.recipe_sender.config_chunk_hint", "1 ~ 256，越大越快、余料越多"));
        row(graphics, 3, Lang.message("text.recipe_sender.config_fluid_label", "流体支持"),
                Lang.message("text.recipe_sender.config_fluid_hint", "含流体的配方要整份齐备"));

        Component fluidLabel = fluidDraft
                ? Lang.message("text.recipe_sender.config_fluid_on", "已开启")
                : Lang.message("text.recipe_sender.config_fluid_off", "已关闭");
        drawButton(graphics, fieldX(), rowY(3) - 4, fluidLabel, "toggle_fluid", mouseX, mouseY,
                Lang.message("text.recipe_sender.config_fluid_tooltip",
                        "关闭时忽略配方里的流体，只搬运物品；开启后直达会把流体也算进一份"));

        int statusY = top + HEADER_HEIGHT + ROW_GAP * ROWS + 2;
        if (!status.isEmpty()) {
            graphics.drawString(font, status, left + PADDING, statusY, statusColor, false);
        }
        graphics.drawString(font,
                Lang.message("text.recipe_sender.config_note",
                        "单人存档即时生效；多人服务器以服务端配置为准").getString(),
                left + PADDING, statusY + STATUS_HEIGHT, BoundUi.TEXT_DIM, false);

        int footerY = top + panelHeight - FOOTER_HEIGHT + 7;
        Component resetLabel = Lang.message("text.recipe_sender.config_reset", "恢复默认");
        Component cancelLabel = Lang.message("text.recipe_sender.config_cancel", "取消");
        Component saveLabel = Lang.message("text.recipe_sender.config_save", "保存");
        int saveWidth = BoundUi.buttonWidth(saveLabel);
        int cancelWidth = BoundUi.buttonWidth(cancelLabel);
        int saveX = left + panelWidth - PADDING - saveWidth;
        int cancelX = saveX - BUTTON_GAP - cancelWidth;
        drawButton(graphics, left + PADDING, footerY, resetLabel, "reset", mouseX, mouseY,
                Lang.message("text.recipe_sender.config_reset_tooltip", "填入默认值，点「保存」后生效"));
        drawButton(graphics, cancelX, footerY, cancelLabel, "cancel", mouseX, mouseY,
                Lang.message("text.recipe_sender.config_cancel_tooltip", "丢弃未保存的改动并关闭"));
        drawButton(graphics, saveX, footerY, saveLabel, "save", mouseX, mouseY, null);

        // 输入框由 Screen 自己画：放在自绘内容之后，避免盖住面板边框。
        super.render(graphics, mouseX, mouseY, partialTick);

        if (pendingTooltip != null) {
            BoundUi.tooltip(graphics, mouseX, mouseY, pendingTooltip);
            pendingTooltip = null;
        }
    }

    /** 画一行「标签 + 灰色说明」。 */
    private void row(GuiGraphics graphics, int index, Component label, Component hint) {
        int y = rowY(index) + 4;
        graphics.drawString(font, label, left + PADDING, y, BoundUi.TEXT, false);
        graphics.drawString(font, hint, left + PADDING + font.width(label) + HINT_GAP, y,
                BoundUi.TEXT_DIM, false);
    }

    /** 画一个自绘按钮并登记命中区。 */
    private void drawButton(GuiGraphics graphics, int x, int y, Component label, String action,
                            int mouseX, int mouseY, Component tooltip) {
        int width = BoundUi.buttonWidth(label);
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + BoundUi.BUTTON_HEIGHT;
        BoundUi.button(graphics, x, y, label, hovered, true, false);
        hits.add(new Hit(x, y, width, BoundUi.BUTTON_HEIGHT, action));
        if (hovered && tooltip != null) {
            pendingTooltip = tooltip;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Hit hit : hits) {
                if (mouseX >= hit.x() && mouseX < hit.x() + hit.width()
                        && mouseY >= hit.y() && mouseY < hit.y() + hit.height()) {
                    act(hit.action());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 输入框聚焦时回车会被输入框自己吃掉（原版行为是失焦），这里只管「没聚焦任何输入框」的回车。
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void act(String action) {
        switch (action) {
            case "toggle_fluid" -> fluidDraft = !fluidDraft;
            case "reset" -> applyDefaults();
            case "save" -> save();
            case "cancel" -> onClose();
            default -> {
            }
        }
    }

    /** 把四个控件填回默认值；不写配置，等玩家点「保存」。 */
    private void applyDefaults() {
        distanceBox.setValue(String.valueOf(RecipeSenderConfig.DEFAULT_RECIPE_MANAGEMENT_DISTANCE));
        batchesBox.setValue(String.valueOf(RecipeSenderConfig.DEFAULT_MAX_BATCHES));
        chunkBox.setValue(String.valueOf(RecipeSenderConfig.DEFAULT_CHUNK_BATCHES));
        fluidDraft = RecipeSenderConfig.DEFAULT_FLUID_SUPPORT;
        setStatus(Lang.message("text.recipe_sender.config_reset_done", "已填入默认值，点「保存」后生效"),
                BoundUi.TEXT_DIM);
    }

    /**
     * 校验并落盘。
     *
     * <p>越界不夹取、直接拒绝：玩家敲了 5000 却被告知「已按 1024 保存」，下次打开界面看到的
     * 又不是自己敲的数，比当场报错更难理解。校验不过时界面保持原样，改完再点一次就行。
     */
    private void save() {
        Integer distance = parse(distanceBox.getValue());
        if (distance == null || distance < RecipeSenderConfig.UNLIMITED_DISTANCE
                || distance > RecipeSenderConfig.MAX_RECIPE_MANAGEMENT_DISTANCE) {
            setStatus(Lang.message("text.recipe_sender.config_distance_invalid",
                    "配方管理距离必须是 -1 ~ 1024 之间的整数"), BoundUi.TEXT_DANGER);
            return;
        }
        Integer batches = parse(batchesBox.getValue());
        if (batches == null || batches < RecipeSenderConfig.MIN_BATCHES
                || batches > RecipeSenderConfig.MAX_BATCHES_LIMIT) {
            setStatus(Lang.message("text.recipe_sender.config_batches_invalid",
                    "单次份数上限必须是 1 ~ 16384 之间的整数"), BoundUi.TEXT_DANGER);
            return;
        }
        Integer chunk = parse(chunkBox.getValue());
        if (chunk == null || chunk < RecipeSenderConfig.MIN_CHUNK_BATCHES
                || chunk > RecipeSenderConfig.MAX_CHUNK_BATCHES) {
            setStatus(Lang.message("text.recipe_sender.config_chunk_invalid",
                    "单块份数必须是 1 ~ 256 之间的整数"), BoundUi.TEXT_DANGER);
            return;
        }
        RecipeSenderConfig.setRecipeManagementDistance(distance);
        RecipeSenderConfig.setMaxBatches(batches);
        RecipeSenderConfig.setChunkBatches(chunk);
        RecipeSenderConfig.setFluidSupport(fluidDraft);
        setStatus(Lang.message("text.recipe_sender.config_saved",
                "已保存到 config/recipe_sender-common.toml"), BoundUi.TAG_MASTER);
    }

    private void setStatus(Component message, int color) {
        status = message.getString();
        statusColor = color;
    }

    /** 解析输入框里的整数；空串、单个负号、非数字都返回 {@code null}。 */
    private static Integer parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty() || trimmed.equals("-")) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private int rowY(int index) {
        return top + HEADER_HEIGHT + index * ROW_GAP;
    }

    /** 输入框与右侧按钮共用的列起点。 */
    private int fieldX() {
        return left + panelWidth - PADDING - FIELD_WIDTH;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
