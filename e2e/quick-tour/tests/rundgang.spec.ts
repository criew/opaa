import { expect, test, type Browser, type BrowserContext, type Page } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";
import { expectNoSeriousA11yViolations } from "../../fixtures/a11y";

/**
 * The quick tour for new users against a stack with the tour switched on: the hint "Neu hier?" on
 * the first visit, its answer kept per account across browsers, and the tour from the profile
 * menu. The stack is the local-auth one, so "the same account in a second browser" is a real
 * second sign-in, not a second tab of one session.
 *
 * Serial on purpose: the first test answers the bootstrap administrator's hint, the second relies
 * on that, and the marker cannot be set back.
 */
test.describe.configure({ mode: "serial" });

const MARKER_PATH = "/api/v1/me/quick-tour-hint";

function hint(page: Page) {
  return page.getByRole("dialog", { name: "Neu hier?" });
}

function chatInput(page: Page) {
  return page.getByRole("textbox", { name: "Nachricht eingeben" });
}

/** The card of the running tour; its accessible name is the title of the current station. */
function tourCard(page: Page, title?: string) {
  return title
    ? page.getByRole("dialog", { name: title })
    : page.getByRole("dialog").filter({ has: page.getByText(/^\d+ von \d+$/) });
}

/**
 * Waits until the empty chat has settled - either its input or the hint, which hides the input
 * from the accessibility tree while it is open - and then asserts that no hint is shown.
 */
async function expectChatWithoutHint(page: Page): Promise<void> {
  await expect(chatInput(page).or(hint(page))).toBeVisible({ timeout: 20_000 });
  await expect(hint(page)).toHaveCount(0);
}

async function signInAsAdmin(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
    route: "/login/system",
  });
  return { context, page };
}

/** More stations than any tour has; ends a walk that never reaches "Fertig". */
const MAX_STATIONS = 30;

async function readAnnouncedSteps(page: Page): Promise<number> {
  const text = await hint(page).getByText(/^Ein Rundgang in \d+ Schritten/).innerText();
  return Number(/in (\d+) Schritten/.exec(text)?.[1]);
}

/**
 * The number of steps the hint announces ("Ein Rundgang in n Schritten ..."), read until two
 * readings agree: conditional stations count only once their element is on screen, and the
 * page may still be adding one (the paper clip arrives with the space).
 */
async function announcedSteps(page: Page): Promise<number> {
  let current = -1;
  await expect
    .poll(
      async () => {
        const previous = current;
        current = await readAnnouncedSteps(page);
        return current === previous;
      },
      { intervals: [250] },
    )
    .toBe(true);
  return current;
}

/**
 * Pages through the open tour with "Weiter" up to "Fertig" and returns the titles of all stations
 * and the final m of "n von m". The total is read anew at every station, because a conditional
 * station may join while the page is still settling.
 */
async function walkThroughTour(page: Page): Promise<{ titles: string[]; total: number }> {
  const counter = tourCard(page).getByText(/^\d+ von \d+$/);
  const titles: string[] = [];
  for (let step = 1; step <= MAX_STATIONS; step++) {
    await expect(counter).toHaveText(new RegExp(`^${step} von \\d+$`));
    const total = Number(/von (\d+)/.exec(await counter.innerText())?.[1]);
    titles.push((await tourCard(page).getByRole("heading").innerText()).trim());
    const isLast = step === total;
    const primary = tourCard(page).getByRole("button", { name: isLast ? "Fertig" : "Weiter" });
    await expect(primary).toBeFocused();
    await primary.click();
    if (isLast) {
      await expect(tourCard(page)).toHaveCount(0);
      return { titles, total };
    }
  }
  throw new Error(`The tour did not end within ${MAX_STATIONS} stations`);
}

async function startTourFromProfileMenu(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Profil und Einstellungen" }).click();
  await page.getByRole("menuitem", { name: "Rundgang starten" }).click();
}

test.describe("Rundgang für neue Nutzer", () => {
  test("der erste Besuch zeigt den Hinweis, „Nicht jetzt“ erledigt ihn für das ganze Konto", async ({
    browser,
  }) => {
    const first = await signInAsAdmin(browser);
    await expect(hint(first.page)).toBeVisible({ timeout: 20_000 });
    await expect(first.page.getByRole("button", { name: "Rundgang starten" })).toBeFocused();
    expect(await announcedSteps(first.page)).toBeGreaterThan(1);
    await expectNoSeriousA11yViolations(first.page, "Hinweis „Neu hier?“ über dem leeren Chat");

    // Only an answer closes the hint, a click beside it does not.
    await first.page.mouse.click(5, 5);
    await expect(hint(first.page)).toBeVisible();

    const [marked] = await Promise.all([
      first.page.waitForResponse(
        (response) =>
          response.url().endsWith(MARKER_PATH) && response.request().method() === "PUT",
      ),
      hint(first.page).getByRole("button", { name: "Nicht jetzt" }).click(),
    ]);
    expect(marked.status()).toBe(204);
    await expect(hint(first.page)).toHaveCount(0);
    await expect(chatInput(first.page)).toBeFocused();

    // A second browser of the same account: the answer is kept on the server, not in the browser.
    const second = await signInAsAdmin(browser);
    await expectChatWithoutHint(second.page);
    await second.page.reload();
    await expectChatWithoutHint(second.page);

    await second.context.close();
    await first.context.close();
  });

  test("der Rundgang startet über das Profil, auch außerhalb eines Chats", async ({ browser }) => {
    const { context, page } = await signInAsAdmin(browser);
    await expect(chatInput(page)).toBeVisible({ timeout: 20_000 });

    await startTourFromProfileMenu(page);
    await expect(tourCard(page, "Fragen stellen")).toBeVisible();
    await expect(page.getByRole("button", { name: "Weiter" })).toBeFocused();
    await expectNoSeriousA11yViolations(page, "Rundgang, Station „Fragen stellen“");
    await page.keyboard.press("ArrowRight");
    await expect(tourCard(page, "Antworten prüfen")).toBeVisible();
    await page.keyboard.press("ArrowLeft");
    await expect(tourCard(page, "Fragen stellen")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(tourCard(page)).toHaveCount(0);
    await expect(chatInput(page)).toBeFocused();

    // Away from the chat the menu opens a new chat first, because the stations explain the chat.
    await page
      .getByRole("navigation", { name: "Globale Navigation" })
      .getByRole("link", { name: "Katalog" })
      .click();
    await expect(page).toHaveURL(/\/catalog/);
    await startTourFromProfileMenu(page);
    await expect(tourCard(page, "Fragen stellen")).toBeVisible();
    await expect(page).toHaveURL(/\/spaces\/[^/]+\/chats\/[^/]+$/);

    const { titles, total } = await walkThroughTour(page);
    expect(titles).toHaveLength(total);
    expect(titles[0]).toBe("Fragen stellen");
    expect(titles).toContain("Systemverwaltung");
    expect(titles.at(-1)).toBe("Rundgang wiederholen");
    await expect(chatInput(page)).toBeFocused();

    await context.close();
  });

  test("„Rundgang starten“ im Hinweis führt eine Person ohne Systemverwaltung durch alle angekündigten Schritte", async ({
    browser,
  }) => {
    const address = uniqueAddress("frieda.lang");
    const password = "Lindenblatt-Morgentau-4471";
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await configureSmtp(admin);
    await enableLocalAccounts(admin);
    await createActiveAccount(
      admin,
      browser,
      { email: address, displayName: "Frieda Lang", reason: "Sachgebiet Bürgerservice" },
      password,
    );
    await adminContext.close();

    const context = await browser.newContext();
    const page = await context.newPage();
    await signInSuccessfully(page, address, password);
    await expect(hint(page)).toBeVisible({ timeout: 20_000 });
    const announced = await announcedSteps(page);

    await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().endsWith(MARKER_PATH) && response.request().method() === "PUT",
      ),
      hint(page).getByRole("button", { name: "Rundgang starten" }).click(),
    ]);
    await expect(tourCard(page, "Fragen stellen")).toBeVisible();
    const { titles, total } = await walkThroughTour(page);
    expect(titles).toHaveLength(total);
    expect(total).toBe(announced);
    expect(titles).not.toContain("Systemverwaltung");
    await expect(chatInput(page)).toBeFocused();

    // Starting the tour answered the hint as well.
    await page.reload();
    await expectChatWithoutHint(page);

    await context.close();
  });
});
