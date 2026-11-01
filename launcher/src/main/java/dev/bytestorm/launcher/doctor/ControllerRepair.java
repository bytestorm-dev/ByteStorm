package dev.bytestorm.launcher.doctor;

import dev.bytestorm.api.service.ServiceId;
import dev.bytestorm.controller.ServiceController;
import dev.bytestorm.node.lifecycle.ServiceLifecycleException;
import dev.bytestorm.proto.ServiceInfo;

import java.time.Duration;
import java.util.Objects;

/**
 * Repariert die Forwarding-Dateien über den Controller ({@code doctor --fix}, §14).
 *
 * <p>Die Diagnose nennt einen Service-Namen, die Controller-Fassade arbeitet mit
 * IDs — diese Klasse ist die Brücke. Ein laufender Service muss dafür neu
 * starten: Er liest seine Konfiguration nur beim Start, und ein Neustart allein
 * genügt nicht, weil die Templates eines vorbereiteten statischen Ordners sonst
 * nicht erneut angewendet würden. Deshalb läuft die Reihenfolge Stop →
 * Templates → Start.
 */
public final class ControllerRepair implements Doctor.Repair {

    /** Graceful-Timeout beim Stop, damit Backends ihre Welt speichern können. */
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final ServiceController controller;

    /**
     * @param controller Controller der Cloud
     */
    public ControllerRepair(ServiceController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public void reapply(String service) {
        ServiceId id = id(service);
        try {
            controller.reapply(id);
        } catch (ServiceLifecycleException e) {
            throw new IllegalStateException("Templates von '" + service
                    + "' nicht angewendet: " + e.getMessage(), e);
        }
    }

    @Override
    public void restart(String service) {
        ServiceId id = id(service);
        try {
            controller.stop(id, STOP_TIMEOUT, false);
            // Nach dem Stop: Der Service merkt sich seinen Port, %PORT% bleibt also erhalten.
            controller.reapply(id);
            controller.start(id);
        } catch (ServiceLifecycleException e) {
            throw new IllegalStateException("'" + service
                    + "' nicht neu gestartet: " + e.getMessage(), e);
        }
    }

    private ServiceId id(String service) {
        ServiceInfo info = controller.byName(service)
                .orElseThrow(() -> new IllegalStateException("Unbekannter Service: " + service));
        return ServiceId.parse(info.getId());
    }
}
