import { expect, test } from "@playwright/test";
import {
  acceptConfirmDialogs,
  bootstrapAdmin,
  enableLocalAccounts,
  openAccountList,
  openLocalAuthSettings,
  signInSuccessfully,
} from "../../fixtures/localAuth";

/**
 * Scenario 8 of #1543: the local sign-in needs no new origin in the Content Security Policy.
 *
 * e2e/tests/csp.spec.ts proves this for the app shell of the regular stack (#707). What it cannot
 * cover is the claim ADR-0033 makes for the local issuer: since the backend mints the tokens itself,
 * every call of the sign-in, the self-service and the administration goes to the SPA's own origin -
 * unlike the OIDC way, which needs OPAA_CSP_CONNECT_SRC_EXTRA for the provider (see
 * e2e/demo-smoke.env). A violation here would mean the shipped nginx policy has to be widened for a
 * house that uses local accounts, which is exactly what must not happen quietly.
 */
test.describe("Content Security Policy der lokalen Anmeldung", () => {
  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext();
    const admin = await context.newPage();
    await acceptConfirmDialogs(admin);
    await signInSuccessfully(admin, bootstrapAdmin.email, bootstrapAdmin.password, {
      route: "/login/system",
    });
    await enableLocalAccounts(admin);
    await context.close();
  });

  test("keine CSP-Verstöße auf Anmeldung, Selbstbedienung und Verwaltung", async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await acceptConfirmDialogs(page);
    const violations: string[] = [];
    page.on("console", (message) => {
      if (message.text().includes("Content Security Policy")) {
        violations.push(`${page.url()}: ${message.text()}`);
      }
    });

    // The pages without a session first - they are served by the same nginx and load the same bundle.
    await page.goto("/login");
    await expect(page.getByLabel("E-Mail-Adresse")).toBeVisible();
    await page.goto("/forgot-password");
    await expect(page.getByRole("heading", { level: 1, name: "Passwort vergessen" })).toBeVisible();
    await page.goto("/set-password?token=kein-echter-token");
    await expect(page.getByRole("heading", { level: 1, name: "Passwort festlegen" })).toBeVisible();

    // Then a real session and the administration, where the list and the settings card load.
    await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password);
    await openAccountList(page);
    await openLocalAuthSettings(page);
    await page.goto("/admin/mail/server");
    await expect(page.getByRole("switch", { name: "Versand aktiv" })).toBeVisible();

    expect(violations, "CSP-Verstöße in der Browser-Konsole").toEqual([]);
    await context.close();
  });
});
