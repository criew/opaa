import { expect, test, type Page } from "@playwright/test";
import {
  bootstrapAdmin,
  enableLocalAccounts,
  openAccountList,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { apiSignedInAs, changeRole, createLocalAccount } from "../../fixtures/revision";

/**
 * Holding the deletion of a locked account's chats in the Vier-Augen-Prinzip: one system
 * administrator requests with a purpose of the closed list and a reason, the request alone holds
 * nothing and its author cannot approve it, a second administrator approves, and the hold shows
 * its end and the days left. The end of a hold itself is a nightly run and is covered by
 * LockedAccountChatCleanupIntegrationTest.
 *
 * Lives in this target because only local accounts can be locked by the administration.
 */
test.describe("Anhalten der Chat-Löschung im Vier-Augen-Prinzip", () => {
  const second = {
    email: uniqueAddress("sven.zweitfreigabe"),
    displayName: "Sven Zweitfreigabe",
  };
  const locked = {
    email: uniqueAddress("lena.gesperrt"),
    displayName: `Lena Gesperrt ${Date.now().toString(36)}`,
  };
  const password = "Lindenblatt-Abendrot-17";
  const reason = "Anordnung der Staatsanwaltschaft, Az. 12 Js 345/26";

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await enableLocalAccounts(page);
    await context.close();

    const admin = await apiSignedInAs(bootstrapAdmin.email, bootstrapAdmin.password);
    const secondId = await createLocalAccount(admin, second, password);
    await changeRole(admin, secondId, "SYSTEM_ADMIN");
    const lockedId = await createLocalAccount(admin, locked, password);
    const lock = await admin.post(`/api/v1/admin/local-users/${lockedId}/lock`, { data: {} });
    expect(lock.status(), await lock.text()).toBe(200);
    await admin.dispose();
  });

  async function openChatCleanup(page: Page) {
    await openAccountList(page);
    await page
      .getByRole("button", { name: `Weitere Aktionen für „${locked.displayName}“` })
      .click();
    await page.getByRole("menuitem", { name: "Bearbeiten" }).click();
    const section = page.getByRole("region", { name: "Chats nach der Sperre" });
    await expect(section).toBeVisible();
    return section;
  }

  test("Antrag mit Zweck und Begründung, Freigabe nur durch eine zweite Person", async ({
    browser,
  }) => {
    const firstContext = await browser.newContext();
    const first = await firstContext.newPage();
    await signInSuccessfully(first, bootstrapAdmin.email, bootstrapAdmin.password);
    const requested = await openChatCleanup(first);
    await expect(requested).toContainText("frühestens am");
    await requested.getByRole("button", { name: "Anhaltung beantragen" }).click();
    await requested.getByRole("combobox", { name: "Zweck" }).click();
    await expect(first.getByRole("option")).toHaveCount(3);
    await first.getByRole("option", { name: "Behördliche oder gerichtliche Anordnung" }).click();
    await requested.getByRole("textbox", { name: /Begründung/ }).fill(reason);
    await requested.getByRole("button", { name: "Antrag stellen" }).click();

    const pending = requested.getByTestId("chat-cleanup-pending");
    await expect(pending).toContainText("Anhaltung beantragt, wartet auf Freigabe");
    await expect(pending).toContainText("Freigeben kann nur eine zweite Person");
    await expect(pending.getByRole("button", { name: "Freigeben" })).toHaveCount(0);
    // the request alone holds nothing: the deletion date still stands
    await expect(requested).toContainText("frühestens am");
    await firstContext.close();

    const secondContext = await browser.newContext();
    const approver = await secondContext.newPage();
    await signInSuccessfully(approver, second.email, password);
    const approving = await openChatCleanup(approver);
    await expect(approving.getByTestId("chat-cleanup-pending")).toContainText(reason);
    await approving.getByRole("button", { name: "Freigeben" }).click();

    const hold = approving.getByTestId("chat-cleanup-hold");
    await expect(hold).toContainText(/noch 1(79|80) Tage/);
    await expect(hold).toContainText("Zweck: Behördliche oder gerichtliche Anordnung");
    await expect(hold).toContainText(`Begründung: ${reason}`);
    await expect(approving).toContainText("ist für dieses Konto angehalten");
    await expect(approving.getByRole("button", { name: "Verlängerung beantragen" })).toBeVisible();

    await approving.getByRole("button", { name: "Löschung fortsetzen" }).click();
    await expect(approving).toContainText("frühestens am");
    await secondContext.close();
  });
});
