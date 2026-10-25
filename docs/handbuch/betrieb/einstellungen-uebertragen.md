# Einstellungen übertragen

> **Entwurf** — gegen den Code geprüft, vom Maintainer noch nicht abgenommen.

Die Einstellungen der Verwaltung liegen in der Datenbank, nicht in der Konfiguration. Wer eine
Testinstallation mit der Produktion vergleichen oder eine neue Installation wie eine bestehende
einrichten will, exportiert sie deshalb als Datei und importiert sie dort. Beispiel: Die
Aufbewahrungsfristen, Linkziele und Mail-Vorlagen der Testinstallation sind abgestimmt; ein Export
dort und ein Import in der Produktion übernimmt sie in einem Schritt, und das Revisionsprotokoll der
Produktion zeigt jede übernommene Änderung.

Beides steht unter **Administration → Einstellungen übertragen** und nur der Systemverwaltung zur
Verfügung.

## Was die Datei enthält

Die Datei ist JSON, mit Formatname, Formatversion, Zeitpunkt des Exports und je Bereich dessen
wirksame Werte. Ein leerer Wert (`null`) hat je Angabe seine eigene Bedeutung: Beim Branding gilt
der OPAA-Standard, beim Speicherkontingent, beim Vollabgleich-Rhythmus, bei den Fristen der Chats
und der anlassbezogenen Klärungen und bei den Schwellen und Fristen der Verbindungen die
Werkseinstellung, bei einer E-Mail-Vorlage der
ausgelieferte Text, bei einer Angabe des Mailservers ist sie nicht gesetzt.

| Bereich | Inhalt |
|---|---|
| Aufbewahrungsfristen | Protokoll, Rechtehistorie, Diagnoseprotokoll, Verbindungsprotokoll |
| Fristen der Chats | Archivieren und Löschen der Chat-Bereinigung in Spaces, Schalter und Frist der Löschung nach einer Sperre, je `null` für die Werkseinstellung |
| Anlassbezogene Klärungen | Löschfrist in Kalenderjahren oder `null` für die Werkseinstellung |
| Branding | Produktname, Claim, Hinweis zur KI-Nutzung, Akzentfarbe, Farbschema-Vorgabe |
| E-Mail-Versand | Schalter, Mailserver, Port, Zugangsname, Verschlüsselung, Absender |
| E-Mail-Vorlagen | je Vorlage die eigene Fassung mit Betreff und Text oder `null` für den ausgelieferten Text |
| Lokale Benutzerverwaltung | Selbstregistrierung, Passwortregeln, Fristen der Links und Konten |
| Selbstauskunft | Schalter und Tagesgrenze |
| Erlaubte Linkziele | die ganze Liste |
| Ausgangsproxy | Proxy-Adresse und Ausnahmeliste |
| Speicherkontingent privater Bibliotheken | der gesetzte Wert oder `null` für die Werkseinstellung |
| Vollabgleich-Rhythmus | je Quellart die gesetzten Tage oder `null` für die Werkseinstellung |
| Schwellen und Fristen der Verbindungen | Inaktivitätsschwelle, Löschfrist privater Bibliotheken und Warnschwelle abgelaufener Konten, je der gesetzte Wert oder `null` für die Werkseinstellung |
| Externe KI-Werkzeuge | Schalter, Ablauf-Obergrenze, Kontingent, Netzbereiche, Alarmschwelle, Einleitungstext |

**Nicht in der Datei** stehen Passwörter und Schlüssel, auch nicht, ob einer hinterlegt ist, und
nicht, wer einen Wert wann geändert hat. Ebenfalls nicht dabei sind Modelle, Identitätsanbieter,
Verzeichniszugänge, Zugänge und die Freigabe der Konnektoren: Sie verbinden die Installation mit
ihren eigenen Diensten und brauchen deren Geheimnisse. Der Schalter der lokalen Konten gehört zum
Anmeldeverfahren der Installation und bleibt ebenfalls draußen, ebenso die drei Bilder des
Brandings.

## Importieren

Nach der Auswahl der Datei prüft OPAA sie gegen diese Installation, ohne etwas zu ändern, und zeigt
jede Angabe, die sich ändern würde, mit bisherigem und neuem Wert. Änderungen, die erweitern oder
umlenken, was die Installation erreicht oder zulässt, oder Nachweise früher löschen, sind als
„Sicherheitsrelevant“ markiert: Mailserver und Absender, E-Mail-Vorlagen (ihre Links erreichen
Eingeladene), Ausgangsproxy, Selbstregistrierung, Passwortlänge und Gültigkeit der Links, externe
KI-Werkzeuge mit Netzbereichen, Ablauf und Kontingent, erlaubte Linkziele, jede
Aufbewahrungs- und Löschfrist, auch die der Chats, der anlassbezogenen Klärungen und der privaten
Bibliotheken, die Inaktivitätsschwelle der Verbindungen (sie hält Zugangsdaten abwesender Personen
nutzbar) und der Vollabgleich-Rhythmus (er bestimmt, wie lange in der Quelle Gelöschtes noch
auffindbar bleibt). Erst **Übernehmen** ändert die Installation, nach einer Rückfrage.

- **Geprüft wird wie in der Verwaltung.** Jeder Wert geht durch denselben Weg wie eine Änderung im
  Formular, mit denselben Grenzen — Pflichtangaben, Längen, Anzahl der Einträge —, auch denen des
  Betriebs: Ohne `OPAA_PUBLIC_BASE_URL` lässt sich etwa die Selbstregistrierung auch per Import nicht
  einschalten. Die Datei wird streng gelesen; eine doppelte oder unbekannte Angabe wird abgelehnt.
- **Das Passwort des Mailservers folgt keinem neuen Ziel.** Ändern sich Server, Port, Zugangsname
  oder Verschlüsselung, während ein Passwort hinterlegt ist, lehnt OPAA die Änderung ab, beim Import
  wie im Formular: Das Passwort ist dann im Formular neu einzugeben oder zu leeren.
- **Alles oder nichts.** Besteht ein Wert die Prüfung nicht, ändert der Import nichts, auch nicht an
  den Bereichen davor. Die Meldung nennt Bereich und Angabe.
- **Was die Datei nicht enthält, bleibt.** Ein fehlender Bereich bleibt, wie er ist, eine fehlende
  Angabe eines Bereichs behält ihren Wert. Passwörter und Schlüssel dieser Installation bleiben
  erhalten.
- **Ein zweiter Import derselben Datei ändert nichts** und erscheint nicht im Protokoll.
- **Eine Datei einer anderen Formatversion** oder mit einem Bereich, den diese Installation nicht
  kennt, wird abgelehnt.

## Nachweis im Revisionsprotokoll

Jede Änderung einer Einstellung steht im Revisionsprotokoll, ob über das Formular, die
Schnittstelle oder einen Import, mit vorherigem und neuem Wert. Ein Geheimnis erscheint dort nur als
„gesetzt“, „geändert“ oder „entfernt“, nie mit seinem Wert; das gilt auch für die Geheimnisse von
Modellen, Verzeichniszugängen und Zugängen. Freitexte — Betreff und Text der E-Mail-Vorlagen, der
Einleitungstext für externe KI-Werkzeuge — stehen dort nur als Prüfsumme (SHA-256) und Länge vorher
und nachher: Ob und wann sich ein Text geändert hat, ist belegt, sein Wortlaut nicht aufbewahrt. Eine
Änderung auf denselben Wert schreibt keinen Eintrag. Ein Import, der etwas ändert, schreibt zusätzlich einen
eigenen Eintrag mit den geänderten Bereichen; er und alle Änderungen des Imports tragen dieselbe
Vorgangskennung, sodass die Revision sie über den Abfrageweg „nach Vorgang“ zusammen findet.

## Grenzen

| Grenze | Wert |
|---|---|
| Größe einer Datei | 256 KiB |
| Prüfungen und Importe zusammen | 30 je Stunde, für die ganze Installation |

Die Grenzen sind fest und nicht konfigurierbar.

## Über die API

```bash
# Export, mit dem Zugangstoken einer Systemverwalterin
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/system/settings/export \
  > opaa-einstellungen.json

# Prüfen, ohne etwas zu ändern
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data @opaa-einstellungen.json http://localhost:8081/api/v1/system/settings/import/preview

# Übernehmen
curl -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data @opaa-einstellungen.json http://localhost:8081/api/v1/system/settings/import
```

## Was nicht gebaut ist

- Übertragen von Modellen, Identitätsanbietern, Verzeichniszugängen, Zugängen, Konnektorfreigaben
  und Bildern des Brandings (#3201).

## Weiterführende Kapitel

- [Branding](branding.md)
- [E-Mail-Versand](e-mail.md)
- [Konfiguration](konfiguration.md)
