package dev.navalengineering.network;

import dev.navalengineering.NavalEngineering;
import dev.navalengineering.simulation.DeckMotion;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

public record CrewInput(int shipId, int sequence, byte strafe, byte forward, short yaw, byte flags)
        implements CustomPayload {
    public static final byte JUMP = 1;
    public static final byte SPRINT = 2;
    public static final byte HELM = 4;
    public static final byte SNEAK = 8;
    public static final Id<CrewInput> ID = new Id<>(NavalEngineering.id("crew_input"));
    public static final PacketCodec<RegistryByteBuf, CrewInput> CODEC = PacketCodec.of(
            (value, buf) -> buf.writeVarInt(value.shipId).writeVarInt(value.sequence)
                    .writeByte(value.strafe).writeByte(value.forward).writeShort(value.yaw).writeByte(value.flags),
            buf -> new CrewInput(buf.readVarInt(), buf.readVarInt(), buf.readByte(), buf.readByte(),
                    buf.readShort(), buf.readByte()));

    public boolean valid() {
        return sequence > 0 && Math.abs(strafe) <= 1 && Math.abs(forward) <= 1 && (flags & ~15) == 0;
    }

    public DeckMotion.Input walking() {
        return new DeckMotion.Input(strafe, forward, yaw * Math.PI / 32768,
                (flags & JUMP) != 0, (flags & SPRINT) != 0, (flags & SNEAK) != 0);
    }

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}

