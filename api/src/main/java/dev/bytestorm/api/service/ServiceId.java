package dev.bytestorm.api.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Typsichere Service-ID. Im Proto und in Redis wird sie als String geführt.
 *
 * @param value die zugrunde liegende UUID
 */
public record ServiceId(UUID value) {

    /**
     * @param value UUID des Services
     */
    public ServiceId {
        Objects.requireNonNull(value, "value");
    }

    /**
     * @return eine neue, zufällige Service-ID
     */
    public static ServiceId random() {
        return new ServiceId(UUID.randomUUID());
    }

    /**
     * @param raw UUID als String
     * @return die geparste ID
     * @throws IllegalArgumentException bei ungültigem Format
     */
    public static ServiceId parse(String raw) {
        return new ServiceId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
