# Lokale Konten verwalten

**Ziel:** Wer ohne Identitätsanbieter arbeitet, hat ein passendes lokales Konto.
{: .kopf }

**Wer:** Systemverwaltung, „Administration → Benutzer“
{: .kopf }

Lokale Konten führt OPAA selbst. Sie werden gebraucht, solange kein Identitätsanbieter angebunden
ist, und immer für das Notanker-Konto der Systemverwaltung. Konten eines Identitätsanbieters stehen
in derselben Liste, ihren Lebenszyklus führt aber der Anbieter; hier lässt sich nur ihre
[Systemrolle](systemrollen.md) ändern.

**Läuft ein Identitätsanbieter, die lokale Verwaltung abschalten**, sobald er steht (Reiter
„Einstellungen“, Karte „Lokale Anmeldung“). Jedes lokale Konto ist ein Zugang, den kein Austritt
im Haus automatisch entzieht. Abgeschaltet melden sich nur noch lokale Systemverwalter an, über
`/login/system`.

## Konto anlegen

1. Reiter „Konten“, „Konto anlegen“.
2. E-Mail-Adresse, Anzeigename und Rolle eintragen.
3. **Anlagegrund** eintragen, etwa „Vertretung Meldewesen bis Jahresende“. Die Person liest ihn in
   ihren eigenen Einstellungen; nichts zu Gesundheit, Leistung oder Beschäftigungsverhältnis.
4. **Ablaufdatum** prüfen; es ist vorbelegt. Ohne Datum verlangt das Formular „Kein Ablaufdatum“.
5. Weg wählen: „Einladung per E-Mail“ (die Person setzt ihr Passwort selbst) oder ein
   Anfangspasswort, das OPAA einmal anzeigt und beim ersten Anmelden wechseln lässt.

Ohne Mailversand zeigt OPAA den Einladungslink **einmal** an; Sie geben ihn auf anderem Weg weiter.
Ein Link gilt einmal, ein neuer macht den alten ungültig.

## Sperren statt löschen

| Handlung | Wirkung |
|---|---|
| „Sperren“ | beendet alle Sitzungen und Zugangstokens sofort; die Person erhält eine Mail, auf Wunsch mit einem Satz von Ihnen |
| „Entsperren“ | hebt jede Sperre auf: durch die Verwaltung, nach Fehlversuchen, nach Inaktivität |
| „Rücksetz-Link per E-Mail“ oder „Passwort erzeugen“ | neues Passwort; beendet ebenfalls alle Sitzungen |
| Ablaufdatum erreicht | das Konto kann sich nicht anmelden; ein neues Datum gibt es frei |
| „Löschen“ | nur für ein Konto, auf das nichts mehr verweist: kein Chat, keine Bibliothek, keine Freigabe, kein Nachweiseintrag. Praktisch nur für Tippfehler und Testkonten |
| „Personenbezug entfernen …“ | für ein Löschbegehren nach der DSGVO, nicht umkehrbar; siehe unten |

**Ausscheiden heißt sperren.** Wer je Mitglied einer Gruppe war, hinterlässt einen Nachweis, der
das Löschen blockiert, solange er liegt. Was mit Bibliotheken und Spaces der Person geschieht,
regelt [Ausscheiden und Nachfolge](ausscheiden.md).

**Chats gesperrter Konten** löscht OPAA nach einer Frist ab der Sperre durch die Verwaltung, in
allen Spaces. Das Datum steht im Dialog „Bearbeiten“ unter „Chats nach der Sperre“. Entsperren
beendet die Frist. Muss etwas aufgehoben werden, etwa auf behördliche Anordnung, beantragt eine
Person der Systemverwaltung „Anhaltung beantragen“ mit Zweck und Begründung, und erst eine
**zweite** Person gibt frei. Die Freigabe gilt genau dem angezeigten Antrag: Wurde er inzwischen
zurückgezogen und neu gestellt, lehnt OPAA sie ab und zeigt den aktuellen Antrag. Einen Antrag,
der älter als 14 Tage ist, kann niemand mehr freigeben: „Ablehnen“ und neu beantragen. Gelöschte Chats kommen über die Oberfläche nicht zurück. Frist und
Schalter stehen unter [Datenschutz](datenschutz.md).

## Personenbezug entfernen

Verlangt eine Person die Löschung ihrer Daten, entfernt „Personenbezug entfernen …“ im Zeilenmenü
alles, was sie persönlich betrifft. Das gilt für lokale Konten und für Konten eines
Identitätsanbieters.

| Was geschieht | Was bleibt |
|---|---|
| Name, Adresse und Kennung beim Anbieter verschwinden, überall steht „Gelöschtes Konto“ | Mitgliedschaften, Rechte und deren Historie |
| alle Chats der Person, auch in geteilten Spaces, ihr persönlicher Space, Benachrichtigungen, Favoriten und Fachwörter der Spracheingabe werden gelöscht | geteilte Bibliotheken, Spaces und Gruppen; sie stehen auf „Nachfolge offen“ |
| private Bibliotheken werden binnen Minuten mit allen Inhalten gelöscht | das Revisionsprotokoll mit seinen Pseudonymen |
| Anmeldedaten, Sitzungen und Zugangstokens enden | Dokumente, die die Person in geteilte Bibliotheken geladen hat |

Das Konto verschwindet aus der Liste. Meldet sich die Person später beim Anbieter wieder an,
entsteht ein neues, leeres Konto.

**Gut zu wissen:**

- Nur ein gesperrtes oder abgelaufenes Konto ohne Systemrolle lässt sich so behandeln. Konten
  eines Anbieters gelten als deaktiviert, wenn der Verzeichnisabgleich sie sperrt oder der Anbieter
  entfernt wurde.
- Eine laufende oder beantragte Anhaltung der Chat-Löschung und eine offene anlassbezogene Klärung,
  die das Konto nennt, verhindern den Schritt, bis sie enden.
- Daten über die Person in Dokumenten und in Sicherungen erfasst der Schritt nicht.

## Liste prüfen

Die Filter „ohne Ablaufdatum“, „länger nicht genutzt“ und „offene Einladungen“ zeigen die Konten,
an denen eine Entscheidung fällig ist. Ein Hinweis neben „Konto anlegen“ nennt die Zahlen, einmal
im Quartal kommt eine Wiedervorlage per Mail. Die Liste zeigt bewusst kein Datum der letzten
Nutzung und hat keinen Export.

## Das Notanker-Konto

Das Konto aus dem Erststart ist der Weg zurück in eine ausgesperrte Installation. Es lässt sich
weder sperren noch löschen, befristen, herabstufen oder übergeben, und die Verwaltung kann ihm kein
Passwort setzen. Sein Passwort ändert es selbst; ohne Anmeldung hilft nur die
[Notfallprozedur](../betrieb/authentifizierung.md#notfallprozedur-wieder-hereinkommen). Auch das
**letzte anmeldefähige Systemverwalterkonto** lässt sich nicht sperren, befristen, herabstufen
oder löschen.

**Gut zu wissen:**

- Die Anmeldeseite nennt nie den Grund einer Ablehnung; die Person erfährt ihn per Mail oder beim
  nächsten Aufruf.
- Die Sperre nach Fehlversuchen schickt keine Mail und endet von selbst; „Passwort vergessen“
  bleibt wirksam.
- Selbstregistrierung, Passwortregeln und Fristen: [Anmeldung einstellen](anmeldung-einstellen.md).
