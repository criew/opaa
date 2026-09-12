import { expect, test } from "@playwright/test";
import { expectNoSeriousA11yViolations } from "../../fixtures/a11y";
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
 * Scenario 7 of #1543: the accessibility pass over the pages only this target can reach.
 *
 * e2e/tests/accessibility.spec.ts already checks the self-service pages and the administration of
 * local accounts - but against a stubbed `/api/v1/auth/config`, because the regular stack runs the
 * "dev" profile and has no local accounts at all. What it therefore cannot check is exactly what is
 * checked here: the pages in the states only a real local session produces - the administrators'
 * own sign-in route, the sign-in page with the password form actually switched on, the forced
 * password change with its reason, and the two hand-over dialogs that carry a one-time secret.
 *
 * `/login/system` is checked in 01-erststart.spec.ts instead: it only renders while the management is
 * switched off, and that scenario is the one that runs in that state.
 *
 * Threshold and rule set come from the shared fixture (serious/critical fail, WCAG 2.1 AA).
 */
test.describe("Barrierefreiheit der lokalen Anmeldung (axe-core)", () => {
  const address = uniqueAddress("anna.siebert");
  const password = "Birkenrinde-Sonnenhang-74";
  const displayName = "Anna Siebert";

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
      { email: address, displayName, reason: "Geschäftsstelle Gremien" },
      password,
    );
    await context.close();
  });

  test("Anmeldeseite mit eingeschalteter lokaler Anmeldung in beiden Farbschemata", async ({
    page,
  }) => {
    // Das Schema wird **vor** dem Laden gesetzt und für den zweiten Durchgang neu geladen. Wird es
    // erst danach umgestellt, mischt sich auf den Seiten des Anmelderahmens die Palette: Ein Teil der
    // Farben folgt der Medienabfrage sofort, der von der Anwendung aufgelöste Teil nicht — axe sieht
    // dann Paare, die es in keinem der beiden Schemata gibt (#1600). Geprüft wird damit auch das, was
    // eine Person tatsächlich sieht: eine Seite, die unter ihrem Schema geladen wurde.
    for (const scheme of ["light", "dark"] as const) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.goto("/login");
      await expect(page.getByLabel("E-Mail-Adresse")).toBeVisible();
      await expectNoSeriousA11yViolations(page, `Anmeldeseite mit lokaler Maske (${scheme})`);
    }
  });

  test("Anmeldeseite mit Fehlermeldung", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel("E-Mail-Adresse").fill(address);
    await page.getByLabel("Passwort", { exact: true }).fill("absichtlich-falsch");
    await page.getByRole("button", { name: "Anmelden" }).click();
    await expect(page.getByText("Anmeldung nicht möglich")).toBeVisible();

    await expectNoSeriousA11yViolations(page, "Anmeldeseite mit Fehlermeldung");
  });

  test("erzwungener Passwortwechsel mit Anlass", async ({ browser }) => {
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);

    const forced = uniqueAddress("peter.wendt");
    await openUserAdministration(admin);
    await admin.getByRole("button", { name: "Konto anlegen" }).click();
    const dialog = admin.getByRole("dialog", { name: "Lokales Konto anlegen" });
    await dialog.locator("#user-form-email").fill(forced);
    await dialog.locator("#user-form-display-name").fill("Peter Wendt");
    await dialog.locator("#user-form-created-reason").fill("Zuarbeit Haushaltsplanung");
    await dialog
      .getByRole("radio", {
        name: "Anfangspasswort jetzt erzeugen (Wechsel bei der ersten Anmeldung)",
      })
      .click();

    // The open form of the dialog is its own state worth checking - it carries the only mandatory
    // free-text field of the administration and a radio group with a long option label.
    await expectNoSeriousA11yViolations(admin, "Dialog „Lokales Konto anlegen“");

    await dialog.getByRole("button", { name: "Anlegen" }).click();
    const handover = admin.getByRole("dialog", { name: "Passwort übergeben" });
    await expect(handover).toBeVisible();
    await expectNoSeriousA11yViolations(admin, "Dialog „Passwort übergeben“");
    const initial = (await admin.getByTestId("generated-password-value").innerText()).trim();
    await handover.getByRole("button", { name: "Übergeben, schließen" }).click();

    const userContext = await browser.newContext();
    const user = await userContext.newPage();
    await user.emulateMedia({ colorScheme: "light" });
    await signInSuccessfully(user, forced, initial);
    await expect(user).toHaveURL(/\/account\/password/);
    await expect(user.getByText("Neues Passwort erforderlich")).toBeVisible();
    await expectNoSeriousA11yViolations(user, "Erzwungener Passwortwechsel (hell)");

    // Neu laden statt nur umschalten - siehe die Begründung im ersten Szenario dieser Datei.
    await user.emulateMedia({ colorScheme: "dark" });
    await user.reload();
    await expect(user.getByText("Neues Passwort erforderlich")).toBeVisible();
    await expectNoSeriousA11yViolations(user, "Erzwungener Passwortwechsel (dunkel)");

    await userContext.close();
    await adminContext.close();
  });

  test("Einladungslink-Dialog und die Seite, auf die er führt", async ({ browser }) => {
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);

    const invited = uniqueAddress("sabine.krause");
    await clearMailbox();
    await openUserAdministration(admin);
    await admin.getByRole("button", { name: "Konto anlegen" }).click();
    const dialog = admin.getByRole("dialog", { name: "Lokales Konto anlegen" });
    await dialog.locator("#user-form-email").fill(invited);
    await dialog.locator("#user-form-display-name").fill("Sabine Krause");
    await dialog.locator("#user-form-created-reason").fill("Amtsleitung Sekretariat");
    await dialog.getByRole("button", { name: "Anlegen" }).click();
    await expect(admin.getByText(`Die Einladung wurde an ${invited} versendet.`)).toBeVisible();

    // The list in its loaded state, with the review notice the mandatory-limit rule produces.
    await expectNoSeriousA11yViolations(admin, "Verwaltung lokaler Konten mit Kontenliste");

    const mail = await waitForMail(invited);
    const userContext = await browser.newContext();
    const user = await userContext.newPage();
    await user.goto(linkPathIn(mail));
    await expect(user.getByRole("heading", { level: 1, name: "Passwort festlegen" })).toBeVisible();
    await expectNoSeriousA11yViolations(user, "Passwort festlegen über einen echten Einladungslink");

    await userContext.close();
    await adminContext.close();
  });
});
