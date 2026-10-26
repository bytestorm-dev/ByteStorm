package dev.bytestorm.launcher.download;

import java.util.Locale;
import java.util.Objects;

/**
 * Eine externe Abhängigkeit, die im {@code lib/}-Verzeichnis liegen muss.
 *
 * <p>Die erwartete SHA-256-Prüfsumme ist Pflicht: Ein Download ohne
 * Integritätsprüfung wird nicht akzeptiert (§5.1, §20).
 *
 * @param groupId    Maven-Gruppe, z. B. {@code eu.koboo}
 * @param artifactId Maven-Artefakt, z. B. {@code en2do}
 * @param version    Version, z. B. {@code 3.3.3}
 * @param sha256     erwartete Prüfsumme als 64 Hex-Zeichen
 */
public record Dependency(String groupId, String artifactId, String version, String sha256) {

    /**
     * @param groupId    Maven-Gruppe
     * @param artifactId Maven-Artefakt
     * @param version    Version
     * @param sha256     erwartete Prüfsumme, Groß-/Kleinschreibung egal
     */
    public Dependency {
        groupId = requireCoordinate(groupId, "groupId");
        artifactId = requireCoordinate(artifactId, "artifactId");
        version = requireCoordinate(version, "version");
        Objects.requireNonNull(sha256, "sha256");
        sha256 = sha256.trim().toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 muss aus 64 Hex-Zeichen bestehen: " + sha256);
        }
    }

    /**
     * @return Dateiname im {@code lib/}-Verzeichnis
     */
    public String fileName() {
        return artifactId + "-" + version + ".jar";
    }

    /**
     * @return Pfad unterhalb der Repository-Basis, z. B.
     *         {@code eu/koboo/en2do/3.3.3/en2do-3.3.3.jar}
     */
    public String mavenPath() {
        return groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + fileName();
    }

    /**
     * @return {@code groupId:artifactId:version}
     */
    public String coordinates() {
        return groupId + ":" + artifactId + ":" + version;
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
