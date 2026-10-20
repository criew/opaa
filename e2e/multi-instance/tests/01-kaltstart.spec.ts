import { expect, test } from "@playwright/test";
import {
  bootstrapAdmin,
  call,
  callOk,
  loginOk,
  platform,
  prepareInstallation,
} from "../../fixtures/multiInstance";

/**
 * Scenario 1 (ADR-0053, Entscheidungen 3, 4 and 14): both instances started at the same time
 * against an empty database, after the migration step alone created the schema. Every one-time
 * start action ran once, both instances stand in the register, and a third instance whose
 * configuration differs is refused.
 */

test.describe.configure({ mode: "serial" });

test("beide Instanzen sind bereit und stehen im Instanzregister", async () => {
  const a = await platform().instanceUrl("a");
  const b = await platform().instanceUrl("b");
  for (const url of [a, b]) {
    expect((await call(url, "GET", "/api/health")).status).toBe(200);
  }
  const live = await platform().sql(
    "SELECT count(*) FROM backend_instances WHERE stopped_at IS NULL" +
      " AND heartbeat_at > clock_timestamp() - interval '2 minutes'",
  );
  expect(Number(live)).toBe(2);

  // The disclosure is the same whichever instance answers, and names both.
  const admin = await loginOk(a, bootstrapAdmin.email, bootstrapAdmin.password);
  const startedAt = async (url: string) => {
    const disclosure = await callOk<{ instance: { instances: { startedAt: string }[] } }>(
      url,
      "GET",
      "/api/v1/admin/compliance/data-collection-disclosure",
      { token: admin.token },
    );
    return disclosure.instance.instances.map((instance) => instance.startedAt).sort();
  };
  const fromA = await startedAt(a);
  expect(fromA).toHaveLength(2);
  expect(await startedAt(b)).toEqual(fromA);
});

test("die einmaligen Startaktionen liefen genau einmal", async () => {
  const a = await platform().instanceUrl("a");
  const b = await platform().instanceUrl("b");
  const admin = await prepareInstallation(a);

  // The schema came from the migration step: no open lock, no changeset twice.
  expect(await platform().sql("SELECT count(*) FROM databasechangeloglock WHERE locked")).toBe("0");
  expect(
    await platform().sql(
      "SELECT count(*) - count(DISTINCT (id, author, filename)) FROM databasechangelog",
    ),
  ).toBe("0");

  // Seeds under their advisory lock: one bootstrap administrator, one chat model, from either side.
  for (const url of [a, b]) {
    const accounts = await callOk<{ items: { email: string }[] }>(
      url,
      "GET",
      `/api/v1/admin/local-users?query=${encodeURIComponent(bootstrapAdmin.email)}`,
      { token: admin.token },
    );
    expect(accounts.items.filter((item) => item.email === bootstrapAdmin.email)).toHaveLength(1);
    const models = await callOk<unknown[]>(url, "GET", "/api/v1/admin/models", {
      token: admin.token,
    });
    expect(models).toHaveLength(1);
  }
});

test("eine Instanz mit abweichender Konfiguration verweigert den Start", async () => {
  const secret = "deviating-secret-of-a-third-instance-0123456789abcdefghij";
  const { exitCode, log } = await platform().startDeviatingInstance({
    TZ: "Europe/Berlin",
    OPAA_AUTH_JWT_SECRET: secret,
  });
  expect(exitCode, log.slice(-4000)).not.toBe(0);
  expect(exitCode, log.slice(-4000)).not.toBeNull();
  expect(log).toContain("refuses to start");
  expect(log).toContain("user.timezone");
  expect(log).toContain("jwt-secret");
  // A secret is named by its key, never by its value.
  expect(log).not.toContain(secret);

  // The two running instances are untouched.
  expect(
    Number(
      await platform().sql(
        "SELECT count(*) FROM backend_instances WHERE stopped_at IS NULL" +
          " AND heartbeat_at > clock_timestamp() - interval '2 minutes'",
      ),
    ),
  ).toBe(2);
});
