# Installation

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

Eine OPAA-Instanz mit leerer Datenbank ist erst nutzbar, wenn sie eingerichtet ist: erstes Konto
der Systemverwaltung und Anmeldeverfahren, dazu nach Wahl Chat-Modell, Embedding-Modell,
Rerank-Modell, das Transkriptionsmodell der [Spracheingabe](modelle.md#spracheingabe-einschalten), der
Mailserver für den [E-Mail-Versand](e-mail.md) und das [Branding](branding.md). Bis dahin steht sie im
**Installationsmodus** und
bietet nur die Installation an. Eingerichtet wird im Browser mit dem
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
verwerfen die Prüfung des Schritts. Pflicht sind die ersten zwei Schritte, erstes Konto und
Anmeldeverfahren; Chat-Modell, Embedding-Modell, Rerank-Modell, Transkriptionsmodell, E-Mail-Versand
und Branding sind optional. Das Transkriptionsmodell gibt es nur, solange `OPAA_FEATURES_SPEECH_INPUT_ENABLED`
nicht `false` ist: Sonst fehlt der Schritt in Assistent und API, und die Antwortdatei übergeht seinen
Teil. Den Wert der Variable tragen alle Instanzen gleich: Schließt eine Instanz mit
abgeschalteter Spracheingabe die Installation ab, verwirft sie einen gespeicherten Schritt
Transkriptionsmodell.

| Schritt | Pflicht | Angaben | Prüfung | Später änderbar |
|---|---|---|---|---|
| Erstes Konto (`FIRST_ADMIN`) | ja | E-Mail-Adresse, Anzeigename, Passwort | zustellbar geformte Adresse, nicht `admin@opaa.local`; Passwortrichtlinie | Benutzerverwaltung |
| Anmeldeverfahren (`SIGN_IN`) | ja | nur lokale Konten, oder zusätzlich ein OIDC-Anbieter: Issuer, Client-ID, JWK-Set-Adresse | Zielprüfung, Discovery und JWK-Set erreichbar | Anbieterverwaltung |
| Chat-Modell (`CHAT_MODEL`) | nein | Adresse, Modellname, Zugangsschlüssel, Temperatur, Antwortlänge | Verbindungstest wie in der Modellverwaltung | Modelle ([Chat-Modelle in der Oberfläche anlegen](modelle.md#chat-modelle-in-der-oberfläche-anlegen)) |
| Embedding-Modell (`EMBEDDING_MODEL`) | nein | Adresse, Modellname, Zugangsschlüssel, Eingabegrenze | Probeeinbettung; ihr Ergebnis legt die Zahl der Dimensionen fest | Modelle, Reiter „Einbettung": erstes Modell festlegen ([Erstes Modell festlegen](modelle.md#erstes-modell-festlegen)), später mit Neueinbettung wechseln ([Modell wechseln](modelle.md#modell-wechseln)) |
| Rerank-Modell (`RERANK_MODEL`) | nein | Adresse ohne den Pfad `/rerank`, Modellname, Zugangsschlüssel | Adressprüfung der Modelle; dieselbe Probe wie „Verbindung testen“ in der Modellverwaltung, eine Rerank-Anfrage mit einem Prüftext | Modelle, Reiter „Reranking“ ([Rerank-Modell und Transkriptionsmodell in der Oberfläche](modelle.md#rerank-modell-und-transkriptionsmodell-in-der-oberfläche)) |
| Transkriptionsmodell (`TRANSCRIPTION_MODEL`) | nein | Adresse, Modellname, Zugangsschlüssel, Sprache | Probe wie „Verbindung testen“ unter Modelle → Spracheingabe, ohne Aufnahme | Modelle, Reiter „Spracheingabe“ ([Spracheingabe einschalten](modelle.md#spracheingabe-einschalten)) |
| E-Mail-Versand (`MAIL`) | nein | Server, Port, Verschlüsselung, Benutzername, Passwort, Absenderadresse, Absendername | Adressprüfung der Mailserver; Verbindung zum Server, mit Anmeldung, wenn ein Benutzername gesetzt ist, ohne eine Nachricht zu senden; auf Wunsch eine Testnachricht an eine angegebene Adresse | E-Mail ([E-Mail-Versand](e-mail.md)) |
| Branding (`BRANDING`) | nein | Produktname, Claim, Hinweis zur KI-Nutzung, Akzentfarbe, Farbschema-Vorgabe; keine Bilder | Regeln der Branding-Verwaltung, ohne Verbindung | Branding ([Branding](branding.md)) |

```bash
TOKEN=...   # aus dem Log oder OPAA_INSTALLATION_TOKEN
curl -H "Authorization: Bearer $TOKEN" https://opaa.example.org/api/v1/installation
curl -X PUT -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"signIn":{"mode":"LOCAL_ONLY"}}' \
  https://opaa.example.org/api/v1/installation/steps/SIGN_IN
curl -X POST -H "Authorization: Bearer $TOKEN" \
  https://opaa.example.org/api/v1/installation/steps/SIGN_IN/check
curl -X POST -H "Authorization: Bearer $TOKEN" \
  https://opaa.example.org/api/v1/installation/steps/MAIL/skip
curl -X POST -H "Authorization: Bearer $TOKEN" https://opaa.example.org/api/v1/installation/complete
```

**Optionale Schritte** lassen sich überspringen, etwa wenn der Modellendpunkt noch nicht
freigeschaltet ist: `POST /api/v1/installation/steps/<Schritt>/skip` verwirft, was für den Schritt
gespeichert war, samt Prüfung, und der Abschluss lässt ihn aus. Ein übersprungener oder nie
geöffneter optionaler Schritt hält den Abschluss nicht auf; ein gespeicherter dagegen schon, bis
seine Prüfung gelingt oder er übersprungen wird. Die Testnachricht des E-Mail-Versands verlangt die
Prüfung mit der Adresse im Rumpf (`{"testRecipient": "it-betrieb@stadt.example"}`); die Adresse wird
weder gespeichert noch protokolliert. Was ohne Chat- oder Embedding-Modell geht und was nicht, steht
unter [Ohne Modelle installiert](#ohne-modelle-installiert).

Das Passwort des ersten Kontos speichert OPAA nur als Hash, Zugangsschlüssel und Passwort des
Mailservers verschlüsselt mit `OPAA_SETTINGS_ENCRYPTION_KEY`; zurückgegeben wird nur, **ob** sie
gesetzt sind. Ein gespeicherter Schlüssel bleibt nur, solange die Basis-Adresse des Modells gleich
bleibt (Schema, Host, Port, Pfad); wer die Adresse ändert, gibt den Schlüssel neu ein oder entfernt
ihn. Ebenso geht ein gespeichertes Passwort des Mailservers nicht an einen anderen Server, Port,
Benutzernamen oder eine andere Verschlüsselung.

**Adressprüfung der Modelle.** Weil die Installation vor jedem Konto läuft, prüft OPAA die
Adressen von Chat-, Embedding-, Rerank- und Transkriptionsmodell beim Speichern und unmittelbar vor
Verbindungstest, Probeeinbettung und Probe, auch eine Adresse, die die Modellverwaltung schon
gespeichert hat: Adressen in internen, Loopback- oder Link-Local-Bereichen werden abgelehnt, ohne
dass eine Anfrage hinausgeht. Ein Modell im eigenen Netz, etwa Ollama im Compose-Stapel, gibt der
Betrieb mit Host und Port in `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` frei (`ollama:11434`); die
Liste der Quellen gibt kein Modell frei ([Adressprüfung der Modelle](modelle.md#adressprüfung-der-modelle)). Die Probeeinbettung folgt keiner Weiterleitung, wartet höchstens zehn Sekunden, wiederholt
sich nicht und liest nur eine begrenzte Antwort. Zwischen Prüfung und Anfrage löst OPAA den
Hostnamen erneut auf; wer den Namensdienst des Modells kontrolliert, könnte die Anfrage in diesem
Moment noch umlenken. Einen festen Endpunkt im eigenen Netz trifft das nicht.

**Adressprüfung der Mailserver.** Aus demselben Grund prüft die Installation den Mailserver des
Schritts E-Mail-Versand beim Speichern und unmittelbar vor der Prüfung: Ein Server, der in einen
internen, Loopback- oder Link-Local-Bereich auflöst, wird abgelehnt, ohne dass eine Verbindung
entsteht. Ein Mailserver im eigenen Netz, der Regelfall, braucht dafür einen Eintrag in
`OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST`, am besten als `host:port`, damit kein anderer Port des
Hosts erreichbar wird. Ohne Eintrag nimmt der Assistent nur die üblichen SMTP-Ports an (siehe
[Konfiguration](#konfiguration)); ein anderer Port, etwa `mailpit:1025` im Compose-Stapel, braucht
einen Eintrag mit genau diesem Host und Port. Wer den Server nicht freigeben will, überspringt den
Schritt und trägt ihn nach der Installation unter „Administration → E-Mail“ ein; dort findet keine
Adressprüfung statt. Eine gescheiterte Prüfung nennt nur, ob der Server nicht erreichbar ist, die
Verschlüsselung scheiterte, die Anmeldung abgelehnt wurde oder er nicht wie ein Mailserver antwortet;
die genaue Ursache steht im Log (WARN „Installation mail check failed“), ohne Passwort. Die Prüfung
wartet höchstens so lange, wie `OPAA_MAIL_CONNECT_TIMEOUT`, `OPAA_MAIL_READ_TIMEOUT` und
`OPAA_MAIL_WRITE_TIMEOUT` erlauben ([E-Mail-Versand](e-mail.md)); Prüfungen und Testnachrichten
sind je Instanz begrenzt.

**Der Abschluss** gelingt, wenn jeder Pflichtschritt und jeder gespeicherte optionale Schritt
erfolgreich geprüft ist, und genau einmal, auch wenn zwei Instanzen ihn zugleich versuchen. Er legt das erste Konto als
[Notanker-Konto](authentifizierung.md#erststart-und-systemverwalter-konto) an, mit dem gewählten
Passwort: aus dem Assistenten ohne erzwungenen Wechsel, aus einer Antwortdatei mit Wechsel bei der
ersten Anmeldung, solange `requirePasswordChange` nicht `false` ist. Er richtet das
Anmeldeverfahren ein: Er schaltet in beiden Verfahren die
[lokale Benutzerverwaltung](authentifizierung.md#lokale-benutzerverwaltung) ein, und ein OIDC-Anbieter
wird Standardanbieter. Er aktiviert das Chat-Modell, legt das
Embedding-Modell fest samt der Vektorablage mit der Zahl der Dimensionen aus der Probeeinbettung,
übernimmt ein geprüftes Rerank-Modell in die Modellverwaltung und schaltet das Reranking ein,
schaltet mit einem geprüften Transkriptionsmodell die Spracheingabe ein wie die Modellverwaltung,
übernimmt einen geprüften Mailserver in die Einstellungen des E-Mail-Versands und schaltet den
Versand ein, übernimmt ein geprüftes Branding wie die Branding-Verwaltung und verwirft Token und
Zwischenstand. Ein übersprungener Modellschritt legt kein Modell an, ohne Rerank-Modell bleibt das
Reranking aus, ohne Transkriptionsmodell die Spracheingabe; ist der E-Mail-Versand übersprungen,
bleibt er aus; ist das Branding übersprungen oder in jedem Feld leer, gilt der
OPAA-Standard, und der Abschluss schreibt dafür nichts. Vor dem Embedding-Modell gibt es keine
Vektorablage; ein späterer Wechsel des Embedding-Modells läuft in der Modellverwaltung, mit
Neueinbettung ([Modelle](modelle.md#modell-wechseln)). Danach
meldet sich die Person mit dem ersten Konto auf der regulären Anmeldeseite an, mit E-Mail-Adresse
und Passwort; alle Instanzen übernehmen das ohne Neustart. Mit den lokalen Konten steht „Passwort
vergessen“ an, sobald `OPAA_PUBLIC_BASE_URL` und der [E-Mail-Versand](e-mail.md) eingerichtet sind,
auch für das erste Konto über dessen Postfach. Für dieses Konto deshalb ein Funktionspostfach der IT
wählen; wer den Rücksetzweg nicht will, schaltet ihn in der
[Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md) ab. Jeder Schritt, jede Prüfung,
jedes Überspringen, das Einschalten der lokalen Konten, die Übernahme des Rerank-Modells
(„Rerank-Modell“) und der Abschluss stehen im Revisionsprotokoll, ohne Passwörter und ohne die Adresse einer Testnachricht.

## Einrichtungs-Assistent im Browser

Solange die Installation aussteht, führt jede Adresse der Oberfläche zum Assistenten unter
`/installation`, etwa `https://opaa.stadt.example/installation`. Er führt durch dieselben Schritte
wie die API und braucht dasselbe [Installations-Token](#installations-token); ohne gültiges Token
zeigt er keinen Schritt und keinen gespeicherten Wert.

- **Schrittliste.** Links stehen die Schritte mit ihrem Zustand: „Geprüft“, „Prüfung fehlgeschlagen“
  (der Grund steht über dem Formular, auch nach erneutem Öffnen), „Gespeichert, Prüfung offen“ oder
  „Offen“, ein optionaler Schritt ohne Angaben als „Nicht eingerichtet“. Pflichtschritte und
  optionale Schritte stehen in getrennten Gruppen; über dem Titel eines optionalen Schritts steht
  „Optional · später unter …“ (bei Chat-, Embedding-, Rerank- und Transkriptionsmodell „Modelle“,
  beim E-Mail-Versand
  „E-Mail“, beim Branding „Branding“), und „Überspringen“ lässt ihn aus. Pflicht sind erstes Konto und Anmeldeverfahren.
  „Überspringen“ in einem schon gespeicherten Schritt verwirft dessen Angaben, auch nach einer
  fehlgeschlagenen Prüfung.
- **Prüfen und weiter** speichert die Angaben eines Schritts und prüft sie sofort. Die Oberfläche
  prüft vorab nur die Form, etwa einer E-Mail-Adresse oder einer Adresse mit `http://` oder
  `https://`; alles Weitere prüft OPAA wie oben beschrieben.
- **Modelle auswählen.** In den Modellschritten wählt man zuerst den Anbieter (Ollama, OpenAI, beim
  Chat auch Anthropic, beim Transkriptionsmodell nur OpenAI, oder „Eigene Adresse“); die Vorlage trägt
  die Basis-Adresse ein. Der Schritt Rerank-Modell hat keine Vorlage, weil keiner dieser Anbieter
  einen Rerank-Endpunkt bietet; er beginnt bei der Basis-Adresse, ohne den Pfad `/rerank`. Verlässt
  man Adresse oder API-Schlüssel und steht beides, wo nötig, ruft
  der Assistent die Modelle ab, die der Endpunkt anbietet, und bietet sie unter „Modell-Kennung“
  zur Auswahl an, bei Ollama mit Größe und Kontextlänge; ebenso beim Öffnen eines gespeicherten
  Modellschritts. „Modelle abrufen“ wiederholt den Abruf. Es ist derselbe Abruf wie in der
  Modellverwaltung, mit derselben Adressprüfung, ohne Weiterleitungen und mit begrenzter
  Wartezeit. Liefert der Endpunkt keine Liste oder ist er nicht erreichbar, sagt der Assistent
  warum, und die Kennung wird von Hand eingetragen. Ein getippter Schlüssel geht nie von selbst an
  eine andere Adresse: Die Wahl eines anderen Anbieters leert ihn, und nach einer Änderung der
  Adresse von Hand sendet ihn erst „Modelle abrufen“ an die neue Adresse. Ein gespeicherter
  Schlüssel gilt nur für die gespeicherte Adresse. Meldet der Endpunkt mehr als 500 Modelle,
  zeigt die Auswahl die ersten 500 und sagt das. Je Instanz sind 30 Modellabrufe des Assistenten
  in 10 Minuten möglich, für alle Clients zusammen.
- **Embedding-Modell.** Nach erfolgreicher Probeeinbettung zeigt der Schritt die ermittelte Zahl
  der Dimensionen; mit ihr legt der Abschluss die Vektorablage an. Wer Adresse oder Modell danach
  ändert, prüft erneut.
- **Rerank-Modell.** Die Prüfung ist dieselbe Probe wie „Verbindung testen“ unter „Modelle →
  Reranking“; nach dem Abschluss steht das Modell dort, eingeschaltet. Nicht jeder Rerank-Dienst
  bietet eine Modellliste an; dann wird die Kennung von Hand eingetragen. Der Schritt hängt nicht von
  Chat- und Embedding-Modell ab und lässt sich auch prüfen und übernehmen, wenn beide übersprungen
  sind. Ohne Embedding-Modell gibt es keine Suche; das Reranking wirkt dann erst, wenn die
  Systemverwaltung eines festlegt.
- **Transkriptionsmodell.** Der Schritt hat die Texte und die Sprachregel von „Modelle →
  Spracheingabe“; die Sprache steht auf `de`, leer erkennt das Modell sie selbst. „Prüfen und
  weiter“ fragt den Endpunkt nach seinen Modellen und sendet keine Aufnahme. Unter den Anbietern
  steht nur OpenAI als Cloud-Dienst; die Kachel und nach ihrer Wahl ein Hinweis sagen, dass
  Aufnahmen dann an einen externen Dienst außerhalb des Hauses gehen
  ([Externer Anbieter](modelle.md#externer-anbieter)). Eine Bestätigung zur
  Mitbestimmung verlangt der Schritt nicht ([Spracheingabe und
  Mitbestimmung](modelle.md#spracheingabe-und-mitbestimmung)). Der Abschluss nennt, ob er die
  Spracheingabe einschaltet.
- **E-Mail-Versand.** Der Schritt hat die Felder, Hinweise und Regeln von „Administration →
  E-Mail → SMTP-Zugang“. „Prüfen und weiter“ baut die Verbindung auf und meldet sich an, ohne zu
  senden; steht unter „Testnachricht an“ eine Adresse, geht die Testnachricht dorthin. Ein Satz
  über der Schaltfläche sagt vor der Prüfung, was geschieht: ohne Adresse, dass nur die
  Verbindung geprüft wird, mit Benutzername auch die Anmeldung; mit gültiger Adresse, wohin die
  Testnachricht geht. Die Adresse wird nicht gespeichert. Fehlt `OPAA_PUBLIC_BASE_URL`, weist der Schritt wie die
  Verwaltung darauf hin, dass Links in E-Mails unbrauchbar bleiben. Scheitert die Prüfung, lässt
  sich der Schritt korrigieren oder überspringen; „Überspringen“ verwirft die Angaben. Nach
  erfolgreicher Prüfung steht beim Abschluss unter „E-Mail-Versand“ das Ergebnis, etwa
  „Verbindung und Anmeldung beim Mailserver sind gelungen. Es wurde keine Testnachricht
  gesendet.“ (ohne Benutzername „Die Verbindung zum Mailserver ist gelungen. …“) oder „… Die
  Testnachricht wurde dem Mailserver übergeben.“, ohne die Adresse. Der
  Abschluss nennt außerdem, ob er den Versand und das Reranking einschaltet.
- **Branding.** Der Schritt hat die Felder, Hinweise und Regeln von „Administration → Branding“,
  dazu dessen Kontrastwarnung der Akzentfarbe, die warnt, aber nicht sperrt, und die Vorschau von
  Produktname, Claim und Akzentfarbe im hellen und im dunklen Farbschema. Ein leeres Feld zeigt den
  OPAA-Standard, „Hinweis auf Standard zurücksetzen“ stellt den Standardtext des Hinweises zur
  KI-Nutzung wieder her, abschalten lässt er sich nicht. Logo, Logo der Anmeldeseite und
  Hintergrundbild nimmt der Assistent nicht an; sie lädt die Systemverwaltung nach der Installation
  unter „Administration → Branding“ hoch, bis dahin gelten die Bilder von OPAA.
- **Geheimnisse** zeigt der Assistent nie wieder an, nur als „Gesetzt“ mit „Ersetzen“; einen
  gespeicherten API-Schlüssel entfernt „Gespeicherten Schlüssel entfernen“, ein gespeichertes
  Passwort des Mailservers „Gespeichertes Passwort löschen“. Wer das erste Konto
  nachträglich ändert, gibt das Passwort erneut ein; unverändert wird es nur noch einmal geprüft.
- **Unterbrechen und fortsetzen.** Der Browser behält das Token nur, solange die Seite offen ist.
  Nach Neuladen oder an einem anderen Rechner gibt man es erneut ein und landet beim ersten
  Schritt, dessen Prüfung aussteht (ein Pflichtschritt oder ein gespeicherter optionaler), sonst beim
  Abschluss.
- **Abschluss.** „Installation abschließen“ ist erst möglich, wenn alle Pflichtschritte geprüft
  sind und jeder ausgefüllte optionale Schritt geprüft oder übersprungen ist; bis dahin nennt die
  Seite die offenen. Übersprungene Schritte stehen dort als „Nicht eingerichtet“. Danach führt „Zur
  Anmeldung“ zur Anmeldeseite. Hat
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
  },
  "rerankModel": {
    "baseUrl": "http://reranker:80",
    "model": "BAAI/bge-reranker-v2-m3",
    "apiKeyFile": null
  },
  "transcriptionModel": {
    "baseUrl": "http://speaches:8000/v1",
    "model": "Systran/faster-whisper-large-v3",
    "apiKeyFile": null,
    "language": "de"
  },
  "mail": {
    "host": "smtp.stadt.example",
    "port": 587,
    "encryption": "STARTTLS",
    "username": "opaa",
    "passwordFile": "/run/secrets/opaa-mail-password",
    "fromAddress": "opaa@stadt.example",
    "fromName": "OPAA"
  },
  "branding": {
    "productName": "Landesamt-Assistent",
    "primaryColor": "#7A3E9D",
    "defaultColorScheme": "SYSTEM"
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
- **Optionale Teile:** `chatModel`, `embeddingModel`, `rerankModel`, `transcriptionModel`, `mail` und
  `branding` sind optional. Fehlt ein Teil,
  gilt sein Schritt als übersprungen; was der Assistent vorher dafür gespeichert hatte, wird
  verworfen.
- **Rerank-Modell:** Ohne den Abschnitt `rerankModel` bleibt das Reranking aus. Mit ihm prüft OPAA
  Adresse und Endpunkt wie im Assistenten, und der Abschluss schaltet das Reranking ein. Der
  Schlüssel steht als `apiKeyFile`; ohne ihn sendet OPAA keinen.
- **E-Mail-Versand:** Ohne den Abschnitt `mail` bleibt der Versand aus. Mit ihm prüft OPAA Adresse
  und Verbindung wie im Assistenten, ohne Testnachricht, und der Abschluss schaltet den Versand ein.
  Das Passwort steht als `passwordFile`; ohne es meldet sich OPAA nicht an.
- **Spracheingabe:** Ohne den Abschnitt `transcriptionModel` bleibt die Spracheingabe aus. Mit ihm
  prüft OPAA Adresse und Endpunkt wie im Assistenten, und der Abschluss schaltet die Rolle ein; ohne
  `language` gilt `de`, eine leere Sprache lässt das Modell sie erkennen. Ist die Spracheingabe mit
  `OPAA_FEATURES_SPEECH_INPUT_ENABLED=false` abgeschaltet, übergeht OPAA den Abschnitt und meldet das
  im Log, die Datei gilt trotzdem.
- **Branding:** Ohne den Abschnitt `branding` gilt der OPAA-Standard. Mit ihm prüft OPAA die Angaben
  wie die Branding-Verwaltung; jedes Feld ist optional (`productName`, `claim`, `aiNotice`,
  `primaryColor`, `defaultColorScheme`), ein fehlendes oder leeres gilt als Standard. Bilder nimmt
  die Antwortdatei nicht an.
- Angaben späterer Schritte (`outboundProxy`) wertet diese Version nicht aus.
- Ohne `maxInputTokens` gilt eine Eingabegrenze von 2.048 Tokens
  ([Eingabegrenze des Embedding-Modells](modelle.md#eingabegrenze-des-embedding-modells)).

## Fehlerbilder

| Meldung | Bedeutung | Abhilfe |
|---|---|---|
| `503`, Code `INSTALLATION_PENDING` | Die Instanz ist nicht eingerichtet | Installation abschließen |
| `401` „Das Installations-Token fehlt oder ist ungültig." | Token fehlt oder falsch | Token aus dem Log oder `OPAA_INSTALLATION_TOKEN` nehmen |
| `400` am Feld Basis-Adresse „Die Basis-Adresse ist nicht zugelassen oder nicht erreichbar.“ mit Hinweis auf `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST`; im Assistenten dieselbe Meldung beim Modellabruf | die Modelladresse liegt in einem gesperrten Bereich, oder ihr Name lässt sich nicht auflösen; welches von beidem, nennt die WARN-Zeile „Model address … refused“ im Log (`TargetAddressBlockedException` oder `UnknownTargetHostException`) | erst die Schreibweise des Hosts prüfen, dann Host und Port in `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST` freigeben |
| `400` am Feld Zugangsschlüssel „Die Basis-Adresse hat sich geändert …" | der gespeicherte Schlüssel folgt keiner geänderten Adresse | Schlüssel neu eingeben oder Feld leeren |
| `400` am Feld Server „Der Mailserver ist nicht zugelassen oder nicht erreichbar …“ | der Mailserver liegt in einem gesperrten Bereich, oder sein Name lässt sich nicht auflösen; welches von beidem, nennt die WARN-Zeile „Mail server … refused“ im Log | Schreibweise prüfen, dann `host:port` in `OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST` freigeben, oder den Schritt überspringen und den Server später in der Verwaltung eintragen |
| `400` am Feld Port „Im Assistenten sind die Ports 25, 465, 587 und 2525 zugelassen …“ | ein anderer Port ohne Eintrag mit genau diesem `host:port` | Port prüfen, oder `host:port` in `OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST` eintragen |
| `400` am Feld Server, Absenderadresse, Passwort oder Testnachricht | Leer- oder Steuerzeichen im Server; mehr als ein Postfach in einer Adresse (`,` `;` `<` `>` `"`); `***` als Passwort, das in der Verwaltung „unverändert“ bedeutet | Eingabe korrigieren |
| `400` am Feld Produktname, Claim, Hinweis zur KI-Nutzung oder Primärfarbe | zu lang, Steuerzeichen oder Zeilenumbruch, oder eine Farbe, die kein sechsstelliger Hex-Wert mit `#` ist; dieselben Regeln wie unter „Administration → Branding“ | Eingabe korrigieren, das Feld leeren für den OPAA-Standard oder den Schritt überspringen |
| `400` am Feld Passwort „Server, Port, Zugangsname oder Verschlüsselung ändern sich …“ | das gespeicherte Passwort des Mailservers folgt keinem neuen Ziel | Passwort neu eingeben oder löschen |
| Prüfung des E-Mail-Versands „Der Mailserver ist nicht erreichbar …“ | falscher Server oder Port, Name nicht auflösbar, Verbindung abgelehnt oder Timeout beim Verbindungsaufbau | Server und Port prüfen, im Compose-Stapel den Servicenamen verwenden |
| Prüfung „Die verschlüsselte Verbindung zum Mailserver ist fehlgeschlagen …“ | STARTTLS nicht angeboten oder TLS-Handshake gescheitert | Verschlüsselung passend zum Port wählen ([Fehlerbilder des E-Mail-Versands](e-mail.md#fehlerbilder)) |
| Prüfung „Der Mailserver hat die Anmeldung abgelehnt …“ | Benutzername oder Passwort falsch | Zugangsdaten neu eintragen |
| Prüfung „Der Server antwortet nicht wie ein Mailserver …“ | unter Server und Port läuft kein SMTP-Dienst, oder er antwortet nicht in der Wartezeit | Port und Verschlüsselung prüfen; die Ursache nennt die WARN-Zeile „Installation mail check failed“ |
| Prüfung „… Der Mailserver hat die Testnachricht nicht angenommen.“ | die Verbindung gelang, der Server lehnte die Nachricht ab, etwa den Absender oder den Empfänger | Absenderadresse und Empfänger prüfen; die Ursache nennt die WARN-Zeile „Installation test message refused“ |
| `403` „Die Installation ist aus diesem Netz nicht zugelassen." | Client-Adresse außerhalb von `OPAA_INSTALLATION_ALLOWED_CIDRS` | aus dem erlaubten Netz aufrufen, vertraute Proxys prüfen |
| `400` „Die Anfrage ist zu groß." | Anfrage über 64 KiB | Antworten prüfen |
| `429` | zu viele Aufrufe oder abgelehnte Tokens von dieser Adresse; im Assistenten „Zu viele Modellabrufe“: das Budget der Modellabrufe ist erschöpft; mit dem Code `MAIL_TEST_LIMIT` „Zu viele Testnachrichten“: das Budget der Testnachrichten ist erschöpft; mit dem Code `MAIL_CHECK_LIMIT` „Zu viele Prüfungen des Mailservers“: das Budget der Prüfungen ist erschöpft (die übrigen Fälle zeigen „Zu viele Anfragen oder Fehlversuche von dieser Adresse“) | `Retry-After` abwarten, die Modell-Kennung lässt sich so lange von Hand eintragen, der Mailserver ohne Testnachricht prüfen; hinter einem Proxy `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` setzen |
| `409` beim Abschluss | ein Pflichtschritt ist nicht gespeichert oder nicht erfolgreich geprüft, oder ein gespeicherter optionaler Schritt ist nicht erfolgreich geprüft | offene Schritte prüfen oder optionale überspringen; die Meldung nennt sie |
| `400` beim Überspringen | der Schritt ist Pflicht | Schritt ausfüllen und prüfen |
| Prüfung des Rerank-Modells „Endpunkt oder Modell wurde nicht gefunden (HTTP 404) …“ | die Basis-Adresse enthält den Pfad `/rerank` schon, oder der Dienst kennt die Modell-Kennung nicht | Basis-Adresse ohne `/rerank` eintragen, Kennung prüfen, oder den Schritt überspringen und das Modell später unter „Modelle → Reranking“ eintragen |
| `400` „Den Schritt TRANSCRIPTION_MODEL gibt es in dieser Installation nicht …“ | die Spracheingabe ist mit `OPAA_FEATURES_SPEECH_INPUT_ENABLED=false` abgeschaltet | Schritt auslassen, oder die Variable entfernen und neu starten |
| `400` am Feld Sprache „Die Sprache ist ein Kürzel aus zwei Buchstaben …“ | die Sprache des Transkriptionsmodells ist kein Kürzel nach ISO 639-1 | Kürzel wie `de` eintragen oder das Feld leeren |
| Prüfung des Transkriptionsmodells „Endpunkt oder Modell wurde nicht gefunden (HTTP 404) …“ oder „Die Verbindung wurde vom Server abgelehnt.“ | der Dienst bietet `GET {Basis-Adresse}/models` nicht an, oder die Adresse enthält den Pfad `/audio/transcriptions` | Basis-Adresse ohne den Pfad eintragen, Dienst prüfen |
| Prüfung des Embedding-Modells „Die Probeeinbettung ist fehlgeschlagen …" | Endpunkt nicht erreichbar, Modell nicht geladen oder Schlüssel falsch | Endpunkt und Modell prüfen, Schritt erneut prüfen |
| Prüfung des Embedding-Modells „… der Vektorindex nimmt höchstens 2000 auf" | das Modell liefert mehr Dimensionen, als der Vektorindex aufnimmt | ein anderes Embedding-Modell wählen |
| `404` | die Installation ist abgeschlossen | anmelden mit dem ersten Konto |
| Log „The answer file … was not applied" | eine Angabe oder Prüfung der Antwortdatei scheiterte | Datei korrigieren, neu starten |

## Ohne Modelle installiert

Eine Installation ohne Chat- oder Embedding-Modell ist ein regulärer Zustand. OPAA startet ohne
Fehler im Log; Anmeldung, Konten, Spaces, Bibliotheken und die Verwaltung arbeiten wie sonst.

| Fehlt | Wirkung | Abhilfe |
|---|---|---|
| Chat-Modell | Fragen im Chat bekommen keine Antwort; der Chat zeigt „Noch kein Chat-Modell eingerichtet“, für die Systemverwaltung mit „Chat-Modell einrichten“ | unter Modelle → Chat-Modelle ein Modell anlegen und aktivieren, ohne Neustart ([Chat-Modelle in der Oberfläche anlegen](modelle.md#chat-modelle-in-der-oberfläche-anlegen)) |
| Embedding-Modell | keine Vektorablage: Indexierung, Upload und Suche antworten mit dem Hinweis, dass noch kein Embedding-Modell eingerichtet ist; geplante Läufe ruhen, von einer Quelle gemeldete Änderungen verfallen nach kurzer Zeit (der nächste Lauf nach dem Festlegen deckt sie ab). Fragen, die in Bibliotheken suchen, bekommen denselben Hinweis; der Chat zeigt „Noch kein Embedding-Modell eingerichtet“ | unter Modelle → Einbettung das erste Modell festlegen, ohne Neustart ([Erstes Modell festlegen](modelle.md#erstes-modell-festlegen)) |

Readiness und `/actuator/health` bleiben `UP`; die Health Groups `chat-model`, `embedding-model`
und `vector-store` melden `UNKNOWN` mit dem Detail `NOT_CONFIGURED`. Wer Modelle überwacht, wertet
dieses Detail aus (Health Groups mit Zugangstoken) oder mit einer angemeldeten Sitzung
`GET /api/v1/search/availability` (`chatModelConfigured`, `embeddingModelConfigured`); eine Überwachung nur auf `DOWN` bemerkt ein nie
eingerichtetes Modell nicht. Eine Vektortabelle mit Abschnitten, aber ohne Embedding-Modell bleibt
ein Fehler (`DOWN`, [Fehlerbilder der Modelle](modelle.md#fehlerbilder)).

## Konfiguration

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `OPAA_INSTALLATION_TOKEN` | leer: erzeugt und einmal ins Log geschrieben | Installations-Token, mindestens 32 Zeichen |
| `OPAA_INSTALLATION_ALLOWED_CIDRS` | leer: keine Beschränkung | Netzbereiche, aus denen die Installations-API antwortet |
| `OPAA_INSTALLATION_ANSWER_FILE` | leer: keine | Pfad der Antwortdatei, die ein Start anwendet |
| `OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST` | leer: kein interner Mailserver | Mailserver im eigenen Netz, die der Schritt E-Mail-Versand erreichen darf, je `host` oder `host:port` |

Die vier Variablen gelten je Instanz und wirken nur, solange die Installation offen ist; nach dem
Abschluss lassen sie sich entfernen, ohne dass ein Rolling Update daran scheitert. **Bei mehreren
Instanzen setzen alle oder keine `OPAA_INSTALLATION_TOKEN`, mit demselben Wert:** Startet eine Instanz
ohne die Variable, ersetzt sie das gesetzte Token durch ein erzeugtes und schreibt es ins Log (mit
einer Warnung); startet eine mit anderem Wert, gilt das zuletzt gestartete.

Fest im Code: je Netzadresse und Instanz 300 Aufrufe und 10 abgelehnte Tokens in 15 Minuten; je
Instanz 30 Modellabrufe des Assistenten in 10 Minuten, für alle Clients zusammen; höchstens 500
Modelle je Abruf; im Schritt E-Mail-Versand je Instanz 30 Prüfungen und 10 Testnachrichten in
10 Minuten, für alle Clients zusammen (die Antwortdatei beim Start zählt nicht), und ohne Eintrag in
`OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST` nur die Ports 25, 465, 587 und 2525.

## Weiterführende Kapitel

- [Konfiguration](konfiguration.md), [Authentifizierung](authentifizierung.md), [Modelle](modelle.md),
  [E-Mail-Versand](e-mail.md), [Branding](branding.md)
