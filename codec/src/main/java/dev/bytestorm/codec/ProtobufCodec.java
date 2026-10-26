package dev.bytestorm.codec;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.redisson.client.codec.BaseCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Redisson-Codec für alle ByteStorm-Strukturen (§6.2).
 *
 * <p>Nachrichten werden als {@link Any} übertragen (Type-URL + Nutzlast), sodass
 * der Empfänger den Typ ohne Vorwissen bestimmen kann. Für Schlüssel und
 * einfache Werte (String, Integer, Long, Double) gibt es einen 1-Byte-Tag, damit
 * derselbe Codec auch für {@code RSet<Integer>} oder Map-Keys funktioniert.
 *
 * <p>Wire-Format: {@code [1 Byte Tag][Nutzlast]}.
 */
public final class ProtobufCodec extends BaseCodec {

    /** Obergrenze für eine einzelne Nutzlast, schützt vor manipulierten Größen. */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private static final byte TAG_NULL = 0x00;
    private static final byte TAG_PROTOBUF = 0x01;
    private static final byte TAG_UTF8 = 0x02;
    private static final byte TAG_INT = 0x03;
    private static final byte TAG_LONG = 0x04;
    private static final byte TAG_DOUBLE = 0x05;
    private static final byte TAG_BYTES = 0x06;
    private static final byte TAG_FALLBACK = 0x07;

    private final TypeRegistry registry;
    private final int maxPayloadBytes;
    private final Codec fallback;
    private final Encoder encoder;
    private final Decoder<Object> decoder;

    /**
     * @param registry Registry aller bekannten Nachrichtentypen
     */
    public ProtobufCodec(TypeRegistry registry) {
        this(registry, DEFAULT_MAX_PAYLOAD_BYTES, new ProtoAwareKryoCodec(registry));
    }

    /**
     * @param registry        Registry aller bekannten Nachrichtentypen
     * @param maxPayloadBytes Obergrenze einer Nutzlast in Bytes
     */
    public ProtobufCodec(TypeRegistry registry, int maxPayloadBytes) {
        this(registry, maxPayloadBytes, new ProtoAwareKryoCodec(registry));
    }

    /**
     * @param registry        Registry aller bekannten Nachrichtentypen
     * @param maxPayloadBytes Obergrenze einer Nutzlast in Bytes
     * @param fallback        Codec für Nicht-Proto-Werte (z. B. Redisson-RPC-Interna),
     *                        {@code null} deaktiviert den Fallback
     */
    public ProtobufCodec(TypeRegistry registry, int maxPayloadBytes, Codec fallback) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes muss positiv sein: " + maxPayloadBytes);
        }
        this.maxPayloadBytes = maxPayloadBytes;
        this.fallback = fallback;
        this.encoder = this::encode;
        this.decoder = (source, state) -> decode(source, state);
    }

    @Override
    public Encoder getValueEncoder() {
        return encoder;
    }

    @Override
    public Decoder<Object> getValueDecoder() {
        return decoder;
    }

    @Override
    public Encoder getMapKeyEncoder() {
        return encoder;
    }

    @Override
    public Decoder<Object> getMapKeyDecoder() {
        return decoder;
    }

    @Override
    public Encoder getMapValueEncoder() {
        return encoder;
    }

    @Override
    public Decoder<Object> getMapValueDecoder() {
        return decoder;
    }

    /**
     * Kodiert einen Wert. Nicht unterstützte Typen führen zu einer
     * {@link IOException}, damit der Fehler im Redisson-Stack sichtbar bleibt.
     *
     * @param value zu kodierender Wert
     * @return gepufferte Bytes inklusive Tag
     * @throws IOException bei zu großer Nutzlast oder unbekanntem Typ
     */
    private ByteBuf encode(Object value) throws IOException {
        if (value == null) {
            return Unpooled.wrappedBuffer(new byte[]{TAG_NULL});
        }
        if (value instanceof Any any) {
            return wrap(TAG_PROTOBUF, any.toByteArray());
        }
        if (value instanceof Message message) {
            return wrap(TAG_PROTOBUF, Any.pack(message).toByteArray());
        }
        if (value instanceof String text) {
            return wrap(TAG_UTF8, text.getBytes(StandardCharsets.UTF_8));
        }
        if (value instanceof Integer number) {
            return wrap(TAG_INT, ByteBuffer.allocate(Integer.BYTES).putInt(number).array());
        }
        if (value instanceof Long number) {
            return wrap(TAG_LONG, ByteBuffer.allocate(Long.BYTES).putLong(number).array());
        }
        if (value instanceof Double number) {
            return wrap(TAG_DOUBLE, ByteBuffer.allocate(Double.BYTES).putDouble(number).array());
        }
        if (value instanceof byte[] bytes) {
            return wrap(TAG_BYTES, bytes);
        }
        if (fallback != null) {
            // Redisson-RPC und fremde Werte sind nicht als Proto darstellbar.
            ByteBuf encoded = fallback.getValueEncoder().encode(value);
            try {
                byte[] data = new byte[encoded.readableBytes()];
                encoded.readBytes(data);
                return wrap(TAG_FALLBACK, data);
            } finally {
                encoded.release();
            }
        }
        throw new IOException("ProtobufCodec kann " + value.getClass().getName() + " nicht kodieren");
    }

    private ByteBuf wrap(byte tag, byte[] payload) throws IOException {
        if (payload.length > maxPayloadBytes) {
            throw new IOException("Nutzlast zu groß: " + payload.length + " Bytes (Limit " + maxPayloadBytes + ")");
        }
        return Unpooled.wrappedBuffer(new byte[]{tag}, payload);
    }

    /**
     * Dekodiert einen Wert anhand seines Tags.
     *
     * @param source Puffer mit Tag und Nutzlast
     * @param state  Redisson-Verbindungszustand (ungenutzt)
     * @return dekodierter Wert
     * @throws IOException bei unbekanntem Tag, zu großer Nutzlast oder fehlender Typ-Registrierung
     */
    private Object decode(ByteBuf source, State state) throws IOException {
        if (!source.isReadable()) {
            return null;
        }
        byte tag = source.readByte();
        byte[] data = new byte[source.readableBytes()];
        source.readBytes(data);

        if (data.length > maxPayloadBytes) {
            throw new IOException("Nutzlast zu groß: " + data.length + " Bytes (Limit " + maxPayloadBytes + ")");
        }

        try {
            return switch (tag) {
                case TAG_NULL -> null;
                case TAG_PROTOBUF -> registry.parse(Any.parseFrom(data));
                case TAG_UTF8 -> new String(data, StandardCharsets.UTF_8);
                case TAG_INT -> {
                    requireLength(data, Integer.BYTES);
                    yield ByteBuffer.wrap(data).getInt();
                }
                case TAG_LONG -> {
                    requireLength(data, Long.BYTES);
                    yield ByteBuffer.wrap(data).getLong();
                }
                case TAG_DOUBLE -> {
                    requireLength(data, Double.BYTES);
                    yield ByteBuffer.wrap(data).getDouble();
                }
                case TAG_BYTES -> data;
                case TAG_FALLBACK -> {
                    if (fallback == null) {
                        throw new IOException("Fallback-Codec ist nicht konfiguriert");
                    }
                    yield fallback.getValueDecoder().decode(Unpooled.wrappedBuffer(data), state);
                }
                default -> throw new IOException("Unbekannter Codec-Tag: 0x" + Integer.toHexString(tag & 0xFF));
            };
        } catch (InvalidProtocolBufferException e) {
            throw new IOException("Nutzlast ist kein gültiges Protobuf: " + e.getMessage(), e);
        } catch (UnknownMessageTypeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void requireLength(byte[] data, int expected) throws IOException {
        if (data.length != expected) {
            throw new IOException("Erwartete " + expected + " Bytes, bekam " + data.length);
        }
    }
}
