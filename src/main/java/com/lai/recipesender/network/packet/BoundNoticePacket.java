package com.lai.recipesender.network.packet;

import com.lai.recipesender.client.ClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端发给玩家的提示文本。
 *
 * <p>只带语言键与参数、不带成品字符串：文案要跟着客户端的语言走，这样也能复用
 * 语言文件的回退机制。绑定校验失败、绑定成功、孤立项提醒都走这个包，客户端统一
 * 显示在屏幕下方，不再往聊天栏里塞。
 *
 * @param key  语言键
 * @param args 语言模板的参数，按顺序填进 {@code %s}
 */
public record BoundNoticePacket(String key, List<String> args) {
    private static final int MAX_KEY_LENGTH = 128;
    private static final int MAX_ARG_LENGTH = 256;
    /** 参数个数的上限：服务端自己发的最多两三个，这里只是防越界读。 */
    private static final int MAX_ARGS = 16;

    public BoundNoticePacket {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** 编码提示。 */
    public static void encode(BoundNoticePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.key() == null ? "" : packet.key(), MAX_KEY_LENGTH);
        buffer.writeVarInt(packet.args().size());
        for (String arg : packet.args()) {
            buffer.writeUtf(arg == null ? "" : arg, MAX_ARG_LENGTH);
        }
    }

    /** 解码提示。 */
    public static BoundNoticePacket decode(FriendlyByteBuf buffer) {
        String key = buffer.readUtf(MAX_KEY_LENGTH);
        int size = Math.min(buffer.readVarInt(), MAX_ARGS);
        List<String> args = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            args.add(buffer.readUtf(MAX_ARG_LENGTH));
        }
        return new BoundNoticePacket(key, args);
    }

    /** 交给客户端显示在屏幕下方。 */
    public static void handle(BoundNoticePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientPacketHandler.acceptNotice(packet.key(), packet.args()));
        context.setPacketHandled(true);
    }
}
