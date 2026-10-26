package dev.bytestorm.codec;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoException;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import lombok.RequiredArgsConstructor;

/**
 * Kryo-Serializer, der Protobuf-Nachrichten als {@link Any} ablegt.
 *
 * <p>Wird nur innerhalb des Fallback-Codecs gebraucht: Redisson-RPC verpackt
 * Argumente in eigene Objekte, die Kryo serialisiert. Ohne diesen Serializer
 * würde Kryo versuchen, die internen Felder einer Proto-Nachricht zu
 * reflektieren — das schlägt fehl.
 */
@RequiredArgsConstructor
final class ProtobufMessageSerializer extends Serializer<Message> {

    private final TypeRegistry registry;

    @Override
    public void write(Kryo kryo, Output output, Message message) {
        Any any = message instanceof Any existing ? existing : Any.pack(message);
        byte[] bytes = any.toByteArray();
        output.writeVarInt(bytes.length, true);
        output.writeBytes(bytes);
    }

    @Override
    public Message read(Kryo kryo, Input input, Class<? extends Message> type) {
        int length = input.readVarInt(true);
        if (length < 0) {
            throw new KryoException("Ungültige Länge für " + type.getName() + ": " + length);
        }
        byte[] bytes = input.readBytes(length);
        try {
            return registry.parse(Any.parseFrom(bytes));
        } catch (Exception e) {
            throw new KryoException("Protobuf-Nachricht " + type.getName() + " nicht lesbar", e);
        }
    }
}
