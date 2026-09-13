package com.lai.recipesender.service;

import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.NearbyRecipeAvailabilityPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 负责周围配方统计、缺口规划和 FindMeExtended 提取调度。 */
public final class NearbyRecipeService {
    private static final long QUERY_INTERVAL_TICKS = 10L;
    private static final long QUERY_CACHE_TTL_TICKS = 12000L;
    /** 取回时的最大尝试次数；容器可能在统计后发生变化，允许重试一次。 */
    private static final int MAX_PULL_ATTEMPTS = 2;
    private static final ConcurrentHashMap<UUID, QueryState> QUERY_STATES = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("recipe_sender");

    private NearbyRecipeService() {
    }

    /** 统计背包和周围容器能够支持的最大配方份数。 */
    public static synchronized void query(ServerPlayer player, NearbyRecipeQueryPacket packet) {
        if (player == null || packet == null || !validIngredients(packet.ingredients())) {
            return;
        }

        long currentTick = player.serverLevel().getGameTime();
        cleanupQueryStates(currentTick);
        QueryState previous = QUERY_STATES.get(player.getUUID());
        if (previous != null && sameIngredients(previous.ingredients(), packet.ingredients())
                && currentTick >= previous.tick()
                && currentTick - previous.tick() < QUERY_INTERVAL_TICKS) {
            sendAvailability(player, packet.requestId(), previous.availableBatches());
            return;
        }

        int available = 0;
        try {
            if (FindMeExtendedAdapter.isAvailable()) {
                available = countAvailable(packet.ingredients(), captureStocks(player));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("统计玩家 {} 周围的配方材料失败",
                    player.getGameProfile().getName(), exception);
        }

        QUERY_STATES.put(player.getUUID(),
                new QueryState(currentTick, List.copyOf(packet.ingredients()), available));
        sendAvailability(player, packet.requestId(), available);
    }

    /** 将统计结果返回给发起请求的玩家。 */
    private static void sendAvailability(ServerPlayer player, long requestId, int availableBatches) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new NearbyRecipeAvailabilityPacket(requestId, availableBatches));
    }

    /** 清理长时间未更新的玩家缓存，避免服务端长期运行时积累无效记录。 */
    private static void cleanupQueryStates(long currentTick) {
        QUERY_STATES.entrySet().removeIf(entry -> {
            QueryState state = entry.getValue();
            return currentTick < state.tick()
                    || currentTick - state.tick() > QUERY_CACHE_TTL_TICKS;
        });
    }

    /** 逐项比较配方快照，避免仅依赖哈希值造成缓存误判。 */
    private static boolean sameIngredients(List<RecipeIngredientSpec> first,
                                           List<RecipeIngredientSpec> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            RecipeIngredientSpec left = first.get(index);
            RecipeIngredientSpec right = second.get(index);
            if (left.amountPerBatch() != right.amountPerBatch()
                    || left.wildcard() != right.wildcard()
                    || left.options().size() != right.options().size()) {
                return false;
            }
            for (int option = 0; option < left.options().size(); option++) {
                if (!ItemStack.isSameItemSameTags(left.options().get(option),
                        right.options().get(option))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 校验请求后，从周围容器取回背包缺少的材料。 */
    public static synchronized void pull(ServerPlayer player, NearbyRecipePullPacket packet) {
        if (player == null || packet == null || packet.batches() < 1
                || packet.batches() > RecipeIngredientSpec.MAX_BATCHES
                || !validIngredients(packet.ingredients())
                || !FindMeExtendedAdapter.isAvailable()) {
            return;
        }

        try {
            PullPlan plan = createPlan(packet.ingredients(), captureStocks(player), packet.batches());
            if (!plan.success() || plan.nearbyRequirements().isEmpty()) {
                return;
            }
            List<ItemStack> missing = plan.nearbyRequirements();
            for (int attempt = 0; attempt < MAX_PULL_ATTEMPTS; attempt++) {
                int[] pulled = FindMeExtendedAdapter.pullAll(player, missing);
                List<ItemStack> shortfall = new ArrayList<>();
                for (int index = 0; index < missing.size(); index++) {
                    int remaining = missing.get(index).getCount() - pulled[index];
                    if (remaining > 0) {
                        shortfall.add(missing.get(index).copyWithCount(remaining));
                    }
                }
                if (shortfall.isEmpty()) {
                    return;
                }
                if (attempt == MAX_PULL_ATTEMPTS - 1) {
                    for (ItemStack item : shortfall) {
                        LOGGER.warn("为玩家 {} 取回 {} 时仍短缺 {}",
                                player.getGameProfile().getName(),
                                item.getHoverName().getString(), item.getCount());
                    }
                    return;
                }
                missing = shortfall;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("为玩家 {} 取回周围配方材料失败",
                    player.getGameProfile().getName(), exception);
        }
    }

    /** 使用二分查找快速计算最大可制作份数。 */
    private static int countAvailable(List<RecipeIngredientSpec> ingredients, StockSnapshot snapshot) {
        int lower = 0;
        int upper = RecipeIngredientSpec.MAX_BATCHES + 1;
        while (lower + 1 < upper) {
            int candidate = lower + (upper - lower) / 2;
            if (createPlan(ingredients, snapshot, candidate).success()) {
                lower = candidate;
            } else {
                upper = candidate;
            }
        }
        return lower;
    }

    /** 按配方顺序规划背包材料和周围容器材料的消耗。 */
    private static PullPlan createPlan(List<RecipeIngredientSpec> ingredients,
                                       StockSnapshot snapshot, int batches) {
        // 每轮规划只复制一份计数数组，避免为二分查找的每一步都复制整份材料列表。
        int[] available = snapshot.newAvailableCounts();
        List<Stock> stocks = snapshot.stocks();
        List<ItemStack> nearbyRequirements = new ArrayList<>();
        for (RecipeIngredientSpec ingredient : ingredients) {
            long requested = (long) ingredient.amountPerBatch() * batches;
            if (requested <= 0 || requested > Integer.MAX_VALUE) {
                return PullPlan.failure();
            }
            int remaining = (int) requested;
            for (int index = 0; index < stocks.size() && remaining > 0; index++) {
                int count = available[index];
                if (count == 0) {
                    continue;
                }
                Stock stock = stocks.get(index);
                if (!ingredient.matches(stock.stack())) {
                    continue;
                }
                int used = Math.min(remaining, count);
                available[index] = count - used;
                remaining -= used;
                if (stock.nearby()) {
                    appendRequirement(nearbyRequirements, stock.stack(), used);
                }
            }
            if (remaining != 0) {
                return PullPlan.failure();
            }
        }
        return PullPlan.success(List.copyOf(nearbyRequirements));
    }

    /** 创建包含玩家背包和周围容器的材料快照。 */
    private static StockSnapshot captureStocks(ServerPlayer player) {
        List<ItemStack> inventoryItems = player.getInventory().items;
        List<ItemStack> nearbyItems = FindMeExtendedAdapter.snapshotNearby(player);
        List<Stock> stocks = new ArrayList<>(inventoryItems.size() + nearbyItems.size());
        for (ItemStack stack : inventoryItems) {
            if (!stack.isEmpty()) {
                stocks.add(new Stock(stack.copy(), false));
            }
        }
        for (ItemStack stack : nearbyItems) {
            if (!stack.isEmpty()) {
                stocks.add(new Stock(stack.copy(), true));
            }
        }
        int[] counts = new int[stocks.size()];
        for (int index = 0; index < stocks.size(); index++) {
            counts[index] = stocks.get(index).stack().getCount();
        }
        return new StockSnapshot(List.copyOf(stocks), counts);
    }

    /** 合并相同物品，减少取回请求数量。 */
    private static void appendRequirement(List<ItemStack> requirements, ItemStack sample, int amount) {
        for (ItemStack existing : requirements) {
            if (ItemStack.isSameItemSameTags(existing, sample)) {
                existing.grow(amount);
                return;
            }
        }
        if (amount > 0) {
            requirements.add(sample.copyWithCount(amount));
        }
    }

    /** 校验配方输入数量和每项材料描述。 */
    private static boolean validIngredients(List<RecipeIngredientSpec> ingredients) {
        return ingredients != null && !ingredients.isEmpty()
                && ingredients.size() <= RecipeIngredientSpec.MAX_INGREDIENTS
                && ingredients.stream().allMatch(RecipeIngredientSpec::isValid);
    }

    private record QueryState(long tick, List<RecipeIngredientSpec> ingredients,
                              int availableBatches) {
    }

    /** 背包与周围容器的材料快照，计数单独保存以便每轮规划廉价地重置。 */
    private record StockSnapshot(List<Stock> stocks, int[] templateCounts) {
        private int[] newAvailableCounts() {
            return templateCounts.clone();
        }
    }

    private record Stock(ItemStack stack, boolean nearby) {
    }

    private record PullPlan(boolean success, List<ItemStack> nearbyRequirements) {
        private static PullPlan success(List<ItemStack> requirements) {
            return new PullPlan(true, requirements);
        }

        private static PullPlan failure() {
            return new PullPlan(false, List.of());
        }
    }
}
