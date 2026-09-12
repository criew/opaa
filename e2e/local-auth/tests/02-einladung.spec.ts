import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  enableLocalAccounts,
  openAccountList,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { clearMailbox, linkPathIn, waitForMail } from "../../fixtures/mailpit";

/**
 * Scenario 2 of #1543: invitation, setting the password, first sign-in.
 *
 * The whole path a newly invited person walks, across two browser contexts: the administrator
 * creates the account and never learns the password, the person receives the link by mail, sets a
 * password and lands in their own personal space. The account is INVITED until the password is set -
 * a sign-in before that is refused with the same answer as every other rejection.
 */
test.describe("Einladung, Passwort setzen, erste Anmeldung", () => {
  const invited = uniqueAddress("erika.muster");
  const invitedName = "Erika Muster";
  const invitedPassword = "Windmuehle-Brombeere-7";

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    acceptConfirmDialogs(page);
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await configureSmtp(page);
    await enableLocalAccounts(page);
    await page.close();
  });

  test("Verwalter lädt ein Konto ein, die Person setzt ihr Passwort und meldet sich an", async ({
    browser,
  }) => {
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);

    await clearMailbox();
    await openAccountList(admin);
    await admin.getByRole("button", { name: "Konto anlegen" }).click();

    const dialog = admin.getByRole("dialog", { name: "Lokales Konto anlegen" });
    await expect(dialog).toBeVisible();
    await dialog.locator("#user-form-email").fill(invited);
    await dialog.locator("#user-form-display-name").fill(invitedName);
    // Mandatory and purpose-bound (ADR-0033, Entscheidung 11) - the person can read it later, which
    // the last step of this scenario asserts.
    await dialog.locator("#user-form-created-reason").fill("Sachbearbeitung Meldewesen, Vertretung");
    // The default of the radio group: an invitation, not a password the administrator would know.
    await expect(dialog.getByRole("radio", { name: "Einladung per E-Mail senden" })).toBeChecked();
    await dialog.getByRole("button", { name: "Anlegen" }).click();

    await expect(admin.getByText(`Die Einladung wurde an ${invited} versendet.`)).toBeVisible();
    await expect(admin.getByRole("table", { name: "Konten" })).toContainText(invitedName);
    await expect(admin.getByRole("table", { name: "Konten" })).toContainText("Eingeladen");

    const invitation = await waitForMail(invited);
    const setupPath = linkPathIn(invitation);
    expect(setupPath).toContain("/set-password");
    expect(setupPath).toContain("token=");

    const userContext = await browser.newContext();
    const user = await userContext.newPage();

    // An invited account has no password yet, so it cannot sign in - and the answer is the one every
    // rejection gets, never "this account is not active yet" (ADR-0033, Entscheidung 9).
    await user.goto("/login");
    await user.getByLabel("E-Mail-Adresse").fill(invited);
    await user.getByLabel("Passwort", { exact: true }).fill(invitedPassword);
    await user.getByRole("button", { name: "Anmelden" }).click();
    await expect(
      user.getByText("Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort."),
    ).toBeVisible();

    await user.goto(setupPath);
    await expect(user.getByRole("heading", { level: 1, name: "Passwort festlegen" })).toBeVisible();
    // ADR-0033, Entscheidung 9: the raw token is out of the address bar before anything else - it
    // would otherwise stand in the nginx access log as a referrer.
    await expect.poll(() => user.url()).not.toContain("token=");

    await user.locator("#set-password-new").fill(invitedPassword);
    await user.locator("#set-password-repeat").fill(invitedPassword);
    await user.getByRole("button", { name: "Passwort festlegen" }).click();
    await expect(
      user.getByText("Passwort festgelegt — Sie können sich jetzt anmelden."),
    ).toBeVisible();

    // The link is single-use. The page does not validate the token up front - it carries the form
    // again and answers on submit, which is where the consumed token shows.
    await user.goto(setupPath);
    await user.locator("#set-password-new").fill(invitedPassword);
    await user.locator("#set-password-repeat").fill(invitedPassword);
    await user.getByRole("button", { name: "Passwort festlegen" }).click();
    await expect(user.getByText("Dieser Link ist nicht mehr gültig.")).toBeVisible();

    await signInSuccessfully(user, invited, invitedPassword);
    await user.goto("/spaces");
    // UserProvisionedEvent gives every provisioned account its personal space - for a local account
    // exactly as for one of an identity provider (ADR-0033, Entscheidung 8).
    await expect(user.getByText("Meine Dokumente").first()).toBeVisible();

    // The reason of the account is part of the person's own view of it (Entscheidung 11).
    await user.goto("/settings");
    await expect(user.getByText("Anlass des Kontos")).toBeVisible();
    await expect(user.getByText("Sachbearbeitung Meldewesen, Vertretung")).toBeVisible();
    // A local session offers the password change; an OIDC one would not.
    await expect(user.getByRole("link", { name: "Passwort ändern" })).toBeVisible();

    // The administration now sees the account as ACTIVE, without ever having seen the password.
    await openAccountList(admin);
    await expect(admin.getByRole("table", { name: "Konten" })).toContainText("Aktiv");

    await userContext.close();
    await adminContext.close();
  });

  test("ein Anfangspasswort erzwingt den Wechsel bei der ersten Anmeldung", async ({ browser }) => {
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);

    const address = uniqueAddress("thomas.kranz");
    await openAccountList(admin);
    await admin.getByRole("button", { name: "Konto anlegen" }).click();
    const dialog = admin.getByRole("dialog", { name: "Lokales Konto anlegen" });
    await dialog.locator("#user-form-email").fill(address);
    await dialog.locator("#user-form-display-name").fill("Thomas Kranz");
    await dialog.locator("#user-form-created-reason").fill("Hospitation Bauamt, drei Monate");
    await dialog
      .getByRole("radio", {
        name: "Anfangspasswort jetzt erzeugen (Wechsel bei der ersten Anmeldung)",
      })
      .click();
    await dialog.getByRole("button", { name: "Anlegen" }).click();

    // The generated password is shown exactly once, in a dialog that only its own button closes.
    const handover = admin.getByRole("dialog", { name: "Passwort übergeben" });
    await expect(handover).toBeVisible();
    const initialPassword = (await admin.getByTestId("generated-password-value").innerText()).trim();
    expect(initialPassword.length).toBeGreaterThan(0);
    await handover.getByRole("button", { name: "Übergeben, schließen" }).click();

    const userContext = await browser.newContext();
    const user = await userContext.newPage();
    await signInSuccessfully(user, address, initialPassword);

    // ADR-0033, Entscheidung 8: `pcr` sends every route but the password one to /account/password,
    // and the page names the occasion in plain words instead of just demanding a new password.
    await expect(user).toHaveURL(/\/account\/password/);
    await expect(user.getByText("Neues Passwort erforderlich")).toBeVisible();
    await expect(user.getByText("Bitte legen Sie Ihr erstes Passwort fest.")).toBeVisible();

    // Nothing else is reachable while the change is pending.
    await user.goto("/spaces");
    await expect(user).toHaveURL(/\/account\/password/);

    const chosen = "Kastanie-Nordwind-42";
    await user.locator("#change-password-current").fill(initialPassword);
    await user.locator("#change-password-new").fill(chosen);
    await user.locator("#change-password-repeat").fill(chosen);
    await user.getByRole("button", { name: "Passwort speichern" }).click();

    // change-password mints a new access token without `pcr` right away (Entscheidung 8), so the
    // person is not sent back to the sign-in form.
    await expect(user.getByText("Ihr Passwort wurde geändert.")).toBeVisible();
    await expect(user).not.toHaveURL(/\/account\/password/);
    await user.goto("/spaces");
    await expect(user.getByText("Meine Dokumente").first()).toBeVisible();

    await userContext.close();
    await adminContext.close();
  });
});
