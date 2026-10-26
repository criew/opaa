# Installation

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

Eine OPAA-Instanz mit leerer Datenbank ist erst nutzbar, wenn sie eingerichtet ist: erstes Konto
der Systemverwaltung, Anmeldeverfahren, Chat-Modell, Embedding-Modell. Bis dahin steht sie im
**Installationsmodus** und bietet nur die Installation an. Eingerichtet wird im Browser mit dem
[Einrichtungs-Assistenten](#einrichtungs-assistent-im-browser), über die Installations-API Schritt
für Schritt oder in einem Zug mit einer **Antwortdatei**, etwa aus Helm oder einer CI. Den Betriebsweg
selbst beschreiben [Docker Compose](docker-compose.md) und [Kubernetes](kubernetes.md).

## Installationsmodus

Der Zustand „eingerichtet oder nicht" steht in der Datenbank und gilt deshalb für alle Instanzen
zugleich ([Mehrere Backend-Instanzen](mehrere-instanzen.md)).

| Was | Im Installationsmodus | Nach dem Abschluss |
|---|---|---|
| Installations-API | erreichbar, nur mit Installations-Token | `404`, auf jeder Instanz |
| Anmeldekonfiguration und Branding der Oberfläche | erreichbar; meldet „Installation ausstehend" | wie immer |
| Health, Readiness, Liveness | `UP`; mit Anmeldung zeigt `/actuator/health` die Komponente `installation` mit `pending: true` | `pending: false` |
| Metrik `opaa_installation_pending` | `1` | `0` |
| jeder andere Aufruf | `503` mit dem Code `INSTALLATION_PENDING` | wie immer |

Die Readiness bleibt im Installationsmodus `UP`, damit Load Balancer und Ingress die Installation
überhaupt erreichen. Einen Rückweg in den Installationsmodus gibt es nur über einen Eingriff des
Betriebs in die Datenbank und einen Neustart.

Im Modus `dev` richtet sich jede Instanz selbst ein, mit einem Chat-Modell aus festen Vorgaben für
die Entwicklung. Eine Datenbank, auf der OPAA schon lief (vorhandene Konten), gilt als eingerichtet.

Die Installation entscheidet eine Instanz erst, wenn das Instanzregister sie zugelassen hat
([Mehrere Backend-Instanzen](mehrere-instanzen.md)); eine abgewiesene Instanz schließt nichts ab und
erzeugt kein Token.

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
**Das Token landet damit in jeder Log-Weiterleitung** (Log-Aggregator, SIEM, Ticket-Anhänge): Wer
das nicht will, setzt `OPAA_INSTALLATION_TOKEN` aus einem Secret; dann erscheint kein Token im Log,
sofern **jede** Instanz die Variable mit demselben Wert trägt.
Gespeichert wird nur ein Prüfwert; mit dem Abschluss verfällt er. Wird ein gesetztes Token
wieder entfernt, gilt es beim nächsten Start nicht mehr; OPAA erzeugt ein neues. **Ein verlorenes Token** ersetzt
der Betrieb, indem er `OPAA_INSTALLATION_TOKEN` setzt und neu startet. Wer das Log lesen kann, kann
die Installation abschließen: Log-Zugriff ist bis dahin so schutzwürdig wie das Token selbst.

`OPAA_INSTALLATION_ALLOWED_CIDRS` beschränkt die Installations-API zusätzlich auf Netzbereiche (wie
`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`); hinter einem Reverse-Proxy zählt die Adresse aus
`X-Forwarded-For`, sofern der Proxy in `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` steht. **Fehlt diese
Angabe hinter einem Reverse-Proxy,** sieht OPAA für jeden Aufruf die Adresse des Proxys: Die
Netzbeschränkung prüft dann den Proxy statt der Person, und alle teilen sich ein Budget, sodass
fremde Fehlversuche den Betrieb aussperren. Start (Netzbereiche gesetzt, keine vertrauten Proxys)
und der erste Aufruf mit `X-Forwarded-For` von einer nicht vertrauten Verbindung warnen davor im Log.
Je Netzadresse und Instanz begrenzt OPAA Aufrufe und abgelehnte Tokens; jede Ablehnung steht im
Log. Netz, Budget und Token prüft OPAA, bevor es eine Anfrage liest; größere Anfragen als 64 KiB
lehnt es ab.

## Schritte

Jeder Aufruf trägt das Token als `Authorization: Bearer <Token>`. Jeder Schritt wird einzeln
gespeichert und geprüft; ein Abbruch setzt beim gespeicherten Stand fort. Neu gespeicherte Angaben
verwerfen die Prüfung des Schritts.

| Schritt | Angaben | Prüfung | Später änderbar |
|---|---|---|---|
| Erstes Konto (`FIRST_ADMIN`) | E-Mail-Adresse, Anzeigename, Passwort | zustellbar geformte Adresse, nicht `admin@opaa.local`; Passwortrichtlinie | Benutzerverwaltung |
| Anmeldeverfahren (`SIGN_IN`) | nur lokale Konten, oder zusätzlich ein OIDC-Anbieter: Issuer, Client-ID, JWK-Set-Adresse | Zielprüfung, Discovery und JWK-Set erreichbar | Anbieterverwaltung |
| Chat-Modell (`CHAT_MODEL`) | Adresse, Modellname, Zugangsschlüssel, Temperatur, Antwortlänge | Verbindungstest wie in der Modellverwaltung | Modelle |
| Embedding-Modell (`EMBEDDING_MODEL`) | Adresse, Modellname, Zugangsschlüssel, Eingabegrenze | Probeeinbettung; ihr Ergebnis legt die Zahl der Dimensionen fest | Modelle, Reiter „Einbettung", mit Neueinbettung ([Modell wechseln](modelle.md#modell-wechseln)) |

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
`OPAA_SETTINGS_ENCRYPTION_KEY`; zurückgegeben wird nur, **ob** sie gesetzt sind. Ein gespeicherter
Schlüssel bleibt nur, solange die Basis-Adresse des Modells gleich bleibt (Schema, Host, Port,
Pfad); wer die Adresse ändert, gibt den Schlüssel neu ein oder entfernt ihn.

**Adressprüfung der Modelle.** Weil die Installation vor jedem Konto läuft, prüft OPAA die
Adressen von Chat- und Embedding-Modell beim Speichern und unmittelbar vor Verbindungstest und
Probeeinbettung: Adressen in internen, Loopback- oder Link-Local-Bereichen werden abgelehnt, ohne
dass eine Anfrage hinausgeht. Ein Modell im eigenen Netz, etwa Ollama im Compose-Stapel, gibt der
Betrieb mit Host und Port in `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` frei (`ollama:11434`); die
Liste der Quellen gibt kein Modell frei ([Adressprüfung der Modelle](modelle.md#adressprüfung-der-modelle)). Die Probeeinbettung folgt keiner Weiterleitung, wartet höchstens zehn Sekunden, wiederholt
sich nicht und liest nur eine begrenzte Antwort. Zwischen Prüfung und Anfrage löst OPAA den
Hostnamen erneut auf; wer den Namensdienst des Modells kontrolliert, könnte die Anfrage in diesem
Moment noch umlenken. Einen festen Endpunkt im eigenen Netz trifft das nicht.

**Der Abschluss** gelingt, wenn jeder Schritt erfolgreich geprüft ist, und genau einmal, auch wenn
zwei Instanzen ihn zugleich versuchen. Er legt das erste Konto als
[Notanker-Konto](authentifizierung.md#erststart-und-systemverwalter-konto) an, mit dem gewählten
Passwort: aus dem Assistenten ohne erzwungenen Wechsel, aus einer Antwortdatei mit Wechsel bei der
ersten Anmeldung, solange `requirePasswordChange` nicht `false` ist. Er richtet das
Anmeldeverfahren ein (ein OIDC-Anbieter wird Standardanbieter), aktiviert das Chat-Modell, legt das
Embedding-Modell fest samt der Vektorablage mit der Zahl der Dimensionen aus der Probeeinbettung und
verwirft Token und Zwischenstand. Vor dem Abschluss gibt es keine Vektorablage; ein späterer Wechsel
des Embedding-Modells läuft in der Modellverwaltung, mit Neueinbettung ([Modelle](modelle.md#modell-wechseln)). Danach
meldet sich die Person mit dem ersten Konto an. Jeder Schritt, jede Prüfung und der Abschluss stehen
im Revisionsprotokoll.

## Einrichtungs-Assistent im Browser

Solange die Installation aussteht, führt jede Adresse der Oberfläche zum Assistenten unter
`/installation`, etwa `https://opaa.stadt.example/installation`. Er führt durch dieselben Schritte
wie die API und braucht dasselbe [Installations-Token](#installations-token); ohne gültiges Token
zeigt er keinen Schritt und keinen gespeicherten Wert.

- **Schrittliste.** Links stehen die Schritte mit ihrem Zustand: „Geprüft“, „Prüfung fehlgeschlagen“
  (der Grund steht über dem Formular, auch nach erneutem Öffnen), „Gespeichert, Prüfung offen“ oder
  „Offen“. Pflichtschritte und optionale Schritte stehen in getrennten Gruppen; optionale lassen
  sich mit „Überspringen“ auslassen. Heute sind alle Schritte Pflicht.
- **Prüfen und weiter** speichert die Angaben eines Schritts und prüft sie sofort. Die Oberfläche
  prüft vorab nur die Form, etwa einer E-Mail-Adresse oder einer Adresse mit `http://` oder
  `https://`; alles Weitere prüft OPAA wie oben beschrieben.
- **Embedding-Modell.** Nach erfolgreicher Probeeinbettung zeigt der Schritt die ermittelte Zahl
  der Dimensionen; mit ihr legt der Abschluss die Vektorablage an. Wer Adresse oder Modell danach
  ändert, prüft erneut.
- **Geheimnisse** zeigt der Assistent nie wieder an, nur als „Gesetzt“ mit „Ersetzen“; einen
  gespeicherten API-Schlüssel entfernt „Gespeicherten Schlüssel entfernen“. Wer das erste Konto
  nachträglich ändert, gibt das Passwort erneut ein; unverändert wird es nur noch einmal geprüft.
- **Unterbrechen und fortsetzen.** Der Browser behält das Token nur, solange die Seite offen ist.
  Nach Neuladen oder an einem anderen Rechner gibt man es erneut ein und landet beim ersten
  Pflichtschritt, der noch nicht geprüft ist, sonst beim Abschluss.
- **Abschluss.** „Installation abschließen“ ist erst möglich, wenn alle Pflichtschritte geprüft
  sind; bis dahin nennt die Seite die offenen. Danach führt „Zur Anmeldung“ zur Anmeldeseite. Hat
  inzwischen jemand anders abgeschlossen, etwa über eine Antwortdatei, zeigt der Assistent
  „Bereits eingerichtet“.

## Antwortdatei

Ohne Browser richtet die Antwortdatei die Instanz in einem Zug ein — dieselben Angaben, dieselben
Prüfungen, alles oder nichts.

```json
{
  "firstAdmin": {
    "email": "it-betrieb@stadt.example",
    "displayName": "Systemverwaltung",
    "passwordFile": "/run/secrets/opaa-admin-password",
    "requirePasswordChange": true
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
  },
  "embeddingModel": {
    "baseUrl": "http://ollama:11434/v1",
    "model": "bge-m3",
    "apiKeyFile": null,
    "maxInputTokens": 8192
  }
}
```

- **Beim Start:** Zeigt `OPAA_INSTALLATION_ANSWER_FILE` auf die Datei und ist die Installation
  offen, wendet der Start sie an. Passwort und Zugangsschlüssel stehen nie im Klartext darin,
  sondern als Pfad einer Datei (`passwordFile`, `apiKeyFile`), etwa aus einem Secret; `password`
  oder `apiKey` in der Datei lehnt der Start ab. Scheitert eine Prüfung, bleibt der
  Installationsmodus, und das Log nennt Schritt und Grund. Nach dem Abschluss wird die Datei
  übergangen; Passwort- und Schlüsseldateien gehören dann gelöscht.
- **Passwortwechsel:** Das erste Konto aus der Datei muss sein Passwort bei der ersten Anmeldung
  wechseln. So gilt das Passwort, das in einer Datei oder einem Secret liegen bleibt, danach nicht
  mehr. `"requirePasswordChange": false` schaltet das ab, etwa für eine CI, die sich mit dem Passwort
  anmeldet; fehlt die Angabe, gilt `true`.
- **Compose und Helm-Chart** hängen die Datei ein: Compose das Verzeichnis `installation/`
  ([Installation im Compose-Stapel](docker-compose.md#installation-im-compose-stapel)), der Chart
  rendert sie aus `bootstrap` und hängt das Passwort aus dem Secret ein
  ([Kubernetes](kubernetes.md#7-installation-und-erste-anmeldung)).
- **Über die API:** `POST /api/v1/installation/answer-file` nimmt dieselben Angaben mit Token an,
  Passwort und Schlüssel dann als `password` und `apiKey` im Rumpf, wie in den einzelnen Schritten;
  das so gesetzte Passwort gilt wie im Assistenten ohne erzwungenen Wechsel.
  Dateipfade nimmt die API nicht an: Sie ließen den Inhaber des Tokens Dateien des Backends lesen.
- Angaben späterer Schritte (`outboundProxy`, `mail`, `branding`) wertet diese Version nicht aus.
- Ohne `maxInputTokens` gilt eine Eingabegrenze von 2.048 Tokens
  ([Eingabegrenze des Embedding-Modells](modelle.md#eingabegrenze-des-embedding-modells)).

## Fehlerbilder

| Meldung | Bedeutung | Abhilfe |
|---|---|---|
| `503`, Code `INSTALLATION_PENDING` | Die Instanz ist nicht eingerichtet | Installation abschließen |
| `401` „Das Installations-Token fehlt oder ist ungültig." | Token fehlt oder falsch | Token aus dem Log oder `OPAA_INSTALLATION_TOKEN` nehmen |
| `400` am Feld Basis-Adresse mit Hinweis auf `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` | die Modelladresse liegt in einem gesperrten Bereich | Host und Port in `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` freigeben |
| `400` am Feld Zugangsschlüssel „Die Basis-Adresse hat sich geändert …" | der gespeicherte Schlüssel folgt keiner geänderten Adresse | Schlüssel neu eingeben oder Feld leeren |
| `403` „Die Installation ist aus diesem Netz nicht zugelassen." | Client-Adresse außerhalb von `OPAA_INSTALLATION_ALLOWED_CIDRS` | aus dem erlaubten Netz aufrufen, vertraute Proxys prüfen |
| `400` „Die Anfrage ist zu groß." | Anfrage über 64 KiB | Antworten prüfen |
| `429` | zu viele Aufrufe oder abgelehnte Tokens von dieser Adresse | `Retry-After` abwarten; hinter einem Proxy `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` setzen |
| `409` beim Abschluss | ein Schritt ist nicht gespeichert oder nicht erfolgreich geprüft | offene Schritte prüfen; die Meldung nennt sie |
| Prüfung des Embedding-Modells „Die Probeeinbettung ist fehlgeschlagen …" | Endpunkt nicht erreichbar, Modell nicht geladen oder Schlüssel falsch | Endpunkt und Modell prüfen, Schritt erneut prüfen |
| Prüfung des Embedding-Modells „… der Vektorindex nimmt höchstens 2000 auf" | das Modell liefert mehr Dimensionen, als der Vektorindex aufnimmt | ein anderes Embedding-Modell wählen |
| `404` | die Installation ist abgeschlossen | anmelden mit dem ersten Konto |
| Log „The answer file … was not applied" | eine Angabe oder Prüfung der Antwortdatei scheiterte | Datei korrigieren, neu starten |

## Konfiguration

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `OPAA_INSTALLATION_TOKEN` | leer: erzeugt und einmal ins Log geschrieben | Installations-Token, mindestens 32 Zeichen |
| `OPAA_INSTALLATION_ALLOWED_CIDRS` | leer: keine Beschränkung | Netzbereiche, aus denen die Installations-API antwortet |
| `OPAA_INSTALLATION_ANSWER_FILE` | leer: keine | Pfad der Antwortdatei, die ein Start anwendet |

Die drei Variablen gelten je Instanz und wirken nur, solange die Installation offen ist; nach dem
Abschluss lassen sie sich entfernen, ohne dass ein Rolling Update daran scheitert. **Bei mehreren
Instanzen setzen alle oder keine `OPAA_INSTALLATION_TOKEN`, mit demselben Wert:** Startet eine Instanz
ohne die Variable, ersetzt sie das gesetzte Token durch ein erzeugtes und schreibt es ins Log (mit
einer Warnung); startet eine mit anderem Wert, gilt das zuletzt gestartete.

Fest im Code: je Netzadresse und Instanz 300 Aufrufe und 10 abgelehnte Tokens in 15 Minuten.

## Weiterführende Kapitel

- [Konfiguration](konfiguration.md), [Authentifizierung](authentifizierung.md), [Modelle](modelle.md)
