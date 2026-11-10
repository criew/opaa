# ADR-0073: Isolierte Codeausführung für Agenten (Sandbox)

## Status

Akzeptiert · zuletzt geändert 2026-11-10

## Kontext

Ein Agent soll Dateien erzeugen: ein PDF nach Vorgabe, eine Tabellenauswertung, ein Diagramm. Dafür
schreibt das Modell Code, den niemand vorher prüft ([ADR-0070](0070-werkzeugaufrufe-im-chat.md),
Werkzeug `run_code` der Rechteklasse *isoliert*). Dieser Code darf weder auf den Knoten ausbrechen
noch Opaa-Daten außer den übergebenen sehen noch ungeprüft ins Netz. Wettbewerber zeigen, wo es
schiefgeht: Sprach-Sandboxen im Serverprozess (vm2, Flowise, n8n), Sandbox-APIs ohne Anmeldung (Dify,
OpenHands), Dateinamen aus der Sandbox als Pfad auf dem Server (LibreChat), ein Egress-Proxy, der bei
Fehlern öffnet (E2B). Die Recherche und der Spike (#3584) haben OpenSandbox im Docker-Modus
durchgespielt. Behörden betreiben Kubernetes, OpenShift oder Docker; gVisor ist auf OpenShift nicht
unterstützt, Kata braucht dort Bare Metal. Die Plattform entscheidet also über die
Isolationsstärke, nicht der Code.

## Entscheidung

### 1. Fertiger Baustein hinter eigener Schnittstelle

Opaa betreibt keine eigene Sandbox. Der Sandbox-Dienst ist **OpenSandbox** (Apache 2.0) mit
REST-API, Pflicht-Schlüssel und Docker- wie Kubernetes-Modus; auf Kubernetes und OpenShift kann er
agent-sandbox (Kubernetes SIG Apps) als Unterbau nutzen. Das Backend spricht nur mit der eigenen
Schnittstelle `io.opaa.query.sandbox.Sandboxes`; der Baustein dahinter ist austauschbar, ohne ein
Werkzeug anzufassen. Begründung: Ein gepflegter Baustein mit vielen Beitragenden zieht Sicherheits-
und Plattformkorrekturen nach, die ein Eigenbau selbst leisten müsste.

### 2. Eine Sandbox je Agentenlauf, nie geteilt

Der erste Aufruf von `run_code` in einem Lauf öffnet eine Sandbox, weitere Aufrufe desselben Laufs
arbeiten im selben Ordner weiter. Das Lauf-Ende schließt sie in jedem Ausgang. Ihre Lebensdauer beim
Dienst ist die Restzeit des Laufs plus Antwortaufrufe, gedeckelt durch `opaa.sandbox.max-lifetime`;
fällt eine Instanz aus, löscht der Dienst sie selbst. Eine Sandbox gehört genau einem Lauf einer
Person. Begründung: Ein Lauf lebt eine Anfrage lang (ADR-0070, Entscheidung 1); eine Korrekturrunde
der Person ist ein neuer Lauf und baut auf den Anhängen der vorigen Antwort auf, nicht auf einem
geparkten Container.

### 3. Ergebnisse nur als Anhang der Antwort

Am Ende des Laufs, bevor die Antwort gespeichert wird, liest das Backend die Dateien direkt in
`/workspace/out` (höchstens fünf in einem Anhangsformat, je höchstens 10 MB, in einer festen Frist)
und nimmt jede durch alle Prüfungen eines gewöhnlichen Uploads als Anhang der fragenden Person in
deren Chat. Bilder sind kein Anhangsformat; ein Diagramm kommt als PDF oder eingebettet in ein
Dokument. Der Endstand zählt, nicht jeder Zwischenstand. Alles aus der Sandbox gilt als feindlich:
Name, Pfad, Größe und Dateityp werden geprüft, Unterordner und Verweise nicht gelesen, Ausgaben und
Dateinamen erreichen das Modell nur als markierter Datenblock. Eine Datei, die sich nicht abholen
oder anhängen lässt, fehlt, ohne die Antwort zu verwerfen; was dem Modell als angehängt gemeldet
wird, folgt denselben Regeln wie das Abholen.

### 4. Kein Netz, Anmeldung je Aufruf

Die Sandbox hat keinen Ausgang: Unter Docker hängt sie an einem internen Netz ohne Ausgang, das
Backend ist nicht darin, erreichbar sind dort nur der Dienst und andere Sandboxen, beide nur mit
Schlüssel oder Token; unter Kubernetes legt das Helm-Chart im Namespace der Sandboxen eine NetworkPolicy
an, die jeden Ausgang sperrt und Eingang nur vom Dienst zulässt, unabhängig von den übrigen
Netzwerkregeln; dieser Namespace darf nicht der von Opaa sein. Der Dienst verlangt seinen Schlüssel, jede Sandbox ein eigenes Zufallstoken für
ihren Ausführungsagenten. Ein Ausgangstor mit Zulassungsliste kommt erst mit einem Werkzeug, das
Netz braucht.

### 5. Isolationsstärke ist Einstellung des Dienstes

Wie stark eine Sandbox vom Knoten getrennt ist, setzt der Betrieb über die Laufzeitklasse des
Dienstes: gVisor auf Kubernetes (auch auf VMware), Standardlaufzeit mit User Namespaces und eigenen
Worker-VMs auf OpenShift auf VMware, Kata auf OpenShift mit Bare Metal. Opaa ändert dafür keinen
Code. Im Compose-Betrieb braucht der Dienst den Docker-Socket; dieser Stapel gehört auf eine
eigene VM.

### 6. Abschaltbar, ohne Dienst nicht angeboten

`opaa.sandbox.enabled` ist aus. Eingeschaltet ohne Adresse oder Schlüssel startet das Backend nicht.
Ohne Schalter gibt es weder Client noch Werkzeug als Bean; die Werkzeug-Registry kennt `run_code`
dann nicht, und kein Agent kann es aufrufen. Eingeschaltet entscheiden der Werkzeugschalter und die
Zuordnung am Agenten (ADR-0070, Entscheidung 6).

### 7. Sandboxprotokoll als eigener Bestand neben dem Schrittprotokoll

Je Sandbox hält eine eigene Tabelle Lauf-Kennung, technische Kennung der Sandbox, Werkzeug,
Ergebnis, Dauer, Zahl der Skripte, Zahl und Größe der Dateien und den Abbruchgrund fest; kein Konto,
kein Skript, kein Dateiinhalt. Sie folgt der Bauart des Schrittprotokolls (ADR-0070, Entscheidung 4,
Bestand b): Tagespartitionen im Besitz von `opaa_audit_owner`, derselbe Leseweg mit Lauf-Kennung und
Anlass, eigene Frist (Vorgabe 7, höchstens 30 Tage, nie länger als das Schrittprotokoll). Geschrieben
wird beim Schließen der Sandbox, auch wenn sie nicht startet. Der Dienst selbst protokolliert auf
Stufe `INFO` oder höher, also ohne Anfragekörper, und Compose rotiert sein Log; ein Test prüft die
mitgelieferte Konfiguration.

## Konsequenzen

- **Einfacher:** Ein weiteres isoliertes Werkzeug, etwa das Füllen einer Dokumentvorlage, nutzt
  dieselbe Sandbox des Laufs. Der Baustein lässt sich tauschen, ohne Werkzeuge zu ändern.
- **Schwieriger:** Der Betrieb installiert und aktualisiert einen weiteren Dienst mit eigenem Chart.
  Das Sandbox-Image ist ein weiteres Image mit Paketliste.
- **Restrisiko:** Ein Ausbruch aus der Laufzeit trifft den Knoten; die Stufe hängt an der
  Plattform (Entscheidung 5). Im Compose-Betrieb hält der Dienst den Docker-Socket des Hosts.

## Verworfene Alternativen

- Eigenbau einer Sandbox: Pflege der Isolation läge allein bei Opaa (1).
- Sprach-Sandbox im Backend-Prozess (Pyodide, Interpreter-Einschränkungen): Ausbrüche dort treffen
  den Server mit allen Daten (1).
- E2B, Daytona, LibreChat code-interpreter: brauchen KVM oder privilegierte Container oder sind
  nicht mehr offen gepflegt (1).
- Eine Sandbox je Aufruf: Korrekturen eines Laufs müssten jedes Mal alles neu erzeugen (2).
- Sandbox über Läufe hinweg je Chat: Zustand über eine Anfrage hinaus, der beim Pod-Tausch verloren
  ginge (2).
- Jede erzeugte Datei sofort als Anhang: Zwischenstände einer Korrektur würden doppelt angehängt (3).
- Netzregel nur, wenn `networkPolicy.*` eingeschaltet ist: Eine Sandbox hätte bis dahin das offene
  Clusternetz (4).

## Offen

- Erprobung auf Kubernetes mit gVisor und auf OpenShift mit User Namespaces, mit agent-sandbox als
  Unterbau.
- Anzeige der tatsächlich laufenden Isolationsstufe in der Systemverwaltung.
- Ausgangstor mit Zulassungsliste für Werkzeuge mit Netzbedarf.
