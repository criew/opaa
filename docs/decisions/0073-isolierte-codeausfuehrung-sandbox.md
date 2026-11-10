# ADR-0073: Isolierte Codeausführung für Agenten (Sandbox)

## Status

Akzeptiert · zuletzt geändert 2026-11-10

## Kontext

Ein Agent soll Dateien erzeugen: ein PDF nach Vorgabe, eine Tabellenauswertung, ein Diagramm. Dafür
schreibt das Modell Code, den niemand vorher prüft ([ADR-0070](0070-werkzeugaufrufe-im-chat.md),
Werkzeug `run_code` der Rechteklasse *isoliert*). Dieser Code darf weder auf den Knoten ausbrechen
noch Opaa-Daten außer den übergebenen sehen noch ungeprüft ins Netz. Wettbewerber zeigen, wo es
schiefgeht: Sprach-Sandboxen im Serverprozess (vm2, Flowise, n8n), Sandbox-APIs ohne Anmeldung
(Dify, OpenHands), Dateinamen aus der Sandbox als Pfad auf dem Server (LibreChat), ein Egress-Proxy,
der bei Fehlern öffnet (E2B). Die Recherche und der Spike (#3584) haben OpenSandbox im Docker-Modus
durchgespielt. Behörden betreiben Kubernetes, OpenShift oder Docker; gVisor ist auf OpenShift nicht
unterstützt, Kata braucht dort Bare Metal. Die Plattform entscheidet also über die Isolationsstärke,
nicht der Code.

## Entscheidung

### 1. Fertiger Baustein hinter eigener Schnittstelle

Opaa betreibt keine eigene Sandbox. Der Sandbox-Dienst ist **OpenSandbox** (Apache 2.0) mit
REST-API, Pflicht-Schlüssel und Docker- wie Kubernetes-Modus; auf Kubernetes und OpenShift kann er
agent-sandbox (Kubernetes SIG Apps) als Unterbau nutzen. Das Backend spricht nur mit der eigenen
Schnittstelle `io.opaa.query.sandbox.Sandboxes`; der Baustein dahinter ist austauschbar, ohne ein
Werkzeug anzufassen. Begründung: Ein gepflegter Baustein mit vielen Beitragenden zieht Sicherheits-
und Plattformkorrekturen nach, die ein Eigenbau selbst leisten müsste.

### 2. Eine Sandbox je Agentenlauf, nie geteilt

Der erste Aufruf von `run_code` oder `fill_template` in einem Lauf öffnet eine Sandbox, weitere
Aufrufe desselben Laufs, auch des jeweils anderen Werkzeugs, arbeiten im selben Ordner weiter. Das
Lauf-Ende schließt sie in jedem Ausgang. Ihre Lebensdauer beim
Dienst ist die Restzeit des Laufs plus Antwortaufrufe, gedeckelt durch `opaa.sandbox.max-lifetime`;
fällt eine Instanz aus, löscht der Dienst sie selbst. Eine Sandbox gehört genau einem Lauf einer
Person. Begründung: Ein Lauf lebt eine Anfrage lang (ADR-0070, Entscheidung 1); eine Korrekturrunde
der Person ist ein neuer Lauf und baut auf den Anhängen der vorigen Antwort auf, nicht auf einem
geparkten Container.

### 3. Ergebnisse nur als Anhang der Antwort

Am Ende des Laufs, bevor die Antwort gespeichert wird, liest das Backend nach einem Aufruf von
`run_code` die Dateien direkt in `/workspace/out` (höchstens fünf, je höchstens 10 MB, in einer
festen Frist) in einem Anhangsformat. `fill_template` schreibt in ein eigenes, frisch angelegtes
Verzeichnis je Aufruf, dessen Namen das Backend zufällig wählt; direkt nach dem Skript liest das
Backend dort nur die Dateien, die das Skript gemeldet hat, nur DOCX und PDF, und behält sie bis zum
Lauf-Ende. Jede Datei geht durch alle Prüfungen eines gewöhnlichen Uploads, ein DOCX zusätzlich
durch die Prüfung auf aktive Inhalte wie beim Hochladen einer Vorlage (Entscheidung 8), und wird
Anhang der fragenden Person in deren Chat. Bilder sind kein Anhangsformat; ein Diagramm kommt als
PDF oder eingebettet in ein Dokument. Der Endstand zählt, nicht jeder Zwischenstand. Alles aus der
Sandbox gilt als feindlich: Name, Pfad, Größe und Dateityp werden geprüft, Unterordner und Verweise
nicht gelesen, Ausgaben und Dateinamen erreichen das Modell nur als markierter Datenblock. Eine
Datei, die sich nicht abholen oder anhängen lässt, fehlt, ohne die Antwort zu verwerfen;
`fill_template` meldet dem Modell nur Dateien, die es schon gelesen und geprüft hat.

### 4. Kein Netz, Anmeldung je Aufruf

Die Sandbox hat keinen Ausgang: Unter Docker hängt sie an einem internen Netz ohne Ausgang, das
Backend ist nicht darin, erreichbar sind dort nur der Dienst und andere Sandboxen, beide nur mit
Schlüssel oder Token; unter Kubernetes legt das Helm-Chart im Namespace der Sandboxen eine
NetworkPolicy an, die jeden Ausgang sperrt und Eingang nur vom Dienst zulässt, unabhängig von den
übrigen Netzwerkregeln; dieser Namespace darf nicht der von Opaa sein. Der Dienst verlangt seinen
Schlüssel, jede Sandbox ein eigenes Zufallstoken für ihren Ausführungsagenten. Das Skript läuft ohne
Root-Rechte und startet ohne dieses Token in seiner Umgebung: Mit dem Token könnte es den
Ausführungsagenten in der eigenen Sandbox ansprechen und Kommandos als Root ausführen. Der Container
behält nur die Capabilities, die der Ausführungsagent braucht. Ein Ausgangstor mit Zulassungsliste
kommt erst mit einem Werkzeug, das Netz braucht.

### 5. Isolationsstärke ist Einstellung des Dienstes

Wie stark eine Sandbox vom Knoten getrennt ist, setzt der Betrieb über die Laufzeitklasse des
Dienstes: gVisor auf Kubernetes (auch auf VMware), Standardlaufzeit mit User Namespaces und eigenen
Worker-VMs auf OpenShift auf VMware, Kata auf OpenShift mit Bare Metal. Opaa ändert dafür keinen
Code. Im Compose-Betrieb braucht der Dienst den Docker-Socket; dieser Stapel gehört auf eine
eigene VM.

### 6. Abschaltbar, ohne Dienst nicht angeboten

`opaa.sandbox.enabled` ist aus. Eingeschaltet ohne Adresse oder Schlüssel startet das Backend nicht.
Ohne Schalter gibt es weder Client noch Werkzeuge als Bean; die Werkzeug-Registry kennt `run_code`
und `fill_template` dann nicht, und kein Agent kann sie aufrufen. Eingeschaltet entscheiden der
Werkzeugschalter und die Zuordnung am Agenten (ADR-0070, Entscheidung 6), bei `fill_template`
zusätzlich der Schalter „Vorlagen“ und die Leserechte der fragenden Person an der Vorlage.

### 7. Sandboxprotokoll als eigener Bestand neben dem Schrittprotokoll

Je Sandbox hält eine eigene Tabelle Lauf-Kennung, technische Kennung der Sandbox, Werkzeug,
Ergebnis, Dauer, Zahl der Skripte, Zahl und Größe der Dateien und den Abbruchgrund fest; kein Konto,
kein Skript, kein Dateiinhalt. Sie folgt der Bauart des Schrittprotokolls (ADR-0070, Entscheidung 4,
Bestand b): Tagespartitionen im Besitz von `opaa_audit_owner`, derselbe Leseweg mit Lauf-Kennung und
Anlass, eigene Frist (Vorgabe 7, höchstens 30 Tage, nie länger als das Schrittprotokoll).
Geschrieben wird beim Schließen der Sandbox, auch wenn sie nicht startet. Der Dienst selbst
protokolliert auf Stufe `INFO` oder höher, also ohne Anfragekörper, und Compose rotiert sein Log;
ein Test prüft die mitgelieferte Konfiguration.

### 8. Feste Skripte des Images für Werkzeuge ohne Modellcode

`fill_template` lässt das Modell keinen Code schreiben: Es liefert nur die Werte der Platzhalter,
geprüft gegen die beim Hochladen gelesenen Platzhalter der Vorlage. Das Backend lädt Vorlage und
Werte in die Sandbox des Laufs und startet ein festes Skript aus dem Image (`/opt/opaa`, gehört
Root, mit `python3 -I`, damit Dateien im Arbeitsordner kein Modul ersetzen). Das Skript füllt mit
docxtpl in Jinjas Sandbox-Umgebung mit Autoescape, sodass ein Wert nie als Vorlage ausgewertet und
immer als Text eingesetzt wird, und erzeugt das PDF mit LibreOffice ohne Oberfläche, bis zu einer
Frist ab Skriptstart, die das Backend aus dem Zeitlimit des Aufrufs ableitet; LibreOffice endet
spätestens mit dem Skript. Eine Vorlage darf nur, was Feld, Liste und Bedingung brauchen, nach einer
Zulassungsliste statt einer Sperrliste: Text, Ausgabe, Namen, Teile der Daten über Attribut oder
festen Schlüssel, `for` und `if`, Vergleiche, Addieren und Subtrahieren, Verketten und eine kleine
Liste von Filtern, die nur Text, Zahlen oder Listen liefern (`default`, `upper`, `lower`, `title`,
`capitalize`, `trim`, `round`, `int`, `float`, `abs`, `join`, `length`, `count`, `first`, `last`)
mit festen Argumenten. Kein Aufruf einer Funktion oder Methode, keine Zuweisung, kein Makro, Block,
Filterblock, `autoescape`, keine rekursive Schleife, kein Multiplizieren, Teilen oder Modulo (außer
der Spaltenbreite, die docxtpl selbst erzeugt), kein Ausschnitt und kein Name mit Unterstrich am
Anfang; die Umgebung hat keine globalen Funktionen, `a.b` und `a['b']` lesen nur die Daten oder die
Zähler der Schleife, alles andere ist undefiniert. Skript und Upload-Prüfung laden dieselbe
Fallliste in ihren Tests. So entsteht in einer Vorlage kein Wert, den Jinja als Markup ungeschützt
einsetzt. Das Skript prüft das am Syntaxbaum vor dem Füllen, das Backend dieselben Regeln schon beim
Hochladen. Zusätzlich prüft das Backend jedes DOCX aus der Sandbox wie eine hochgeladene Vorlage auf
aktive Inhalte, bevor es Anhang wird, auch eines aus `run_code`: Makros, auch als Ein- oder
Austrittsmakro eines Formularfelds, eingebettete Objekte, externe Verknüpfungen,
Seriendruck-Einstellungen mit Datenquelle und jedes Feld, das nicht auf einer Liste harmloser Felder
steht (Seitenzahl, Datum, Verweis, Verzeichnis u. a.) oder ein anderes Feld in seinem Code trägt.
Fehlt ein Wert oder ist er `null`, setzt das Backend eine Liste leer, eine Bedingung auf nein und
einen leeren Listeneintrag als leeren Text; ein Text bleibt leer, und ein `default` der Vorlage
greift. Die Tests des Skripts laufen beim Bau des Images; scheitert einer, entsteht kein Image. Im
Backend rendert nichts eine Vorlage: Jinja dort hieße Template-Injection im Prozess mit allen Daten.
Ein Stempel- oder Entwurfstext für Fußzeile und Dokumenteigenschaften kommt als Parameter vom
Backend, nie vom Modell. Scheitert das Skript oder ist die Sandbox nicht erreichbar, bekommt das
Modell eine Meldung und antwortet im Chat; der Lauf endet nicht mit einem Fehler.

## Konsequenzen

- **Einfacher:** Ein weiteres isoliertes Werkzeug nutzt dieselbe Sandbox des Laufs, wie
  `fill_template` neben `run_code`. Der Baustein lässt sich tauschen, ohne Werkzeuge zu ändern.
- **Schwieriger:** Der Betrieb installiert und aktualisiert einen weiteren Dienst mit eigenem Chart.
  Das Sandbox-Image ist ein weiteres Image mit Paketliste; LibreOffice macht es deutlich größer, und
  dessen Debian-Pakete folgen dem Stand des Basis-Images beim Bau statt einer festen Version.
- **Restrisiko:** Ein Ausbruch aus der Laufzeit trifft den Knoten; die Stufe hängt an der
  Plattform (Entscheidung 5). Im Compose-Betrieb hält der Dienst den Docker-Socket des Hosts.
- **Restrisiko der geteilten Sandbox:** Hat `run_code` im selben Lauf vorher Code ausgeführt, kann
  ein zurückgelassener Prozess die Ergebnisdatei von `fill_template` zwischen Schreiben und Lesen
  ändern. Ein eigenes Verzeichnis mit Zufallsnamen je Aufruf, das sofortige Lesen und die Prüfung
  auf aktive Inhalte begrenzen das auf Inhalte, die auch `run_code` selbst hätte anhängen können.

## Verworfene Alternativen

- Eigenbau einer Sandbox: Pflege der Isolation läge allein bei Opaa (1).
- Sprach-Sandbox im Backend-Prozess (Pyodide, Interpreter-Einschränkungen): Ausbrüche dort treffen
  den Server mit allen Daten (1).
- E2B, Daytona, LibreChat code-interpreter: brauchen KVM oder privilegierte Container oder sind
  nicht mehr offen gepflegt (1).
- Eine Sandbox je Aufruf: Korrekturen eines Laufs müssten jedes Mal alles neu erzeugen (2).
- Sandbox über Läufe hinweg je Chat: Zustand über eine Anfrage hinaus, der beim Pod-Tausch verloren
  ginge (2).
- Jede erzeugte Datei sofort als Anhang: Zwischenstände einer Korrektur würden doppelt angehängt
  (3).
- Netzregel nur, wenn `networkPolicy.*` eingeschaltet ist: Eine Sandbox hätte bis dahin das offene
  Clusternetz (4).
- Vorlage im Backend füllen (Jinja-Engine im Prozess): Template-Injection mit Zugriff auf alle
  Daten (8).
- Vorlage über `run_code` füllen lassen: Das Ergebnis hinge an ungeprüftem Modellcode (8).
- Fehlende Textwerte als leere Zeichenkette setzen: `default` der Vorlage griffe dann nie (8).
- Gefährliche Filter und Blöcke einzeln sperren: Jinja liefert Markup auf vielen Wegen (Escape-
  Filter mit Methoden von Markup, `set`-Blöcke, Makros); nur eine Zulassungsliste schließt sie
  alle (8).

## Offen

- Erprobung auf Kubernetes mit gVisor und auf OpenShift mit User Namespaces, mit agent-sandbox als
  Unterbau.
- Anzeige der tatsächlich laufenden Isolationsstufe in der Systemverwaltung.
- Ausgangstor mit Zulassungsliste für Werkzeuge mit Netzbedarf.
