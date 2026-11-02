# ADR-0066: Chatliste — persönliche Ablage, Chatsuche und Bereinigung

## Status

Akzeptiert · zuletzt geändert 2026-11-02

## Kontext

Wer täglich mit dem Assistenten arbeitet, hat nach Wochen Dutzende und nach einem Jahr Hunderte
Chats in einem Space. Die Chatliste war eine einzige, nach letzter Änderung sortierte Liste. Es
fehlten Ordnung, ein Archiv, eine Suche über den Inhalt und ein Export. Außerdem blieben Chats ohne
Regel unbegrenzt liegen, auch die einer ausgeschiedenen Person. Chats sind der größte und
aussagekräftigste Teil der Daten über eine Person.

Entschieden wurde in Epic #1762 (18.09.2026), für die Bereinigung je Space in #1923 (02.10.2026)
und für die Löschung bei gesperrten Konten in #2997 (23.10.2026). Den beiden letzten ging ein
Stakeholder-Review mit Personalrat und Betrieb voraus. Verglichen wurde mit ChatGPT, Claude, Gemini,
Open WebUI und LibreChat.

## Entscheidung

### 1. Ordnung ohne Ordner und Schlagworte

Die Seitenleiste bietet Titelfilter, Zeitgruppen und Anheften ohne Obergrenze. Die Seite „Chats“ je
Space bietet die Reiter Aktiv und Archiv, Mehrfachauswahl und die Inhaltssuche. Ordner und
Schlagworte gibt es nicht. Wer im Space weiter gliedern will, hat oft zwei Vorhaben in einem Space;
dann ist ein zweiter Space die bessere Antwort.

### 2. Persönliche Merkmale als eigene Zeilen

Angeheftet, archiviert und zurückgeholt sind Zeilen je Person und Chat, keine Spalten am Chat. Ihr
Setzen ändert die letzte Aktivität nicht und ist deshalb auch im archivierten Space erlaubt. Fremde
Merkmale sind weder lesbar noch setzbar. Der Versuch wird beantwortet wie bei einem unbekannten
Chat. So trägt das Modell auch geteilte Chats, in denen jede Person ihre eigene Ordnung hat.

### 3. Archiv als persönliche Ablage

Ein archivierter Chat bleibt lesbar und fortsetzbar. Eine eigene neue Nachricht holt ihn
automatisch zurück, mit Hinweis; Aktivität anderer tut das nicht. Archivieren löst die Anheftung
und ändert am Chat nichts. Es ist kein Löschen und gibt dem Chat keine eigene Lebensdauer. Den
Space leert es nicht, dessen Löschsperre bleibt bestehen.

### 4. Chatsuche über Titel, Fragen und Antworten

Die Chatsuche ist eine Volltextsuche mit deutscher Sprachkonfiguration über Titel, Fragen und
Antworten. Die Gesprächsnotiz und die Texte der Fundstellen werden nicht durchsucht. Sonst entstünde
eine Schattensuche über Dokumente an deren Rechtelage vorbei. Eine semantische Suche über Chats gibt
es nicht: Ein zweiter Vektorbestand aus dem privaten Arbeitsbereich steht in keinem Verhältnis zu
„wo stand das noch“. Gesucht wird nur im aktiven Space, das Archiv ist einbezogen und gekennzeichnet.
Der Rechtefilter steht in der Abfrage. Ein Durchgriff auf fremde Chats ist ausgeschlossen, auch
für die Space- oder Systemverwaltung. Die Suche ist nur in der angemeldeten Sitzung erreichbar,
nicht über Zugangstokens und nicht über den MCP-Server (ADR-0035).

### 5. Datenschutz der Ordnung und der Suche

Suchbegriffe werden nirgends protokolliert: nicht im Nachweisprotokoll, nicht im Anwendungslog,
nicht in Metriken. Sie stehen in keiner URL, der Endpunkt nimmt sie im Body entgegen, und die
Seite legt sie nicht im Browser ab. Die Ordnungsmerkmale sieht niemand sonst. Sie werden nicht
protokolliert und nicht ausgewertet, auch nicht als Zahl je Person. Exportiert werden sie nur in der
Selbstauskunft.

### 6. Export eines einzelnen Chats

Ein Chat lässt sich als Markdown (im Browser) oder PDF (im Server) exportieren. Der Export enthält
Titel, Gesprächsnotiz, Fragen und Antworten mit Zeit und die Quellen als Fußnoten. Er endet mit
dem KI-Vermerk. Exportieren darf, wer den Chat lesen darf. Der Export wird weder protokolliert
noch gespeichert. Mehrere Chats oder ganze Spaces lassen sich nicht exportieren.

### 7. Automatisches Archivieren und Löschen je Space

Jeder Space hat einen Schalter, ab Werk aus. Umlegen darf ihn, wer die Stammdaten ändert. Im
persönlichen Standard-Space darf das nur die Person selbst. Ein Standard-Space wird deshalb nie
übertragen. Die Fristen sind systemweit und nur vom Betrieb einstellbar: Archivieren nach 90 Tagen
ohne Aktivität, Löschen nach 365 Tagen im Archiv. Die Untergrenzen von 90 und 30 Tagen brechen
sonst den Start ab. Aktivität ist nur der vorhandene Zeitstempel des Chats oder des Zurückholens.
Die Fristen wirken nicht rückwirkend, angeheftete Chats sind ausgenommen. Die Mitglieder sehen die
Fristen auf der Seite „Chats“, der archivierte Chat zeigt sein Löschdatum. Protokolliert wird nur
das Umlegen des Schalters. Der Lauf schreibt nur Gesamtzahlen ins Anwendungslog.

### 8. Löschung der Chats von der Verwaltung gesperrter Konten

- **Auslöser** ist allein die Sperre durch die Verwaltung. Inaktivität, Fehlversuche, Ablauf und
  Anbieterzustände sind es nicht. Eine solche Sperre lässt sich ohne Entsperren in eine
  Verwaltungssperre umwidmen.
- **Frist:** einstellbar von der Systemverwaltung, ab Werk 365 Tage, Grenzen 90 bis 3650 Tage, und
  abschaltbar. Sie beginnt mit der aktuellen Sperre, frühestens mit dem letzten Einschalten.
  Entsperren beendet sie.
- **Umfang:** Gelöscht werden alle privaten Chats des Kontos in allen Spaces, angeheftete
  eingeschlossen. Konto und Standard-Space bleiben, geteilte Chats ebenfalls.
- **Aussetzen:** Eine offene anlassbezogene Klärung setzt die Löschung aus.
- **Anhalten:** Eine Anhaltung braucht einen Zweck aus einer festen Liste (Anordnung,
  Beweissicherung nach Vorfall, Auskunfts- oder Löschantrag) und eine Begründung. Disziplinarische
  und leistungsbezogene Zwecke sind nicht wählbar. Es gilt das Vier-Augen-Prinzip. Die Höchstdauer
  setzt der Betrieb (ab Werk 180 Tage).
- **Protokoll:** je Konto und Lauf ein Ereignis mit Fristbeginn und Sperrgrund, ohne Zahl, Titel
  oder Space. Anhaltungen werden ohne Begründung protokolliert.
- **Sichtbarkeit:** Das früheste Löschdatum steht nur in der Einzelansicht des Kontos und in der
  Mail zur Sperre. In der Kontenliste gibt es keine Spalte, keinen Filter und keinen Export.

Eine Liste „wird bald gelöscht“ wäre eine Liste der Ausgeschiedenen.

## Konsequenzen

- **Einfacher:** Chats bleiben auffindbar, ohne dass jemand fremde Chats einsehen kann. Für
  Ausgeschiedene gibt es einen geregelten Löschweg, ohne das Konto zu löschen.
- **Schwieriger:** Mit dem Teilen von Chats ist neu zu entscheiden, wessen Archivzeitpunkt die
  Löschfrist bestimmt. Ebenso, ob die Chatsuche geteilte Chats einbezieht.
- **Restrisiko:** Eine Sicherung bringt gelöschte Chats zurück. Der nächste Lauf löscht sie erneut,
  sofern das Konto nicht vorher entsperrt oder angehalten wird.

## Verworfene Alternativen

- Ordner oder Schlagworte im Datenmodell vorsehen: kein gemeldeter Bedarf (1).
- Archiv schreibgeschützt bis zum Zurückholen, oder fortsetzbar und archiviert bleibend: Ein
  Gespräch, an dem man arbeitet, ist nicht erledigt (3).
- Semantische Chatsuche: zweiter großer Vektorbestand privater Inhalte (4).
- Suchbegriff als Query-Parameter: landet im Zugriffslog des Proxys (5).
- Allgemeine Aufbewahrungsfrist für Chats: als nicht geplant geschlossen (#216); stattdessen der
  Schalter je Space (7).
- Löschung bei jeder Art von Sperre: Inaktivität ist Abwesenheit, Fehlversuche sind ein Angriff
  Dritter (8).

## Offen

- Suche nach Titeln zitierter Dokumente; offen ist, wie sie mit einer inzwischen entzogenen
  Bibliothek umgeht.
- Spaceübergreifende Chatsuche.
- Filter „meine / mit mir geteilte“ und Bereinigung geteilter Chats, beides mit dem Teilen von Chats.
- Ordner oder Schlagworte, nur bei gemeldetem Bedarf.
