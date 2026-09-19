# Diskussion: Berechtigungsmodell — Gruppenherkunft, Synchronisation, globale Fähigkeiten und Lebenszyklus

Konzeptpapier zu Issue [#1809](https://github.com/criew/opaa/issues/1809) (Epic
[#1295](https://github.com/criew/opaa/issues/1295) „Gruppen, Rollen und Berechtigungen", Phase 1),
zweite Fassung vom 19.09.2026 (erste Fassung bewertet durch die Perspektiven Betrieb, Personalrat,
Referatsleitung und Sachbearbeitung, siehe [Abschnitt 12](#12-stakeholder-bewertung)). Es nimmt die Ausarbeitung aus
[#1443](https://github.com/criew/opaa/issues/1443) vollständig auf und endet mit einer
Entscheidungsvorlage für den ADR ([#1810](https://github.com/criew/opaa/issues/1810)). Die
Umsetzungs-Issues des Epics (#1811 bis #1824) sind gegen die Empfehlungen geprüft; wo eine Empfehlung
von ihrem heutigen Zuschnitt abweicht, steht das bei der jeweiligen Frage.

**Mitgelesen (Code, Stand `origin/main` d49c9954):** `io.opaa.group` (`Group`, `GroupKind`,
`GroupService`, `GroupMembershipResolver`, `TokenGroupSynchronizer`, `TokenGroupProvisioner`, Paket
`sync` mit `DirectoryClient`, `DirectoryGroup`, `DirectorySyncService`, `DirectorySyncPlanExecutor`,
`DirectorySyncStatus`, `NoOpDirectoryClient`), `io.opaa.auth.oidc` (`OidcProvider`,
`OidcProviderService`, `OidcProvidersChangedEvent`), `io.opaa.auth.TrustedProvider`,
`io.opaa.space.SpaceAccessPolicy`/`SpaceService`, `io.opaa.library.LibraryAccessService`/
`AssetGrantService`/`KnowledgeLibraryService`, `io.opaa.diagnosticaccess.DiagnosticImpersonationGrant`,
`io.opaa.audit.AuditIncidentScopeGrant`, `io.opaa.searchadmin.SearchDiagnosisService`,
`io.opaa.api.types.SystemRole`/`SpaceRole`/`AssetRole`, `GroupController`, `SpaceController`,
`LibraryController`, `MeController`.
**Mitgelesen (Dokumente):** `docs/features/access-control.md`, `docs/features/spaces-and-assets.md`,
`docs/features/hybrid-retrieval.md` (Diagnosewerkzeug und Leitplanken),
`docs/features/security-and-compliance.md`, ADR-0016, ADR-0018 (Entscheidung 6 mit Nachtrag #484),
ADR-0025, ADR-0033, `docs/handbuch/suche.md`, die Issues #240, #797, #1150, #1349, #1442, #1726 und
alle Issues des Epics.
**Bei Widerspruch gilt der Code.** Die Spezifikationen laufen an einigen Stellen hinter dem gebauten
Stand her (#1808 gleicht sie parallel an); die dabei gefundenen Abweichungen stehen in
[Anhang B](#anhang-b-abweichungen-zwischen-spezifikation-und-gebautem-stand).

---

## 0. Rahmen: was bereits entschieden ist

Die Abstimmungen vom 10.09. und 19.09.2026 setzen den Rahmen. Diese Punkte werden hier **nicht** neu
zur Wahl gestellt, sondern vorausgesetzt:

1. **Subjekte sind Nutzer und Gruppen, flach.** Keine freien Rollen als drittes Rechtesubjekt, keine
   Gruppenschachtelung. Die festen Systemrollen (`SYSTEM_ADMIN`, `AUDITOR`) bleiben.
2. **Die Gruppe bleibt als Subjekt am Grant** und wird nicht in Einzelnutzer aufgelöst; nur so
   schlagen Gruppenänderungen durch.
3. **Gruppen werden Space-Mitglieder mit Rolle**, nach dem Muster der Asset-Grants (#1815).
   `memberSource = GROUP` (#358) wird nicht wiederbelebt.
4. **Globale Berechtigungen sind Fähigkeiten**, die an Nutzer oder Gruppen vergeben werden;
   ausgeliefert wird mit „Alle Konten dürfen", damit Bestandsinstallationen ihr Verhalten behalten
   (#1813). Das löst ADR-0018, Entscheidung 6 samt Nachtrag #484 ab.
5. **Das Epic liefert das typunabhängige Grant-Fundament** (#1811), auf dem #1726 (Prompt-Bibliothek)
   aufbaut.
6. **Mandantenfähigkeit (#1442) ist nur Randbedingung:** Jede Gruppe, jeder Grant und jede Fähigkeit
   gehört genau einer Organisation; nichts überschreitet die Organisationsgrenze.

Was darunter offen ist, behandeln die sieben Abschnitte 2 bis 8. Jeder endet mit **einer** Empfehlung.
Die Abschnitte 9 bis 9c tragen die Querschnittsfestlegungen, die die Stakeholder-Runde verlangt hat.

### Begriffe

Das Papier verwendet vier Wörter für vier rechtlich verschiedene Dinge. Im Amtsdeutsch ist „Befugnis"
das Alltagswort für alle zusammen; die Oberfläche muss deshalb unterscheidbare Wörter benutzen.

| Begriff im Papier | In der Oberfläche | Bedeutung | Beispiel |
|---|---|---|---|
| **Rolle** | Rolle | gestufte Berechtigung an **einem Objekt**, für Person oder Gruppe | `VIEWER` an einer Bibliothek, `ADMIN` in einem Space |
| **Fähigkeit** | Anlegerecht | unbefristetes Recht, etwas **anzulegen**; an Person, Gruppe oder „Alle Konten"; ohne Gegenstand; öffnet nie einen Inhalt | „darf Spaces anlegen" |
| **Befugnis** | Vollmacht | befristete, an **genau eine Person** gebundene Erlaubnis mit Gegenstand und Begründungspflicht | „Sicht als" für Referat 50 bis 31.03. |
| **Systemrolle** | Systemrolle | `SYSTEM_ADMIN`, `AUDITOR` | — |
| **Wirksame Gruppe** | — | eine Gruppe, die Rechte tatsächlich vermitteln kann: nicht aufgelöst, ihr Anbieter aktiviert (bei internen Gruppen entfällt das), mindestens ein **aktives** Konto als Mitglied | — |

„Vollmacht" ist bereits Sprachgebrauch des Projekts (ADR-0016, Nachtrag: „Diagnose-Vollmacht").
„Anlegerecht" ist neu und deckt die erste Liste der Fähigkeiten vollständig ab (Abschnitt 6.1).

---

## 1. Ausgangslage: was heute gebaut ist

Das Papier argumentiert vom gebauten Stand aus, nicht von der Spezifikation. Der Stand in Kürze:

| Baustein | Gebaut | Fundstelle |
|---|---|---|
| Rechtesubjekt | `PermissionSubject` = genau ein Nutzer **oder** eine Gruppe, mit `organizationId`; Auflösung über `GroupMembershipResolver` (Caffeine-Cache, 10 Minuten, Invalidierung nach Commit) | `io.opaa.group` |
| Gruppenarten | `GroupKind.ORG_UNIT` (Verzeichnisabgleich, schreibgeschützt, mit `parentGroupId` und `dissolved`), `AD_HOC` (im System angelegt, nur durch `SYSTEM_ADMIN` pflegbar), `IDENTITY_PROVIDER` (Gruppen-Claim des Tokens, `external_id = oidc:<anbieter-uuid>:<name>`, schreibgeschützt) | `Group`, `GroupKind`, `GroupService#rejectOrgUnit` |
| Herkunft | steckt in `kind` und im Präfix von `external_id`; **kein Fremdschlüssel auf `oidc_providers`** | `Group`, `TokenGroupSynchronizer#namespaceOf` |
| Token-Abgleich | bei jeder Anmeldung eines Anbieters mit `groups_claim`: Konto ist Mitglied genau der Gruppen, die das Token nennt; Änderungen historisiert und auditiert unter dem Akteur `identity-provider` | `TokenGroupSynchronizer`, `TokenGroupProvisioner` |
| Verzeichnisabgleich | Pull über `DirectoryClient#fetchGroups(organizationId)` → `DirectorySnapshot` aus `DirectoryGroup(externalId, name, parentExternalId, memberSubjects)`; Trockenlauf, Plausibilitätsschwelle (Abbruch, Vorgabe 30 % Entzüge), Leerergebnis-Schutz, Sperre je Organisation, Statuszeile je Organisation, Mitglieder aufgelöst **nur** unter dem Issuer des Standardanbieters (`TrustedProvider`); im Betrieb ist `NoOpDirectoryClient` der einzige Bean („immer unerreichbar"); kein Zeitplan | `io.opaa.group.sync` |
| Asset-Grants | `asset_grants` an Wissensbibliotheken (`library_id NOT NULL`), Subjekt Nutzer oder Gruppe, Rollen `VIEWER`–`OWNER`; aufgelöste Gruppen sind kein zulässiges Grant-Ziel; Rechtehistorie überlebt Löschungen (ADR-0016) | `AssetGrantService#requireGrantableGroup`, `PermissionHistoryService` |
| Bibliothekseigentum | Person **oder** Gruppe (`owner_user_id`/`owner_group_id`); eine gruppengehörende Bibliothek legt nur ein Mitglied der Gruppe an | `KnowledgeLibrary`, `KnowledgeLibraryService#createLibrary` |
| Space-Mitgliedschaft | nur Personen (`space_memberships.user_id`); Eigentümer ist Person und gilt als `ADMIN`; keine Rechtehistorie; kein Schutz des letzten `ADMIN` (nur der Eigentümer kann nicht entfernt werden) | `SpaceAccessPolicy`, `SpaceService#removeMember` |
| Systemrollen | `USER`, `SYSTEM_ADMIN`, `AUDITOR` (einwertig); `SYSTEM_ADMIN` hat in `LibraryAccessService#effectiveRole` `OWNER` auf jede Bibliothek, in `readableLibraryIds` (Suche) bewusst **keinen** Lesezugriff | `SystemRole`, `LibraryAccessService` |
| Befugnisse außerhalb der Rollen | „Sicht als" (`diagnostic_impersonation_grants`: Inhaber, Geltungsbereich = eine `ORG_UNIT`-Gruppe, Frist ≤ 12 Monate, folgt aus keiner Rolle) und Vorfallsbereich (`audit_incident_scope_grants`: Person, Zeitraum, Zweck, Vier-Augen-Freigabe zweier `AUDITOR`) | `DiagnosticImpersonationGrant`, `AuditIncidentScopeGrant` |
| Globale Berechtigungen | keine. `SpaceController#createSpace` und `LibraryController` (POST) verlangen nur `authenticated` | `SpaceController`, `LibraryController` |
| Rechteprofil der Diagnose | eine Gruppe samt der Bibliotheksmenge, die sie lesen darf (`readableLibraryIdsForGroup`); Voreinstellung der Suchdiagnose, kein Personenbezug | `SearchDiagnosisService.PermissionProfile`, `LibraryAccessService#readableLibraryIdsForGroup` |
| Anbieter | `oidc_providers` mit `enabled`, `is_default`, Claim-Zuordnung (`groups_claim`); Löschen lässt Konten stehen (kein FK), prüft Standardanbieter und anmeldefähigen Systemverwalter — **berührt Gruppen nicht** | `OidcProvider`, `OidcProviderService#deleteProvider` |

Zwei Folgen dieses Stands tragen das ganze Papier: Erstens ist der gesamte Verzeichnisabgleich außerhalb
der Tests wirkungslos, weil kein Konnektor existiert; die einzige produktive Gruppenquelle ist der
Token. Zweitens hängt jede Gruppe eines Anbieters nur über eine Zeichenkette an ihm — wird der Anbieter
gelöscht, bleibt eine Gruppe zurück, deren Mitgliedschaft sich nie mehr ändern kann, deren Grants aber
weiter wirken.

---

## 2. Frage 1: Vergleich mit anderen Systemen

Verglichen werden vier Produktfamilien, die in der Verwaltung tatsächlich stehen, plus die drei
Bezugssysteme für die Synchronisation. Je System: Modell, was OPAA übernimmt, was bewusst nicht — und
warum.

### 2.1 Confluence und Jira (Atlassian Data Center)

**Modell.** Confluence kennt **globale Berechtigungen** — „Can Use", „Personal Space",
„Create Space(s)", „Confluence Administrator", „Browse All Group Members", „System Administrator" —,
jede vergebbar an Gruppen und an einzelne Nutzer ([Global Permissions
Overview](https://confluence.atlassian.com/doc/global-permissions-overview-138709.html)). Die
Atlassian-Empfehlung für „Create Space(s)" lautet, sie einer Gruppe von „champions" zu geben, die keine
Administratoren sein müssen ([Permissions best
practices](https://confluence.atlassian.com/doc/permissions-best-practices-992678945.html)).
Verzeichnisanbindung: Ein LDAP-Verzeichnis läuft als **„Read Only"** („LDAP users, groups and
memberships … can only be modified via your directory server"), **„Read Only, with Local Groups"**
(„you can add groups to the internal directory and add LDAP users to those groups") oder
**„Read/Write"**; der Abgleich ist ein Polling, Vorgabe 60 Minuten ([Connecting to an LDAP
Directory](https://confluence.atlassian.com/doc/connecting-to-an-ldap-directory-229838241.html)).
Verliert ein Space seinen letzten Space-Admin, kann ein Confluence Administrator die Rechte
wiederherstellen; „Requests to recover permissions are recorded in the Confluence audit log" ([Assign
Space Permissions](https://confluence.atlassian.com/doc/assign-space-permissions-139460.html)).
Jira legt darüber **Projektrollen**: „group membership is global whereas project role membership is
project-specific", und „group membership can only be altered by Jira administrators, whereas project
role membership can be altered by project administrators" ([Managing project
roles](https://confluence.atlassian.com/adminjiraserver/managing-project-roles-938847166.html)).

**Was OPAA übernimmt.**
- Die globalen Berechtigungen als eigene, an Gruppen **und** Personen vergebbare Fähigkeiten, mit
  „Spaces anlegen" als erstem Fall — genau das Confluence-Muster „Create Space(s)", samt der
  Empfehlung, es einer benannten Gruppe statt allen zu geben (Abschnitt 6).
- „Read Only, with Local Groups" als Betriebsmodell: Verzeichnisgruppen sind schreibgeschützt, interne
  Gruppen kommen hinzu, und **Verzeichniskonten dürfen Mitglied interner Gruppen sein** (Abschnitt 3).
- Die Wiederherstellung durch die Systemverwaltung als protokollierter Verwaltungsakt für den Fall, dass
  ein Space seinen letzten `ADMIN` verliert (Abschnitt 7).

**Was OPAA bewusst nicht übernimmt.**
- **Projektrollen als Indirektion.** Jiras Projektrollen lösen ein Delegationsproblem: Wer Gruppen nicht
  pflegen darf, soll wenigstens je Projekt Mitglieder zuordnen dürfen. OPAA löst dasselbe Problem ohne
  zweites Subjekt: Space-Rollen werden je Space von dessen `ADMIN` vergeben, und interne Gruppen
  bekommen Verantwortliche (Abschnitt 5). Eine Rolle „zwischen" Gruppe und Objekt wäre das dritte
  Subjekt, das die Vorentscheidung ausschließt — und für jede Auskunft „warum sieht X das?" eine
  Herleitungsstufe mehr.
- **Die Super-Gruppe.** `confluence-administrators` hat „complete access to all content and
  administration functions". OPAA trennt Verwaltung und Inhalt: `SYSTEM_ADMIN` liest in der Suche
  nichts, was ihm nicht freigegeben ist (`readableLibraryIds` ohne Bypass), und „Sicht als" folgt aus
  keiner Rolle. Diese Trennung ist eine Zusage an den Personalrat und bleibt — als **prüfbare
  Invariante**: `LibraryAccessService#readableLibraryIds` kennt keinen Bypass, und keine Fähigkeit
  (Abschnitt 6) darf sie aushebeln. Sie gilt für die **Suche**; in der Bibliotheksverwaltung führt
  `effectiveRole` den Systemverwalter als `OWNER` — der ADR spricht beides aus, damit es nicht zwei
  Lesarten gibt. Die Betriebsfolge gehört dazu: Wer eine Störung („die Suche liefert ein Dokument, das
  ich nicht sehen dürfte") nachstellen muss, tut das über die Suchdiagnose mit **Rechteprofil**
  (Abschnitt 8) — das ist der dafür vorgesehene Weg, nicht ein Umweg über eine Rolle.
- **„Read/Write" ins Verzeichnis.** OPAA schreibt nie ins Verzeichnis. Die Verwaltung führt ihr
  Verzeichnis mit eigenen Verfahren; ein zweiter Schreibweg wäre ein Prüfungsbefund.

### 2.2 SharePoint und Microsoft Entra ID

**Modell.** Entra ID kennt Sicherheitsgruppen und Microsoft-365-Gruppen; Sicherheitsgruppen können
geschachtelt werden — mit der Einschränkung, die Microsoft selbst hervorhebt: „When nesting an existing
security group to another security group, only members in the parent group have access to shared
resources and applications" ([Learn about
groups](https://learn.microsoft.com/en-us/entra/fundamentals/concept-learn-about-groups)). Für die
Zuweisung von Anwendungen gilt „Nested group memberships aren't supported for group-based assignment to
applications at this time" ([Use a group to manage access to SaaS
apps](https://learn.microsoft.com/en-us/entra/identity/users/groups-saasapps)), und die
Bereitstellung „can't read or provision users in nested groups" ([How provisioning
works](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works)).
Im Token dagegen **sind** geschachtelte Gruppen enthalten, und die Zahl ist gedeckelt: „The number of
groups emitted in a token is limited to 150 for SAML assertions and 200 for JWT, including nested
groups. … Exceeding this limit will cause Microsoft Entra ID completely omit sending group claims in
the token" — stattdessen kommt ein Overage-Hinweis mit Verweis auf Microsoft Graph. Microsoft empfiehlt
die unveränderliche `ObjectID` als Gruppenkennung und lässt Anzeigenamen nur für ausdrücklich der
Anwendung zugewiesene Gruppen zu, „because a group name is not unique … Otherwise, any user could
create a group with duplicate name and gain access in the application side" ([Configure group
claims](https://learn.microsoft.com/en-us/entra/identity/hybrid/connect/how-to-connect-fed-group-claims)).
SharePoint-Gruppen sind demgegenüber **standortgebunden**: „A SharePoint group is a collection of users
who all have the same set of permissions to sites and content", mit genau einem Eigentümer und den
Vorgabegruppen Owners/Members/Visitors ([Customize SharePoint site
permissions](https://learn.microsoft.com/en-us/sharepoint/customize-sharepoint-site-permissions)).
Für **eigentümerlose** Microsoft-365-Gruppen gibt es eine Richtlinie, die „the members who are the
most active in the group" wöchentlich fragt, ob sie das Eigentum übernehmen; nimmt niemand an, „Tenant
admins need to find and assign an owner for each ownerless group" ([Manage ownerless
groups](https://learn.microsoft.com/en-us/microsoft-365/admin/create-groups/ownerless-groups-teams)).

**Was OPAA übernimmt.**
- **Die stabile Kennung statt des Namens** als Abgleichschlüssel des Verzeichnisabgleichs — Microsofts
  Begründung („any user could create a group with duplicate name and gain access") ist exakt das Risiko,
  das `DirectoryGroup.externalId` heute schon abfängt. Für Token-Gruppen bleibt der Claim-Wert der
  Schlüssel (Abschnitt 4).
- **Den Overage-Fall als eigenen Zustand** („Claim fehlt" ≠ „Claim leer"), den #1807 vorgezogen baut.
- **Den Nachfolgevorgang als Liste mit Auffangzuständigkeit** (Systemverwaltung), nicht als
  automatische Neuzuweisung (Abschnitt 7).

**Was OPAA bewusst nicht übernimmt.**
- **Schachtelung.** Entra zeigt, was sie kostet: drei Funktionen (Token, App-Zuweisung, Bereitstellung),
  drei verschiedene Antworten auf „gilt die Untergruppe mit?". Ein Berechtigungsmodell, das in der
  Prüfung erklärt werden muss, darf diese Frage nicht dreimal verschieden beantworten. OPAA bleibt flach;
  wer eine Querschnittsgruppe braucht, legt sie explizit an. Die Aufbauorganisation bleibt als
  `parentGroupId` **Anzeige- und Aggregationsachse**, trägt aber keine Mitgliedschaft (so bereits
  `spaces-and-assets.md`, „Mitgliedschaft vererbt nicht").
- **Standortgebundene Gruppen.** SharePoint-Gruppen gehören einer Site; OPAA-Gruppen gehören der
  Organisation und wirken an beliebig vielen Assets und Spaces. Das ist die Voraussetzung dafür, dass
  „Referat 50 darf lesen" einmal gepflegt und überall wirksam ist.
- **„Aktivste Mitglieder" als Adressaten der Nachfolge.** Das setzt eine personenbezogene
  Aktivitätsauswertung voraus, die `security-and-compliance.md` („kein personenbezogener
  Auswertungspfad") ausschließt (Abschnitt 7).

### 2.3 GitLab

**Modell.** Feste, geordnete Rollen Guest → Planner → Reporter → Developer → Maintainer → Owner;
Mitgliedschaft in einer Obergruppe vererbt sich auf Untergruppen und Projekte („When you add a member
to a subgroup, they inherit the membership and permission level from the parent groups"); „Any user can
remove themselves from a group, unless they are the only Owner of the group" ([Roles and
permissions](https://docs.gitlab.com/user/permissions/)). **Custom Roles** gibt es nur im Tier Ultimate,
gebaut als Basisrolle plus Zusatzrechte, höchstens zehn je Instanz ([Custom
roles](https://docs.gitlab.com/user/custom_roles/)). Der SAML-Gruppenabgleich wird **bei jeder
Anmeldung** ausgewertet, und „After a group sync, users who are not members of a mapped SAML group are
removed from the group" — mit der Warnung, dass eine Fehlkonfiguration Nutzer aus der Gruppe entfernt
([SAML Group Sync](https://docs.gitlab.com/user/group/saml_sso/group_sync/)). Der LDAP-Abgleich läuft
dagegen **zeitgesteuert**: Nutzerprüfung „once per day at 01:30 AM", Gruppenabgleich „every hour on the
hour", und ein Konto, dessen Prüfung scheitert, wird gesperrt ([LDAP
synchronization](https://docs.gitlab.com/administration/auth/ldap/ldap_synchronization/)).

**Was OPAA übernimmt.**
- **Beide Synchronisationswege nebeneinander, aber je Quelle einen:** Token bei der Anmeldung (wie SAML
  Group Sync) oder zeitgesteuerter Abgleich (wie LDAP Sync) — Abschnitt 4.
- **Der letzte Verantwortliche kann sich nicht selbst entfernen** — übertragen auf Gruppenverantwortliche
  (Abschnitt 5) und den letzten `ADMIN` eines Space (Abschnitt 7).
- **Kontosperre durch den Abgleich** (#1818) als Regelweg des Ausscheidens.

**Was OPAA bewusst nicht übernimmt.**
- **Vererbung über die Gruppenhierarchie.** Bei GitLab ist die Hierarchie ein Namensraum für Projekte;
  in OPAA gibt es keine Hierarchie von Spaces, und die Aufbauorganisation ist eine Anzeigeachse.
- **Custom Roles.** GitLabs Zuschnitt (Basisrolle plus Rechte, gedeckelt, nur im teuersten Tier) belegt,
  dass freie Rollen ein eigenes Verwaltungsobjekt mit eigener Pflege sind. Genau das hat die
  Vorentscheidung vom 10.09. verworfen.
- **Stille Entfernung bei Fehlkonfiguration.** GitLab warnt vor dem Effekt, statt ihn abzufangen. OPAA
  hat mit Leerergebnis-Schutz, Plausibilitätsschwelle und Trockenlauf bereits die stärkere Antwort und
  ergänzt sie um den Bestätigungsweg (#1816).

### 2.4 Nextcloud

**Modell.** Neben den Super-Administratoren gibt es **Gruppenadministratoren**, die je Gruppe vergeben
werden: Sie können Nutzer in ihren Gruppen anlegen und entfernen und deren Stammdaten ändern, haben
aber keinen Zugriff auf Systemeinstellungen ([User
management](https://docs.nextcloud.com/server/stable/admin_manual/configuration_user/user_configuration.html)).
Die LDAP-Anbindung ist rein lesend („Only read access to your LDAP"), Gruppenmitgliedschaften werden
über einen Hintergrundjob zwischengespeichert, und „Disable users missing from LDAP" lässt
verschwundene Konten als deaktiviert erscheinen ([User authentication with
LDAP](https://docs.nextcloud.com/server/stable/admin_manual/configuration_user/user_auth_ldap.html)).

**Was OPAA übernimmt.**
- **Die Gruppenadministration als delegierte Rolle je Gruppe** — das Vorbild für die
  Gruppenverantwortlichen interner Gruppen (Abschnitt 5, #1814).
- **Ausscheiden = Deaktivieren, nicht Löschen**, wie es `access-control.md` schon vorsieht.

**Was OPAA bewusst nicht übernimmt.**
- **Verantwortliche, die Konten anlegen.** In OPAA entstehen Konten aus dem Verzeichnis oder aus der
  lokalen Benutzerverwaltung (ADR-0033); ein Gruppenverantwortlicher nimmt **bestehende** Konten auf.
- **Gruppenadministratoren an Verzeichnisgruppen.** Verzeichnisgruppen bleiben schreibgeschützt; ein
  Verantwortlicher gibt es nur an internen Gruppen.

### 2.5 Bezugssysteme für die Synchronisation

- **SCIM 2.0** ([RFC 7644](https://www.rfc-editor.org/rfc/rfc7644.html)) ist ein Push-Protokoll: der
  Identitätsanbieter ist Client, die Anwendung ist „service provider" mit `/Users` und `/Groups`; die
  Entra-Bereitstellung sendet Deaktivierungen als `active = false` und läuft in Initial- und
  Inkrementalzyklen „at intervals defined in the tutorial specific to each application" ([How
  provisioning works](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works);
  [SCIM endpoint
  tutorial](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)).
  Für OPAA hieße SCIM: ein eingehender, dauerhaft erreichbarer API-Pfad mit Bearer-Token, der von
  außen Konten und Gruppen schreibt — eine neue Angriffsfläche, die ausschließlich in Entra-/Okta-Häusern
  mit Premium-Lizenz überhaupt befüllt würde.
- **Keycloak Admin REST API**: `GET /admin/realms/{realm}/groups` („Get group hierarchy"),
  `GET /admin/realms/{realm}/groups/{group-id}/members` („Returns a list of users, members of the
  group"), `GET /admin/realms/{realm}/users/{user-id}/groups`, alle mit `first`/`max`/
  `briefRepresentation` ([Admin REST API](https://www.keycloak.org/docs-api/latest/rest-api/openapi.yaml));
  Gruppen sind hierarchisch, Mitglieder „inherit the attributes and role mappings that group defines",
  und LDAP-Gruppen werden über Mapper importiert ([Server Administration
  Guide](https://www.keycloak.org/docs/latest/server_admin/index.html)). Entscheidend: Die
  Keycloak-Nutzer-ID **ist** der `sub` des Tokens.
- **Microsoft Graph**: `GET /groups/{id}/transitiveMembers` „returns a flat list of all nested members"
  ([List group transitive
  members](https://learn.microsoft.com/en-us/graph/api/group-list-transitivemembers)).
- **LDAP/Active Directory**: `objectGUID` „is set when the object is created and cannot be changed"
  ([Object-Guid attribute](https://learn.microsoft.com/en-us/windows/win32/adschema/a-objectguid)) —
  die stabile Kennung, die `DirectoryGroup.externalId` vorsieht.

### 2.6 Zusammenfassung des Vergleichs

| Merkmal | Confluence/Jira | Entra/SharePoint | GitLab | Nextcloud | **OPAA (Empfehlung)** |
|---|---|---|---|---|---|
| Rechtesubjekte | Nutzer, Gruppe, (Jira) Projektrolle | Nutzer, Gruppe | Nutzer, Gruppe | Nutzer, Gruppe | Nutzer, Gruppe — flach |
| Freie Rollen | nein (Jira: Rollen als Container) | App-Rollen empfohlen | nur Ultimate, gedeckelt | nein | nein |
| Schachtelung | nein | ja, mit drei verschiedenen Wirkungen | Vererbung entlang Namensraum | LDAP-Option | nein; Hierarchie nur Anzeige |
| Verzeichnisgruppen | schreibgeschützt + lokale Gruppen | verwaltet im Verzeichnis | schreibgeschützt (Group Links) | schreibgeschützt | schreibgeschützt + interne Gruppen |
| Globale Berechtigungen | ja, an Gruppen/Nutzer | Rollen/Lizenzen | Instanzrechte | Super-Admin | Fähigkeiten an Gruppen/Nutzer/„Alle Konten" |
| Delegierte Gruppenpflege | Jira-Projektrollen | Gruppenbesitzer | Group Owner | Gruppenadministrator | Gruppenverantwortliche interner Gruppen |
| Synchronisation | Polling (60 min) | Push (SCIM) / Token | Token bei Anmeldung **oder** Cron | Hintergrundjob | Token **oder** zeitgesteuerter Pull, je Anbieter |
| Verlust des letzten Verantwortlichen | Admin stellt wieder her (protokolliert) | Richtlinie fragt aktivste Mitglieder | letzter Owner kann nicht gehen | — | „Nachfolge offen", vollständige Liste, Auffang Systemverwaltung |
| Reorganisation (Referat 50 → 52) | Verzeichnisgruppe leert sich; Space-Rechte von Hand neu vergeben, Recover Permissions als Notweg | Gruppe umbenennen/verschieben im Verzeichnis; Zuweisungen folgen der Gruppen-ID | Group Link auf neue IdP-Gruppe; alte Mitglieder werden beim nächsten Sync entfernt | LDAP-Gruppe verschwindet; Freigaben an die Gruppe laufen leer | **Übertragung** der Rechte von Gruppe A auf Gruppe B als protokollierter Verwaltungsakt (Abschnitt 9c) |

---

## 3. Frage 2: Gruppenherkunft

### 3.1 Datenmodell

Heute trägt `Group` die Herkunft in `kind` und, bei Token-Gruppen, im Präfix von `external_id`; ein
Fremdschlüssel auf `oidc_providers` fehlt. Drei Optionen:

**Option A — beim heutigen Modell bleiben.** `kind` sagt, wie die Gruppe entsteht, das Präfix sagt,
von wem. Vorteil: keine Migration. Nachteil: Der Anbieterbezug ist eine Zeichenkette ohne Integrität;
`ORG_UNIT`-Gruppen tragen gar keinen Anbieterbezug (er ist implizit der Standardanbieter, `TrustedProvider`);
Namenskollisionen sind nur über Parsen des Präfixes erkennbar; das Löschen eines Anbieters hinterlässt
Gruppen, die niemand mehr ändern kann.

**Option B — `provider_id` als echter Fremdschlüssel, `kind` bleibt Mechanismus.** Neue Spalte
`groups.provider_id` (nullable, FK auf `oidc_providers`, `ON DELETE RESTRICT`); Prüfregel
`kind = AD_HOC ⇔ provider_id IS NULL`. `external_id` verliert das Präfix und trägt nur noch die
Kennung aus der Quelle; die Eindeutigkeit wird `(organization_id, provider_id, kind, external_id)`.
Die Herkunft im Sinne von #1443 („intern oder Anbieter X") ist damit **ableitbar**, nicht doppelt
gespeichert; `kind` behält seine heutige Rolle als Bezeichnung des Mechanismus (Verzeichnisabgleich,
Token, manuell), die etwa „Sicht als" braucht (Geltungsbereich muss `ORG_UNIT` sein).

**Option C — `origin`-Enum (`INTERNAL` | `PROVIDER`) plus `provider_id`, `kind` abschaffen.** Sauberer
Begriff, aber `GroupKind` ist API-Typ (`opaa-api`), Prüfbedingung (`chk_groups_kind`), Filter in
Abfragen und Kriterium des Geltungsbereichs von Befugnissen. Das Abschaffen wäre ein Umbau über die
Frage hinaus, und der Gewinn — ein Wort statt einer Ableitung — rechtfertigt ihn nicht.

**Empfehlung: Option B.** Sie liefert genau das, was #1812 verlangt (echter Anbieterbezug, Migration der
Bestandszeilen aus `kind` und Präfix, Delta-Test), ohne den API-Typ zu brechen. Die Ableitung
„intern" (`provider_id IS NULL`) bzw. „Anbieter *Name*" (Join) wird in `GroupResponse` als `origin`
und `provider { id, displayName }` ausgeliefert (Spezifikation zuerst, ADR-0006). Die Migration ordnet
`ORG_UNIT`-Gruppen dem heutigen Standardanbieter zu (das ist die einzige Quelle, aus der sie entstanden
sein können) und schneidet bei `IDENTITY_PROVIDER`-Gruppen das Präfix aus `external_id` in
`provider_id` um.

**Folge für #1812:** Der Issue-Text sagt „Herkunft als Spalten an `groups` (intern oder Anbieter, mit
Fremdschlüssel)". Die Empfehlung präzisiert: **eine** Spalte `provider_id`, keine zusätzliche
`origin`-Spalte. Das ist eine Nachschärfung, keine Abweichung.

### 3.2 Zwei Mechanismen, ein Anbieter?

Ein Anbieter kann heute `groups_claim` tragen (Token-Gruppen) **und** — als Standardanbieter — Ziel des
Verzeichnisabgleichs sein (`ORG_UNIT`). Dieselbe Verzeichnisgruppe entstünde dann zweimal: als
Token-Gruppe (Schlüssel = Claim-Wert) und als Verzeichnisgruppe (Schlüssel = `objectGUID`), mit zwei
Wahrheiten über die Mitgliedschaft und zwei Entzugszeitpunkten. Zusammenführen lässt sich das nicht
verlässlich, weil der Token keine stabile Kennung liefern muss.

**Empfehlung: Je Anbieter genau ein Gruppenmechanismus** — Token-Claim **oder** Verzeichnisabgleich.
Das Einschalten des Abgleichs (#1816) verlangt einen leeren `groups_claim` und umgekehrt; die
Verwaltung meldet den Konflikt mit `409` und verständlichem Grund statt ihn stumm hinzunehmen. Wer von
Token auf Abgleich wechselt, bekommt einen Trockenlauf mit Differenzbericht, der die Token-Gruppen als
„laufen aus" ausweist (Abschnitt 4.4).

### 3.3 Anzeige bei Namenskollision

Zwei Anbieter (Haus A, Partnerportal) liefern je ein „Referat 50"; dazu kann eine interne Gruppe
gleichen Namens kommen. Die Gruppen bleiben über ihre ID getrennt (heute schon); die Frage ist die
Darstellung.

| Option | Beurteilung |
|---|---|
| **Präfix im gespeicherten Namen** („Haus A/Referat 50") | Verändert den Namen der Quelle; bricht bei Umbenennung des Anbieters; in Freitextsuchen und Auditeinträgen inkonsistent. **Verworfen.** |
| **Herkunft als eigenes Feld, in der Oberfläche als Zusatz** („Referat 50 · Verzeichnis Haus A · 23 Mitglieder") | Name bleibt, wie die Quelle ihn führt; die Herkunft ist überall dieselbe Ableitung (Abschnitt 3.1); genau die UI-Referenz aus #1820. **Empfohlen.** |
| **Eindeutigkeit des Namens erzwingen** | Für Anbietergruppen unmöglich (die Quelle bestimmt den Namen). Für **interne** Gruppen in der ersten Fassung empfohlen — **zurückgenommen** (Betrieb, Auflage 2.4): Ein `409` beim Anlegen verriete jedem Inhaber des Anlegerechts, dass eine Gruppe dieses Namens existiert — genau das Datum, das Abschnitt 5.3 und 9b schützen. Stattdessen: eine **Warnung** beim Anlegen, wenn eine für den Anlegenden **sichtbare** Gruppe gleich heißt; die Auflösung läuft über Herkunft, Verantwortliche und ID. Die Eindeutigkeit ist damit eine Anzeigehilfe, keine Garantie. |

Drei Ergänzungen aus der Stakeholder-Runde:

- **Gleichnamige Gruppen eines Anbieters.** In Keycloak heißt jede zweite Untergruppe „Leitung" oder
  „Sachbearbeitung"; der Anbietername unterscheidet sie nicht. Der Herkunftszusatz trägt deshalb den
  **Pfad der Quelle** („Leitung · Verzeichnis Haus A · /Haus/Abteilung 5/Referat 50"), gespeichert als
  eigenes Feld `source_path`, das der Abgleich mitliefert (Betrieb, Auflage 3.3).
- **Externe Anbieter sind mehr als ein Textzusatz.** Ein Fehlklick zwischen „Referat 50 · Verzeichnis
  Haus A" und „Referat 50 · Verzeichnis Partner" gibt einen internen Vorgang an ein Partnerportal frei,
  und ein Zusatz, den man zum zehnten Mal liest, wird nicht mehr gelesen (Sachbearbeitung). Die
  Anbieterzeile erhält ein Kennzeichen **„extern"** (gesetzt von der Systemverwaltung; Vorgabe: jeder
  Anbieter außer dem Standardanbieter ist extern, bis jemand es ändert). Gruppen externer Anbieter sind
  in jeder Auswahl **sichtbar anders** dargestellt (Symbol, nicht nur Text), und das Erteilen eines
  Grants oder einer Space-Mitgliedschaft an eine solche Gruppe verlangt eine Zwischenfrage („Sie geben
  für eine Gruppe eines externen Anbieters frei — fortfahren?"). Das ist dieselbe Mechanik, die
  `access-control.md` für externe Konten schon vorsieht („die Aufnahme verlangt eine ausdrückliche
  Bestätigung").
- **Hinweis beim Anlegen einer internen Gruppe:** Name und Herkunft werden für alle sichtbar, für die
  die Gruppe wählbar ist (Abschnitt 9b); die Oberfläche sagt das beim Anlegen (Personalrat).

Auditeinträge und Rechtehistorie tragen zusätzlich zur Gruppen-ID die Herkunft als Text
(„Referat 50 (Verzeichnis Haus A)"), damit ein Prüfer sie liest, ohne die Anbietertabelle zu joinen —
die Anbieterzeile kann später gelöscht sein (ADR-0016: Historie überlebt). **Dieser Namensschnappschuss
wird nur zusammen mit der Aufbewahrungshöchstdauer der Historie beschlossen** (Abschnitt 9a,
Entscheidung 8): Ohne Höchstdauer bliebe der Name einer gelöschten Gruppe „Disziplinarverfahren 2026"
unbefristet an jedem ihrer früheren Mitglieder hängen (Personalrat). Mit Höchstdauer ist er eine
lesbare Zeile mit Verfallsdatum.

### 3.4 Deaktivieren und Löschen eines Anbieters

**Deaktivieren** ist der Notweg (Token ab sofort abgewiesen, ADR-0025) und muss ohne Bedingung
möglich bleiben. Gruppen, Mitgliedschaften und Grants bleiben unverändert stehen; Token-Gruppen ändern
sich nicht (niemand meldet sich mehr an), der Verzeichnisabgleich dieses Anbieters pausiert. Wird der
Anbieter wieder aktiviert, läuft alles weiter, als wäre nichts gewesen.

**Die Gruppen eines deaktivierten Anbieters sind aber keine wirksamen Gruppen** (Begriff in
Abschnitt 0): Kein Mitglied kann sich anmelden, die Mitgliedschaft ist faktisch eingefroren. Die erste
Fassung ließ sie „in Auswahlfeldern weiter wählbar, mit Hinweis" — das widersprach Abschnitt 7.4, der
den funktional gleichen Zustand (leer, aufgelöst) vom neuen Grant ausschließt, und es erzeugte einen
Schaden: Eine Freigabe an „Referat 50 (Anbieter deaktiviert)" wirkt für niemanden, bis der Anbieter
wieder aktiv ist, und dann schlagartig für alle, ohne erneute Entscheidung (Betrieb, Auflage 2.3).
**Aufgelöst:** Nur wirksame Gruppen sind neues Grant-Ziel und neues Space-Mitglied. Gruppen eines
deaktivierten Anbieters erscheinen in der Auswahl als nicht wählbar mit Grund; bestehende Grants bleiben
unangetastet. Dieselbe Regel gilt in Abschnitt 7.4 für leere und aufgelöste Gruppen.

**Rücknahme nach einem Vorfall.** Die Deaktivierung nimmt keine Mitgliedschaft zurück, die ein
kompromittierter Anbieter vorher über manipulierte Tokens gesetzt hat. Die Rechtehistorie
(`group_membership_history` mit `valid_from`) gibt eine gezielte Rücknahme „alle Mitgliedschaften
dieses Anbieters seit Zeitpunkt T" her; **im ersten Schritt bleibt das Handarbeit**, und der ADR sagt
das ausdrücklich, statt es als Nebenwirkung eintreten zu lassen (Betrieb, Auflage 2.6, dort als
Alternative angeboten). Die Übertragungsoperation (Abschnitt 9c) ist die spätere Grundlage dafür.

**Löschen** ist Aufräumen, kein Notweg. Der einzige realistische Grund, einen Anbieter zu löschen, ist
seine Ablösung (Keycloak A durch B, Fusion, abgeschaltetes Partnerportal) — und genau dann tragen seine
Gruppen Grants. Drei Optionen:

| Option | Wirkung | Beurteilung |
|---|---|---|
| **Auflösen** — Gruppen des Anbieters gehen in `dissolved` | Nichts blockiert; Grants wirken für Konten, die sich nicht mehr anmelden können; Assets im Eigentum solcher Gruppen gehen in „Nachfolge offen" | Erzeugt dauerhaft tote Gruppen; verlangt `ON DELETE SET NULL` und eine Zusatzregel für Gruppen ohne Anbieter — genau die Zustandsklasse, die #1812 abschaffen will |
| **Verweigern allein** — `409`, solange Gruppen des Anbieters wirken | Zwingt zum Aufräumen vor dem Löschen | In der ersten Fassung empfohlen. Im Betrieb eine Sackgasse: Die einzige angebotene Auflösung ist, die Berechtigungen zu **entfernen** — also die Rechte zu zerstören, die man auf die Gruppen des neuen Anbieters umziehen will; bei 200 Assets endet das in einem `UPDATE` auf der Datenbank, und die Rechtehistorie ist ab dem Tag wertlos (Betrieb, 3.2.1) |
| **Verweigern mit Übertragungsoperation** — `409` mit Arbeitsliste je Anbieter; die Rechte jeder Gruppe lassen sich protokolliert auf eine Gruppe des neuen Anbieters übertragen (Abschnitt 9c); Gruppen ohne Wirkung werden mit dem Anbieter gelöscht | Die Verweigerung ist ein Arbeitsauftrag, kein Stoppschild; jeder Umzug ist eine Historienzeile mit Vorgangsbezug | Dasselbe Muster wie heute für `deleteGroup` (409 bei Grants) und den Standardanbieter; `ON DELETE RESTRICT` trägt „keine Gruppe ohne Anbieter" strukturell |

**Empfehlung: Verweigern mit Übertragungsoperation.** Die Fehlermeldung nennt Anzahl und Art der
Wirkungen („3 Gruppen tragen 12 Berechtigungen an 7 Bibliotheken und sind Mitglied in 2 Spaces") und
verweist auf die **Arbeitsliste je Anbieter** (Ergänzung zu #1821, das die Sicht je Gruppe liefert),
von der aus jede Gruppe übertragen oder ihre Wirkung entfernt wird. Der Lebenszyklus (Abschnitt 7)
braucht keinen eigenen Fall „Anbieter gelöscht".

**Folge für #1812:** Die Alternative „aufgelöst" entfällt; das Löschen setzt die Übertragungsoperation
voraus (Abschnitt 9c wird eigenes Umsetzungs-Issue, von dem #1812 abhängt).

### 3.5 Verhältnis zu lokalen Konten (#1368, ADR-0033)

Lokale Konten haben keinen Gruppenmechanismus („Lokale Konten erhalten Gruppen nur manuell", ADR-0033);
sie werden Mitglied **interner** Gruppen, aufgenommen von deren Verantwortlichen (Abschnitt 5). Eine
Anbietergruppe kann kein lokales Konto enthalten — die Prüfregel ist, dass Mitglieder einer Anbietergruppe
den Issuer dieses Anbieters tragen. Das gilt heute für den Token-Pfad implizit und wird im
Verzeichnisabgleich je Anbieter (#1816) ausdrücklich.

### 3.6 Migration und ihre Fehlerfälle

Die erste Fassung beschrieb die Migration in einem Satz. Sie hat einen belegbaren Abbruchfall, den das
Papier in Anhang B Nr. 12 selbst festhält: Heute bleiben Gruppen **gelöschter Anbieter** zurück, mit
`external_id = oidc:<uuid>:<name>` auf eine Anbieterzeile, die es nicht mehr gibt. Für diese Zeilen
lässt sich `provider_id` mit `RESTRICT` nicht setzen; Liquibase bricht ab, die Anwendung startet nicht,
und die Baseline hat bewusst keine Rollback-Blöcke (Betrieb, Auflage 2.1). Der ADR bekommt deshalb
einen Abschnitt **„Migration und ihre Fehlerfälle"**:

| Fall | Behandlung im Changeset (#1812) | Delta-Test |
|---|---|---|
| Token-Gruppe, deren Anbieter-UUID in `oidc_providers` fehlt | Umwandlung in eine **interne Gruppe** (`kind = AD_HOC`, `provider_id = NULL`, `external_id = NULL`, Beschreibung mit Herkunftsvermerk „übernommen aus gelöschtem Anbieter <uuid>"), ohne Verantwortliche → sie erscheint sofort in der Liste offener Nachfolgen (Abschnitt 7.3); je Zeile ein Audit-Ereignis unter einem Systemprozess-Akteur `migration` | Fixture mit einer solchen Waisen-Gruppe samt Grant; nach der Migration ist sie intern, ihr Grant unverändert |
| `ORG_UNIT`-Gruppe in einer Installation **ohne Standardanbieter** (die Anbietermenge darf leer sein; die `LOCAL`-Zeile ist nie Standard) | dieselbe Umwandlung; `dissolved`-Zustand bleibt als Beschreibungsvermerk erhalten | Fixture ohne `is_default`-Zeile |
| `ORG_UNIT`-Gruppen im Betriebsmodus `dev` (dort gibt es keine Anbieterzeile; `TrustedProvider` liest den Issuer aus `opaa.auth.dev`) | #1816 legt fest, ob der Abgleich im `dev`-Modus über eine synthetische Anbieterzeile läuft oder entfällt; die Migration darf im `dev`-Modus nicht abbrechen | Suite unter `local,dev` |
| Reihenfolge der Eindeutigkeit: `uk_groups_organization_external_id` ist heute `(organization_id, external_id)` und der Nebenläufigkeitsschutz von `TokenGroupSynchronizer` | erst neuen Schlüssel `(organization_id, provider_id, kind, external_id)` anlegen, dann Präfix schneiden, dann alten Schlüssel fallen lassen — sonst kollidieren zwei gleichnamige Gruppen zweier Anbieter **während** des Updates; `MAX_NAME_LENGTH` in `TokenGroupSynchronizer` ändert sich mit dem Präfix (Betrieb, Auflage 2.2) | Fixture mit gleichnamigen Gruppen zweier Anbieter |
| Vorabprüfung durch den Betrieb | `docs/handbuch/deployment.md` erhält vor dem Update eine Prüfabfrage (Zahl der Waisen-Gruppen, Zahl der `ORG_UNIT`-Gruppen, Vorhandensein eines Standardanbieters), damit der Betrieb den Umwandlungsfall kennt, bevor er eintritt | — |

Das Muster „ein Migrationsfehler, den man vorher kennt, ist ein Testfall" gilt für jedes Changeset des
Epics; `#1813` (vier Fähigkeitszeilen je Organisation) und `#1815` (Subjektspalten an
`space_memberships`) haben keinen vergleichbaren Fall, weil sie nur Zeilen hinzufügen.

---

## 4. Frage 3: Synchronisation

### 4.1 Die Optionen

| Option | Rechteentzug wirkt | Reichweite | Voraussetzung |
|---|---|---|---|
| **T — Token bei der Anmeldung** (heute) | bei der nächsten Anmeldung der betroffenen Person; wer sich nicht mehr anmeldet, behält die Mitgliedschaft im Bestand | nur Konten, die sich anmelden; Gruppen nur, wenn ein Mitglied sich anmeldet | jeder OIDC-Anbieter mit Gruppen-Mapper; keine Zugangsdaten |
| **P — periodischer Pull** (LDAP/AD, Keycloak Admin API, Graph) | beim nächsten Lauf, unabhängig von Anmeldungen | alle Gruppen und Mitglieder der Quelle, auch nie angemeldete | Konnektor, Dienstkonto, Zeitplan, Netzfreigabe |
| **S — SCIM-Push** (OPAA als SCIM-Server) | wenn der Anbieter sendet (Entra: Inkrementalzyklen) | wie P | eingehender API-Pfad, Bearer-Token, Anbieter mit Bereitstellungsfunktion (Entra P1/P2, Okta); Gruppenpush nur mit `PATCH`-Unterstützung |
| **T + P je Anbieter** | beides | beides | beide, plus Auflösung des Doppelbestands (Abschnitt 3.2) |

Die Spezifikation (`access-control.md`, „Was übernommen wird") nennt SCIM als bevorzugten Weg und einen
turnusmäßigen Abgleich als Ersatz. Der Code ist Pull (`DirectoryClient#fetchGroups` liefert einen
vollständigen Schnappschuss), und die ganze Schutzmechanik — Leerergebnis, Schwelle, Trockenlauf — ist
auf Schnappschüsse gebaut: Ein Push liefert Deltas, für die es keinen „leeren Lauf" gibt und keine
Schwelle je Lauf, sondern nur je Nachricht.

### 4.2 Empfehlung zum Mechanismus

**Je Anbieter genau eine Gruppenquelle: Token (Vorgabe) oder zeitgesteuerter Pull.** Kein SCIM im
ersten Schritt. Begründung:

- **Token bleibt die Vorgabe**, weil er ohne Konfiguration mit jedem Anbieter funktioniert und den
  Grundsatz „Rechteentzug wirkt bei der nächsten Anmeldung" schon einlöst. Seine Lücke — die Person,
  die sich nie wieder anmeldet — schließt für Anbieterkonten die Kontosperre am Anbieter (kein Token
  mehr) und für die Reichweite über Gruppen der Pull-Modus.
- **Pull statt Push**, weil Pull die gebaute Schutzmechanik unverändert nutzt, weil er keinen eingehenden
  Schreibpfad öffnet und weil er mit jedem Anbieter geht, der eine Leseschnittstelle hat. SCIM
  verlangt vom Betrieb eine Bereitstellungslizenz und von OPAA eine zweite Schnittstelle mit eigener
  Sicherheitsprüfung, für einen Kundenkreis, der nach Vision (ADR-0014) nicht der erste ist.
- **Zeitgesteuert mit Bestätigungsweg** (#1816): Intervall je Anbieter (Vorgabe 6 Stunden, wie die
  Spezifikation nennt; GitLab fährt LDAP-Gruppen stündlich), Trockenlauf jederzeit, Läufe über der
  Schwelle als ausstehender Plan, den ein Systemverwalter bestätigt oder verwirft.

**Der Vorgabeweg ist der ungeschützte — das muss der ADR aussprechen.** Die vier Schutzmechaniken
(Trockenlauf, Schwelle, Leerergebnis-Schutz, Bestätigungsweg) hat nur der Verzeichnisabgleich, der wegen
`NoOpDirectoryClient` heute nirgends läuft. Der Token-Pfad hat keine davon: `TokenGroupSynchronizer`
setzt je Anmeldung „genau die Gruppen, die das Token nennt". Was das im Alltag heißt (Betrieb,
Auflage 3.1): Das Identitätsmanagement benennt den Gruppenanspruch `Ref50` in `Referat 50` um — eine
Pflegemaßnahme, kein Fehler. Ab der ersten Anmeldung ist jede Person Mitglied der **neuen** Gruppe und
wird aus der alten entfernt; die 40 Freigaben an der alten Gruppen-ID wirken für niemanden mehr, und im
Protokoll stehen 200 Einzelereignisse `GROUP_MEMBER_REMOVED` unter dem Akteur `identity-provider`, aber
kein Hinweis, dass eine wirksame Gruppe leergelaufen ist.

- **Was #1807 schließt** (vorgezogen, läuft parallel): die Lücke „Claim fehlt ≠ Claim leer". Ein
  fehlender oder falsch geformter Claim und der Entra-Overage-Hinweis entziehen danach nichts mehr; nur
  ein vorhandener, leerer Claim gilt als Entzug.
- **Was danach noch fehlt:** (a) Eine Umbenennung des Claim-Werts ist im Token-Modus ein Gruppenwechsel
  ohne jede Schutzmechanik — der ADR benennt das als Eigenschaft des Modus, nicht als Randnotiz; (b) ein
  **sichtbares Signal**, wenn eine Gruppe mit Wirkung (Grants, Space-Mitgliedschaft, Eigentum)
  leerläuft — die Liste „Freigaben ohne Empfänger" in Abschnitt 7.5, die beide Mechanismen deckt; (c)
  das Handbuch sagt Häusern mit gepflegtem Verzeichnis, dass der Pull-Modus der **empfohlene** ist und
  Token nur die Vorgabe, die ohne Konfiguration funktioniert; (d) die Rechtehistorie im Token-Modus ist
  nur so genau wie die Anmeldezeitpunkte der Mitglieder — eine Verzeichnisänderung vom 3. März erscheint
  für eine Person, die sich am 20. März anmeldet, mit dem 20. März. Das Handbuch nennt diese Genauigkeit
  je Mechanismus, und die Herleitung (Abschnitt 9b) zeigt je Gruppe, welcher Mechanismus sie pflegt
  (Referatsleitung, Auflage 3; ihre Alternative — Pull als Vorbedingung für Grants ab Referatsebene —
  ist nicht umsetzbar, weil der Mechanismus eine Anbietereinstellung ist und kein Grant-Geber ihn wählen
  kann).
- **Auch der Zeitverzug muss sichtbar sein, und zwar nicht nur der Systemverwaltung:** Eine neue
  Kollegin wird morgens ins Verzeichnis aufgenommen und sieht bis zum nächsten Lauf nichts; wer sie
  eingewiesen hat, ruft die IT an, obwohl in Stunden alles von selbst funktioniert (Sachbearbeitung).
  Die Ansicht „Meine Gruppen" nennt je Anbieter Mechanismus, Intervall und Zeitpunkt des letzten
  Abgleichs — das ist keine schutzwürdige Information.

### 4.3 Empfehlung zum ersten Konnektor

| Kandidat | Mitgliederauflösung | Stabile Gruppenkennung | Kontostatus (#1818) | Aufwand |
|---|---|---|---|---|
| **Keycloak Admin REST API** | Keycloak-Nutzer-ID **=** `sub` des Tokens; `DirectoryGroup.memberSubjects` passt ohne Abbildung | Gruppen-UUID | `enabled` je Nutzer | Dienstkonto mit `view-users`/`query-groups`; Container für die Integrationssuite existiert im Demo-Stack |
| LDAP / Active Directory | Mitglieder sind DNs; der `sub` des Tokens ist bei Keycloak eine Keycloak-UUID, bei Entra pairwise — OPAA braucht ein konfigurierbares Abbildungsattribut (welches LDAP-Attribut dem Subject oder einem anderen Claim entspricht) | `objectGUID` | `userAccountControl` | LDAP-Client, Bind-Konto, Paging, Attributabbildung je Haus |
| Microsoft Graph | Entra-Objekt-ID ≠ `sub`; braucht den `oid`-Claim als Subject-Ersatz | Objekt-ID | `accountEnabled` | App-Registrierung, Anwendungsberechtigungen `GroupMember.Read.All`, Mandantenfreigabe |

**Empfehlung: Keycloak Admin REST API zuerst.** Sie ist der einzige Kandidat, bei dem die Identität des
Mitglieds ohne Abbildungsregel mit dem Konto in OPAA zusammenfällt — der Fehler, den ADR-0025 beim
Abgleich ohne Issuer beschrieb („ein Konto aus Anbieter B bekäme die Gruppenmitgliedschaften des
gleichnamigen `subject` aus Anbieter A"), kann hier strukturell nicht entstehen. Keycloak ist zudem in
Verwaltungshäusern die übliche Vermittlungsschicht vor Active Directory (ADR-0025 nennt
„Kerberos weiterhin über eine Keycloak-Föderation"); seine LDAP-Mapper importieren die AD-Gruppen, und
OPAA liest sie über eine Schnittstelle, die im Demo-Stack schon läuft — die Integrationssuite gegen ein
echtes Verzeichnis im Container (#1817) ist damit ohne neues Werkzeug möglich. LDAP direkt folgt als
zweiter Konnektor mit ausdrücklicher Abbildungsregel; Graph als dritter, sobald ein Entra-Haus ihn
braucht (dann auch als Antwort auf den Overage-Fall).

Drei Festlegungen, die #1817 braucht (Betrieb, Auflagen 3.2 bis 3.4):

- **Direkte Mitglieder, nicht transitiv.** `GET /admin/realms/{realm}/groups/{group-id}/members` ruft
  `session.users().getGroupMembersStream(realm, group, …)` auf und liefert nur die direkten Mitglieder
  der Gruppe, keine Mitglieder von Untergruppen (geprüft am
  [Quelltext von `GroupResource`](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/services/resources/admin/GroupResource.java)).
  Das deckt sich mit der Spezifikation („Mitgliedschaft vererbt nicht — ein Grant an ‚Amt 5' erreicht
  nur, wen das Verzeichnis dieser Gruppe zurechnet") und mit Keycloaks eigener Semantik, in der Rollen
  und Attribute nach unten vererbt werden, Mitgliedschaft aber nicht nach oben. Der Konnektor liest also
  je Gruppe die direkten Mitglieder; eine Abteilung, die in Keycloak nur Untergruppen hat, ist in OPAA
  eine leere Gruppe — der **Differenzbericht des ersten Laufs nennt die Zahl der Mitglieder je Gruppe**,
  damit der Betrieb das vor dem Anwenden sieht, und das Handbuch erklärt es.
- **Zugangsdaten des Dienstkontos** liegen verschlüsselt über `io.opaa.security.CredentialsEncryptor`
  (AES-256-GCM, `enc:v1:`), nicht in `application.yml`. Dessen Javadoc hält fest, dass eine
  Schlüsselrotation im Bestand nicht vorgesehen ist (ein Wechsel soll `enc:v2:` bringen); ein regelmäßig
  zu wechselndes Dienstkonto-Passwort ist damit an eine ungelöste Frage gekoppelt. Der ADR nennt sie; ob
  daraus eine harte Anforderung folgt (BSI IT-Grundschutz ORP.4 ist der naheliegende Baustein, **zu
  prüfen**), entscheidet der Informationssicherheitsbeauftragte des Hauses.
- **Der Verbindungstest** folgt dem Muster der Anbieterverwaltung (`OidcProviderConnectionTester`) und
  der Allowlist-Prüfung für die Admin-API-Adresse.

**Folge für #1817:** Der Issue lässt den Typ offen („entscheidet der ADR"); die Empfehlung setzt Keycloak
und die drei Festlegungen oben.

### 4.3a Lebenszyklus eines ausstehenden Plans (#1816)

Der Bestätigungsweg braucht vier Festlegungen, jede mit eigenem Schadensfall (Betrieb, Auflage 3.5):

1. **Der Leerergebnis-Schutz bleibt ein harter Abbruch** und wandert nicht in den Bestätigungsweg.
   `ABORTED_EMPTY_RESULT` heißt „die Quelle hat nicht geantwortet, wie sie soll" — das darf niemand
   wegklicken.
2. **Ein neuer Lauf ersetzt den ausstehenden Plan.** Sonst staut sich alle sechs Stunden ein weiterer,
   und irgendwann wird der älteste bestätigt.
3. **Bestätigt wird gegen einen frischen Schnappschuss** — der Plan wird beim Bestätigen neu gerechnet;
   weicht das Ergebnis vom gezeigten ab, wird neu vorgelegt (so bereits #1816). Nach einer Rücksicherung
   überschreitet der erste Lauf regelmäßig die Schwelle, weil sich zwei Tage Verzeichnisänderungen auf
   einmal zeigen.
4. **Ein ausstehender Plan ist ein lauter Zustand.** Sein Alter steht in der Statuszeile
   (`directory_sync_status` trägt bereits `last_run_at`/`last_outcome`) und auf der Verwaltungsübersicht,
   nicht nur in einer Unterseite. „Der Abgleich lag seit dem 01.06. als unbestätigter Plan" ist sonst der
   Prüfbefund zu einem Konto, das am 01.09. noch Leserechte hatte.

**Kontosperren aus dem Abgleich (#1818) unterliegen derselben Schwelle und demselben Bestätigungsweg**
wie Mitgliedschaftsentzüge — #1818 sieht das vor, das Papier sagt es jetzt ausdrücklich (Personalrat,
Bedingungen C1 bis C3): Ein Lauf, der 40 Konten sperren würde, weil im Verzeichnis ein Attribut
umgestellt wurde, wartet auf Bestätigung, statt 40 Beschäftigte am Morgen vor einem gesperrten Zugang
stehen zu lassen. Eine Sperre aus dem Abgleich ist **rückholbar** und hinterlässt keinen Bruch in der
Historie (der Kontozustand wird historisiert, Abschnitt 9a). Die betroffene Person erhält bei der
nächsten Anmeldung **Grund und Ansprechstelle**, nicht nur eine Abweisung.

### 4.3b Nebenwirkung auf die Vollmacht „Sicht als"

`DiagnosticImpersonationGrantService#grant` lehnt jeden Geltungsbereich ab, der keine `ORG_UNIT`-Gruppe
ist („keine Ad-hoc-Gruppe und keine Gruppe aus dem Identitätsanbieter"). Mit „je Anbieter genau ein
Mechanismus" folgt: In einem Haus, das im Token-Modus bleibt, entstehen nie `ORG_UNIT`-Gruppen, und die
Vollmacht ist dort dauerhaft nicht vergebbar (Betrieb, Auflage 3.6). **Empfehlung:** Der Geltungsbereich
wird auf **jede Anbietergruppe** erweitert (`ORG_UNIT` und `IDENTITY_PROVIDER`), sofern sie oberhalb
der Mindestgruppengröße liegt; interne Gruppen bleiben ausgeschlossen. Eine Token-Gruppe „Referat 50" ist
dieselbe Organisationseinheit wie die Verzeichnisgruppe gleichen Namens — nur anders gepflegt.

### 4.4 Verhalten beim Wechsel des Mechanismus

Wechselt ein Anbieter von Token auf Pull, sind seine Token-Gruppen (`IDENTITY_PROVIDER`) und die neuen
Verzeichnisgruppen (`ORG_UNIT`) verschiedene Objekte. Empfehlung: Der erste Lauf zeigt im Differenzbericht
neben den neuen Gruppen die Token-Gruppen als **„werden nicht mehr gepflegt"**; sie bleiben mit
eingefrorener Mitgliedschaft stehen (wie `dissolved`), sind kein neues Grant-Ziel mehr, und die
Verwaltung bietet je Token-Gruppe die **Übertragung** ihrer Rechte auf die Verzeichnisgruppe an — das
ist kein Sonderweg mehr, sondern ein Anwendungsfall der allgemeinen Übertragungsoperation (Abschnitt 9c).
Nichts wird stillschweigend entzogen; der Wechsel ist ein Verwaltungsakt mit Bericht.

### 4.5 Was von der Spezifikation bleibt

`access-control.md` verspricht SCIM als bevorzugten Weg. Die Empfehlung ersetzt das durch „Pull je
Anbieter, SCIM ausdrücklich später"; der ADR sollte den Satz umschreiben, nicht nur ergänzen. Die
übrigen Zusagen — Leerergebnis, Schwelle, Trockenlauf, last-known-good, eine Protokollzeile je
bewirkter Änderung — sind gebaut und bleiben.

---

## 5. Frage 4: Pflege interner Gruppen

### 5.1 Das Problem

Interne Gruppen (`AD_HOC`) existieren, aber jede Methode in `GroupController` verlangt `SYSTEM_ADMIN`.
Die Vorentscheidung „Wer eine Querschnittsgruppe braucht, legt sie explizit an" trägt nur, wenn
Anlegen und Mitgliederpflege ohne Ticket an die Systemverwaltung gehen. Lokale Konten (ADR-0033)
bekommen Gruppen ausschließlich auf diesem Weg.

### 5.2 Die Optionen

| Option | Wer legt an | Wer pflegt Mitglieder | Beurteilung |
|---|---|---|---|
| **A — nur Systemverwaltung** (heute) | `SYSTEM_ADMIN` | `SYSTEM_ADMIN` | Jede Mitgliederänderung ein Ticket; Querschnittsgruppen entstehen nicht, weil der Weg zu lang ist. Das ist die Wirklichkeit, die der Skeptiker beschreibt: Die Pflegearbeit bleibt liegen. |
| **B — Gruppenverantwortliche** | wer die Fähigkeit „interne Gruppe anlegen" hat; wird erster Verantwortlicher | die Verantwortlichen der Gruppe | Delegation wie Nextclouds Gruppenadministratoren und Jiras Projektadministratoren, aber ohne zweites Subjekt; jede Änderung mit dem Verantwortlichen als Akteur im Audit |
| **C — Selbstbeitritt** (offene Gruppen wie `OPEN`-Spaces) | wie B | jeder tritt selbst bei | Für Rechtesubjekte ungeeignet: Wer einer Gruppe beitreten kann, an der ein Grant hängt, gibt sich selbst Rechte. **Verworfen** für Gruppen; Selbstbeitritt gehört zu Spaces (`visibility = OPEN`), die keine Rechte an Assets tragen. |
| **D — Space-Admin pflegt „seine" Gruppe** | Space-`ADMIN` | Space-`ADMIN` | Bindet die Gruppe an einen Space und erzeugt genau die Standortbindung, die SharePoint-Gruppen zum Pflegeproblem macht. **Verworfen.** |

### 5.3 Empfehlung: Option B, Gruppenverantwortliche

- **Jede interne Gruppe hat einen oder mehrere Verantwortliche**, ausschließlich natürliche Personen
  (keine Gruppe als Verantwortliche — das wäre Schachtelung durch die Hintertür). Verantwortliche sind
  nicht automatisch Mitglied.
- **Verantwortliche dürfen:** Mitglieder aufnehmen und entfernen (nur Konten der eigenen Organisation,
  wie heute `requireUserInOrganization`), Name und Beschreibung ändern, weitere Verantwortliche
  ernennen und entlassen, die Gruppe **zur Verwendung freigeben** (Abschnitt 9b: erst danach ist sie für
  andere Rechtevergebende wählbar), die Gruppe als **geschützt** kennzeichnen (Abschnitt 9b), und die
  Gruppe löschen — Letzteres unter denselben Bedingungen wie heute `deleteGroup` (409, solange die
  Gruppe Grants trägt, Assets besitzt oder Space-Mitglied ist).
- **Der letzte Verantwortliche kann sich nicht entfernen** (GitLab: „unless they are the only Owner").
  Scheidet er aus (Kontosperre), greift der Lebenszyklus (Abschnitt 7): Die Gruppe ist „Nachfolge
  offen", die Systemverwaltung ernennt einen neuen Verantwortlichen.
- **Verantwortung abgeben ist ein eigener Schritt.** Der häufigste Fall in der Verwaltung ist nicht die
  Kontosperre, sondern der Referatswechsel bei fortbestehendem Konto: Die Verantwortliche der Gruppe
  „Vergabestelle intern" wechselt ins Referat 30 und bekommt drei Monate später noch die Bitte, Herrn
  Schulz aufzunehmen — und tut es, weil sie es technisch kann (Sachbearbeitung). Ein Automatismus ist
  nicht möglich, ohne die Gruppenmitgliedschaften einer Person auszuwerten (ein Referatswechsel ist im
  Verzeichnis kein Ereignis, sondern ein Gruppenwechsel), und er ist nicht erwünscht. Stattdessen:
  „Meine Gruppen" zeigt der Person ihre **Verantwortlichkeiten** mit der Handlung „Verantwortung
  abgeben an …" (Übertragung an eine benannte Person, Abschnitt 9c); die Ernennung nennt beim Anlegen
  die Erwartung, dass Verantwortung beim Aufgabenwechsel abgegeben wird. Kein Zwang, kein Signal aus
  Personendaten — ein sichtbarer Ausgang.
- **Das Anlegen ist eine globale Fähigkeit** (`CREATE_INTERNAL_GROUP`, Abschnitt 6). Ausgeliefert wird
  sie **an niemanden** — heute kann nur `SYSTEM_ADMIN` anlegen, und Auslieferung darf Verhalten nicht
  ändern. Das Handbuch empfiehlt bei der Einführung ausdrücklich, sie an eine Verzeichnisgruppe wie
  „Referatsleitungen" zu vergeben, damit die erste Querschnittsgruppe „Gecko" nicht doch ein Ticket
  braucht (Sachbearbeitung: „ein echter Fortschritt, aber keine vollständige Selbstständigkeit" — das
  ist beabsichtigt; die Öffnung ist eine Entscheidung des Hauses).
- **Anbietergruppen bleiben schreibgeschützt** (`rejectOrgUnit` bleibt); sie haben keine
  Verantwortlichen.
- **Sichtbarkeit** — wer Gruppennamen, Mitgliederzahl und Mitgliederliste sieht — ist eine Frage, die
  an vier Stellen zugleich entschieden werden muss (Freigabedialog, Space-Mitgliederliste, Herleitung,
  Rechteprofil) und deshalb einmal in **Abschnitt 9b** steht. Die Kurzform: Verantwortliche und
  `SYSTEM_ADMIN` sehen die Mitgliederliste; ebenso, wer der Gruppe an einem Objekt ein Recht einräumt
  oder verwaltet; ein Mitglied sieht seine Gruppen, deren Verantwortliche und deren Größe, nicht die
  übrigen Mitglieder. „Vor Kollegen verborgen" heißt nicht „verborgen" (Personalrat, 4d): Wer eine
  Gruppe anlegt oder ihr ein Recht gibt, sieht, an wen.
- **Symmetrie der Sichtbarkeit:** Mitglieder sehen die Verantwortlichen ihrer Gruppen namentlich
  (`GET /api/v1/me/groups` liefert sie mit), und Aufnahme in eine Gruppe wie Entfernung aus ihr werden
  der betroffenen Person in der Oberfläche angezeigt — über die Benachrichtigungsinfrastruktur aus
  ADR-0019, ohne Mail (Personalrat, 4a). Sonst endet ein Leserecht „sofort", ohne dass die Person
  erfährt, dass und durch wen.
- **Audit, keine Historientabelle.** Jede Mitgliederänderung steht in Rechtehistorie
  (`GroupMembershipHistoryCause.ADDED`/`REMOVED`, mit `actor_user_id`) und Audit mit dem Verantwortlichen
  als Akteur. Ernennung, Entlassung und Abgabe eines Verantwortlichen sind **Audit-Ereignisse, keine
  Historienzeilen**: Verantwortung trägt kein Leserecht und ist für die Frage „wer konnte am Tag X was
  lesen" ohne Bedeutung — nach dem Nachtrag zu ADR-0016 ein Betriebsrecht der Gegenwart wie die
  Vollmacht, das der Aufbewahrungsfrist des Protokolls unterliegt (Personalrat D6; die Gegenauflage des
  Betriebs, Auflage 4.2, ist damit zurückgewiesen — die Prüferfrage „wer war am 14.03.2027
  verantwortlich" ist innerhalb der Protokollfrist beantwortbar, und außerhalb ist sie keine
  Rechtefrage).
- **Wildwuchs sichtbar machen, nicht verhindern:** Wird das Anlegerecht breit vergeben, entstehen
  Gruppen, die nie ein Recht tragen. Die Betriebsliste (Abschnitt 7.5) führt interne Gruppen **ohne
  Wirkung und ohne aktives Mitglied** mit Alter; Löschen bleibt eine Handlung der Verantwortlichen oder
  der Systemverwaltung (Betrieb, Auflage 4.3).
- **API ohne `/admin`**, Rechteprüfung je Gruppe; ein Nichtverantwortlicher erhält dieselbe Antwort wie
  für eine unbekannte Gruppe (404, wie `loadGroup` heute über die Organisationsgrenze hinweg).

**Folge für #1814:** Ergänzt werden Freigabe zur Verwendung, Schutzkennzeichen, Abgabe der
Verantwortung, Anzeige der Verantwortlichen für Mitglieder, Benachrichtigung bei Aufnahme und
Entfernung; Verantwortliche ohne Historientabelle.

## 6. Frage 5: Globale Berechtigungen (Fähigkeiten)

### 6.1 Liste für den ersten Schritt

| Fähigkeit | Heute | Auslieferung | Begründung |
|---|---|---|---|
| `CREATE_SPACE` | jeder (`authenticated`) | „Alle Konten" | Vorentscheidung; der persönliche Space entsteht bei der ersten Anmeldung **unabhängig** davon (Bereitstellung, kein Anlegen) |
| `CREATE_LIBRARY` (Upload-Bibliothek) | jeder (ADR-0018, Entscheidung 6) | „Alle Konten" | Vorentscheidung; löst ADR-0018/6 ab |
| `CREATE_CONNECTOR_LIBRARY` (lauf-basierte Quellen: Dateisystem, Webverzeichnis, RSS, Confluence, S3) | jeder (#484) | „Alle Konten" | Eigene Fähigkeit, weil Konnektorbibliotheken Serverpfade und Zugangsdaten erreichen und die Freigabe-Obergrenze (#797) tragen — ein Haus will sie typisch enger vergeben als Upload-Bibliotheken. Auslieferung wie heute, damit sich nichts ändert |
| `CREATE_INTERNAL_GROUP` | nur `SYSTEM_ADMIN` | **niemand** | Abschnitt 5; Auslieferung erhält das heutige Verhalten |

Das Handbuch nennt `CREATE_CONNECTOR_LIBRARY` **als ersten Kandidaten, den ein Haus nach der Migration
auf eine benannte Gruppe einschränkt** — mit Begründung (Serverpfade, Zugangsdaten, Freigabe-Obergrenze
#797), nicht nur als technische Möglichkeit (Referatsleitung, Auflage 5). Und der ausgelieferte Zustand
bleibt sichtbar: Die Verwaltungsübersicht zeigt je Fähigkeit den aktuellen Stand als Klartextzeile
(„Alle Konten dürfen Konnektorbibliotheken anlegen"), damit er bei der Einführung bewusst bestätigt oder
geändert wird — eine Anzeigezeile, kein Assistent (Betrieb, Auflage 5.3).

Bewusst **nicht** in der ersten Liste: „Space organisationsweit sichtbar machen", „Bibliothek
organisationsweit freigeben", „Fremdzugang freigeben" — das sind Reichweitenentscheidungen am Objekt
(`visibility`, `listed`, `external_access_state`), für die `MANAGER`/`OWNER` und die Obergrenze (#797)
zuständig sind; sie als globale Fähigkeit zu doppeln, erzeugte zwei Prüfstellen für dieselbe Frage.

### 6.2 Verhältnis zu `SystemRole` und zu den Befugnissen

- **`SYSTEM_ADMIN` besitzt jede Fähigkeit implizit.** Die Systemrolle bleibt die Verwaltungsrolle;
  Fähigkeiten sind darunter angesiedelt und **niemals** Leserechte: Eine Fähigkeit öffnet einen
  Anlegepfad, nie einen Inhalt.
- **`AUDITOR` besitzt keine Fähigkeit.** Die Rolle ist ein Lesepfad in das Protokoll und sonst nichts
  (`SystemRole`-Javadoc).
- **„Sicht als" und Vorfallsbereich bleiben Befugnisse, keine Fähigkeiten.** Beide sind gebunden
  (Geltungsbereich, Frist, bei Vorfällen Zweck und Vier-Augen-Freigabe), folgen aus keiner Rolle und
  dürfen **nicht** an Gruppen oder „Alle Konten" vergebbar sein — eine befristete, bereichsgebundene
  Erlaubnis, personenbezogene Sichten einzunehmen, ist das Gegenteil einer unbefristeten,
  installationsweiten Anlegeerlaubnis. Das Papier empfiehlt, den Unterschied im ADR festzuschreiben:
  *Fähigkeit* = unbefristet, an Nutzer/Gruppe/Alle, ohne Gegenstand; *Befugnis* = befristet, an genau
  eine Person, mit Gegenstand und Begründungspflicht.
- **Keine neue Systemrolle.** „Bibliotheksverwalter", „Gruppenverwalter" als Rollen wären genau die
  Rechtebündel, die die Vorentscheidung ausschließt. Ein Haus, das einen „Bibliotheksverwalter" will,
  legt eine Gruppe an und gibt ihr die Fähigkeiten.

### 6.3 Datenmodell: objektbezogen oder global?

Das Epic stellt die Frage „objektbezogene Berechtigung (Space X) vs. globale Berechtigung (Objekt =
Installation)". Zwei Optionen:

**Option 1 — Fähigkeit als Grant auf ein Pseudo-Asset „Organisation" in der typunabhängigen
Grant-Tabelle (#1811).** Verlockend, weil dann alles ein Grant ist. Aber Asset-Grants tragen eine
**gestufte Rolle** (`VIEWER < EDITOR < MANAGER < OWNER`, `atLeast`), eine Fähigkeit ist ein
**Mengenelement** (hat/hat nicht). Eine Fähigkeit als „Rolle" zu codieren, zwingt entweder eine
künstliche Rangordnung („`CREATE_SPACE` < `CREATE_LIBRARY`"?) oder bricht `atLeast`. Auch „Alle Konten"
passt nicht in ein Grant, dessen Subjekt Nutzer oder Gruppe ist — ein synthetisches Gruppenobjekt
„Alle Konten" würde in jeder Auswahl auftauchen und `visibility = ORGANIZATION` doppeln.

**Option 2 — eigene Tabelle `capability_grants`** mit `organization_id`, `capability` (Enum, Prüfbedingung),
Subjekt nach demselben Muster wie `asset_grants` (`subject_user_id` | `subject_group_id`) **plus** der
dritten Subjektart `ALL_ACCOUNTS`; Eindeutigkeit je (Organisation, Fähigkeit, Subjekt); eigene
Historientabelle `capability_grant_history` mit `valid_from`/`valid_to` nach dem Muster von
`group_membership_history` (Betrieb, Auflage 5.1) und nach ADR-0016 (Subjektspalten `RESTRICT`,
Gruppen-ID ohne FK); Audit-Ereignisse `CAPABILITY_GRANTED`/`_REVOKED` — **als Governance-Ereignisse**,
die im Auszug für die Personalvertretung erscheinen (Personalrat E5): Der Entzug einer Fähigkeit von
„Alle Konten" ändert die Arbeitsbedingungen aller Beschäftigten und ist kein technisches Ereignis unter
vielen. Für `ALL_ACCOUNTS` ist die Stichtagsauskunft nur so gut wie die Kontenmenge zum Stichtag:
„jedes Konto durfte es" braucht den Beleg, dass X am 01.03. ein aktives Konto hatte — deshalb wird der
Kontozustand historisiert (Abschnitt 9a; Betrieb, Auflage 5.2).

**Empfehlung: Option 2.** Sie nutzt `PermissionSubject` und `GroupMembershipResolver` (dieselbe
Auflösung, dieselbe Organisationsgrenze) und hält Rang und Menge auseinander. Das typunabhängige
Grant-Fundament aus #1811 bleibt für Assets — das ist auch der Grund, warum #1813 von #1811 abhängt:
das neue Paket (`io.opaa.permission`) beherbergt beide Tabellen und die eine Auswertung
`hasCapability(user, capability)` = `SYSTEM_ADMIN ∨ Grant an ALL_ACCOUNTS ∨ Grant an Nutzer ∨ Grant an
eine seiner Gruppen`.

### 6.4 Durchsetzung

- Prüfung im Service (`SpaceService#createSpace`, `KnowledgeLibraryService#createLibrary`, künftig
  `GroupService#createGroup`), Antwort `403` mit Grund und Hinweis, an wen man sich wendet; das `403`
  wird in der Spezifikation als eigene Entscheidung der Operation deklariert
  (`TransportStatusCodeSpecificationTest`).
- **Entzug wirkt ohne Neuanmeldung**, weil je Anfrage aus der Datenbank ausgewertet wird; der Cache
  der Fähigkeitszeilen (klein, je Organisation) wird nach Commit invalidiert wie
  `GroupMembershipResolver`. Die Zusage trägt, weil ADR-0021 einen einzigen Prozess voraussetzt; fällt
  diese Annahme, fällt die Zusage mit — das gehört als Satz in den ADR (Betrieb).
- `GET /api/v1/me` liefert die eigenen Fähigkeiten, damit die Oberfläche Schaltflächen passend zeigt
  und bei fehlender Fähigkeit erklärt statt versteckt (#1820).
- **Migration:** Vier Zeilen je Organisation (`CREATE_SPACE`, `CREATE_LIBRARY`,
  `CREATE_CONNECTOR_LIBRARY` an `ALL_ACCOUNTS`; `CREATE_INTERNAL_GROUP` ohne Zeile); Delta-Test.
  Nach der Migration verhält sich jede Installation wie vorher.

**Folge für #1813:** Die Liste wird um `CREATE_CONNECTOR_LIBRARY` und `CREATE_INTERNAL_GROUP` ergänzt;
„Alle Konten" wird als dritte Subjektart modelliert, nicht als eingebautes Gruppenobjekt.

---

## 7. Frage 6: Lebenszyklus

### 7.1 Begriff: der handlungsfähige Verantwortliche

Alle Fälle lassen sich auf eine Frage zurückführen: **Hat dieses Objekt gerade jemanden, der für es
handeln kann?** Für ein Asset ist das der Eigentümer (Person: Konto aktiv; Gruppe: nicht aufgelöst und
mindestens ein aktives Mitglied); für einen Space der Eigentümer oder ein wirksames `ADMIN`-Mitglied
(Person aktiv, oder Gruppe nicht aufgelöst mit mindestens einem aktiven Mitglied); für eine interne
Gruppe mindestens ein aktiver Verantwortlicher.

**„Nachfolge offen" ist die Abwesenheit eines handlungsfähigen Verantwortlichen** — und sollte als
**abgeleiteter Zustand** ausgewertet werden, nicht als gespeichertes Flag. Ein Flag müsste an jedem
Auslöser (Kontosperre, Gruppenaustritt, Auflösung, Wiederaufnahme, Entsperrung) gesetzt und
zurückgenommen werden und driftete beim ersten vergessenen Pfad; eine Ableitung ist an jedem Ort
dieselbe Abfrage. Das folgt dem Muster „abgeleiteter Kontozustand statt `status`-Spalte" aus ADR-0033.
Gespeichert wird nur der **Vorgang**: wann der Zustand erstmals festgestellt wurde, wer ihn beendet hat.
Weil ein abgeleiteter Zustand keinen Auslöser hat, der schreibt, legt ein **benannter, regelmäßiger
Feststellungslauf** (Intervall in der Konfigurationstabelle des Handbuchs; Vorgabe stündlich) die
Vorgänge an und schließt sie — die Ableitung bleibt die Wahrheit, der Lauf schreibt nur den
Zeitstempel. Sonst hieße „Alter" in Wahrheit „seit dem letzten Hinsehen" (Betrieb, Auflage 6.3).
Die Nachfolgevorgänge unterliegen der Aufbewahrungsfrist des Protokolls, nicht der Rechtehistorie: Sie
sagen nichts über Leserechte aus (Personalrat, E3).

### 7.2 Die Fälle

| Auslöser | Wirkung auf Rechte | Wirkung auf Objekte |
|---|---|---|
| **Austritt aus der Organisation** (Konto gesperrt — durch Abgleich #1818, Anbieter oder Verwaltung) | Zugang sofort weg; Sitzungen und Zugangstokens unwirksam. **Mitgliedschaften bleiben stehen** (reversibel, Historie ohne Bruch); erst die Kontolöschung entfernt sie (`security-and-compliance.md`, Schritt 3) | Assets im Eigentum der Person und Spaces, in denen sie einziger wirksamer `ADMIN` war → „Nachfolge offen" |
| **Austritt aus einer Gruppe** (Token, Abgleich, Verantwortlicher) | Rechte über die Gruppe enden sofort (Cache-Invalidierung nach Commit; #1815 verlangt dasselbe für Spaces) | keine, es sei denn, die Gruppe wird dadurch leer (nächste Zeile) |
| **Gruppe leer** (letztes Mitglied ausgetreten) | Grants an die Gruppe bleiben, wirken für niemanden | Assets im Eigentum der Gruppe, Spaces mit ihr als einzigem `ADMIN` → „Nachfolge offen"; **endet von selbst**, sobald wieder ein Mitglied da ist (abgeleiteter Zustand) |
| **Gruppe aufgelöst** (Verzeichnis meldet sie nicht mehr; heute `dissolved`) | Mitgliedschaft eingefroren, kein neues Grant-Ziel (`requireGrantableGroup`) | Assets/Spaces → „Nachfolge offen"; endet nur durch Übernahme (oder Reaktivierung durch das Verzeichnis, `Group#reactivate`) |
| **Letzter Verantwortlicher einer internen Gruppe ausgeschieden** | Mitglieder unverändert | Gruppe → „Nachfolge offen" (kein neuer Verantwortlicher kann sich ernennen); Systemverwaltung ernennt |
| **Referatswechsel bei aktivem Konto** (Verantwortlicher, Eigentümer, Space-`ADMIN`) | keine — das Konto ist aktiv, nichts ist abgeleitet | kein Zustand; der Ausgang ist die ausdrückliche Abgabe (Abschnitt 5.3, Abschnitt 9c) |
| **Gruppe mit Wirkung wird leer** (Grants, Space-Mitgliedschaft), ohne Eigentum | Grants bleiben, wirken für niemanden | kein „Nachfolge offen" (kein Eigentum betroffen), aber Eintrag in „Freigaben ohne Empfänger" (Abschnitt 7.5) |

Was „Nachfolge offen" **bewirkt** (aus #240 und der Spezifikation übernommen, unverändert): Das Objekt
bleibt nutzbar, Rechte bleiben, aber die **Reichweite ist eingefroren** — keine neuen Grants, keine
höhere Freigabestufe, keine neue Bereitstellung, für Spaces keine neuen Mitglieder. Nichts wird
gelöscht.

### 7.3 Wer ist Adressat des Nachfolgevorgangs?

#240 setzte den Kurator der Organisationseinheit mit Eskalation nach oben; Kuratoren sind gestrichen.
Optionen:

| Option | Beurteilung |
|---|---|
| **Systemverwaltung als alleiniger Adressat** | Einfach und immer bestimmbar; im dritten Jahr eine Liste, die niemand abarbeitet, weil die Systemverwaltung den Fachbezug nicht kennt |
| **Vorgesetzte aus dem Verzeichnis** (`manager`-Attribut) | Fachlich richtig, aber das Attribut ist in Verwaltungsverzeichnissen unzuverlässig gepflegt, und OPAA hat es nicht (Kontenmodell ADR-0025) |
| **Aktivste Mitglieder** (M365-Muster) | Personenbezogene Aktivitätsauswertung; verworfen |
| **Gestufte Zuständigkeit** — zuerst die nächstliegende benannte Stelle, dann Systemverwaltung als Auffang | Nutzt Zuständigkeiten, die es schon gibt, ohne neue Objekte |

Die erste Fassung wählte die gestufte Zuständigkeit und widerlegte sich in derselben Tabelle: Für
Verzeichnisgruppen als Eigentümer — nach Abschnitt 3.4 und der Spezifikation der Regelfall zentral
gepflegter Bestände — landete die leere Gruppe bei der Systemverwaltung ohne Frist, also bei genau der
Liste, „die niemand abarbeitet" (Referatsleitung). Der Betrieb hat das Problem präziser gefasst: Ohne
Zeitelement ist die Stufung keine Stufung, sondern eine feste Zuweisung — ein Fall der Stufe 2 erreicht
die Stufe 3 **nie**, und die Prüferfrage „nennen Sie alle Bestände ohne handlungsfähigen
Verantwortlichen" ist mit einer Liste, die Stufe 1 und 2 nicht enthält, nicht beantwortbar (Auflage 6.1).

**Aufgelöst — die Stufung ist eine Zuständigkeitsangabe, die Liste ist vollständig:**

1. **Die Liste der Systemverwaltung enthält alle offenen Nachfolgen ab dem ersten Tag**, mit den
   Spalten „Objekt", „derzeitiger Adressat" und „Alter" (Zeitpunkt der Erstfeststellung durch den Lauf
   aus Abschnitt 7.1). Keine Frist, keine Mail, keine Eskalationslogik — Vollständigkeit. Die
   Auffangzuständigkeit ist damit jederzeit handlungsfähig, auch wenn der Adressat der Stufe 1 oder 2
   zwei Jahre nichts tut.
2. **Adressat je Fall:** Space → die übrigen wirksamen `ADMIN`-Mitglieder (ist noch eines wirksam, ist
   der Space nicht „Nachfolge offen"; nur der Eigentümerwechsel ist nötig, und den darf künftig jeder
   wirksame `ADMIN` an sich oder einen anderen `ADMIN` übertragen — heute nur Eigentümer oder
   `SYSTEM_ADMIN`, das ist die eine Verhaltensänderung). Asset einer internen Gruppe → deren
   Verantwortliche. Alles andere (Asset einer Person, Asset einer Verzeichnisgruppe, Gruppe ohne
   Verantwortliche) → Systemverwaltung.
3. **Die Übernahme ist die Übertragungsoperation** (Abschnitt 9c): Eigentum oder Verantwortung geht
   protokolliert auf eine Person oder Gruppe über, mit Historienzeile und Vorgangsbezug; das beendet
   den Zustand. Für den Regelfall Verzeichnisgruppe heißt das: Referat 50 wurde aufgelöst, seine
   Bibliotheken gehen mit **einer** Übertragung an Referat 52 — nicht Objekt für Objekt.
4. **Alterungsschwelle mit Sichtungsvermerk, ohne Zwang.** Einträge, die älter sind als eine
   konfigurierbare Schwelle (Vorgabe 12 Monate, orientiert an der Höchstfrist der Vollmacht), werden in
   der Liste hervorgehoben; die Systemverwaltung kann einen Sichtungsvermerk setzen („geprüft am …,
   weiterhin offen, Grund"), der die Hervorhebung für eine weitere Periode aufhebt. Das ist die
   „Pflichtsichtung" der Referatsleitung in der Form, die ohne Frist auskommt: Sie verhindert, dass eine
   Zeile zehn Jahre unberührt bleibt, ersetzt aber keine fachliche Zuständigkeit und löst nichts aus.
5. **Sichtbar am Objekt, nicht nur in der Liste.** Ein Asset oder Space im Zustand „Nachfolge offen"
   trägt die Kennzeichnung in seiner Übersicht und Detailansicht für jeden, der es lesen darf, samt
   Adressat („Nachfolge offen seit 14.03.2026 — zuständig: Systemverwaltung"). Sonst zitiert ein
   Mitarbeiter eine Quelle, ohne zu wissen, dass sie seit zwei Jahren niemand fachlich verantwortet
   (Referatsleitung), und ein Team merkt den Zustand erst, wenn das Hinzufügen einer Kollegin scheitert
   (Sachbearbeitung). **Nicht** gekennzeichnet werden Suchtreffer und Quellenverweise in Antworten: Der
   Zustand betrifft die Zuständigkeit, nicht die Richtigkeit des Inhalts, und ein Zitat ist kein
   Verwaltungsort — die Kennzeichnung dort würde jede Antwort zu einer Zustandsauswertung machen.
6. **Die Liste ist objektbezogen.** Einstieg über das Objekt; der frühere Eigentümer wird je Zeile
   genannt (sonst ist die Nachfolge nicht beurteilbar), aber es gibt keine Abfrage „was gehörte
   Person X" und keine Sortierung oder Filterung nach Person — dieselbe Regel wie für die Berichte in
   `security-and-compliance.md` („Einstieg über das Objekt und einen Zeitraum, nie über eine Person";
   Personalrat E1).

**Hinweis statt Auswertung:** Die Liste darf als **Vorschlag** die Verzeichnisgruppen des Ausgeschiedenen
nennen („war Mitglied von Referat 50") — das ist Bestandsinformation, keine Aktivitätsauswertung, und
hilft der Systemverwaltung, die richtige Gruppe als neuen Eigentümer zu wählen. Gruppen-Eigentum ist
weiterhin der Regelfall für zentral gepflegte Bestände; die Liste ist das Auffangnetz.

### 7.4 Schutzregeln, die den Zustand vermeiden

- **Ein Space verliert nie sein letztes wirksames `ADMIN`-Mitglied durch eine Verwaltungshandlung**:
  Entfernen, Herabstufen oder Austritt des letzten `ADMIN` wird mit `409` abgelehnt (heute nur für den
  Eigentümer). **Eine Gruppe zählt als `ADMIN`, solange sie wirksam ist** (Begriff in Abschnitt 0) —
  das beantwortet die offene Frage aus #1815.
- **Eine Kontosperre wird nie abgelehnt.** Sie ist die eine Handlung, die den Zustand erzeugen darf,
  weil der Zugang wichtiger ist als die Zuständigkeit (Spezifikation: „Die Deaktivierung wird nie durch
  offene Eigentumsfragen aufgehalten").
- **Der letzte Verantwortliche einer internen Gruppe kann sich nicht entfernen** (Abschnitt 5).
- **Nur wirksame Gruppen sind neues Grant-Ziel und neues Space-Mitglied.** Das deckt aufgelöste Gruppen
  (heute `requireGrantableGroup`), leere Gruppen und Gruppen deaktivierter Anbieter (Abschnitt 3.4)
  mit **einer** Regel und einer verständlichen Meldung.
- **„Nachfolge offen" lässt laufende Arbeit unberührt.** Das Objekt bleibt nutzbar, bestehende Rechte
  bleiben; nur die Reichweite ist eingefroren. Der Satz gehört in ADR und Handbuch, sonst wird aus einem
  Verwaltungszustand ein Arbeitshindernis, dessen Druck bei den Betroffenen landet (Personalrat E2).

### 7.5 Die Betriebsliste: Nachfolgen, Freigaben ohne Empfänger, Gruppen ohne Wirkung

Der Lebenszyklus braucht **eine** Verwaltungssicht, nicht drei. Sie hat drei Reiter mit derselben
Mechanik (Feststellungslauf, Alter, objektbezogener Einstieg, Sichtungsvermerk):

| Reiter | Inhalt | Warum |
|---|---|---|
| **Offene Nachfolgen** | Assets, Spaces, interne Gruppen ohne handlungsfähigen Verantwortlichen; Adressat, Alter | Abschnitt 7.3 |
| **Freigaben ohne Empfänger** | wirksam gewesene Gruppen, die Grants tragen oder Space-Mitglied sind und **kein aktives Mitglied** mehr haben; Zahl der betroffenen Objekte, Alter | Ohne sie steht in der Freigabeliste einer Bibliothek weiter „Referat 50 — VIEWER", und niemand liest damit; nach einer Umbenennung im Token-Modus (Abschnitt 4.2) sind 40 Freigaben tot, und nichts zeigt es an (Betrieb, Auflage 6.2). Die Übertragung (Abschnitt 9c) ist der Ausgang |
| **Gruppen ohne Wirkung** | interne Gruppen ohne Grant, ohne Space-Mitgliedschaft, ohne Eigentum und ohne aktives Mitglied; Alter | Wildwuchs sichtbar machen (Betrieb, Auflage 4.3); Löschen bleibt Handlung |

**Folge für #1819:** „Nachfolge offen" als abgeleiteter Zustand mit Feststellungslauf statt gespeichertem
Flag; vollständige Liste mit Adressat und Alter; Alterungsschwelle mit Sichtungsvermerk; Kennzeichnung
am Objekt; Übernahme als Übertragungsoperation; die zwei weiteren Reiter der Betriebsliste. Frist,
Eskalation und Mail bleiben bewusst draußen. Typunabhängigkeit bleibt (#1726).

## 8. Frage 7: Diagnose im Gruppenkontext

### 8.1 Was existiert

Das Rechteprofil der Suchdiagnose **ist** eine Gruppe: `SearchDiagnosisService.PermissionProfile(id,
name, libraryCount)` wird aus `GroupService#listGroups` gebildet, die Bibliotheksmenge aus
`LibraryAccessService#readableLibraryIdsForGroup` (direkte Gruppen-Grants ∪ Gruppeneigentum ∪
organisationsweite Freigabe). Der Profilkontext ist die Voreinstellung, braucht keine Befugnis und keine
Begründung, und `target_ref` trägt die Gruppen-ID, „damit ‚kein Personenbezug im Protokoll' eine
Struktureigenschaft" ist (`hybrid-retrieval.md`). Damit ist die Randbedingung des Epics — eine Auswertung
im Kontext einer Gruppe ist weniger personenbezogen als im Kontext einer Person — für Bibliotheken
bereits eingelöst.

### 8.2 Was für Spaces fehlt — und was die erste Fassung dabei aufhob

1. **Gruppen sind heute keine Space-Mitglieder.** Ein Profil hat deshalb keinen Space-Kontext; die
   Diagnose läuft immer über den Bibliotheksbestand der Organisation, nicht über den Suchbereich eines
   Chats in einem bestimmten Space (`spaces-and-assets.md`, „Suchbereich je Chatart"). Mit #1815 lässt
   sich das Profil um „Gruppe G im Space S" erweitern: Suchbereich = im Space assoziierte Bibliotheken
   ∩ für G lesbare Bibliotheken — eine optionale Space-ID im Profilkontext von `SearchDiagnosisRequest`,
   nach #1815, nicht davor.
2. **Herkunft im Profilnamen.** Mit zwei Anbietern gibt es zwei „Referat 50"; die Profilliste braucht
   dieselbe Herkunftsanzeige wie die Subjekt-Auswahl (Abschnitt 3.3) und unterliegt derselben
   Sichtbarkeitsregel (Abschnitt 9b): Wer ein Profil wählen darf, sieht Gruppennamen.
3. **Kleine Gruppen sind Personen.** Eine Token-Gruppe mit einem Mitglied oder eine interne Gruppe
   „Projektleitung X" mit zwei Mitgliedern ist als Rechteprofil de facto ein Personenkontext — ohne
   Vollmacht, ohne Begründung, ohne Protokoll. Gruppen unterhalb der **Mindestgruppengröße** sind
   deshalb kein wählbares Rechteprofil.
4. **Die erste Fassung hob Punkt 3 mit Punkt 1 wieder auf** — der Personalrat hat es gefunden, und
   deshalb war Empfehlung 7 nicht mitbestimmungsfähig: Die Schwelle hing an der Gruppe, nicht an der
   Schnittmenge. Ein Profil „Referat 50" (23 Mitglieder) in einem Space, in dem aus Referat 50 genau
   eine Person Mitglied ist, zeigt, was diese eine Person dort findet — ohne jede Schutzmechanik des
   Personenkontexts, weil formal ein Profil gewählt wurde. **Aufgelöst:** Die Mindestgruppengröße gilt
   für die **Schnittmenge** aus aktiven Mitgliedern der Gruppe und Zugang zum Space, geprüft **zum
   Zeitpunkt des Laufs**; liegt sie darunter, antwortet der Endpunkt mit `403` und dem Hinweis, dass
   für diese Sicht der Personenkontext mit Vollmacht zu wählen ist. Ist die Schnittmengen-Prüfung zu
   teuer, entfällt der Space-Kontext — nicht die Prüfung.
5. **Gezählt werden aktive Konten.** Abschnitt 7.2 lässt Mitgliedschaften gesperrter Konten stehen;
   eine Projektgruppe mit 20 Mitgliedern, von denen 18 gesperrt sind, ist ein Zwei-Personen-Kontext
   (Betrieb, Auflage 7.1). Die Mindestgruppengröße für Profile — und die Mitgliederzahl, die neben dem
   Gruppennamen steht (Abschnitt 9b) — zählt aktive Konten, nicht Mitgliedschaftszeilen. Das ist
   dieselbe Zählweise wie beim handlungsfähigen Verantwortlichen (Abschnitt 7.1). Und es ist ausdrücklich
   ein anderes Maß als die Mindestgruppengröße der Nutzungstransparenz in `spaces-and-assets.md`
   („Zahl der tatsächlich nutzenden Personen"): Für ein Rechteprofil zählt der Rechtekontext, nicht die
   Nutzung. Der ADR spricht beide Lesarten aus (Personalrat, 7d).
6. **Erzwungene Untergrenze.** Die Mindestgruppengröße ist die einzige Schutzregel zwischen Profil und
   Personenkontext; eine Dienststelle, die sie auf 1 stellt, schaltet den Schutz aus. Das Produkt
   setzt eine Voreinstellung **und erzwingt eine Untergrenze**, wie `spaces-and-assets.md` es für die
   Nutzungstransparenz bereits vorsieht (Personalrat B5); Änderungen des Werts sind
   Governance-Ereignisse.
7. **Protokollpflicht für Profil-Läufe mit Space-Kontext.** Mit dem Space-Kontext richtet sich ein
   Profil-Lauf erstmals auf einen konkreten, oft kleinen Personenkreis. Profil-Läufe **mit**
   Space-Kontext werden protokolliert (ausführende Person, Profil, Space, Zeitpunkt — eine Zeile je
   Lauf, keine je Abfrage); Profil-Läufe ohne Space-Kontext bleiben unprotokolliert. Die in
   `hybrid-retrieval.md` offen gelassene allgemeine Protokollpflicht für Profil-Läufe bleibt offen; die
   Regel oben ist das Mindestmaß.
8. **Die Suchdiagnose bleibt `SYSTEM_ADMIN` vorbehalten; keine Fähigkeit öffnet sie.** Die Begründung,
   mit der `hybrid-retrieval.md` die Diagnosesperre (Leitplanke (e)) bei Profil-Läufen nicht anwendet,
   trägt nur, solange die Seite Systemverwaltern vorbehalten ist. Wird sie je geöffnet, schaltet
   dieselbe Änderung die Sperre auch für Profil-Läufe scharf (Personalrat B4). Leitplanke (b) bleibt
   unverändert: Die Diagnose ist kein Zugriffshistorien-Nachweis — dafür gibt es die Stichtagsauskunft
   (Abschnitt 9a), und beide bleiben getrennt.

**Folge für die Umsetzungs-Issues:** Punkt 2 gehört zu #1820/#1821; Punkte 1, 4, 5, 7 und 8 sind
Nacharbeiten an `SearchDiagnosisService` nach #1815 und stehen im ADR als Randbedingung, damit sie
nicht verloren gehen; Punkt 6 ist eine Konfigurationsvalidierung.

---

## 9. Randbedingung Mandantenfähigkeit (#1442)

Alles hier Vorgeschlagene ist je Organisation modelliert: `groups.organization_id`,
`capability_grants.organization_id`, die Verantwortlichen einer Gruppe müssen derselben Organisation
angehören, und die Liste offener Nachfolgen ist eine Sicht je Organisation. Eine Stelle bleibt offen und
gehört zu #1442: `oidc_providers` trägt keine `organization_id` (ADR-0025: „Alle Anbieter
provisionieren in `Organization.DEFAULT_ID`"). Der Fremdschlüssel `groups.provider_id` (Abschnitt 3.1)
ist damit heute organisationsübergreifend; sobald Anbieter einer Organisation zugeordnet werden, muss
die Prüfregel „Gruppe und Anbieter in derselben Organisation" hinzukommen — als zusammengesetzter
Fremdschlüssel, wie ihn `fk_group_memberships_group_organization` heute für Mitgliedschaften zieht.

---

## 9a. Querschnitt: Rechtehistorie, Stichtagsauskunft und Aufbewahrung

Die erste Fassung erweiterte die historisierten Quellen in einem Nebensatz der Entscheidungsvorlage
von drei auf sechs, ohne zu sagen, wer sie liest, wie lange sie bleiben und ob der Abruf protokolliert
wird; die Wörter Stichtag, Aufbewahrung und Pseudonymisierung kamen nicht vor (Personalrat). Der Betrieb
kam von der anderen Seite: Für Space-Mitgliedschaft, Eigentum und Systemrolle gibt es heute keine
Historientabelle, nur Audit-Ereignisse, die nach 12–120 Monaten gelöscht werden
(`chk_audit_retention_settings_months`) — und ausgerechnet #1815 macht die Space-Mitgliedschaft zur
Massenberechtigung. Beide treffen sich in **einer** Entscheidung, die vier Dinge trennt und festlegt.

**Audit verfällt, Historie bleibt.** Ein Audit-Ereignis sagt, *dass* jemand etwas getan hat, und wird
nach der Protokollfrist gelöscht. Eine Historienzeile sagt, *in welchem Zeitraum* ein Recht galt, und
trägt die Stichtagsauskunft. Jede Änderungsart des Epics wird genau einer Seite zugeordnet:

| Änderungsart | Tabelle der Stichtagsauskunft (bleibt) | Audit (verfällt) |
|---|---|---|
| Asset-Grant | `asset_grant_history` (heute) | ja |
| Gruppenmitgliedschaft | `group_membership_history` (heute) | ja |
| Reichweitenfelder am Asset | `library_visibility_history` (heute) | ja |
| **Space-Mitgliedschaft** (Person oder Gruppe, mit Rolle) | **neu**, #1815 | ja |
| **Eigentum** an Asset und Space | **neu** (`asset_ownership_history`), #1819 | ja (`ASSET_OWNER_CHANGED` heute) |
| **Fähigkeit** | **neu** (`capability_grant_history`), #1813 | ja, Governance-Ereignis |
| **Systemrolle** | **neu** — weil `effectiveRole` `SYSTEM_ADMIN` als `OWNER` führt | ja (heute) |
| **Kontozustand** (aktiv/gesperrt) | **neu**, #1818 — Beleg der Kontenmenge zum Stichtag, Voraussetzung für „Alle Konten" und für die rückholbare Sperre | ja (heute) |
| Verantwortliche interner Gruppen | **keine** — Betriebsrecht der Gegenwart (ADR-0016, Nachtrag) | ja |
| Vollmachten | keine (heute, ADR-0016 Nachtrag) | ja |
| Nachfolgevorgänge, Sichtungsvermerke | keine | ja |

Alle Historientabellen tragen `valid_from`/`valid_to` wie `group_membership_history` und folgen ADR-0016
(Subjektspalten `RESTRICT`, Objektspalten ohne FK). Jede neue Tabelle mit Personenspalte wird dieser
Einordnung ausdrücklich zugeordnet („nichts Architektonisches wird implizit festgelegt").

**Wer abruft — der Lesepfad, den es heute nicht gibt.** `PermissionHistoryService#readableLibraryIdsAsOf`
ist gebaut, aber ohne Endpunkt (#1822). Die Stichtagsauskunft ist eine Funktion der Rolle `AUDITOR`,
mit zwei Einstiegen:

- **Objekt-Einstieg** („wer durfte Bibliothek Z am 3. März lesen") — für `AUDITOR` ohne weitere
  Vollmacht, mit verpflichtendem, begrenztem Zeitfenster und Seitenobergrenze; eine zu weite Anfrage wird
  abgelehnt, nicht zurechtgestutzt (Vorbild `AuditFrom`: höchstens 92 Tage; Personalrat D4).
- **Personen-Einstieg** („worauf hatte Person X am 3. März Zugriff") — nur mit einer eigenen,
  befristeten, begründeten **Vollmacht nach dem Muster des Vorfallsbereichs**
  (`audit_incident_scope_grants`: Person, Zeitraum, Zweck, Vier-Augen-Freigabe zweier `AUDITOR`;
  Personalrat D2). Eine Amtsleitung, die 2032 „alles, worauf Herr K. seit 2026 Zugriff hatte" sehen
  will, bekommt das nicht per Rolle, sondern per Vorgang.
- **Jeder Abruf ist selbst ein Protokollereignis**, einschließlich abgewiesener Versuche — analog
  `AUDIT_LOG_ACCESSED` (Personalrat D3).

**Wie lange — Aufbewahrungshöchstdauer.** `security-and-compliance.md` sagt eine Höchstdauer mit
automatischer Löschung zu und stellt fest, dass sie nicht umgesetzt ist. Sie wird
Governance-Einstellung mit erzwungener Ober- und Untergrenze (Untergrenze wie beim Protokoll: nicht
unter einem Prüfzyklus), Änderungen sind Governance-Ereignisse. **Ohne sie wird keine weitere
personenbezogene Historienquelle angeschlossen** — #1813, #1815, #1818 und #1819 hängen an dieser
Entscheidung wie an #1810 (Personalrat D1). Der Namensschnappschuss aus Abschnitt 3.3 ist nur unter
dieser Bedingung zulässig (D5). ADR-0016 bleibt unberührt: Die Historie überlebt die Löschung des
Objekts, nicht die Höchstdauer — das sind zwei Achsen.

**Pseudonymisierung** (#391/#395) ist benannte Voraussetzung für den **Personen-Einstieg** der
Stichtagsauskunft und für die Kontolöschung, die heute an `RESTRICT` scheitert (ADR-0016) — nicht für
das Schreiben von Historienzeilen. Das ist eine Abschwächung von D7: Historienzeilen ohne Lesepfad
erzeugen keinen Auswertungspfad, und die Kontolöschung ist heute für jedes Konto blockiert, das je ein
Recht hatte; eine weitere `RESTRICT`-Spalte ändert daran nichts, solange der Lesepfad die Vollmacht
verlangt. Der ADR verweist auf #391/#395 als Blocker des Personen-Einstiegs.

**Verfahren** (Personalrat F1 bis F3): Vor dem Rollout legt die Dienststelle die Auskunft über die
Datenerhebung vollständig vor — einschließlich der neuen Tabellen; die Personalvertretung erhält einen
Testzugang, um die Zusagen nachzuvollziehen; Änderungen an Mindestgruppengröße, Abgleichintervall,
Plausibilitätsschwelle und Aufbewahrungsfristen sind Governance-Ereignisse und der Personalvertretung
zugänglich. Beides sieht `security-and-compliance.md` bereits vor; das Handbuchkapitel (#1824) nimmt es
auf.

## 9b. Querschnitt: Sichtbarkeit von Gruppen und die Herleitung „warum sehe ich das"

Vier Bewertungen stellten dieselbe Frage von vier Seiten: Der Betrieb fand, dass die Gruppen**liste**
(nicht nur die Mitgliederliste) unentschieden ist — `GET /api/v1/groups` verlangt heute
`SYSTEM_ADMIN`, eine Bibliotheksverwalterin kann eine Gruppe nicht einmal finden. Die Referatsleitung
will als Grant-Geberin die Mitglieder sehen („ich gebe frei, ohne zu wissen, an wen"). Der Personalrat
will Gruppennamen und Mitgliederzahl vor Kollegen schützen und fand, dass #1815/#1820 den Gruppennamen
in jede Space-Mitgliederliste setzen. Die Sachbearbeitung fragt, warum sie eine Bibliothek sieht — und
bekommt vom Modell keine Antwort. Das ist **eine** Entscheidung.

**Was schon gilt und die Frage verkleinert:** Die Space-Mitgliederliste ist heute **nicht** für alle
Mitglieder sichtbar. `SpaceAccessPolicy` beschränkt sie auf `ADMIN`, Eigentümer und `SYSTEM_ADMIN`;
`MEMBER` und `CURATOR` erhalten nur `roleCounts` (`spaces-and-assets.md`, „Die Mitgliederliste ist nicht
jedem Mitglied zugänglich"). Eine Gruppenzeile in dieser Liste sehen also genau die, die die
Mitgliedschaft verwalten — nicht „jeder Kollege im Space". Die Prämisse des Personalrats-Befunds 4(c)
trifft insoweit nicht zu; der Schutz besteht, und die Regel unten macht ihn ausdrücklich.

**Die Regel — „wer ein Recht gibt, sieht, an wen":**

| Wer | sieht Gruppenname und Herkunft | sieht Mitgliederzahl | sieht Mitgliederliste |
|---|---|---|---|
| **Verantwortliche** einer internen Gruppe | ja | voll | ja |
| **`SYSTEM_ADMIN`** | ja | voll | ja — der Abruf ist ein Audit-Ereignis (Personalrat A6; Verantwortliche pflegen sie ohnehin) |
| **Wer der Gruppe an einem Objekt ein Recht einräumt oder verwaltet** (`MANAGER`/`OWNER` des Assets, `ADMIN`/Eigentümer des Space), solange die Gruppe dort ein Recht hält | ja | voll | ja (Referatsleitung, Auflage 4) — Ausnahme geschützte Gruppen, siehe unten |
| **Rechtevergebende** in der Subjekt-Auswahl (Freigabedialog, Space-Mitgliederverwaltung) | Anbietergruppen: ja. Interne Gruppen: nur, wenn ihre Verantwortlichen sie **zur Verwendung freigegeben** haben (Vorgabe: nicht freigegeben — dann wählbar nur für Mitglieder, Verantwortliche, `SYSTEM_ADMIN`; Betrieb, Auflage 4.1) | Zahl aktiver Konten; **unterhalb der Mindestgruppengröße „kleine Gruppe"** statt Zahl (Personalrat A2) | nein |
| **Mitglied** der Gruppe | seine eigenen Gruppen mit Verantwortlichen (`GET /api/v1/me/groups`) | Größe | nein |
| **Sonstige** | nichts | — | — |

Die Freigabe zur Verwendung ist das Gegenstück zu `listed` bei Assets: Auffindbarkeit ist eine bewusste
Handlung der Verantwortlichen, nicht die Vorgabe. Sie beantwortet zugleich die Namenskollision unter
internen Gruppen (Abschnitt 3.3): Was nicht freigegeben ist, kollidiert mit nichts.

**Geschützte Gruppen** (Personalrat A3). Für Gruppen der Personalvertretung, der
Schwerbehindertenvertretung, der Gleichstellung und für Personalvorgänge — die Stellen, die
`hybrid-retrieval.md`, Leitplanke (e), für Bibliotheken benennt — gilt dieselbe Sonderstellung: Das
Kennzeichen setzt und löst die zuständige Stelle selbst (ihre Verantwortlichen), nicht die
Administration (Audit-Ereignis wie `LIBRARY_DIAGNOSTICS_LOCK_CHANGED`). Eine geschützte Gruppe ist nicht
über Suche auffindbar, sondern nur über ihre vollständige Bezeichnung wählbar; in fremden Listen (Space-
Mitgliederliste, Freigabeliste) erscheint sie als „geschützte Gruppe" ohne Namen; und der Grant-Geber
sieht statt der Mitgliederliste die **Verantwortlichen** als Ansprechstelle. Das ist die Ausnahme von
„wer ein Recht gibt, sieht, an wen" — mit einer benannten Person, die er stattdessen fragen kann.

**Die Herleitung für die eigene Person** (#1822, Sachbearbeitung, Personalrat A1). Flach zu *sein* und
das flach zu *zeigen* sind zwei Zusagen; die erste Fassung löste nur die erste ein. Für jede
Bibliothek und jeden Space, den eine Person sieht, zeigt die Oberfläche ihr **den eigenen Weg** zur
effektiven Rolle: direkter Grant; Grant über Gruppe — mit Name, Herkunft, Mechanismus (Token/Abgleich)
und Zeitpunkt des Grants; organisationsweite Freigabe; Eigentum; Fähigkeit. Nur die eigene Herleitung,
ohne Vollmacht, ohne Protokoll; die Mitglieder der Gruppe werden dabei nicht offengelegt. **Gegenüber
anderen** nennt die Herleitung den Gruppennamen nur, wo die Person die Mitgliedschaft verwaltet — also
in der Space-Mitgliederliste für `ADMIN`/Eigentümer („Rolle über Gruppe Referat 50"), die nach der
Regel oben ohnehin die Mitgliederliste dieser Gruppe sehen. Das ist eine Änderung an A1 (dort: nur die
Person selbst, die Verantwortlichen, `SYSTEM_ADMIN`), und sie ist nötig, weil ein Space-`ADMIN`, der
eine Gruppe aufgenommen hat, sonst nicht sehen könnte, was er getan hat.

**Signal bei Gruppenzuwachs** (Referatsleitung, Auflage 2). Die Gruppe bleibt Subjekt am Grant, damit
Änderungen durchschlagen — der Leserkreis wächst also mit, ohne dass der Grant-Geber es entscheidet.
Eine Zustimmungspflicht ist ausgeschlossen (Vorentscheidung). Was bleibt, ist ein passives Signal:
Jeder Grant und jede Space-Mitgliedschaft an eine Gruppe speichert die **Zahl aktiver Mitglieder zum
Zeitpunkt der Erteilung**; die Freigabeansicht zeigt beide Zahlen („Referat 50: 23 bei Erteilung, heute
41"). Keine Mail, kein Vorgang — eine Zeile, die jemand liest, der für die Freigabe geradesteht. Die
allgemeine Autoren-Benachrichtigung aus `spaces-and-assets.md` bleibt außerhalb des Epics (Abschnitt
11); dieses Signal ist ihr kleinster Vorläufer.

## 9c. Querschnitt: die Übertragungsoperation

Die erste Fassung erfand sie als Sonderfall für den Wechsel des Gruppenmechanismus (Abschnitt 4.4). Der
Betrieb hat sie als „die eine Änderung" benannt: **„Rechte einer Gruppe auf eine andere übertragen"** —
Grants, Space-Mitgliedschaften, Eigentum und (bei internen Gruppen) Verantwortliche von A nach B, mit
Vorschau („12 Berechtigungen an 7 Bibliotheken, Mitglied in 2 Spaces, Eigentümerin von 3
Bibliotheken"), Bestätigung, einem Audit-Ereignis und einem sauberen Schnitt in der Rechtehistorie (A
endet, B beginnt, gleicher Zeitpunkt, gleicher Vorgangsbezug).

**Als Option bewertet:**

| Option | Beurteilung |
|---|---|
| **Keine Operation** — Rechte werden je Objekt entfernt und neu vergeben | Bei einer Reorganisation mit 200 Assets Handarbeit an jedem Objekt; endet erfahrungsgemäß in einem `UPDATE` auf der Datenbank, und die Rechtehistorie ist ab dem Tag wertlos. Der `409` beim Anbieterlöschen (Abschnitt 3.4) ist dann eine Sackgasse |
| **Sonderfall je Anlass** (Mechanismuswechsel, Anbieterablösung, Nachfolge je eigene Mechanik) | dreimal dieselbe Historienschreibung mit drei Fehlerquellen |
| **Eine allgemeine Operation** mit vier Anlässen | löst Reorganisation (Referat 50 → 52), Anbieterablösung, Mechanismuswechsel und Nachfolgeübernahme mit derselben Mechanik; jede Anwendung ist ein Vorgang mit Vorschau und Historienschnitt |

**Empfehlung: die allgemeine Operation, als eigenes Umsetzungs-Issue des Epics** (Abhängigkeit für #1812,
#1816 und #1819). Festlegungen:

- **Quelle und Ziel:** Gruppe → Gruppe (Regelfall), Gruppe → Person und Person → Person (Nachfolge,
  Abgabe der Verantwortung). Das Ziel muss wirksam sein (Abschnitt 0); Organisationsgrenze wie überall.
- **Umfang wählbar:** alle Wirkungen der Quelle oder eine Teilmenge (nur Grants, nur Eigentum, nur die
  Wirkungen an einem Anbieter — die Arbeitsliste je Anbieter aus Abschnitt 3.4).
- **Wer:** `SYSTEM_ADMIN` organisationsweit; für den Umfang „Eigentum und Verantwortung, die ich selbst
  trage" auch die Person selbst (Abgabe, Abschnitt 5.3). Ein `MANAGER` ändert Grants an seinem Asset
  weiterhin einzeln; die Massenoperation bleibt ein Verwaltungsakt.
- **Historienschnitt:** je betroffener Zeile ein `valid_to` für A und ein `valid_from` für B mit
  demselben Zeitstempel und einer gemeinsamen Vorgangskennung; die Stichtagsauskunft zeigt an jedem
  Tag genau ein Subjekt.
- **Vorschau ist Pflicht, Bestätigung ist ausdrücklich, Audit trägt Quelle, Ziel, Umfang und Zahl der
  Zeilen**; die betroffenen Objekte tragen den Vorgang in ihrer Freigabeansicht („übertragen von
  Referat 50 am 14.03.2026").
- **Nicht** enthalten: die Rücknahme von Mitgliedschaften nach einem Vorfall (Abschnitt 3.4, bewusst
  Handarbeit im ersten Schritt) und automatische Auslösung durch den Verzeichnisabgleich — eine
  Reorganisation im Verzeichnis erzeugt eine aufgelöste Gruppe und einen Eintrag in der Betriebsliste,
  die Übertragung bleibt eine Entscheidung.

---

## 10. Entscheidungsvorlage für den ADR (#1810)

Je Frage eine Entscheidung in einem Satz; die Unterpunkte sind die Festlegungen, die der ADR mit
aufnehmen sollte. Die Entscheidungen 8 bis 11 sind aus der Stakeholder-Runde hinzugekommen.

1. **Vergleich.** OPAA übernimmt globale Fähigkeiten an Gruppen und Personen (Confluence), die
   schreibgeschützte Verzeichnisgruppe neben internen Gruppen (Confluence „Read Only, with Local
   Groups", Nextcloud), die delegierte Gruppenpflege je Gruppe (Nextcloud, Jira) und den Grundsatz
   „letzter Verantwortlicher kann nicht gehen" (GitLab) — und verwirft Schachtelung (Entra),
   standortgebundene Gruppen (SharePoint), Projektrollen als Indirektion (Jira), freie Rollen (GitLab
   Custom Roles) und die Super-Gruppe mit Inhaltszugriff (Confluence); die Zusage „kein Lesebypass für
   `SYSTEM_ADMIN` in der Suche" steht als prüfbare Invariante an `LibraryAccessService#readableLibraryIds`,
   abgegrenzt von `effectiveRole`, und die Suchdiagnose mit Rechteprofil ist der vorgesehene Weg, eine
   Störung nachzustellen.
2. **Gruppenherkunft.** Eine Gruppe trägt `provider_id` als echten Fremdschlüssel (`NULL` = intern),
   `kind` bleibt der Mechanismus, `external_id` verliert das Präfix, `source_path` trägt den Pfad der
   Quelle; je Anbieter genau ein Gruppenmechanismus; die Herkunft wird als Zusatz zum Namen angezeigt,
   Gruppen externer Anbieter sichtbar abgehoben und mit Zwischenfrage; keine erzwungene
   Namenseindeutigkeit (Warnung statt `409`); Deaktivieren eines Anbieters lässt alles stehen, seine
   Gruppen sind aber keine wirksamen Gruppen mehr; Löschen wird verweigert, solange seine Gruppen
   wirken, mit Arbeitsliste je Anbieter und Übertragungsoperation als Ausgang; die Rücknahme von
   Mitgliedschaften nach einem Vorfall bleibt im ersten Schritt Handarbeit.
3. **Synchronisation.** Je Anbieter Token (Vorgabe) oder zeitgesteuerter Pull mit Bestätigungsweg;
   kein SCIM im ersten Schritt; erster Konnektor ist die Keycloak Admin REST API (direkte Mitglieder,
   Zugangsdaten über `CredentialsEncryptor`, offene Rotationsfrage benannt), LDAP und Graph folgen mit
   Abbildungsregel; der ADR benennt, dass der Token-Modus keine der vier Schutzmechaniken hat und eine
   Umbenennung dort ein Gruppenwechsel ist, dass #1807 nur „Claim fehlt ≠ Claim leer" schließt und der
   Pull-Modus für Häuser mit gepflegtem Verzeichnis der empfohlene ist; ausstehende Pläne haben einen
   Lebenszyklus (Leerergebnis bleibt Abbruch, neuer Lauf ersetzt, frische Berechnung beim Bestätigen,
   Alter sichtbar); Kontosperren aus dem Abgleich unterliegen Schwelle und Bestätigungsweg, sind
   rückholbar und nennen der Person Grund und Ansprechstelle; die Vollmacht „Sicht als" gilt für jede
   Anbietergruppe oberhalb der Mindestgruppengröße.
4. **Interne Gruppen.** Jede interne Gruppe hat mindestens einen Verantwortlichen (natürliche Person),
   der Mitglieder, Namen, Beschreibung, weitere Verantwortliche, die Freigabe zur Verwendung und das
   Schutzkennzeichen pflegt; der letzte kann sich nicht entfernen; Verantwortung wird ausdrücklich
   abgegeben (kein Automatismus beim Referatswechsel); Mitglieder sehen ihre Verantwortlichen und
   werden über Aufnahme und Entfernung benachrichtigt; Anlegen ist die Fähigkeit
   `CREATE_INTERNAL_GROUP`, ausgeliefert an niemanden, mit Handbuchempfehlung zur Vergabe an
   Referatsleitungen; Verantwortlichkeit ist Audit, keine Historienzeile.
5. **Globale Fähigkeiten.** Eigene Tabelle mit Subjekt Nutzer, Gruppe oder „Alle Konten"; erste
   Fähigkeiten `CREATE_SPACE`, `CREATE_LIBRARY`, `CREATE_CONNECTOR_LIBRARY` (ausgeliefert an „Alle
   Konten", Klartextzeile in der Verwaltung, `CREATE_CONNECTOR_LIBRARY` als erster
   Einschränkungskandidat im Handbuch) und `CREATE_INTERNAL_GROUP` (ausgeliefert an niemanden);
   `SYSTEM_ADMIN` hat alle implizit, `AUDITOR` keine; Vergabe und Entzug sind Governance-Ereignisse;
   Fähigkeiten öffnen nie einen Inhalt; „Sicht als" und Vorfallsbereich bleiben Vollmachten (befristet,
   personengebunden, mit Gegenstand) und werden nie Fähigkeiten; der Entzug wirkt ohne Neuanmeldung
   unter der Single-Instance-Annahme (ADR-0021); ADR-0018, Entscheidung 6 ist abgelöst.
6. **Lebenszyklus.** „Nachfolge offen" ist der abgeleitete Zustand „kein handlungsfähiger
   Verantwortlicher" mit eingefrorener Reichweite bei unveränderter Nutzbarkeit; ein benannter
   Feststellungslauf schreibt Erstfeststellung und Ende; die Liste der Systemverwaltung enthält alle
   offenen Nachfolgen ab Tag eins mit Adressat und Alter, objektbezogen, mit Alterungsschwelle und
   Sichtungsvermerk ohne Zwang; Adressat gestuft (wirksame Space-`ADMIN`s, Verantwortliche der
   Eigentümergruppe, sonst Systemverwaltung); die Übernahme ist die Übertragungsoperation; der Zustand
   ist am Objekt für Leseberechtigte gekennzeichnet, nicht in Suchtreffern; keine Frist, keine
   Eskalation, keine Mail; eine Kontosperre wird nie abgelehnt; nur wirksame Gruppen sind neues
   Grant-Ziel und Space-Mitglied; eine wirksame Gruppe zählt als Space-`ADMIN`; die Betriebsliste führt
   zusätzlich „Freigaben ohne Empfänger" und „Gruppen ohne Wirkung".
7. **Diagnose.** Das Rechteprofil bleibt eine Gruppe; nach #1815 wird es um einen optionalen
   Space-Kontext erweitert, für den die Mindestgruppengröße für die Schnittmenge aus aktiven
   Mitgliedern und Space-Zugang zum Zeitpunkt des Laufs gilt (sonst `403` mit Verweis auf den
   Personenkontext); Profil-Läufe mit Space-Kontext werden protokolliert; die Mindestgruppengröße zählt
   aktive Konten, ist ein anderes Maß als die der Nutzungstransparenz und hat eine erzwungene
   Untergrenze; die Suchdiagnose bleibt `SYSTEM_ADMIN` vorbehalten, keine Fähigkeit öffnet sie;
   Leitplanke (b) bleibt.
8. **Rechtehistorie, Stichtagsauskunft, Aufbewahrung** (Abschnitt 9a). Audit verfällt, Historie bleibt;
   historisiert werden mit `valid_from`/`valid_to` Asset-Grants, Gruppenmitgliedschaft,
   Reichweitenfelder, Space-Mitgliedschaft, Eigentum, Fähigkeiten, Systemrolle und Kontozustand —
   nicht Verantwortliche, Vollmachten und Nachfolgevorgänge; die Stichtagsauskunft ist eine Funktion des
   `AUDITOR` mit Objekt-Einstieg ohne Vollmacht (begrenztes Zeitfenster, Seitenobergrenze) und
   Personen-Einstieg nur mit Vier-Augen-Vollmacht nach dem Muster des Vorfallsbereichs; jeder Abruf
   einschließlich abgewiesener ist ein Protokollereignis; die Historie erhält eine
   Aufbewahrungshöchstdauer als Governance-Einstellung mit erzwungenen Grenzen, ohne die keine weitere
   personenbezogene Historienquelle angeschlossen wird; der Namensschnappschuss ist nur mit ihr
   zulässig; Pseudonymisierung (#391/#395) ist Voraussetzung des Personen-Einstiegs und der
   Kontolöschung.
9. **Sichtbarkeit und Herleitung** (Abschnitt 9b). Wer einer Gruppe ein Recht gibt oder es verwaltet,
   sieht ihre Mitgliederliste — ebenso ihre Verantwortlichen und `SYSTEM_ADMIN` (dessen Abruf ein
   Audit-Ereignis ist); Rechtevergebende sehen Anbietergruppen und zur Verwendung freigegebene interne
   Gruppen mit Zahl aktiver Konten („kleine Gruppe" unterhalb der Mindestgruppengröße), sonst nichts;
   geschützte Gruppen (Kennzeichen durch die zuständige Stelle) sind nicht auffindbar, erscheinen in
   fremden Listen ohne Namen, und der Grant-Geber sieht statt der Mitglieder die Verantwortlichen; jede
   Person sieht ihre eigene Herleitung je Bibliothek und Space (Weg, Gruppe mit Herkunft und
   Mechanismus, Zeitpunkt) ohne Vollmacht; Grants an Gruppen speichern die Mitgliederzahl bei Erteilung
   als passives Zuwachssignal.
10. **Übertragungsoperation** (Abschnitt 9c). Eine allgemeine, protokollierte Operation überträgt
    Grants, Space-Mitgliedschaften, Eigentum und Verantwortung von einer Gruppe oder Person auf eine
    wirksame Gruppe oder Person, mit Vorschau, Bestätigung, Audit und Historienschnitt mit gemeinsamer
    Vorgangskennung; sie ist der Ausgang für Reorganisation, Anbieterablösung, Mechanismuswechsel,
    Nachfolge und Abgabe der Verantwortung und wird eigenes Umsetzungs-Issue, von dem #1812, #1816 und
    #1819 abhängen.
11. **Migration und ihre Fehlerfälle** (Abschnitt 3.6). Waisen-Gruppen gelöschter Anbieter und
    `ORG_UNIT`-Gruppen ohne Standardanbieter werden zu internen Gruppen ohne Verantwortliche
    umgewandelt (Audit je Zeile, sofort in der Nachfolgeliste); der `dev`-Modus ohne Anbieterzeile wird
    in #1816 entschieden; die Eindeutigkeitsschlüssel werden in der Reihenfolge neu–umschneiden–alt
    getauscht; jeder bekannte Fall ist ein Fixture des Delta-Tests; das Handbuch liefert eine
    Vorabprüfung, weil die Baseline keine Rollback-Blöcke hat.

Dazu die übergreifenden Festlegungen: alles je Organisation (Abschnitt 9); Begriffe Rolle, Anlegerecht,
Vollmacht, Systemrolle und wirksame Gruppe (Abschnitt 0) in ADR, Oberfläche und Handbuch; die
Typunabhängigkeit der Grants (#1811) als Vorgabe für #1726.

**Folgen für die Umsetzungs-Issues:** Neu geschnitten werden die Übertragungsoperation (Entscheidung
10) und die Historien-/Stichtagsentscheidung (Entscheidung 8, erweitert #1822 um Vollmacht,
Zeitfenster, Abrufereignis und Höchstdauer). Erweitert werden #1812 (Migration, `source_path`, extern),
#1813 (zwei Fähigkeiten, `ALL_ACCOUNTS`, Governance-Ereignis, Historie mit Zeitspanne), #1814
(Freigabe zur Verwendung, Schutzkennzeichen, Abgabe, Benachrichtigung), #1815 (Historie,
Mitgliederzahl bei Erteilung, wirksame Gruppe als `ADMIN`), #1816 (Plan-Lebenszyklus, Mechanismuskonflikt),
#1817 (Keycloak-Festlegungen), #1818 (Schwelle, Rückholbarkeit, Kontozustandshistorie, Meldung an die
Person), #1819 (Feststellungslauf, vollständige Liste, Betriebsliste, Kennzeichnung), #1820 (externe
Anbieter, „kleine Gruppe", Herleitung), #1821 (Arbeitsliste je Anbieter, Klartextzeile der Fähigkeiten,
Betriebsliste).

---

## 11. Bewusst offen gelassen

- **Mehrfachzugehörigkeit** zu Organisationseinheiten (offene Frage in `access-control.md`): kein
  Modellproblem — eine Person kann in beliebig vielen Gruppen sein —, aber die Aggregation der
  Auswertung ist nicht Gegenstand dieses Papiers.
- **Befristete Grants und Rezertifizierung** (#241, nicht geplant): Das Modell hindert sie nicht; eine
  Fähigkeit oder ein Grant könnte später ein Ablaufdatum tragen.
- **Die Obergrenze der Freigabe für Konnektorbibliotheken (#797):** Das Papier trennt nur die
  Fähigkeit `CREATE_CONNECTOR_LIBRARY` ab; was die Obergrenze begrenzt und wie ein Absenken wirkt,
  bleibt bei #797.
- **Die allgemeine Autoren-Benachrichtigung** bei wesentlicher Erweiterung des Leserkreises
  (`spaces-and-assets.md`): außerhalb des Epics; das passive Zuwachssignal in Abschnitt 9b ist ihr
  kleinster Vorläufer, nicht ihr Ersatz.
- **Die allgemeine Protokollpflicht für Profil-Läufe ohne Space-Kontext** (Abschnitt 8.2, Punkt 7).
- **Die Rücknahme von Mitgliedschaften nach einem Vorfall** (Abschnitt 3.4): Handarbeit, im ADR benannt.
- **Anbieter je Organisation** (Abschnitt 9) — gehört zu #1442.
- **Die Werte** für Intervall (6 Stunden), Schwelle (30 %), Mindestgruppengröße, Alterungsschwelle
  (12 Monate), Feststellungsintervall und Aufbewahrungshöchstdauer sind Vorgaben, keine Entscheidungen
  dieses Papiers — mit der Auflage, dass Mindestgruppengröße und Höchstdauer erzwungene Grenzen haben.
- **Das Lastverhalten** des abgeleiteten Zustands „Nachfolge offen" und der Schnittmengen-Prüfung bei
  Hunderten Assets und Tausend Konten; falls die Ableitung teuer wird, ist die Antwort der
  Feststellungslauf mit materialisiertem Ergebnis, nicht ein gespeichertes Flag.
- **Ob Häuser mit bezahlter Entra-P1/P2-Lizenz die SCIM-Ablehnung mittragen** — eine
  Beschaffungsfrage, keine des Modells.

---

## 12. Stakeholder-Bewertung

Die vier Bewertungen der ersten Fassung (Verfahren nach
[`docs/AGENT-ORGANIZATION.md`](../AGENT-ORGANIZATION.md#stakeholder-review)) liegen vollständig als
Berichte vor; hier stehen je Perspektive das Urteil je Empfehlung und die Antwort auf jede Auflage —
übernommen, geändert zu …, oder zurückgewiesen, weil …. Eine stillschweigende Nichtübernahme gibt es
nicht (Personalrat F4). Vier Befunde waren keine Meinungsfragen, sondern Widersprüche im Papier selbst;
sie sind aufgelöst in Abschnitt 3.4 (deaktivierter Anbieter gegen 7.4), Abschnitt 8.2 (Space-Kontext
gegen Mindestgruppengröße), Abschnitt 7.3 (Stufung ohne Zeitelement) und Abschnitt 3.6 (Migration).

### 12.1 Betriebs- und Informationssicherheitsverantwortlicher

**Gesamturteil: tragfähig mit Auflagen.** Urteile je Empfehlung: 1 tragfähig · 2 mit Auflagen · 3 mit
Auflagen · 4 mit Auflagen · 5 mit Auflagen · 6 mit Auflagen · 7 mit Auflagen · Entscheidungsvorlage mit
Auflagen. Die eine Änderung: die Übertragungsoperation.

| Auflage | Antwort |
|---|---|
| 1.1 Zeile „Reorganisation" in der Vergleichsmatrix | **Übernommen** (Abschnitt 2.6). |
| 1.2 Suchdiagnose mit Rechteprofil als vorgesehener Weg der Störungsnachstellung | **Übernommen** (Abschnitt 2.1, Entscheidung 1). |
| 2.1 Waisen-Gruppen gelöschter Anbieter, Installation ohne Standardanbieter (blockierend) | **Übernommen** — Abschnitt 3.6, Entscheidung 11: Umwandlung in interne Gruppen ohne Verantwortliche, Audit je Zeile, Fixture im Delta-Test, Vorabprüfung im Handbuch; zusätzlich der `dev`-Modus als dritter Fall. |
| 2.2 Reihenfolge der Eindeutigkeitsschlüssel, `MAX_NAME_LENGTH` | **Übernommen** (Abschnitt 3.6). |
| 2.3 Widerspruch §3.4/§7.4 | **Aufgelöst** — Gruppen deaktivierter Anbieter sind keine wirksamen Gruppen, kein neues Grant-Ziel, kein neues Space-Mitglied (Abschnitte 0, 3.4, 7.4). |
| 2.4 Namenseindeutigkeit verrät Gruppennamen per `409` | **Übernommen** — Eindeutigkeit fällt; Warnung nur bei sichtbarer gleichnamiger Gruppe (Abschnitt 3.3). |
| 2.5 `409` nur mit Übertragungsoperation (blockierend), Arbeitsliste je Anbieter | **Übernommen** (Abschnitte 3.4, 9c, Entscheidung 10). |
| 2.6 Rücknahme von Mitgliedschaften nach einem Vorfall | **Geändert zu** der angebotenen Alternative: bleibt Handarbeit, im ADR ausdrücklich benannt; die Übertragungsoperation ist die spätere Grundlage (Abschnitt 3.4). |
| 3.1 Token-Modus ohne Schutzmechanik (blockierend): (a) im ADR benennen, (b) Signal bei leerlaufender Gruppe, (c) Handbuch: Pull empfohlen | **Übernommen** alle drei (Abschnitte 4.2, 7.5), mit Verweis auf #1807 und der Liste dessen, was danach fehlt. |
| 3.2 Untergruppen in Keycloak | **Übernommen und geprüft** — `/members` liefert nur direkte Mitglieder (Quelltext `GroupResource`); Festlegung direkt, Mitgliederzahl je Gruppe im Differenzbericht (Abschnitt 4.3). |
| 3.3 Gleichnamige Gruppen eines Anbieters — Pfad im Herkunftszusatz | **Übernommen** (`source_path`, Abschnitt 3.3). |
| 3.4 Zugangsdaten über `CredentialsEncryptor`, Rotationsfrage in den ADR | **Übernommen** (Abschnitt 4.3). |
| 3.5 Lebenszyklus des ausstehenden Plans (vier Festlegungen) | **Übernommen** alle vier (Abschnitt 4.3a). |
| 3.6 Nebenwirkung auf „Sicht als" | **Übernommen, entschieden** — Geltungsbereich auf jede Anbietergruppe oberhalb der Mindestgruppengröße erweitert (Abschnitt 4.3b). |
| 4.1 Sichtbarkeit der Gruppenliste (blockierend) | **Übernommen** — Freigabe zur Verwendung durch die Verantwortlichen als Gegenstück zu `listed` (Abschnitt 9b, Entscheidung 9). |
| 4.2 Verantwortliche mit Historientabelle | **Zurückgewiesen** — Verantwortung trägt kein Leserecht und ist nach dem Nachtrag zu ADR-0016 ein Betriebsrecht der Gegenwart (Personalrat D6); innerhalb der Protokollfrist ist die Prüferfrage beantwortbar, außerhalb ist sie keine Rechtefrage; eine weitere `RESTRICT`-Spalte gegen `users` ohne Rechtebezug wäre der falsche Preis (Abschnitt 5.3). |
| 4.3 Liste interner Gruppen ohne Wirkung | **Übernommen** — dritter Reiter der Betriebsliste (Abschnitt 7.5). |
| 5.1 Historie der Fähigkeiten mit `valid_from`/`valid_to` | **Übernommen** (Abschnitte 6.3, 9a). |
| 5.2 „Alle Konten" nur mit rekonstruierbarer Kontenmenge | **Übernommen** — der Kontozustand wird historisiert (Abschnitt 9a). |
| 5.3 Ausgelieferter Wert als Klartextzeile sichtbar | **Übernommen** (Abschnitt 6.1). |
| Hinweis ADR-0021 | **Übernommen** als Satz (Abschnitt 6.4). |
| 6.1 Vollständige Liste ab Tag eins mit Adressat und Alter (blockierend) | **Übernommen** — die Stufung ist Zuständigkeitsangabe, die Liste vollständig (Abschnitt 7.3). |
| 6.2 „Freigaben ohne Empfänger" (blockierend) | **Übernommen** — zweiter Reiter der Betriebsliste (Abschnitt 7.5). |
| 6.3 Feststellungslauf schreibt den Zeitpunkt | **Übernommen** (Abschnitt 7.1). |
| 6.4 Historie für Space-Mitgliedschaft und Eigentum | **Übernommen** (Abschnitt 9a, Entscheidung 8). |
| 7.1 Mindestgruppengröße an aktiven Konten | **Übernommen** (Abschnitte 8.2, 9b). |
| 7.2 Profilliste ist Gruppenliste | **Übernommen** (Abschnitt 8.2, Punkt 2). |
| Hinweis `target_ref` wörtlich in den ADR | **Übernommen** (Entscheidung 7 verweist auf Abschnitt 8.1). |
| 8.1 Abschnitt „Migration und ihre Fehlerfälle" | **Übernommen** (Abschnitt 3.6, Entscheidung 11). |
| 8.2 Übertragungsoperation | **Übernommen** (Abschnitt 9c, Entscheidung 10). |
| 8.3 Nachweisgrundlage je Änderungsart | **Übernommen** — Tabelle in Abschnitt 9a; Systemrolle und Kontozustand kommen hinzu, Verantwortliche bewusst nicht. |

### 12.2 Personalrat

**Gesamturteil: tragfähig mit Auflagen.** Urteile je Empfehlung: 1 mitbestimmungsfähig mit Auflage ·
2 mit Auflage · 3 mit Auflage · 4 mit Auflage · 5 mit Auflage · 6 mit Auflage · **7 nicht
mitbestimmungsfähig** · Entscheidungsvorlage mit Auflage. Die eine Änderung: Entscheidung 8 vor jeder
weiteren Historienzeile. Die 28 Bedingungen A1–F4:

| Nr. | Antwort |
|---|---|
| Empf. 1: Invariante „kein Lesebypass", Unterschied zu `effectiveRole` | **Übernommen** (Abschnitt 2.1, Entscheidung 1). |
| Empf. 2a: Namensschnappschuss nur mit Höchstdauer | **Übernommen** (Abschnitte 3.3, 9a). |
| Empf. 2b: Hinweis beim Anlegen | **Übernommen** (Abschnitt 3.3). |
| A1 Herleitung nennt Dritten nie den Gruppennamen | **Geändert** — zusätzlich sehen ihn `ADMIN`/Eigentümer eines Space in dessen Mitgliederliste, weil sie die Mitgliedschaft verwalten und nach der Regel in 9b ohnehin die Mitgliederliste der Gruppe sehen; die Space-Mitgliederliste ist heute schon nur ihnen zugänglich (`SpaceAccessPolicy`), die Prämisse „sichtbar für alle Space-Mitglieder" trifft nicht zu (Abschnitt 9b). |
| A2 „kleine Gruppe" statt Zahl | **Übernommen** (Abschnitt 9b). |
| A3 Geschützte Gruppen | **Übernommen, konkretisiert** — Kennzeichen durch die Verantwortlichen, nicht auffindbar, ohne Namen in fremden Listen, Grant-Geber sieht Verantwortliche statt Mitglieder (Abschnitt 9b). |
| A4 Mitglieder sehen Verantwortliche; Anzeige bei Aufnahme und Entfernung | **Übernommen** (Abschnitt 5.3, über ADR-0019 ohne Mail). |
| A5 Hinweis beim Anlegen | **Übernommen** (Abschnitt 3.3). |
| A6 Mitgliederlisten-Abruf durch `SYSTEM_ADMIN` als Audit-Ereignis | **Übernommen** (Abschnitt 9b). |
| B1 Mindestgruppengröße für Profile | **Übernommen** (unverändert). |
| B2 Schnittmenge, zum Zeitpunkt des Laufs, sonst `403` | **Übernommen** — Widerspruch aufgelöst (Abschnitt 8.2, Punkt 4). |
| B3 Protokoll für Profil-Läufe mit Space-Kontext | **Übernommen** (Abschnitt 8.2, Punkt 7). |
| B4 Suchdiagnose bleibt `SYSTEM_ADMIN`; Sperre bei Öffnung | **Übernommen** (Abschnitt 8.2, Punkt 8). |
| B5 Erzwungene Untergrenze | **Übernommen** (Abschnitt 8.2, Punkt 6). |
| B6 Leitplanke (b) | **Übernommen** (unverändert; Abschnitt 8.2, Punkt 8). |
| C1 Kontosperren unter Schwelle und Bestätigungsweg | **Übernommen** (Abschnitt 4.3a). |
| C2 Sperre rückholbar, Historie ohne Bruch | **Übernommen** — Kontozustand wird historisiert (Abschnitte 4.3a, 9a). |
| C3 Grund und Ansprechstelle | **Übernommen** (Abschnitt 4.3a). |
| C4 Mechanismuswechsel ohne stillen Entzug | **Übernommen** (unverändert, jetzt Anwendungsfall der Übertragung). |
| D1 Aufbewahrungshöchstdauer als Vorbedingung weiterer Quellen | **Übernommen** (Abschnitt 9a, Entscheidung 8). |
| D2 Personen-Einstieg nur mit Vier-Augen-Vollmacht | **Übernommen** (Abschnitt 9a). |
| D3 Jeder Abruf ein Ereignis | **Übernommen** (Abschnitt 9a). |
| D4 Zeitfenster und Seitenobergrenze | **Übernommen** (Abschnitt 9a). |
| D5 Namensschnappschuss nur mit D1 | **Übernommen** (Abschnitte 3.3, 9a). |
| D6 Verantwortlichkeit kein Historienartefakt | **Übernommen** (Abschnitte 5.3, 9a). |
| D7 Pseudonymisierung als Voraussetzung jedes weiteren Historienpfads | **Geändert zu** Voraussetzung des Personen-Einstiegs und der Kontolöschung, nicht des Schreibens — Historienzeilen ohne Lesepfad erzeugen keinen Auswertungspfad, und D1 ist die Bedingung, die das Schreiben tatsächlich begrenzt (Abschnitt 9a). |
| E1 Nachfolgeliste objektbezogen | **Übernommen** (Abschnitt 7.3, Punkt 6). |
| E2 „Nachfolge offen" lässt Arbeit unberührt — in ADR und Handbuch | **Übernommen** (Abschnitt 7.4). |
| E3 Nachfolgevorgänge unter der Protokollfrist | **Übernommen** (Abschnitt 7.1). |
| E4 Bestandsinformation, keine Aktivität | **Übernommen** (unverändert). |
| E5 Fähigkeitsvergabe als Governance-Ereignis | **Übernommen** (Abschnitt 6.3). |
| E6 Fähigkeit ≠ Vollmacht | **Übernommen** (unverändert; Begriffe in Abschnitt 0). |
| E7 Kein Lesebypass, Unterschied ausgesprochen | **Übernommen** (Abschnitt 2.1). |
| F1 Auskunft über die Datenerhebung vor Rollout | **Übernommen** (Abschnitt 9a, Verfahren). |
| F2 Testzugang für die Personalvertretung | **Übernommen** (Abschnitt 9a, Verfahren). |
| F3 Wertänderungen protokollpflichtig | **Übernommen** (Abschnitte 8.2, 9a). |
| F4 Einzelne, begründete Ausweisung | **Übernommen** — dieser Abschnitt. |
| Empf. 4d: „vor Kollegen verborgen" ist nicht „verborgen" — aussprechen | **Übernommen** (Abschnitt 5.3). |
| Empf. 5, Punkt 2: erklärt statt versteckt | **Übernommen** (unverändert). |
| Querschnitt 3.3: erzwungene Untergrenze | **Übernommen** (siehe B5). |

Empfehlung 7 ist damit in der zweiten Fassung mit B1 bis B6 vollständig umgesetzt; die Bewertung, ob
sie nun mitbestimmungsfähig ist, liegt beim Personalrat.

### 12.3 Referatsleitung

**Gesamturteil: tragfähig mit Auflagen.** Urteile je Empfehlung: 1 tragfähig · 2 mit Auflage · 3 mit
Auflage · 4 mit Auflage · 5 mit Auflage · 6 mit Auflage (gewichtigster Punkt) · 7 tragfähig.

| Auflage | Antwort |
|---|---|
| 2: Passives Signal bei Gruppenzuwachs für den Grant-Geber | **Übernommen** — Mitgliederzahl bei Erteilung wird am Grant gespeichert und neben der heutigen Zahl gezeigt (Abschnitt 9b); keine Zustimmungspflicht, wie von der Referatsleitung selbst ausgeschlossen. |
| 3: Pull als Vorbedingung für Grants ab Referatsebene | **Zurückgewiesen** — der Mechanismus ist eine Anbietereinstellung, kein Grant-Geber kann ihn wählen, und eine Vorbedingung je Grant würde Freigaben an Token-Gruppen in Häusern ohne Konnektor unmöglich machen. **Die Alternative übernommen:** Handbuch nennt die Genauigkeit der Historie je Mechanismus (Anmeldezeitpunkte), die Herleitung zeigt je Gruppe den Mechanismus (Abschnitt 4.2). |
| 4: Grant-Geber sieht die Mitgliederliste | **Übernommen** — „wer ein Recht gibt, sieht, an wen", mit der Ausnahme geschützter Gruppen, bei denen er die Verantwortlichen als Ansprechstelle sieht (Abschnitt 9b). |
| 5: `CREATE_CONNECTOR_LIBRARY` als erster Einschränkungskandidat im Handbuch | **Übernommen** (Abschnitt 6.1). |
| 6.1: Kennzeichnung „Nachfolge offen" für jeden Nutzer | **Geändert** — in Übersicht und Detailansicht des Objekts für alle Leseberechtigten, samt Adressat; **nicht** in Suchtreffern und Quellenverweisen, weil der Zustand die Zuständigkeit betrifft, nicht die Richtigkeit des Inhalts, und die Kennzeichnung dort jede Antwort zu einer Zustandsauswertung machte (Abschnitt 7.3, Punkt 5). |
| 6.2: Pflichtsichtung nach Alterungsschwelle | **Geändert zu** Alterungsschwelle (Vorgabe 12 Monate) mit Hervorhebung und freiwilligem Sichtungsvermerk, ohne Zwang; zusammen mit der vollständigen Liste (Betrieb 6.1) erreicht das den Zweck — keine Zeile bleibt zehn Jahre unbemerkt — ohne eine Frist, die niemand durchsetzt (Abschnitt 7.3, Punkt 4). |
| Zur Entscheidungsvorlage: Dauerlücke für Verzeichnisgruppen-Eigentum als bewusste Entscheidung ausweisen | **Übernommen, aber die Lücke ist geschlossen** — Verzeichnisgruppen-Eigentum geht mit einer Übertragung an die Nachfolgegruppe (Abschnitt 7.3, Punkt 3; Abschnitt 9c). |

### 12.4 Sachbearbeitung

**Gesamturteil: tragfähig mit Auflagen.** Urteile je Empfehlung: 1 mit Auflage · 2 mit Auflage · 3 mit
Auflage · 4 mit Auflage · 5 alltagstauglich · 6 mit Auflage · 7 nur indirekt beurteilbar. Die eine
Änderung: Gruppen fremder Anbieter sichtbar anders markieren, mit Zwischenfrage.

| Auflage | Antwort |
|---|---|
| 1: Zwei ähnlich benannte Gruppen (Verzeichnis „Referat 50" und interne „Referat 50 Vergabe AG") — Konsequenz aussprechen | **Übernommen** als Handbuchhinweis; die eigene Herleitung (Abschnitt 9b) beantwortet die Kollegin selbst, über welche Gruppe sie sieht oder nicht sieht. |
| 2 / die eine Änderung: externe Anbieter sichtbar anders, Zwischenfrage | **Übernommen** — Kennzeichen „extern" an der Anbieterzeile, Symbol in jeder Auswahl, Zwischenfrage beim Erteilen (Abschnitt 3.3). |
| 3: Zeitverzug des Abgleichs nur in der Verwaltung sichtbar | **Übernommen** — „Meine Gruppen" nennt je Anbieter Mechanismus, Intervall und letzten Abgleich (Abschnitt 4.2). |
| 4a: Anlegen interner Gruppen bis zur Vergabe gesperrt | **Zurückgewiesen als Modelländerung** — Auslieferung darf Verhalten nicht ändern, und die Öffnung ist eine Entscheidung des Hauses; **übernommen als Handbuchempfehlung**, das Anlegerecht bei der Einführung an Referatsleitungen zu vergeben (Abschnitt 5.3). |
| 4b: Referatswechsel bei aktivem Konto | **Übernommen** — ausdrücklicher Abgabeschritt in „Meine Gruppen", Erwartung bei der Ernennung genannt; kein Automatismus, weil er die Gruppenmitgliedschaften einer Person auswerten müsste (Abschnitte 5.3, 7.2). |
| 5 / Begriffe Fähigkeit, Befugnis, Rolle | **Übernommen** — Begriffstabelle in Abschnitt 0 mit Oberflächenwörtern Rolle, Anlegerecht, Vollmacht; „Vollmacht" ist bereits Projektsprache (ADR-0016). |
| 6: Team merkt „Nachfolge offen" erst beim Scheitern | **Übernommen** — Kennzeichnung am Objekt mit Adressat (Abschnitt 7.3, Punkt 5); die abgelehnte Handlung nennt die Zuständigkeit. |
| Vertiefung „warum sehe ich das" | **Übernommen** — eigene Herleitung je Bibliothek und Space, Entscheidung 9 (Abschnitt 9b). |

## Anhang A: Quellen des Vergleichs

Alle Quellen am 19.09.2026 abgerufen.

**Atlassian**
- [Global Permissions Overview, Confluence Data Center 10.2](https://confluence.atlassian.com/doc/global-permissions-overview-138709.html)
- [Permissions best practices, Confluence Data Center 10.2](https://confluence.atlassian.com/doc/permissions-best-practices-992678945.html)
- [Connecting to an LDAP Directory, Confluence Data Center 10.2](https://confluence.atlassian.com/doc/connecting-to-an-ldap-directory-229838241.html)
- [Assign Space Permissions, Confluence Data Center 10.2](https://confluence.atlassian.com/doc/assign-space-permissions-139460.html)
- [Managing project roles, Jira Data Center 11.3](https://confluence.atlassian.com/adminjiraserver/managing-project-roles-938847166.html)

**Microsoft**
- [Learn about groups, group membership, and access (Entra ID)](https://learn.microsoft.com/en-us/entra/fundamentals/concept-learn-about-groups)
- [Use a group to manage access to SaaS apps (Entra ID)](https://learn.microsoft.com/en-us/entra/identity/users/groups-saasapps)
- [Configure group claims for applications (Entra ID)](https://learn.microsoft.com/en-us/entra/identity/hybrid/connect/how-to-connect-fed-group-claims)
- [How Application Provisioning works (Entra ID)](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/how-provisioning-works)
- [Develop a SCIM endpoint for user provisioning (Entra ID)](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups)
- [List group transitive members (Microsoft Graph v1.0)](https://learn.microsoft.com/en-us/graph/api/group-list-transitivemembers)
- [Customize SharePoint site permissions](https://learn.microsoft.com/en-us/sharepoint/customize-sharepoint-site-permissions)
- [Manage ownerless Microsoft 365 groups and teams](https://learn.microsoft.com/en-us/microsoft-365/admin/create-groups/ownerless-groups-teams)
- [Object-Guid attribute (Active Directory Schema)](https://learn.microsoft.com/en-us/windows/win32/adschema/a-objectguid)

**GitLab**
- [Roles and permissions](https://docs.gitlab.com/user/permissions/)
- [Custom roles](https://docs.gitlab.com/user/custom_roles/)
- [SAML Group Sync](https://docs.gitlab.com/user/group/saml_sso/group_sync/)
- [LDAP synchronization](https://docs.gitlab.com/administration/auth/ldap/ldap_synchronization/)

**Nextcloud**
- [User management (Administration Manual)](https://docs.nextcloud.com/server/stable/admin_manual/configuration_user/user_configuration.html)
- [User authentication with LDAP (Administration Manual)](https://docs.nextcloud.com/server/stable/admin_manual/configuration_user/user_auth_ldap.html)

**Standards und Schnittstellen**
- [RFC 7644: System for Cross-domain Identity Management: Protocol](https://www.rfc-editor.org/rfc/rfc7644.html)
- [Keycloak Admin REST API (OpenAPI)](https://www.keycloak.org/docs-api/latest/rest-api/openapi.yaml)
- [Keycloak `GroupResource` (Quelltext, Mitgliederabfrage)](https://github.com/keycloak/keycloak/blob/main/services/src/main/java/org/keycloak/services/resources/admin/GroupResource.java)
- [Keycloak Server Administration Guide](https://www.keycloak.org/docs/latest/server_admin/index.html)

---

## Anhang B: Abweichungen zwischen Spezifikation und gebautem Stand

Beim Lesen aufgefallen, nicht in diesem Papier behoben (Änderungen an Spezifikationen macht #1808).
Für die Konzeptarbeit galt jeweils der Code.

| Nr. | Fundstelle | Spezifikation sagt | Code / anderes Dokument sagt |
|---|---|---|---|
| 1 | `access-control.md`, „System-Admin-Rolle" | System-Admins legen „Gruppengebundene Spaces (`memberSource = GROUP`)" an | `memberSource` ist nicht gebaut; #358 ist als nicht geplant geschlossen, #1815 ersetzt es durch Gruppen als Mitglieder |
| 2 | `access-control.md`, „Offboarding", Punkt 3 | „Zuständig für die Nachfolge ist der Kurator der Organisationseinheit, ersatzweise der System-Admin" | `spaces-and-assets.md` („Eigentümerschaft und Verwaisung", Punkt 4, und „Freigabe an eine Gruppe braucht keine Zustimmung") hat Kuratoren ersatzlos gestrichen und nennt den System-Admin; die beiden Spezifikationen widersprechen sich |
| 3 | `access-control.md`, „Externe Beteiligte" | „Grant `USER` auf genau die benötigte Bibliothek" | `AssetRole` kennt kein `USER` mehr (in #330 gestrichen); der Altwert steht nur noch in `chk_asset_grant_history_role` (#1808/#1811) |
| 4 | `access-control.md`, „Was übernommen wird" | Abgleich „bevorzugt über SCIM", ersatzweise turnusmäßig; übernimmt Kontostatus, Funktionsbezeichnung, Organisationseinheit | `DirectoryClient` ist reiner Pull und liefert nur Gruppen; kein Zeitplan; im Betrieb `NoOpDirectoryClient` (#1816, #1817, #1818 kennen das) |
| 5 | `access-control.md`, „Gruppensynchronisation ist ein Rechteereignis" | Ein auffällig großer Lauf „wird angezeigt und ist bestätigungspflichtig" | `DirectorySyncPlanExecutor` bricht mit `ABORTED_THRESHOLD` ab; kein Bestätigungsweg (#1816 kennt das) |
| 6 | `spaces-and-assets.md`, „Organisationseinheiten sind Gruppen" | Gruppenarten `ORG_UNIT` und `AD_HOC` | `GroupKind.IDENTITY_PROVIDER` fehlt (#1808 kennt das) |
| 7 | `spaces-and-assets.md`, „Gruppengebundene Spaces" und „Gruppengebundene Spaces sind mitbetroffen" | beschreibt `memberSource = GROUP` als Konstrukt | nicht gebaut; siehe Nr. 1 |
| 8 | `Group.parentGroupId` (Javadoc) | „Used to escalate curator responsibility upward … (see #208)" | Kuratoren und Eskalation sind gestrichen; das Feld dient laut Spezifikation nur Anzeige und Aggregation. Nicht in der Liste von #1808 |
| 9 | `Group.externalId` (Javadoc) | „Stable directory identifier (objectGUID or SCIM externalId) … Null for AD_HOC groups" | Für `IDENTITY_PROVIDER`-Gruppen ist es `oidc:<anbieter>:<name>`, also namensbasiert und nicht stabil gegen Umbenennung. Nicht in der Liste von #1808 |
| 10 | `opaa-api` `GroupKind` (Javadoc) | Kurator-Zustimmung nach Gruppengröße | weder gebaut noch spezifiziert (#1808 kennt das) |
| 11 | `hybrid-retrieval.md`, „Das Diagnosewerkzeug" | Rechteprofil als „eine Rolle mit der zugehörigen Bibliotheksmenge" | gebaut ist es als **Gruppe** (`SearchDiagnosisService.PermissionProfile`); das Wort „Rolle" widerspricht der Vorentscheidung „keine freien Rollen" und sollte „Gruppe" heißen |
| 12 | `OidcProviderService#deleteProvider` | — | Löscht den Anbieter ohne Blick auf Gruppen, deren `external_id` seine ID trägt; die Spezifikation trifft dazu keine Aussage (Gegenstand von #1812) |
