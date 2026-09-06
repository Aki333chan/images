package ovh.aurumgg.ui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record AdminRequestPayload(byte[] data) implements CustomPacketPayload {
    static final Type<AdminRequestPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("aurum", "admin_request"));
    static final StreamCodec<RegistryFriendlyByteBuf, AdminRequestPayload> CODEC = StreamCodec.ofMember(
            (payload, buffer) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size > 30_000) throw new IllegalArgumentException("AurumUI admin request is too large");
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new AdminRequestPayload(data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
