import type { DispatcherSnapshot, PerformanceReport } from './types'

/** A legacy report is different from a measured zero. */
export function dispatcherSnapshot(report?: PerformanceReport | null): DispatcherSnapshot | null {
  const value = report?.items?.Dispatcher
  if (value?.schemaVersion !== 1 || !value.http?.window || !value.http.total
    || !Array.isArray(value.http.series) || !Array.isArray(value.http.modules)
    || !Array.isArray(value.http.routes) || !Array.isArray(value.topology?.services)) return null
  return value
}

export function metric(value: number | null | undefined, digits = 0): string {
  return value == null || !Number.isFinite(value)
    ? '--' : value.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}

export function milliseconds(value: number | null | undefined): string {
  return value == null ? '--' : `${metric(value, 1)} ms`
}
