import { request } from './client'

export interface UsageTotals {
  calls: number; actual_calls: number; estimated_calls: number; unknown_calls: number; pending_calls: number
  input_tokens: number; output_tokens: number; total_tokens: number; estimated_input: number; estimated_output: number
}
export type UsageGroup = UsageTotals & { model?: string; module?: string; domain?: string; contact_id?: number; contact_name?: string; unit_id?: string; timestamp?: number }
export interface UsageCall {
  meter_id: string; domain: string; contact_id: number; model: string; module: string; unit_id: string; run_id: string; request_id: string
  started_at: number; finished_at: number | null; status: string; quality: 'pending' | 'actual' | 'estimated' | 'unknown'
  input_tokens: number | null; output_tokens: number | null; source: string; revision: number
}
export interface AIUsageResponse {
  schemaVersion: number; timezone: string; generatedAt: number; complete: boolean; summary: UsageTotals | null
  filter: UsageParams
  sources: { name: string; state: string; storeId?: string; collectedAt?: number; updatedAt?: number; historyComplete?: boolean }[]
  trend: UsageGroup[]; breakdown: UsageGroup[]; models: UsageGroup[]; modules: UsageGroup[]
  calls: UsageCall[]; total: number; offset: number; limit: number; contactName?: string
}
export type UsageParams = Record<string, string | number | undefined>
export function fetchAIUsage(operation: 'summary' | 'trend' | 'breakdown' | 'calls' | 'bill', params: UsageParams = {}) {
  return request<AIUsageResponse>(`/statistic/ai-usage/${operation}`, { query: params, timeout: 60000 })
}
export function usageExportUrl(params: UsageParams) {
  const q = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => { if (value != null && value !== '') q.set(key, String(value)) })
  return `/statistic/ai-usage/export?${q}`
}
