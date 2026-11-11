import { expect, test } from "@playwright/test";
import { bootstrapAdmin, signInSuccessfully } from "../../fixtures/localAuth";

/**
 * The quick tour switched off (OPAA_FEATURES_QUICK_TOUR_ENABLED=false in local-auth.env): an
 * account that has never answered the hint sees neither the hint nor the menu entry. The tour
 * switched on runs in the quick-tour target (e2e/quick-tour/tests/).
 */
test("abgeschalteter Rundgang zeigt weder Hinweis noch Menüeintrag", async ({ page, request }) => {
  const config = await request.get("/api/v1/auth/config");
  expect(config.ok()).toBe(true);
  expect((await config.json()).features.quickTour).toBe(false);

  await signInSuccessfully(page, bootstrapAdmin.email, bootstrapAdmin.password, {
    route: "/login/system",
  });
  // Either the input or a hint wrongly shown, which hides the input while it is open: the
  // assertion below then names the hint instead of a missing input.
  const hint = page.getByRole("dialog", { name: "Neu hier?" });
  await expect(
    page.getByRole("textbox", { name: "Nachricht eingeben" }).or(hint),
  ).toBeVisible({ timeout: 20_000 });
  await expect(hint).toHaveCount(0);

  await page.getByRole("button", { name: "Profil und Einstellungen" }).click();
  await expect(page.getByRole("menuitem", { name: "Einstellungen" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Abmelden" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Rundgang starten" })).toHaveCount(0);
});
