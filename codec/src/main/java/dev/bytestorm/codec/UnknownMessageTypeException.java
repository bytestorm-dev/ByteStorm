package dev.bytestorm.codec;

/**
 * Eine {@code Any}-Nachricht trug eine Type-URL, die im {@link TypeRegistry}
 * nicht registriert ist — meist ein Zeichen für unterschiedliche
 * Proto-Versionen zwischen Cloud und Bridge (§6.4).
 */
public class UnknownMessageTypeException extends RuntimeException {

    private final String typeUrl;

    /**
     * @param typeUrl unbekannte Type-URL
     * @param known   Anzahl der registrierten Typen (für die Fehlermeldung)
     */
    public UnknownMessageTypeException(String typeUrl, int known) {
        super("Unbekannter Nachrichtentyp '" + typeUrl + "' (" + known
                + " Typen registriert) — stimmen die Proto-Versionen überein?");
        this.typeUrl = typeUrl;
    }

    /**
     * @return die unbekannte Type-URL
     */
    public String typeUrl() {
        return typeUrl;
    }
}
