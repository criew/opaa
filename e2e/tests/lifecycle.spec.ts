import { readFile } from "node:fs/promises";
import type { APIRequestContext, Page } from "@playwright/test";
import { expect, test } from "../fixtures/auth";
import { askQuestion } from "../fixtures/chat";
import { runIndexingViaApi } from "../fixtures/connectionProfiles";
import { queryDatabase } from "../fixtures/database";
import { apiAs } from "../fixtures/externalAccess";

/**
 * Lebenszyklus: die Wege, die eine Installation umziehen und Bestände übergeben oder
 * entfernen - Einstellungen übertragen, Eigentümerwechsel eines Space, Archivieren und Löschen
 * eines Space, Löschen einer Bibliothek über die Oberfläche. Vorbedingungen entstehen über die
 * API, geprüft wird der Weg durch die Oberfläche. Die Nachfolge nach einer Kontosperre braucht
 * lokale Konten und liegt deshalb im Ziel "local-auth" (local-auth/tests/12-nachfolge.spec.ts).
 */
const runId = Date.now();

async function confirmOverlay(page: Page): Promise<void> {
  const overlay = page
    .getByRole("dialog")
    .filter({ has: page.locator("#confirm-question") });
  await overlay.locator("#confirm-accept").click();
  await expect(overlay).toHaveCount(0);
}

async function idOf(api: APIRequestContext): Promise<string> {
  const me = await api.get("/api/v1/auth/me");
  expect(me.status(), await me.text()).toBe(200);
  return ((await me.json()) as { id: string }).id;
}

async function createSpace(api: APIRequestContext, name: string): Promise<string> {
  const created = await api.post("/api/v1/spaces", { data: { name } });
  expect(created.status(), await created.text()).toBe(201);
  return ((await created.json()) as { id: string }).id;
}

test.describe("Einstellungen übertragen", () => {
  test("Export, Vorschau mit bisherigem und neuem Wert, Import mit Eintrag im Revisionsprotokoll", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/admin/settings-transfer");
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Einstellungen exportieren" }).click(),
    ]);
    const original = JSON.parse(await readFile(await download.path(), "utf8")) as {
      sections: { branding: { claim: string | null } };
    };
    const previousClaim = original.sections.branding.claim;
    const claim = `Lebenszyklus ${runId}`;
    const changed = structuredClone(original);
    changed.sections.branding.claim = claim;

    const admin = await apiAs("dev-admin");
    const startedAt = new Date().toISOString();
    try {
      await page.getByLabel("Einstellungsdatei auswählen").setInputFiles({
        name: "einstellungen.json",
        mimeType: "application/json",
        buffer: Buffer.from(JSON.stringify(changed), "utf8"),
      });
      const changes = page.getByRole("table", { name: "Änderungen durch den Import" });
      await expect(page.getByText("„einstellungen.json“ ändert 1 Angabe:")).toBeVisible();
      const row = changes.getByRole("row").filter({ hasText: "Claim" });
      await expect(row.getByRole("cell").nth(0)).toHaveText("Branding");
      await expect(row.getByRole("cell").nth(3)).toHaveText(claim);

      // The preview changes nothing - only "Übernehmen" does.
      const beforeImport = await admin.get("/api/v1/system/settings/export");
      expect(
        ((await beforeImport.json()) as typeof original).sections.branding.claim,
      ).toBe(previousClaim);

      await page.getByRole("button", { name: "Übernehmen" }).click();
      await confirmOverlay(page);
      await expect(page.getByText("1 Angabe wurde übernommen.")).toBeVisible();

      const afterImport = await admin.get("/api/v1/system/settings/export");
      expect(((await afterImport.json()) as typeof original).sections.branding.claim).toBe(
        claim,
      );

      // The import's own entry names the changed section; the change of the value itself carries
      // the same operation id (docs/handbuch/betrieb/einstellungen-uebertragen.md, "Nachweis").
      const [imported] = queryDatabase(
        `select correlation_ref || '|' || after from audit_log
           where event_type = 'SETTINGS_IMPORTED' and recorded_at >= '${startedAt}'
           order by recorded_at desc limit 1`,
      );
      expect(imported, "kein SETTINGS_IMPORTED im Revisionsprotokoll").toBeDefined();
      const [correlation, after] = imported.split("|");
      expect(after).toContain("branding");
      const [related] = queryDatabase(
        `select count(*) from audit_log
           where correlation_ref = '${correlation}' and event_type <> 'SETTINGS_IMPORTED'`,
      );
      expect(Number(related)).toBeGreaterThanOrEqual(1);
    } finally {
      const restored = await admin.post("/api/v1/system/settings/import", { data: original });
      expect(restored.status(), await restored.text()).toBeLessThan(300);
      await admin.dispose();
    }
  });
});

/**
 * Ein Space von dev-admin wechselt an dev-user, der ihn dann archiviert. Der Chat, den dev-admin
 * vorher darin geführt hat, bleibt für ihn lesbar. Das Mitglied dev-outsider hat keinen eigenen
 * Chat darin und sieht den archivierten Space nicht mehr; der Eigentümer behält ihn, markiert.
 */
test.describe.serial("Space: Eigentümerwechsel, Archivieren, Löschen", () => {
  const spaceName = `E2E-Lebenszyklus-${runId}`;
  const question = `Was gilt im Lebenszyklus ${runId}?`;
  let spaceId = "";
  let chatPath = "";

  test.beforeAll(async () => {
    const admin = await apiAs("dev-admin");
    const user = await apiAs("dev-user");
    const outsider = await apiAs("dev-outsider");
    try {
      spaceId = await createSpace(admin, spaceName);
      for (const [api, role] of [
        [user, "ADMIN"],
        [outsider, "MEMBER"],
      ] as const) {
        const added = await admin.post(`/api/v1/spaces/${spaceId}/members`, {
          data: { subjectType: "USER", subjectId: await idOf(api), role },
        });
        expect(added.status(), await added.text()).toBe(201);
      }
    } finally {
      await admin.dispose();
      await user.dispose();
      await outsider.dispose();
    }
  });

  test("Eigentümerwechsel über die Einstellungen", async ({ authenticatedPage: page }) => {
    await page.goto(`/spaces/${spaceId}/chats/new`);
    await askQuestion(page, question);
    await page.waitForURL(new RegExp(`/spaces/${spaceId}/chats/(?!new$)[^/]+$`));
    chatPath = new URL(page.url()).pathname;

    await page.goto(`/spaces/${spaceId}/settings/members`);
    const row = page.getByTestId("space-member-row").filter({ hasText: "Dev User" });
    await row.getByRole("button", { name: "Weitere Aktionen für „Dev User“" }).click();
    await page.getByRole("menuitem", { name: "Zum Eigentümer machen" }).click();
    await confirmOverlay(page);
    await expect(page.getByText("Verantwortung an Dev User übertragen")).toBeVisible();
    await expect(row.getByRole("combobox")).toHaveText("Eigentümer");

    // The former owner stays an administrator, but the danger zone belongs to the owner alone.
    await page.goto(`/spaces/${spaceId}/settings/general`);
    await expect(page.getByLabel("Name des Space")).toHaveValue(spaceName);
    await expect(page.getByText("Gefahrenbereich")).toHaveCount(0);
  });

  test("der neue Eigentümer archiviert, der Chat bleibt für seinen Autor lesbar", async ({
    authenticatedPage: adminPage,
    regularUserPage: page,
    outsiderPage,
  }) => {
    expect(chatPath, "der erste Schritt hat keinen Chat angelegt").not.toBe("");
    await outsiderPage.goto("/spaces");
    await expect(outsiderPage.getByRole("link", { name: new RegExp(spaceName) })).not.toHaveCount(
      0,
    );

    await page.goto(`/spaces/${spaceId}/settings/general`);
    await expect(page.getByText("Gefahrenbereich")).toBeVisible();
    await page.getByRole("button", { name: "Space archivieren" }).click();
    await confirmOverlay(page);
    await expect(page.getByText("Space archiviert", { exact: true })).toBeVisible();
    await expect(
      page.getByText("Dieser Space ist archiviert und nimmt keinen neuen Inhalt mehr an."),
    ).toBeVisible();

    // A member without a chat of their own no longer finds the space anywhere.
    await outsiderPage.reload();
    await expect(outsiderPage.getByRole("heading", { level: 1, name: "Spaces" })).toBeVisible();
    await expect(outsiderPage.getByRole("link", { name: new RegExp(spaceName) })).toHaveCount(0);

    // The owner keeps it in the overview, marked; the sidebar row says "archiviert" in lower case.
    await page.goto("/spaces");
    await expect(
      page.getByRole("link", { name: new RegExp(`^${spaceName} Archiviert`) }),
    ).toBeVisible();

    // The author of a chat still reads it.
    await adminPage.goto(chatPath);
    await expect(adminPage.getByText(question, { exact: true })).toBeVisible();
  });

  test("Löschen bleibt gesperrt, solange Chats darin liegen; ein Space ohne Chats wird gelöscht", async ({
    regularUserPage: page,
  }) => {
    await page.goto(`/spaces/${spaceId}/settings/general`);
    await page.getByRole("button", { name: "Space löschen" }).click();
    await confirmOverlay(page);
    await expect(page.getByRole("alert").filter({ hasText: "Archivieren" })).toBeVisible();

    const user = await apiAs("dev-user");
    try {
      const emptyName = `E2E-Leer-${runId}`;
      const emptyId = await createSpace(user, emptyName);
      await page.goto(`/spaces/${emptyId}/settings/general`);
      await page.getByRole("button", { name: "Space löschen" }).click();
      await confirmOverlay(page);
      await expect(page).toHaveURL(/\/spaces$/);
      await expect(page.getByRole("main").getByText(emptyName, { exact: true })).toHaveCount(0);
      expect((await user.get(`/api/v1/spaces/${emptyId}`)).status()).toBe(404);
    } finally {
      await user.dispose();
    }
  });
});

/**
 * Eine Konnektor-Bibliothek nimmt beim Löschen ihre Dokumente mit (ADR-0018, Entscheidung 5) -
 * anders als eine Upload-Bibliothek, die erst leer sein muss. Danach findet die Suche das Dokument
 * nicht mehr, und sein Original ist nicht mehr abrufbar.
 */
test("Bibliothek über die Oberfläche löschen: Dokumente und Original sind weg", async ({
  authenticatedPage: page,
}) => {
  const name = `E2E-Loeschpfad-${runId}`;
  const admin = await apiAs("dev-admin");
  try {
    const created = await admin.post("/api/v1/libraries", {
      data: { name, sourceType: "HTTP_DIRECTORY", sourceUrl: "http://rss-feed/webverzeichnis/" },
    });
    expect(created.status(), await created.text()).toBe(201);
    const libraryId = ((await created.json()) as { id: string }).id;
    expect((await runIndexingViaApi("dev-admin", libraryId)).status).toBe("COMPLETED");

    const documents = await admin.get(`/api/v1/libraries/${libraryId}/documents`);
    const [document] = ((await documents.json()) as { items: Array<{ id: string }> }).items;
    expect(document, "der Lauf hat kein Dokument angelegt").toBeDefined();
    expect((await admin.get(`/api/v1/documents/${document.id}/content`)).status()).toBe(200);
    const hitsOf = async (body: Record<string, unknown>) => {
      const response = await admin.post("/api/v1/search", { data: body });
      expect(response.status(), await response.text()).toBe(200);
      return ((await response.json()) as { hits: Array<{ documentId: string }> }).hits;
    };
    const searchFor = { question: "Merkblatt Webverzeichnis" };
    expect(
      (await hitsOf({ ...searchFor, libraryIds: [libraryId] })).map((hit) => hit.documentId),
    ).toContain(document.id);

    await page.goto(`/libraries/${libraryId}`);
    await expect(page.getByRole("heading", { name })).toBeVisible();
    await page.getByRole("button", { name: `Weitere Aktionen für „${name}“` }).click();
    await page.getByRole("menuitem", { name: "Löschen" }).click();
    await confirmOverlay(page);
    await expect(page).not.toHaveURL(new RegExp(`/libraries/${libraryId}`));

    expect((await admin.get(`/api/v1/libraries/${libraryId}`)).status()).toBe(404);
    expect((await admin.get(`/api/v1/documents/${document.id}/content`)).status()).toBe(404);
    expect((await hitsOf(searchFor)).map((hit) => hit.documentId)).not.toContain(document.id);
  } finally {
    await admin.dispose();
  }
});
