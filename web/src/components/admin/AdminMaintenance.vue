<script setup>
// 유지보수 (F12 11절): 실행 결과(처리 건수, 소요 시간) 표시, 서버가 감사 로그 기록
import { onMounted, ref } from 'vue'
import { adminApi, errorText } from '../../admin.js'

const TASKS = [
  ['reindex-search', '검색 색인 재구축'], ['rebuild-links', '링크 인덱스 재계산'],
  ['clear-link-previews', '링크 미리보기 캐시 비우기'], ['cleanup-drafts', '초안 정리 즉시 실행'],
  ['backup', '백업 즉시 실행 (DB + 첨부)'],
]
const results = ref({})
const running = ref('')
const report = ref(null)

async function run(task) {
  running.value = task
  try {
    const r = await adminApi.maintenance(task)
    results.value = { ...results.value, [task]: `${r.count}건 · ${r.ms}ms` }
  } catch (e) {
    results.value = { ...results.value, [task]: errorText(e) }
  } finally {
    running.value = ''
  }
}

onMounted(async () => { report.value = await adminApi.migrationReport() })
</script>

<template>
  <h1 class="doc-title">유지보수</h1>
  <table class="recent-table">
    <tbody>
      <tr v-for="[task, label] in TASKS" :key="task">
        <td>{{ label }}</td>
        <td><button type="button" :disabled="!!running" @click="run(task)">{{ running === task ? '실행 중…' : '실행' }}</button></td>
        <td class="muted">{{ results[task] ?? '' }}</td>
      </tr>
    </tbody>
  </table>
  <h2>마이그레이션 리포트</h2>
  <pre v-if="report?.exists" class="history-source">{{ report.content }}</pre>
  <p v-else class="muted">data/migration-report.md 없음</p>
</template>
