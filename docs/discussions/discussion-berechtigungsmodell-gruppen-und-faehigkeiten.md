# Diskussion: Berechtigungsmodell — Gruppenherkunft, Synchronisation, globale Fähigkeiten und Lebenszyklus

Konzeptpapier zu Issue [#1809](https://github.com/criew/opaa/issues/1809) (Epic
[#1295](https://github.com/criew/opaa/issues/1295) „Gruppen, Rollen und Berechtigungen", Phase 1),
Entwurf vom 19.09.2026. Es nimmt die Ausarbeitung aus
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
  keiner Rolle. Diese Trennung ist eine Zusage an den Personalrat und bleibt.
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
| Verlust des letzten Verantwortlichen | Admin stellt wieder her (protokolliert) | Richtlinie fragt aktivste Mitglieder | letzter Owner kann nicht gehen | — | „Nachfolge offen", Auffang Systemverwaltung |

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
| **Eindeutigkeit des Namens erzwingen** | Für Anbietergruppen unmöglich (die Quelle bestimmt den Namen). Für **interne** Gruppen sinnvoll: Name eindeutig je Organisation (ohne Berücksichtigung der Groß-/Kleinschreibung), damit nicht zwei interne „Projekt Gecko" entstehen. **Empfohlen als Ergänzung.** |

Auditeinträge und Rechtehistorie tragen zusätzlich zur Gruppen-ID die Herkunft als Text
(„Referat 50 (Verzeichnis Haus A)"), damit ein Prüfer sie liest, ohne die Anbietertabelle zu joinen —
die Anbieterzeile kann später gelöscht sein (ADR-0016: Historie überlebt).

### 3.4 Deaktivieren und Löschen eines Anbieters

**Deaktivieren** ist der Notweg (Token ab sofort abgewiesen, ADR-0025) und muss ohne Bedingung
möglich bleiben. Gruppen, Mitgliedschaften und Grants bleiben unverändert stehen; Token-Gruppen ändern
sich nicht (niemand meldet sich mehr an), der Verzeichnisabgleich dieses Anbieters pausiert. Die
Gruppen sind in Auswahlfeldern weiter wählbar, aber mit Hinweis „Anbieter deaktiviert". Wird der Anbieter
wieder aktiviert, läuft alles weiter, als wäre nichts gewesen. **Keine Diskussion nötig.**

**Löschen** ist Aufräumen, kein Notweg. Zwei Optionen aus #1812:

| Option | Wirkung | Beurteilung |
|---|---|---|
| **Auflösen** — Gruppen des Anbieters gehen in `dissolved` (Mitgliedschaft eingefroren, kein neues Grant-Ziel) | Nichts blockiert; bestehende Grants wirken für Konten, die sich ohnehin nicht mehr anmelden können; Assets im Eigentum solcher Gruppen gehen in „Nachfolge offen" | Erzeugt dauerhaft „tote" Gruppen, die in jeder Liste als aufgelöst mitlaufen; verlangt `ON DELETE SET NULL` und eine Zusatzregel, wie eine Gruppe ohne Anbieter aussieht — genau die Klasse Zustand, die #1812 abschaffen will |
| **Verweigern** — solange Gruppen des Anbieters Grants tragen, Space-Mitglied sind oder Assets besitzen, antwortet das Löschen mit `409` und Zählung; Gruppen **ohne** Wirkung werden mit dem Anbieter gelöscht (Historie geschlossen wie in `GroupService#deleteGroup`) | Zwingt zum Aufräumen vor dem Löschen; dafür gibt es die Sicht „wo wirkt diese Gruppe" (#1821) | Dasselbe Muster wie heute für `deleteGroup` (409 bei Grants) und für den Standardanbieter (409, solange ein anderer existiert); kein neuer Zustand; `ON DELETE RESTRICT` trägt die Garantie „keine Gruppe ohne existierenden Anbieter" strukturell |

**Empfehlung: Verweigern.** Der Notfall ist mit Deaktivieren abgedeckt; das Löschen darf Bedingungen
haben. Die Fehlermeldung nennt Anzahl und Art der Wirkungen („3 Gruppen tragen 12 Berechtigungen an 7
Bibliotheken und sind Mitglied in 2 Spaces") und verweist auf die Gruppenverwaltung. Der Lebenszyklus
(Abschnitt 7) braucht dafür keinen eigenen Fall „Anbieter gelöscht" — er kennt nur „Gruppe aufgelöst"
(aus dem Verzeichnisabgleich) und „Gruppe leer".

**Folge für #1812:** Die Alternative „aufgelöst" entfällt; das Abnahmekriterium „Das Löschen eines
Anbieters hinterlässt keine wirksame Gruppe, deren Mitgliedschaft sich nie mehr ändern kann" wird durch
die Verweigerung erfüllt.

### 3.5 Verhältnis zu lokalen Konten (#1368, ADR-0033)

Lokale Konten haben keinen Gruppenmechanismus („Lokale Konten erhalten Gruppen nur manuell", ADR-0033);
sie werden Mitglied **interner** Gruppen, aufgenommen von deren Verantwortlichen (Abschnitt 5). Eine
Anbietergruppe kann kein lokales Konto enthalten — die Prüfregel ist, dass Mitglieder einer Anbietergruppe
den Issuer dieses Anbieters tragen. Das gilt heute für den Token-Pfad implizit und wird im
Verzeichnisabgleich je Anbieter (#1816) ausdrücklich.

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

**Folge für #1817:** Der Issue lässt den Typ offen („entscheidet der ADR"); die Empfehlung setzt Keycloak.
Die Abnahmekriterien passen unverändert; „Zieladressen über die bestehende Allowlist-Prüfung" gilt für
die Admin-API-Adresse genauso wie für den Issuer.

### 4.4 Verhalten beim Wechsel des Mechanismus

Wechselt ein Anbieter von Token auf Pull, sind seine Token-Gruppen (`IDENTITY_PROVIDER`) und die neuen
Verzeichnisgruppen (`ORG_UNIT`) verschiedene Objekte. Empfehlung: Der erste Lauf zeigt im Differenzbericht
neben den neuen Gruppen die Token-Gruppen als **„werden nicht mehr gepflegt"**; sie bleiben mit
eingefrorener Mitgliedschaft stehen (wie `dissolved`), sind kein neues Grant-Ziel mehr, und die
Verwaltung bietet je Token-Gruppe „Grants auf Verzeichnisgruppe übertragen" an. Nichts wird
stillschweigend entzogen; der Wechsel ist ein Verwaltungsakt mit Bericht.

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
  ernennen und entlassen, die Gruppe löschen — Letzteres unter denselben Bedingungen wie heute
  `deleteGroup` (409, solange die Gruppe Grants trägt, Assets besitzt oder Space-Mitglied ist).
- **Der letzte Verantwortliche kann sich nicht entfernen** (GitLab: „unless they are the only Owner").
  Scheidet er aus (Kontosperre), greift der Lebenszyklus (Abschnitt 7): Die Gruppe ist „Nachfolge
  offen", die Systemverwaltung ernennt einen neuen Verantwortlichen.
- **Das Anlegen ist eine globale Fähigkeit** (`CREATE_INTERNAL_GROUP`, Abschnitt 6). Ausgeliefert wird
  sie **an niemanden** — heute kann nur `SYSTEM_ADMIN` anlegen, und Auslieferung darf Verhalten nicht
  ändern. Der Betrieb vergibt sie an eine Gruppe (etwa „Referatsleitungen" aus dem Verzeichnis) oder an
  „Alle Konten", wenn das Haus das will.
- **Anbietergruppen bleiben schreibgeschützt** (`rejectOrgUnit` bleibt); sie haben keine
  Verantwortlichen.
- **Sichtbarkeit der Mitgliederliste:** vollständige Liste für Verantwortliche und `SYSTEM_ADMIN`;
  ein Mitglied sieht die Gruppen, denen es angehört (`GET /api/v1/me/groups`, wie heute), und deren
  Größe, nicht die übrigen Mitglieder. Das entspricht der Regel für Space-Mitgliederlisten
  (`spaces-and-assets.md`: `MEMBER` sieht nur `roleCounts`) und hält die Mitgliedschaft in einer
  Gruppe „Disziplinarverfahren 2026" vor Kollegen verborgen.
- **Audit und Rechtehistorie** nennen den Verantwortlichen als Akteur (`GroupMembershipHistoryCause.ADDED`/
  `REMOVED` mit `actor_user_id`, wie heute für den Systemverwalter); Ernennung und Entlassung eines
  Verantwortlichen sind eigene Ereignisse.
- **API ohne `/admin`**, Rechteprüfung je Gruppe; ein Nichtverantwortlicher erhält dieselbe Antwort wie
  für eine unbekannte Gruppe (404, wie `loadGroup` heute über die Organisationsgrenze hinweg).

**Folge für #1814:** deckungsgleich. Präzisiert wird nur: Verantwortliche sind Personen, die Sichtbarkeit
der Mitgliederliste, und die Auslieferung der Fähigkeit „an niemanden".

---

## 6. Frage 5: Globale Berechtigungen (Fähigkeiten)

### 6.1 Liste für den ersten Schritt

| Fähigkeit | Heute | Auslieferung | Begründung |
|---|---|---|---|
| `CREATE_SPACE` | jeder (`authenticated`) | „Alle Konten" | Vorentscheidung; der persönliche Space entsteht bei der ersten Anmeldung **unabhängig** davon (Bereitstellung, kein Anlegen) |
| `CREATE_LIBRARY` (Upload-Bibliothek) | jeder (ADR-0018, Entscheidung 6) | „Alle Konten" | Vorentscheidung; löst ADR-0018/6 ab |
| `CREATE_CONNECTOR_LIBRARY` (lauf-basierte Quellen: Dateisystem, Webverzeichnis, RSS, Confluence, S3) | jeder (#484) | „Alle Konten" | Eigene Fähigkeit, weil Konnektorbibliotheken Serverpfade und Zugangsdaten erreichen und die Freigabe-Obergrenze (#797) tragen — ein Haus will sie typisch enger vergeben als Upload-Bibliotheken. Auslieferung wie heute, damit sich nichts ändert |
| `CREATE_INTERNAL_GROUP` | nur `SYSTEM_ADMIN` | **niemand** | Abschnitt 5; Auslieferung erhält das heutige Verhalten |

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
Historientabelle nach ADR-0016 (Subjektspalten `RESTRICT`, Gruppen-ID ohne FK); Audit-Ereignisse
`CAPABILITY_GRANTED`/`_REVOKED`.

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
  `GroupMembershipResolver`.
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

### 7.2 Die Fälle

| Auslöser | Wirkung auf Rechte | Wirkung auf Objekte |
|---|---|---|
| **Austritt aus der Organisation** (Konto gesperrt — durch Abgleich #1818, Anbieter oder Verwaltung) | Zugang sofort weg; Sitzungen und Zugangstokens unwirksam. **Mitgliedschaften bleiben stehen** (reversibel, Historie ohne Bruch); erst die Kontolöschung entfernt sie (`security-and-compliance.md`, Schritt 3) | Assets im Eigentum der Person und Spaces, in denen sie einziger wirksamer `ADMIN` war → „Nachfolge offen" |
| **Austritt aus einer Gruppe** (Token, Abgleich, Verantwortlicher) | Rechte über die Gruppe enden sofort (Cache-Invalidierung nach Commit; #1815 verlangt dasselbe für Spaces) | keine, es sei denn, die Gruppe wird dadurch leer (nächste Zeile) |
| **Gruppe leer** (letztes Mitglied ausgetreten) | Grants an die Gruppe bleiben, wirken für niemanden | Assets im Eigentum der Gruppe, Spaces mit ihr als einzigem `ADMIN` → „Nachfolge offen"; **endet von selbst**, sobald wieder ein Mitglied da ist (abgeleiteter Zustand) |
| **Gruppe aufgelöst** (Verzeichnis meldet sie nicht mehr; heute `dissolved`) | Mitgliedschaft eingefroren, kein neues Grant-Ziel (`requireGrantableGroup`) | Assets/Spaces → „Nachfolge offen"; endet nur durch Übernahme (oder Reaktivierung durch das Verzeichnis, `Group#reactivate`) |
| **Letzter Verantwortlicher einer internen Gruppe ausgeschieden** | Mitglieder unverändert | Gruppe → „Nachfolge offen" (kein neuer Verantwortlicher kann sich ernennen); Systemverwaltung ernennt |

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

**Empfehlung: gestufte Zuständigkeit.**
1. **Space:** die übrigen `ADMIN`-Mitglieder — ist noch eines wirksam, ist der Space gar nicht
   „Nachfolge offen"; nur der Eigentümerwechsel ist nötig, und den darf jeder wirksame `ADMIN` an sich
   oder einen anderen `ADMIN` übertragen (heute nur Eigentümer oder `SYSTEM_ADMIN`; das ist die eine
   Verhaltensänderung).
2. **Asset einer internen Gruppe:** die Verantwortlichen der Gruppe — sie nehmen ein Mitglied auf, und
   der Zustand endet.
3. **Alles andere** (Asset einer Person, Asset einer Verzeichnisgruppe, Gruppe ohne Verantwortliche):
   **Systemverwaltung als Auffangzuständigkeit** über die Liste offener Nachfolgen. Die Übernahme
   durch eine Person oder Gruppe beendet den Zustand; Historie und Audit halten Vorgang und Übernehmenden
   fest.

**Frist und Eskalation:** #1819 nennt sie als Kandidat. Empfehlung für den ersten Schritt: **keine
automatische Frist, keine Eskalation, keine Mail.** Die Liste zeigt das Alter jedes Vorgangs; die
Wiedervorlage ist Sache der Organisation. Eine Frist ohne Adressaten, an den eskaliert werden könnte,
ist eine Zahl ohne Wirkung — und eine Eskalation „nach oben" setzt genau die Hierarchie voraus, die
mit den Kuratoren gestrichen wurde. Kommt später ein Vorgesetztenbezug ins Kontenmodell, lässt sich die
Stufe 2 erweitern, ohne den Mechanismus zu ändern.

**Hinweis statt Auswertung:** Die Liste darf als **Vorschlag** die Verzeichnisgruppen des Ausgeschiedenen
nennen („war Mitglied von Referat 50") — das ist Bestandsinformation, keine Aktivitätsauswertung, und
hilft der Systemverwaltung, die richtige Gruppe als neuen Eigentümer zu wählen. Gruppen-Eigentum ist
weiterhin der Regelfall für zentral gepflegte Bestände; die Liste ist das Auffangnetz.

### 7.4 Schutzregeln, die den Zustand vermeiden

- **Ein Space verliert nie sein letztes wirksames `ADMIN`-Mitglied durch eine Verwaltungshandlung**:
  Entfernen, Herabstufen oder Austritt des letzten `ADMIN` wird mit `409` abgelehnt (heute nur für den
  Eigentümer). **Eine Gruppe zählt als `ADMIN`, solange sie wirksam ist** (nicht aufgelöst, mindestens
  ein aktives Mitglied) — das beantwortet die offene Frage aus #1815.
- **Eine Kontosperre wird nie abgelehnt.** Sie ist die eine Handlung, die den Zustand erzeugen darf,
  weil der Zugang wichtiger ist als die Zuständigkeit (Spezifikation: „Die Deaktivierung wird nie durch
  offene Eigentumsfragen aufgehalten").
- **Der letzte Verantwortliche einer internen Gruppe kann sich nicht entfernen** (Abschnitt 5).
- **Aufgelöste und leere Gruppen sind kein neues Grant-Ziel und kein neues Space-Mitglied** (heute nur
  „aufgelöst"; „leer" kommt hinzu, mit verständlicher Meldung).

**Folge für #1819:** „Nachfolge offen" als abgeleiteter Zustand statt gespeichertem Flag; Adressat gestuft;
Frist und Eskalation bewusst nicht im ersten Schritt. Typunabhängigkeit bleibt (#1726 braucht denselben
Mechanismus für Prompt-Bibliotheken).

---

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

### 8.2 Was für Spaces fehlt

1. **Gruppen sind heute keine Space-Mitglieder.** Ein Profil hat deshalb keinen Space-Kontext; die
   Diagnose läuft immer über den Bibliotheksbestand der Organisation, nicht über den Suchbereich eines
   Chats in einem bestimmten Space (`spaces-and-assets.md`, „Suchbereich je Chatart"). Mit #1815 lässt
   sich das Profil um „Gruppe G im Space S" erweitern: Suchbereich = im Space assoziierte Bibliotheken
   ∩ für G lesbare Bibliotheken. Das ist eine Erweiterung von `SearchDiagnosisRequest` um eine optionale
   Space-ID im Profilkontext — nach #1815, nicht davor.
2. **Herkunft im Profilnamen.** Mit zwei Anbietern gibt es zwei „Referat 50"; die Profilliste braucht
   dieselbe Herkunftsanzeige wie die Subjekt-Auswahl (Abschnitt 3.3).
3. **Kleine Gruppen sind Personen.** Eine Token-Gruppe mit einem Mitglied oder eine interne Gruppe
   „Projektleitung X" mit zwei Mitgliedern ist als Rechteprofil de facto ein Personenkontext — ohne
   Befugnis, ohne Begründung, ohne Protokoll. `security-and-compliance.md` kennt für Auswertungen eine
   **Mindestgruppengröße**. Empfehlung als Randbedingung für den ADR: Gruppen unterhalb der
   Mindestgruppengröße sind **kein wählbares Rechteprofil**; wer ihre Sicht braucht, nimmt den
   Personenkontext mit Befugnis. Das schließt zugleich die in `hybrid-retrieval.md` offen gelassene
   Umgehung („wer statt der Person deren Gruppe als Rechteprofil wählt") für den Fall, in dem sie
   wirklich personenbezogen wäre.
4. **Protokollpflicht für Profil-Läufe** bleibt die in `hybrid-retrieval.md` ausdrücklich nicht
   mitgetroffene Entscheidung; dieses Papier ändert daran nichts, nennt aber Punkt 3 als das Mindestmaß,
   ohne das sie irgendwann unausweichlich wird.

**Folge für die Umsetzungs-Issues:** kein eigenes Issue nötig; Punkt 2 gehört zu #1820/#1821, Punkte 1
und 3 sind Nacharbeiten an `SearchDiagnosisService` nach #1815 und sollten im ADR als Randbedingung
stehen, damit sie nicht verloren gehen.

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

## 10. Entscheidungsvorlage für den ADR (#1810)

Je Frage eine Entscheidung in einem Satz; die Unterpunkte sind die Festlegungen, die der ADR mit
aufnehmen sollte.

1. **Vergleich.** OPAA übernimmt globale Fähigkeiten an Gruppen und Personen (Confluence), die
   schreibgeschützte Verzeichnisgruppe neben internen Gruppen (Confluence „Read Only, with Local
   Groups", Nextcloud), die delegierte Gruppenpflege je Gruppe (Nextcloud, Jira) und den Grundsatz
   „letzter Verantwortlicher kann nicht gehen" (GitLab) — und verwirft Schachtelung (Entra),
   standortgebundene Gruppen (SharePoint), Projektrollen als Indirektion (Jira), freie Rollen (GitLab
   Custom Roles) und die Super-Gruppe mit Inhaltszugriff (Confluence).
2. **Gruppenherkunft.** Eine Gruppe trägt `provider_id` als echten Fremdschlüssel (`NULL` = intern),
   `kind` bleibt der Mechanismus, `external_id` verliert das Präfix; je Anbieter genau ein
   Gruppenmechanismus (Token oder Abgleich); die Herkunft wird als Zusatz zum Namen angezeigt, nie in
   den Namen geschrieben; interne Gruppennamen sind je Organisation eindeutig; Deaktivieren eines
   Anbieters lässt alles stehen, Löschen wird verweigert, solange seine Gruppen wirken.
3. **Synchronisation.** Je Anbieter Token (Vorgabe) oder zeitgesteuerter Pull mit Bestätigungsweg;
   kein SCIM im ersten Schritt; erster Konnektor ist die Keycloak Admin REST API, weil dort die
   Mitgliedskennung mit dem Token-Subject zusammenfällt; LDAP und Graph folgen mit ausdrücklicher
   Abbildungsregel.
4. **Interne Gruppen.** Jede interne Gruppe hat mindestens einen Verantwortlichen (natürliche Person),
   der Mitglieder, Namen, Beschreibung und weitere Verantwortliche pflegt; der letzte kann sich nicht
   entfernen; Anlegen ist die Fähigkeit `CREATE_INTERNAL_GROUP`, ausgeliefert an niemanden;
   Mitgliederlisten sehen nur Verantwortliche und Systemverwaltung.
5. **Globale Fähigkeiten.** Eigene Tabelle mit Subjekt Nutzer, Gruppe oder „Alle Konten"; erste
   Fähigkeiten `CREATE_SPACE`, `CREATE_LIBRARY`, `CREATE_CONNECTOR_LIBRARY` (ausgeliefert an „Alle
   Konten") und `CREATE_INTERNAL_GROUP` (ausgeliefert an niemanden); `SYSTEM_ADMIN` hat alle implizit,
   `AUDITOR` keine; „Sicht als" und Vorfallsbereich bleiben Befugnisse (befristet, personengebunden,
   mit Gegenstand) und werden nie Fähigkeiten; ADR-0018, Entscheidung 6 ist abgelöst.
6. **Lebenszyklus.** „Nachfolge offen" ist der abgeleitete Zustand „kein handlungsfähiger
   Verantwortlicher" (Konto gesperrt, Gruppe leer oder aufgelöst, kein Verantwortlicher) mit
   eingefrorener Reichweite; Adressat gestuft: wirksame Space-`ADMIN`s, Verantwortliche der
   Eigentümergruppe, sonst Systemverwaltung über die Liste offener Nachfolgen; keine Frist, keine
   Eskalation, keine Mail im ersten Schritt; eine Kontosperre wird nie abgelehnt, der letzte wirksame
   `ADMIN` eines Space (Person oder wirksame Gruppe) wird nie durch eine Verwaltungshandlung entfernt.
7. **Diagnose.** Das Rechteprofil bleibt eine Gruppe; nach #1815 wird es um einen optionalen
   Space-Kontext erweitert, zeigt die Herkunft, und Gruppen unter der Mindestgruppengröße sind kein
   wählbares Profil.

Dazu die übergreifenden Festlegungen: alles je Organisation (Abschnitt 9); Rechtehistorie und Audit
für jede hier genannte Änderung (Grant, Mitgliedschaft, Verantwortlicher, Fähigkeit, Übernahme), mit
Herkunftstext statt bloßer ID; die Typunabhängigkeit der Grants (#1811) als Vorgabe für #1726.

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
- **Benachrichtigung der Autoren** bei wesentlicher Erweiterung des Leserkreises durch Gruppenzuwachs
  (`spaces-and-assets.md`): außerhalb von #1815 bewusst ausgenommen; das Modell ändert daran nichts.
- **Protokollpflicht für Profil-Läufe** der Diagnose (Abschnitt 8.2, Punkt 4).
- **Anbieter je Organisation** (Abschnitt 9) — gehört zu #1442.
- **Die Werte** für Intervall (6 Stunden), Schwelle (30 %) und Mindestgruppengröße sind Vorgaben, keine
  Entscheidungen dieses Papiers.

---

## 12. Stakeholder-Bewertung

Die Bewertung läuft nach dem Verfahren in
[`docs/AGENT-ORGANIZATION.md`](../AGENT-ORGANIZATION.md#stakeholder-review) gegen diesen Entwurf.
Die Berichte werden hier unverändert eingefügt; welche Befunde übernommen und welche begründet
zurückgewiesen wurden, steht anschließend je Perspektive.

### 12.1 Betriebs- und Informationssicherheitsverantwortlicher

_Bewertung ausstehend._

**Übernommen / zurückgewiesen:** _ausstehend._

### 12.2 Personalrat

_Bewertung ausstehend._

**Übernommen / zurückgewiesen:** _ausstehend._

### 12.3 Referatsleitung

_Bewertung ausstehend._

**Übernommen / zurückgewiesen:** _ausstehend._

### 12.4 Sachbearbeitung

_Bewertung ausstehend._

**Übernommen / zurückgewiesen:** _ausstehend._

---

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
