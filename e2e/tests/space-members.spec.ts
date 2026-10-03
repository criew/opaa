import { expect, test } from "../fixtures/auth";
import { expectNoSeriousA11yViolations } from "../fixtures/a11y";
import { apiAs } from "../fixtures/externalAccess";
import { createReleasedGroupViaApi } from "../fixtures/promptLibraries";

const runId = Date.now();
const GROUP_NAME = `E2E-Meldewesen-${runId}`;
const SPACE_NAME = `E2E-Mitglieder-${runId}`;
let spacePath = "";

/**
 * #2131: Der Space-Assistent nimmt Personen und Gruppen über dasselbe Suchfeld auf wie die
 * Space-Einstellungen, ohne Sichtbarkeit und mit den Bereinigungsfristen der Installation. Die
 * Gruppe ist Vorbedingung, nicht Gegenstand, und entsteht deshalb über die API.
 */
test.describe.serial("Space-Anlage und Mitglieder (#2131)", () => {
  test("Assistent: Person und Gruppe aufnehmen, Zusammenfassung prüfen, Space anlegen", async ({
    authenticatedPage: page,
  }) => {
    await createReleasedGroupViaApi(GROUP_NAME, "Dev Outsider");

    await page.goto("/spaces/new");
    await expect(page.getByText("Sichtbarkeit")).toHaveCount(0);
    await page.getByLabel("Name", { exact: true }).fill(SPACE_NAME);
    await page
      .getByRole("switch", {
        name: /^Inaktive Chats nach \d+ Tagen archivieren/,
      })
      .check();
    await page.getByRole("button", { name: "Weiter", exact: true }).click();

    // One field for persons and groups. Not getByLabel: with the listbox open, its
    // aria-labelledby also points at the field.
    const search = page.getByRole("combobox", {
      name: "Person oder Gruppe suchen",
    });
    await search.click();
    await search.fill("Dev User");
    await page
      .getByRole("option", { name: /Dev User/ })
      .first()
      .click();
    await page.getByRole("button", { name: "Vormerken" }).click();

    await search.click();
    await search.fill(GROUP_NAME);
    await page.getByRole("option", { name: `${GROUP_NAME} · Gruppe` }).click();
    await page
      .getByRole("combobox", { name: "Rolle des neuen Mitglieds" })
      .click();
    await page.getByRole("option", { name: "Kurator" }).click();
    await page.getByRole("button", { name: "Vormerken" }).click();
    await expect(page.getByText("2 Mitglieder vorgemerkt")).toBeVisible();

    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    await page.getByRole("button", { name: "Weiter", exact: true }).click();
    await expect(
      page.getByText(`${GROUP_NAME} · Gruppe · Kurator`),
    ).toBeVisible();
    await expect(
      page.getByText(
        /^Inaktive Chats werden nach \d+ Tagen archiviert und nach weiteren \d+ Tagen gelöscht\.$/,
      ),
    ).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Space-Assistent: Zusammenfassung",
    );

    await Promise.all([
      page.waitForURL(/\/spaces\/(?!new$)[^/]+$/),
      page.getByRole("button", { name: "Space anlegen" }).click(),
    ]);
    await expect(page.getByRole("heading", { name: SPACE_NAME })).toBeVisible();
    spacePath = new URL(page.url()).pathname;
  });

  test("Einstellungen: Mitglieder in einer Zeile, Gruppe aus dem Assistenten in der Liste", async ({
    authenticatedPage: page,
  }) => {
    expect(spacePath, "der erste Schritt hat keinen Space angelegt").not.toBe(
      "",
    );
    await page.goto(`${spacePath}/settings/members`);

    await expect(
      page.getByRole("heading", { level: 2, name: "Mitglied hinzufügen" }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Mitglieder", exact: true }),
    ).toHaveCount(0);
    await expect(page.getByText(GROUP_NAME, { exact: true })).toBeVisible();

    const search = page.getByRole("combobox", {
      name: "Person oder Gruppe suchen",
    });
    await search.click();
    await search.fill("Dev");
    await expect(page.getByRole("option").first()).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Space-Einstellungen: Mitglied hinzufügen",
    );
  });

  /**
   * #2205: Ein Rollenwechsel meldet sich als Popup in einer Live-Region, ohne dass die Liste neu
   * lädt; die Herleitung nennt Rolle und Herkunft in einem Satz und schließt mit „Schließen".
   */
  test("Einstellungen: Rollenwechsel als Popup, Herleitung in einem Satz", async ({
    authenticatedPage: page,
  }) => {
    expect(spacePath, "der erste Schritt hat keinen Space angelegt").not.toBe(
      "",
    );
    await page.goto(`${spacePath}/settings/members`);
    await expect(
      page.getByText("Gruppen geben ihre Rolle an alle ihre Mitglieder weiter."),
    ).toHaveCount(0);

    const row = page
      .getByTestId("space-member-row")
      .filter({ hasText: "Dev User" });
    await row.getByRole("combobox").click();
    await page.getByRole("option", { name: "Kurator" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Rolle von Dev User: Kurator" }),
    ).toBeVisible();
    await expect(page.getByText(/Mitgliederliste wird geladen/)).toHaveCount(0);
    await expectNoSeriousA11yViolations(
      page,
      "Space-Einstellungen: Popup nach Rollenwechsel",
    );

    await row.getByRole("button", { name: "Weitere Aktionen für „Dev User“" }).click();
    await page
      .getByRole("menuitem", { name: "Warum hat Dev User Zugriff?" })
      .click();
    await expect(
      row.getByText(
        "Dev User ist Kurator in diesem Space – direkt aufgenommen.",
        { exact: true },
      ),
    ).toBeVisible();
    await expectNoSeriousA11yViolations(
      page,
      "Space-Einstellungen: Herleitung einer Person",
    );
    await row.getByRole("button", { name: "Schließen", exact: true }).click();
    await expect(row.getByText(/in diesem Space/)).toHaveCount(0);
  });

  /** #2207: Die Eigentümerzeile zeigt eine schreibgeschützte Auswahl im Aussehen der anderen. */
  test("Einstellungen: Eigentümer als schreibgeschützte Auswahl", async ({
    authenticatedPage: page,
  }) => {
    expect(spacePath, "der erste Schritt hat keinen Space angelegt").not.toBe(
      "",
    );
    await page.goto(`${spacePath}/settings/members`);
    const ownerRow = page
      .getByTestId("space-member-row")
      .filter({ hasText: "Eigentümer" });
    const ownerRole = ownerRow.getByRole("combobox");
    await expect(ownerRole).toHaveText("Eigentümer");
    await expect(ownerRole).toHaveAttribute("aria-readonly", "true");
    await ownerRole.click();
    await expect(page.getByRole("listbox")).toHaveCount(0);
    await expectNoSeriousA11yViolations(
      page,
      "Space-Einstellungen: Eigentümerzeile",
    );
  });
});

/**
 * #2207: Ein einfaches Mitglied öffnet die Einstellungen über das Zahnrad und sieht alles
 * schreibgeschützt; im Reiter „Mitglieder" nur die Zählung je Rolle, keinen Namen.
 */
test("Einstellungen lesend für ein Mitglied, Mitglieder nur als Zählung", async ({
  regularUserPage: page,
}) => {
  const admin = await apiAs("dev-admin");
  const member = await apiAs("dev-user");
  const me = await member.get("/api/v1/auth/me");
  expect(me.status(), await me.text()).toBe(200);
  const memberId = ((await me.json()) as { id: string }).id;
  const created = await admin.post("/api/v1/spaces", {
    data: { name: `E2E-Lesend-${Date.now()}` },
  });
  expect(created.status(), await created.text()).toBe(201);
  const spaceId = ((await created.json()) as { id: string }).id;
  try {
    const added = await admin.post(`/api/v1/spaces/${spaceId}/members`, {
      data: { subjectType: "USER", subjectId: memberId, role: "MEMBER" },
    });
    expect(added.status(), await added.text()).toBe(201);

    await page.goto(`/spaces/${spaceId}`);
    await page.getByRole("link", { name: "Einstellungen" }).click();
    await expect(page).toHaveURL(
      new RegExp(`/spaces/${spaceId}/settings/general$`),
    );
    await expect(page.getByLabel("Name des Space")).toHaveAttribute(
      "readonly",
      "",
    );
    await expect(
      page.getByRole("button", { name: "Einstellungen speichern" }),
    ).toHaveCount(0);
    await expect(page.getByText("Gefahrenbereich")).toHaveCount(0);

    await page.getByRole("tab", { name: "Mitglieder" }).click();
    await expect(page.getByText("2 Personen", { exact: true })).toBeVisible();
    await expect(
      page.getByText("Rollen: 1 Administrator, 1 Mitglied", { exact: true }),
    ).toBeVisible();
    await expect(page.getByTestId("space-member-row")).toHaveCount(0);
    await expectNoSeriousA11yViolations(
      page,
      "Space-Einstellungen eines Mitglieds: Mitglieder",
    );
  } finally {
    await admin.delete(`/api/v1/spaces/${spaceId}`);
  }
});
