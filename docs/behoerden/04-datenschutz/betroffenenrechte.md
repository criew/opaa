---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Ablauf für Betroffenenrechte

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Dieser Baustein beschreibt, wie eine Behörde Anfragen zu Betroffenenrechten bearbeitet, die OPAA
betreffen: Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch. Er knüpft an die
Funktionen an, die das Produkt dafür hat, und sagt, wo die Behörde selbst handeln muss. Den Ablauf,
den die Behörde für alle Verfahren hat, ersetzt er nicht; er ergänzt ihn um das, was für OPAA gilt.
Angaben in eckigen Klammern **[…]** ergänzt die Behörde.

## Quellen und Prüfstand

| Fundstelle | Inhalt | Herkunft |
|---|---|---|
| Art. 12 bis 21 [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu) | Rechte der betroffenen Person, Form und Fristen der Antwort | Normtext |
| § 26 [BDSG](https://www.gesetze-im-internet.de/bdsg_2018/__26.html) bzw. Landesrecht | Beschäftigtendaten | Recherche vom 13.10.2026 |
| DSK, [Kurzpapiere](https://www.datenschutzkonferenz-online.de/kurzpapiere.html), Nr. 6 „Auskunftsrecht der betroffenen Person“ und Nr. 11 „Recht auf Löschung“ | Hinweise zu Umfang und Ausnahmen | Muster |

Die Recherche vom 13.10.2026 steht im ersten Kommentar von Epic
[#2542](https://github.com/criew/opaa/issues/2542). Ausnahmen und Beschränkungen der Rechte, etwa nach
dem BDSG oder dem Landesrecht, prüft die Behörde im Einzelfall.

## Ablauf

1. **Eingang** bei [Stelle, z. B. Datenschutzbeauftragte]; Datum festhalten, die Frist nach Art. 12
   Abs. 3 DSGVO läuft ab Eingang.
2. **Identität prüfen.** Bei Beschäftigten etwa über das Konto, mit dem sie sich bei OPAA anmelden.
3. **Betroffene Daten bestimmen.** Welche Daten zu einer Person entstehen können, steht im
   {{faktenblatt:datenkatalog}}, Zeile „Spalten mit Personenbezug“; wer sie sieht, in der
   {{faktenblatt:rollensicht}}. Unterscheiden:
   - Daten über die Person als **Nutzende** (Konto, Chats, Rechte, Protokoll),
   - Daten über die Person **in Dokumenten** der Wissensbibliotheken.
4. **Handeln** nach der Tabelle unten.
5. **Antworten und dokumentieren**, mit der eingesetzten OPAA-Version.

## Rechte und Wege in OPAA

| Recht | Was die Person selbst kann | Was die Behörde tut |
|---|---|---|
| **Auskunft** (Art. 15) | eigene Angaben in den Einstellungen ([Handbuch, Benutzerverwaltung: Was Nutzende selbst können](../../handbuch/benutzerverwaltung.md#11-was-nutzende-selbst-können)); eigene Chats ([Handbuch, Suche in eigenen Chats](../../handbuch/suche.md#72-suche-in-eigenen-chats)); eigene Zugangstokens ([Handbuch, Fremdzugänge](../../handbuch/fremdzugaenge.md#5-zugangstokens-aus-sicht-der-person)) | übrige Daten nach dem Datenkatalog zusammenstellen, Zugriffsweg je Datum in der Zeile „Zugriffsweg“; Protokolleinträge siehe unten |
| **Berichtigung** (Art. 16) | — | Name und Adresse eines lokalen Kontos ändert die Systemverwaltung ([Handbuch, Benutzerverwaltung](../../handbuch/benutzerverwaltung.md)); bei Konten eines Identitätsanbieters ändert die Behörde die Angaben dort |
| **Löschung** (Art. 17) | eigene Chats löschen | Konto sperren oder löschen: Ein benutztes Konto ist in der Regel nicht löschbar, weil Nachweiseinträge darauf verweisen; dann bleibt nur die Sperre, und die Pseudonymzuordnung des Protokolls bleibt bestehen ([Handbuch, Löschen oder sperren](../../handbuch/benutzerverwaltung.md#8-löschen-oder-sperren); Frist: {{faktenblatt:datenkatalog#audit-pseudonyms}}); Dokumente in der Bibliothek oder ihrer Quelle löschen; Fristen nach dem [Löschkonzept](./loeschkonzept.md) |
| **Einschränkung** (Art. 18) | — | Konto sperren; [Regel der Behörde für Dokumente, etwa aus der Bibliothek nehmen und gesondert aufbewahren] |
| **Datenübertragbarkeit** (Art. 20) | — | [Prüfung durch die Behörde, ob das Recht bei der Rechtsgrundlage greift; Bereitstellung aus den Wegen der Auskunft] |
| **Widerspruch** (Art. 21) | — | [Prüfung durch die Behörde] |

Ein gebündelter Export aller Daten einer Person (Selbstauskunft) ist im Produkt nicht gebaut; das
Zielbild steht in der Spezifikation
[Sicherheit, Nachweis & Prüfbarkeit](../../features/security-and-compliance.md#export-und-auskunft).
Bis dahin stellt die Behörde die Auskunft aus den Wegen der Tabelle zusammen.

### Protokolleinträge

Das Protokoll speichert Personen unter Pseudonymen
({{faktenblatt:datenkatalog#audit-pseudonyms}}); Klarnamen sieht die Revision nur in den Grenzen, die
die {{faktenblatt:rollensicht#auditor}} nennt. Ob Protokolleinträge in eine Auskunft gehören und über
welchen Weg sie erhoben werden, entscheidet die Behörde mit ihrer oder ihrem Datenschutzbeauftragten
und, wo eine Dienstvereinbarung den Zugriff auf das Protokoll regelt, nach deren Verfahren
([#2566](https://github.com/criew/opaa/issues/2566)). Eine Löschung des Kontos lässt das Protokoll
unverändert und hebt nur den Personenbezug auf
([Unveränderlichkeit und Löschrecht](../../features/security-and-compliance.md#unveränderlichkeit-und-löschrecht));
ausgenommen bleibt eine Freigabe der anlassbezogenen Klärung, die die Person nennt: Über sie lassen
sich deren Protokolleinträge bis zur Löschfrist der Freigabe weiter zuordnen
({{faktenblatt:datenkatalog#audit-incident-scopes}}).
Ist das Konto nur gesperrt, weil es nicht gelöscht werden kann, bleibt der Personenbezug bestehen;
die Behörde berücksichtigt das bei der Antwort auf ein Löschbegehren.

### Daten in Dokumenten

Betrifft eine Anfrage Daten in Dokumenten, gilt sie in der Regel dem Verfahren, aus dem das Dokument
stammt. In OPAA folgt das Dokument seiner Quelle; wird es dort gelöscht oder berichtigt, zieht OPAA
nach ([Handbuch, Wissensbibliotheken: Änderungen und Löschungen](../../handbuch/wissensbibliotheken.md#44-änderungen-und-löschungen)).
Antworten in bestehenden Chats, die das Dokument zitiert haben, bleiben bis zur Löschung des Chats
erhalten; ob das für die Anfrage genügt, bewertet die Behörde.

## Vermerk

```text
Anfrage nach Art. [15–21] DSGVO
Eingang:              [Datum]          Frist bis: [Datum]
Person:               [Name, Konto]    Identität geprüft durch: [ ]
OPAA-Version:         [Version]
Betroffene Daten:     [Datenkatalog-Einträge]
Maßnahmen:            [ ]
Antwort am:           [Datum]          durch: [ ]
```
