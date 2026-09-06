package ovh.aurumgg.ui;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

record HelloPayload(byte[] data) implements CustomPacketPayload {
    static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("aurum", "hello"));
    static final StreamCodec<RegistryFriendlyByteBuf, HelloPayload> CODEC = StreamCodec.ofMember(
            (payload, buffer) -> buffer.writeBytes(payload.data),
            buffer -> {
                int size = buffer.readableBytes();
                if (size > 512) throw new IllegalArgumentException("AurumUI hello is too large");
                byte[] data = new byte[size];
                buffer.readBytes(data);
                return new HelloPayload(data);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
