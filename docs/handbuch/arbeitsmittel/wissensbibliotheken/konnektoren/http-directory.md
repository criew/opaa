# Konnektor: Webverzeichnis

## 1. Was der Konnektor ist

Der Konnektor liest eine **automatisch erzeugte Verzeichnisliste**, wie sie ein Webserver für einen
Ordner ohne Startseite ausgibt („Index of /dokumente"): einen internen Dokumentenserver, ein per
Apache oder nginx freigegebenes Ablageverzeichnis, einen Download-Bereich. Er spricht **HTTP(S)** und
versteht die Listen von Apache `mod_autoindex` (mit und ohne Tabelle), nginx `autoindex on` und
schlichte `<ul>`-Listen, wie sie Pythons `http.server` liefert. Er ist **kein** allgemeiner
Web-Crawler: Er folgt nur Links aus Verzeichnislisten und bleibt unterhalb der Start-Adresse.

```mermaid
flowchart LR
    B[Wissens-<br/>bibliothek] -.-> Z[Zugang<br/>optional]
    B -->|HTTP GET,<br/>optional<br/>Basic Auth| W[Webserver]
    W --> L[Verzeichnis-<br/>liste und<br/>Dateien]
```

- **Anmeldung:** keine oder HTTP Basic mit Benutzername und Passwort, eigene Zugangsdaten der
  Bibliothek ([Zugänge](../../zugaenge.md), „Eigene Zugangsdaten der Bibliothek"). Ein Zugang ist
  möglich.
- **Liest:** alle Dateien in unterstützten Formaten unter der Start-Adresse, rekursiv.
- **Dokument:** Die Adresse der Datei ist die Identität.
- **Rechte** des Webservers übernimmt OPAA nicht.

## 2. Einrichtung

1. Liegt der Server im privaten Netz, seinen Hostnamen für die
   [Zieladressprüfung](README.md#12-zieladressprüfung) freigeben — für diesen Konnektor der Normalfall.
2. Bei geschütztem Verzeichnis ein Konto mit Leserecht anlegen.
3. Optional einen Zugang anlegen, wenn mehrere Bibliotheken denselben Server lesen.

## 3. Quellkonfiguration

| Angabe im Formular | Bedeutung |
|---|---|
| „Adresse (URL)" | Pflicht, `http://` oder `https://`. Fehlt der abschließende Schrägstrich und sieht der letzte Pfadteil nicht wie eine Datei aus, wird er ergänzt. Anmeldedaten in der Adresse (`https://benutzer:passwort@host`) lehnt OPAA ab: Die Adresse wird im Klartext gespeichert und protokolliert, Benutzername und Passwort gehören in das Feld darunter |
| „Anmeldedaten (optional)", beim Bearbeiten „Neue Zugangsdaten" | `benutzer:passwort`; der erste Doppelpunkt trennt |

Bleibt beim Bearbeiten das Feld für die Zugangsdaten leer und der Ursprung der Adresse (Schema, Host,
Port) gleich, bleiben die gespeicherten Zugangsdaten. Ein anderer Ursprung verwirft sie, damit
niemand ein unbekanntes Passwort an einen eigenen Server umleiten kann. Adresse und Laufprotokoll
sehen nur Verwaltende; von den Zugangsdaten nur, ob welche hinterlegt sind.

**„Verbindung testen"** lädt die Start-Adresse und unterscheidet „nicht erreichbar", „erreichbar,
aber keine erkennbare Verzeichnisliste" und „Verzeichnisliste erkannt".

**Über einen Zugang:** Die Adresse liegt unter der Server-Adresse des Zugangs. Er meldet sich ohne
Anmeldung an oder mit Benutzername und Passwort, die die Verwaltenden der Bibliothek eintragen.
Vorgaben für weitere Einstellungen gibt es nicht.

## 4. Betriebsarten und Änderungserkennung

Jeder Lauf ist ein **Vollabgleich**: Er lädt die Start-Seite, erkennt an Tabellenzeilen,
`<pre>`-Blöcken, Datum-und-Größe-Mustern oder einem Titel wie „Index of", ob sie eine
Verzeichnisliste ist, und betritt Unterverzeichnisse rekursiv in Fundreihenfolge. Eine gewöhnliche
Startseite liefert so keine Einträge. Den Dateinamen nimmt er aus dem Linkziel, weil Apache lange
Namen im Linktext kürzt; Datum und Größe liest er aus dem Text hinter dem Link, bei `<ul>`-Listen
fehlen sie.

```mermaid
flowchart LR
    P[Verzeichnis-<br/>seite laden] -->|Unter-<br/>verzeichnis| P
    P -->|Datei| E{Datum bekannt<br/>und gleich?}
    E -- ja --> S[übersprungen]
    E -- nein --> H[Anfang laden,<br/>Format erkennen]
    H -- unterstützt --> D[ganz laden,<br/>Dokumentstrecke]
```

Unverändertes erkennt er in zwei Stufen:

1. **Änderungsdatum aus der Liste.** Ist es bekannt und gleich dem gespeicherten, wird die Datei
   nicht geladen. Ein fehlendes Datum gilt als „unbekannt", nicht als „unverändert".
2. **Prüfsumme** nach dem Download, wie bei jeder Quelle. Ändert sich nur das Datum, bleiben die
   Chunks, und das neue Datum wird gespeichert.

Vor dem Download einer neuen oder geänderten Datei lädt er nur die ersten 64 KiB und erkennt daran das
Format; ein nicht unterstütztes Format kostet so nur einen kleinen Teil-Download.

Ein Crawl endet **vollständig**, **abgeschnitten** (Tiefe oder Menge erreicht) oder
**unvollständig** (ein Unterverzeichnis war nicht abrufbar oder wurde aus dem Start-Unterbaum heraus
weitergeleitet). Reicht das Anfragebudget nicht, endet der Lauf als „unvollständig, wird
fortgesetzt", und der nächste durchsucht das Verzeichnis erneut und überspringt Unverändertes.

## 5. Löscherkennung

Entfernt wird nur nach einem **vollständigen** Crawl: Dokumente, deren Adresse er nicht mehr
gefunden hat. Ein abgeschnittener, unvollständiger oder leerer Crawl entfernt nichts. Anhänge
unveränderter Mails gelten als vorhanden.

## 6. Ordner

Die Verzeichnisstruktur erscheint als schreibgeschützte Ordner: der URL-Pfad unterhalb der
Start-Adresse ohne Dateiname und Abfrage, jedes Segment dekodiert (`Verg%C3%BCtung` wird
`Vergütung`). Ordner entstehen nur entlang gefundener Dateien und werden nur nach einem vollständigen
Crawl aufgeräumt. Ein Segment, das leer ist, `.` oder `..` lautet, einen Pfadtrenner oder ein
NUL-Byte enthält oder länger als 255 Zeichen ist, ergibt keinen Ordner; die Datei liegt dann in der
Wurzel, mit einer Warnung im Log. Ein Anhang liegt im Ordner seiner Mail.

## 7. Anhänge

Eine EML- oder MSG-Datei liefert ihre Anhänge als eigene Dokumente, mit den Grenzen des Kapitels
[E-Mail](../formate/mail.md). Links in Verzeichnislisten gelten nicht als Anlagen.

## 8. Grenzen und Schutzmechanismen

- **Zieladressprüfung** vor jeder Anfrage und auf jedem Redirect
  ([Konnektoren](README.md#12-zieladressprüfung)).
- **Im Verzeichnis bleiben.** Links auf fremde Server, gleichursprüngliche Links außerhalb des
  gerade gelesenen Verzeichnisses und Links, die mit `../` — auch prozentkodiert wie `%2E%2E/` —
  über die Start-Adresse hinausführen, werden nicht verfolgt. Jede Adresse wird nur einmal besucht.
  Sortier-Links (`?C=`), „Parent Directory", Anker, `mailto:` und `javascript:` werden ignoriert.
- **Redirects.** Höchstens fünf; `https` auf `http` wird abgelehnt. Zugangsdaten gehen nur
  mit, solange das Ziel auf demselben Ursprung und unterhalb der Start-Adresse bleibt; sonst wird
  ohne Zugangsdaten gefolgt. Landet eine Verzeichnisseite außerhalb des Start-Unterbaums, wird sie
  nicht ausgewertet, und der Crawl ist unvollständig. Nennt die Start-Adresse eine Datei
  (`/liste.php`), gehören sie selbst, auch mit anderer Abfrage, und alles darunter zum Unterbaum.
  Protokolle nennen vom Ziel nur Schema, Host und Port.
- **„Original öffnen"** lädt die Datei nach derselben Regel: Zugangsdaten gehen nur mit, solange
  ihre Adresse und jeder Redirect auf dem Ursprung und unterhalb der Start-Adresse bleiben.
- **Drosselung.** Nur bei HTTP 429 wartet der Lauf die in `Retry-After` genannte Zeit und wiederholt;
  jeder andere Fehlschlag ist für diesen Lauf endgültig.

| Grenze | Parameter oder fester Wert | Bei Überschreitung |
|---|---|---|
| Verzeichnistiefe | `opaa.indexing.crawl.max-depth`, Wurzel ist 0 | Crawl abgeschnitten |
| Einträge (Dateien und besuchte Verzeichnisse) | `opaa.indexing.crawl.max-entries` | Crawl abgeschnitten |
| Datei | `opaa.indexing.crawl.max-file-size-bytes` | Datei abgewiesen; geprüft an `Content-Length` und während des Downloads |
| Verzeichnisseite | 8 MiB, fest | Unterverzeichnis nicht abrufbar; an der Wurzel scheitert der Lauf |
| Anfragen je Lauf | `opaa.indexing.http.request-budget-per-run` | „unvollständig, wird fortgesetzt" |
| Summe der 429-Wartezeiten je Lauf | `opaa.indexing.http.max-rate-limit-wait-per-run` | „unvollständig, wird fortgesetzt" |

Timeouts: 30 s Verbindungsaufbau, 60 s je Verzeichnisseite, 120 s je Datei. Grenzen gegen
komprimierte Inhalte liegen in den Formaten. DNS-Rebinding bleibt eine bekannte Restlücke.

## 9. Protokoll und Fehlerbilder

| Meldung | Bedeutung | Abhilfe |
|---|---|---|
| „Das Zertifikat des Servers wird nicht anerkannt …" | Zertifikat einer hauseigenen Zertifizierungsstelle oder auf einen anderen Namen | Zertifizierungsstelle ergänzen ([Zertifikatsprüfung](README.md#13-zertifikatsprüfung)) |
| „Crawl wurde durch ein konfiguriertes Limit abgeschnitten (Tiefe oder Anzahl Einträge)" | keine Löscherkennung | Grenzen anheben oder Start-Adresse enger wählen |
| „Mindestens ein Unterverzeichnis konnte nicht abgerufen werden, der Bestand dieses Laufs ist unvollständig" | keine Löscherkennung | Server prüfen |
| „Verzeichnisseite wurde auf eine Adresse außerhalb der Start-URL weitergeleitet …" | Redirect aus dem Unterbaum, keine Löscherkennung | Redirect am Server prüfen |
| „Datei überschreitet die zulässige Größe von …" | Größengrenze | Grenze anheben oder hinnehmen |
| „Zieladresse liegt in einem gesperrten Adressbereich" | Zieladressprüfung | Hostnamen freigeben |
| „Dateiformat wird nicht unterstützt" | Inhalt nicht zugelassen, auch eine Bot-Schutzseite statt einer Datei | erwartbar |
| „Dateiendung passt nicht zum erkannten Inhalt (erkannt: …)" | wird trotzdem indiziert | nichts |
| „Verarbeitung fehlgeschlagen" | Download- oder Pipeline-Fehler, auch HTTP 403 oder 429 nach allen Wiederholungen | bei Häufung Server prüfen |
| „Die Quelle hat den Lauf n-mal gedrosselt …" (Ratenbegrenzung) | eine Zeile je Lauf, wenn gewartet wurde | bei Häufung Zeitplan entzerren |
| „Anfragebudget von … Anfragen erschöpft …" / „Deckel der 429-Wartezeit von … je Lauf erreicht …" | der nächste Lauf durchsucht erneut | Budget oder Deckel anheben |
| Lauf fehlgeschlagen, etwa „HTTP 401 Unauthorized, check credentials" | Start-Adresse nicht erreichbar oder Zugangsdaten abgelehnt; nichts wird entfernt | Adresse und Zugangsdaten prüfen |

Eine Wartungsseite mit HTTP 200 enthält keine Verzeichnisliste: Der Lauf endet erfolgreich mit null
Einträgen und entfernt nichts. Wird ein Lauf während einer Datei gestoppt, endet er mit „Lauf
unterbrochen", ohne die Datei als Fehler zu zählen.

## 10. Konfiguration

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `opaa.indexing.crawl.max-depth` | `OPAA_INDEXING_CRAWL_MAX_DEPTH` | 10 | Verzeichnistiefe, Wurzel ist 0; 0 bedeutet Standard |
| `opaa.indexing.crawl.max-entries` | `OPAA_INDEXING_CRAWL_MAX_ENTRIES` | 50000 | Dateien und besuchte Verzeichnisse zusammen |
| `opaa.indexing.crawl.max-file-size-bytes` | `OPAA_INDEXING_CRAWL_MAX_FILE_SIZE_BYTES` | 104857600 (100 MiB) | Obergrenze je Datei |
| `opaa.indexing.http.user-agent` | `OPAA_INDEXING_HTTP_USER_AGENT` | `OPAA-Indexer/1.0` | `User-Agent`, gemeinsam mit Feed und Confluence; wahrheitsgemäß, keine Browser-Imitation |
| `opaa.indexing.http.max-rate-limit-retries` | `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_RETRIES` | 6 | aufeinanderfolgende 429-Antworten, die eine Anfrage abwartet |
| `opaa.indexing.http.max-retry-after` | `OPAA_INDEXING_HTTP_MAX_RETRY_AFTER` | 2m | Obergrenze einer Wartezeit aus `Retry-After`; ohne Header fünf Sekunden |
| `opaa.indexing.http.request-budget-per-run` | `OPAA_INDEXING_HTTP_REQUEST_BUDGET_PER_RUN` | 0 (unbegrenzt) | Anfragen je Lauf einschließlich Wiederholungen, gemeinsam mit dem Feed |
| `opaa.indexing.http.max-rate-limit-wait-per-run` | `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_WAIT_PER_RUN` | 15m | Summe der 429-Wartezeiten je Lauf, gemeinsam mit dem Feed |

Nicht konfigurierbar: Verzeichnisseiten-Grenze, Timeouts, Zahl der Redirects. Zugangsdaten
verschlüsselt der Schlüssel aus `OPAA_CREDENTIALS_ENCRYPTION_KEY`; die Zieladressprüfung steht in der
[Übersicht](README.md#12-zieladressprüfung).

## 11. Nicht gebaut

- Wartezeit zwischen Anfragen; die gibt es nur beim Feed
- Wiederholungen über die 429-Wartezeit hinaus
- Bedingte Anfragen mit ETag oder If-Modified-Since
- Schonzeitraum, Rechteübernahme, ereignisgesteuerte Aktualisierung, Drosselung nach wiederholtem
  Scheitern
