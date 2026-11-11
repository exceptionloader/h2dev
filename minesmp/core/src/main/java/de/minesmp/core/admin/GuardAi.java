package de.minesmp.core.admin;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.Duration;

/**
 * Optionale KI-Gegenpruefung fuer den Waechter: schickt die anonymisierten Beweise (keine Namen, keine IPs) an Claude
 * und bekommt ein Urteil als festes JSON zurueck. Wird nur geladen, wenn guard.ai.enabled und ein API-Schluessel gesetzt sind.
 * Laeuft immer in einem Hintergrund-Thread (blockierender Aufruf).
 */
final class GuardAi {
    /** Antwort der KI (Felder werden als JSON-Schema an die API gegeben). */
    public static final class Verdict {
        @JsonPropertyDescription("true nur, wenn die Beweise einen Cheat so sicher belegen, dass ein permanenter Bann vertretbar ist")
        public boolean cheating;
        @JsonPropertyDescription("Sicherheit des Urteils in Prozent, 0 bis 100")
        public int confidence;
        @JsonPropertyDescription("Kurze Begruendung auf Deutsch, hoechstens zwei Saetze, fuer das Serverteam")
        public String reason;
    }

    /** Ergebnis: verdict == null bedeutet Fehler (error beschreibt ihn auf Deutsch). */
    record Result(Verdict verdict, String error) {}

    private static final String SYSTEM = """
            Du bist die zweite Pruefinstanz eines Anti-Cheat-Systems auf einem Minecraft-Survival-Server.
            Ein Statistik-System hat einen Spieler gemeldet. Du bekommst die gesammelten Messwerte als JSON und entscheidest,
            ob sie einen Cheat (X-Ray, Zugriffe durch Waende mit Freecam/Ghost-Hand, Hack-Client) so sicher belegen,
            dass ein permanenter Bann vertretbar ist. Ein falscher Bann trifft einen ehrlichen Spieler dauerhaft, deshalb
            reicht "auffaellig" nicht: stimme nur zu, wenn eine harmlose Erklaerung (Glueck, Hoehlen, Lag, Bedrock-Client,
            sehr kleine Stichprobe, ein einzelner Fehlalarm-Typ) unplausibel ist.

            So liest du die Daten:
            - xray: "hidden_veins" sind Erzadern, die vor dem Abbau nirgends an Luft grenzten; Hoehlenfunde zaehlen nicht mit.
              "veins_per_1000" ist die Fundrate pro 1000 abgebaute Bloecke, "legit_upper_bound_per_1000" die grosszuegige Obergrenze
              fuer ehrliches Minen (uebliche ehrliche Werte liegen deutlich darunter). "p_value" ist die Wahrscheinlichkeit, so viele
              Funde bei dieser Obergrenze durch Zufall zu erreichen. "median_gap" ist der mittlere Abstand in Bloecken zwischen zwei Funden.
            - wall_access: Behaelter, die geoeffnet wurden, obwohl zwischen Auge und Klickpunkt ein voller Block lag
              (im normalen Spiel unmoeglich). "distinct_containers" zaehlt verschiedene Behaelter.
            - grim: Alarme des Anti-Cheat-Plugins GrimAC nach Kategorie mit hoechstem Verstoss-Zaehler ("max_vl").
              Bewegungs-Alarme allein koennen Fehlalarme sein; mehrere unabhaengige Kategorien gleichzeitig sind stark.
            - local_confidence: Gesamtwertung des Statistik-Systems (0-100).
            Antworte nur mit dem verlangten JSON.""";

    private final AnthropicClient client;
    private final String model;

    GuardAi(String apiKey, String model, String baseUrl) {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(90)).maxRetries(2);
        if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl);
        this.client = b.build();
        this.model = model;
    }

    /** Server-seitige Ausweichmodelle bei einer abgelehnten Anfrage gibt es nur fuer diese Modellreihen. */
    private boolean supportsFallbacks() {
        return model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5") || model.startsWith("claude-sonnet-5-5");
    }

    Result review(String evidenceJson) {
        try {
            MessageCreateParams.Builder base = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8000L)
                    .system(SYSTEM);
            if (supportsFallbacks()) base.addBeta("server-side-fallback-2026-07-01").fallbacksDefault();
            StructuredMessageCreateParams<Verdict> params = base
                    .outputConfig(Verdict.class, BetaOutputConfig.Effort.MEDIUM, JsonSchemaLocalValidation.YES)
                    .addUserMessage(evidenceJson)
                    .build();
            StructuredMessage<Verdict> msg = client.beta().messages().create(params);
            BetaStopReason stop = msg.stopReason().orElse(null);
            if (BetaStopReason.REFUSAL.equals(stop)) return new Result(null, "die KI hat die Anfrage abgelehnt");
            if (BetaStopReason.MAX_TOKENS.equals(stop)) return new Result(null, "die Antwort der KI wurde abgeschnitten");
            Verdict v = msg.content().stream().flatMap(c -> c.text().stream()).map(t -> t.text()).findFirst().orElse(null);
            if (v == null) return new Result(null, "die KI hat kein Urteil geliefert");
            v.confidence = Math.max(0, Math.min(100, v.confidence));
            if (v.reason == null) v.reason = "";
            return new Result(v, null);
        } catch (UnauthorizedException e) {
            return new Result(null, "API-Schluessel ungueltig (guard.ai.api-key)");
        } catch (RateLimitException e) {
            return new Result(null, "Anfrage-Limit der KI erreicht");
        } catch (AnthropicServiceException e) {
            return new Result(null, "KI-Dienst meldet Fehler " + e.statusCode() + e.errorType().map(t -> " (" + t + ")").orElse(""));
        } catch (AnthropicIoException e) {
            return new Result(null, "KI-Dienst nicht erreichbar: " + e.getMessage());
        } catch (RuntimeException e) {
            return new Result(null, "unerwarteter Fehler: " + e);
        }
    }
}
