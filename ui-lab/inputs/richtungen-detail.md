# Drei Richtungen für die Detailansicht einer Wissensbibliothek

**Ausgangslage**
- Ist ist Variante F. Ihr Reiter „Quelle“ (Zeilen Verbindung · Umfang · Zeitplan mit „Ändern“) ist entschieden und bleibt in allen drei Richtungen.
- Maßstab: `pruefliste-detail.md` (D1–D24) und `docs/design/ui-pruefliste.md`.
- Belege: `inventory-detail.md`, `ui-lab/results/ist-detail/REVIEW-1.md`.

**Unterschied der Richtungen.** Sie unterscheiden sich in einer Achse: **Wo lebt der Zustand der Bibliothek, und wo die Läufe?**

| | A „Alles an seinen Ort“ | B „Statusband“ | C „Steckbrief“ |
|---|---|---|---|
| Zustand | ein Wort im Kopf, Ursache in der Zeile des Reiters Quelle | ein Band mit drei festen Feldern im Kopf, für alle Arten gleich | Seitenspalte rechts mit Zustand, Quelle kurz und nächsten Schritten |
| Läufe | Reiter Quelle, unter den Zeilen | eigener Reiter „Verlauf“ | Reiter Quelle; die Seitenspalte zeigt den letzten Lauf |
| Hypothese des Maintainers | **konsequent umgesetzt** | **bewusste Gegenposition** | Mittelweg: Status kompakt, Einzelheiten im Reiter |
| „Nach dem Anlegen“-Lücke | Zeile „Live-Aktualisierung“ im Reiter Quelle | wie A | **Abschluss nach dem Anlegen** mit nächsten Schritten |

Für die Skizzenrunde (Skill, Schritt 3) schlage ich drei Fälle vor, die den Bereich aufspannen:
1. **Upload `small`** – der kleine Fall
2. **Confluence `scale`** – viel: 120 Spaces, 45 Freigaben, 500 Ereignisse
3. **Nextcloud `failed`** – Anmeldung abgelehnt

Dazu je ein Bild von `partial` und `slow` in Kopfhöhe, weil die Richtungen sich genau dort trennen.

---

## Richtung A · „Alles an seinen Ort“ (Hypothese konsequent)

**Idee.** Der Kopf ist für alle Bibliotheken gleich und trägt nur, was für alle gilt. Alles Quellartspezifische steht im Reiter Quelle an der Zeile, die es betrifft. Ausnahme: Ein Zustand, der Handeln verlangt, erscheint im Kopf als **ein Wort**, das zum Ort der Ursache führt. Zwischen Kopf und Reitern steht nichts.

```
[Wissen] [Confluence] [Eigentümer]                               [Jetzt indizieren] ☆ ⋯
Verwaltungswiki ✎
Spaces aus Confluence Cloud
1 250 Dokumente · in 34 Spaces › · Erika Muster › · ● Anmeldung abgelehnt ›      ← eine Zeile
──────────────────────────────────────────────────────────────────────────────────────
Dokumente   Quelle   Metadaten   Freigaben   Zuordnungen
──────────────────────────────────────────────────────────────────────────────────────
Verbindung   rheinfurt.atlassian.net · ● Anmeldung abgelehnt am 23.10.      Ändern
Umfang       120 Spaces · 2 nicht lesbar: Bauamt, Rechtsamt                   Ändern
             Personal und Organisation 001 … und 115 weitere
Zeitplan     täglich 06:00 · Vollabgleich alle 7 Tage · nächster 25.10.       Ändern
Live-Aktual. aus                                                           Einrichten
Läufe                                                   Jetzt indizieren · Vollabgleich
 ● Fehlgeschlagen   23.10. 06:00   7 min   Zeitplan   Anmeldung abgelehnt        ›
 ● Teilweise        22.10. 06:00   3 h 12  Zeitplan   1 320 · 38 fehlgeschl.     ›
```

**Kopf**
- Feste Reihenfolge nach D1: Chips · Titel · Beschreibung · **eine** Kennzahlzeile.
- Das **Zustandswort** (D2) ist das letzte Element der Kennzahlzeile: Punkt + Wort + bei Bedarf Datum oder Prozent. Es ist ein Link:
  - „Anmeldung abgelehnt“ führt zu Quelle › Verbindung.
  - „Teilweise fehlgeschlagen“ führt zu Quelle › Läufe, neuester Lauf offen.
  - „Läuft · 62 %“ führt zur Laufzeile.
- Im Normalfall steht dort „Aktuell · 24.10.2026“; die Kennzahl „Aktualisiert am“ entfällt dafür.
- Upload: „Hochgeladen · 24.10.2026“, „Kontingent voll“ oder „3 fehlgeschlagen“ führen in den Reiter Dokumente (gefiltert).
- Speicher steht als Kennzahl bei allen Arten, die ihn kennen.

**Reiter**
- **Dokumente:** Liste direkt unter den Reitern, Werkzeugleiste mit Suche · Filter „Status“ · Filter „ohne Metadatenwert“. Kein Info-Alert, keine Metadaten-Pflege (D20, D22). Upload: die Ablagefläche nur, solange die Bibliothek leer ist; danach ein Knopf „Hochladen“ in der Werkzeugleiste und Drag & Drop auf die Liste.
- **Quelle:** F unverändert, dazu:
  - Die Zeile Umfang markiert nicht lesbare Bereiche (D8).
  - Die Zeile Verbindung trägt den letzten bekannten Zustand (D10).
  - Live-Aktualisierung als vierte Zeile bei Confluence und S3 (D9). Web und RSS bekommen eine Zeile Umfang („Ganzes Verzeichnis“).
- **Metadaten, Freigaben, Zuordnungen:**
  - eine Inhaltsbreite (D16), ein Überschriftenstil (D17), „Hinzufügen“ oben rechts (D18)
  - Suche ab 8 Einträgen (D19)
  - Freigaben gruppiert in Gruppen · Personen · Alle Konten, je Eintrag zwei Zeilen

**Läufe**
- Laufliste im Reiter Quelle als Tabelle mit festen Spalten: Zustand · Beginn · Dauer · Auslöser · Ergebnis (D12). Die Startknöpfe stehen an der Überschrift.
- Aufgeklappt: Gruppen nach Grund mit Anzahl, je Gruppe 5 Fundorte und „alle anzeigen“ (D13). Hinweise (entfernt, gedrosselt) stehen in einer eigenen, zugeklappten Gruppe. Technische Kennzahlen unter „Technische Angaben“.

**Quellartspezifisches.** Steht nur im Reiter Quelle. Der Kopf unterscheidet sich je Art nur im Chip und darin, ob „Jetzt indizieren“ da ist (bei Upload „Hochladen“ an derselben Stelle).

**Adressiert besonders:** D1, D2, D4, D8, D10, P46, P50, Rückmeldungen 1 und 4.

**Risiken**
- Ein Wort im Kopf wird übersehen, wenn es grau ist. Die Signalfarbe im Punkt und das Wort müssen reichen; Prüfung im Video „fehler“.
- Wer im Reiter Dokumente den Teilfehler sucht, muss in den Reiter Quelle wechseln. Gemildert dadurch, dass „Teilweise fehlgeschlagen“ im Kopf hinführt.
- Der Kopf hängt bei sehr vielen Kennzahlen (Upload mit Speicher) an der Grenze einer Zeile.

---

## Richtung B · „Statusband“ (Gegenposition zur Hypothese)

**Idee.** Der Zustand ist die wichtigste Information einer angebundenen Bibliothek. Darum steht er **für alle Arten gleich** in einem festen Band unter dem Titel. Das Band hat drei Felder, die nie wandern: **Stand · Umfang · Nächster Lauf**. Quellartspezifisches erscheint dort in einheitlicher Kurzform. Die Läufe bekommen einen eigenen Reiter „Verlauf“; der Reiter Quelle bleibt die Einstellung.

```
[Wissen] [Confluence] [Eigentümer]                               [Jetzt indizieren] ☆ ⋯
Verwaltungswiki ✎
1 250 Dokumente · in 34 Spaces · Erika Muster
┌────────────────────────┬───────────────────────────┬──────────────────────────┐
│ Stand                  │ Umfang                    │ Nächster Lauf            │
│ ● Anmeldung abgelehnt  │ 120 Spaces · 2 nicht lesb.│ 25.10. 06:00 · täglich   │
│ 23.10. 06:07 · Details›│ Quelle ›                  │ Ändern ›                 │
└────────────────────────┴───────────────────────────┴──────────────────────────┘
Dokumente   Verlauf   Quelle   Metadaten   Freigaben   Zuordnungen
```

**Kopf**
- Titel und eine Kennzahlzeile ohne Lauf, darunter das **Statusband**: drei gleich breite Felder mit 1-px-Rahmen, ohne Tönung (G 4.3), immer da.
  - **Stand:** Zustandswort + Datum, darunter „Details ›“ in den Reiter Verlauf.
  - **Umfang:** Kurzform wie im Reiter Quelle (Zahl + Art, „2 nicht lesbar“ in Warnfarbe).
  - **Nächster Lauf:** Zeitplan in Kurzform.
- Läuft gerade etwas, zeigt das Feld Stand „Läuft · 62 %“ und eine dünne Leiste **im Feld**, nicht über die Seite.
- Bei Upload heißen die Felder **Stand · Speicher · Zuletzt hochgeladen**. Gleiche Form, anderer Inhalt.
- Bei 320 px stehen die Felder untereinander, je eine Zeile.

**Reiter**
- **Dokumente** wie in A.
- **Verlauf (neu, zweiter Reiter):** die 10 Läufe als Tabelle (D12) mit der Ereignisgruppierung (D13). Darüber ein Filter „nur mit Fehlern“ und die Knöpfe „Jetzt indizieren · Vollabgleich“.
  - Bei Upload zeigt der Verlauf die Upload-Vorgänge: Datum, Person, n Dateien, n abgelehnt. Der Upload bekommt damit dieselbe Spur wie ein Lauf.
- **Quelle:** F ohne Läufe, dafür mit Live-Aktualisierung (D9). Bei Upload nicht vorhanden.
- **Metadaten, Freigaben, Zuordnungen** wie in A.

**Läufe.** Eigener Reiter, keine Konkurrenz mit den Einstellungen. Die Seite „Quelle“ bleibt kurz (3–4 Zeilen); der Verlauf darf lang sein, weil er nichts anderes zeigt.

**Quellartspezifisches.** Steht im Band in fester Form (Umfang-Kurzform) und im Reiter Quelle ausführlich. Alle Arten haben dieselben drei Felder; nur der Text darin unterscheidet sich.

**Adressiert besonders:** D1 (gleiche Höhe durch festes Band), D2, D3, D4 und D7 (Fortschritt im Feld), D15 (Upload-Verlauf), Rückmeldung 2 (Läufe bekommen Platz statt Länge).

**Risiken**
- Das Band ist eine Fläche aus drei Kacheln. Das ist nahe an „getönte Kacheln“ (P4); es muss ohne Tönung und ohne Icons auskommen, sonst kippt es in KI-Look.
- Der Umfang steht zweimal (Band und Reiter Quelle): eine gewollte Doppelung gegen P50. Sie ist nur vertretbar, wenn das Band eine Kurzform mit Link ist.
- Sechs Reiter: bei 320 px Scrollen nötig (D-Budget Kopfhöhe beachten).
- Die Hypothese des Maintainers wird ausdrücklich **nicht** umgesetzt. Die Juroren prüfen, ob das Band den Zwischenbereich ersetzt oder nur ordnet.

---

## Richtung C · „Steckbrief mit Abschluss“

**Idee.** Die Detailseite hat zwei Aufgaben: **Inhalte bearbeiten** (Dokumente, Metadaten, Freigaben, Zuordnungen) und **den Betrieb im Blick halten** (Zustand, Quelle, nächster Lauf, offene Schritte). C trennt sie räumlich. Links stehen die Reiter mit den Inhalten, rechts eine schmale Spalte „Steckbrief“ (280 px), die auf allen Reitern gleich bleibt. Direkt nach dem Anlegen zeigt der Steckbrief einen **Abschluss** mit den nächsten Schritten, die der Assistent nicht kennt (Inventur, Kapitel 8).

```
[Wissen] [Confluence] [Eigentümer]                         [Jetzt indizieren] ☆ ⋯
Verwaltungswiki ✎ · Spaces aus Confluence Cloud
────────────────────────────────────────────────────────┬───────────────────────────
Dokumente   Quelle   Metadaten   Freigaben   Zuordnungen │ Zustand
────────────────────────────────────────────────────────│ ● Anmeldung abgelehnt
[Suche …………………]  Status ▾  ohne Wert ▾                 │ 23.10. 06:07 · Verbindung ›
Dokument 001.docx                        indiziert   ⓘ ↗ │
25 KB · 12 Abschnitte · 24.10.2026                       │ Quelle
Dokument 002.xlsx                    fehlgeschlagen ⓘ ↗ │ rheinfurt.atlassian.net
…                                                        │ 120 Spaces · 2 nicht lesbar
                                                         │ täglich 06:00 · nächster 25.10.
                                                         │
                                                         │ Bestand
                                                         │ 1 250 Dokumente · 734 MB
                                                         │ in 34 Spaces › · Erika Muster ›
                                                         │
                                                         │ Nächste Schritte (2 von 4)
                                                         │ ✓ Quelle verbunden
                                                         │ ○ In Space verwenden
                                                         │ ○ Live-Aktualisierung einrichten
                                                         │ ✓ Metadatenfelder (übersprungen)
```

**Kopf.** Schmal: Chips, Titel mit Beschreibung in einer Zeile, Aktionen. Keine Kennzahlen, sie wandern in den Steckbrief.

**Steckbrief (rechte Spalte, ab 1100 px; darunter unter dem Kopf als einklappbarer Block „Steckbrief“)**
- **Zustand:** Wort + Datum + Link zur Ursache (D2). Läuft gerade etwas, „Läuft · 62 %“ mit dünner Leiste im Block.
- **Quelle:** drei Kurzzeilen (Verbindung, Umfang, Zeitplan) als Spiegel der Zeilen im Reiter Quelle, ohne „Ändern“. Ein Klick führt in die Zeile.
- **Bestand:** Dokumente, Speicher, Spaces, Zuständige; je ein Link in den Reiter.
- **Nächste Schritte:** nur nach dem Anlegen, bis alles erledigt oder verworfen ist.
  - alle Arten: „In Space verwenden“
  - Upload: „Dateien hochladen“
  - Confluence, S3: „Live-Aktualisierung einrichten“
  - alle: „Metadatenfelder anlegen“
  - Wer sie verwirft, bekommt sie nicht wieder.

**Reiter.** Wie A (Dokumente ohne Alert, Quelle von F mit D8–D11, Metadaten/Freigaben/Zuordnungen mit D16–D19). Die Inhaltsbreite links ist für alle Reiter gleich und schmaler als heute, die Spalte rechts füllt die Breite.

**Läufe.** Im Reiter Quelle wie A. Der Steckbrief zeigt nur den letzten Lauf als Zustand.

**Quellartspezifisches.** Im Reiter Quelle ausführlich, im Steckbrief als Kurzzeilen. Upload hat statt „Quelle“ den Block „Hochladen“: Speicher, zuletzt hochgeladen, abgelehnte Dateien.

**Adressiert besonders:** D2 und D4 (Zustand auf jedem Reiter sichtbar, ohne Kopf zu verlängern), D1 (Kopf überall gleich klein), D24 und Inventur-Kapitel 8 (Lücke nach dem Anlegen), D23 (Upload mit eigenem, gleich gebautem Block).

**Risiken**
- Zwei Spalten verschieben die Seite stärker als A und B; ab 1100 px gewinnt sie, darunter wird der Steckbrief ein Block über den Reitern, also wieder ein Zwischenbereich (Gegenstück zu D8). Das muss bei 320 px einklappen und darf nicht mehr als eine Zeile beanspruchen.
- Der Steckbrief spiegelt den Reiter Quelle: eine Doppelung, die P50 nur als Kurzform mit Link übersteht (wie das Band in B).
- Die Liste der nächsten Schritte ist eine Checkliste, ein beliebtes KI-Muster. Sie darf nur nach dem Anlegen erscheinen, ohne Fortschrittsbalken und ohne Icons außer ✓/○.
- Die Tabellen der Reiter Dokumente und Freigaben werden schmaler; Spalten müssen bei 1280 noch passen.

---

## Was alle drei gemeinsam erfüllen müssen

- **Gleich in allen Richtungen:**
  - Kopf gleich hoch über alle Arten (D1)
  - ein Zustand mit einem Wort aus einer Quelle (D2, D3)
  - Ernstfall von jedem Reiter aus sichtbar (D4)
  - nichts zwischen Kopf und Reitern (D8; in C nur unter 1100 px als einklappbarer Steckbrief)
- **Läufe** als Tabelle mit gruppierten Ereignissen (D12–D14).
- **Reiter:** eine Inhaltsbreite und ein Überschriftenstil (D16, D17); Suche ab 8 Einträgen (D19); kein Dauerhinweis (D20).
- **Upload** gleich gebaut (D23); nichts geht verloren (D24, einschließlich Webhook, S3-Ereignissen und „letzte geplante Läufe fehlgeschlagen“).
- **Verlauf aus `main`:** Er wird auch für die Prompt-Bibliothek entfernt (F4).
