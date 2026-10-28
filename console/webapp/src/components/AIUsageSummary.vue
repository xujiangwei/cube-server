<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchAIUsage, type AIUsageResponse } from '@/api/ai-usage'
const props = withDefaults(defineProps<{ domain?: string; unit?: string; module?: string }>(), { domain: '', unit: '', module: '' })
const data = ref<AIUsageResponse | null>(null), error = ref(false)
let revision = 0
async function refresh() {
  const mine = ++revision
  try { const result = await fetchAIUsage('summary', { domain: props.domain || undefined, unit: props.unit || undefined, module: props.module || undefined }); if (mine === revision) { data.value = result; error.value = false } }
  catch { if (mine === revision) { error.value = true; data.value = null } }
}
watch(() => [props.domain, props.unit, props.module], () => { data.value = null; void refresh() }, { immediate: true })
const timer = window.setInterval(refresh, 60000)
onBeforeUnmount(() => { ++revision; window.clearInterval(timer) })
const query = computed(() => ({ domain: props.domain || undefined, unit: props.unit || undefined, module: props.module || undefined }))
</script>
<template>
  <div class="flex flex-wrap items-center justify-between gap-3 rounded-xl bg-indigo-50/60 px-4 py-3 text-xs text-slate-600">
    <span>本月 AI 用量<span class="ml-1 text-slate-400">（{{ domain ? '当前域' : '授权域' }} · {{ unit ? '当前执行单元' : '已配置计量库' }}）</span></span>
    <span v-if="error">查询失败</span><span v-else-if="!data?.summary">尚未获得计量数据</span>
    <span v-else>{{ data.summary.total_tokens.toLocaleString('zh-CN') }} 实际 Token · {{ data.summary.calls.toLocaleString('zh-CN') }} 次调用<span v-if="!data.complete || data.summary.unknown_calls || data.summary.pending_calls" class="ml-2 text-amber-600">存在未核对数据</span></span>
    <RouterLink :to="{ path: '/ai-usage', query }" class="font-medium text-indigo-600 hover:underline">查看用量与账单 →</RouterLink>
  </div>
</template>
