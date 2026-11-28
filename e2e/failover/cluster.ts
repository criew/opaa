import { spawn, spawnSync, type ChildProcess } from "node:child_process";
import { appendFileSync } from "node:fs";
import { join } from "node:path";
import { Forward, sleep } from "../fixtures/multiInstance";

/**
 * The cluster of the failover test (deploy/helm/ci/failover-test.sh): the backend pods of the
 * release, the CloudNativePG cluster "opaa-db" and the disturbances. Every call goes through
 * kubectl with the kubeconfig the script exports.
 */

const env = (name: string, fallback?: string): string => {
  const value = process.env[name] ?? fallback;
  if (value === undefined || value === "") {
    throw new Error(`${name} ist nicht gesetzt; den Lauf startet failover-test.sh.`);
  }
  return value;
};

export const namespace = env("E2E_KIND_NAMESPACE");
export const dbNamespace = env("E2E_FAILOVER_DB_NAMESPACE", "failover-db");
export const drainNode = env("E2E_FAILOVER_DRAIN_NODE");
const DB_CLUSTER = "opaa-db";
export const HEALTHY = "Cluster in healthy state";

/** Hands a background kubectl to the script, which ends it should this process not. */
function track(child: ChildProcess): ChildProcess {
  const pidFile = process.env.E2E_KIND_FORWARD_PIDS;
  if (pidFile && child.pid) {
    appendFileSync(pidFile, `${child.pid}\n`);
  }
  return child;
}

export function kubectl(args: string[], { allowFailure = false } = {}): string {
  const result = spawnSync("kubectl", args, { encoding: "utf8", timeout: 120_000, maxBuffer: 64 * 1024 * 1024 });
  if (!allowFailure && (result.error || result.status !== 0)) {
    throw new Error(`kubectl ${args.join(" ")} endete mit ${result.status}:\n${result.stdout}\n${result.stderr}`);
  }
  return (result.stdout ?? "").trim();
}

/** kubectl without blocking the event loop; resolves with stdout, or null when it failed. */
export function kubectlAsync(args: string[], timeoutMs = 10_000): Promise<string | null> {
  return new Promise((resolve) => {
    const child = spawn("kubectl", args, { stdio: ["ignore", "pipe", "ignore"] });
    let out = "";
    const timer = setTimeout(() => child.kill(), timeoutMs);
    child.stdout.on("data", (chunk) => (out += chunk));
    child.on("error", () => resolve(null));
    child.on("close", (code) => {
      clearTimeout(timer);
      resolve(code === 0 ? out.trim() : null);
    });
  });
}

export interface PodState {
  name: string;
  node: string;
  ready: boolean;
  role?: string;
}

const podColumns =
  '{range .items[*]}{.metadata.name}|{.spec.nodeName}|{.metadata.deletionTimestamp}|{.status.conditions[?(@.type=="Ready")].status}|{.metadata.labels.cnpg\\.io/instanceRole}{"\\n"}{end}';

function parsePods(listing: string | null): PodState[] {
  return (listing ?? "")
    .split("\n")
    .map((line) => line.trim().split("|"))
    .filter((fields) => fields.length === 5 && fields[0] !== "")
    .map(([name, node, deleted, ready, role]) => ({
      name,
      node,
      ready: deleted === "" && ready === "True",
      ...(role ? { role } : {}),
    }));
}

export async function backendPods(): Promise<PodState[]> {
  const listing = await kubectlAsync(["-n", namespace, "get", "pods", "-l", "app.kubernetes.io/component=backend", "-o", `jsonpath=${podColumns}`]);
  return parsePods(listing).sort((a, b) => a.name.localeCompare(b.name));
}

export async function databasePods(): Promise<PodState[]> {
  const listing = await kubectlAsync(["-n", dbNamespace, "get", "pods", "-l", `cnpg.io/cluster=${DB_CLUSTER}`, "-o", `jsonpath=${podColumns}`]);
  return parsePods(listing);
}

export async function clusterPhase(): Promise<string> {
  return (await kubectlAsync(["-n", dbNamespace, "get", "cluster", DB_CLUSTER, "-o", "jsonpath={.status.phase}"])) ?? "";
}

export async function primary(): Promise<PodState | undefined> {
  return (await databasePods()).find((pod) => pod.role === "primary" && pod.ready);
}

/** One statement on the current primary as superuser, retried until the primary answers. */
export async function dbSql(statement: string, timeoutMs = 180_000): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const current = await primary();
    if (current) {
      const out = await kubectlAsync(["-n", dbNamespace, "exec", current.name, "-c", "postgres", "--", "psql", "-d", "opaa", "-At", "-F", "\t", "-c", statement], 30_000);
      if (out !== null) {
        return out;
      }
    }
    await sleep(1000);
  }
  throw new Error(`Keine Antwort des Primärservers auf: ${statement}`);
}

export async function waitUntil(probe: () => Promise<boolean>, timeoutMs: number, what: string): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await probe()) {
      return;
    }
    await sleep(1000);
  }
  throw new Error(`${what}: nach ${timeoutMs / 1000} s nicht erreicht`);
}

/** Both instances of the database ready, the cluster healthy, both backend pods ready. */
export async function settled(): Promise<boolean> {
  const db = await databasePods();
  const backend = await backendPods();
  return (
    (await clusterPhase()) === HEALTHY &&
    db.filter((pod) => pod.ready).length === 2 &&
    db.some((pod) => pod.role === "primary" && pod.ready) &&
    backend.filter((pod) => pod.ready).length === 2 &&
    backend.length === 2
  );
}

/** A planned switchover to `target`, as `kubectl cnpg promote` sets it in the cluster status. */
export function switchover(target: string): void {
  const status = {
    targetPrimary: target,
    targetPrimaryTimestamp: new Date().toISOString().replace("Z", "000Z"),
    phase: "Switchover in progress",
    phaseReason: `Switching over to ${target}`,
  };
  kubectl(["-n", dbNamespace, "patch", "cluster", DB_CLUSTER, "--type", "merge", "--subresource", "status", "-p", JSON.stringify({ status })]);
}

/** The unplanned case: the primary's pod is killed without a grace period. */
export function killPod(name: string): void {
  kubectl(["-n", dbNamespace, "delete", "pod", name, "--grace-period=0", "--force", "--wait=false"]);
}

/** `kubectl drain` of `node`, running in the background; resolves with its exit code. */
export function drain(node: string, log: (line: string) => void): Promise<number | null> {
  return new Promise((resolve) => {
    const child = spawn("kubectl", ["drain", node, "--ignore-daemonsets", "--delete-emptydir-data", "--timeout=10m"], { stdio: ["ignore", "pipe", "pipe"] });
    const forward = (chunk: Buffer) => chunk.toString().split("\n").filter(Boolean).forEach(log);
    child.stdout.on("data", forward);
    child.stderr.on("data", forward);
    child.on("close", (code) => resolve(code));
  });
}

export function uncordon(node: string): void {
  kubectl(["uncordon", node], { allowFailure: true });
}

// --- Per-pod access: API, metrics and log -----------------------------------------------------

let nextPort = 18300;
const apiForwards = new Map<string, Forward>();

/** Base URL of the API of one backend pod itself. */
export async function apiUrl(pod: string): Promise<string> {
  let forward = apiForwards.get(pod);
  if (!forward) {
    forward = new Forward(namespace, nextPort++, 8080);
    apiForwards.set(pod, forward);
  }
  return forward.to(`pod/${pod}`, "/api/health");
}

/** A port-forward to the management port of one pod, without waiting; dies with the pod. */
class MetricsForward {
  readonly url: string;
  private readonly process: ChildProcess;

  constructor(pod: string) {
    const port = nextPort++;
    this.url = `http://127.0.0.1:${port}/actuator/prometheus`;
    this.process = track(spawn("kubectl", ["-n", namespace, "port-forward", `pod/${pod}`, `${port}:8081`], { stdio: "ignore" }));
  }

  get alive(): boolean {
    return this.process.exitCode === null;
  }

  close(): void {
    this.process.kill();
  }
}

export interface PodMetrics {
  busConnected?: number;
  heartbeatAgeSeconds?: number;
  instancesActive?: number;
  poolActive?: number;
  poolPending?: number;
  poolTimeouts?: number;
  scheduledFailures: Record<string, number>;
}

function parseMetrics(text: string): PodMetrics {
  const metrics: PodMetrics = { scheduledFailures: {} };
  const sum = (current: number | undefined, value: number) => (current ?? 0) + value;
  for (const line of text.split("\n")) {
    const match = /^([a-zA-Z_:]+)(\{[^}]*\})?\s+(\S+)/.exec(line);
    if (!match) {
      continue;
    }
    const [, name, labels = "", raw] = match;
    const value = Number(raw);
    if (name === "opaa_invalidation_bus_connected") metrics.busConnected = value;
    else if (name === "opaa_instance_heartbeat_age_seconds") metrics.heartbeatAgeSeconds = value;
    else if (name === "opaa_instances_active") metrics.instancesActive = value;
    else if (name === "hikaricp_connections_active") metrics.poolActive = sum(metrics.poolActive, value);
    else if (name === "hikaricp_connections_pending") metrics.poolPending = sum(metrics.poolPending, value);
    else if (name === "hikaricp_connections_timeout_total") metrics.poolTimeouts = sum(metrics.poolTimeouts, value);
    else if (name === "opaa_scheduled_failures_total") {
      const job = /scheduled_job="([^"]+)"/.exec(labels)?.[1] ?? "?";
      metrics.scheduledFailures[job] = sum(metrics.scheduledFailures[job], value);
    }
  }
  return metrics;
}

/**
 * Follows every backend pod while it runs: its metrics on request and its log into `dir`, read in
 * slices since the last line (`kubectl logs -f` needs an inotify watch per stream, which a busy
 * host may not have). A pod that appears later, the replacement after a drain, is read from its
 * first line; an evicted pod's last seconds may be missing.
 */
export class PodWatch {
  private readonly metrics = new Map<string, MetricsForward>();
  private readonly lastLine = new Map<string, string>();
  private ticks = 0;

  constructor(private readonly dir: string, private readonly prefix: string) {}

  logFile(pod: string): string {
    return join(this.dir, `${this.prefix}-${pod}.log`);
  }

  get followed(): string[] {
    return [...this.lastLine.keys()];
  }

  /** Appends the lines of `pod` after the last one read; a pod gone keeps what was read. */
  async readLog(pod: string, since?: Date): Promise<void> {
    const last = this.lastLine.get(pod) ?? "";
    const from = last ? new Date(Date.parse(last.slice(0, last.indexOf(" "))) - 1000) : since;
    const args = ["-n", namespace, "logs", `pod/${pod}`, "-c", "backend", "--timestamps"];
    if (from) {
      args.push(`--since-time=${from.toISOString().replace(/\.\d+Z$/, "Z")}`);
    }
    const out = await kubectlAsync(args, 20_000);
    if (out === null) {
      return;
    }
    const stamp = (line: string) => line.slice(0, line.indexOf(" "));
    const fresh = out.split("\n").filter((line) => line !== "" && (!last || stamp(line) > stamp(last)));
    if (fresh.length) {
      appendFileSync(this.logFile(pod), `${fresh.join("\n")}\n`);
      this.lastLine.set(pod, fresh[fresh.length - 1]);
    } else if (!this.lastLine.has(pod)) {
      this.lastLine.set(pod, "");
    }
  }

  async sample(pods: PodState[], since: Date): Promise<Record<string, PodMetrics | null>> {
    const sampled: Record<string, PodMetrics | null> = {};
    const readLogs = this.ticks++ % 3 === 0;
    await Promise.all(
      pods.map(async (pod) => {
        if (readLogs || !this.lastLine.has(pod.name)) {
          await this.readLog(pod.name, since);
        }
        let forward = this.metrics.get(pod.name);
        if (!forward || !forward.alive) {
          forward?.close();
          if (!pod.ready) {
            sampled[pod.name] = null;
            return;
          }
          forward = new MetricsForward(pod.name);
          this.metrics.set(pod.name, forward);
          await sleep(500);
        }
        try {
          const response = await fetch(forward.url, { signal: AbortSignal.timeout(2000) });
          sampled[pod.name] = response.ok ? parseMetrics(await response.text()) : null;
        } catch {
          sampled[pod.name] = null;
        }
      }),
    );
    return sampled;
  }

  /** Reads the last lines of every pod still there and ends the metrics forwards. */
  async close(): Promise<void> {
    await Promise.all(this.followed.map((pod) => this.readLog(pod)));
    this.metrics.forEach((forward) => forward.close());
  }
}

export function closeApiForwards(): void {
  apiForwards.forEach((forward) => forward.close());
}
