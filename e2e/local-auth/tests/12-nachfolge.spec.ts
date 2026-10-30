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
 * Nachfolge nach einer Kontosperre: Wird das Konto der Eigentümerin gesperrt, steht ihr
 * Space unter „Ohne Zuständigkeit" (ADR-0036, Entscheidung 6), und die Systemverwaltung bestimmt
 * mit Vorschau und Bestätigung eine Nachfolge, die danach Eigentümerin ist.
 *
 * Lebt in diesem Ziel, weil nur lokale Konten sich über die Oberfläche sperren lassen; die
 * dev-Konten der Hauptsuite haben keinen Kontostatus.
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

    // The entry is derived on every call, so it is there at once - no detection run to wait for.
    await admin.goto("/admin/succession/open");
    const entry = admin.getByRole("listitem", { name: spaceName });
    await expect(entry).toBeVisible({ timeout: 20_000 });
    await expect(entry).toContainText("ist nicht mehr aktiv");
    await entry.getByRole("button", { name: "Nachfolge bestimmen" }).click();

    const dialog = admin.getByRole("dialog", { name: "Nachfolge bestimmen" });
    for (const [field, person] of [
      ["Bisheriges Konto", leaving.displayName],
      ["Nachfolge", successor.displayName],
    ]) {
      const picker = dialog.getByRole("combobox", { name: field });
      await picker.fill(person);
      await admin.getByRole("option", { name: new RegExp(person) }).first().click();
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
