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
          authority: "http://localhost/oidc-stub",
          clientId: "e2e",
        },
      }),
    );
    await page.goto("/login");
    await expect(
      page.getByRole("button", { name: "Anmelden über den Verzeichnisdienst" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Anmeldeseite");
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

  test("Wissensbibliotheken", async ({ authenticatedPage: page }) => {
    await gotoLibraries(page);
    await expect(
      page.getByRole("heading", { level: 1, name: "Wissensbibliotheken" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Wissensbibliotheken");
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

  // #800: die Einstellungsseite lag als einzige globale Seite außerhalb der Suite, obwohl sie
  // mit Badge neben der H1 und Akzent-Avatar eigene Farbkombinationen einführt (#788).
  test("Benutzer-Einstellungen", async ({ authenticatedPage: page }) => {
    await page.goto("/settings");
    await expect(
      page.getByRole("heading", { level: 1, name: "Ihre Einstellungen" }),
    ).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Benutzer-Einstellungen");
  });
});
