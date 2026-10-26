package dev.bytestorm.proto;

import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die Type-URL ist der Schlüssel der Codec-Registry (Konzept §6.2) und muss
 * exakt dem entsprechen, was {@link Any#pack} erzeugt.
 */
class ProtoVersionTest {

    @Test
    void typeUrlMatchesAnyPack() {
        ServiceInfo info = ServiceInfo.newBuilder().setId("id").setName("Lobby-1").build();

        assertEquals(Any.pack(info).getTypeUrl(), ProtoVersion.typeUrl(info));
        assertEquals(ProtoVersion.typeUrl(info), ProtoVersion.typeUrlFor(info.getDescriptorForType().getFullName()));
    }

    @Test
    void typeUrlUsesProtoPackage() {
        assertEquals("type.googleapis.com/bytestorm.ServiceInfo", ProtoVersion.typeUrlFor("bytestorm.ServiceInfo"));
    }

    @Test
    void majorVersionIsCompatibleWithItself() {
        assertTrue(ProtoVersion.isCompatible(ProtoVersion.MAJOR));
        assertFalse(ProtoVersion.isCompatible(ProtoVersion.MAJOR + 1));
        assertFalse(ProtoVersion.isCompatible(ProtoVersion.MAJOR - 1));
    }
}
