package ovh.aurumgg.ui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record AdminStatePayload(byte[] data) implements CustomPacketPayload {
    static final Type<AdminStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aurum", "admin_state"));
    static final StreamCodec<RegistryFriendlyByteBuf, AdminStatePayload> CODEC = StreamCodec.ofMember(
            (payload, buffer) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size > 30_000) throw new IllegalArgumentException("AurumUI admin state is too large");
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new AdminStatePayload(data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
