package dev.bytestorm.proto;

import com.google.protobuf.Any;
import com.google.protobuf.Message;

/**
 * Version des ByteStorm-Wire-Protokolls und Helfer für {@code Any}-Type-URLs.
 *
 * <p>Cloud und Bridge-Plugins müssen dieselbe Major-Version sprechen; der
 * Handshake lehnt abweichende Major-Versionen ab (Konzept §6.4). Innerhalb
 * einer Major-Version sind ausschließlich additive Änderungen erlaubt:
 * Feldnummern werden nie wiederverwendet, entfernte Felder werden
 * {@code reserved}.
 */
public final class ProtoVersion {

    /** Inkompatible Änderungen erhöhen die Major-Version. */
    public static final int MAJOR = 1;

    /** Additive, kompatible Änderungen erhöhen die Minor-Version. */
    public static final int MINOR = 0;

    /** Präfix der Type-URL, das {@link Any#pack} verwendet. */
    public static final String TYPE_URL_PREFIX = "type.googleapis.com/";

    private ProtoVersion() {
    }

    /**
     * Prüft, ob eine entfernte Major-Version mit dieser Cloud kompatibel ist.
     *
     * @param remoteMajor Major-Version aus dem {@code BridgeHello}
     * @return {@code true}, wenn beide Seiten dasselbe Wire-Format sprechen
     */
    public static boolean isCompatible(int remoteMajor) {
        return remoteMajor == MAJOR;
    }

    /**
     * Type-URL einer konkreten Nachricht, wie sie in {@link Any} landet.
     *
     * @param message Nachricht, die verpackt würde
     * @return Type-URL inklusive {@value #TYPE_URL_PREFIX}
     */
    public static String typeUrl(Message message) {
        return Any.pack(message).getTypeUrl();
    }

    /**
     * Type-URL für einen vollqualifizierten Proto-Namen, z. B. {@code bytestorm.ServiceInfo}.
     *
     * @param fullName vollqualifizierter Name aus dem Proto-Descriptor
     * @return Type-URL inklusive {@value #TYPE_URL_PREFIX}
     */
    public static String typeUrlFor(String fullName) {
        return TYPE_URL_PREFIX + fullName;
    }
}
