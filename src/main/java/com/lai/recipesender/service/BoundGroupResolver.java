package com.lai.recipesender.service;

import com.lai.recipesender.model.BoundContainer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把扁平的绑定列表解析成「发送单元」。
 *
 * <p>一个发送单元 = 一个主容器 + 挂在它下面的全部并列成员。从容器可以挂在<b>主容器</b>名下，
 * 也可以挂在<b>某个并列成员</b>名下：它到底服务谁，由主容器的组模式决定
 * （见 {@link BoundGroup#effectiveSlaves()} 与 {@link BoundGroup#effectiveMemberSlaves}）。
 *
 * <p><b>动态解析、不缓存</b>：从容器的归属与是否参与投送只看组模式，不需要重新绑定——
 * 把主容器在独立/整体模式之间切换时，同一批从容器自动换一种用法。
 *
 * <p>层级最多两级：并列成员只能挂在主容器下，从容器只能挂在主容器或并列成员下。
 * 父容器不存在、或者父容器的角色不合法（例如从容器挂从容器）的绑定一律当作<b>孤立项</b>，
 * 不参与路由（管理界面会标成「未归组」）。
 */
public final class BoundGroupResolver {

    /**
     * 一个发送单元。
     *
     * @param master       主容器（组的代表，共用它的名称与图标）
     * @param parallels    并列成员，按绑定顺序
     * @param slaves       挂在<b>主容器</b>名下的从容器，按绑定顺序
     * @param memberSlaves 挂在<b>并列成员</b>名下的从容器：成员 id → 它名下的从容器，按绑定顺序；
     *                     没有成员私有从容器时是空表
     */
    public record BoundGroup(BoundContainer master, List<BoundContainer> parallels,
                             List<BoundContainer> slaves,
                             Map<UUID, List<BoundContainer>> memberSlaves) {
        public BoundGroup {
            parallels = parallels == null ? List.of() : List.copyOf(parallels);
            slaves = slaves == null ? List.of() : List.copyOf(slaves);
            // 用 LinkedHashMap 包一层而不是 Map.copyOf：后者不保证顺序，而溢出落点的顺序要稳定。
            memberSlaves = memberSlaves == null || memberSlaves.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(memberSlaves));
        }

        /** 参与均分的容器：主容器在前，并列成员按绑定顺序跟在后面。 */
        public List<BoundContainer> members() {
            List<BoundContainer> members = new ArrayList<>(parallels.size() + 1);
            members.add(master);
            members.addAll(parallels);
            return List.copyOf(members);
        }

        /** 这一组的组模式；只认主容器上的那一份（成员身上的值不参与）。 */
        public BoundContainer.GroupMode mode() {
            return master.groupMode();
        }

        /** 这一组是不是整体模式：电路必须一致、在跑的成员也接料、从容器被整组共享。 */
        public boolean unified() {
            return mode() == BoundContainer.GroupMode.UNIFIED;
        }

        /** 组内容器数量，含主容器自己、不含从容器。 */
        public int memberCount() {
            return parallels.size() + 1;
        }

        /** 只有主容器自己时是「独立发送单元」。 */
        public boolean isStandalone() {
            return parallels.isEmpty();
        }

        /**
         * 这一组名下的<b>全部</b>从容器，按成员顺序（主容器的在前，然后按并列成员的绑定顺序）。
         *
         * <p>整体模式把整组当成一台大机器，这一批箱子合起来是它的缓冲；独立模式里它们各自
         * 只服务自己挂的那一台，见 {@link #effectiveMemberSlaves}。
         */
        public List<BoundContainer> allSlaves() {
            if (memberSlaves.isEmpty()) {
                return slaves;
            }
            List<BoundContainer> all = new ArrayList<>(slaves);
            for (BoundContainer parallel : parallels) {
                all.addAll(memberSlaves.getOrDefault(parallel.id(), List.of()));
            }
            return List.copyOf(all);
        }

        /**
         * 本次真正参与投送的<b>组级</b>溢出落点。
         *
         * <p>只有<b>整体模式</b>（{@link BoundContainer.GroupMode#UNIFIED}）才有组级溢出：
         * 那时整组被当作一台大机器，组内机器电路一致，从容器（由玩家自己铺管路抽到组内）是这一组
         * 的缓冲，投给它的材料迟早会进机器，落点明确，所以全组的从容器一起当落点。
         *
         * <p>独立模式返回空：组内每台机器各自独立、电路可以互不相同，一个箱子只认自己挂的那一台机器，
         * 该由 {@link #effectiveMemberSlaves} 按成员取，而不是摊成一张全组共用的落点表——
         * 否则第一台机器满出来的材料会跑进第三台的箱子里。
         */
        public List<BoundContainer> effectiveSlaves() {
            return unified() ? allSlaves() : List.of();
        }

        /**
         * 某个成员在本次投送里自己的溢出落点。
         *
         * <p>独立模式：就是挂在这个成员名下的从容器——它是这一台机器的库存扩展，机器收不下的份额
         * 进自己的箱子，玩家自己铺路径补回机器。挂主容器名下的从容器归主容器（它也是一个成员），
         * 挂并列成员名下的归那一个成员。
         *
         * <p>整体模式返回空：全组的从容器已经由 {@link #effectiveSlaves()} 一并给出，再按成员算一遍
         * 会让同一只箱子被当成两个落点、溢出份数被重复计数。
         */
        public List<BoundContainer> effectiveMemberSlaves(BoundContainer member) {
            if (member == null || unified()) {
                return List.of();
            }
            if (member.id().equals(master.id())) {
                return slaves;
            }
            return memberSlaves.getOrDefault(member.id(), List.of());
        }
    }

    private BoundGroupResolver() {
    }

    /** 按绑定顺序解析出全部发送单元；孤立项被跳过。 */
    public static List<BoundGroup> resolve(List<BoundContainer> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<UUID, BoundContainer> masters = new LinkedHashMap<>();
        for (BoundContainer binding : bindings) {
            if (binding != null && binding.isMaster()) {
                masters.put(binding.id(), binding);
            }
        }
        // 先认并列成员，再认从容器：从容器的父可能是一个并列成员，而那个成员自己得先站住脚
        // （父是真实存在的主容器）才轮得到它收从容器。两遍扫描的顺序就是这个依赖关系。
        Map<UUID, List<BoundContainer>> parallels = new LinkedHashMap<>();
        // 并列成员 id → 它所属的主容器 id。用来把「挂在成员下的从容器」归到正确的组里。
        Map<UUID, UUID> owners = new LinkedHashMap<>();
        for (BoundContainer binding : bindings) {
            if (binding == null || binding.isMaster()
                    || binding.role() != BoundContainer.Role.PARALLEL) {
                continue;
            }
            UUID parentId = binding.parentId();
            if (parentId == null || !masters.containsKey(parentId)) {
                continue;
            }
            parallels.computeIfAbsent(parentId, key -> new ArrayList<>()).add(binding);
            owners.put(binding.id(), parentId);
        }
        Map<UUID, List<BoundContainer>> slaves = new LinkedHashMap<>();
        Map<UUID, List<BoundContainer>> memberSlaves = new LinkedHashMap<>();
        for (BoundContainer binding : bindings) {
            if (binding == null || binding.isMaster()
                    || binding.role() != BoundContainer.Role.SLAVE) {
                continue;
            }
            UUID parentId = binding.parentId();
            if (parentId == null) {
                continue;
            }
            if (masters.containsKey(parentId)) {
                slaves.computeIfAbsent(parentId, key -> new ArrayList<>()).add(binding);
                continue;
            }
            // 父是并列成员：归到该成员名下（不是它所属的主容器名下——独立模式里这一只箱子
            // 只服务那一台机器）。父既不是主容器也不是并列成员时，这条绑定是孤立项。
            if (owners.containsKey(parentId)) {
                memberSlaves.computeIfAbsent(parentId, key -> new ArrayList<>()).add(binding);
            }
        }
        List<BoundGroup> groups = new ArrayList<>(masters.size());
        for (BoundContainer master : masters.values()) {
            List<BoundContainer> members = parallels.getOrDefault(master.id(), List.of());
            Map<UUID, List<BoundContainer>> own = new LinkedHashMap<>();
            for (BoundContainer member : members) {
                List<BoundContainer> attached = memberSlaves.get(member.id());
                if (attached != null) {
                    own.put(member.id(), attached);
                }
            }
            groups.add(new BoundGroup(master, members,
                    slaves.getOrDefault(master.id(), List.of()), own));
        }
        return List.copyOf(groups);
    }

    /** 取某个主容器所在的发送单元；不是主容器、或该主容器不存在时返回 {@code null}。 */
    public static BoundGroup groupOf(List<BoundContainer> bindings, UUID masterId) {
        if (masterId == null) {
            return null;
        }
        for (BoundGroup group : resolve(bindings)) {
            if (group.master().id().equals(masterId)) {
                return group;
            }
        }
        return null;
    }

    /** 挂在某个主容器下的成员数量（含主容器自己），用于界面上的 {@code ×N}。 */
    public static int memberCount(List<BoundContainer> bindings, UUID masterId) {
        BoundGroup group = groupOf(bindings, masterId);
        return group == null ? 0 : group.memberCount();
    }

    /** 挂在某个主容器所在组名下的从容器数量（含挂在并列成员名下的）。 */
    public static int slaveCount(List<BoundContainer> bindings, UUID masterId) {
        BoundGroup group = groupOf(bindings, masterId);
        return group == null ? 0 : group.allSlaves().size();
    }

    /** 显示名：有并列成员时加 {@code ×N} 后缀（N 含主容器自己、不含从容器）。 */
    public static String displayName(List<BoundContainer> bindings, BoundContainer master) {
        if (master == null) {
            return "";
        }
        int count = memberCount(bindings, master.id());
        return count > 1 ? master.name() + " ×" + count : master.name();
    }
}
