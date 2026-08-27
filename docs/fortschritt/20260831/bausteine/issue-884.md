# Issue #884 — refactor(backend): Request-scoped CurrentUser — Aufrufer-Identität zentralisieren
- Geschlossen: 2026-08-24 (completed)
- Labels: enhancement, backend, size:L, auth
- PRs: #887 (2026-08-24)

**Laut Issue:** Teil von Epic #826, Phase 3 (Befund B2, Kernteil), baut auf #860 und #875 auf. `currentUser(Jwt)` wortgleich in 14 Controllern; Nutzer bis zu dreimal pro Request geladen; Service-Methoden mit `(UUID currentUserId, boolean systemAdmin)`-Parameterpaaren. Request-scoped `CurrentUser`, befüllt vom `UserProvisioningFilter` ohne zusätzlichen DB-Zugriff.

**Geliefert:** `CurrentUser`-Record (id, organizationId, systemRole, displayName) über `HandlerMethodArgumentResolver` (`CurrentUserArgumentResolver`) statt Request-scoped Bean bereitgestellt — bewusste Mechanismus-Entscheidung, da der Filter das Objekt bereits vollständig gebaut hat. 14 Controller und ~10 Service-Signaturen umgestellt. Wichtiger Sicherheitsbefund aus dem Review selbst behoben: `CurrentUser` war zunächst fail-open (ein fehlender Resolver hätte Springs generischem Databinding erlaubt, das Objekt aus Query-Parametern zu befüllen — Identitätsübernahme via `?systemRole=SYSTEM_ADMIN` möglich gewesen). Jetzt zweifach fail-closed: exklusive `@Caller`-Annotation plus strukturell unbindbare Klasse (kein öffentlicher Konstruktor, Reflection-Guard gegen Springs `BeanUtils.getResolvableConstructor`). Neuer Test `CurrentUserFailClosedTest` mit rot/grün-Nachweis dieser Lücke. `LibraryAccessService`-Methoden, die die Berechtigung eines *fremden* Zielnutzers prüfen, bewusst außerhalb des Umfangs belassen (Folgeticket B2b).

**Verifikation:** `backend/src/main/java/io/opaa/auth/{CurrentUser,CurrentUserArgumentResolver,Caller,CurrentUserWebConfig}.java` im Worktree vorhanden; `backend/src/test/java/io/opaa/auth/CurrentUserFailClosedTest.java` existiert.

**Themen:** auth, backend, refactoring, sicherheit, architektur
