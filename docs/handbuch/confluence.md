# Betriebshandbuch: Confluence-Konnektor

Dieses Handbuch bündelt, was der Betrieb wissen muss, bevor die erste Confluence-Bibliothek produktiv
geht (#1142, Epic #1129). Es beschreibt das gebaute Verhalten; die Begründungen stehen in
[ADR-0023](../decisions/0023-confluence-konnektor.md), die fachliche Einordnung in
[`features/knowledge-sources.md`](../features/knowledge-sources.md) („Konnektor“), die Umgebungsvariablen
mit Standardwerten in [`deployment.md`](./deployment.md) („Alle Umgebungsvariablen“, Block
`OPAA_INDEXING_CONFLUENCE_*` und `OPAA_RATE_LIMIT_WEBHOOK_*`).

**Kurzfassung für den eiligen Betrieb**

1. On-premises-Instanz? Hostnamen in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` eintragen.
2. Ein Dienstkonto je Leserkreis; Token (Cloud: E-Mail + API-Token, Data Center: Personal Access Token)
   nur mit Leserecht auf die gewünschten Spaces.
3. Bibliothek anlegen: Adresse → „Edition erkennen“ → Zugangsdaten → „Verbindung testen“ → Spaces
   auswählen. Alles aus den gewählten Spaces ist für **alle** Leseberechtigten der Bibliothek sichtbar.
4. Zeitplan setzen. Der Konnektor läuft inkrementell und wöchentlich als Vollabgleich; nur der
   Vollabgleich entfernt, was in Confluence nicht mehr existiert.
5. Optional Webhook einrichten (Geheimnis in OPAA erzeugen, in Confluence hinterlegen).
6. Im Laufprotokoll auf „nicht lesbar“, „Ratenbegrenzung“ und „unvollständig, wird fortgesetzt“ achten.

---

## 1. Was der Konnektor tut — und was nicht

Eine Confluence-Bibliothek zeigt auf **eine** Instanz (Cloud oder Data Center), mit **einem** Token und
einer **Auswahl von Spaces** (bis zu 500). Indiziert werden die **aktuellen Seiten** dieser Spaces und
ihre **Anhänge** in den unterstützten Dateiformaten. Nicht indiziert werden Blogbeiträge, Kommentare,
Whiteboards, Datenbanken, archivierte Seiten (Cloud) und der Papierkorb. Aus Makros bleibt, was eine
Autorin selbst geschrieben hat (Hinweiskästen, Aufklappbereiche, Code); was Confluence zur Laufzeit aus
anderen Quellen zusammensetzt (Inhaltsverzeichnisse, Kindseitenlisten, Jira-Tabellen, Berichte), fällt
weg — Einzelheiten in [`features/ingestion-pipelines.md`](../features/ingestion-pipelines.md), Teil 3,
Punkt 6.

Jedes Dokument trägt Space, Gliederungspfad (die Vorfahren-Titel), Seitentitel, Versionsnummer und die
titelfreie Confluence-URL; die Fundstelle im Chat verlinkt auf die Seite bzw. den Anhang in Confluence.

**Rechte werden nicht abgebildet.** Was das Token lesen darf und in der Auswahl liegt, sehen alle
Leseberechtigten der Bibliothek — Abschnitt 8 („Grenzen“) sagt, was daraus für den Zuschnitt folgt.

## 2. Voraussetzungen im Betrieb

### 2.1 Zielprüfung und Allowlist (on-premises-Instanzen)

Alle Abrufe des Backends gegen fremde Adressen — Indizierungsläufe **und** der Verbindungstest —
durchlaufen die SSRF-Zielprüfung (`OPAA_INDEXING_TARGET_VALIDATION_ENABLED`, Standard `true`): Ziele,
deren aufgelöste Adresse Loopback, Link-Local, privat (`10/8`, `172.16/12`, `192.168/16`) oder
anderweitig nicht routbar ist, werden abgelehnt, auch nach einer Weiterleitung. Ein selbst betriebenes
Confluence Data Center steht praktisch immer in einem solchen Bereich.

Die Prüfung **nicht abschalten**, sondern den Hostnamen der Instanz ausnehmen:

```env
# .env / .env.docker — exakter Hostname, ohne Schema und Pfad, kommagetrennt bei mehreren
OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST=wiki.behoerde.example
```

Der Eintrag gilt für alle Bibliotheken gegen diese Adresse (Betriebseinstellung, keine Eigenschaft der
Bibliothek). Ein fehlender Eintrag zeigt sich beim Verbindungstest als abgewiesenes Ziel — nicht als
Token-Fehler.

### 2.2 Proxy und TLS

Je Bibliothek kann ein Proxy (`host:port`) hinterlegt werden; die Zertifikatsprüfung lässt sich je
Bibliothek aussetzen (`sourceInsecureSsl`) — nur für Testinstanzen, nie im Produktivbetrieb. Ein
eigenes Zertifikat der Behörden-CA gehört in den Truststore des Backend-Containers, nicht in das
Aussetzen der Prüfung.

### 2.3 Umgebungsvariablen (Auszug)

| Variable | Standard | Wirkung |
|---|---|---|
| `OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL` | `7d` | Abstand, in dem statt des inkrementellen Abgleichs ein Vollabgleich läuft |
| `OPAA_INDEXING_CONFLUENCE_INCREMENTAL_OVERLAP` | `10m` | Überlappung des Änderungsfensters nach hinten |
| `OPAA_INDEXING_CONFLUENCE_REQUEST_BUDGET_PER_RUN` | `50000` | Anfragen je Lauf, danach „unvollständig, wird fortgesetzt“ |
| `OPAA_INDEXING_CONFLUENCE_MAX_RATE_LIMIT_RETRIES` / `_MAX_RETRY_AFTER` | `6` / `2m` | Wie oft und wie lange ein Lauf auf `429` wartet |
| `OPAA_INDEXING_CONFLUENCE_MAX_ATTACHMENT_SIZE_BYTES` | `20971520` | Obergrenze je Anhang (20 MiB) |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_DEBOUNCE` / `_MAX_PENDING_PAGES` / `_MAX_DEFERRALS` | `5s` / `200` / `120` | Sammelzeit, Stapelgrenze und Wartezyklen des Webhook-Eingangs |
| `OPAA_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` (je IP und Bibliothek) / `_GLOBAL_MAX_REQUESTS` | `120` / `600` pro Minute | Ratenbegrenzung des Webhook-Eingangs |

Vollständige Tabelle und Erläuterungen: [`deployment.md`](./deployment.md).

## 3. Einrichtung je Edition

### 3.1 Confluence Cloud

- **Token-Art:** E-Mail-Adresse des Kontos plus **API-Token** (erzeugt unter
  *Atlassian-Konto → Sicherheit → API-Token*). OPAA speichert beides als `<E-Mail>:<Token>` verschlüsselt
  und sendet es als HTTP Basic.
- **Minimale Berechtigungen:** Das Konto braucht eine Confluence-Lizenz auf der Site und **Ansehen**
  auf jedem Space der Auswahl — nichts weiter. Kein Site-Admin, kein Space-Admin.
- **Erkennung:** OPAA erkennt die Edition an der Adresse (`*.atlassian.net/wiki`) ohne Zugangsdaten;
  der Verbindungstest prüft das Token mit einer Anfrage.
- **Ratenbegrenzung:** Cloud rechnet mit einem Punktebudget und antwortet mit `429`/`Retry-After`;
  OPAA wartet (Abschnitt 6). Zwei Bibliotheken gegen dieselbe Site teilen sich dieses Budget.

### 3.2 Confluence Data Center

- **Token-Art:** **Personal Access Token (PAT)** des Kontos (*Profil → Personal Access Tokens*), gesendet
  als `Bearer`. Ein Ablaufdatum setzen und den Ablauf im Betriebskalender führen — ein abgelaufenes
  Token macht die Bibliothek nicht kaputt, aber jeder Lauf schlägt sichtbar fehl.
- **Minimale Berechtigungen:** **Ansehen** auf jedem Space der Auswahl; das Konto muss sich per REST
  anmelden dürfen (kein reines UI-Konto hinter einem SSO, das PATs nicht zulässt).
- **Besonderheit:** Data Center **weist ein ungültiges Token nicht ab**, sondern beantwortet Anfragen
  anonym — mit dem, was anonym sichtbar ist (oft nichts). OPAA prüft deshalb vor jedem Lauf und im
  Verbindungstest, als wer die Instanz das Token sieht; ein anonym beantwortetes Token wird als
  „nicht angenommen“ gemeldet, nicht als leere Instanz.
- **Kontextpfad:** Eine Instanz unter `https://wiki.behoerde.example/confluence` wird mit genau dieser
  Adresse eingetragen; OPAA folgt dem Kontextpfad.

### 3.3 Dienstkonten-Zuschnitt (Empfehlung)

- **Ein Dienstkonto je Leserkreis, nicht je Bibliothek.** Das Token bestimmt die Obergrenze dessen,
  was in die Bibliothek gelangen kann; die Space-Auswahl grenzt darunter ein. Ein Konto, das „alles“
  lesen darf, mit einer schmalen Auswahl zu kombinieren, funktioniert — aber ein Fehlgriff in der
  Auswahl legt dann Inhalte offen, die das Konto eines schmaleren Zuschnitts gar nicht hätte liefern
  können.
- **Kein persönliches Konto.** Ein persönliches Token trägt die Rechte einer Person und verfällt mit
  ihrem Austritt; die Bibliothek fällt dann aus.
- **Token je Bibliothek hinterlegen.** OPAA kennt keine geteilte „Verbindung“; wer ein Token rotiert,
  das in fünf Bibliotheken steht, rotiert es fünfmal (Bibliothek → Quellkonfiguration → Bearbeiten).
  Ein Rotationskalender mit Bibliotheksliste je Token erspart die Suche.
- **Webhook-Geheimnis** ebenfalls je Bibliothek (Abschnitt 5); es ist ein zweites Geheimnis, kein Ersatz
  für das Token.

## 4. Bibliothek anlegen, Betriebsarten, Löschsemantik

### 4.1 Anlage

Der Anlagedialog führt in dieser Reihenfolge: **Adresse eingeben → „Edition erkennen“ → Zugangsdaten
im Format der erkannten Edition → „Verbindung testen“ → Spaces auswählen**. Die Auswahl zeigt nur, was
das Token lesen darf; über der Auswahl steht der Hinweis, den auch dieses Handbuch wiederholt: **Alles,
was aus den gewählten Spaces indiziert wird, sehen alle Leseberechtigten der Bibliothek.** Die Auswahl
ist später änderbar; jede Änderung erzwingt beim nächsten Lauf einen Vollabgleich. Die Edition ist nach
der Anlage nicht änderbar (eine migrierte Instanz wird als neue Bibliothek angelegt).

Die Bibliotheksansicht zeigt jedem Leser Edition und ausgewählte Spaces (die Freigabefolge ist keine
Konfigurationsinterna), Verwaltenden zusätzlich Adresse, Proxy und Webhook-Zustand.

### 4.2 Betriebsarten

| Betriebsart | Was passiert | Wann |
|---|---|---|
| **Vollabgleich** (`FULL`) | Alle gewählten Spaces vollständig auflisten, Neues und Geändertes holen, am Ende entfernen, was in der Auflistung fehlt | Erster Lauf; nach jeder Änderung der Space-Auswahl oder Adresse; nach einem unterbrochenen Vollabgleich; sobald der letzte Vollabgleich älter als `FULL_SYNC_INTERVAL` ist; manuell über „Vollabgleich starten“ |
| **Inkrementell** (`INCREMENTAL`) | Per Suche nur die seit dem Anker geänderten Seiten holen (Fenster relativ zur Uhr der Instanz, mit Überlappung); nie wegen Abwesenheit löschen | Jeder andere geplante Lauf und „Jetzt indizieren“ |
| **Webhook** (Auslöser `WEBHOOK`, Betriebsart inkrementell) | Nur die gemeldeten Seiten gezielt holen | Wenige Sekunden nach einer Benachrichtigung (Abschnitt 5) |

„Jetzt indizieren“ folgt dem Zustand der Bibliothek; „Vollabgleich starten“ erzwingt den Vollabgleich.
Der Vollabgleich bleibt nötig, weil nur er Löschungen nachvollzieht; sein Rhythmus ist verlängerbar,
nicht abschaltbar (instanzweit; je Bibliothek ist #1200).

### 4.3 Löschsemantik — der eine Satz

**Ein Dokument wird nur entfernt, wenn die Instanz selbst den Befund liefert.** Das heißt konkret:

- Der **Vollabgleich** entfernt am Ende, was in der vollständigen Auflistung fehlt — aber nur, wenn die
  Auflistung vollständig war. Konnte ein Space oder eine Anhangsliste nicht gelesen werden, bleibt der
  gesamte Bestand stehen und das Protokoll sagt es („… nicht lesbar; sein Bestand bleibt bis zur
  nächsten vollständigen Auflistung unverändert“).
- Eine Seite, die die Instanz beim Einzelabruf **als im Papierkorb** ausweist, wird mitsamt Anhängen
  entfernt — in jeder Betriebsart.
- Ein **`404` oder `403`** beim Einzelabruf entfernt **nichts**: Der bisherige Stand bleibt, bis der
  nächste Vollabgleich über die Auflistung entscheidet. Ein Rechteentzug oder ein Token-Fehler leert den
  Index nie stillschweigend.
- Ein **Webhook** ist ein Anlass zur Prüfung, kein Befund (Abschnitt 5).

Ein entzogenes Leserecht wirkt also **verzögert**: Der Bestand eines nicht mehr lesbaren Spaces bleibt
sichtbar, bis eine Verwalterin den Space aus der Auswahl nimmt oder das Recht zurückkommt und der
Vollabgleich entscheidet. Wer Inhalte sofort aus OPAA entfernen muss, nimmt den Space aus der Auswahl
(erzwingt den Vollabgleich, der den Bestand dieses Spaces entfernt).

## 5. Webhooks einrichten

Webhooks verkürzen die Zeit bis zur Aufnahme einer Änderung von „nächster geplanter Lauf“ auf wenige
Sekunden. Sie ersetzen weder Zeitplan noch Vollabgleich: **Ohne Webhook ist nichts falsch, nur später.**

### 5.1 Geheimnis in OPAA erzeugen

Bibliothek → Quellkonfiguration (Verwaltende) → Zeile **Webhook** → **„Webhook einrichten“**. OPAA zeigt
das Geheimnis **genau einmal** zusammen mit der Adresse des Eingangs
(`https://<opaa-host>/api/v1/libraries/<Bibliotheks-ID>/confluence-webhook`). Beides jetzt in Confluence
hinterlegen; danach ist das Geheimnis nur noch als „eingerichtet“ sichtbar. „Geheimnis neu erzeugen“
rotiert (das alte gilt sofort nicht mehr), „Webhook entfernen“ schließt den Eingang. Beide Aktionen
fragen nach — Confluence merkt nichts davon, wenn OPAA seine Nachrichten abweist. Jede Änderung steht im
Audit-Protokoll als `LIBRARY_SOURCE_UPDATED` mit dem Feldnamen `confluenceWebhookSecret`, nie mit dem
Wert.

Der Eingang muss für die Instanz erreichbar sein (Firewall/Proxy-Regel von Confluence zu OPAA). Er ist
der einzige Pfad unter `/api/v1`, der ohne Anmeldung erreichbar ist; die Nachricht weist sich mit dem
Geheimnis aus. Der vorgelagerte Proxy **muss** `X-Forwarded-For` autoritativ setzen (siehe
[`deployment.md`](./deployment.md), „Netzwerkzugang“), sonst greift die Ratenbegrenzung je Client nicht.

### 5.2 Data Center (nativ, signiert)

1. *Confluence-Administration → Webhooks → Webhook erstellen*.
2. **URL:** die von OPAA angezeigte Adresse.
3. **Secret:** das von OPAA angezeigte Geheimnis. Confluence signiert damit jede Nachricht
   (`X-Hub-Signature: sha256=<HMAC-SHA256 über den Rohkörper>`); OPAA prüft die Signatur zeitkonstant.
4. **Ereignisse:** Seite erstellt, aktualisiert, entfernt, in den Papierkorb verschoben,
   wiederhergestellt; Anhang erstellt, aktualisiert, entfernt. Andere Ereignisse (Spaces, Blogs,
   Kommentare) darf man mitsenden — OPAA ignoriert alles ohne Seiten-ID.
5. **Aktiv** setzen und speichern.

Probe: In einem gewählten Space eine Seite bearbeiten; wenige Sekunden später erscheint in der
Laufhistorie ein Lauf „per Webhook“ mit genau dieser Seite. Ohne Lauf: Abschnitt 9.

### 5.3 Cloud (über eine Automation-Regel)

Cloud bietet keine frei konfigurierbaren Webhooks ohne App; der Weg ist eine **Automation-Regel**
(*Space-Einstellungen → Automation* oder global):

1. **Auslöser:** „Seite veröffentlicht“ und „Seite aktualisiert“ (nach Bedarf auch „Seite
   archiviert/gelöscht“ — OPAA entscheidet ohnehin am Abruf).
2. **Aktion:** „Web-Anfrage senden“ — **URL** = Adresse aus OPAA, **Methode** `POST`, **Body** als
   benutzerdefinierte Daten (JSON):
   ```json
   {"pageId": "{{page.id}}"}
   ```
3. **Header:** `X-OPAA-Webhook-Secret` mit dem Geheimnis aus OPAA (Cloud-Regeln können nicht signieren;
   der Header trägt das Geheimnis selbst — deshalb nur über TLS).
4. Regel aktivieren; ein Test über „Regel ausführen“ genügt als Probe.

Eine Regel deckt einen Space oder die Site ab; für eine Auswahl mehrerer Spaces genügt eine
site-weite Regel — Seiten außerhalb der Auswahl protokolliert OPAA als „liegt in einem nicht
ausgewählten Space“ und lässt sie liegen.

### 5.4 Was eine Nachricht auslöst — und was nicht

- Aus dem Körper werden **nur Seiten-IDs** gelesen (`page.id`, `content.id`, `attachment.pageId`,
  `pageId`, `pageIds`); die **Ereignisart wird nicht ausgewertet**. Was mit der Seite geschah, sagt der
  Abruf: geändert → neu indiziert; von der Instanz als im Papierkorb ausgewiesen → entfernt; `404`/`403`
  → unverändert.
- Nachrichten werden je Bibliothek **gesammelt** (`WEBHOOK_DEBOUNCE`, 5 s) und in **einem** kurzen Lauf
  geholt. Mehr als `WEBHOOK_MAX_PENDING_PAGES` (200) Seiten in einem Stapel → statt Einzelabrufen ein
  gewöhnlicher Lauf in der Betriebsart, die der Zustand vorgibt.
- Läuft für die Bibliothek gerade ein Lauf, wartet der Stapel — bis zu `WEBHOOK_MAX_DEFERRALS` (120 ×
  5 s = 10 min) — und wird dann **verworfen**: Der nächste geplante Lauf deckt dieselben Seiten ab. Ein
  Verwerfen kostet Aktualität, nie Korrektheit.
- Der **Anker des inkrementellen Abgleichs bewegt sich nicht**; der nächste inkrementelle Lauf liest die
  gemeldeten Seiten noch einmal (je ein Auflistungseintrag).
- **Kein Replay-Schutz:** Eine mitgeschnittene, gültig signierte Nachricht lässt sich wieder
  einspielen und kostet je einen gezielten Lauf innerhalb der Ratenbegrenzung — für den Index folgenlos.
- Jede nicht authentifizierte Anfrage (unbekannte Bibliothek, kein Geheimnis, falsche Signatur) erhält
  dieselbe `401`; der Körper ist auf 256 KiB begrenzt (`413` darüber).

## 6. Ratenbegrenzung, Anfragebudget, Kennzahlen

### 6.1 Wenn die Instanz bremst (`429`)

Antwortet Confluence mit `429` und `Retry-After`, **wartet** der Lauf die genannte Zeit (gedeckelt auf
`MAX_RETRY_AFTER`, 2 min) und wiederholt die Anfrage — bis zu `MAX_RATE_LIMIT_RETRIES` (6) Mal in Folge,
dann bricht der Lauf mit einer klaren Meldung ab. Ein gebremster Lauf ist **kein Fehler**: Das Protokoll
enthält eine Zeile „Ratenbegrenzung“ mit Anzahl und Gesamtwartezeit, egal wie der Lauf endet. Cloud
bremst nach einem Punktebudget je Site — mehrere Bibliotheken gegen dieselbe Site bremsen einander;
Data Center bremst standardmäßig nicht (Abschnitt 6.2 ist dort die einzige Grenze).

### 6.2 Das Anfragebudget je Lauf

`REQUEST_BUDGET_PER_RUN` (Standard **50 000**) begrenzt, wie viele Anfragen ein Lauf an die Instanz
sendet — Wiederholungen nach `429` und Anhangs-Downloads eingerechnet. Ist es erschöpft, endet der Lauf
**geordnet als „unvollständig, wird fortgesetzt“**: Status *Abgeschlossen* mit dem Chip **„unvollständig,
wird fortgesetzt“**, ein Protokolleintrag „Anfragebudget erschöpft“ nennt, wo der nächste Lauf ansetzt.

- **Vollabgleich:** Der Fortschritt je Space ist gespeichert (auch über einen Neustart hinweg); der
  nächste Lauf beginnt mit den offenen Spaces. Ein angebrochener Space wird erneut aufgelistet; bereits
  gespeicherte Seiten kosten dabei nur einen Auflistungseintrag (Versionsvergleich vor dem Abruf), geholt
  wird nur, was fehlt. Ein unvollständiger Vollabgleich **bereinigt nichts** — die Auflistung war nicht
  vollständig.
- **Inkrementell:** Der Anker bleibt stehen; der nächste Lauf durchsucht dasselbe Fenster erneut.
- **Webhook-Lauf:** Die übrigen gemeldeten Seiten nimmt der nächste Lauf auf.

**Faustregel zur Größe:** Eine Seite kostet etwa zwei Anfragen (Body, Anhangsliste) plus ihre
Anhangs-Downloads und einen Anteil der Auflistung (100 Seiten je Anfrage). 50 000 Anfragen decken damit
rund 20 000 Seiten je Lauf. Eine Auswahl von 100 000 Seiten braucht für den ersten Vollabgleich also
etwa fünf Läufe — bei täglichem Zeitplan eine Arbeitswoche, danach sind inkrementelle Läufe klein. Das
Budget ist **kein** Schutz vor der Bremse der Instanz (das leistet Abschnitt 6.1), sondern eine Grenze für
die Dauer eines einzelnen Laufs; wer es kleiner setzt als ein einzelner großer Space an Anfragen braucht,
erlebt Läufe, die denselben Space immer wieder auflisten und wenig hinzugewinnen — dann das Budget
anheben oder den Space in eine eigene Bibliothek nehmen. `0` schaltet das Budget ab.

### 6.3 Kennzahlen lesen

Jeder Lauf zeigt in der Laufhistorie im Kopf Status, Zeitpunkt, Auslöser („manuell gestartet“, „per
Zeitplan“, „per Webhook“), Zähler (verarbeitet/übersprungen/fehlgeschlagen), Betriebsart, ggf.
„unvollständig, wird fortgesetzt“ und die Zahl der Protokolleinträge („n Ereignisse, davon k nicht
lesbar“); aufgeklappt eine **Kennzahlenzeile**:

> 1842 Anfragen an die Quelle · 3-mal gedrosselt (95 s gewartet) · Anhänge: 120 indiziert, 800
> übersprungen, 2 fehlgeschlagen · Dauer 42 min 10 s

So liest man sie:

- **Anfragen ÷ Dauer** = Durchsatz. Sinkt er bei gleichbleibender Instanz, wird gebremst (siehe
  Drosselungen) oder das Netz ist der Engpass.
- **Anfragen gegen das Budget**: Liegt ein Vollabgleich regelmäßig nah am Budget und endet
  unvollständig, ist die Auswahl für einen Lauf zu groß — Budget anheben oder Auswahl aufteilen.
- **Übersprungene Anhänge** sind normal (unverändert, nicht unterstütztes Format); **fehlgeschlagene**
  Anhänge nennt das Protokoll einzeln (Größengrenze, Download-Fehler).
- **Drosselungen** auf Cloud: Häufig und lang → Läufe verschiedener Bibliotheken gegen dieselbe Site
  zeitlich entzerren (Zeitpläne versetzen).

Dieselbe Zeile steht je Lauf im Backend-Log (`Confluence run … requests, … throttles …`), für
Auswertungen über viele Läufe.

## 7. Das Laufprotokoll lesen

| Eintrag | Bedeutung | Was tun |
|---|---|---|
| **Abgewiesen** „… ist für das hinterlegte Dienstkonto nicht lesbar oder nicht mehr vorhanden, übersprungen; der bereits indizierte Stand bleibt erhalten“ | Seite `404`/`403` beim Einzelabruf | Nichts, wenn gewollt; sonst Rechte des Dienstkontos prüfen. Der Bestand bleibt bis zum nächsten Vollabgleich |
| **Abgewiesen** „Space … ist für das hinterlegte Dienstkonto nicht lesbar; sein Bestand bleibt …“ | Ganzer Space nicht auflistbar | Rechte prüfen oder Space aus der Auswahl nehmen. Solange das steht, bereinigt kein Vollabgleich |
| **Abgewiesen** „… liegt in einem nicht ausgewählten Space“ | Seite wurde verschoben bzw. per Webhook außerhalb der Auswahl gemeldet | Nichts; ggf. Auswahl erweitern |
| **Nicht erreichbar** | Netz/Instanz antwortet nicht oder mit Serverfehler | Instanz, Proxy, Allowlist prüfen; der Lauf zählt die Seite als fehlgeschlagen und rückt den Anker nicht vor |
| **In der Quelle entfernt** „In Confluence im Papierkorb, entfernt“ / „… verschoben“ | Positiver Befund, Dokument entfernt | Nichts |
| **Ratenbegrenzung** | Die Instanz hat gebremst; Anzahl und Wartezeit | Bei Häufung Zeitpläne entzerren |
| **Anfragebudget erschöpft** | Lauf endete geordnet unvollständig | Nichts — der nächste Lauf setzt fort; bei Dauerzustand Abschnitt 6.2 |
| **Format nicht unterstützt** / **Fehler** (Anhang) | Anhangsformat nicht indizierbar / Verarbeitung fehlgeschlagen | Erwartbar bei Bildern u. ä.; Fehler bei Häufung im Backend-Log nachsehen |
| Lauf **Fehlgeschlagen** „… Token … nicht angenommen“ / „anonym“ | Zugangsdaten ungültig oder abgelaufen (DC: anonym beantwortet) | Token rotieren (Quellkonfiguration → Bearbeiten) |

## 8. Grenzen des Konnektors

- **Freigabefolge der gemeinsamen Bibliothek.** Alles Indizierte ist für jeden Leseberechtigten der
  Bibliothek sichtbar — unabhängig von seinen Confluence-Rechten. Der Zuschnitt (Dienstkonto, Auswahl,
  Leserkreis der Bibliothek) ist die einzige Steuerung; die Obergrenze der Freigabe (#797) ist offen.
- **Keine Rechteabbildung.** Confluence-Gruppen oder Space-Berechtigungen werden nicht auf OPAA-Rechte
  abgebildet; ein Rechteentzug wirkt verzögert (Abschnitt 4.3).
- **Nur Seiten und Anhänge.** Keine Blogbeiträge, Kommentare, Whiteboards, Datenbanken, keine weiteren
  Atlassian-Produkte; archivierte Seiten (Cloud) gelten als nicht vorhanden.
- **Anhänge nur mit dem Vollabgleich vollständig.** Ein neuer oder geänderter Anhang an einer sonst
  unveränderten Seite bewegt deren Änderungszeit nicht; der inkrementelle Lauf sieht ihn nicht, der
  Webhook nur, wenn Confluence das Anhangs-Ereignis meldet.
- **Makros:** Dynamische Inhalte fallen weg; was fehlt, steht in
  [`features/ingestion-pipelines.md`](../features/ingestion-pipelines.md).
- **Ratenbudget der Instanz ist geteilt** zwischen allen Bibliotheken gegen dieselbe Instanz; eine
  instanzweite Koordination der Läufe gibt es nicht.
- **Rhythmus des Vollabgleichs ist instanzweit** (#1200: je Bibliothek); ein bibliotheksweiter Zustand
  „Auflistung unvollständig“ jenseits des Laufprotokolls ist #1191.
- **Webhooks:** kein Replay-Schutz, keine Bestätigung an Confluence über das Ergebnis, Cloud nur über
  Automation-Regeln.

## 9. Fehlerbehebung

| Symptom | Wahrscheinliche Ursache | Prüfung / Abhilfe |
|---|---|---|
| Verbindungstest: Ziel abgewiesen, obwohl die Instanz erreichbar ist | Zielprüfung blockt die private Adresse | Hostname in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`, Backend neu starten |
| „Edition erkennen“ findet kein Confluence | Falscher Pfad (Kontextpfad fehlt), Proxy nötig, Instanz hinter Anmeldung ohne REST-Zugang | Adresse mit Kontextpfad; Proxy eintragen; `curl <adresse>/rest/api/space?limit=1` aus dem Backend-Netz |
| Data Center: Verbindungstest meldet Token „nicht angenommen“, obwohl es im Browser geht | PAT ungültig/abgelaufen oder Konto darf REST nicht nutzen — DC antwortet anonym | Neues PAT; Rechte des Kontos prüfen |
| Verbindungstest gut, aber Space-Auswahl leer | Konto hat auf keinen Space „Ansehen“ | Space-Rechte des Dienstkontos setzen |
| Läufe enden ständig „unvollständig, wird fortgesetzt“ | Budget kleiner als ein einzelner Space braucht; oder sehr große Auswahl | Abschnitt 6.2: Budget anheben, Auswahl aufteilen |
| Läufe brechen mit Ratenbegrenzung ab | Mehr als 6 aufeinanderfolgende `429`; Cloud-Punktebudget durch parallele Läufe erschöpft | Zeitpläne entzerren; `MAX_RATE_LIMIT_RETRIES`/`MAX_RETRY_AFTER` anheben |
| Webhook: kein Lauf nach einer Änderung | Eingang von Confluence aus nicht erreichbar; Geheimnis falsch (Confluence erhält `401`); Ereignis ohne Seiten-ID; Stapel wartet auf laufenden Lauf | Backend-Log „Rejected Confluence webhook … not authenticated“ → Geheimnis neu erzeugen und in Confluence eintragen; Erreichbarkeit vom Confluence-Host prüfen; Laufhistorie auf laufenden Lauf prüfen |
| Webhook: `413` | Nachricht größer als 256 KiB | Body der Automation-Regel auf die Seiten-ID beschränken |
| Webhook: `429` | Ratenbegrenzung je IP/Bibliothek | `OPAA_RATE_LIMIT_WEBHOOK_*` anheben; `X-Forwarded-For` am Proxy prüfen |
| Ein gelöschter Space ist noch im Index | Space noch in der Auswahl, Auflistung nicht lesbar → kein Vollabgleich bereinigt | Space aus der Auswahl nehmen (erzwingt Vollabgleich) |
| Nach Token-Rotation läuft nichts mehr | Altes Token in weiteren Bibliotheken | Jede Bibliothek gegen diese Instanz einzeln aktualisieren (Abschnitt 3.3) |

Signatur eines Webhook-Tests von Hand (Data Center-Format):

```bash
BODY='{"event":"page_updated","page":{"id":"102"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "<geheimnis>" | sed 's/^.* //')
curl -i -X POST "https://<opaa-host>/api/v1/libraries/<id>/confluence-webhook" \
  -H "Content-Type: application/json" -H "X-Hub-Signature: sha256=$SIG" --data "$BODY"
# 202 Accepted = angenommen; 401 = Geheimnis/Signatur falsch oder Bibliothek ohne Webhook
```
