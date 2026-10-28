import type { PerformanceReport } from './types'

export interface ServiceValues {
  requests: number; completed: number; succeeded: number; failed: number; timedOut: number
  cancelled: number; rejected: number; accepted: number; unknown: number; knownCompleted: number
  rps: number; failureRate: number | null; avgMs: number | null; p95Ms: number | null
  queueAvgMs: number | null; executionAvgMs: number | null
  streamCount: number; streamAvgMs: number | null; firstByteAvgMs: number | null
  queuedPeak: number; runningPeak: number
}
export interface ServiceRow {
  module: string; action?: string; control?: boolean
  window: ServiceValues; total: ServiceValues; queued: number; running: number
}
export interface ServiceExecution extends Omit<ServiceRow, 'control' | 'module' | 'action'> {
  startedAt: number; windowSeconds: number; windowStartedAt: number; windowEndedAt: number
  receivedMessages: number; modules: ServiceRow[]; actions: ServiceRow[]
  series: (ServiceValues & { timestamp: number })[]; control: ServiceRow
  states?: Record<string, number>; recovered?: number; untracked?: number
}
export interface ServiceWorker {
  id: string; module: string; workerId: string; instanceId: string; role?: string; runnerType?: string
  name?: string; address?: string; model?: string; backendId?: string; busy?: boolean; connected: boolean | null; ready?: boolean | null; healthy?: boolean | null
  draining?: boolean; leaseRemainingMs?: number; lastSeenAgeMs: number
  capacity?: number; occupied?: number; available?: number | null
}
export interface ServicePeer { id: string; name: string; address: string; ready: boolean | null; totalCalls: number; lastSeenAgeMs: number }
export interface ServicePool { name: string; running: boolean; active: number | null; threads: number | null; maxThreads: number | null; queued: number | null; completed: number | null }
export interface ServiceJVM {
  timestamp: number; heapUsed: number; heapCommitted: number; heapMax: number | null
  threads: number; daemonThreads: number; peakThreads: number
  gcCount: number; gcMillis: number; gcDeltaCount: number | null; gcDeltaMillis: number | null
}
export interface ServiceCapacity { registeredUnits: number; available: number; executableConcurrency: number; backends: Record<string, string | number>[] }
export interface ServiceModule {
  module: string; started: boolean; collectionError?: boolean
  details?: { jobs?: ServiceExecution; capacity?: ServiceCapacity | null; infrastructureReady?: boolean; runsReady?: boolean; accepting?: boolean | null }
}
export interface ServiceSnapshot {
  schemaVersion: 1; execution: ServiceExecution; modules: ServiceModule[]; pools: ServicePool[]
  jvm: ServiceJVM; runtimeSeries: ServiceJVM[]
  topology: { dispatchers: ServicePeer[]; workers: ServiceWorker[] }
}
export function serviceSnapshot(report?: PerformanceReport | null): ServiceSnapshot | null {
  const value = report?.items?.Service
  if (value?.schemaVersion !== 1 || !value.execution?.window || !value.execution.total
    || !Array.isArray(value.execution.series) || !Array.isArray(value.execution.actions)
    || !Array.isArray(value.execution.modules) || !Array.isArray(value.modules) || !Array.isArray(value.pools)
    || !value.jvm || !Array.isArray(value.runtimeSeries) || !Array.isArray(value.topology?.dispatchers)
    || !Array.isArray(value.topology?.workers)) return null
  return value
}
export const failureCount = (value: ServiceValues) => value.failed + value.timedOut
