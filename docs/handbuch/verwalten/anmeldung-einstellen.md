# Anmeldung einstellen

> **Wer:** Systemverwaltung, „Administration → Benutzer“, Reiter „Einstellungen“.

Die Karte legt fest, was für alle lokalen Konten gilt. Jede Änderung wirkt ab der nächsten Anwendung,
nicht rückwirkend auf bestehende Passwörter, und steht im Nachweisprotokoll. Identitätsanbieter und
Verzeichnisabgleich richtet die Systemverwaltung unter „Administration → Identitätsanbieter“ und
„Administration → Verzeichnisabgleich“ ein ([Authentifizierung](../betreiben/authentifizierung.md#anbieterverwaltung)).

| Einstellung | Grenzen |
|---|---|
| Adress-Domänen der Selbstregistrierung | leer heißt: niemand kann sich registrieren |
| Mindestlänge des Passworts | 8 bis 64 Zeichen |
| Vorbelegtes Ablaufdatum (Tage) | mindestens 1 |
| Sperre nach Inaktivität (Tage) | mindestens 30 |
| Einladungslink gültig (Stunden) | 1 bis 720 |
| Rücksetzlink gültig (Minuten) | 1 bis 1440 |
| Fehlversuche bis zur Sperre | 1 bis zur Grenze des Betriebs; Werkseinstellung 5 |
| Dauer der Sperre (Minuten) | Grenze des Betriebs bis 1440; Werkseinstellung 15 |

**Fehlversuch-Sperre:** Ab Werk lässt sie sich nur verschärfen; lockern geht erst, wenn der Betrieb
die Grenzen weiter setzt. Sehr strenge Werte treffen auch das Notanker-Konto: Wer seine Adresse
kennt, sperrt es mit falschen Passwörtern. Dagegen hilft die Begrenzung lokaler Systemverwalter auf
bestimmte Netze ([Authentifizierung](../betreiben/authentifizierung.md#lokale-benutzerverwaltung)).

**Passwortregel:** mindestens die eingestellte Länge, höchstens 64 Zeichen, nicht die eigene Adresse
und nicht auf der Liste häufiger Passwörter. Keine Komplexitätsregeln, kein erzwungener Wechsel.

## Selbstregistrierung

Erst mit einer Liste zulässiger Domänen wirkt der Schalter. Ein selbst registriertes Konto erhält
immer die Rolle *Nutzer*, ein Pflicht-Ablaufdatum und den Anlagegrund „Selbstregistrierung“. Name
und Passwort setzt die Person erst über den Bestätigungslink; bis dahin ist das Konto nicht
anmeldefähig. Eine schon belegte Adresse ergibt kein zweites Konto, und die Antwort verrät das nicht.

Die Selbstregistrierung gehört vor dem Einschalten mit der Personalvertretung geklärt
([Einführung vorbereiten](einfuehrung.md)).

**Gut zu wissen:**

- Einen zweiten Faktor gibt es für lokale Konten nicht.
- „Zurücksetzen“ an einem unbestätigten Konto schickt die Einladung; wer sie einlöst, bestätigt
  damit die Adresse.
- Das Abschalten der lokalen Verwaltung lässt die verbundenen Konten regulärer lokaler Konten nur
  ruhen.
