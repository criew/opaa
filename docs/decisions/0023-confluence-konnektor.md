# ADR-0023: Confluence-Konnektor — Space-Auswahl, Editionen, Zugangsdaten und Löschsemantik je Lauf

## Status

Vorgeschlagen

## Kontext

OPAA kennt vier Herkünfte: `UPLOAD`, `FILESYSTEM`, `HTTP_DIRECTORY` und `RSS_FEED`. Epic #1129
ergänzt **Confluence** als fünfte — den ersten Konnektor gegen ein Fremdsystem mit eigener
Rechteverwaltung, eigenem Versionsbegriff und zwei nicht deckungsgleichen REST-APIs. Das
Quellentypmodell aus [ADR-0017](0017-quellentypmodell-indizierung.md) (Lauftyp-Enum, Executor-Registry,
Löschsemantik je Typ) und die Quellkonfiguration an der Bibliothek aus
[ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md) (ein Typ je Bibliothek, unveränderlich;
Konfiguration als Einzelwerte; Zeitplan je Bibliothek, #485; verschlüsselte Zugangsdaten, #483) sind
die Grundlage; hinzu kommen [ADR-0022](0022-anhang-als-eigenes-dokument.md) (ein Anhang ist ein
eigenes Dokument mit `parent_document_id`, gemeldet in `currentFilePaths` seines Laufs) und die mit #886
gebaute, mit #877 auf `(Bibliothek, Quellentyp)` begrenzte Abwesenheitsbereinigung
`StaleDocumentCleanupService#cleanupVanished`, die gegen die im Lauf angetroffenen
`file_path`-Werte vergleicht. An drei
Stellen reicht diese Grundlage nicht:

1. **Die Konfiguration hat erstmals einen Listenwert.** Eine Confluence-Bibliothek umfasst eine
   *Auswahl* von Spaces. ADR-0018 kennt Quellkonfiguration nur als Einzelwerte (`source_path`,
   `source_url`, `source_proxy`, `source_credentials`, `source_insecure_ssl`).
2. **Zwei Editionen mit unterschiedlicher API.** Confluence Cloud und Confluence Data Center
   unterscheiden sich in Basispfad, Authentifizierung, Paginierung und Inhaltsformat so weit, dass
   ein gemeinsamer Codepfad mit `if`-Zweigen die falsche Struktur wäre (Belege unten). Die Edition
   ist deshalb Teil des Datenmodells, nicht ein Laufzeitdetail.
3. **Aktualität ohne vollständige Auflistung.** Ein günstiger Änderungsabgleich („was hat sich seit
   dem letzten Lauf geändert") kann Löschungen prinzipiell nicht sehen. Ein Confluence-Lauf zerfällt
   damit in zwei Betriebsarten mit **unterschiedlicher Löschsemantik** — und ADR-0017, Entscheidung 5
   kennt die Löschsemantik bisher nur je Quellentyp, nicht je Lauf.

### Gesetzte Rahmenbedingungen

Drei Festlegungen des Maintainers (01.–03.09.2026, Epic #1129) stehen **vor** diesem ADR fest und
werden hier nicht neu verhandelt, sondern als Rahmen dokumentiert:

- **Eine Bibliothek für alle ausgewählten Spaces.** Confluence ist *eine* Datenquelle; die
  Space-Auswahl ist ihr Umfang, nicht eine Menge eigenständiger Quellen. Kein Verbindungsobjekt,
  keine Bibliothek je Space. Der Preis ist benannt: Die Freigabe der Bibliothek gilt für alle
  ausgewählten Spaces gemeinsam — „Person darf Space A, aber nicht Space B" ist in diesem Modell
  nicht ausdrückbar, sondern erfordert eine zweite Bibliothek mit eigener Auswahl. Das verschärft
  #797 (Obergrenze der Freigabe) und muss in der Anlage **vor** der Space-Auswahl stehen.
- **Beliebig viele Confluence-Bibliotheken.** Der Typ ist weder auf eine Instanz noch auf eine
  Bibliothek je Instanz beschränkt: Jede Bibliothek trägt ihre eigene Adresse, ihre eigenen
  Zugangsdaten und ihre eigene Space-Auswahl — auch mehrere Bibliotheken gegen dieselbe Instanz,
  mit gleichen oder unterschiedlichen Tokens, angelegt von verschiedenen Personen. Zulässig ist zum
  Beispiel gleichzeitig: Instanz 1/Space 1 (Token 1); Instanz 1/Space 2 + Space 3 (Token 1);
  Instanz 1/Space 4 (Token 2); Instanz 2/Space A (Token A); Instanz 2/Space B (Token B).
- **Beide Editionen von Anfang an.** Die Zugriffsschicht entsteht als Port mit zwei Adaptern und
  wird für beide Editionen gemeinsam abgenommen. Kein Adapter wird nachgereicht, weil ein
  nachgereichter Adapter regelmäßig die Struktur des ersten erbt.

### Belege: Unterschiede der Editionen

Geprüft am 01.09.2026 gegen die Herstellerdokumentation. Diese Unterschiede sind der Grund für den
Adapter-Schnitt in Entscheidung 2, nicht eine Vermutung.

| | Cloud | Data Center |
|---|---|---|
| Basispfad Inhalte | `/wiki/api/v2` — **v1-Inhalts-Endpunkte 2025 abgeschaltet** | `/rest/api` (v1, aktuell) |
| Authentifizierung | HTTP Basic aus **E-Mail + API-Token** | `Authorization: Bearer <PAT>` |
| Spaces | `GET /wiki/api/v2/spaces` | `GET /rest/api/space` |
| Paginierung | Cursor (`cursor`, `Link`-Header) | Offset (`start`/`limit`) |
| Verschachtelte Daten | kein `expand`, Folge-Abrufe je Bezug | `expand=body.storage,version,ancestors` |
| Inhaltsformat | `storage` und `atlas_doc_format` | `storage` |
| Ratenbegrenzung | 429 mit `Retry-After`, `X-RateLimit-*`, Punktebudget | im Auslieferungszustand keine |
| Webhooks | nur über Automation-Regel oder Forge-App | eingebaut, mit HMAC-Signatur |
| CQL-Suche | `/wiki/rest/api/search` (v1, nicht abgekündigt) | `/rest/api/content/search` |

Quellen: [Cloud REST v2](https://developer.atlassian.com/cloud/confluence/rest/v2/intro/),
[Abschaltung der v1-Inhalts-Endpunkte](https://developer.atlassian.com/cloud/confluence/deprecation-notice-v1-content-api/),
[Cloud-Authentifizierung mit API-Token](https://developer.atlassian.com/cloud/confluence/basic-auth-for-rest-apis/),
[Cloud-Ratenbegrenzung](https://developer.atlassian.com/cloud/confluence/rate-limiting/),
[Cloud-Webhooks](https://developer.atlassian.com/cloud/confluence/using-webhooks/),
[Data Center REST](https://developer.atlassian.com/server/confluence/confluence-server-rest-api/),
[Data Center PAT](https://confluence.atlassian.com/enterprise/using-personal-access-tokens-1026032365.html),
[Data Center Webhooks](https://developer.atlassian.com/server/confluence/webhooks/),
[CQL](https://developer.atlassian.com/cloud/confluence/advanced-searching-using-cql/).

Drei Befunde daraus sind Fallen, keine Merkmale, und binden die Umsetzung:

- **Die E-Mail-Adresse ist bei Cloud Teil der Zugangsdaten.** Eine Anlage, die nur „Adresse und
  Token" erfragt, kann sich gegen Cloud nicht anmelden. Die Eingabe der Zugangsdaten hängt von der
  Edition ab — und die Edition muss deshalb *vor* der Eingabe der Zugangsdaten feststehen.
- **CQL kappt bei `expand=body.*` still auf 50 Treffer** — ohne Hinweis in der Antwort. Ein Lauf,
  der Inhalte über die Suche mit Body-Expansion holt, endet mit 50 Dokumenten und meldet Erfolg.
  Die Regel „Suche und Auflistung liefern Kennungen, Inhalte werden einzeln geholt" ist deshalb
  Abnahmekriterium jedes Laufs, nicht Stilfrage.
- **Die Zielprüfung blockiert on-premises-Confluence im Normalfall.** `opaa.indexing.target-validation`
  (#267) ist standardmäßig aktiv und lehnt private Adressbereiche ab — genau dort steht ein
  Data-Center-Server. Ohne Eintrag in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` scheitert jeder
  Verbindungstest und jeder Lauf, und die Fehlermeldung muss diesen Eintrag benennen. (Der
  Gesamtschalter `OPAA_INDEXING_TARGET_VALIDATION_ENABLED` existiert ebenfalls; für ein Haus mit
  eigenem Confluence ist der gezielte Allowlist-Eintrag die richtige Empfehlung, nicht das
  Abschalten.)

## Entscheidung

### 1. Die Space-Auswahl ist eine Kindtabelle der Bibliothek — der erste Listenwert der Quellkonfiguration

Die ausgewählten Spaces werden in einer eigenen Tabelle `knowledge_library_confluence_spaces`
gehalten: je Zeile `library_id` (Fremdschlüssel auf `knowledge_libraries`, `ON DELETE CASCADE`),
`space_key` und `space_name` (Anzeigename zum Zeitpunkt der Auswahl, damit die Oberfläche die Auswahl
ohne erneuten Confluence-Aufruf zeigen kann), Primärschlüssel `(library_id, space_key)`. Eine
Confluence-Bibliothek trägt **mindestens einen** Space; die Anlage ohne Auswahl wird abgelehnt, weil
eine Bibliothek ohne Umfang nichts indizieren könnte und ein späterer Lauf das nicht als Fehler,
sondern als leeren Erfolg melden würde.

**Das ist ein ausdrücklicher Bruch mit ADR-0018, Entscheidung 1**, die Quellkonfiguration als
Einzelwerte an der Bibliothek kennt. Der Bruch ist eng: Die Bibliothek bleibt die *eine* Quelle —
Adresse, Zugangsdaten, Proxy, SSL-Schalter, Edition und Zeitplan stehen weiterhin als Einzelwerte an
`knowledge_libraries`, und die Kindtabelle trägt **nichts davon**. Sie ist keine Quellen-Tabelle im
Sinne der in ADR-0018 verworfenen Alternative (Konnektor → Quellen → Bibliotheken), sondern der Wert
*eines* Konfigurationsattributs, der zufällig eine Menge ist. Entsprechend bleibt der
Geltungsbereich der Abwesenheitsprüfung aus ADR-0017/Entscheidung 5 „je Bibliothek und Quellentyp"
— über denselben Dienst `StaleDocumentCleanupService#cleanupVanished`, ergänzt um die Betriebsart
des Laufs als Parameter (Entscheidung 4): Der Vollabgleich meldet die `file_path`-Werte aller Seiten
und Anhänge *der ausgewählten Spaces* als `currentFilePaths`; ein abgewählter Space steuert nichts
bei, seine Dokumente fehlen in der Menge und fallen weg. Es braucht keinen Space-Filter im
Aufräumcode, nur die Disziplin, dass der Lauf genau die aktuelle Auswahl aufzählt.

Die Space-Auswahl ist **nach der Anlage änderbar** (wie die übrige Konfiguration seit #516, anders als
Typ und Edition). Jede Änderung der Auswahl erzwingt als nächsten Lauf einen **Vollabgleich**
(Entscheidung 4); Hinzufügen und Entfernen wirken damit gleichzeitig — das Entfernen, weil nur der
Vollabgleich löschen darf. Die Oberfläche sagt das an der Stelle, an der die Auswahl geändert wird.

Jedes aus Confluence indizierte Dokument trägt seinen Space-Schlüssel als Metadatum (neben Seitentitel,
Gliederungspfad, Version und Confluence-URL, siehe Entscheidung 4) — nicht für die Löschung, die über
`currentFilePaths` läuft, sondern für Beleg, Laufprotokoll und die Anzeige „welcher Space, welcher
Pfad".

### 2. Die Edition steht an der Bibliothek, wird erkannt und ist danach unveränderlich

`knowledge_libraries` erhält eine Spalte `source_confluence_edition` mit den Werten `CLOUD` und
`DATA_CENTER` (`CHECK`-Constraint nach dem Migrationsmuster aus ADR-0017; `NOT NULL` genau dann, wenn
`source_type = 'CONFLUENCE'`, sonst `NULL`). Der neue Typ erweitert nach demselben
Drop-und-Neuanlage-Muster alle drei Wertelisten der Baseline — `chk_documents_source_type`,
`chk_knowledge_libraries_source_type` und den `CONFLUENCE`-Zweig von
`chk_knowledge_libraries_source_configuration` (URL Pflicht, Pfad verboten, Proxy und
`source_insecure_ssl` zulässig, Edition Pflicht) — und beide Enums, `DocumentSourceType`
(`io.opaa.api.types`) und `IndexingSourceType`, deren Grenze ADR-0017/0018 aufrechterhalten.
API-seitig ist die Edition ein Domain-Enum `ConfluenceEdition`, das nach ADR-0006 über
`typeMappings`/`importMappings` gemappt wird.

**Die gespeicherte Basisadresse ist die Confluence-Wurzel** — für Cloud die Site-Wurzel **ohne**
`/wiki` (`https://<site>` bzw. eine eigene Domain; der Adapter hängt `/wiki` selbst an), für Data
Center einschließlich eines etwaigen Kontextpfads (`https://wiki.example.org/confluence`). Die
Anlage normalisiert die eingegebene Adresse darauf (Schema und Host kleingeschrieben, kein
abschließender Schrägstrich, für Cloud ein angehängtes `/wiki` entfernt, keine Zugangsdaten im
`userinfo`-Teil) und lehnt ab, was sich nicht normalisieren lässt. Erkennung, API-Aufrufe und die
Dokumentidentität (Entscheidung 4) beziehen sich alle auf diese eine Form.

**Die Edition wird erkannt, nicht erraten.** Der Verbindungstest (#1134) ermittelt sie aus dem
Antwortverhalten der Instanz — ohne Zugangsdaten, allein aus der Adresse, in zwei Sonden:

1. `GET <Basisadresse>/_edge/tenant_info` — jede Cloud-Site antwortet darauf unauthentifiziert mit
   `200` und einem JSON-Objekt mit `cloudId`; Data Center kennt den Pfad nicht. Geprüft am
   03.09.2026 gegen eine öffentliche Cloud-Site: `200 {"cloudId":"…"}`, während
   `/wiki/api/v2/spaces` dort unauthentifiziert `404` und `/wiki/rest/api/space` `403` liefert —
   die Inhalts-Endpunkte sind als Signatur also **ungeeignet**, weil Cloud einen nicht angemeldeten
   Aufrufer nicht von einem nicht existierenden Pfad unterscheidet. (Die Beleg-Tabelle oben trägt
   den Stand der Herstellerdokumentation vom 01.09.2026; diese Signatur wurde zwei Tage später
   empirisch nachgeprüft, weil die Dokumentation sie nicht hergibt.)
2. Fehlt die Cloud-Signatur: `GET <Basisadresse>/status` — Data Center antwortet mit
   `{"state":"RUNNING"}` (bzw. einem anderen Zustand), und `GET <Basisadresse>/rest/api/space`
   antwortet mit `200` oder `401` im Data-Center-Fehlerformat. Trifft auch das nicht zu, ist die
   Adresse kein Confluence, und der Test sagt das — auch dann, wenn ein vorgeschalteter
   SSO-Reverse-Proxy jede Sonde auf eine HTML-Anmeldeseite umleitet: Eine Instanz, deren REST-API
   ohne Browser-Anmeldung nicht erreichbar ist, kann OPAA nicht indizieren, und die Meldung nennt
   diesen Fall.

Mit Zugangsdaten bestätigt der Test die erkannte Edition zusätzlich über den jeweiligen
Spaces-Endpunkt (`/wiki/api/v2/spaces?limit=1` bzw. `/rest/api/space?limit=1`). Erst nach der
Erkennung kann die Oberfläche die zur Edition passenden Zugangsdaten erfragen. Eine Erkennung über
den Hostnamen (`*.atlassian.net`) findet **nicht** statt: Sie wäre für Cloud hinter einer eigenen
Domain falsch und für Data Center hinter einem beliebigen Hostnamen ohne Aussage. Die Signaturen
sind im Testdoppel beider Editionen abgebildet, damit ein Wechsel im Verhalten der Hersteller-Seite
als Teständerung sichtbar wird und nicht als stille Fehlerkennung.

Die Anlage übernimmt die vom Verbindungstest erkannte Edition und **prüft sie erneut** gegen die
Instanz, bevor sie speichert — dieselbe zugangsdatenlose Signaturprüfung, ein Aufruf. Eine Anlage mit
einer Edition, die nicht zur Instanz passt, wird abgelehnt. Der Aufwand ist ein HTTP-Aufruf zu einer
Instanz, die die Anlage Sekunden vorher für die Space-Auswahl ohnehin erreichen musste; der Gewinn ist,
dass die Invariante „gespeicherte Edition = Edition der Instanz" nicht vom Verhalten des Clients
abhängt.

Nach der Anlage ist die Edition **unveränderlich, wie der Quellentyp selbst** (ADR-0018,
Entscheidung 1): Ein Änderungsversuch wird mit derselben Klasse verständlicher Fehlermeldung
abgelehnt, mit der heute ein Typwechsel abgelehnt wird. Ein Editionswechsel wäre in der Praxis eine
Migration der Instanz (Data Center → Cloud), bei der sich Seitenkennungen, URLs und Versionen
ändern — der Bestand einer Bibliothek wäre danach nicht mehr dem Bestand der Instanz zuzuordnen. Wer
die Edition ändern will, legt eine neue Bibliothek an.

Die Zugriffsschicht (#1132) ist ein **Port mit zwei Adaptern** (`ConfluenceClient` als Port;
je ein Adapter für Cloud gegen `/wiki/api/v2` und für Data Center gegen `/rest/api`), aufgelöst über
die gespeicherte Edition. Der Port liefert ein gemeinsames Zwischenmodell (Space, Seite mit
Kennung/Titel/Version/Vorfahren/Storage-Body, Anhang), an dem alle nachgelagerten Schritte —
Aufbereitung (#1137), Vollabgleich (#1136), inkrementeller Abgleich (#1139) — editionsunabhängig
arbeiten. Kein nachgelagerter Schritt unterscheidet die Edition; wo eine Unterscheidung nötig wird,
gehört sie in den Adapter. Beide Adapter werden gegen **dasselbe Testdoppel** abgenommen; ein Test,
der nur eine Edition abdeckt, gilt als unvollständig. Die Container-Suite gegen eine echte
Data-Center-Instanz (#1171) ist eine *zusätzliche* Ebene, die das Testdoppel nicht ersetzt — Cloud
lässt sich nicht containerisieren.

### 3. Zugangsdaten je Edition — in der bestehenden verschlüsselten Spalte, mit editionsabhängigem Format

Confluence-Zugangsdaten werden in der bestehenden Spalte `knowledge_libraries.source_credentials`
abgelegt — verschlüsselt ruhend über `SourceCredentialsConverter`/`CredentialsEncryptor`
(AES-256-GCM, Schlüssel aus `OPAA_CREDENTIALS_ENCRYPTION_KEY`, #483) — und über das bestehende,
nur-schreibende API-Feld `sourceCredentials` entgegengenommen. Das Format hängt von der Edition ab
und ist Teil der API-Dokumentation des Feldes:

- **Cloud:** `<E-Mail>:<API-Token>` — dieselbe `user:password`-Form, die `HTTP_DIRECTORY` und
  `RSS_FEED` heute für HTTP Basic verwenden, weil Cloud *genau das* ist: HTTP Basic mit der E-Mail als
  Benutzername. Die Oberfläche erfasst E-Mail und Token in zwei Feldern und fügt sie zusammen; der
  Adapter zerlegt am ersten Doppelpunkt (eine E-Mail-Adresse enthält in der Praxis keinen, ein
  Atlassian-API-Token auch nicht — der Verbindungstest weist eine Eingabe ohne Doppelpunkt für
  Cloud mit klarer Meldung zurück). Weil das Feld nur schreibbar ist, kann die Oberfläche später
  auch die E-Mail-Adresse nicht mehr anzeigen, obwohl sie kein Geheimnis ist — in Kauf genommen,
  um keinen zweiten Ablageweg zu eröffnen.
- **Data Center:** `<PAT>` allein, gesendet als `Authorization: Bearer`. Ein Doppelpunkt in dieser
  Eingabe ist mit hoher Wahrscheinlichkeit ein irrtümlich eingetragenes Cloud-Paar und wird mit
  entsprechendem Hinweis zurückgewiesen.

Die Regeln aus ADR-0018, Entscheidung 4 gelten unverändert und werden für diesen Typ verschärft
formuliert, weil er als erster Typ Zugangsdaten *aktiv prüft* (Verbindungstest, Space-Auflistung)
und damit neue Stellen schafft, an denen sie auftauchen könnten: **Zugangsdaten erscheinen in keiner
API-Antwort, keinem Log, keiner Exception-Message und keiner Fehlermeldung** — auch nicht in der
Fehlermeldung des Verbindungstests und nicht in verschachtelten Ursachen (`getCause()`). Die
Zugriffsschicht hält Zugangsdaten in einem eigenen Wertobjekt ohne `toString()`-Ausgabe des Inhalts
und setzt den `Authorization`-Header an genau einer Stelle. Ein Test der Zugriffsschicht prüft, dass
keine ihrer Exceptions den Token enthält.

Die Bindung der Zugangsdaten an den Ursprung (#516, `SourceOriginMatcher`: ein Wechsel von Schema,
Host oder Port verwirft gespeicherte Zugangsdaten) gilt auch hier; das Rotieren eines Tokens ist eine gewöhnliche
Konfigurationsänderung und braucht keine Neuanlage.

### 4. Löschsemantik je Lauf, nicht je Quellentyp — Ergänzung zu ADR-0017, Entscheidung 5

ADR-0017, Entscheidung 5 ordnet jeden Quellentyp bei seiner Registrierung genau einer Kategorie zu:
„vollständig auflistend" (Abwesenheit löscht) oder „ergänzend" (Abwesenheit löscht nie). Für
Confluence ist diese Zuordnung nicht je *Typ* möglich, weil derselbe Typ zwei Betriebsarten hat:

- **Vollabgleich (`FULL`)** listet alle Seiten und Anhänge der ausgewählten Spaces vollständig auf.
  Fehlt ein zuvor indiziertes Dokument in dieser **vollständigen** Auflistung, ist das eine
  verlässliche Aussage — gelöscht, in den Papierkorb verschoben, archiviert, in einen nicht
  ausgewählten Space verschoben, der Space abgewählt oder die Seite dem Token durch eine
  Seitenbeschränkung entzogen — und das Dokument wird aus dem Index genommen. Der letzte Fall ist
  gewollt: Es wird nie mehr im Index gehalten, als das konfigurierte Token lesen darf. Der
  Vollabgleich ist **vollständig auflistend**.
- **Inkrementeller Abgleich (`INCREMENTAL`)** fragt über CQL (`lastModified >= <Anker>`) nur nach
  Kennungen dessen, was sich seit dem letzten Lauf geändert hat, und holt diese Inhalte einzeln.
  Eine gelöschte Seite taucht in dieser Antwort **nicht** auf — sie ist keine Änderung, sie ist weg,
  und ihre Abwesenheit ist von der Abwesenheit jeder unveränderten Seite nicht zu unterscheiden. Der
  inkrementelle Abgleich ist **ergänzend** und löscht **nie** wegen Abwesenheit.

**Die Ergänzung zu ADR-0017:** Die Kategorie wird nicht mehr je Quellentyp, sondern **je Betriebsart
eines Laufs** deklariert. Dafür entsteht ein Enum `IndexingRunMode` (`FULL`, `INCREMENTAL`); der
Lauf trägt seine Betriebsart als Spalte an `indexing_jobs` (`run_mode`; bestehende Zeilen werden
je Typ ihrer Bibliothek nachgetragen — `RSS_FEED` als `INCREMENTAL`, alle anderen als `FULL` —,
damit der Altbestand zur Deklaration der Executoren passt), sichtbar im Laufprotokoll und in der
API (`IndexingRunResponse`). Ob eine
Betriebsart durch Abwesenheit löschen darf, **deklariert der Executor** — ADR-0017 verlangte diese
ausdrückliche Registrierung bereits, gebaut wurde sie bis heute nicht: Ob ein Typ löscht, entscheidet
sich daran, ob sein Executor `cleanupVanished` *aufruft* (Aufrufkonvention, dokumentiert in Prosa).
Mit Confluence hängt die Entscheidung erstmals von einem Laufzeitattribut ab, und damit wird die
Konvention zu Code: `SourceIndexingExecutor` erhält eine schmale Deklaration seiner unterstützten
Betriebsarten samt Löschkategorie, gegen die `StaleDocumentCleanupService` einen Aufruf aus einem
ergänzenden Lauf zurückweist. Für die bestehenden Typen ändert sich nichts Inhaltliches:
`FILESYSTEM` und `HTTP_DIRECTORY` kennen nur `FULL` (vollständig auflistend), `RSS_FEED` kennt nur
`INCREMENTAL` (ergänzend). Confluence ist der erste Typ mit beiden. Die Registrierung bleibt
ausdrücklich — es gibt weiterhin keinen impliziten Standardwert. Diese Ergänzung ist in ADR-0017 als
Nachtrag vermerkt.

**Der Geltungsbereich der Löschung** bleibt „je Bibliothek und Quellentyp" (ADR-0017,
Entscheidung 5; umgesetzt mit #877 in `cleanupVanished`) und ist für Confluence durch die Menge
`currentFilePaths` auf die **ausgewählten Spaces** begrenzt (Entscheidung 1): Ein Vollabgleich nimmt nur Dokumente aus dem Index, die (a)
dieser Bibliothek gehören, (b) aus Confluence stammen und (c) in der Auflistung der aktuell
ausgewählten Spaces nicht mehr vorkommen. Ein Dokument eines abgewählten Spaces erfüllt (c) und
verschwindet; ein Dokument einer *anderen* Confluence-Bibliothek gegen dieselbe Instanz erfüllt (a)
nicht und bleibt unberührt. Anhänge folgen ADR-0022: Der Lauf meldet die Pfade aller angetroffenen
Anhänge — auch die bereits vorhandener Anhänge einer unverändert übersprungenen Seite — in dieselbe
Menge. Ein Vollabgleich, der **null** Dokumente antrifft, löscht bewusst nichts (bestehender
Failsafe in `StaleDocumentCleanupService`, weil ein leerer Bestand von einer nicht erreichbaren
Quelle nicht zu unterscheiden ist); der Bestand einer vollständig geleerten Auswahl verschwindet über
das Löschen der Bibliothek, nicht über einen Lauf.

**Rechteentzug ist kein Löschbefund.** Confluence antwortet in beiden Editionen auf eine Seite, die
das konfigurierte Token nicht lesen darf, mit `404` — demselben Signal wie für eine gelöschte Seite
—, und ein Space, dessen Leserecht dem Token entzogen wurde, fehlt in der Space-Auflistung oder
antwortet mit `403`. Deshalb gilt: Verliert der Lauf den Zugriff auf einen **ganzen ausgewählten
Space** (`403`, Space nicht mehr auflistbar), gilt die Auflistung als **unvollständig**; der
Vollabgleich ruft `cleanupVanished` dann **nicht** auf, meldet den Space sichtbar im Laufprotokoll und
die Bibliothek zeigt den Zustand an — dieselbe Regel, die `UrlIndexingExecutor` mit
`truncated`/`incomplete` für ein nicht abrufbares Unterverzeichnis anwendet. Der Bestand dieses
Spaces bleibt so lange stehen, bis entweder das Token den Space wieder lesen darf oder eine Person
den Space aus der Auswahl nimmt; das ist eine bewusste Abwägung gegen den Grundsatz „nie mehr im
Index als das Token lesen darf": Ein Token-Fehler ist die weit häufigere Ursache als ein echter
Rechteentzug, und ein irrtümlich geleerter Bestand kostet Neuaufbau und bricht jeden Beleg. Die
Sichtbarkeit im Laufprotokoll ist der Ausgleich, nicht ein stilles Weiterführen.

**Ein explizites Lösch-Ereignis ist keine Ausnahme von der Regel, sondern ein Anlass zur Prüfung.**
Die Frage aus #1131, ob ein Webhook-Ereignis (`page_removed`, `page_trashed`) außerhalb eines
Vollabgleichs löschen darf, wird so entschieden: **Löschung braucht einen positiven Befund der
Instanz selbst.** Der Vollabgleich liefert ihn durch die vollständige Auflistung. Ein Webhook liefert
ihn *nicht* — er ist eine Nachricht eines Absenders, dessen Authentizität geprüft, dessen Inhalt aber
nicht bewiesen ist, und für Cloud stammt er aus einer Automation-Regel, nicht aus dem System selbst.
Ein Lösch-Ereignis stößt deshalb den **gezielten Einzelabruf** der gemeldeten Seite an; erst wenn die
Instanz die Seite ausdrücklich als im Papierkorb (`status = trashed`) ausweist, wird das Dokument
aus dem Index genommen — als verifizierte Löschung, protokolliert mit dem Anlass. Ein `404` ist
**kein** solcher Befund (siehe Rechteentzug oben): Die Seite bleibt bis zum nächsten Vollabgleich
stehen, der die Frage über die vollständige Auflistung entscheidet. Antwortet die Instanz mit der
Seite, war das Ereignis falsch oder veraltet, und nichts geschieht. Abwesenheit in einem
inkrementellen Fenster bleibt in jedem Fall kein Löschgrund. Dieselbe Regel gilt, wenn ein
inkrementeller Lauf beim Einzelabruf einer *geänderten* Seite `trashed` erhält: Das ist ein positiver
Befund und darf löschen — es ist nicht die Abwesenheit, vor der die Regel schützt.

Beide Pfade — verifiziertes Lösch-Ereignis und `trashed` im inkrementellen Lauf — entfernen ein
Elterndokument außerhalb von `cleanupVanished` und unterliegen deshalb der Auflage aus
[ADR-0022](0022-anhang-als-eigenes-dokument.md), Entscheidung 3: Sie löschen die Anhangszeilen der
Seite (`parent_document_id`) und deren Chunks ausdrücklich mit; eine Datenbank-Kaskade gibt es
bewusst nicht. Das ist Abnahmekriterium von #1139 und #1140, kein Umsetzungsdetail.

**Betriebsarten im Zeitplan.** Der Zeitplan je Bibliothek (#485) löst für Confluence standardmäßig
**inkrementelle** Läufe aus; ein **Vollabgleich** läuft zusätzlich in einem je Bibliothek
konfigurierbaren Rhythmus mit dem Standard *einmal wöchentlich*. Der erste Lauf einer Bibliothek und
der erste Lauf nach jeder Änderung der Space-Auswahl sind immer Vollabgleiche — ohne sie hätte der
inkrementelle Lauf keinen Anker bzw. wüsste nichts von abgewählten Spaces. Der manuelle Anstoß bietet
beide Betriebsarten an. Die konkrete Konfiguration trifft #1139; dieses ADR legt fest, dass der
Vollabgleich regelmäßig **nötig** bleibt (er ist der einzige Weg, nicht gemeldete Löschungen
nachzuvollziehen) und deshalb nicht abschaltbar, nur seltener stellbar ist. Weil
`uk_indexing_jobs_library_running` nur einen laufenden Job je Bibliothek zulässt und
`LibraryIndexingScheduler` verpasste Fälligkeiten bewusst nicht nachholt, gilt zusätzlich: Ein
fälliger Vollabgleich, der auf einen laufenden inkrementellen Lauf trifft, wird **vorgemerkt** und
mit dem nächsten Tick nachgeholt, nicht verworfen — ohne diese Vormerkung wäre der Vollabgleich
faktisch abschaltbar, durch Timing statt durch Konfiguration.

**Anker und Wiederaufnahme.** Der Laufzustand einer Confluence-Bibliothek lebt in einer eigenen
Zustandstabelle je Bibliothek (Vorbild `rss_feed_state`, dort bereits je `(library_id, feed_url)`
geschlüsselt; für Confluence genügt `library_id`, weil eine Bibliothek genau eine Instanz trägt —
zwei Bibliotheken gegen dieselbe Instanz haben getrennte Zustände, Entscheidung 5). Der
inkrementelle Lauf speichert dort nach erfolgreichem Abschluss den Zeitpunkt, ab dem der nächste Lauf
sucht — mit einer festen Überlappung nach hinten (Uhrenversatz zwischen OPAA und Instanz, Änderungen
während des Laufs). Ein abgebrochener Lauf verschiebt den Anker nicht; ein erneut geholter,
unveränderter Inhalt ist billig (Versionsvergleich vor dem Abruf des Bodys), ein verpasstes
Änderungsfenster wäre teuer. Ein Vollabgleich hält seinen Fortschritt je Space in derselben Tabelle
fest, damit ein Abbruch nicht von vorn beginnt; er ruft `cleanupVanished` **erst nach vollständiger
Auflistung aller ausgewählten Spaces** auf — ein Abbruch mitten in der Auflistung hat noch keinen
vollständigen Befund und darf deshalb nichts entfernen (dieselbe Regel, die `UrlIndexingExecutor`
mit `truncated`/`incomplete` bereits anwendet).

**Identität und Metadaten jedes Dokuments.** `documents.file_path` — die Identität eines Dokuments
je Bibliothek (`uk_documents_library_path`) und zugleich der Beleg-Link (`Document#getDeepLinkSourceUrl`)
— ist für eine Confluence-Seite eine **titelfreie, aus Basisadresse und Seitenkennung gebildete URL**
— Cloud `<Basis>/wiki/spaces/<Space-Schlüssel>/pages/<id>`, Data Center
`<Basis>/pages/viewpage.action?pageId=<id>` —, für einen Anhang seine Download-Adresse mit
Anhangskennung (ADR-0022, Entscheidung 2). Der von der API gelieferte `webui`-Link taugt dafür
**nicht**: Er enthält den Titel und ändert sich bei jeder Umbenennung. Die Seitenkennung ist damit
die stabile Identität über Umbenennungen und Verschiebungen innerhalb eines Spaces hinweg, und der
Beleg öffnet die Seite ohne Zusatzspalte. Bei Cloud ist der Space-Schlüssel Teil der Adresse; eine
Seite, die in einen *anderen* ausgewählten Space verschoben wird, erscheint dem Vollabgleich als neu
und die alte als verschwunden — ein Neuaufbau ihrer Zerlegung, kein Datenverlust, und für den
Rechteanker der Bibliothek der ehrlichere Befund.
`Document#getDeepLinkSourceUrl` wird um `CONFLUENCE` erweitert, sonst zeigt weder Beleg noch
Dokumentliste einen Link. `file_name` trägt den Seitentitel (heute schon die Quelle des abgeleiteten
Dokumenttitels),
`last_modified_remote` die Versionsnummer — das Änderungsmerkmal im Sinne von ADR-0017,
Entscheidung 2, geprüft **vor** dem Abruf des Inhalts. Space-Schlüssel und Gliederungspfad (Titel
der Vorfahren) bekommen zwei neue, für alle anderen Typen leere Spalten an `documents`
(Arbeitstitel `source_container_key`, `source_hierarchy_path`), damit Laufprotokoll, Beleg und
Chunk-Kontext sie ausgeben können, ohne die Instanz zu fragen. Eine Abbildung der Seitenhierarchie
auf Bibliotheksordner (ADR-0020) findet **nicht** statt (siehe „Ausdrücklich offen").

### 5. Folgen des Mehrfach-Bibliotheken-Modells

Aus der Rahmenbedingung „beliebig viele Confluence-Bibliotheken, auch gegen dieselbe Instanz" folgt:

- **Keine Eindeutigkeitsregel auf Adresse oder Token.** Weder `source_url` noch `source_credentials`
  noch ihre Kombination sind je Organisation eindeutig. Zwei Bibliotheken dürfen dieselbe Adresse mit
  demselben Token tragen.
- **Zugangsdaten strikt je Bibliothek, kein geteiltes Verbindungsobjekt.** Es gibt keine Tabelle
  „Confluence-Verbindung", auf die mehrere Bibliotheken zeigen. Das ist die konsequente Fortsetzung
  von ADR-0018 (die Bibliothek *ist* die Quelle) und hat einen benannten Preis: Wer ein Token
  rotiert, das in fünf Bibliotheken hinterlegt ist, rotiert es fünfmal. Das ist akzeptiert, weil das
  geteilte Objekt ein eigenes Rechtemodell bräuchte (wer darf die Verbindung ändern, die eine fremde
  Bibliothek benutzt?) und genau die Kopplung zwischen Bibliotheken einführte, die dieses Modell
  vermeidet. Auch Webhook-Geheimnisse (#1140) sind je Bibliothek.
- **Überlappende Space-Auswahlen indizieren doppelt.** Enthalten zwei Bibliotheken denselben Space,
  wird er zweimal geholt und zweimal indiziert; es findet **keine** Deduplizierung über
  Bibliotheksgrenzen statt. Jede Bibliothek hat ihren eigenen Bestand, ihren eigenen Lauf, ihren
  eigenen Anker. Das ist beabsichtigt: Die beiden Bibliotheken haben verschiedene Leserkreise, und
  ein gemeinsamer Bestand würde die Freigabe der einen zur Freigabe der anderen machen.
- **Läufe verschiedener Bibliotheken beeinflussen einander nicht** — mit einer Ausnahme, die benannt
  wird: Sie teilen sich das Ratenbudget der Instanz. Zwei gleichzeitige Läufe gegen dieselbe
  Cloud-Instanz werden von ihr gemeinsam gebremst (`429`/`Retry-After`), und jeder Lauf beachtet die
  Bremse für sich. Eine instanzweite Koordination der Läufe (gemeinsame Warteschlange je Hostname)
  wird **nicht** gebaut, solange keine Messung gegen eine reale Instanz (#1141) ihren Bedarf belegt.
- **Die Zielprüfung gilt je Bibliothek.** Eine Allowlist-Freigabe für eine on-premises-Adresse wirkt
  für alle Bibliotheken gegen diese Adresse — sie ist eine Betriebseinstellung, keine Eigenschaft der
  Bibliothek.

Das Beispiel aus den Rahmenbedingungen (fünf Bibliotheken über zwei Instanzen mit drei Tokens) ist
mit diesen Regeln abbildbar und wird in #1133 als Test festgehalten.

## Ausdrücklich offen

- **Die Obergrenze der Freigabe** (#797). Dieses ADR beschränkt sich darauf, *nicht mehr* zu
  indizieren, als das konfigurierte Token lesen darf, und die Freigabefolge an der Oberfläche sichtbar
  zu machen (Anlage vor der Space-Auswahl, Bibliotheksansicht dauerhaft, Laufprotokoll für
  Übersprungenes — #1135, #1138). Was eine Bibliothek höchstens freigeben darf, bleibt dort zu
  entscheiden.
- **Rechteabbildung Confluence → OPAA.** Confluence-Gruppen oder Space-Berechtigungen auf
  OPAA-Rechte abzubilden (`knowledge-sources.md`, „Spiegelung der Rechte", Option 2), wäre ein eigenes
  Epic mit eigener Rechtequelle.
- **Weitere Inhaltsarten** (Blogbeiträge, Kommentare, Whiteboards, Datenbanken) und weitere
  Atlassian-Produkte. Die Zugriffsschicht wird nicht auf Vorrat verallgemeinert.
- **Archivierte Seiten** (`status = archived`, Cloud). Sie sind nicht Teil der aktuellen
  Auflistung und werden deshalb wie gelöschte behandelt — nicht indiziert, beim Vollabgleich
  entfernt. Ob ein Archiv später als eigener, gekennzeichneter Bestand aufgenommen wird
  (Lebenszyklus „archiviert" in `knowledge-sources.md`), bleibt offen.
- **Seitenhierarchie als Bibliotheksordner.** ADR-0020 kennt Ordner als nutzerverwaltete Navigation
  (umbenennen, verschieben, löschen samt Inhalt). Ein Lauf, der die Confluence-Hierarchie als Ordner
  nachbaut, konkurrierte mit dieser Verwaltung. Dieses ADR hält den Gliederungspfad als Metadatum;
  ob er später Ordner speist, ist eine eigene Entscheidung.
- **Konkrete Grenzwerte** — Anfragebudget je Lauf, Überlappung des Ankers, Rhythmus des
  Vollabgleichs — werden mit einer Messung gegen eine reale Instanz begründet (#1141), nicht hier
  geraten.

## Konsequenzen

### Einfacher

- **Ein Lauftyp mit zwei Betriebsarten ist modellierbar**, ohne ADR-0017 zu brechen: Die
  Registrierung deklariert die Löschsemantik weiterhin ausdrücklich, nur eine Stufe feiner. Ein
  künftiger Typ mit demselben Muster (jedes Quellsystem mit Änderungssuche) findet die Struktur vor.
- **Die Löschregel ist ein Satz:** Löschung braucht einen positiven Befund der Instanz. Sie
  beantwortet den Vollabgleich, den inkrementellen Lauf, den Webhook und den `404` beim Einzelabruf
  gleich, statt vier Sonderfälle zu führen.
- **Beide Editionen teilen alles oberhalb des Adapters.** Aufbereitung, Läufe, Löschung und
  Oberfläche kennen die Edition nicht; ein Fehler in der Editionsbehandlung ist im Adapter lokalisiert
  und gegen das Testdoppel beider Editionen prüfbar.
- **Zugangsdaten brauchen keinen neuen Weg.** Verschlüsselung, Nur-Schreiben-Feld, Ursprungsbindung
  und der Bearbeitungsdialog aus #483/#516 gelten unverändert.

### Schwieriger

- **Die Constraint `chk_knowledge_libraries_source_configuration` wird komplexer:** Sie muss für
  `CONFLUENCE` die Edition erzwingen und für alle anderen Typen verbieten, zusätzlich zur bestehenden
  Regel für Pfad und URL. Jede Erweiterung folgt dem Drop-und-Neuanlage-Muster mit Delta-Test unter
  `io.opaa.migration`.
- **Ein `INCREMENTAL`-Lauf kann Löschungen verschleppen** — bis zum nächsten Vollabgleich, im
  Standard bis zu einer Woche. Das ist der Preis der günstigen Änderungssuche und muss im
  Betriebshandbuch (#1142) und an der Bibliothek stehen, damit niemand einen inkrementellen Lauf für
  einen vollständigen hält.
- **`indexing_jobs` bekommt eine Betriebsart und `SourceIndexingExecutor` eine Deklaration**, die
  Laufprotokoll, Zeitplan und manueller Anstoß kennen müssen — auch für die drei Typen, die nur eine
  Betriebsart haben. Der Umbau ist klein, aber er fasst alle Executoren an.
- **`documents` wächst um zwei typspezifische Spalten**, die für vier von fünf Typen leer bleiben.
  Das ist der Preis dafür, dass Beleg und Laufprotokoll ohne Rückfrage an die Instanz auskommen.
- **Die Anlage ist netzabhängig:** Erkennung der Edition, Space-Auflistung und die erneute Prüfung
  bei der Anlage brauchen die Instanz. Eine nicht erreichbare Instanz macht die Anlage unmöglich, nicht
  nur den Lauf — anders als bei `HTTP_DIRECTORY`/`RSS_FEED`, wo ein Verbindungstest optional ist.
- **Doppelte Indizierung überlappender Spaces kostet doppelt** — Abrufe, Einbettungen, Speicher —
  und zählt zweimal gegen das Ratenbudget der Instanz.
- **Ein Adresswechsel baut den Bestand neu auf.** Weil `file_path` die Basisadresse enthält, sieht
  der erste Vollabgleich nach einem Umzug der Instanz (anderer Host, Schema oder Kontextpfad) nur
  neue Pfade, entfernt den Altbestand und bettet alles neu ein. Das ist dieselbe Eigenschaft, die
  `HTTP_DIRECTORY` und `RSS_FEED` heute haben; eine adressunabhängige Identität wurde verworfen
  (siehe unten), der Preis steht hier.
- **Ein Space ohne Leserecht bleibt indiziert**, bis eine Person eingreift (Rechteentzug ist kein
  Löschbefund, Entscheidung 4). Das Laufprotokoll und die Bibliotheksansicht müssen diesen Zustand
  so deutlich zeigen, dass er nicht wochenlang unbemerkt bleibt (#1138).

## Verworfene Alternativen

**Eine Bibliothek je Space, verbunden über ein gemeinsames Verbindungsobjekt.** Würde die
Freigabefolge entschärfen (je Space ein eigener Leserkreis) und Tokens zentral rotierbar machen.
Verworfen als Rahmenbedingung des Maintainers: Confluence ist *eine* Datenquelle, und das
Verbindungsobjekt wäre genau die Konnektor-Tabelle, die ADR-0018 verworfen hat, mit eigenem
Rechtemodell und eigener Oberfläche. Wer getrennte Leserkreise braucht, legt getrennte Bibliotheken an —
der Preis ist die Mehrfacheingabe der Zugangsdaten, nicht eine zweite Objektklasse.

**Keine Space-Auswahl — die Bibliothek indiziert alles, was das Token lesen darf.** Schemafrei,
ohne Bruch mit ADR-0018. Verworfen, weil der Umfang der Bibliothek dann nicht mehr von OPAA, sondern
von der Rechteverwaltung in Confluence bestimmt würde: Jede Erweiterung der Token-Rechte weitete
still den Bestand aus, den alle Leseberechtigten der Bibliothek sehen — genau die Freigabefolge, die
#797 begrenzen soll, ohne Sichtbarkeit an der Stelle der Entscheidung.

**Space-Auswahl als Textspalte (JSON-Array oder kommagetrennt) an `knowledge_libraries`.** Bleibt
formal bei „Einzelwerte an der Bibliothek" und spart eine Tabelle. Verworfen, weil die Datenbank
dann weder Eindeutigkeit je Bibliothek noch Nichtleere prüfen kann, jede Abfrage „welche Bibliotheken
enthalten Space X" die Spalte parsen müsste, und ein späterer Fortschrittsstand je Space (für die
Wiederaufnahme des Vollabgleichs) keinen Ort hätte. Der Bruch mit ADR-0018 findet so oder so statt —
ein Listenwert ist ein Listenwert —, und er soll als Struktur sichtbar sein, nicht in einem String
versteckt.

**Edition aus dem Hostnamen ableiten oder vom Nutzer wählen lassen.** Billiger als eine Erkennung.
Verworfen: `*.atlassian.net` ist weder notwendig (Cloud hinter eigener Domain) noch hinreichend, und
eine Nutzerwahl ist genau das Raten, das das Epic ausschließt — wer die Edition falsch wählt, bekommt
Zugangsdatenfelder, mit denen sich die Instanz nicht anmelden lässt, und eine Fehlermeldung, die auf
die falsche Ursache zeigt.

**Edition änderbar lassen.** Würde eine Data-Center-Instanz, die nach Cloud migriert, ohne Neuanlage
weiterführen. Verworfen, weil nach einer solchen Migration Kennungen, URLs und Versionen des Bestands
nicht mehr zur Instanz passen; die Bibliothek trüge einen Bestand, für den der Vollabgleich der neuen
Edition keine Aussage treffen kann — dieselbe Vermengung, die ADR-0018 beim Typwechsel verbietet.

**Eigene Spalten für E-Mail und Token statt der bestehenden Zugangsdaten-Spalte.** Klarer im
Schema. Verworfen, weil es einen zweiten verschlüsselten Pfad, eine zweite Ursprungsbindung und einen
zweiten Bearbeitungsweg neben `source_credentials` erzeugte — für ein Format, das bei Cloud ohnehin
HTTP Basic und damit `user:password` ist. Die Oberfläche trennt die Felder; die Ablage nicht.

**Löschsemantik weiterhin je Typ, Confluence als „ergänzend" registriert.** Der geringste Eingriff in
ADR-0017. Verworfen, weil eine in Confluence gelöschte Seite dann **nie** aus dem Index verschwände —
kein Lauf dürfte löschen. Ein Konnektor gegen ein Wiki, in dem Löschen Alltag ist, wäre damit nach
Monaten voller Geisterseiten.

**Löschsemantik weiterhin je Typ, Confluence als „vollständig auflistend" registriert und jeder Lauf
ein Vollabgleich.** Ebenfalls ohne Änderung an ADR-0017. Verworfen, weil ein stündlicher Vollabgleich
gegen eine Instanz mit hunderttausend Seiten Cloud-Punktebudget und Data-Center-Server gleichermaßen
überfordert; die Änderungssuche existiert genau deshalb.

**Papierkorb-Abfrage im inkrementellen Lauf.** Beide Editionen erlauben eine gezielte Abfrage nach
Inhalten im Papierkorb (`status=trashed`); ein Zusatzaufruf je Space könnte viele Löschungen schon im
inkrementellen Fenster sichtbar machen. Verworfen als Ersatz für den Vollabgleich, weil sie nur den
Papierkorb sieht — nicht endgültig gelöschte, archivierte, in andere Spaces verschobene oder per
Seitenbeschränkung entzogene Inhalte. Als Beschleuniger neben dem Vollabgleich bleibt sie #1139
freigestellt.

**Adressunabhängige Dokumentidentität (`confluence:<Space>:<Seitenkennung>` als `file_path`,
Beleg-URL berechnet).** Würde einen Umzug der Instanz ohne Neuaufbau überstehen. Verworfen, weil
`file_path` heute für jeden URL-basierten Typ zugleich Identität und Beleg-Link ist
(`Document#getDeepLinkSourceUrl`) und ein synthetisches Schema eine zweite Spalte für den Link
sowie Sonderbehandlung in Dokumentliste und Beleg-Anzeige bräuchte; der Umzug einer Instanz ist
selten, der Neuaufbau ein einmaliger Preis.

**Webhook-Lösch-Ereignisse löschen unmittelbar.** Schnellste Aktualität. Verworfen, weil das
Ereignis den Befund nicht ersetzt: Ein falsch konfigurierter, veralteter oder — bei Cloud über eine
Automation-Regel — gänzlich fremder Absender könnte den Index leeren. Der Einzelabruf kostet einen
Aufruf und macht aus der Nachricht einen Befund.

**Eine instanzweite Ratenkoordination über Bibliotheksgrenzen.** Wäre die saubere Antwort auf
mehrere Bibliotheken gegen dieselbe Cloud-Instanz. Verworfen für jetzt, weil sie eine geteilte
Struktur je Hostname einführt, deren Bedarf nicht gemessen ist; `Retry-After` je Lauf ist die
Mindestlösung, und #1141 misst, ob mehr nötig ist.
