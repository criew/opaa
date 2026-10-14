# ADR-0046: SMB-Konnektor

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0045](0045-gemeinsamer-datei-abgleich.md)
und [ADR-0038](0038-steckbare-konnektoren.md)

## Kontext

Windows-Dateifreigaben sind in der Verwaltung die häufigste Ablage. Sie sind der Vollscan-Fall des
Ports aus ADR-0045: kein Änderungsprotokoll, kein Ordnermerkmal, das Änderungen tiefer im Baum
weiterträgt. Eine Freigabe kann Verknüpfungen (Reparse-Punkte, Junctions, DFS-Verweise) und
ausgelagerte Dateien enthalten und ist oft größer als das Anfragebudget eines Laufs. Der Zugriff
soll über ein Dienstkonto laufen, dessen Zugangsdaten die Freigabe nie verlassen.

## Entscheidung

### 1. Paket, Bibliothek und Typ

Paket `indexing.source.smb`, Typ `SMB`, nur über `filesync` und den Kern, keine neue Modulkante. Der
Zugriff liegt im Konnektorpaket (`SmbShareClient`), nicht in foundation: Nur der Konnektor braucht
ihn. Bibliothek ist smbj (Apache 2.0, `libs.versions.toml`): SMB 2.0.2 bis 3.1.1 mit Signatur und
Verschlüsselung; SMB 1 spricht OPAA nicht. Ein echter Samba (`dockurr/samba`) läuft im regulären
`test`, `FileStoreContract` läuft gegen ihn.

### 2. Ziel und Ursprung

`sourceUrl` ist `smb://server[:port]/freigabe`; ein UNC-Pfad wird in diese Form gebracht und
normalisiert gespeichert (Server klein, Port 445 weggelassen, Freigabe prozentkodiert), damit die
Ursprungsbindung des Kerns greift. Jeder Socket entsteht in einer eigenen `SocketFactory`, die zuerst
`TargetAddressValidator#validateHost` prüft; SMB geht nie über den Ausgangsproxy (ADR-0050).
DFS-Verweisen folgt der Client nicht (`withDfsEnabled(false)`), Zugangsdaten verlassen den Server
nie. Gespeicherte Zugangsdaten gelten nur für dieselbe Freigabe (`SourceConnector#credentialBinding`,
durchgesetzt im Kern); versteckte Freigaben (`*$`, auch `C$`, `ADMIN$`, `IPC$`) werden abgewiesen.

### 3. Anmeldung

NTLM mit Dienstkonto aus `source_credentials` (`DOMÄNE\Benutzer:Passwort`, `SmbCredentials`).
Signatur ist Pflicht, Verschlüsselung wird genutzt, wo der Server SMB 3 anbietet. Eine Sitzung als
Gast oder anonym gilt als abgelehnte Anmeldung; jede Ablehnung des Sitzungsaufbaus, auch „Zugriff
verweigert“, ist ein Anmeldebefund. Kerberos fehlt: smbj bräuchte ein JAAS-Subject mit `krb5.conf`,
KDC und Keytab, also Betriebsinfrastruktur je Installation. Domänen ohne NTLM lassen sich nicht
anbinden; der Verbindungstest nennt das (`STATUS_NTLM_BLOCKED`).

### 4. Identität und Merkmal

`file_path` ist `smb://server/freigabe/pfad` (unkodiert); Umbenennen oder Verschieben ist Löschen
und Neuanlegen. Das Merkmal ist `m:<Änderungszeit in Windows-Ticks>|<Größe>`. Kein Deep Link; das
Original liest `OriginalAccess` von der Freigabe. Grenze: Ändert sich der Inhalt bei gleicher
Änderungszeit und Größe, bemerkt der Lauf das nicht. Ein Pfad über `FilePathLimit` wird vor dem
Download abgelehnt (ADR-0045, Entscheidung 10).

### 5. Kein Ordnergedächtnis, Runde mit Marke, `LOCATION_IDENTITY`

Die Änderungszeit eines Ordners ändert sich unter NTFS und Samba nur bei direkten Einträgen; sie
erfüllt den Vertrag von `FileStore#recall` nicht, jeder Lauf listet alles. Der Store geht in
sortierter Tiefensuche; ein Ordner wird beim Betreten ganz gelesen und sortiert, eine Seite endet nach
`list-page-size` Einträgen oder geöffneten Ordnern. Die Marke ist die Stelle im Baum (Namen und
Datei-IDs der offenen Ordner, letzter erledigter Eintrag), die Datei-IDs aller betretenen Ordner
(`VisitedFolders`, vorzeichenlos sortiert, Abstände in Basis 36) und die Befunde (nicht lesbar, zu
tief). `absenceProof()` ist `LOCATION_IDENTITY`: Ein Ort, an dem in keinem Lauf der Runde eine Datei
war, ist weg. In Namensreihenfolge gewinnt bei doppelt erreichbaren Ordnern der zuerst betretene Name;
den bisherigen Namen zu bevorzugen machte die Wahl vom Bestand abhängig.

### 6. Verknüpfungen werden nicht verfolgt

Ein Eintrag mit Reparse-Punkt, dessen Tag ein Namensersatz ist (symbolischer Link, Junction,
Mount-Point) oder ein DFS-Verweis zählt als „kein Dokument“; andere Reparse-Punkte (Deduplizierung)
werden gelesen. Jeder Pfad wird mit `FILE_OPEN_REPARSE_POINT` geöffnet; ist das Geöffnete eine
Verknüpfung (`FSCTL_GET_REPARSE_POINT`), wird es abgewiesen, ein anderer Reparse-Punkt ohne die Option
neu geöffnet. Ein Samba-Server löst Unix-Links selbst auf; ein Ordner, dessen Datei-ID die Runde
schon betreten hat, gilt deshalb als Verknüpfung — das beendet Schleifen über Läufe hinweg. Der
Abstieg endet nach `SmbFileStore.MAX_DEPTH` Ebenen. Samba öffnet einen Unix-Link mit dieser Option gar
nicht; ein gelisteter Eintrag, der sich so nicht öffnen lässt, gilt als Verknüpfung bzw. als
verschwunden. Eine Verknüpfung weiter vorn im Pfad löst smbj selbst auf (smbj-interner
`SymlinkPathResolver`); eine Schleife endet so mit einem Befund. Ein Name mit `/`, `\`,
`:` oder Steuerzeichen zählt als „kein Dokument“. Teilen sich zwei Ordner eine Datei-ID (Samba mit
mehreren Dateisystemen unter einer Freigabe), gilt der zweite als Verknüpfung und sein Bestand wird
entfernt.

### 7. Unlesbare Unterordner, ausgelagerte Dateien, Budget

Ein Ordner, den das Dienstkonto nicht öffnen darf, wird übersprungen und genannt; der Bereich gilt
als unvollständig, nichts wird entfernt; ist der konfigurierte Ordner selbst unlesbar, gilt das
sofort. Mit Access-Based Enumeration liefert der Server einen solchen
Ordner gar nicht: Für OPAA ist er verschwunden, seine Dokumente werden entfernt — Betreiber geben dem
Dienstkonto deshalb Leserecht auf alles im Bereich (Handbuch). Ausgelagerte Dateien (Offline) werden
nicht abgerufen und gelten als vorhanden. Jede SMB-Nachricht kostet eine Anfrage des Budgets; der
Standard (`SmbProperties`, `opaa.indexing.smb.*`) liegt deshalb höher als bei HTTP-Ablagen.

## Konsequenzen

**Einfacher:** Freigaben jeder Größe werden über Läufe hinweg fertig gelistet und gleichen ab.

**Schwieriger:** Keine Kerberos-Anmeldung; jeder Lauf listet alles; Löschungen zeigt erst das Ende
einer Runde.

**Restrisiken:** Wird eine Datei während einer Runde an einen Ort vor der Marke verschoben oder am
selben Ort gelöscht und neu angelegt, fehlt sie bis zur nächsten Runde. Die Marke wächst mit den
betretenen Ordnern (ADR-0045, Offen).

## Verworfene Alternativen

- Zugriff in foundation — nur der Konnektor braucht ihn.
- Kerberos jetzt — Betriebsinfrastruktur je Installation, eigenes Issue.
- Verknüpfungen folgen — Schleifen, Dubletten und Ziele außerhalb der Freigabe.
- `SINGLE_RUN` — große Freigaben entfernten nie etwas.

## Offen

- Kerberos-Anmeldung für Domänen ohne NTLM.
