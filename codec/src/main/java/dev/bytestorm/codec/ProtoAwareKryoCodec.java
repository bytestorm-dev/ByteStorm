package dev.bytestorm.codec;

import com.esotericsoftware.kryo.Kryo;
import com.google.protobuf.Message;
import lombok.RequiredArgsConstructor;
import org.redisson.codec.Kryo5Codec;

/**
 * Fallback-Codec für Werte, die keine ByteStorm-Nachricht sind — vor allem
 * Redisson-RPC-Interna.
 *
 * <p>Kryo übernimmt die Hülle, alle enthaltenen {@link Message}-Instanzen werden
 * jedoch über {@link ProtobufMessageSerializer} als {@code Any} geschrieben.
 * Damit bleiben Argumente und Ergebnisse im Proto-Format, obwohl der
 * RPC-Umschlag von Redisson kommt.
 */
@RequiredArgsConstructor
final class ProtoAwareKryoCodec extends Kryo5Codec {

    private final TypeRegistry registry;

    @Override
    protected Kryo createKryo(ClassLoader classLoader, boolean useReferences) throws ClassNotFoundException {
        Kryo kryo = super.createKryo(classLoader, useReferences);
        ProtobufMessageSerializer serializer = new ProtobufMessageSerializer(registry);
        for (Class<? extends Message> messageClass : registry.messageClasses()) {
            kryo.register(messageClass, serializer);
        }
        kryo.addDefaultSerializer(Message.class, serializer);
        return kryo;
    }
}
