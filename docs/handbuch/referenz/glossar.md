# Glossar

Begriffe, die in allen Kapiteln in genau dieser Bedeutung verwendet werden.

| Begriff | Bedeutung |
|---|---|
| **Wissensbibliothek** (Bibliothek) | Verwaltungseinheit für Dokumente: gehört zu einer Organisation, trägt Berechtigungen und genau eine Quelle |
| **Prompt-Bibliothek** | Verwaltungseinheit für Prompts: gehört zu einer Organisation, trägt Berechtigungen wie eine Wissensbibliothek, bindet aber kein Wissen und ändert keinen Suchbereich; einem Space zugeordnet, bietet sie dessen Chat ihre Prompts an |
| **Arbeitsmittel** | Was angelegt, geteilt und einem Space zugeordnet wird, mit eigenen Rollen und einem Eigentümer: Wissens- und Prompt-Bibliotheken, Agenten und Vorlagen |
| **Agent** | Ein Arbeitsmittel mit Auftrag, Wissen und Werkzeugen, das eine Frage in mehreren Schritten beantwortet; im Chat mit `@` gewählt, liest nie mehr als die aufrufende Person, und jede Änderung an Auftrag, Wissen, Werkzeugen oder Grenzen ergibt eine neue Fassung ([Agent anlegen](../benutzer/agent-anlegen.md)) |
| **Agentenlauf** (Lauf) | Die Arbeit eines Agenten an genau einer Frage, mit Laufkennung, eigenen Grenzen und den Schritten unter der Antwort; eine Rückfrage beendet ihn, die Antwort der Person beginnt einen neuen ([Agenten verwenden](../benutzer/agenten-verwenden.md)) |
| **Vorlage** | Ein Arbeitsmittel mit einer Word-Datei voller Platzhalter, die ein Agent mit Werten füllt; Ergebnis ist eine Word-Datei und ein PDF an der Antwort ([Vorlage anlegen](../benutzer/vorlage-anlegen.md)) |
| **Entwurf** | Kennzeichnung jeder Agentenantwort und jeder Datei daraus: eine Vorlage für die eigene Bearbeitung, kein geprüftes Arbeitsergebnis; mit Fassungsstempel aus Agent, Bibliotheken, Vorlagen und Modell ([Agentenergebnis prüfen](../benutzer/agentenergebnis-pruefen.md)) |
| **Werkzeugfähigkeit** | Die Angabe am Chat-Modell, ob es Werkzeuge zuverlässig aufruft: „Ungeprüft“, „Fähig“ oder „Nicht fähig“, mit Grundlage gemessen oder manuell gesetzt; gilt der Kombination aus Modell und Laufzeit ([Modelle verwalten](../administration/modelle.md#werkzeugfähigkeit-festhalten)) |
| **Katalog** | Der eine Einstieg der Hauptnavigation für Arbeitsmittel jeder Art, nur als Kacheln: genau die, die die Person lesen darf; was sie nicht lesen darf, erscheint dort nicht. Über „Neuer Katalogeintrag" beginnt dort das Anlegen mit der Wahl der Art |
| **Prompt** | Eine benannte, wiederverwendbare Anweisung in genau einer Prompt-Bibliothek, mit Titel, Befehl (`/name`), Text und Variablen |
| **Variable** | Eine Stelle im Text eines Prompts, geschrieben als `{{name}}`, die beim Einsetzen gefüllt wird; `{{CURRENT_DATE}}` und `{{USER_NAME}}` füllt OPAA selbst |
| **Quelle** | Woher eine Bibliothek ihre Dokumente bezieht: Upload oder eine angebundene Quelle mit Konnektor, etwa Confluence oder ein Webverzeichnis ([Konnektoren](../benutzer/konnektoren/README.md)) |
| **Konnektor** | Der Teil der Indexierung, der die Eigenheiten eines Quellentyps kennt: Aufzählen, Abrufen, Betriebsarten |
| **Zugang** | Eine von der Systemverwaltung angelegte, benannte Anbindung an ein Quellsystem (etwa „Microsoft 365 Rheinfurt“) mit Adresse und Vorgaben, oft auch App-Registrierung; nutzen darf ihn, wem er freigegeben ist. Wer eine Bibliothek anlegt, wählt ihn nur noch aus ([Zugänge](../administration/zugaenge.md)) |
| **Indexierungslauf** (Lauf) | Ein Durchgang über die Quelle einer Bibliothek mit Status, Zählern und Protokoll |
| **Betriebsart** | Ob ein Lauf die Quelle vollständig auflistet (und Verschwundenes entfernen darf) oder nur ergänzt |
| **Dokument** | Eine Datei, Seite oder ein Eintrag der Quelle, so wie OPAA ihn kennt; identifiziert über Bibliothek und Ort in der Quelle. Anhänge sind eigene Dokumente mit Verweis auf das Elterndokument |
| **Chunk** | Ein Textstück eines Dokuments mit Vektor, Volltext und Metadaten; die Einheit, auf der die Suche arbeitet |
| **Originalablage** | Der Ort, an dem die Originale hochgeladener Dokumente liegen: ein Verzeichnis (auch ein dorthin eingehängtes Netzlaufwerk) oder ein Objektspeicher; Dokumente aus einem Konnektor bleiben bei ihrer Quelle |
| **Format-Pipeline** | Die Verarbeitung eines Dateiformats: Parsen und Zuschnitt in Chunks, mit Kennung und Versionsnummer |
| **Nachzug** | Neuverarbeitung von Dokumenten, deren Chunks mit einer älteren Pipeline-Version oder Volltextfassung entstanden sind; von einem Systemadministrator angestoßen |
| **Kernfelder** | Titel, Dokumentart und Datum/Stand je Dokument |
| **Kontextpräfix** | Text, der jedem Chunk beim Einbetten und im Volltextindex vorangestellt wird (Titel, ausgewählte Felder, Gliederungspfad), ohne den gespeicherten Text zu ändern |
| **Space** | Arbeitsbereich, in dem Chats liegen; enthält genau die ihm zugeordneten Bibliotheken und Prompt-Bibliotheken — ohne Zuordnung sucht sein Chat nichts |
| **Suchbereich** | Die Bibliotheken, in denen eine Frage sucht; steht in der Chip-Leiste des Chats (Standard `@Space-Wissen`) und ist nie weiter als Zuordnung und Leserechte |
| **Fundstelle** (Beleg) | Eine Quellenangabe zu einer Antwort, eine je Dokument; das Belegfenster zeigt Titel und Ortsangabe, die Ausgabe für externe KI-Werkzeuge zusätzlich Rang und Metadaten |
| **Ortsangabe** | Die Stelle eines Chunks im Dokument, etwa „S. 3 · Abschn. Fristen" |
| **Belegstand** | Die Stufe im Fuß einer Antwort, wie weit sie sich auf bestätigte Belege stützt: „Belegt", „Teilweise belegt" oder „Nicht belegt", mit einem kurzen Grund; keine Zahl und kein Urteil über die Richtigkeit ([Suche](wie-die-suche-arbeitet.md)) |
| **Fundstellen-Anker** | Die Stelle eines Chunks maschinenlesbar: Seiten, Abschnittspfad, Folie, Blatt und Zeilen oder Nachricht, dazu je ein kurzes Zitat von Anfang und Ende; Grundlage für den Sprung an die Fundstelle |
| **Textfassung** | Der beim Indexieren gewonnene Text eines Dokuments in Lesereihenfolge; „Dokument öffnen" zeigt ihn für Formate, die der Browser nicht anzeigen kann, an der Fundstelle |
| **Fakten-Manifest** | Die Beschreibung im Code, welche Daten OPAA speichert, protokolliert, wem es sie zeigt, wohin es sie sendet und was voreingestellt ist; Prüfungen im Build halten sie mit dem Code gleich |
| **Faktenblatt** | Ein aus dem Fakten-Manifest erzeugtes Dokument zu einem Thema, etwa Datenflüsse oder Fristen, mit Version und Commit im Kopf ([Nachvollziehbarkeit](../betrieb/nachvollziehbarkeit.md)) |
| **Modellrolle** | Eine der vier Aufgaben, für die ein externes Modell konfiguriert wird: Chat, Embedding, Reranking, Transkription |
| **Rechteprofil** | Eine Gruppe samt der Bibliotheken, die sie lesen darf; der voreingestellte Kontext des Diagnosewerkzeugs |
| **Diagnosesperre** | Grundzustand jeder Bibliothek, der sie aus einer Diagnose im Rechtekontext einer benannten Person heraushält |
| **Live-Transkript** | Zusatz zur Spracheingabe: Ein Live-Dienst erkennt den Text schon beim Sprechen, der Chat zeigt ihn gedimmt im Eingabefeld und übernimmt ihn erst mit dem endgültigen Text. Fällt Live aus, gilt das Diktat mit der ganzen Aufnahme ([Live-Transkript](../betrieb/modell-endpunkte.md#live-transkript)) |
| **Lokales Konto** | Ein Konto, das OPAA selbst führt: Anmeldung mit E-Mail-Adresse und Passwort, verwaltet unter Administration → Benutzer; Gegenstück zu einem Konto aus einem Identitätsanbieter |
| **Notanker-Konto** | Das eine lokale Systemverwalterkonto, das OPAA beim Erststart anlegt; Notfallzugangsmittel, kein Arbeitskonto |
| **Rolle** | Gestufte Berechtigung an genau einem Objekt — an einer Bibliothek Leser, Bearbeiter, Verwalter, Eigentümer; in einem Space Mitglied, Kurator, Administrator. Für eine Person oder eine Gruppe, unbefristet oder mit Ablaufdatum |
| **Anlegerecht** | Ein installationsweites Recht, etwas anzulegen (Spaces, Bibliotheken für Uploads, Konnektorbibliotheken, interne Gruppen, Prompt-Bibliotheken, Agenten, Vorlagen); vergeben an eine Person, eine Gruppe oder „Alle Konten", ohne Gegenstand und ohne Lesewirkung |
| **Vollmacht** | Eine befristete, begründungspflichtige Erlaubnis mit Gegenstand, an genau eine Person gebunden und an keine Gruppe vergebbar: „Sicht als" in der Suchdiagnose und der Geltungsbereich einer anlassbezogenen Klärung im Nachweisprotokoll |
| **Interne Gruppe** | Eine Gruppe, die in OPAA selbst entsteht und von benannten Verantwortlichen gepflegt wird; für andere erst wählbar, nachdem diese sie zur Verwendung freigegeben haben |
| **Geschützte Gruppe** | Eine Gruppe der Personalvertretung, der Schwerbehindertenvertretung, der Gleichstellung oder der Personalvorgänge: über die Suche nicht auffindbar, in fremden Listen ohne Namen, ohne Größenangabe. Über das Kennzeichen entscheidet die Systemverwaltung |
| **Wirksame Gruppe** | Eine Gruppe, der ein Recht erteilt werden darf: nicht aufgelöst, ihr Anbieter eingeschaltet, ihre Mitgliedschaft noch gepflegt. Sie darf leer sein; hat sie mindestens ein **aktives** Konto, ist sie zusätzlich **handlungsfähig** und kann Eigentümerin oder Space-Administratorin sein |
| **Alle Konten** | Empfänger einer Freigabe oder eines Anlegerechts, der jedes Konto der Organisation umfasst; an einem Arbeitsmittel höchstens mit der Rolle Bearbeiter |
| **Handlungsfähig** | Eine wirksame Gruppe mit mindestens einem aktiven Konto; nur sie kann als Eigentümerin oder Space-Administratorin handeln. Gruppen verschachteln nicht |
| **Nachfolge offen** | Der abgeleitete Zustand eines Objekts ohne handlungsfähige Verantwortliche: nutzbar wie bisher, alle Rechte bleiben, allein die Reichweite ist eingefroren |
| **Rechtehistorie** | Die Zeiträume, in denen ein Recht galt — die Grundlage der Stichtagsauskunft; getrennt vom Nachweisprotokoll, das Handlungen festhält |
| **Anlagegrund** | Pflichtangabe zu jedem lokalen Konto: der dienstliche Anlass und der Grund seiner Befristung; die betroffene Person kann ihn lesen |
| **Aktivitätsklasse** | Die einzige Angabe zur Nutzung eines lokalen Kontos in der Kontenliste („nie", „länger nicht genutzt", „aktiv") — kein Zeitstempel, nicht sortierbar |
| **Externe KI-Werkzeuge** | KI-Werkzeuge außerhalb von OPAA, die über einen eigenen, lesenden Zugriffsweg in freigegebenen Bibliotheken suchen; der Zugriffsweg ist installationsweit schaltbar und standardmäßig aus |
| **Zugangstoken** | Ein persönliches Zugangsmerkmal für externe KI-Werkzeuge, mit Name, unveränderlicher Bibliotheksauswahl und Pflicht-Ablaufdatum; Ausschnitt der Rechte seiner Person, nie mehr |
| **Freigabe für externe KI-Werkzeuge** | Das befristete Merkmal einer Wissensbibliothek „Für externe KI-Werkzeuge nutzbar"; wird wie die übrigen Reichweitenfelder historisiert |
| **Effektive Sicht** | Was ein Zugangstoken bei einem Aufruf sieht: die Schnittmenge aus Rechten der Person, gültiger Freigabe, Tokenauswahl und Installationsschalter, je Aufruf neu ausgewertet |
