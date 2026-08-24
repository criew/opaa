# ADR-0019: Minimale Benachrichtigungsinfrastruktur

## Status

Vorgeschlagen

## Kontext

`docs/features/spaces-and-assets.md#assets-in-einen-space-assoziieren` verlangt für #203/#686:
Wird eine Bibliothek in einem Space bereitgestellt, dessen Mitglieder nicht alle Lesezugriff darauf
haben, wird ihr Eigentümer **aktiv benachrichtigt** — ohne Zustimmungspflicht, aber ohne dass er in
eine Liste schauen muss. Für diese eine Zusage gibt es im System noch keine Infrastruktur: kein
Benachrichtigungsmodell, keine Ablage, keine Anzeige.

Gleichzeitig ist absehbar, dass weitere Zusagen derselben Art folgen werden, die bereits an anderer
Stelle der Spezifikation stehen, aber noch nicht gebaut sind — unter anderem:

- „Der Autor wird benachrichtigt, wenn sich der Leserkreis eines von ihm geteilten Inhalts wesentlich
  erweitert" (`docs/features/spaces-and-assets.md#chats`, Abschnitt „Konsequenz für die
  Nutzerführung").
- „Der Verantwortliche wird benachrichtigt, wenn das Original eine neue Version bekommt"
  (`docs/features/spaces-and-assets.md#das-abdriften-von-abkömmlingen`).
- Die spätere Prüfaufforderung mit Frist bei Deaktivierung eines Originals (dieselbe Stelle, bewusst
  zurückgestellt).

Diese Serie legt nahe, früher oder später ein gemeinsames **In-App-Postfach** zu bauen — mit
Benachrichtigungen und, absehbar, auch Todos (offene Freigaben, ausstehende Prüfaufforderungen). Ein
solches Postfach ist an keiner Stelle der Spezifikation spezifiziert; es existiert bislang nur als
plausible Erwartung, nicht als Entscheidung. #203 selbst braucht nur die eine, konkrete
Eigentümer-Benachrichtigung, keine allgemeine Infrastruktur.

Die Frage dieser Entscheidung ist deshalb **nicht**, wie ein künftiges Postfach aussehen soll —
das wäre eine verfrühte Festlegung ohne hinreichende Anforderungsbasis. Die Frage ist, wie schmal
#203 seine eine Benachrichtigung bauen kann, ohne eine spätere Zusammenführung mehrerer
Benachrichtigungsarten in ein gemeinsames Postfach unnötig zu erschweren.

## Entscheidung

#203 führt eine einzige, bewusst schmal geschnittene Tabelle `notifications` ein
(`io.opaa.notification.Notification`), mit genau den Feldern, die die eine benötigte
Benachrichtigung braucht: `recipientUserId`, `type` (geschlossenes Vokabular, aktuell nur
`LIBRARY_ASSOCIATED_TO_MIXED_SPACE`), `objectType`/`objectId` (Bezug auf das betroffene Objekt,
wiederverwendet aus `io.opaa.audit.AuditObjectType`), `title`, `body`, `readAt`, `createdAt`. Dazu
zwei Endpunkte: `GET /v1/notifications` (die eigenen Benachrichtigungen, neueste zuerst, mit
`limit`) und `POST /v1/notifications/{id}/read` (als gelesen markieren). Im Frontend eine einzelne
Glocke mit Ungelesen-Badge (`NotificationBell`), ohne Filter, Sortierung, Kategorien oder
Massenaktionen.

Bewusst **nicht** gebaut: ein Postfach-Konzept, eine Todo-Achse, Kategorien/Filter, Gruppierung nach
Objekt oder Auslöser, eine Zustellungsgarantie über den einfachen Datenbank-Insert hinaus, oder eine
Vorwegnahme der weiteren, oben genannten Benachrichtigungszusagen — jede davon bekommt ihren eigenen
`NotificationType`-Wert (und, falls nötig, weitere `notifications`-Spalten) erst, wenn sie selbst
umgesetzt wird, nicht spekulativ jetzt.

Die Tabelle ist so geschnitten, dass ein künftiges Postfach **aus ihr heraus wachsen** kann, statt
sie zu ersetzen: `type` ist bereits ein geschlossenes, erweiterbares Vokabular (dasselbe Muster wie
`AuditEventType`), `objectType`/`objectId` binden bereits an ein
beliebiges Objekt, und `readAt` trägt bereits die Grundunterscheidung gelesen/ungelesen, die auch
ein Todo-Postfach braucht. Ein Todo-spezifisches Feld (z. B. eine Fälligkeit oder ein
Erledigt-Status) kommt erst mit der Notwendigkeit, nicht vorab.

## Konsequenzen

**Einfacher:**

- #203 liefert seine eine Benachrichtigungszusage ohne eine unbegründete Vorwegnahme künftiger
  Anforderungen — kein Rätselraten darüber, wie ein noch nicht spezifiziertes Postfach aussehen
  soll.
- Die nächste Benachrichtigungszusage (z. B. die Leserkreis-Erweiterung bei Chats) kann dieselbe
  Tabelle und dieselben zwei Endpunkte wiederverwenden, indem sie nur einen neuen `NotificationType`
  ergänzt — ohne eigene Migration, seit #862 `chk_notifications_type` ersatzlos entfernt hat und der
  Java-Enum allein der Schreibschutz ist.

**Schwieriger / bewusst aufgeschoben:**

- Ein echtes Postfach mit Kategorien, Todos und Massenaktionen ist noch zu spezifizieren und zu
  bauen; diese Entscheidung trifft dafür keine Vorfestlegung, verhindert eine spätere aber auch
  nicht.
- Solange nur eine Benachrichtigungsart existiert, bleibt ungeklärt, ob `objectType`/`objectId`
  als einzelnes Paar für jede künftige Art ausreicht, oder ob ein Postfach mehrere Objektbezüge pro
  Eintrag braucht (z. B. „Bibliothek X in Space Y") — diese Frage wird zurückgestellt, bis eine
  zweite Benachrichtigungsart sie tatsächlich aufwirft, statt sie spekulativ vorwegzunehmen.
