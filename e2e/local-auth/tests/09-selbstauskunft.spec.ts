import { expect, test, type Browser, type Page } from "@playwright/test";
import { askQuestion, clearSearchScope, startFreshChat } from "../../fixtures/chat";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import {
  COMPLETED_MESSAGE,
  DOWNLOAD_BUTTON,
  PERSONAL_ONLY_NOTICE,
  captureDownload,
  expectExportStructure,
  exportedMessages,
  jsonEntry,
  MY_DATA_PATH,
  waitForMyDataStatus,
} from "../../fixtures/selfDisclosure";

/**
 * Self-disclosure behind the password confirmation. The ticket needs a recent sign-in, and a token
 * renewed over the refresh cookie carries none - so each test loads the page afresh after signing
 * in, and the download asks for the password.
 */
test.describe("Selbstauskunft nach bestätigter Anmeldung", () => {
  const address = uniqueAddress("ida.brenner");
  const password = "Mohnblume-Abendrot-2994";
  const displayName = "Ida Brenner";
  const question = `Selbstauskunft-Probe ${Date.now().toString(36)}: Wie lautet die Frist?`;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    await acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await configureSmtp(admin);
    await enableLocalAccounts(admin);
    await createActiveAccount(
      admin,
      browser,
      { email: address, displayName, reason: "Sachgebiet Grundsatzfragen" },
      password,
    );
    await context.close();
  });

  /**
   * Loads „Meine Daten“ afresh: the page load restores the session over the refresh cookie, whose
   * token carries no time of sign-in, so the ticket asks for a confirmation. Waits for the refresh
   * to answer - a navigation that cut it off would send the rotated-away cookie again, which the
   * backend renews only within its grace period and otherwise treats as reuse.
   */
  async function loadMyDataWithoutRecentLogin(page: Page): Promise<void> {
    const [refreshed] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/local/refresh")),
      page.goto(MY_DATA_PATH),
    ]);
    expect(refreshed.status()).toBe(200);
    await waitForMyDataStatus(page);
  }

  async function signedInSessionWithoutRecentLogin(browser: Browser): Promise<Page> {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, address, password);
    await loadMyDataWithoutRecentLogin(page);
    return page;
  }

  /** Every request the page sends to the download address of the self-disclosure. */
  function downloadRequests(page: Page): string[] {
    const seen: string[] = [];
    page.on("request", (request) => {
      if (request.url().includes("/api/v1/me/self-disclosure/download")) seen.push(request.url());
    });
    return seen;
  }

  test("Szenario 2: ohne bestätigte Anmeldung gibt es keinen Download", async ({ browser }) => {
    const page = await signedInSessionWithoutRecentLogin(browser);
    const requests = downloadRequests(page);
    const downloads: string[] = [];
    page.on("download", (download) => downloads.push(download.suggestedFilename()));

    // cancelled
    await page.getByRole("button", { name: DOWNLOAD_BUTTON }).click();
    const dialog = page.getByRole("dialog", { name: "Anmeldung bestätigen" });
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByText("Bevor Sie Ihre Daten herunterladen, bestätigen Sie bitte Ihre Anmeldung."),
    ).toBeVisible();
    await dialog.getByRole("button", { name: "Abbrechen" }).click();
    await expect(dialog).toBeHidden();

    // wrong password
    await page.getByRole("button", { name: DOWNLOAD_BUTTON }).click();
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Ihr Passwort").fill("Falsches-Passwort-2994");
    await dialog.getByRole("button", { name: "Bestätigen" }).click();
    await expect(
      dialog.getByText("Die Anmeldung ließ sich nicht bestätigen. Bitte prüfen Sie Ihr Passwort."),
    ).toBeVisible();
    await expect(dialog.getByLabel("Ihr Passwort")).toHaveValue("");
    await dialog.getByRole("button", { name: "Abbrechen" }).click();
    await expect(dialog).toBeHidden();

    // nothing reached the download address, nothing was saved, and the day is untouched
    await expect(page.getByText(COMPLETED_MESSAGE)).toHaveCount(0);
    expect(requests).toEqual([]);
    expect(downloads).toEqual([]);
    await loadMyDataWithoutRecentLogin(page);
    await expect(page.getByText(/Zuletzt vollständig heruntergeladen/)).toHaveCount(0);
    await page.context().close();
  });

  test("Szenario 1: nach Bestätigung mit dem Passwort enthält das ZIP Konto und den eigenen Chat", async ({
    browser,
  }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, address, password);
    await startFreshChat(page);
    await clearSearchScope(page);
    const [answered] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.request().method() === "POST" && response.url().endsWith("/api/v1/query"),
      ),
      askQuestion(page, question),
    ]);
    expect(answered.status()).toBe(200);
    await expect(page.getByText("Denkt nach …")).toHaveCount(0);

    await loadMyDataWithoutRecentLogin(page);
    await expect(page.getByText(PERSONAL_ONLY_NOTICE)).toBeVisible();
    await page.getByRole("button", { name: DOWNLOAD_BUTTON }).click();
    const dialog = page.getByRole("dialog", { name: "Anmeldung bestätigen" });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Ihr Passwort").fill(password);
    const download = await captureDownload(page, () =>
      dialog.getByRole("button", { name: "Bestätigen" }).click(),
    );
    await expect(page.getByText(COMPLETED_MESSAGE)).toBeVisible({ timeout: 20_000 });

    expectExportStructure(download);
    const account = JSON.stringify(jsonEntry(download.entries, "accounts.json"));
    expect(account).toContain(address);
    expect(account).toContain(displayName);
    const own = exportedMessages(download.entries).find((message) => message.content === question);
    expect(own, "der vorher angelegte Chat fehlt in chats.json").toBeDefined();
    expect(own!.role).toBe("Frage");
    await context.close();
  });
});
