import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  openUserAdministration,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { clearMailbox, linkPathIn, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 4 of #1543: the account lockout after failed sign-ins, and the way back out.
 *
 * Five wrong passwords lock the account for fifteen minutes (ADR-0033, Entscheidung 9). Three
 * properties are asserted: the answer after the lock is the same as before it (naming the state
 * would confirm a password to an attacker), the administrator can lift the lock without waiting,
 * and - the part that is easy to get wrong - the lockout does not cut off self-help: the
 * "Passwort vergessen" route stays open for a locked-out account.
 *
 * The rate limits of the sign-in endpoint are raised for this stack
 * (e2e/docker-compose.local-auth.yml) so the five attempts reach the lockout instead of a 429; the
 * lockout values themselves are left at their production defaults.
 */
test.describe("Fehlversuche sperren, Verwalter entsperrt", () => {
  const address = uniqueAddress("markus.lohse");
  const password = "Fichtenzapfen-Morgenrot-5";
  const displayName = "Markus Lohse";

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
      { email: address, displayName, reason: "Projektstelle Digitalisierung, befristet" },
      password,
    );
    await context.close();
  });

  test("fünf Fehlversuche sperren das Konto, der Verwalter hebt die Sperre auf", async ({
    browser,
  }) => {
    const userContext = await browser.newContext();
    const user = await userContext.newPage();

    await user.goto("/login");
    for (let attempt = 1; attempt <= 5; attempt++) {
      await user.getByLabel("E-Mail-Adresse").fill(address);
      await user.getByLabel("Passwort", { exact: true }).fill(`falsch-${attempt}`);
      await user.getByRole("button", { name: "Anmelden" }).click();
      await expect(
        user.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
      ).toBeVisible();
    }

    // The sixth attempt uses the *correct* password and is still refused - with the very same
    // sentence. That is the point: a different answer would tell an attacker the password is right.
    await user.getByLabel("E-Mail-Adresse").fill(address);
    await user.getByLabel("Passwort", { exact: true }).fill(password);
    await user.getByRole("button", { name: "Anmelden" }).click();
    await expect(
      user.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();
    await expect(user).toHaveURL(/\/login(?:$|[?#])/);

    // The administration sees the state and its cause, and unlocks without a confirmation dialog -
    // unlocking takes nothing away from anyone.
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);
    await clearMailbox();
    await openUserAdministration(admin);
    const table = admin.getByRole("table", { name: "Lokale Konten" });
    await expect(table).toContainText("Gesperrt (Fehlversuche)");

    await admin.getByRole("button", { name: `Aktionen für „${displayName}“` }).click();
    await admin.getByRole("menuitem", { name: "Entsperren" }).click();
    await expect(admin.getByText(`„${displayName}“ wurde entsperrt.`)).toBeVisible();
    await expect(table).toContainText("Aktiv");

    // Unlocking resets the counter, so the correct password works immediately.
    await signInSuccessfully(user, address, password);

    await adminContext.close();
    await userContext.close();
  });

  test("die Fehlversuch-Sperre schneidet den Rücksetzweg nicht ab", async ({ browser }) => {
    const userContext = await browser.newContext();
    const user = await userContext.newPage();

    await user.goto("/login");
    for (let attempt = 1; attempt <= 5; attempt++) {
      await user.getByLabel("E-Mail-Adresse").fill(address);
      await user.getByLabel("Passwort", { exact: true }).fill(`wieder-falsch-${attempt}`);
      await user.getByRole("button", { name: "Anmelden" }).click();
      await expect(user.getByText("Anmeldung nicht möglich")).toBeVisible();
    }

    // ADR-0033, Entscheidung 9: possession of the mailbox is the proof the lockout asks for, so the
    // reset mail still goes out - and redeeming the link lifts the lockout.
    await clearMailbox();
    await user.goto("/forgot-password");
    await user.getByLabel("E-Mail-Adresse").fill(address);
    await user.getByRole("button", { name: "Link anfordern" }).click();
    await expect(
      user.getByText("Wenn zu dieser Adresse ein Konto besteht, haben wir eine E-Mail geschickt."),
    ).toBeVisible();
    const mail = await waitForMail(address);
    const resetPath = linkPathIn(mail);
    expect(resetPath).toContain("/set-password");

    // Und der eingelöste Link hebt die Sperre auf - das ist der Ausweg, den der Absatz verspricht.
    const recovered = "Uferschwalbe-Kiesbank-52";
    await user.goto(resetPath);
    await user.locator("#set-password-new").fill(recovered);
    await user.locator("#set-password-repeat").fill(recovered);
    await user.getByRole("button", { name: "Passwort festlegen" }).click();
    await expect(
      user.getByText("Passwort festgelegt — Sie können sich jetzt anmelden."),
    ).toBeVisible();
    await signInSuccessfully(user, address, recovered);

    await userContext.close();
  });
});
