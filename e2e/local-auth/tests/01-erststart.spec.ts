import { expect, test } from "@playwright/test";
import { expectNoSeriousA11yViolations } from "../../fixtures/a11y";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  enableLocalAccounts,
  signIn,
  signInSuccessfully,
  smtp,
} from "../../fixtures/localAuth";
import { clearMailbox, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 1 of #1543: the first start.
 *
 * The installation this runs against has no OIDC provider at all and exactly one account - the
 * bootstrap administrator the backend seeded itself (ADR-0033, Entscheidung 5). The journey is the
 * one the handbook chapter describes: sign in on the administrators' own route, set up the mail
 * server, prove it works with a test mail, then switch the local account management on.
 *
 * It runs first by file name on purpose: every later scenario needs a reachable mail server and the
 * management switched on, and calls the same idempotent helpers in its own setup so it stays
 * runnable on its own.
 */
test.describe("Erststart: Systemverwalter, SMTP, Schalter", () => {
  test.beforeEach(({ page }) => {
    acceptConfirmDialogs(page);
  });

  test("der Systemverwalter meldet sich auf /login/system an", async ({ page }) => {
    // While the local account management is off, /login is the OIDC page - and there is no provider,
    // so it offers nothing at all. The quiet link to the administrators' route is the only way in.
    await page.goto("/login");
    await expect(
      page.getByRole("link", { name: "Anmeldung für die Systemverwaltung" }),
    ).toBeVisible();

    await page.goto("/login/system");
    await expect(
      page.getByRole("form", { name: "Anmeldung für die Systemverwaltung" }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "Nur für lokale Systemverwalter-Konten. Alle anderen Konten melden sich über die reguläre Anmeldung an.",
      ),
    ).toBeVisible();

    // The only scenario that can check this route: it redirects to /login as soon as the management
    // is on, which the last test of this file switches. The scheme is set before loading, never
    // switched on a rendered page - see the first scenario of 07-barrierefreiheit.spec.ts for why.
    for (const scheme of ["light", "dark"] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/login/system");
      await expect(page.getByLabel("E-Mail-Adresse")).toBeVisible();
      await expectNoSeriousA11yViolations(
        page,
        `Anmeldung für die Systemverwaltung (${scheme})`,
        // Bekannte Kontrastlücke beider Schemata auf den Seiten des Anmelderahmens (#1600) -
        // ausführlich begründet in 07-barrierefreiheit.spec.ts.
        { disableRules: ["color-contrast"] },
      );
    }
    await page.emulateMedia({ colorScheme: "light" });

    // A wrong password answers the same way every rejected local sign-in does (ADR-0033,
    // Entscheidung 9) - it never says whether the address exists.
    await signIn(page, bootstrapAdmin.email, "völlig-falsches-passwort", {
      route: "/login/system",
    });
    await expect(
      page.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();

    // OPAA_INITIAL_ADMIN_PASSWORD is set in this stack, so the seed did not force a change
    // (Entscheidung 5, the documented CI path) and the session starts right away.
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await expect(page).toHaveURL(/\/chat/);
  });

  test("SMTP wird eingerichtet und mit einer Testmail belegt", async ({ page }) => {
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
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

  test("die lokale Benutzerverwaltung wird eingeschaltet", async ({ page, browser }) => {
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await enableLocalAccounts(page);

    // The switch is the `enabled` of the LOCAL provider row (Entscheidung 4), and the public
    // configuration endpoint is what the sign-in page reads.
    const config = await page.request.get("/api/v1/auth/config");
    expect(config.ok()).toBeTruthy();
    expect(await config.json()).toMatchObject({ localAccounts: { enabled: true } });

    // From here on /login carries the password form, and /login/system redirects to it - asserted
    // from a context without a session, because a signed-in browser is sent into the app instead.
    const visitorContext = await browser.newContext();
    const visitor = await visitorContext.newPage();
    await visitor.goto("/login/system");
    await expect(visitor).toHaveURL(/\/login(?:$|[?#])/);
    await expect(visitor.getByRole("form", { name: "Anmeldung" })).toBeVisible();
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
