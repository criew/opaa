import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  expectSessionEndedWith,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { clearMailbox, linkPathIn, mailCount, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 3 of #1543: "Passwort vergessen" end to end, including what it does to a running session.
 *
 * Two properties are asserted that are easy to lose: the answer is the same whether or not an
 * account exists under the address (ADR-0033, Entscheidung 11 - the flow must not be an enumeration
 * channel), and setting a new password revokes the sessions the old one still had
 * (`password_invalidated_before`, Entscheidung 7) - the old tab learns *why* on its next call.
 */
test.describe("Passwort vergessen, Rücksetzen, alte Sitzung endet", () => {
  const address = uniqueAddress("jana.feldmann");
  const oldPassword = "Seeigel-Waldrand-31";
  const newPassword = "Nebelhorn-Quittensaft-88";

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
      { email: address, displayName: "Jana Feldmann", reason: "Fachverfahren Bauakten" },
      oldPassword,
    );
    await context.close();
  });

  test("eine unbekannte Adresse bekommt dieselbe Antwort und keine Mail", async ({ page }) => {
    await clearMailbox();
    await page.goto("/forgot-password");
    await expect(page.getByRole("heading", { level: 1, name: "Passwort vergessen" })).toBeVisible();
    await page.getByLabel("E-Mail-Adresse").fill("gibt-es-nicht@stadt.example");
    await page.getByRole("button", { name: "Link anfordern" }).click();

    await expect(
      page.getByText("Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt."),
    ).toBeVisible();
    // Nothing is sent - and the page says exactly what it says for an address that does exist.
    await expect.poll(() => mailCount(), { timeout: 5_000 }).toBe(0);
  });

  test("die Person setzt ein neues Passwort, die laufende Sitzung endet mit Grund", async ({
    browser,
  }) => {
    const sessionContext = await browser.newContext();
    const running = await sessionContext.newPage();
    await signInSuccessfully(running, address, oldPassword);
    await running.goto("/spaces");
    await expect(running.getByText("Meine Dokumente").first()).toBeVisible();

    await clearMailbox();
    const resetContext = await browser.newContext();
    const reset = await resetContext.newPage();
    await reset.goto("/forgot-password");
    await reset.getByLabel("E-Mail-Adresse").fill(address);
    await reset.getByRole("button", { name: "Link anfordern" }).click();
    await expect(
      reset.getByText("Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt."),
    ).toBeVisible();

    const mail = await waitForMail(address);
    const resetPath = linkPathIn(mail);
    expect(resetPath).toContain("/set-password");

    await reset.goto(resetPath);
    await expect(reset.getByRole("heading", { level: 1, name: "Passwort festlegen" })).toBeVisible();
    expect(reset.url()).not.toContain("token=");
    await reset.locator("#set-password-new").fill(newPassword);
    await reset.locator("#set-password-repeat").fill(newPassword);
    await reset.getByRole("button", { name: "Passwort festlegen" }).click();
    await expect(
      reset.getByText("Passwort festgelegt — Sie können sich jetzt anmelden."),
    ).toBeVisible();

    // The other tab's access token carries an `iat` before password_invalidated_before, so its next
    // call is refused with session_revoked:password_changed - and the sign-in page names the reason
    // instead of a bare "session expired" (ADR-0033, Entscheidung 8).
    await expectSessionEndedWith(running, "session_revoked:password_changed", {
      sentence: "Ihre Sitzung wurde beendet, weil Ihr Passwort geändert wurde.",
    });

    // The old password is gone, the new one works.
    await running.getByLabel("E-Mail-Adresse").fill(address);
    await running.getByLabel("Passwort", { exact: true }).fill(oldPassword);
    await running.getByRole("button", { name: "Anmelden" }).click();
    await expect(
      running.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();
    await signInSuccessfully(running, address, newPassword);

    await resetContext.close();
    await sessionContext.close();
  });
});
