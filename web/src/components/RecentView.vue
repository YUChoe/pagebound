<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { formatDate } from '../time.js'

const SOURCE = { web: '웹', mcp: '에이전트', api: 'API', system: '시스템', migration: '이전' }
const items = ref([])
onMounted(async () => {
  document.title = '최근 변경 - Pagebound'
  items.value = await api.recent(100)
})
</script>

<template>
  <article class="doc-card">
    <h1 class="doc-title">최근 변경</h1>
    <table class="recent-table">
      <thead><tr><th>문서</th><th>버전</th><th>편집자</th><th>경로</th><th>변경량</th><th>시각</th></tr></thead>
      <tbody>
        <tr v-for="r in items" :key="r.path + r.version">
          <td><router-link :to="'/w/' + encodePath(r.path)">{{ r.title }}</router-link>
            <div v-if="r.summary" class="muted">{{ r.summary }}</div></td>
          <td>v{{ r.version }}</td>
          <td>{{ r.author ?? '-' }}</td>
          <td><span class="badge" :class="'src-' + r.source">{{ SOURCE[r.source] ?? r.source }}{{ r.tokenName ? ' · ' + r.tokenName : '' }}</span></td>
          <td :class="r.deltaBytes >= 0 ? 'plus' : 'minus'">{{ r.deltaBytes >= 0 ? '+' : '' }}{{ r.deltaBytes }}</td>
          <td class="muted">{{ formatDate(r.createdAt) }}</td>
        </tr>
      </tbody>
    </table>
  </article>
</template>
