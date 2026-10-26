package dev.bytestorm.codec;

import com.google.protobuf.Any;
import dev.bytestorm.proto.LogLine;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufCodecTest {

    private final ProtobufCodec codec = new ProtobufCodec(TypeRegistry.byteStormDefaults());

    private Object roundtrip(Object value) throws IOException {
        ByteBuf encoded = codec.getValueEncoder().encode(value);
        return codec.getValueDecoder().decode(encoded, null);
    }

    private static ServiceInfo sampleService() {
        return ServiceInfo.newBuilder()
                .setId("6f1d1b4e-4f0e-4a9e-9f1a-2c3d4e5f6a7b")
                .setName("Lobby-1")
                .setGroup("Lobby")
                .setPort(30000)
                .setState(ServiceState.ONLINE)
                .build();
    }

    @Test
    void protoMessageRoundtrip() throws Exception {
        ServiceInfo original = sampleService();

        Object decoded = roundtrip(original);

        assertEquals(original, decoded);
    }

    @Test
    void anyPayloadIsNotDoubleWrapped() throws Exception {
        Any original = Any.pack(sampleService());

        Object decoded = roundtrip(original);

        assertEquals(sampleService(), decoded);
    }

    @Test
    void stringRoundtripUsedForMapKeys() throws Exception {
        assertEquals("Lobby-1", roundtrip("Lobby-1"));
    }

    @Test
    void primitiveRoundtrips() throws Exception {
        assertEquals(30000, roundtrip(30000));
        assertEquals(9_000_000_000L, roundtrip(9_000_000_000L));
        assertEquals(0.75d, roundtrip(0.75d));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) roundtrip(new byte[]{1, 2, 3}));
    }

    @Test
    void nullRoundtrip() throws Exception {
        assertNull(roundtrip(null));
    }

    @Test
    void unknownTypeUrlFailsWithCause() throws Exception {
        Any unknown = Any.newBuilder()
                .setTypeUrl("type.googleapis.com/bytestorm.DoesNotExist")
                .setValue(sampleService().toByteString())
                .build();
        ByteBuf encoded = codec.getValueEncoder().encode(unknown);

        IOException error = assertThrows(IOException.class,
                () -> codec.getValueDecoder().decode(encoded, null));

        assertTrue(error.getMessage().contains("bytestorm.DoesNotExist"), error.getMessage());
        assertInstanceOf(UnknownMessageTypeException.class, error.getCause());
    }

    @Test
    void oversizedPayloadIsRejectedOnEncode() {
        ProtobufCodec tiny = new ProtobufCodec(TypeRegistry.byteStormDefaults(), 16);
        LogLine line = LogLine.newBuilder().setService("Lobby-1").setLine("x".repeat(100)).build();

        IOException error = assertThrows(IOException.class, () -> tiny.getValueEncoder().encode(line));

        assertTrue(error.getMessage().contains("zu groß"), error.getMessage());
    }

    @Test
    void oversizedPayloadIsRejectedOnDecode() {
        ProtobufCodec tiny = new ProtobufCodec(TypeRegistry.byteStormDefaults(), 8);
        byte[] payload = "0123456789".getBytes(StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class,
                () -> tiny.getValueDecoder().decode(Unpooled.wrappedBuffer(new byte[]{0x02}, payload), null));

        assertTrue(error.getMessage().contains("zu groß"), error.getMessage());
    }

    @Test
    void unknownTagIsRejected() {
        IOException error = assertThrows(IOException.class,
                () -> codec.getValueDecoder().decode(Unpooled.wrappedBuffer(new byte[]{0x7F}), null));

        assertTrue(error.getMessage().contains("Unbekannter Codec-Tag"), error.getMessage());
    }

    @Test
    void wrongFixedLengthIsRejected() {
        IOException error = assertThrows(IOException.class,
                () -> codec.getValueDecoder().decode(
                        Unpooled.wrappedBuffer(new byte[]{0x03, 0x00, 0x01}), null));

        assertTrue(error.getMessage().contains("Erwartete 4 Bytes"), error.getMessage());
    }

    @Test
    void fallbackHandlesNonProtoValues() throws Exception {
        ArrayList<String> value = new ArrayList<>(List.of("a", "b"));

        Object decoded = roundtrip(value);

        assertEquals(value, decoded, "Redisson-RPC-Interna brauchen einen selbstbeschreibenden Fallback");
    }

    @Test
    void fallbackKeepsNestedProtoMessagesBinary() throws Exception {
        // Entspricht dem Redisson-RPC-Fall: Kryo-Hülle mit Proto-Argument darin.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("service", sampleService());
        envelope.put("count", 3);

        Object decoded = roundtrip(envelope);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) decoded;
        assertEquals(sampleService(), result.get("service"));
        assertEquals(3, result.get("count"));
    }

    @Test
    void fallbackCanBeDisabled() {
        ProtobufCodec strict = new ProtobufCodec(TypeRegistry.byteStormDefaults(), 1024, null);

        IOException error = assertThrows(IOException.class,
                () -> strict.getValueEncoder().encode(UUID.randomUUID()));

        assertTrue(error.getMessage().contains("nicht kodieren"), error.getMessage());
    }

    @Test
    void registryResolvesNamesAndClasses() {
        TypeRegistry registry = TypeRegistry.byteStormDefaults();

        assertEquals("bytestorm.ServiceInfo", registry.fullNameFor(ServiceInfo.class));
        assertTrue(registry.messageClasses().contains(ServiceInfo.class));
        assertThrows(IllegalArgumentException.class, () -> registry.fullNameFor(Any.class));
    }

    @Test
    void registryKnowsAllSchemas() {
        // Jede neue Proto-Nachricht muss in TypeRegistry.byteStormDefaults() auftauchen,
        // sonst scheitern Publish und Subscribe erst zur Laufzeit.
        assertEquals(30, TypeRegistry.byteStormDefaults().size());
        assertTrue(TypeRegistry.byteStormDefaults().typeUrls()
                .contains("type.googleapis.com/bytestorm.ServiceInfo"));
        assertTrue(TypeRegistry.byteStormDefaults().typeUrls()
                .contains("type.googleapis.com/bytestorm.ConnectPlayerRequest"));
        assertTrue(TypeRegistry.byteStormDefaults().typeUrls()
                .contains("type.googleapis.com/bytestorm.KickPlayerRequest"));
    }
}
