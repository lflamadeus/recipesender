package com.lai.recipesender.service;

import com.lai.recipesender.integration.findme.FindMeExtendedAdapter;
import com.lai.recipesender.model.RecipeIngredientSpec;
import com.lai.recipesender.network.ModNetwork;
import com.lai.recipesender.network.packet.NearbyRecipeAvailabilityPacket;
import com.lai.recipesender.network.packet.NearbyRecipePullPacket;
import com.lai.recipesender.network.packet.NearbyRecipeQueryPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** 负责周围配方统计、缺口规划和 FindMeExtended 提取调度。 */
public final class NearbyRecipeService {
    private static final long QUERY_INTERVAL_TICKS = 10L;
    private static final long QUERY_CACHE_TTL_TICKS = 12000L;
    /**
     * 取回时最多换用几个候选物品。
     * <p>
     * 通配符输入（格雷的电路标签就是）在一份配方里允许一组物品中的任意一个，所以某个候选
     * 取不回来时可以顺位换下一个。标签一般只有三到六个成员，四次足够覆盖；每换一次都要重扫
     * 一遍整个搜索范围，不能再大。候选只有一个时，这个上限自然退化成原来的「重试一次」。
     */
    private static final int MAX_CANDIDATE_ATTEMPTS = 4;
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
                available = countAvailable(packet.ingredients(),
                        captureStocks(player, searchableContainers(player)));
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
            Predicate<BlockPos> searchable = searchableContainers(player);
            PullPlan plan = createPlan(packet.ingredients(), captureStocks(player, searchable),
                    packet.batches());
            if (!plan.success() || plan.items().isEmpty()) {
                return;
            }
            List<ItemPlan> items = plan.items();
            // 每一轮换用下一顺位候选：通配符输入下它们都能满足配方，某个容器取不出来时
            // （例如材料在机器输入槽里、提取器抽不动）就换同一个标签下另一份存量大的。
            // 候选只有一个时，这里的第二轮退化成原来的「重试一次」，行为不变。
            for (int attempt = 0; attempt < MAX_CANDIDATE_ATTEMPTS; attempt++) {
                // 同一物品的多项需求合成一次请求：配方把同一种材料拆成两格是很常见的
                // （格雷的传感器配方就是两个 HV 电路格），合并后每轮只扫一遍搜索范围。
                List<ItemStack> requests = new ArrayList<>();
                List<List<ItemPlan>> owners = new ArrayList<>();
                for (ItemPlan item : items) {
                    if (item.done()) {
                        continue;
                    }
                    ItemStack candidate = item.candidates()
                            .get(Math.min(attempt, item.candidates().size() - 1));
                    int need = item.remaining();
                    int existing = indexOfSameItem(requests, candidate);
                    if (existing < 0) {
                        requests.add(candidate.copyWithCount(need));
                        owners.add(new ArrayList<>(List.of(item)));
                    } else {
                        requests.get(existing).grow(need);
                        owners.get(existing).add(item);
                    }
                }
                if (requests.isEmpty()) {
                    return;
                }
                int[] pulled = FindMeExtendedAdapter.pullAll(player, requests, searchable);
                for (int index = 0; index < pulled.length && index < owners.size(); index++) {
                    int remaining = pulled[index];
                    for (ItemPlan owner : owners.get(index)) {
                        int give = Math.min(owner.remaining(), remaining);
                        owner.accept(give);
                        remaining -= give;
                    }
                }
            }
            for (ItemPlan item : items) {
                if (item.done()) {
                    continue;
                }
                ItemStack sample = item.candidates().get(0);
                LOGGER.warn("为玩家 {} 取回 {} 时仍短缺 {}",
                        player.getGameProfile().getName(),
                        sample.getHoverName().getString(), item.remaining());
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("为玩家 {} 取回周围配方材料失败",
                    player.getGameProfile().getName(), exception);
        }
    }

    /** 找出请求列表里同一种物品的下标，没有则返回 -1。 */
    private static int indexOfSameItem(List<ItemStack> requests, ItemStack candidate) {
        for (int index = 0; index < requests.size(); index++) {
            if (ItemStack.isSameItemSameTags(requests.get(index), candidate)) {
                return index;
            }
        }
        return -1;
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
        List<ItemPlan> plans = new ArrayList<>();
        for (RecipeIngredientSpec ingredient : ingredients) {
            long requested = (long) ingredient.amountPerBatch() * batches;
            if (requested <= 0 || requested > Integer.MAX_VALUE) {
                return PullPlan.failure();
            }
            int remaining = (int) requested;
            List<Integer> matched = new ArrayList<>();
            for (int index = 0; index < stocks.size(); index++) {
                if (available[index] != 0 && ingredient.matches(stocks.get(index).stack())) {
                    matched.add(index);
                }
            }
            // 匹配的容器要按存量从多到少消耗。按快照顺序取第一个匹配是错的：那会先吃掉某个
            // 只剩一两个、而且恰好取不回来的容器（典型是材料卡在机器自己的输入槽里），
            // 却放着同一个标签下存量几百的那份不用——两者对配方一样合法，最后整项取回失败。
            matched.sort(Comparator.comparingInt((Integer index) -> available[index]).reversed());
            List<ItemStack> candidates = new ArrayList<>();
            for (Integer index : matched) {
                if (remaining <= 0) {
                    break;
                }
                Stock stock = stocks.get(index);
                int count = available[index];
                int used = Math.min(remaining, count);
                available[index] = count - used;
                remaining -= used;
                if (!stock.nearby()) {
                    continue;
                }
                // 同一种物品分散在多个容器里时只记一个候选，取回时按剩余需求逐个尝试。
                if (candidates.stream().noneMatch(candidate ->
                        ItemStack.isSameItemSameTags(candidate, stock.stack()))) {
                    candidates.add(stock.stack().copyWithCount(1));
                }
            }
            if (remaining != 0) {
                return PullPlan.failure();
            }
            if (!candidates.isEmpty()) {
                plans.add(new ItemPlan(candidates, (int) requested));
            }
        }
        return PullPlan.success(plans);
    }

    /**
     * 反转搜索的容器过滤器：FindMeExtended 黑名单里的坐标返回 {@code false}，既不参与统计也不参与提取。
     * <p>
     * 黑名单由 FindMeExtended 本体维护（手持黑名单工具增删，只认坐标），这里只是共用同一份数据，
     * 保证「查找 / 取出」和「反转取回」排除的是同一批容器。每次统计或取回只构造一次过滤器，
     * 扫描范围内的每个方块实体共用同一份判定；黑名单为空时用常量过滤器，连哈希查表都省掉。
     */
    private static Predicate<BlockPos> searchableContainers(ServerPlayer player) {
        Set<BlockPos> blacklisted = FindMeExtendedAdapter.blacklistedPositions(player.serverLevel());
        return blacklisted.isEmpty() ? container -> true : container -> !blacklisted.contains(container);
    }

    /** 创建包含玩家背包和周围容器的材料快照。 */
    private static StockSnapshot captureStocks(ServerPlayer player,
                                               Predicate<BlockPos> searchable) {
        List<ItemStack> inventoryItems = player.getInventory().items;
        List<ItemStack> nearbyItems = FindMeExtendedAdapter.snapshotNearby(player, searchable);
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

    private record PullPlan(boolean success, List<ItemPlan> items) {
        private static PullPlan success(List<ItemPlan> items) {
            return new PullPlan(true, List.copyOf(items));
        }

        private static PullPlan failure() {
            return new PullPlan(false, List.of());
        }
    }

    /**
     * 一项材料的取回计划。
     * <p>
     * {@code candidates} 是同一个配方输入下所有「确实在附近、且能满足配方」的物品样本，
     * 按存量从多到少排列；{@code amount} 是这一项总共要取回的数量。取回时从头顺位尝试，
     * 前面几个取不出来就换下一个——通配符配方本来就允许这样换。
     */
    private static final class ItemPlan {
        private final List<ItemStack> candidates;
        private final int amount;
        private int pulled;

        private ItemPlan(List<ItemStack> candidates, int amount) {
            this.candidates = List.copyOf(candidates);
            this.amount = amount;
        }

        private List<ItemStack> candidates() {
            return candidates;
        }

        /** 这一项还差多少没取回来。 */
        private int remaining() {
            return amount - pulled;
        }

        /** 记下本轮实际取回的数量。 */
        private void accept(int got) {
            pulled += Math.max(0, got);
        }

        private boolean done() {
            return pulled >= amount;
        }
    }
}
