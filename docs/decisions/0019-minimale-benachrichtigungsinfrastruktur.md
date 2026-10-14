# ADR-0019: Minimale Benachrichtigungsinfrastruktur

## Status

Akzeptiert · zuletzt geändert 2026-10-02 · ergänzt durch ADR-0033 (Entscheidung 10) und ADR-0039

## Kontext

Die Spezifikation verlangt, dass der Eigentümer eines Assets aktiv benachrichtigt wird, wenn es in
einem Space bereitgestellt wird, dessen Mitglieder nicht alle Lesezugriff darauf haben, ohne
Zustimmungspflicht ([spaces-and-assets.md](../features/spaces-and-assets.md#assets-in-einen-space-assoziieren)).
Dafür gab es keine Infrastruktur: kein Modell, keine Ablage,
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
Eine neue Benachrichtigungszusage ergänzt einen Wert, wenn sie umgesetzt wird, nicht vorab.
Es gibt keinen `CHECK` in der Datenbank, das Enum allein ist der Schreibschutz, eine Migration
entfällt; braucht eine Art weitere Spalten, kommen sie mit ihr. Die Zuordnung zu einem gemischten
Space ist typneutral `ASSET_ASSOCIATED_TO_MIXED_SPACE` und wird beim Anlegen eines Space mit vielen
Zuordnungen je Eigentümer gebündelt; die gebündelte Benachrichtigung bezieht sich auf den Space, die
einzelne auf das Asset (ADR-0039, #2097).

### 3. Nicht gebaut

Kein Postfach-Konzept, keine Todo-Achse, keine Kategorien oder Filter, keine Gruppierung nach Objekt
oder Auslöser, keine Zustellungsgarantie über den Datenbank-Insert hinaus. Ein Postfach kann aus der
Tabelle herauswachsen (`type`, Objektbezug und `readAt` sind bereits vorhanden), ohne sie zu ersetzen;
ein Todo-Feld kommt mit der Notwendigkeit.

### 4. Kanal E-Mail ist möglich, nicht beschlossen

Mit ADR-0033 Entscheidung 10 gibt es das Subsystem `io.opaa.mail` (SMTP-Einstellungen in der
Datenbank, `MailService` mit Versandergebnis statt Ausnahme, Vorlagen-Registry mit Überschreibung).
Ob und für welche Typen zusätzlich eine Mail geht, entscheidet das Benachrichtigungs-Epic (#1297);
ein solcher Kanal ergänzt Vorlagen und ruft `MailService`, ohne zweites Versandsubsystem und ohne
den Vertrag von `MailService.send` zu ändern. Der Versand ist synchron, ohne Outbox und ohne
Wiederholung; ein Digest könnte eine Warteschlange davor setzen, ohne diesen Vertrag zu ändern.

## Konsequenzen

**Einfacher:** Jede weitere Benachrichtigungsart nutzt Tabelle und Endpunkte und ergänzt in der
Regel nur einen Enum-Wert.

**Schwieriger:** Ein echtes Postfach ist weiterhin zu spezifizieren. Ob ein einzelnes
`objectType`/`objectId`-Paar für jede Art reicht, bleibt offen, bis eine Art mehrere Objektbezüge
braucht.

## Verworfene Alternativen

- Postfach mit Kategorien und Todos vorab — Festlegung ohne Anforderungsbasis.

## Offen

- Zusätzlicher Mail-Kanal und Digest (#1297).
