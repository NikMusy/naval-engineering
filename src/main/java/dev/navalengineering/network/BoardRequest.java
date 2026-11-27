package dev.navalengineering.network;

import dev.navalengineering.NavalEngineering;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record BoardRequest(int shipId) implements CustomPayload {
    public static final Id<BoardRequest> ID = new Id<>(NavalEngineering.id("board"));
    public static final PacketCodec<RegistryByteBuf, BoardRequest> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeVarInt(value.shipId), buf -> new BoardRequest(buf.readVarInt()));

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

