import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  expectSessionEndedWith,
  openUserAdministration,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";

/**
 * Scenario 5 of #1543: switching the local account management off.
 *
 * ADR-0033, Entscheidung 4: the switch takes effect on running sessions immediately, without the
 * server having to enumerate them - the token validator reads it on every request. A regular local
 * account is shown the door and told why; a local system administrator passes, because otherwise
 * whoever flipped the switch would have locked themselves out with it.
 *
 * The scenario restores the switch at the end: the later scenarios need it on, and a half-finished
 * run must not leave the installation in a state the next file was not written for.
 */
test.describe("Verwaltung abschalten: Nutzer-Sitzung endet, Verwalter bleibt", () => {
  const address = uniqueAddress("bettina.roth");
  const password = "Ahornsirup-Steilkueste-19";

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await configureSmtp(admin);
    await enableLocalAccounts(admin);
    await createActiveAccount(
      admin,
      browser,
      { email: address, displayName: "Bettina Roth", reason: "Sachgebiet Ordnungsamt" },
      password,
    );
    await context.close();
  });

  test("das Abschalten beendet die Nutzer-Sitzung mit Grund und lässt den Verwalter weiterarbeiten", async ({
    browser,
  }) => {
    const userContext = await browser.newContext();
    const user = await userContext.newPage();
    await signInSuccessfully(user, address, password);
    await user.goto("/spaces");
    await expect(user.getByText("Meine Dokumente").first()).toBeVisible();

    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);
    await openUserAdministration(admin);

    const toggle = admin.getByRole("switch", { name: "Lokale Anmeldung aktiv" });
    await expect(toggle).toBeChecked();
    await toggle.click();
    // The answer names how many accounts lost their session - the consequence in numbers, not a bare
    // "saved".
    await expect(admin.getByText(/Die lokale Anmeldung ist abgeschaltet\./)).toBeVisible();
    await expect(toggle).not.toBeChecked();

    // The administrator's own session survives: a local SYSTEM_ADMIN passes the validator even with
    // the switch off (ADR-0033, Entscheidung 4).
    await admin.goto("/spaces");
    await expect(admin.getByText("Meine Dokumente").first()).toBeVisible();

    // The regular account's next call is refused and the person is sent back to the sign-in page.
    // The marker asserted here is the built one, not the one ADR-0033 Entscheidung 4 names
    // (local_accounts_disabled): switching the management off revokes the sessions eagerly with
    // reason ADMIN_RESET, so the person is told their password was reset, which is not true. That
    // mismatch is #1595 - this assertion pins today's behaviour so the fix has to touch it.
    await expectSessionEndedWith(user, "session_revoked:admin_reset", {
      sentence: "Ihre Sitzung wurde beendet, weil die Systemverwaltung Ihr Passwort zurückgesetzt hat.",
    });

    // And it cannot sign in again: the password form is gone, /login offers no provider either.
    await user.goto("/login");
    await expect(user.getByLabel("E-Mail-Adresse")).toHaveCount(0);
    await expect(
      user.getByRole("link", { name: "Anmeldung für die Systemverwaltung" }),
    ).toBeVisible();

    // The administrators' own route is reachable again, and works.
    const adminAgainContext = await browser.newContext();
    const adminAgain = await adminAgainContext.newPage();
    await signInSuccessfully(adminAgain, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await expect(adminAgain).toHaveURL(/\/chat/);

    // Restore the state the following scenarios expect.
    await enableLocalAccounts(admin);
    await signInSuccessfully(user, address, password);

    await adminAgainContext.close();
    await adminContext.close();
    await userContext.close();
  });
});
