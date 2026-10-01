package com.lai.recipesender.service;

import com.lai.recipesender.model.BoundContainer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把扁平的绑定列表解析成「发送单元」。
 *
 * <p>一个发送单元 = 一个主容器 + 挂在它下面的全部并列成员。挂在同一个主容器下的从容器
 * 被整个组共享，是这一组的溢出落点。
 *
 * <p><b>动态解析、不缓存</b>：主容器下面后来多了并列成员，挂在它下面的从容器自动变成
 * 「整组共享」，不需要重新绑定（见 {@code temp\bound-send-plan\prototype.html} ⑥）。
 *
 * <p>三类角色都只有一级：并列成员不能再挂并列成员，从容器不能再挂从容器。父容器不是主容器、
 * 或者父容器已经不存在的绑定一律当作<b>孤立项</b>，不参与路由（管理界面会标成「未归组」）。
 */
public final class BoundGroupResolver {

    /**
     * 一个发送单元。
     *
     * @param master    主容器（组的代表，共用它的名称与图标）
     * @param parallels 并列成员，按绑定顺序
     * @param slaves    从容器，按绑定顺序
     */
    public record BoundGroup(BoundContainer master, List<BoundContainer> parallels, List<BoundContainer> slaves) {
        public BoundGroup {
            parallels = parallels == null ? List.of() : List.copyOf(parallels);
            slaves = slaves == null ? List.of() : List.copyOf(slaves);
        }

        /** 参与均分的容器：主容器在前，并列成员按绑定顺序跟在后面。 */
        public List<BoundContainer> members() {
            List<BoundContainer> members = new ArrayList<>(parallels.size() + 1);
            members.add(master);
            members.addAll(parallels);
            return List.copyOf(members);
        }

        /** 组内容器数量，含主容器自己、不含从容器。 */
        public int memberCount() {
            return parallels.size() + 1;
        }

        /** 只有主容器自己时是「独立发送单元」。 */
        public boolean isStandalone() {
            return parallels.isEmpty();
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
        Map<UUID, List<BoundContainer>> parallels = new LinkedHashMap<>();
        Map<UUID, List<BoundContainer>> slaves = new LinkedHashMap<>();
        for (BoundContainer binding : bindings) {
            if (binding == null || binding.isMaster()) {
                continue;
            }
            UUID parentId = binding.parentId();
            if (parentId == null || !masters.containsKey(parentId)) {
                continue;
            }
            Map<UUID, List<BoundContainer>> bucket =
                    binding.role() == BoundContainer.Role.SLAVE ? slaves : parallels;
            bucket.computeIfAbsent(parentId, key -> new ArrayList<>()).add(binding);
        }
        List<BoundGroup> groups = new ArrayList<>(masters.size());
        for (BoundContainer master : masters.values()) {
            groups.add(new BoundGroup(master,
                    parallels.getOrDefault(master.id(), List.of()),
                    slaves.getOrDefault(master.id(), List.of())));
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

    /** 挂在某个主容器下的从容器数量。 */
    public static int slaveCount(List<BoundContainer> bindings, UUID masterId) {
        BoundGroup group = groupOf(bindings, masterId);
        return group == null ? 0 : group.slaves().size();
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
