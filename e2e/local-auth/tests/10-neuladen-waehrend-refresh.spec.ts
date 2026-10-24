import { expect, test, type Page, type Route } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  configureSmtp,
  createActiveAccount,
  enableLocalAccounts,
  signInSuccessfully,
  uniqueAddress,
} from "../../fixtures/localAuth";

const REFRESH_PATH = "/api/v1/auth/local/refresh";

/**
 * A reload in the middle of a refresh (ADR-0033, Entscheidung 7): the backend has committed the
 * rotation, but the browser discards the answer and its cookie, so the next page load presents the
 * rotated token again. Within the grace period, with the successor never used, that is a renewal,
 * not a replay - the session survives a double reload and the account's other session is untouched.
 */
test.describe("Neuladen während der Erneuerung der Sitzung", () => {
  const address = uniqueAddress("jonas.neuhaus");
  const password = "Kornfeld-Morgentau-4417";

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
      { email: address, displayName: "Jonas Neuhaus", reason: "Sachgebiet Liegenschaften" },
      password,
    );
    await context.close();
  });

  /**
   * Loads the app afresh and returns the backend's status for the refresh of the page load. With
   * `discardAnswer`, the request reaches the backend - the rotation is committed - but the browser
   * never sees the answer or its cookie, as with a reload that cut the refresh off.
   */
  async function reload(
    page: Page,
    { discardAnswer }: { discardAnswer: boolean },
  ): Promise<number> {
    let answered: (status: number) => void = () => {};
    const status = new Promise<number>((resolve) => {
      answered = resolve;
    });
    const handler = async (route: Route) => {
      const answer = await route.fetch();
      if (discardAnswer) {
        await route.abort("aborted");
      } else {
        await route.fulfill({ response: answer });
      }
      answered(answer.status());
    };
    await page.route(`**${REFRESH_PATH}`, handler);
    try {
      await page.goto("/");
      return await status;
    } finally {
      await page.unroute(`**${REFRESH_PATH}`, handler);
    }
  }

  test("Szenario 1: doppeltes Neuladen während der Erneuerung beendet keine Sitzung", async ({
    browser,
  }) => {
    const thisDevice = await (await browser.newContext()).newPage();
    const otherDevice = await (await browser.newContext()).newPage();
    await signInSuccessfully(thisDevice, address, password);
    await signInSuccessfully(otherDevice, address, password);

    // both refreshes succeed in the backend; only the browser never learns the new cookie
    expect(await reload(thisDevice, { discardAnswer: true })).toBe(200);
    expect(await reload(thisDevice, { discardAnswer: true })).toBe(200);

    expect(await reload(thisDevice, { discardAnswer: false })).toBe(200);
    await expect(thisDevice).not.toHaveURL(/\/login(?:$|[?#])/, { timeout: 20_000 });
    // the renewed session carries on with its own cookie
    expect(await reload(thisDevice, { discardAnswer: false })).toBe(200);

    expect(await reload(otherDevice, { discardAnswer: false })).toBe(200);
    await expect(otherDevice).not.toHaveURL(/\/login(?:$|[?#])/, { timeout: 20_000 });

    await thisDevice.context().close();
    await otherDevice.context().close();
  });
});
