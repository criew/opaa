# Issue #888 — refactor(space): Zentrale AccessPolicy und effectiveRole — Owner-Semantik vereinheitlichen
- Geschlossen: 2026-08-25 (completed)
- Labels: enhancement, backend, size:M
- PRs: #891 (2026-08-25)

**Laut Issue:** Teil von Epic #826, Phase 3. Autorisierungsentscheidungen für Spaces waren als eigene Helfer je Service verstreut (`requireManager`/`requireCurator`/`requireMemberListViewer`/`hasCuratorRole`) mit subtil unterschiedlicher Owner-Behandlung — ein Space-Owner zählte in `SpaceAssetAssociationService` als Kurator, in `SpaceService.requireManager` aber nicht als Manager. Gefordert war eine zentrale `effectiveRole`-Funktion (Owner ⇒ ADMIN) plus eine `AccessPolicy`-Komponente und ein `OrganizationScopedLoader` für das kopierte Org-Boundary-404-Muster.

**Geliefert:** Neue `SpaceAccessPolicy` mit einheitlicher `effectiveRole(Space, CurrentUser|UUID)` (Owner ⇒ mindestens ADMIN) sowie `OrganizationScopedLoader` für `loadSpace`/`loadGroup`/`requireUserInOrganization`, angewendet in `SpaceService`, `SpaceAssetAssociationService`, `GroupService`. Die dokumentierte Verhaltensänderung (Owner mit unterhalb-ADMIN-Mitgliedsrolle darf jetzt Manager-Aktionen ausführen) wurde bewusst umgesetzt und getestet. Zusätzlich, über den Issue-Text hinausgehend: Review-Befund zur API-Grenze behoben — `SpaceResponseMapper` speist `userRole` jetzt ebenfalls aus `effectiveRole`, sonst hätte das Frontend die freigeschaltete Manager-Aktion nicht angezeigt. `LibraryAccessService` wurde wie gefordert nicht angetastet.

**Verifikation:** `backend/src/main/java/io/opaa/space/SpaceAccessPolicy.java` existiert im Worktree.

**Themen:** spaces, auth, refactoring, epic-826
