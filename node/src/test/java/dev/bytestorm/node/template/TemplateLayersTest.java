package dev.bytestorm.node.template;

import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateLayersTest {

    @Test
    void proxyGetsAllAndProxy() {
        ServiceGroup group = group(ServiceType.PROXY, false);

        assertEquals(List.of(TemplateLayers.ALL, TemplateLayers.PROXY),
                TemplateLayers.forGroup(group));
    }

    @Test
    void serverGetsAllAndServer() {
        ServiceGroup group = group(ServiceType.SERVER, false);

        assertEquals(List.of(TemplateLayers.ALL, TemplateLayers.SERVER),
                TemplateLayers.forGroup(group));
    }

    @Test
    void fallbackStacksOnServer() {
        ServiceGroup group = group(ServiceType.SERVER, true);

        assertEquals(List.of(TemplateLayers.ALL, TemplateLayers.SERVER, TemplateLayers.FALLBACK),
                TemplateLayers.forGroup(group));
    }

    @Test
    void groupTemplatesComeLastAndKeepOrder() {
        ServiceGroup group = group(ServiceType.SERVER, true).toBuilder()
                .addTemplates("Lobby")
                .addTemplates("Extras")
                .build();

        assertEquals(List.of(TemplateLayers.ALL, TemplateLayers.SERVER, TemplateLayers.FALLBACK,
                        "Lobby", "Extras"),
                TemplateLayers.forGroup(group));
    }

    @Test
    void duplicatesAreDropped() {
        ServiceGroup group = group(ServiceType.SERVER, false).toBuilder()
                .addTemplates("ALL")
                .addTemplates("SERVER")
                .build();

        assertEquals(List.of(TemplateLayers.ALL, TemplateLayers.SERVER),
                TemplateLayers.forGroup(group));
    }

    private static ServiceGroup group(ServiceType type, boolean fallback) {
        return ServiceGroup.newBuilder()
                .setName("Lobby")
                .setType(type)
                .setFallback(fallback)
                .build();
    }
}
