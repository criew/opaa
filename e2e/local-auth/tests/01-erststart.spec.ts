import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  openLocalAuthSettings,
  signIn,
  signInSuccessfully,
  smtp,
} from "../../fixtures/localAuth";
import { clearMailbox, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 1 of #1543: the first start.
 *
 * The installation this runs against has no OIDC provider at all and exactly one account - the
 * bootstrap administrator of the answer file (ADR-0033, Entscheidung 5; ADR-0060). Completing it
 * with "LOCAL_ONLY" switched the local account management on (ADR-0060, Entscheidung 9), so the
 * journey starts on the regular sign-in page: sign in, set up the mail server, prove it works with
 * a test mail. The administrators' own route, which only renders while the management is off, is
 * checked in 05-abschalten.spec.ts.
 *
 * It runs first by file name on purpose: every later scenario needs a reachable mail server and the
 * management switched on, and calls the same idempotent helpers in its own setup so it stays
 * runnable on its own.
 */
test.describe("Erststart: Systemverwalter, SMTP, Schalter", () => {
  test.beforeEach(async ({ page }) => {
    await acceptConfirmDialogs(page);
  });

  test("der Systemverwalter meldet sich nach der Installation auf /login an", async ({ page }) => {
    // The installation left the management on: /login carries the password form, and the
    // administrators' route sends the visitor there.
    await page.goto("/login/system");
    await expect(page).toHaveURL(/\/login(?:$|[?#])/);
    await expect(page.getByRole("form", { name: "Konto dieser Installation" })).toBeVisible();
    await expect(
      page.getByRole("link", { name: "Anmeldung für die Systemverwaltung" }),
    ).toHaveCount(0);

    // A wrong password answers the same way every rejected local sign-in does (ADR-0033,
    // Entscheidung 9) - it never says whether the address exists.
    await signIn(page, bootstrapAdmin.email, "völlig-falsches-passwort");
    await expect(
      page.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();

    // The answer file sets the password, so the installation forces no change and the session
    // starts right away.
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await expect(page).toHaveURL(/\/chat/);
  });

  test("SMTP wird eingerichtet und mit einer Testmail belegt", async ({ page }) => {
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await clearMailbox();
    await configureSmtp(page);

    // The test mail always goes to the signed-in account's own address - a different one cannot be
    // entered here on purpose.
    await page.getByRole("button", { name: "Testmail an mich senden" }).click();
    // The result of the attempt is shown as an alert - asserting it here means a skipped send (SMTP
    // not actually on) fails with its own message instead of as a mail that never arrives.
    await expect(page.getByText(/^Die Testnachricht wurde/)).toBeVisible();
    const delivered = await waitForMail(bootstrapAdmin.email);
    expect(delivered.recipients.map((address) => address.toLowerCase())).toContain(
      bootstrapAdmin.email.toLowerCase(),
    );

    // The status card now names the successful delivery instead of "Nicht konfiguriert".
    await page.reload();
    await expect(page.getByRole("region", { name: "Versandstatus" })).toContainText(
      "Letzter Versand erfolgreich",
    );
  });

  test("die lokale Benutzerverwaltung ist nach der Installation eingeschaltet", async ({
    page,
    browser,
  }) => {
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await openLocalAuthSettings(page);
    await expect(page.getByRole("switch", { name: "Lokale Anmeldung aktiv" })).toBeChecked();

    // The switch is the `enabled` of the LOCAL provider row (Entscheidung 4), and the public
    // configuration endpoint is what the sign-in page reads.
    const config = await page.request.get("/api/v1/auth/config");
    expect(config.ok()).toBeTruthy();
    expect(await config.json()).toMatchObject({ localAccounts: { enabled: true } });

    // /login carries the password form, and /login/system redirects to it - asserted
    // from a context without a session, because a signed-in browser is sent into the app instead.
    const visitorContext = await browser.newContext();
    const visitor = await visitorContext.newPage();
    await visitor.goto("/login/system");
    await expect(visitor).toHaveURL(/\/login(?:$|[?#])/);
    // Der Abschnitt heißt nach seinem Weg; „Anmelden" steht nur noch als Seitenüberschrift.
    await expect(visitor.getByRole("form", { name: "Konto dieser Installation" })).toBeVisible();
    await expect(visitor.getByLabel("E-Mail-Adresse")).toBeVisible();
    await visitorContext.close();
  });

  test("der SMTP-Zugang steht als Hostname des Stapels in den Einstellungen", async ({ page }) => {
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await page.goto("/admin/mail/server");
    await expect(page.getByLabel("Server")).toHaveValue(smtp.host);
    await expect(page.getByLabel("Port")).toHaveValue(smtp.port);
  });
});
