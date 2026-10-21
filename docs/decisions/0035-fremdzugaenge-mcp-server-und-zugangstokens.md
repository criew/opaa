# ADR-0035: Fremdzugänge — ein MCP-Server über Streamable HTTP, persönliche Zugangstokens und die Freigabe je Wissensbibliothek

## Status

Akzeptiert · zuletzt geändert 2026-10-21 · ergänzt ADR-0005, ADR-0033 · Entscheidung 5 umformuliert durch ADR-0039

## Kontext

Beschäftigte arbeiten in KI-Werkzeugen, die OPAA nicht kennen, und holen sich das Wissen des Hauses
über die Zwischenablage dorthin. Der Kanal, der das ablöst, ist lesend, liegt hinter drei
Freigaben (Installationsschalter, Bibliotheksfreigabe, Token) und liefert Fundstellen statt
erzeugter Antworten; sein **Verhalten** steht in
[external-access.md](../features/external-access.md). Randbedingungen: OPAA wird für diesen Kanal
nicht aus dem offenen Netz erreichbar gemacht (Zielclients auf dem Arbeitsplatz oder im Hausnetz,
installationsweite Netzbeschränkung); die einzelne Abfrage wird nicht protokolliert
(`security-and-compliance.md`); das Rechtemodell hängt an der Wissensbibliothek, und die
Rechtehistorie historisiert Reichweitenfelder als Intervalle. Die MCP-Spezifikation `2026-07-28`
handelt die Fassung je Anfrage aus und kündigt den HTTP+SSE-Transport und die dynamische
Client-Registrierung ab; die eingesetzte Bibliothek (Spring AI, `mcp-core`) spricht nur die
handshake-basierten Revisionen bis `2025-11-25`, beantwortet `tools/list` aus einer serverweiten
Liste und legt einen **unauthentifizierten** Endpunkt an — der Prüfweg entsteht in unserer
Filterkette. Das ADR setzt den Maintainer-Beschluss „Fundament zuerst" (18.09.2026, Epic #1715,
Issue #1716) nach fünf Stakeholder-Bewertungen um.

## Entscheidung

### 1. Ein MCP-Server im Backend, Streamable HTTP in der zustandsfreien Betriebsart, Pfad `/mcp`

Genau ein Server für alle Clients, gebaut mit dem Spring-AI-Starter (`spring.ai.mcp.server.protocol
= STATELESS`, `type = SYNC`, Pfad aus `streamable-http.mcp-endpoint` — eine Gruppe
`…stateless.mcp-endpoint` gibt es nicht, ein Schlüssel dort würde still ignoriert), nur die
Fähigkeit Tools. Zustandsfrei, weil jede Anfrage ihr Bearer-Merkmal trägt und eine Sitzung nur
prozesslokalen Zustand, eine scheinbar plausible Prüfung „je Verbindung", eine Sitzungstabelle und
einen unbeobachtbaren Lebenszyklus brächte; ohne Sitzungen ist „eine offene Sitzung überdauert den
Notaus nicht" ein Zustand, den es nicht gibt. Zwei Zusagen liefert die Bibliothek nicht mit und
`OpaaMcpHandler` (dekorierender `McpStatelessServerHandler`) baut sie: `tools/list` wird je Anfrage
aus der effektiven Sicht des Tokens beantwortet, und keine Bibliothek außerhalb der Sicht wird
genannt; `initialize` trägt die Anweisungen der laufenden Installation. `McpEndpoint` leitet
Pfad und Treffer aus derselben Eigenschaft ab, damit Filterkettenregel und Endpunkt nicht
auseinanderlaufen. Die Wegeliste des Sicherheits-Wächters führt `/mcp` ausdrücklich
(`ExternalAccessPathAllowlist`, `ExternalAccessSecurityConfig`); ein Aufruf ohne Merkmal kommt nicht
durch. Die Bibliotheksversion ist nicht Teil der Entscheidung.

### 2. Authentifizierung ist ein persönliches Zugangstoken: undurchsichtig, mit Präfix, nur gehasht gespeichert

`Authorization: Bearer opaa_pat_<Zufall>` (`ExternalAccessTokenValues`, 256 Bit aus einem
kryptografisch sicheren Zufallsgenerator, Base64url). Gespeichert wird in `external_access_tokens`
nur der Suchwert HMAC-SHA256 unter einem HKDF-abgeleiteten Zweckschlüssel
(`LocalAuthKeyService#lookupHash`, `Purpose.EXTERNAL_ACCESS_TOKEN_LOOKUP`) — dieselbe Hausmechanik
wie die Refresh- und Aktionstoken aus ADR-0033, Entscheidung 6; kein Passwort-KDF (läge auf dem
heißen Pfad jedes Aufrufs und schützt gegen einen Angriff, den ein Zufallsmerkmal nicht hat), kein
schlüsselloser SHA-256 (ein Bearer-Geheimnis gehört unter den Schlüssel). **Folge:** Eine Rotation
von `OPAA_AUTH_JWT_SECRET` entwertet alle Zugangstokens auf einmal; das gehört in die
Betriebsdokumentation neben die übrigen Folgen der Rotation. Der Klartext ist genau einmal
sichtbar; das Präfix dient der Wiedererkennung und für Geheimnis-Scanner und erscheint **nicht** in
Anwendungs- oder Proxy-Logs (es ergäbe mit Zeitstempel und Netzadresse die Abfragehistorie, die das
Nachweisprotokoll ausschließt). Ausstellung, Ablauf und Sofortwiderruf folgen derselben Prüfkette
wie die lokalen Tokens, in eigener Ablage. Die Netzbeschränkung gehört dem Kanal
(`ExternalAccessNetworkPolicy`, Vorgabe Hausnetz), nicht dem Token: Eine CIDR je Token wäre ein
Anwesenheitsmerkmal (Dienststelle gegen Heimarbeit).

### 3. OAuth 2.1 mit Autorisierungsserver und dynamischer Client-Registrierung wird zurückgestellt

Nichts davon wird gebaut, wegen der Betriebsform, nicht des Aufwands: Es löst „ein fremd betriebener
Dienst soll im Namen einer Person zugreifen", und dieses Problem hat der Kanal nicht; ein
Autorisierungsserver ohne öffentliche Erreichbarkeit ist Apparat ohne Gegenwert. Die dynamische
Client-Registrierung ist zudem mit `2026-07-28` abgekündigt. Neu zu treffen, sobald ein fremd
betriebener Dienst angeschlossen oder die Installation bewusst öffentlich erreichbar gemacht wird —
zuerst eine Entscheidung über die Erreichbarkeit. Werkzeuge, die nur OAuth mit dynamischer
Registrierung sprechen, sind nicht anschließbar; der Prüfweg in der Filterkette lässt sich um ein
zweites Verfahren ergänzen. Die Gegenrichtung — OPAA als MCP-**Client** gegen fremde MCP-Server mit
OAuth und Ressourcen-Indikator — ist das MCP-Profil in ADR-0041, Entscheidung 5.

### 4. Freigabe-Einheit ist die Wissensbibliothek, und die Freigabe ist ein historisiertes, befristetes Reichweitenfeld

Freigegeben wird die Bibliothek — nicht der Space (eine Sicht, durch eine zweite Sicht umgehbar),
nicht das Dokument (bei Chunk-Treffern nicht durchsetzbar), nicht die Person (eine zweite
Rechtewahrheit neben den Leserechten). Die Freigabe ist ein Reichweitenfeld
(`external_access_state`, `external_access_expires_at` in `asset_visibility_history`) und wird
historisiert, nicht nur protokolliert — mit ADR-0016 und ADR-0032 —, damit „war dieser Bestand 2026
aus dem Haus erreichbar?" auch nach der Löschfrist des Nachweisprotokolls beantwortbar bleibt. Sie
ist pflichtbefristet auf höchstens `opaa.external-access.max-release-days` (365) mit
Wiedervorlage per Mail (`reminder-lead-days` 14, Mailweg aus ADR-0033); eine unbefristete Freigabe
wäre eine Ratsche. Seit ADR-0037 und ADR-0039 ist sie das einzige Reichweitenfeld neben den Grants.
Freigaberecht: Verwalter-Rolle der Bibliothek plus Systemverwaltung, keine Genehmigungsstufe —
Befristung, Historisierung und die angezeigte Anzahl der Tokens, die die Bibliothek enthalten
(`tokenCount`), sind die Antwort auf das dahinterliegende Anliegen. Eine zurückgenommene oder
erloschene Freigabe entzieht die Bibliothek allen Tokens sofort und lebt in bestehenden Tokens nicht
wieder auf. Eine Bibliothek im Zustand „Nachfolge offen" ist vom Weiten der Freigabe gesperrt
(`LibraryExternalAccessService` prüft `requireAssetReachNotFrozen`, ADR-0036, Entscheidung 6);
Zurücknehmen und Verkürzen bleiben möglich.
Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken deckelt dieses Merkmal **nicht**
(Maintainer-Festlegung in #797 vom 21.09.2026: sie gilt allein der Freigabe an „Alle Konten");
der Schutz gegen „eingespeist, dann über den Fremdzugang breit erreichbar" liegt in Befristung und
MANAGER-Schranke.

### 5. Genau ein Zugriffsweg auf die Daten — und die Prüfung geschieht je Werkzeugaufruf

Die Werkzeuge `search`, `fetch`, `list_libraries` (`McpToolCatalog`) und `POST /api/v1/search` rufen
dieselben Domain-Dienste wie `POST /api/v1/query`; kein eigener Zugriff auf `vector_store`, keine
zweite Rechtelogik, kein zweiter Rankingpfad. Die effektive Sicht — Rechte der Person ∩
Bibliotheksfreigabe ∩ Auswahl im Token ∩ Installationsschalter — entsteht bei jedem Aufruf, nie
eingefroren; der Rechtefilter sitzt in der Suche, nicht davor. Abgesichert durch einen Strukturtest
(keine Abhängigkeit der Werkzeug- und Endpunktklassen auf `VectorStore` oder Index-Repositories) und
Tests, die jeden Faktor einzeln entziehen. Seit ADR-0039 lautet die Gleichheitszusage: **Beide Kanäle
laufen über denselben Suchweg mit derselben Rechteprüfung; was sie durchsuchen, bestimmt im Chat die
Zuordnung des Space, im Fremdzugang die Auswahl des Tokens** — der Fremdzugang bleibt bewusst ohne
Space. Der Kanal erbt die Last des vollen Suchwegs je Aufruf, daher das Kontingent je Token
(`ExternalAccessQuota`, gleitendes Stundenfenster im Arbeitsspeicher jeder Instanz, die den Wert
der Einstellung durch die lebenden Instanzen teilt; auf den REST-Lesewegen zählt ein Tokenaufruf
dazu gegen die Grenzen je Token und je Person (alle ihre Tokens) und die Gesamtgrenze der Suche,
nicht gegen die seiner Netzadresse, weil hinter einem gemeinsamen Ausgang sonst ein Skript alle
anderen aussperrt; eine Abweisung durch das Kontingent gibt die Gesamtmarke zurück) und der Abflussalarm
(`ExternalAccessMassRetrievalAlarm`, ein Zähler des ganzen Kanals in der Datenbank, Meldung mit
Token-Kennung und Fenster, aber ohne Messwerte, nach `ExternalAccessAlertRetention` gelöscht); beide
nach ADR-0053 Entscheidung 12. `spring.ai.mcp.server.request-timeout` wirkt nur sitzungsbehaftet; ein Deckel
gehört auf die HTTP-Ebene oder in den Domain-Dienst.

### 6. Der Fassungswechsel der MCP-Spezifikation ist geplant, nicht abgewartet

Weil es einen Server gibt, fallen bei einem Bruch alle Fremdzugänge des Hauses zugleich aus.

- **a) Was der Server aushandelt und ankündigt:** `opaa.mcp.protocol-versions` (`McpProperties`,
  Vorgabe `2025-03-26`, `2025-06-18`, `2025-11-25`; `2024-11-05` bewusst nicht, weil der
  ausgelieferte Transport es nicht bedient) — an einer Stelle, die nicht die Bibliothek ist, damit
  „welche Fassung spricht diese Installation?" aus Konfiguration und Handbuch zu beantworten ist.
- **b) Verhalten bei Fassungsabweichung:** klare Ablehnung mit aufgezählten Fassungen, nie stilles
  Weiterlaufen unter einer anderen Fassung. Die Form richtet sich nach der Ära, die OPAA selbst
  spricht: `initialize` mit unbekannter Fassung wird von `OpaaMcpHandler` abgelehnt und nennt die
  Liste; eine moderne Anfrage mit `MCP-Protocol-Version`-Header wird von `McpProtocolVersionFilter`
  mit `400` und Klartextkörper **ohne** modernen Fehlerkörper (`-32022`) abgewiesen — ein
  Dual-Era-Client erkennt daran, dass er auf `initialize` zurückfallen soll. Der moderne Fehlerkörper
  kommt erst mit der modernen Aushandlung.
- **c) Abkündigungsfrist:** OPAA bedient eine abgekündigte Fassung mindestens zwölf Monate ab der
  ersten Auslieferung der Nachfolgefassung, nie kürzer als die „earliest removal" der
  MCP-Lebenszyklus-Richtlinie; unterschritten nur bei einer Sicherheitslücke, mit Ankündigung.
- **d) Fassungswache:** Eine benannte Rolle (Maintainer oder Benannter) prüft vierteljährlich
  Spezifikationsstand und Abkündigungsliste, die Fassungsliste der Bibliothek und das
  Konfigurationsformat der Client-Anleitungen im [Handbuch](../handbuch/fremdzugaenge.md); das
  Ergebnis wird festgehalten. Renovate leistet das nicht; ein Versionssprung des MCP-Starters ist
  kein gewöhnliches Update und gehört in `renovate.json5` in eine Regel ohne Auto-Merge.

## Konsequenzen

- **Einfacher:** Ein Kanal für alle Werkzeuge; eine Rechtewahrheit; Nachweisbarkeit ohne
  Verhaltensdaten (Rechtehistorie und Ereignisliste); ein Notaus, der mit der nächsten Anfrage
  wirkt, ohne ein Token zu vernichten; Such- und Abrufweg stehen auch angemeldeten Personen offen.
- **Schwieriger:** Ein einziger Ausfallpunkt mit einem Störungsbild, das im fremden Client entsteht
  („MCP server failed") und das die Störungssuche im Handbuch führt; eine wiederkehrende, nicht
  automatisierbare Pflegelast; ein in der Voreinstellung unauthentifizierter Endpunkt, dessen
  Absicherung vollständig bei uns liegt; eine jährlich zu erneuernde Freigabe; eine faktisch
  unumkehrbare Freigabeentscheidung — was in ein fremdes Werkzeug geholt wurde, bleibt dort.
- **Neutral:** Kein dritter Betriebsmodus der Authentifizierung (ADR-0005); die Werkzeugnamen
  `search`/`fetch` sind die Signatur, die gängige Assistenzwerkzeuge als Wissensquelle erkennen,
  ohne Zusage eines Anschlusses an einen fremd betriebenen Dienst.

## Verworfene Alternativen

- Eine Erweiterung je Werkzeug — vier Artefakte mit vier Lebenszyklen für denselben Zuschnitt.
- stdio-Server je Arbeitsplatz — ein Sicherheitsfehler wäre auf tausend Geräten zu beheben.
- SSE-Transport — seit `2025-03-26` abgekündigt.
- Sitzungsbehaftete Betriebsart `STREAMABLE` — Benachrichtigungen und Rückfragen braucht der Kanal nicht; `tools/list` käme ohnehin aus derselben serverweiten Liste.
- Selbsttragendes JWT — Widerruf bräuchte eine `jti`-Sperrliste je Aufruf, und das Merkmal trüge eine Kopie der Rechtelage.
- Clientzertifikate (mTLS), OIDC-Zugangstoken der Web-Sitzung weiterreichen — verteilungsaufwendig bzw. kurzlebig mit vollem Rechteumfang.
- CIDR je Token — Anwesenheitsmerkmal.
- Eigener, für Werkzeuge optimierter Lesepfad — zweite Qualitäts- und Rechtewahrheit.
- OpenAI-kompatibler `/v1/chat/completions`, Werkzeug `ask` — reduziert Belege auf ein Textfeld bzw. hängt den Modellverbrauch an ein fremdes Werkzeug.
- Nachweis der abgerufenen Fundstellen — ein Tätigkeitsprofil je Person; das fremde Werkzeug führt sein eigenes Protokoll.
- Service-Accounts ohne Person, schreibende Fremdzugänge — Zielbild bzw. eigene Missbrauchsfläche; das Handbuch rät davon ab, ein Fachverfahren an ein persönliches Token zu hängen.

## Offen

- OAuth 2.1 für fremd betriebene Dienste (Entscheidung 3), sobald die Erreichbarkeit entschieden ist.
