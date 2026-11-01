package dev.bytestorm.core.config;

import dev.bytestorm.proto.RuntimeType;

/**
 * Inhalt von {@code config/cloud.json} (§21). Alle Felder sind optional; fehlende
 * Werte werden durch Defaults ersetzt, damit eine minimale Datei gültig ist.
 *
 * @param mongo    MongoDB-Verbindung
 * @param redis    Redis-Verbindung
 * @param runtime  globale Runtime
 * @param docker   Docker-Verbindung
 * @param ports    Port-Pools
 * @param proxy    Forwarding-Secret für Proxy und Backends (§14)
 * @param modules  optionale Module
 * @param scaling  Scaling-Intervall
 * @param crash    Crash-Restart-Verhalten
 * @param security Sicherheitsentscheidungen, z. B. Start ohne Datenbank-Auth (§20)
 */
public record CloudConfig(
        MongoSettings mongo,
        RedisSettings redis,
        RuntimeType runtime,
        DockerSettings docker,
        PortSettings ports,
        ProxySettings proxy,
        ModuleSettings modules,
        ScalingSettings scaling,
        CrashSettings crash,
        SecuritySettings security) {

    /**
     * Ersetzt fehlende Werte durch Defaults.
     */
    public CloudConfig {
        mongo = mongo == null ? new MongoSettings(null, null) : mongo;
        redis = redis == null ? new RedisSettings(null, null) : redis;
        runtime = runtime == null ? RuntimeType.PROCESS : runtime;
        docker = docker == null ? new DockerSettings(null) : docker;
        ports = ports == null ? new PortSettings(0, 0) : ports;
        proxy = proxy == null ? ProxySettings.defaults() : proxy;
        modules = modules == null ? new ModuleSettings(false, false) : modules;
        scaling = scaling == null ? new ScalingSettings(0) : scaling;
        crash = crash == null ? new CrashSettings(0, null) : crash;
        security = security == null ? SecuritySettings.defaults() : security;
    }

    /**
     * @return eine Konfiguration, die ausschließlich aus Defaults besteht
     */
    public static CloudConfig defaults() {
        return new CloudConfig(null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * @param secret Forwarding-Secret für Proxy und Backends
     * @return Kopie dieser Konfiguration mit dem übergebenen Secret
     */
    public CloudConfig withForwardingSecret(String secret) {
        return new CloudConfig(mongo, redis, runtime, docker, ports, proxy.withSecret(secret),
                modules, scaling, crash, security);
    }
}
