package dev.bytestorm.launcher.download;

import java.util.Locale;
import java.util.Objects;

/**
 * Eine externe Abhängigkeit, die im {@code lib/}-Verzeichnis liegen muss.
 *
 * <p>Die erwartete SHA-256-Prüfsumme ist Pflicht: Ein Download ohne
 * Integritätsprüfung wird nicht akzeptiert (§5.1, §20).
 *
 * <p>Einige Artefakte liegen als klassifizierte Variante vor — etwa
 * {@code netty-transport-native-epoll} mit {@code linux-x86_64}. Der Classifier
 * gehört deshalb zum Dateinamen und zum Repository-Pfad.
 *
 * @param groupId    Maven-Gruppe, z. B. {@code eu.koboo}
 * @param artifactId Maven-Artefakt, z. B. {@code en2do}
 * @param version    Version, z. B. {@code 3.3.3}
 * @param classifier optionale Variante, z. B. {@code linux-x86_64}
 * @param sha256     erwartete Prüfsumme als 64 Hex-Zeichen
 */
public record Dependency(String groupId, String artifactId, String version, String classifier, String sha256) {

    /**
     * @param groupId    Maven-Gruppe
     * @param artifactId Maven-Artefakt
     * @param version    Version
     * @param classifier optionale Variante, {@code null}/leer für das Hauptartefakt
     * @param sha256     erwartete Prüfsumme, Groß-/Kleinschreibung egal
     */
    public Dependency {
        groupId = requireCoordinate(groupId, "groupId");
        artifactId = requireCoordinate(artifactId, "artifactId");
        version = requireCoordinate(version, "version");
        classifier = classifier == null || classifier.isBlank() ? null : classifier.trim();
        if (classifier != null && !classifier.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("classifier enthält unerlaubte Zeichen: " + classifier);
        }
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.trim().toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 muss aus 64 Hex-Zeichen bestehen: " + sha256);
        }
    }

    /**
     * @param groupId    Maven-Gruppe
     * @param artifactId Maven-Artefakt
     * @param version    Version
     * @param sha256     erwartete Prüfsumme
     */
    public Dependency(String groupId, String artifactId, String version, String sha256) {
        this(groupId, artifactId, version, null, sha256);
    }

    /**
     * @return Dateiname im {@code lib/}-Verzeichnis
     */
    public String fileName() {
        return artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
    }

    /**
     * @return Pfad unterhalb der Repository-Basis, z. B.
     *         {@code eu/koboo/en2do/3.3.3/en2do-3.3.3.jar}
     */
    public String mavenPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName();
    }

    /**
     * @return {@code groupId:artifactId:version} inklusive Classifier, falls vorhanden
     */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version
                + (classifier == null ? "" : ":" + classifier);
    }

    private static String requireCoordinate(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " darf nicht leer sein");
        }
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException(field + " enthält unerlaubte Zeichen: " + value);
        }
        return trimmed;
    }
}
