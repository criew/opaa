import { expect, test } from "@playwright/test";
import {
  call,
  databasePoolSize,
  platform,
  prepareInstallation,
  uniqueAddress,
} from "../../fixtures/multiInstance";

/**
 * Scenario 6 (ADR-0053, Entscheidungen 5, 12 and 15): many more concurrent requests than the pool
 * has connections - sign-ins counted against a global ceiling of the installation, reads, and new
 * accounts. No request may fail for lack of a connection, and no instance holds more than its pool
 * plus the one connection of the invalidation bus - the bound the formula for max_connections
 * rests on.
 */

const CONCURRENT = 40;
const ROUNDS = 3;

test("Anfragen über der Poolgröße bleiben fehlerfrei und innerhalb von Pool + 1", async () => {
  const a = await platform().instanceUrl("a");
  const b = await platform().instanceUrl("b");
  const admin = await prepareInstallation(a);

  let sampling = true;
  const peaks = new Map<string, number>();
  const sampler = (async () => {
    while (sampling) {
      const rows = await platform().sql(
        "SELECT client_addr, count(*) FROM pg_stat_activity WHERE datname = current_database()" +
          " AND client_addr IS NOT NULL AND pid <> pg_backend_pid() GROUP BY client_addr",
      );
      for (const line of rows.split("\n").filter(Boolean)) {
        const [address, count] = line.split("\t");
        peaks.set(address, Math.max(peaks.get(address) ?? 0, Number(count)));
      }
    }
  })();

  const outcomes = new Map<number, number>();
  for (let round = 0; round < ROUNDS; round++) {
    const requests = [a, b].flatMap((url) =>
      Array.from({ length: CONCURRENT }, (_, index) => {
        if (index % 4 === 0) {
          // An account with its personal space, written in one transaction.
          return call(url, "POST", "/api/v1/admin/local-users", {
            token: admin.token,
            json: {
              email: uniqueAddress("pool"),
              displayName: "Poolprobe",
              createdReason: "Erprobung mit zwei Instanzen",
              mode: "INITIAL_PASSWORD",
            },
          });
        }
        return index % 2 === 0
          ? // A failed sign-in of an unknown address: counted by the global ceiling, locks no one.
            call(url, "POST", "/api/v1/auth/local/login", {
              json: { email: uniqueAddress("niemand"), password: "falsch-falsch-falsch" },
            })
          : call(url, "GET", "/api/v1/libraries", { token: admin.token });
      }),
    );
    for (const reply of await Promise.all(requests)) {
      outcomes.set(reply.status, (outcomes.get(reply.status) ?? 0) + 1);
    }
  }
  sampling = false;
  await sampler;

  const summary = JSON.stringify({ outcomes: [...outcomes], peaks: [...peaks] });
  test.info().annotations.push({ type: "Verbindungen je Instanz (Spitze)", description: summary });
  console.log(`Verbindungen je Instanz (Spitze): ${summary}`);
  expect([...outcomes.keys()].sort(), summary).toEqual([200, 201, 401]);
  expect(peaks.size, summary).toBeGreaterThanOrEqual(2);
  for (const [, peak] of peaks) {
    expect(peak, summary).toBeLessThanOrEqual(databasePoolSize + 1);
  }
});
