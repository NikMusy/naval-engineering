package dev.navalengineering.network;

import dev.navalengineering.NavalEngineering;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record HelmInput(byte throttle, byte steer) implements CustomPayload {
    public static final Id<HelmInput> ID = new Id<>(NavalEngineering.id("helm_input"));
    public static final PacketCodec<RegistryByteBuf, HelmInput> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeByte(value.throttle).writeByte(value.steer),
            buf -> new HelmInput(buf.readByte(), buf.readByte()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

