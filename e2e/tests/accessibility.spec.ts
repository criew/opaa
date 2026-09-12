import { expect, test } from "../fixtures/auth";
import { expectNoSeriousA11yViolations } from "../fixtures/a11y";
import { gotoLibraries, startFreshChat } from "../fixtures/chat";

/**
 * Automated accessibility checks with axe-core (#586): every page listed in the issue is opened
 * in its default state and analysed; serious/critical WCAG 2.1 AA violations fail the suite (see
 * fixtures/a11y.ts for the threshold and docs/design/accessibility.md §3.1 for the policy).
 *
 * These scenarios do not mutate shared state (no uploads, no indexing), so they are safe to run
 * in any position of the serial suite.
 */
test.describe("Barrierefreiheit (axe-core, #586)", () => {
  test("Anmeldeseite", async ({ page }) => {
    // The stack runs in dev auth mode, where LoginPage redirects immediately because every
    // visitor is already authenticated. Answering /api/v1/auth/config with an OIDC configuration
    // renders the real login page instead: oidc-client-ts only consults sessionStorage on start,
    // so the fake authority is never contacted.
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({
        json: {
          mode: "oidc",
          providers: [
            {
              id: "00000000-0000-0000-0000-00000000e2e1",
              displayName: "Verzeichnisdienst",
              issuerUri: "http://localhost/oidc-stub",
              clientId: "e2e",
              isDefault: true,
              sortOrder: 0,
            },
          ],
        },
      }),
    );
    await page.goto("/login");
    await expect(
      page.getByRole("button", { name: "Anmelden bei Verzeichnisdienst" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Anmeldeseite");
  });

  test("Anmeldeseite mit zwei Anbietern", async ({ page }) => {
    // The second provider brings the "Zuletzt verwendet" hint and the secondary button variant
    // onto the page (ADR-0025, #1332) - both must pass the contrast check like the single-provider
    // page does.
    // A string script: the E2E suite compiles without DOM typings, so `window` is unknown here.
    await page.addInitScript(
      "window.localStorage.setItem('opaa.oidc.lastProvider', '00000000-0000-0000-0000-00000000e2e2')",
    );
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({
        json: {
          mode: "oidc",
          providers: [
            {
              id: "00000000-0000-0000-0000-00000000e2e1",
              displayName: "Verzeichnisdienst",
              issuerUri: "http://localhost/oidc-stub",
              clientId: "e2e",
              isDefault: true,
              sortOrder: 0,
            },
            {
              id: "00000000-0000-0000-0000-00000000e2e2",
              displayName: "Partnerportal",
              issuerUri: "http://localhost/oidc-stub-partner",
              clientId: "e2e-partner",
              isDefault: false,
              sortOrder: 1,
            },
          ],
        },
      }),
    );
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Anmelden bei Partnerportal" })).toBeVisible();
    await expect(page.getByText("Zuletzt verwendet")).toBeVisible();
    await expectNoSeriousA11yViolations(page, "Anmeldeseite mit zwei Anbietern");
  });

  test("Chat in beiden Farbschemata", async ({ authenticatedPage: page }) => {
    await startFreshChat(page);
    const input = page.getByPlaceholder(
      "Frage stellen … mit @ auf eine Quelle eingrenzen",
    );
    await expect(input).toBeVisible();

    // The theme preference defaults to "system" (uiStore.themeMode), so emulating the media
    // query is enough to switch schemes without touching the settings page.
    await page.emulateMedia({ colorScheme: "light" });
    await expectNoSeriousA11yViolations(page, "Chat (helles Farbschema)");

    await page.emulateMedia({ colorScheme: "dark" });
    await expect(input).toBeVisible();
    await expectNoSeriousA11yViolations(page, "Chat (dunkles Farbschema)");
  });

  test("Space-Seite", async ({ authenticatedPage: page }) => {
    // /chat lands on the account's default space; its ID is the only way to reach the space
    // page, which has no list entry point yet (the card overview arrives with #593).
    await page.goto("/chat");
    await page.waitForURL(/\/spaces\/([^/]+)\/chats\//);
    const spaceId = /\/spaces\/([^/]+)\/chats\//.exec(page.url())?.[1];
    expect(spaceId, "Space-ID aus der Chat-URL").toBeTruthy();

    await page.goto(`/spaces/${spaceId}`);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Space-Seite");
  });

  // #957: Die Rollen-Chips („Administrator", „Eigentümer") fielen im Abschluss-Audit (#598) nur
  // im Dunkelschema durch color-contrast — beide Seiten deshalb in beiden Schemata, nach dem
  // Muster des Chat-Szenarios oben.
  test("Spaces-Übersicht in beiden Farbschemata", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/spaces");
    await expect(
      page.getByRole("heading", { level: 1, name: "Spaces" }),
    ).toBeVisible();

    await page.emulateMedia({ colorScheme: "light" });
    await expectNoSeriousA11yViolations(
      page,
      "Spaces-Übersicht (helles Farbschema)",
    );

    await page.emulateMedia({ colorScheme: "dark" });
    await expectNoSeriousA11yViolations(
      page,
      "Spaces-Übersicht (dunkles Farbschema)",
    );
  });

  test("Wissensbibliotheken", async ({ authenticatedPage: page }) => {
    await gotoLibraries(page);
    await expect(
      page.getByRole("heading", { level: 1, name: "Wissensbibliotheken" }),
    ).toBeVisible();

    await page.emulateMedia({ colorScheme: "light" });
    await expectNoSeriousA11yViolations(
      page,
      "Wissensbibliotheken (helles Farbschema)",
    );

    // #957: der „Eigentümer"-Chip fiel nur im Dunkelschema durch.
    await page.emulateMedia({ colorScheme: "dark" });
    await expectNoSeriousA11yViolations(
      page,
      "Wissensbibliotheken (dunkles Farbschema)",
    );
  });

  // #1541/#1601: die Benutzerverwaltung führt die dichteste Kombination des Bereichs — eine
  // Tab-Leiste, Zustandspunkt plus Text und Herkunft mit Symbol plus Wort in der Tabelle, ein
  // Zeilenmenü aus Nur-Icon-Schaltern und die Schalterkarte mit Konsequenz. Beide Bereiche und
  // beide Farbschemata, weil Zustand und Herkunft je über Farbe *und* Text geführt werden.
  test("Verwaltungsbereich: Benutzer in beiden Farbschemata", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/admin/users/accounts");
    await expect(
      page.getByRole("heading", { level: 1, name: "Benutzer" }),
    ).toBeVisible();
    // Die Liste muss gelandet sein, bevor axe analysiert (vorher steht dort ein Skeleton). Im
    // dev-Stack führt sie die Entwicklungsnutzer als Konten ohne Anbieterzeile; der Leerzustand
    // bleibt als Alternative stehen, damit der Test nicht an einem leeren Stapel scheitert.
    const list = page
      .getByRole("table", { name: "Konten" })
      .or(page.getByText("Kein Konto entspricht den gewählten Filtern."));
    await expect(list.first()).toBeVisible();

    await page.emulateMedia({ colorScheme: "light" });
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (Konten, helles Farbschema)",
    );

    await page.emulateMedia({ colorScheme: "dark" });
    await expect(list.first()).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (Konten, dunkles Farbschema)",
    );

    const localSignInSwitch = page.getByRole("switch", {
      name: "Lokale Anmeldung aktiv",
    });
    await page.goto("/admin/users/settings");
    await expect(localSignInSwitch).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (Benutzer-Einstellungen, dunkles Farbschema)",
    );

    await page.emulateMedia({ colorScheme: "light" });
    await expect(localSignInSwitch).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (Benutzer-Einstellungen, helles Farbschema)",
    );
  });

  test("Verwaltungsbereich: Gruppen", async ({ authenticatedPage: page }) => {
    await page.goto("/admin/groups");
    await expect(
      page.getByRole("heading", { level: 1, name: "Gruppen" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Verwaltungsbereich (Gruppen)");
  });

  // #956: die Branding-Seite lag außerhalb der Suite, obwohl sie mit der aria-hidden-Vorschau
  // als einzige Seite bewusst unsichtbare Interaktionsmuster rendert — genau dort saß der
  // aria-hidden-focus-Befund des Abschluss-Audits (#598). Der Wartepunkt auf den Vorschau-Text
  // stellt sicher, dass axe die Vorschau-Panels wirklich analysiert.
  test("Verwaltungsbereich: Branding", async ({ authenticatedPage: page }) => {
    await page.goto("/admin/branding");
    await expect(
      page.getByRole("heading", { level: 1, name: "Branding" }),
    ).toBeVisible();
    await expect(
      page.getByText("So wirkt die Einstellung in beiden Farbschemata", {
        exact: false,
      }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Verwaltungsbereich (Branding)");
  });

  // #1053: die dichteste Seite des Verwaltungsbereichs — achtspaltige Tabelle, Akkordeons je
  // Suchstufe und Statuschips in drei Farbrollen.
  test("Verwaltungsbereich: Suche & Indexierung", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/admin/search");
    await expect(
      page.getByRole("heading", { level: 1, name: "Suche & Indexierung" }),
    ).toBeVisible();
    // Wait for the status call to have landed: the chat role chip only renders once it has.
    await expect(page.getByLabel(/^Chat: /)).toBeVisible();

    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (Suche & Indexierung)",
    );
  });

  // #1542: die E-Mail-Seite bringt zwei eigene Muster mit, die sonst nirgends vorkommen — die
  // Reiter als Routen (role="tab" auf einem Link) und die Vorschau in einem abgeschotteten
  // iframe. Beide Reiter und beide Farbschemata, weil die Statuskachel ihren Zustand über einen
  // Farbpunkt *und* Text führt und genau das im dunklen Schema nachweisbar bleiben muss.
  /**
   * Die Vorschau der HTML-Fassung läuft in einem `iframe` mit leerem `sandbox` (#1542) - darin
   * führt kein Skript aus, also auch nicht das, das axe in jeden Rahmen injiziert. axe wartet
   * darauf bis zu seiner frameWaitTime und lässt den Test in den Timeout laufen. Der Rahmeninhalt
   * ist eine gerenderte Mail, keine Oberfläche dieser Anwendung; geprüft wird die Seite um ihn
   * herum, einschließlich seines eigenen `title` (axe frame-title greift auf dem Elternrahmen).
   */
  const PREVIEW_IFRAME_EXCLUDED = {
    exclude: ['iframe[title="Vorschau der HTML-Fassung"]'],
  };

  test("Verwaltungsbereich: E-Mail in beiden Farbschemata", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/admin/mail/server");
    await expect(
      page.getByRole("heading", { level: 1, name: "E-Mail" }),
    ).toBeVisible();
    // Wait for the settings call to have landed: the status tile only renders once it has.
    await expect(page.getByRole("region", { name: "Versandstatus" })).toBeVisible();

    await page.emulateMedia({ colorScheme: "light" });
    await expectNoSeriousA11yViolations(page, "Verwaltungsbereich (E-Mail, SMTP-Zugang, helles Farbschema)");

    await page.emulateMedia({ colorScheme: "dark" });
    await expect(page.getByRole("region", { name: "Versandstatus" })).toBeVisible();
    await expectNoSeriousA11yViolations(page, "Verwaltungsbereich (E-Mail, SMTP-Zugang, dunkles Farbschema)");

    await page.getByRole("tab", { name: "Vorlagen" }).click();
    await page.waitForURL("**/admin/mail/templates");
    await page.getByRole("navigation", { name: "Vorlagen" }).getByText("Testnachricht").click();
    // The debounced preview iframe is the last thing to appear; analysing before it is there
    // would skip exactly the pattern this block exists for.
    await expect(page.getByTitle("Vorschau der HTML-Fassung")).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (E-Mail, Vorlagen, dunkles Farbschema)",
      PREVIEW_IFRAME_EXCLUDED,
    );

    await page.emulateMedia({ colorScheme: "light" });
    await expect(page.getByTitle("Vorschau der HTML-Fassung")).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Verwaltungsbereich (E-Mail, Vorlagen, helles Farbschema)",
      PREVIEW_IFRAME_EXCLUDED,
    );
  });

  // #800: die Einstellungsseite lag als einzige globale Seite außerhalb der Suite, obwohl sie
  // mit Badge neben der H1 und Akzent-Avatar eigene Farbkombinationen einführt (#788).
  test("Benutzer-Einstellungen", async ({ authenticatedPage: page }) => {
    await page.goto("/settings");
    await expect(
      page.getByRole("heading", { level: 1, name: "Ihre Einstellungen" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Benutzer-Einstellungen");
  });
  /**
   * #1540: Die vier Selbstbedienungsseiten rendern ohne Sitzung wie die Anmeldeseite, auf
   * Navy-Grund mit eigener Karte, und führen als einzige Seiten Passwortfeld mit Sichtbarkeits-
   * Umschalter, Stärkeanzeige und Ergebnisansicht ein. Die Konfiguration wird wie bei der
   * Anmeldeseite abgefangen: Der Stack läuft im dev-Modus, in dem die lokale Kontoverwaltung aus
   * ist und /register sowie /forgot-password deshalb umleiten würden.
   *
   * Vertrag für weitere E2E-Tests (#1543): `/set-password` und `/verify-email` **entfernen den
   * Token sofort aus der URL** (ADR-0033, Entscheidung 9 — er stünde sonst als Referrer im
   * nginx-Zugriffslog). Nach dem Laden enthält `page.url()` kein `token=` mehr, und ein Neuladen
   * der Seite zeigt „Dieser Link ist nicht mehr gültig." — ein Test muss den Link also jedes Mal
   * frisch aufrufen statt die Seite neu zu laden.
   */
  const LOCAL_ACCOUNTS_CONFIG = {
    mode: "oidc",
    providers: [],
    localAccounts: {
      enabled: true,
      selfRegistrationEnabled: true,
      passwordResetEnabled: true,
      passwordMinLength: 12,
    },
  };

  /**
   * Das Farbschema wird **vor** dem Laden gesetzt, nie auf der gerenderten Seite umgeschaltet: Ein
   * Wechsel danach lässt auf den Seiten des Anmelderahmens eine gemischte Palette zurück und erzeugt
   * Farbpaare, die es in keinem der beiden Schemata gibt. `color-contrast` bleibt auf diesen Seiten in
   * **beiden** Schemata aus - sie erfüllen den Schwellwert mit den Farben des Hauses nicht (#1600,
   * mit den gemessenen Paaren); jede andere Regel wird in beiden Schemata geprüft.
   */
  const AUTH_PAGE_CONTRAST_KNOWN_GAP = { disableRules: ["color-contrast"] };

  test("Selbstbedienung: Passwort festlegen in beiden Farbschemata", async ({ page }) => {
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({ json: LOCAL_ACCOUNTS_CONFIG }),
    );
    for (const scheme of ["light", "dark"] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/set-password?token=e2e-token");
      await expect(
        page.getByRole("heading", { level: 1, name: "Passwort festlegen" }),
      ).toBeVisible();
      // Die Stärkeanzeige und der Generator gehören zur Seite, die geprüft wird - ohne Eingabe wäre
      // genau der Teil mit eigenen Farbrollen nicht im Baum.
      await page.getByRole("button", { name: "Sicheres Passwort erzeugen" }).click();
      await expect(page.getByTestId("password-strength")).toBeVisible();
      await expectNoSeriousA11yViolations(
        page,
        `Passwort festlegen (${scheme})`,
        AUTH_PAGE_CONTRAST_KNOWN_GAP,
      );
    }
  });

  test("Selbstbedienung: Passwort vergessen mit Ergebnisansicht", async ({ page }) => {
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({ json: LOCAL_ACCOUNTS_CONFIG }),
    );
    await page.route("**/api/v1/auth/local/forgot-password", (route) =>
      route.fulfill({ status: 204 }),
    );
    await page.goto("/forgot-password");
    await expect(page.getByRole("heading", { level: 1, name: "Passwort vergessen" })).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Passwort vergessen (Formular)",
      AUTH_PAGE_CONTRAST_KNOWN_GAP,
    );

    await page.getByLabel("E-Mail-Adresse").fill("erika.muster@stadt.example");
    await page.getByRole("button", { name: "Link anfordern" }).click();
    await expect(page.getByRole("alert")).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Passwort vergessen (Ergebnisansicht)",
      AUTH_PAGE_CONTRAST_KNOWN_GAP,
    );
  });

  test("Selbstbedienung: Registrierung", async ({ page }) => {
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({ json: LOCAL_ACCOUNTS_CONFIG }),
    );
    await page.goto("/register");
    await expect(page.getByRole("heading", { level: 1, name: "Konto registrieren" })).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Registrierung", AUTH_PAGE_CONTRAST_KNOWN_GAP);
  });

  test("Selbstbedienung: E-Mail-Bestätigung", async ({ page }) => {
    await page.route("**/api/v1/auth/config", (route) =>
      route.fulfill({ json: LOCAL_ACCOUNTS_CONFIG }),
    );
    await page.route("**/api/v1/auth/local/verify-email", (route) =>
      route.fulfill({ status: 204 }),
    );
    await page.goto("/verify-email?token=e2e-token");
    await expect(page.getByRole("alert")).toContainText("E-Mail-Adresse bestätigt");

    await expectNoSeriousA11yViolations(page, "E-Mail-Bestätigung", AUTH_PAGE_CONTRAST_KNOWN_GAP);
  });
});
