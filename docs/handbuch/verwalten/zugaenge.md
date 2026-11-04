# Zugänge einrichten

> **Wer:** Systemverwaltung, „Administration → Anbindungen“.

Ein **Zugang** legt einmal fest, wo eine Quelle liegt und wie OPAA sich dort anmeldet. Wer eine
Bibliothek anlegt, wählt ihn nur noch aus.

> **Beispiel.** Das Haus hat zwei Confluence-Instanzen. Die Systemverwaltung legt „Confluence
> Intranet“ und „Confluence Projektwiki“ an, mit Adresse und Edition. Wer eine Bibliothek anlegt,
> wählt „Confluence Intranet“ und trägt nur das eigene Token ein.

Ob eine Quellart einen Zugang braucht, sagt ihre Seite unter [Konnektoren](../pflegen/konnektoren/README.md):
nicht möglich, möglich oder Pflicht. Pflicht ist er, wo eine App-Registrierung beim Anbieter nötig
ist, etwa bei SharePoint, OneDrive und Dropbox. Nur über einen Zugang mit der Besitzart „Person“
können Personen [ein eigenes Konto verbinden](../arbeiten/konto-verbinden.md).

## Vier Anmeldewege

| Weg | Wer hält das Geheimnis | Beispiel |
|---|---|---|
| Eigene Zugangsdaten der Bibliothek | die Bibliothek: Token, Passwort oder Schlüssel eines Dienstkontos; der Zugang liefert nur Adresse und Vorgaben | Confluence, Nextcloud, Dateifreigabe, S3 |
| Zugang meldet sich selbst an | der Zugang: Dienstkonto-Schlüssel oder App-Registrierung; was gelesen wird, legt der Anbieter fest | Google Drive, SharePoint |
| Zustimmung beim Anbieter | die Bibliothek: „Quelle verbinden“ mit einem **Funktionskonto**, nicht dem eigenen; bleibt, wenn die Person geht | Dropbox |
| Verbundenes Konto einer Person | die Person, für ihre [private Bibliothek](../arbeiten/private-bibliothek.md); nur sie liest sie | Nextcloud, OneDrive |

Lehnt der Anbieter einen Schlüssel des Zugangs ab, zeigen alle Bibliotheken darauf „Abgelaufen“.

## Zugang anlegen

1. Reiter „Zugänge“, „Neuer Zugang“, Quellart wählen.
2. Name, Server-Adresse, Anmeldeart und Vorgaben eintragen, bei einer App-Registrierung Client-ID,
   Client-Secret und dessen Ablaufdatum.
3. Bei einer Anmeldung beim Anbieter (OAuth) dort als Rücksprungadresse
   `{öffentliche Adresse}/connections/callback` eintragen; das Formular nennt die genaue Adresse.
4. Besitzart wählen: für geteilte Bibliotheken, für Personen oder beides.
5. „Anmeldung testen“, wo angeboten.
6. Festlegen, wer den Zugang nutzen darf, oder über die [Anlegerechte](anlegerechte.md).

Die Übersicht zeigt je Zugang, ob etwas zu tun ist: „Anmeldung abgelehnt“, ein bald ablaufendes
Client-Secret (14 Tage vorher kommt eine Benachrichtigung), viele abgelaufene verbundene Konten oder
„Noch für niemanden freigegeben“.

## Zugang pflegen

| Anlass | Folge für die Bibliotheken darauf |
|---|---|
| neues Client-Secret oder neuer Schlüssel | keine |
| neue Server-Adresse | folgen ihr; ihre Zugangsdaten sind neu einzutragen |
| App beim Anbieter neu registriert | alle Verbindungen neu herstellen |
| Verdacht auf entwendete Zugangsdaten | **„Alle Verbindungen trennen“**: verwirft alle Zugangsdaten sofort, zieht Zustimmungen beim Anbieter zurück |
| Zugang löschen | Bestand bleibt durchsuchbar, Hinweis „Zugang entfernt“ |

Vor jeder Änderung, die Bibliotheken trifft, zeigt eine **Vorschau**, wie viele es sind.

**Sperren** (Reiter „Quellarten“ oder am Zugang): Neue Bibliotheken und Läufe stoppen, der Bestand
bleibt mit „Gesperrt – Inhalt wird nicht mehr aktualisiert“ durchsuchbar. **„Nur über Zugänge“**
macht einen Zugang für eine Quellart zur Pflicht; bestehende Bibliotheken mit eigener Adresse laufen
weiter oder ruhen, bis sie einem Zugang zugeordnet sind.

## Verbundene Konten von Personen

Die Systemverwaltung sieht **keine Liste der Personen**, nur je Zugang Zahlen, kleine nur als
„weniger als N“. Inhalte privater Bibliotheken öffnet OPAA niemandem außer ihrer Besitzerin, auch
nicht bei einem Vorfall; der wird in der Quelle untersucht. Ruht ein Konto lange, ruhen seine
Verbindungen; wird es gesperrt oder läuft ab, enden sie, und nach einer Löschfrist löscht OPAA die
privaten Bibliotheken. Einen Identitätsanbieter für eine Umstellung **deaktivieren**, nicht löschen:
Löschen beendet die Verbindungen aller seiner Personen.

## Einstellungen der Anbindungen

Der Reiter „Einstellungen“ gilt für die ganze Installation: Ausgangsproxy, erlaubte Linkziele,
Speicher geteilter und privater Bibliotheken (je Person ab Werk 10 GiB; die Verwaltung sieht nur
Summen), Vollabgleich-Rhythmus je Quellart, Schwellen und
Fristen der Verbindungen (Inaktivitätsschwelle, Löschfrist privater Bibliotheken, Warnschwelle) und
die Aufbewahrung des Verbindungsprotokolls. Ohne eigenen Wert gilt die Werkseinstellung; jede
Änderung wirkt ohne Neustart und steht im Revisionsprotokoll.

| Schwelle | Werk | Grenzen | Wirkung |
|---|---|---|---|
| Inaktivitätsschwelle | 90 Tage | 30–365 | so lange ohne Anmeldung: verbundene Konten ruhen, nichts wird gelöscht; nächste Anmeldung weckt sie |
| Löschfrist privater Bibliotheken | 30 Tage | 1–90 | so lange deaktiviert (nicht nur ruhend): private Bibliotheken werden gelöscht |
| Warnschwelle | 10 | 1–1000 | ab so vielen abgelaufenen Konten zeigt ein Zugang „Viele Konten abgelaufen“ |

**Gut zu wissen:**

- Geheimnisse liegen verschlüsselt und erscheinen in keiner Antwort und keinem Protokoll.
- Ein Client-Secret geht nie an eine neue Adresse; bei geändertem Token-Endpunkt neu eintragen.
- Läuft die Verbindung einer Bibliothek nicht, nennt ihr Reiter „Quelle“, wer es beheben kann
  ([Bibliothek aktuell halten](../pflegen/bibliothek-aktuell-halten.md)).
