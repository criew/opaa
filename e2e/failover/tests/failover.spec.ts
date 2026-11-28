import { expect, test } from "@playwright/test";
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  HOLD_MARKER,
  callOk,
  corpusLibrary,
  createMailRecipient,
  eventually,
  heldEmbeddings,
  holdEmbeddings,
  platform,
  prepareInstallation,
  settledMailCount,
  sleep,
  type LoadResult,
  type Session,
} from "../../fixtures/multiInstance";
import {
  HEALTHY,
  PodWatch,
  apiUrl,
  backendPods,
  closeApiForwards,
  clusterPhase,
  databasePods,
  dbSql,
  drain,
  drainNode,
  killPod,
  kubectl,
  namespace,
  primary,
  settled,
  switchover,
  uncordon,
  waitUntil,
  type PodMetrics,
  type PodState,
} from "../cluster";
import { analyzeLogs } from "../logs";

/**
 * One disturbance of the failover test under load (deploy/helm/ci/failover-test.sh, ADR-0053,
 * ADR-0076): a planned switchover of the database primary, the loss of its pod, or the drain of the
 * node that carries a backend pod and the primary. Meanwhile the load generator of the two-instance
 * suite reads and signs in through the frontend, an indexing run and an upload wait for their
 * embeddings until two seconds into the disturbance, and a scheduled library run falls due five
 * seconds into it. The run records what happened and checks only what must hold regardless:
 * every mail once, the scheduled run once, no instance taking itself for dead.
 */

const disturbance = process.env.E2E_FAILOVER_DISTURBANCE ?? "switchover";
const runId = process.env.E2E_FAILOVER_RUN ?? disturbance;
const resultsDir = process.env.E2E_FAILOVER_RESULTS ?? "../failover-results";
const corpusSize = Number(process.env.E2E_CORPUS_SIZE ?? "40");

interface Sample {
  at: number;
  primary?: string;
  phase: string;
  pods: PodState[];
  metrics: Record<string, PodMetrics | null>;
}

interface IndexingStatus {
  status: string;
  documentsIndexedTotal: number;
  documentsFailed: number;
}

test.afterAll(async () => {
  await holdEmbeddings(false).catch(() => undefined);
  uncordon(drainNode);
  closeApiForwards();
});

/** For the drain: one backend pod and the primary on the node that is drained. */
async function prepareDrain(): Promise<void> {
  if (!(await backendPods()).some((pod) => pod.node === drainNode)) {
    const elsewhere = (await backendPods()).find((pod) => pod.node !== drainNode);
    kubectl(["-n", namespace, "delete", "pod", elsewhere!.name, "--wait=true"]);
    await waitUntil(settled, 600_000, "Ersatz des Backend-Pods bereit");
  }
  expect((await backendPods()).some((pod) => pod.node === drainNode), "Backend-Pod auf dem zu räumenden Knoten").toBe(true);
  const onNode = (await databasePods()).find((pod) => pod.node === drainNode);
  if (onNode && onNode.role !== "primary") {
    switchover(onNode.name);
    await waitUntil(async () => (await primary())?.name === onNode.name && (await settled()), 600_000, "Primärserver auf dem zu räumenden Knoten");
  }
}

async function anyApi(): Promise<string> {
  const ready = (await backendPods()).find((pod) => pod.ready);
  return apiUrl(ready!.name);
}

async function finalIndexing(libraryId: string, token: string): Promise<IndexingStatus> {
  const deadline = Date.now() + 300_000;
  let status: IndexingStatus = { status: "UNKNOWN", documentsIndexedTotal: 0, documentsFailed: 0 };
  while (Date.now() < deadline) {
    status = await callOk<IndexingStatus>(await anyApi(), "GET", `/api/v1/libraries/${libraryId}/indexing/status`, { token });
    if (status.status !== "RUNNING") {
      break;
    }
    await sleep(2000);
  }
  return status;
}

async function runsOf(libraryId: string, token: string) {
  return (await callOk<{ runs: { triggeredBy: string; status: string }[] }>(await anyApi(), "GET", `/api/v1/libraries/${libraryId}/indexing/runs`, { token })).runs;
}

async function documentStatus(libraryId: string, documentId: string, token: string) {
  const page = await callOk<{ items: { id: string; status: string }[] }>(await anyApi(), "GET", `/api/v1/libraries/${libraryId}/documents?size=100`, { token });
  return page.items.find((item) => item.id === documentId)?.status ?? "MISSING";
}

/** Milliseconds after `t0` during which `down` held, summed over the samples. */
function downtime(samples: Sample[], down: (sample: Sample) => boolean): number {
  let total = 0;
  for (let i = 1; i < samples.length; i++) {
    if (down(samples[i - 1])) {
      total += samples[i].at - samples[i - 1].at;
    }
  }
  return total;
}

function timeline(samples: Sample[], t0: number, oldPrimary: string) {
  const after = samples.filter((sample) => sample.at >= t0);
  const relative = (sample?: Sample) => (sample ? sample.at - t0 : null);
  const pods = [...new Set(samples.flatMap((sample) => Object.keys(sample.metrics)))];
  const perPod = Object.fromEntries(
    pods.map((pod) => {
      const seen = samples.map((sample) => sample.metrics[pod]).filter((metrics): metrics is PodMetrics => !!metrics);
      const first = seen[0];
      const last = seen[seen.length - 1];
      const failures = Object.fromEntries(
        Object.keys(last?.scheduledFailures ?? {})
          .map((job) => [job, (last.scheduledFailures[job] ?? 0) - (first.scheduledFailures[job] ?? 0)])
          .filter(([, delta]) => (delta as number) > 0),
      );
      return [
        pod,
        {
          node: samples.flatMap((sample) => sample.pods).find((state) => state.name === pod)?.node,
          busDownMs: downtime(samples, (sample) => sample.metrics[pod]?.busConnected === 0),
          maxHeartbeatAgeSeconds: Math.max(0, ...seen.map((metrics) => metrics.heartbeatAgeSeconds ?? 0)),
          minInstancesActive: Math.min(...seen.map((metrics) => metrics.instancesActive ?? Infinity)),
          maxPoolActive: Math.max(0, ...seen.map((metrics) => metrics.poolActive ?? 0)),
          maxPoolPending: Math.max(0, ...seen.map((metrics) => metrics.poolPending ?? 0)),
          poolTimeouts: (last?.poolTimeouts ?? 0) - (first?.poolTimeouts ?? 0),
          unreadyMs: downtime(samples, (sample) => sample.pods.some((state) => state.name === pod && !state.ready)),
          scheduledFailures: failures,
        },
      ];
    }),
  );
  return {
    newPrimary: after.find((sample) => sample.primary && sample.primary !== oldPrimary)?.primary ?? null,
    newPrimaryAtMs: relative(after.find((sample) => sample.primary && sample.primary !== oldPrimary)),
    withoutPrimaryMs: downtime(after, (sample) => !sample.primary),
    clusterHealthyAgainAtMs: relative(after.find((sample, i) => sample.phase === HEALTHY && after.slice(0, i).some((earlier) => earlier.phase !== HEALTHY))),
    perPod,
  };
}

function loadSummary(load: LoadResult, t0: number) {
  const failures = load.failures.map((failure) => ({ ...failure, ms: Date.parse(failure.at) - t0 }));
  const outcomes = Object.fromEntries(Object.entries(load.byOutcome).filter(([outcome]) => !/^2\d\d$/.test(outcome)));
  return {
    requests: load.requests,
    failed: failures.length,
    failedOutcomes: outcomes,
    firstFailureMs: failures.length ? Math.min(...failures.map((failure) => failure.ms)) : null,
    lastFailureMs: failures.length ? Math.max(...failures.map((failure) => failure.ms)) : null,
    longestGapMs: load.longestGapMs,
    notifyingSignIns: load.notifyingSignIns,
    failedPaths: Object.fromEntries(
      [...new Set(failures.map((failure) => failure.path))].map((path) => [path, failures.filter((failure) => failure.path === path).length]),
    ),
  };
}

test(`Störung „${disturbance}“ unter Last`, async () => {
  await waitUntil(settled, 600_000, "Ausgangslage: Datenbank und Backends bereit");
  if (disturbance === "drain") {
    await prepareDrain();
  }
  const pods = await backendPods();
  const keeper = disturbance === "drain" ? pods.find((pod) => pod.node !== drainNode)! : pods[0];
  const other = pods.find((pod) => pod.name !== keeper.name)!;
  const a = await apiUrl(keeper.name);
  const b = await apiUrl(other.name);
  const admin: Session = await prepareInstallation(a);
  const recipient = await createMailRecipient(a, admin.token);
  const registerBefore = await dbSql("SELECT id, host_name FROM backend_instances WHERE stopped_at IS NULL ORDER BY host_name");

  // The scheduled run falls due five seconds into the disturbance (UTC is the zone of the chart).
  let due = new Date(Date.now() + 60_000);
  due.setUTCSeconds(0, 0);
  if (due.getTime() - Date.now() < 45_000) {
    due = new Date(due.getTime() + 60_000);
  }
  const scheduled = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", {
    token: admin.token,
    json: { ...corpusLibrary(`Geplant ${runId} ${Date.now()}`, "vermerk-0"), schedule: { frequency: "DAILY", hour: due.getUTCHours(), minute: due.getUTCMinutes() } },
  });
  const library = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", { token: admin.token, json: corpusLibrary(`Umschaltung ${runId} ${Date.now()}`) });
  const uploads = await callOk<{ id: string }>(a, "POST", "/api/v1/libraries", { token: admin.token, json: { name: `Upload ${runId} ${Date.now()}`, sourceType: "UPLOAD" } });

  await holdEmbeddings(true);
  await callOk(a, "POST", `/api/v1/libraries/${library.id}/indexing`, { token: admin.token });
  const form = new FormData();
  form.append("file", new Blob([`Aktennotiz während der Störung, ${HOLD_MARKER}.\n`], { type: "text/plain" }), "aktennotiz.txt");
  const document = await callOk<{ id: string }>(b, "POST", `/api/v1/libraries/${uploads.id}/documents`, { token: admin.token, form });
  expect(await eventually(async () => (await heldEmbeddings()) >= 2, 60_000)).toBe(true);

  await platform().startLoad(admin.token, runId);
  mkdirSync(resultsDir, { recursive: true });
  const watch = new PodWatch(resultsDir, runId);
  const samples: Sample[] = [];
  const watchSince = new Date();
  let sampling = true;
  const sampler = (async () => {
    while (sampling) {
      const [backend, current, phase] = await Promise.all([backendPods(), primary(), clusterPhase()]);
      samples.push({ at: Date.now(), primary: current?.name, phase, pods: backend, metrics: await watch.sample(backend, watchSince) });
      await sleep(1000);
    }
  })();

  await sleep(Math.max(15_000, due.getTime() - 5000 - Date.now()));
  const oldPrimary = (await primary())!.name;
  const t0 = Date.now();
  const drainLog: string[] = [];
  let drained: Promise<number | null> = Promise.resolve(null);
  if (disturbance === "switchover") {
    switchover((await databasePods()).find((pod) => pod.name !== oldPrimary)!.name);
  } else if (disturbance === "failover") {
    killPod(oldPrimary);
  } else if (disturbance === "drain") {
    drained = drain(drainNode, (line) => drainLog.push(`${Date.now() - t0} ms ${line}`));
  } else {
    throw new Error(`unbekannte Störung ${disturbance}`);
  }
  await sleep(2000);
  await holdEmbeddings(false);

  let drainExit: number | null = null;
  let drainDoneMs: number | null = null;
  void drained.then((code) => {
    drainExit = code;
    drainDoneMs = Date.now() - t0;
  });
  await waitUntil(
    async () => {
      const current = await primary();
      const ready = (await backendPods()).filter((pod) => pod.ready).length;
      return (
        Date.now() - t0 > 45_000 &&
        !!current &&
        current.name !== oldPrimary &&
        ready === 2 &&
        (disturbance !== "drain" || drainDoneMs !== null)
      );
    },
    600_000,
    "neuer Primärserver und zwei bereite Backends",
  );
  await sleep(20_000);
  const load = await platform().stopLoad();
  sampling = false;
  await sampler;

  const indexing = await finalIndexing(library.id, admin.token);
  const indexingRuns = await runsOf(library.id, admin.token);
  await eventually(async () => (await documentStatus(uploads.id, document.id, admin.token)) !== "PENDING", 180_000);
  const upload = await documentStatus(uploads.id, document.id, admin.token);
  await sleep(Math.max(0, due.getTime() + 150_000 - Date.now()));
  await eventually(async () => !(await runsOf(scheduled.id, admin.token)).some((run) => run.status === "RUNNING"), 120_000);
  const scheduledRuns = (await runsOf(scheduled.id, admin.token)).filter((run) => run.triggeredBy === "SCHEDULED");
  const delivered = await settledMailCount(recipient, load.notifyingSignIns, { timeoutMs: 180_000 });
  // Every sign-in also mails the recipients of earlier runs; their deliveries may still be under way.
  await eventually(async () => (await dbSql("SELECT count(*) FROM mail_outbox")) === "0", 120_000);
  const outbox = await dbSql("SELECT count(*) FROM mail_outbox");
  const registerAfter = await dbSql(
    "SELECT id, host_name, stopped_at IS NOT NULL, round(extract(epoch FROM clock_timestamp() - heartbeat_at)) FROM backend_instances" +
      " WHERE started_at > clock_timestamp() - interval '1 day' AND coalesce(stopped_at, heartbeat_at) > clock_timestamp() - interval '15 minutes' ORDER BY host_name",
  );
  const locks = await dbSql("SELECT name, locked_by, lock_until > timezone('UTC', clock_timestamp()) FROM shedlock ORDER BY name");
  if (disturbance === "drain") {
    uncordon(drainNode);
  }
  await watch.close();

  const result = {
    run: runId,
    disturbance,
    startedAt: new Date(t0).toISOString(),
    oldPrimary,
    drain: disturbance === "drain" ? { node: drainNode, exit: drainExit, doneMs: drainDoneMs, log: drainLog } : undefined,
    timeline: timeline(samples, t0, oldPrimary),
    load: loadSummary(load, t0),
    mails: { expected: load.notifyingSignIns, delivered, outbox },
    indexing: { ...indexing, expected: corpusSize, runs: indexingRuns.map((run) => run.status), owner: keeper.name },
    upload: { status: upload, owner: other.name },
    scheduled: { dueAt: due.toISOString(), runs: scheduledRuns.map((run) => run.status) },
    register: { before: registerBefore.split("\n"), after: registerAfter.split("\n") },
    shedlock: locks.split("\n"),
    logs: analyzeLogs(watch.followed.map((pod) => watch.logFile(pod)), t0 - 5000),
  };
  writeFileSync(join(resultsDir, `${runId}.json`), `${JSON.stringify(result, null, 2)}\n`);
  test.info().annotations.push({ type: runId, description: JSON.stringify({ load: result.load, mails: result.mails }) });

  expect.soft(delivered, "jede Mail der Outbox genau einmal").toBe(load.notifyingSignIns);
  expect.soft(outbox, "Outbox leer").toBe("0");
  expect.soft(scheduledRuns.length, "geplanter Lauf genau einmal").toBe(1);
  expect.soft(result.logs.counts.instanceLost, "keine Instanz hält sich für tot").toBe(0);
  expect.soft(indexing.status, "Indexierungslauf abgeschlossen").toBe("COMPLETED");
});
