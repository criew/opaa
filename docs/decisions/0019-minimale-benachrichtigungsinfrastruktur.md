# ADR-0019: Minimale Benachrichtigungsinfrastruktur

## Status

Akzeptiert · zuletzt geändert 2026-10-02 · ergänzt durch ADR-0033 (Entscheidung 10) und ADR-0039

## Kontext

Die Spezifikation verlangt, dass der Eigentümer eines Assets aktiv benachrichtigt wird, wenn es in
einem Space bereitgestellt wird, dessen Mitglieder nicht alle Lesezugriff darauf haben
(`docs/features/spaces-and-assets.md`). Dafür gab es keine Infrastruktur: kein Modell, keine Ablage,
keine Anzeige. Weitere Zusagen derselben Art standen bereits in der Spezifikation (Erweiterung des
Leserkreises, neue Version eines Originals, Prüfaufforderungen), was ein gemeinsames In-App-Postfach
mit Todos nahelegte. Ein solches Postfach war aber nirgends spezifiziert. Die Frage war, wie schmal die
eine Benachrichtigung gebaut werden kann, ohne eine spätere Zusammenführung zu erschweren.

## Entscheidung

### 1. Eine schmale Tabelle, zwei Endpunkte, eine Glocke

Die Tabelle `notifications` (`io.opaa.notification.Notification`) trägt `recipientUserId`, `type`,
`objectType`/`objectId` (Bezug auf das betroffene Objekt, wiederverwendet aus `AuditObjectType`),
`title`, `body`, `readAt`, `createdAt`. Dazu `GET /v1/notifications` (eigene Benachrichtigungen,
neueste zuerst, mit `limit`) und `POST /v1/notifications/{id}/read`. Im Frontend eine Glocke mit
Ungelesen-Badge (`NotificationBell`), ohne Filter, Sortierung, Kategorien oder Massenaktionen.

### 2. Ein geschlossenes, wachsendes Typ-Vokabular

`type` ist das Enum `NotificationType` (`io.opaa.api.types`), nach dem Muster von `AuditEventType`.
Eine neue Benachrichtigungszusage ergänzt einen Wert, wenn sie umgesetzt wird, nicht vorab; seit #862
gibt es keinen `CHECK` in der Datenbank, das Enum allein ist der Schreibschutz, eine Migration
entfällt. Die Zuordnung zu einem gemischten Space ist typneutral `ASSET_ASSOCIATED_TO_MIXED_SPACE`
und wird beim Anlegen eines Space mit vielen Zuordnungen je Eigentümer gebündelt (ADR-0039, #2097).

### 3. Nicht gebaut

Kein Postfach-Konzept, keine Todo-Achse, keine Kategorien oder Filter, keine Gruppierung nach Objekt
oder Auslöser, keine Zustellungsgarantie über den Datenbank-Insert hinaus. Ein Postfach kann aus der
Tabelle herauswachsen (`type`, Objektbezug und `readAt` sind bereits vorhanden), ohne sie zu ersetzen;
ein Todo-Feld kommt mit der Notwendigkeit.

### 4. Kanal E-Mail ist möglich, nicht beschlossen

Mit ADR-0033 Entscheidung 10 gibt es das Subsystem `io.opaa.mail` (SMTP-Einstellungen in der
Datenbank, `MailService` mit Versandergebnis statt Ausnahme, Vorlagen-Registry mit Überschreibung).
Ob und für welche Typen zusätzlich eine Mail geht, entscheidet das Benachrichtigungs-Epic (#1297);
ein solcher Kanal ergänzt Vorlagen und ruft `MailService`, ohne zweites Versandsubsystem. Der Versand
ist synchron, ohne Outbox und ohne Wiederholung; ein Digest könnte eine Warteschlange davor setzen.

## Konsequenzen

**Einfacher:** Jede weitere Benachrichtigungsart nutzt Tabelle und Endpunkte unverändert und ergänzt
nur einen Enum-Wert.

**Schwieriger:** Ein echtes Postfach ist weiterhin zu spezifizieren. Ob ein einzelnes
`objectType`/`objectId`-Paar für jede Art reicht, bleibt offen, bis eine Art mehrere Objektbezüge
braucht.

## Verworfene Alternativen

- Postfach mit Kategorien und Todos vorab — Festlegung ohne Anforderungsbasis.

## Offen

- Zusätzlicher Mail-Kanal und Digest (#1297).

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-21 | #706 | Erstfassung | 1–3 |
| 2026-08-24 | #868 | `CHECK` auf `type` entfällt, Enum ist der Schreibschutz | 2 |
| 2026-09-11 | #1536 | Mail-Subsystem vorhanden, Kanal E-Mail nicht beschlossen (ADR-0033) | 4 |
| 2026-10-02 | #2097 | Typneutrale, gebündelte Benachrichtigung bei Space-Zuordnung (ADR-0039) | 2 |
