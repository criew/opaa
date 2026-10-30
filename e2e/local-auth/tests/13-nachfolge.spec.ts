import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  createActiveAccount,
  enableLocalAccounts,
  openAccountList,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";

/**
 * Succession after an account lock: once the owner's account is locked, their space is listed
 * under „Ohne Zuständigkeit" (ADR-0036, Entscheidung 6), and the system administration names a
 * successor with preview and confirmation, who owns the space afterwards.
 *
 * Lives in this target because only local accounts can be locked through the interface; the dev
 * accounts of the main suite have no account status.
 */
test.describe("Nachfolge für den Space eines gesperrten Kontos", () => {
  const leaving = {
    email: uniqueAddress("vera.vogt"),
    displayName: "Vera Vogt",
    reason: "Referat Bauordnung, scheidet aus",
  };
  const successor = {
    email: uniqueAddress("nils.nachtigall"),
    displayName: "Nils Nachtigall",
    reason: "Referat Bauordnung, übernimmt",
  };
  const password = "Lindenblatt-Abendrot-17";
  const spaceName = `Bauordnung ${Date.now().toString(36)}`;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await enableLocalAccounts(admin);
    await createActiveAccount(admin, browser, leaving, password);
    await createActiveAccount(admin, browser, successor, password);
    await context.close();
  });

  test("gesperrte Eigentümerin, Nachfolge mit Vorschau, danach Eigentum bei der Nachfolge", async ({
    browser,
  }) => {
    const leavingContext = await browser.newContext();
    const owner = await leavingContext.newPage();
    await signInSuccessfully(owner, leaving.email, password);
    await owner.goto("/spaces/new");
    await owner.getByLabel("Name", { exact: true }).fill(spaceName);
    for (let step = 0; step < 3; step++) {
      await owner.getByRole("button", { name: "Weiter", exact: true }).click();
    }
    await Promise.all([
      owner.waitForURL(/\/spaces\/(?!new$)[^/]+$/),
      owner.getByRole("button", { name: "Space anlegen" }).click(),
    ]);
    await leavingContext.close();

    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password);
    await openAccountList(admin);
    await admin
      .getByRole("button", { name: `Weitere Aktionen für „${leaving.displayName}“` })
      .click();
    await admin.getByRole("menuitem", { name: "Sperren" }).click();
    await expect(admin.getByText(`„${leaving.displayName}“ wurde gesperrt.`)).toBeVisible();

    // The entry is derived on every call, so no detection run has to pass; the timeout only covers
    // the page's first load.
    await admin.goto("/admin/succession/open");
    const entry = admin.getByRole("listitem", { name: spaceName });
    await expect(entry).toBeVisible({ timeout: 20_000 });
    await expect(entry).toContainText("ist nicht mehr aktiv");
    await entry.getByRole("button", { name: "Nachfolge bestimmen" }).click();

    const dialog = admin.getByRole("dialog", { name: "Nachfolge bestimmen" });
    // Matched by address, not by name: a repeated local run leaves earlier accounts of the same
    // name behind, and the option carries the address next to it.
    for (const [field, person] of [
      ["Bisheriges Konto", leaving],
      ["Nachfolge", successor],
    ] as const) {
      const picker = dialog.getByRole("combobox", { name: field });
      await picker.fill(person.displayName);
      await admin.getByRole("option", { name: person.email }).click();
    }
    await dialog.getByRole("button", { name: "Vorschau erstellen" }).click();
    await expect(dialog.getByText("Vorschau", { exact: true })).toBeVisible();
    await dialog.getByRole("button", { name: "Übertragung bestätigen" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(entry).toHaveCount(0);
    await adminContext.close();

    const successorContext = await browser.newContext();
    const heir = await successorContext.newPage();
    await signInSuccessfully(heir, successor.email, password);
    await heir.goto("/spaces");
    await heir.getByRole("link", { name: new RegExp(`^${spaceName}`) }).first().click();
    await heir.getByRole("link", { name: `Einstellungen von „${spaceName}“` }).first().click();
    await heir.getByRole("tab", { name: "Mitglieder" }).click();
    const ownRow = heir
      .getByTestId("space-member-row")
      .filter({ hasText: successor.displayName });
    await expect(ownRow.getByRole("combobox")).toHaveText("Eigentümer");
    await successorContext.close();
  });
});
