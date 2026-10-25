# Installation

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

Eine OPAA-Instanz mit leerer Datenbank ist erst nutzbar, wenn sie eingerichtet ist: erstes Konto
der Systemverwaltung, Anmeldeverfahren, Chat-Modell. Bis dahin steht sie im **Installationsmodus**
und bietet nur die Installation an. Eingerichtet wird über die Installations-API, entweder Schritt
für Schritt oder in einem Zug mit einer **Antwortdatei**, etwa aus Helm oder einer CI. Den Betriebsweg
selbst beschreiben [Docker Compose](docker-compose.md) und [Kubernetes](kubernetes.md).

## Installationsmodus

Der Zustand „eingerichtet oder nicht" steht in der Datenbank und gilt deshalb für alle Instanzen
zugleich ([Mehrere Backend-Instanzen](mehrere-instanzen.md)).

| Was | Im Installationsmodus | Nach dem Abschluss |
|---|---|---|
| Installations-API | erreichbar, nur mit Installations-Token | `404`, auf jeder Instanz |
| Anmeldekonfiguration und Branding der Oberfläche | erreichbar; meldet „Installation ausstehend" | wie immer |
| Health, Readiness, Liveness | `UP`; Komponente `installation` mit `pending: true` | `pending: false` |
| Metrik `opaa_installation_pending` | `1` | `0` |
| jeder andere Aufruf | `503` mit dem Code `INSTALLATION_PENDING` | wie immer |

Die Readiness bleibt im Installationsmodus `UP`, damit Load Balancer und Ingress die Installation
überhaupt erreichen. Einen Rückweg in den Installationsmodus gibt es nur über einen Eingriff des
Betriebs in die Datenbank und einen Neustart.

**Übergang.** Ist im Modus `oidc` `OPAA_INITIAL_ADMIN_EMAIL` gesetzt, richtet sich eine leere
Instanz wie bisher aus den Startwerten ein ([Authentifizierung](authentifizierung.md#erststart-und-systemverwalter-konto));
ohne die Variable startet der Installationsmodus. Im Modus `dev` richtet sich jede Instanz selbst
ein. Eine Datenbank, auf der schon eine frühere Version lief, gilt als eingerichtet.

## Installations-Token

Solange es kein Konto gibt, schützt ein einmaliges Token die Installation; ohne es übernähme sie,
wer die Adresse zuerst aufruft.

- **Vom Betrieb gesetzt:** `OPAA_INSTALLATION_TOKEN`, etwa aus einem Secret, mindestens 32 Zeichen
  und kein Platzhalter (`openssl rand -base64 48`). Ein schwaches Token lässt den Start
  fehlschlagen.
- **Sonst erzeugt:** Der erste Start schreibt das Token **einmalig** als markierten Block ins Log,
  auch wenn mehrere Instanzen zugleich starten:

```text
=====================================================================
  OPAA INSTALLATION TOKEN - PRINTED ONCE
  Token:  <hier steht das erzeugte Token>
  ...
=====================================================================
```

Zu lesen mit `docker compose logs backend | grep -A3 "INSTALLATION TOKEN"` bzw. `kubectl logs`.
Gespeichert wird nur ein Prüfwert; mit dem Abschluss verfällt er. **Ein verlorenes Token** ersetzt
der Betrieb, indem er `OPAA_INSTALLATION_TOKEN` setzt und neu startet. Wer das Log lesen kann, kann
die Installation abschließen: Log-Zugriff ist bis dahin so schutzwürdig wie das Token selbst.

`OPAA_INSTALLATION_ALLOWED_CIDRS` beschränkt die Installations-API zusätzlich auf Netzbereiche (wie
`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`); hinter einem Reverse-Proxy zählt die Adresse aus
`X-Forwarded-For`, sofern der Proxy in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` steht. Je Netzadresse
und Instanz begrenzt OPAA Aufrufe und abgelehnte Tokens; jede Ablehnung steht im Log.

## Schritte

Jeder Aufruf trägt das Token als `Authorization: Bearer <Token>`. Jeder Schritt wird einzeln
gespeichert und geprüft; ein Abbruch setzt beim gespeicherten Stand fort. Neu gespeicherte Angaben
verwerfen die Prüfung des Schritts.

| Schritt | Angaben | Prüfung | Später änderbar |
|---|---|---|---|
| Erstes Konto (`FIRST_ADMIN`) | E-Mail-Adresse, Anzeigename, Passwort | zustellbar geformte Adresse, nicht `admin@opaa.local`; Passwortrichtlinie | Benutzerverwaltung |
| Anmeldeverfahren (`SIGN_IN`) | nur lokale Konten, oder zusätzlich ein OIDC-Anbieter: Issuer, Client-ID, JWK-Set-Adresse | Zielprüfung, Discovery und JWK-Set erreichbar | Anbieterverwaltung |
| Chat-Modell (`CHAT_MODEL`) | Adresse, Modellname, Zugangsschlüssel, Temperatur, Antwortlänge | Verbindungstest wie in der Modellverwaltung | Modelle |

```bash
TOKEN=...   # aus dem Log oder OPAA_INSTALLATION_TOKEN
curl -H "Authorization: Bearer $TOKEN" https://opaa.example.org/api/v1/installation
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"signIn":{"mode":"LOCAL_ONLY"}}' \
  https://opaa.example.org/api/v1/installation/steps/SIGN_IN
curl -X POST -H "Authorization: Bearer $TOKEN" \
  https://opaa.example.org/api/v1/installation/steps/SIGN_IN/check
curl -X POST -H "Authorization: Bearer $TOKEN" https://opaa.example.org/api/v1/installation/complete
```

Das Passwort speichert OPAA nur als Hash, den Zugangsschlüssel verschlüsselt mit
`OPAA_SETTINGS_ENCRYPTION_KEY`; zurückgegeben wird nur, **ob** sie gesetzt sind.

**Der Abschluss** gelingt, wenn jeder Schritt erfolgreich geprüft ist, und genau einmal, auch wenn
zwei Instanzen ihn zugleich versuchen. Er legt das erste Konto als
[Notanker-Konto](authentifizierung.md#erststart-und-systemverwalter-konto) an, mit dem gewählten
Passwort und ohne erzwungenen Wechsel, richtet das Anmeldeverfahren ein (ein OIDC-Anbieter wird
Standardanbieter), aktiviert das Chat-Modell und verwirft Token und Zwischenstand. Danach meldet
sich die Person mit dem ersten Konto an. Startwerte aus der Umgebung übernimmt eine so eingerichtete
Instanz nie mehr. Jeder Schritt, jede Prüfung und der Abschluss stehen im Revisionsprotokoll.

## Antwortdatei

Ohne Browser richtet die Antwortdatei die Instanz in einem Zug ein — dieselben Angaben, dieselben
Prüfungen, alles oder nichts.

```json
{
  "firstAdmin": {
    "email": "it-betrieb@stadt.example",
    "displayName": "Systemverwaltung",
    "passwordFile": "/run/secrets/opaa-admin-password"
  },
  "signIn": {
    "mode": "LOCAL_AND_OIDC",
    "oidc": {
      "issuerUri": "https://login.stadt.example/realms/opaa",
      "clientId": "opaa-frontend",
      "jwkSetUri": "https://login.stadt.example/realms/opaa/protocol/openid-connect/certs"
    }
  },
  "chatModel": {
    "baseUrl": "http://ollama:11434/v1",
    "model": "qwen2.5:7b",
    "apiKeyFile": null,
    "maxTokens": 8000
  }
}
```

- **Beim Start:** Zeigt `OPAA_INSTALLATION_ANSWER_FILE` auf die Datei und ist die Installation
  offen, wendet der Start sie an. Passwort und Zugangsschlüssel stehen nie im Klartext darin,
  sondern als Pfad einer Datei (`passwordFile`, `apiKeyFile`), etwa aus einem Secret; `password`
  oder `apiKey` in der Datei lehnt der Start ab. Scheitert eine Prüfung, bleibt der
  Installationsmodus, und das Log nennt Schritt und Grund. Nach dem Abschluss wird die Datei
  übergangen.
- **Über die API:** `POST /api/v1/installation/answer-file` nimmt dieselben Angaben mit Token an,
  Passwort und Schlüssel dann als `password` und `apiKey` im Rumpf, wie in den einzelnen Schritten.
  Dateipfade nimmt die API nicht an: Sie ließen den Inhaber des Tokens Dateien des Backends lesen.
- Angaben späterer Schritte (`embeddingModel`, `outboundProxy`, `mail`, `branding`) wertet diese
  Version nicht aus; das Embedding-Modell kommt weiter aus der [Konfiguration](konfiguration.md).

## Fehlerbilder

| Meldung | Bedeutung | Abhilfe |
|---|---|---|
| `503`, Code `INSTALLATION_PENDING` | Die Instanz ist nicht eingerichtet | Installation abschließen |
| `401` „Das Installations-Token fehlt oder ist ungültig." | Token fehlt oder falsch | Token aus dem Log oder `OPAA_INSTALLATION_TOKEN` nehmen |
| `403` „Die Installation ist aus diesem Netz nicht zugelassen." | Client-Adresse außerhalb von `OPAA_INSTALLATION_ALLOWED_CIDRS` | aus dem erlaubten Netz aufrufen, vertraute Proxys prüfen |
| `429` | zu viele Aufrufe oder abgelehnte Tokens von dieser Adresse | `Retry-After` abwarten |
| `409` beim Abschluss | ein Schritt ist nicht gespeichert oder nicht erfolgreich geprüft | offene Schritte prüfen; die Meldung nennt sie |
| `404` | die Installation ist abgeschlossen | anmelden mit dem ersten Konto |
| Log „The answer file … was not applied" | eine Angabe oder Prüfung der Antwortdatei scheiterte | Datei korrigieren, neu starten |

## Konfiguration

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `OPAA_INSTALLATION_TOKEN` | leer: erzeugt und einmal ins Log geschrieben | Installations-Token, mindestens 32 Zeichen |
| `OPAA_INSTALLATION_ALLOWED_CIDRS` | leer: keine Beschränkung | Netzbereiche, aus denen die Installations-API antwortet |
| `OPAA_INSTALLATION_ANSWER_FILE` | leer: keine | Pfad der Antwortdatei, die ein Start anwendet |

Fest im Code: je Netzadresse und Instanz 300 Aufrufe und 10 abgelehnte Tokens in 15 Minuten.

## Weiterführende Kapitel

- [Konfiguration](konfiguration.md), [Authentifizierung](authentifizierung.md), [Modelle](modelle.md)
