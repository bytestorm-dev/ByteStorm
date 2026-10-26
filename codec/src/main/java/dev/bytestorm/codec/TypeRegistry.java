package dev.bytestorm.codec;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import dev.bytestorm.proto.BridgeHello;
import dev.bytestorm.proto.BridgeHelloAck;
import dev.bytestorm.proto.CommandResult;
import dev.bytestorm.proto.ConnectPlayerRequest;
import dev.bytestorm.proto.ExecuteCommand;
import dev.bytestorm.proto.GroupHealthChanged;
import dev.bytestorm.proto.GroupUpdated;
import dev.bytestorm.proto.Heartbeat;
import dev.bytestorm.proto.LoadRule;
import dev.bytestorm.proto.LogLine;
import dev.bytestorm.proto.ModuleInfo;
import dev.bytestorm.proto.ModuleStateChanged;
import dev.bytestorm.proto.KickPlayerRequest;
import dev.bytestorm.proto.PlayerCountChanged;
import dev.bytestorm.proto.PlayerCountRule;
import dev.bytestorm.proto.PlayerInfo;
import dev.bytestorm.proto.PlayerJoined;
import dev.bytestorm.proto.PlayerLeft;
import dev.bytestorm.proto.PlayerSnapshot;
import dev.bytestorm.proto.ProtoVersion;
import dev.bytestorm.proto.ScheduleRule;
import dev.bytestorm.proto.ScalingRule;
import dev.bytestorm.proto.ServiceCrashed;
import dev.bytestorm.proto.ServiceGroup;
import dev.bytestorm.proto.ServiceInfo;
import dev.bytestorm.proto.ServiceStateChanged;
import dev.bytestorm.proto.StartServiceRequest;
import dev.bytestorm.proto.StartServiceResponse;
import dev.bytestorm.proto.StopServiceRequest;
import dev.bytestorm.proto.TemplateInfo;
import dev.bytestorm.proto.TemplateUpdated;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Abbildung {@code typeUrl → Parser} für den {@link ProtobufCodec} (§6.2).
 *
 * <p>Die Registrierung ist bewusst explizit: kein Reflection-Scan, damit der
 * Start schnell bleibt und fehlende Typen sofort auffallen.
 */
public final class TypeRegistry {

    private final Map<String, Parser<? extends Message>> parsers;
    private final Map<Class<? extends Message>, String> fullNames;

    private TypeRegistry(Map<String, Parser<? extends Message>> parsers,
                         Map<Class<? extends Message>, String> fullNames) {
        this.parsers = Collections.unmodifiableMap(parsers);
        this.fullNames = Collections.unmodifiableMap(fullNames);
    }

    /**
     * @return ein Builder ohne registrierte Typen
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Registry mit allen Nachrichten aus {@code bytestorm-proto}.
     *
     * @return vollständig registrierte Registry
     */
    public static TypeRegistry byteStormDefaults() {
        return builder()
                // service.proto
                .register(ServiceInfo.getDefaultInstance())
                .register(ServiceStateChanged.getDefaultInstance())
                .register(ServiceCrashed.getDefaultInstance())
                .register(StartServiceRequest.getDefaultInstance())
                .register(StartServiceResponse.getDefaultInstance())
                .register(StopServiceRequest.getDefaultInstance())
                .register(ExecuteCommand.getDefaultInstance())
                .register(CommandResult.getDefaultInstance())
                .register(LogLine.getDefaultInstance())
                // player.proto
                .register(PlayerInfo.getDefaultInstance())
                .register(PlayerJoined.getDefaultInstance())
                .register(PlayerLeft.getDefaultInstance())
                .register(PlayerCountChanged.getDefaultInstance())
                .register(ConnectPlayerRequest.getDefaultInstance())
                .register(KickPlayerRequest.getDefaultInstance())
                // group.proto
                .register(ServiceGroup.getDefaultInstance())
                .register(GroupUpdated.getDefaultInstance())
                .register(GroupHealthChanged.getDefaultInstance())
                // scaling.proto
                .register(ScalingRule.getDefaultInstance())
                .register(PlayerCountRule.getDefaultInstance())
                .register(ScheduleRule.getDefaultInstance())
                .register(LoadRule.getDefaultInstance())
                // template.proto
                .register(TemplateInfo.getDefaultInstance())
                .register(TemplateUpdated.getDefaultInstance())
                // bridge.proto
                .register(BridgeHello.getDefaultInstance())
                .register(BridgeHelloAck.getDefaultInstance())
                .register(Heartbeat.getDefaultInstance())
                .register(PlayerSnapshot.getDefaultInstance())
                // module.proto
                .register(ModuleInfo.getDefaultInstance())
                .register(ModuleStateChanged.getDefaultInstance())
                .build();
    }

    /**
     * @param typeUrl Type-URL aus einer {@code Any}-Nachricht
     * @return der registrierte Parser, falls vorhanden
     */
    public Optional<Parser<? extends Message>> parser(String typeUrl) {
        return Optional.ofNullable(parsers.get(typeUrl));
    }

    /**
     * Entpackt eine {@code Any}-Nachricht über die Registry.
     *
     * @param any verpackte Nachricht
     * @return die entpackte Nachricht
     * @throws UnknownMessageTypeException wenn die Type-URL nicht registriert ist
     * @throws InvalidProtocolBufferException wenn die Nutzlast nicht zum Parser passt
     */
    public Message parse(Any any) throws InvalidProtocolBufferException {
        Parser<? extends Message> parser = parsers.get(any.getTypeUrl());
        if (parser == null) {
            throw new UnknownMessageTypeException(any.getTypeUrl(), parsers.size());
        }
        return parser.parseFrom(any.getValue());
    }

    /**
     * @return alle registrierten Type-URLs
     */
    public Set<String> typeUrls() {
        return parsers.keySet();
    }

    /**
     * Vollqualifizierter Proto-Name einer registrierten Nachrichtenklasse,
     * z. B. {@code bytestorm.ServiceStateChanged}. Wird für Topic- und
     * Event-Schlüssel gebraucht.
     *
     * @param messageClass Nachrichtenklasse
     * @return der Proto-Name
     * @throws IllegalArgumentException wenn die Klasse nicht registriert ist
     */
    public String fullNameFor(Class<? extends Message> messageClass) {
        String fullName = fullNames.get(messageClass);
        if (fullName == null) {
            throw new IllegalArgumentException("Nachrichtentyp " + messageClass.getName()
                    + " ist nicht im TypeRegistry registriert");
        }
        return fullName;
    }

    /**
     * @return alle registrierten Nachrichtenklassen
     */
    public Set<Class<? extends Message>> messageClasses() {
        return fullNames.keySet();
    }

    /**
     * @return Anzahl der registrierten Typen
     */
    public int size() {
        return parsers.size();
    }

    /**
     * Builder für {@link TypeRegistry}.
     */
    public static final class Builder {

        private final Map<String, Parser<? extends Message>> parsers = new LinkedHashMap<>();
        private final Map<Class<? extends Message>, String> fullNames = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Registriert einen Nachrichtentyp anhand seines Prototyps
         * ({@code X.getDefaultInstance()}).
         *
         * @param prototype Default-Instanz der Nachricht
         * @return this
         * @throws IllegalArgumentException wenn dieselbe Type-URL mit einem anderen Parser registriert wird
         */
        public Builder register(Message prototype) {
            String fullName = prototype.getDescriptorForType().getFullName();
            String typeUrl = ProtoVersion.typeUrlFor(fullName);
            Parser<? extends Message> parser = prototype.getParserForType();
            Parser<? extends Message> existing = parsers.putIfAbsent(typeUrl, parser);
            if (existing != null && existing != parser) {
                throw new IllegalArgumentException("Type-URL doppelt registriert: " + typeUrl);
            }
            fullNames.put(prototype.getClass(), fullName);
            return this;
        }

        /**
         * @return die unveränderliche Registry
         */
        public TypeRegistry build() {
            return new TypeRegistry(new LinkedHashMap<>(parsers), new LinkedHashMap<>(fullNames));
        }
    }
}
