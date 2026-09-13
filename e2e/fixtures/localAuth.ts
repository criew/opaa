import { expect, type Browser, type Page } from "@playwright/test";

/**
 * Helpers shared by the scenarios of the local-auth target (ADR-0033, #1543).
 *
 * Unlike e2e/fixtures/auth.ts - which selects a dev user through `?devUser=` because the regular
 * suite runs the "dev" auth profile and never signs anyone in (ADR-0009) - these drive the real
 * sign-in form: this target runs the operating mode "oidc" with no OIDC provider at all, so the
 * local sign-in is the only way in.
 *
 * Every selector here is the accessible name of the built page, not a test id: the pages are the
 * ones e2e/tests/accessibility.spec.ts already checks, and a name that changes has to break a test.
 */

/** The account the backend seeds on its first start, with the password fixed in local-auth.env. */
export const bootstrapAdmin = {
  email: process.env.E2E_BOOTSTRAP_ADMIN_EMAIL ?? "",
  password: process.env.E2E_BOOTSTRAP_ADMIN_PASSWORD ?? "",
};

/** Host and port of the stack's mail catcher as the *backend* reaches it over the Compose network. */
export const smtp = {
  host: process.env.E2E_SMTP_HOST ?? "mailpit",
  port: process.env.E2E_SMTP_PORT ?? "1025",
  fromAddress: "opaa@opaa.example",
  encryption: "Keine",
};

/**
 * Signs in on the given form and waits until the app shell has taken over.
 *
 * `/login/system` is the route for local system administrators while the local account management is
 * switched off; it redirects to `/login` once it is on (ADR-0033, Entscheidung 5). A scenario
 * therefore has to pass the route that matches the state it put the installation in.
 */
export async function signIn(
  page: Page,
  email: string,
  password: string,
  { route = "/login" }: { route?: string } = {},
): Promise<void> {
  await page.goto(route);
  await page.getByLabel("E-Mail-Adresse").fill(email);
  await page.getByLabel("Passwort", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Anmelden" }).click();
}

/** Signs in and asserts that the session actually started - i.e. the app, not the form, is shown. */
export async function signInSuccessfully(
  page: Page,
  email: string,
  password: string,
  options: { route?: string } = {},
): Promise<void> {
  await signIn(page, email, password, options);
  await expect(page).not.toHaveURL(/\/login(?:\/system)?(?:$|[?#])/, { timeout: 20_000 });
}

/**
 * Signs the current session out through the app's own menu.
 *
 * Deliberately not a context reset: logging out is what revokes the refresh cookie and puts the
 * access token on the `jti` denylist (ADR-0033, Entscheidung 7), and a scenario that only threw the
 * browser context away would leave the session valid on the server.
 */
export async function signOut(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Profil und Einstellungen" }).click();
  await page.getByRole("menuitem", { name: "Abmelden" }).click();
  await expect(page).toHaveURL(/\/login(?:$|[?#])/, { timeout: 20_000 });
}

/**
 * Accepts every confirmation of this page for the rest of the scenario.
 *
 * The consequence dialogs of the administration (switching the local account management off,
 * locking, resetting, deleting) are the app's own confirmation overlay (#1610), not a browser
 * dialog: they are part of the page and block it until they are answered. A registered handler is
 * what keeps the promise scenario-wide - Playwright runs it whenever the overlay stands between it
 * and an action or an auto-waiting assertion - so no scenario has to know which of its steps asks.
 *
 * The accepting button carries the verb of the action ("Löschen", "Abschalten", ...), so it is
 * addressed by its fixed id, not by a name. Playwright's default wait for the overlay to be hidden
 * again after the handler is kept on purpose: the click really does close it, and the wait turns a
 * confirmation that stays open into a failure here rather than into a puzzling one later.
 */
export async function acceptConfirmDialogs(page: Page): Promise<void> {
  await page.addLocatorHandler(
    page.getByRole("dialog").filter({ has: page.locator("#confirm-question") }),
    async (overlay) => {
      await overlay.locator("#confirm-accept").click();
    },
  );
}

/**
 * The account list of the administration (#1601: „Benutzer" has two areas, each its own route),
 * waited for until the list itself has loaded - the table if there is a row, otherwise its empty
 * state. Waiting for the filter bar alone would pass while the first page is still on its way.
 */
export async function openAccountList(page: Page): Promise<void> {
  await page.goto("/admin/users/accounts");
  await expect(page.getByRole("searchbox", { name: "Konten suchen" })).toBeVisible({
    timeout: 20_000,
  });
  await expect(
    page
      .getByRole("table", { name: "Konten" })
      .or(page.getByText("Kein Konto entspricht den gewählten Filtern."))
      .first(),
  ).toBeVisible({ timeout: 20_000 });
}

/** The settings area of the administration's user page, waited for until its card has loaded. */
export async function openLocalAuthSettings(page: Page): Promise<void> {
  await page.goto("/admin/users/settings");
  await expect(page.getByRole("switch", { name: "Lokale Anmeldung aktiv" })).toBeVisible({
    timeout: 20_000,
  });
}

/**
 * Enters the stack's mail catcher as the SMTP server and switches delivery on.
 *
 * The mail settings are an administration setting, not an environment variable (ADR-0033,
 * Entscheidung 10) - which is why this goes through the form instead of the env file, and why it
 * takes effect without a restart.
 */
export async function configureSmtp(page: Page): Promise<void> {
  await page.goto("/admin/mail/server");
  await expect(page.getByRole("switch", { name: "Versand aktiv" })).toBeVisible({
    timeout: 20_000,
  });
  await page.getByLabel("Server").fill(smtp.host);
  await page.getByLabel("Port").fill(smtp.port);
  // The form defaults to STARTTLS, which the mail catcher does not offer - and the backend requires
  // STARTTLS rather than merely offering it, so leaving the default fails every send with
  // "STARTTLS is required but host does not support STARTTLS".
  await page.getByLabel("Verschlüsselung").click();
  await page.getByRole("option", { name: "Keine" }).click();
  await page.getByLabel("Absenderadresse").fill(smtp.fromAddress);
  const active = page.getByRole("switch", { name: "Versand aktiv" });
  if (!(await active.isChecked())) {
    await active.click();
  }
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByText("Die E-Mail-Einstellungen wurden gespeichert.")).toBeVisible();

  // Asserted after a reload, not just from the snackbar: everything downstream depends on delivery
  // being on, and a mail that is merely skipped looks exactly like one that was never triggered.
  await page.reload();
  await expect(page.getByRole("switch", { name: "Versand aktiv" })).toBeChecked();
  await expect(page.getByLabel("Server")).toHaveValue(smtp.host);
}

/** Switches the local account management on, confirming the consequence dialog. */
export async function enableLocalAccounts(page: Page): Promise<void> {
  await openLocalAuthSettings(page);
  const toggle = page.getByRole("switch", { name: "Lokale Anmeldung aktiv" });
  if (!(await toggle.isChecked())) {
    await toggle.click();
    await expect(page.getByText("Die lokale Anmeldung ist eingeschaltet.")).toBeVisible();
  }
  await expect(toggle).toBeChecked();
}

/** A unique address per scenario run, so a repeated local run never collides with its own leftovers. */
export function uniqueAddress(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}@stadt.example`;
}

/**
 * Creates an active local account through the administration and returns its initial password.
 *
 * The account is created with a generated initial password (mode INITIAL_PASSWORD), which means it
 * starts with a forced change - so this also walks that change, leaving an ACTIVE account with the
 * password it returns. Scenarios whose subject is *not* the creation of an account use this as
 * setup; scenario 2 drives both creation paths through the form itself.
 */
export async function createActiveAccount(
  adminPage: Page,
  browser: Browser,
  { email, displayName, reason }: { email: string; displayName: string; reason: string },
  password: string,
): Promise<void> {
  await openAccountList(adminPage);
  await adminPage.getByRole("button", { name: "Konto anlegen" }).click();
  const dialog = adminPage.getByRole("dialog", { name: "Lokales Konto anlegen" });
  await expect(dialog).toBeVisible();
  await dialog.locator("#user-form-email").fill(email);
  await dialog.locator("#user-form-display-name").fill(displayName);
  await dialog.locator("#user-form-created-reason").fill(reason);
  await dialog
    .getByRole("radio", {
      name: "Anfangspasswort jetzt erzeugen (Wechsel bei der ersten Anmeldung)",
    })
    .click();
  await dialog.getByRole("button", { name: "Anlegen" }).click();

  const handover = adminPage.getByRole("dialog", { name: "Passwort übergeben" });
  await expect(handover).toBeVisible();
  const initial = (await adminPage.getByTestId("generated-password-value").innerText()).trim();
  await handover.getByRole("button", { name: "Übergeben, schließen" }).click();

  const context = await browser.newContext();
  const page = await context.newPage();
  await signInSuccessfully(page, email, initial);
  await expect(page).toHaveURL(/\/account\/password/);
  await page.locator("#change-password-current").fill(initial);
  await page.locator("#change-password-new").fill(password);
  await page.locator("#change-password-repeat").fill(password);
  await page.getByRole("button", { name: "Passwort speichern" }).click();
  await expect(page).not.toHaveURL(/\/account\/password/, { timeout: 20_000 });
  await context.close();
}

/**
 * Navigates inside the running app and asserts that the session ended, naming its cause.
 *
 * The cause is asserted on the `WWW-Authenticate` marker of the 401 itself (ADR-0033, Entscheidung
 * 8), not only on the sentence the sign-in page shows: the marker is the contract, and the sentence
 * is derived from it.
 *
 * Deliberately an in-app navigation, not `page.goto`: a full reload re-initialises the app, its own
 * "no identity provider" error then stands where the reason would be, and the marker never reaches
 * the interceptor at all.
 */
export async function expectSessionEndedWith(
  page: Page,
  marker: string,
  { sentence }: { sentence?: string } = {},
): Promise<void> {
  const [refused] = await Promise.all([
    page.waitForResponse(
      (response) => response.status() === 401 && response.url().includes("/api/v1/"),
      { timeout: 20_000 },
    ),
    page.getByRole("link", { name: "Katalog" }).click(),
  ]);
  expect(refused.headers()["www-authenticate"] ?? "").toContain(marker);
  await expect(page).toHaveURL(/\/login(?:$|[?#])/, { timeout: 20_000 });
  if (sentence) {
    await expect(page.getByText(sentence)).toBeVisible();
  }
}
