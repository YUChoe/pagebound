<script setup>
// 역사 화면 /history/{path} (spec/features/F7-common.md 2절)
// ?from=&to= → side-by-side diff, ?v= → 해당 버전 보기
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { renderMarkdown } from '../markdown/render.js'
import { SOURCE_LABELS } from '../blame.js'
import { formatDate } from '../time.js'
import { canEdit } from '../session.js'
import { collapseRows } from '../history.js'

const route = useRoute()
const router = useRouter()
const page = ref(null)
const revisions = ref([])
const status = ref('loading')
const diff = ref(null)
const view = ref(null)
const showSource = ref(false)
const from = ref(null)
const to = ref(null)
const expanded = ref(new Set())

const path = computed(() => routePath(route))
const latest = computed(() => revisions.value[0]?.version)
const blocks = computed(() => (diff.value ? collapseRows(diff.value.rows, 3, expanded.value) : []))
const rendered = computed(() => (view.value ? renderMarkdown(view.value.content, { path: path.value }).html : ''))

async function load() {
  status.value = 'loading'
  try {
    page.value = await api.page(path.value)
    document.title = `역사: ${page.value.title} - Pagebound`
    revisions.value = await api.revisions(page.value.id)
    status.value = 'ok'
    await showQuery()
  } catch (e) {
    status.value = e instanceof ApiError && e.status === 404 ? 'missing' : 'error'
  }
}

async function showQuery() {
  diff.value = null
  view.value = null
  expanded.value = new Set()
  const q = route.query
  if (q.v) {
    view.value = await api.revision(page.value.id, Number(q.v))
  } else if (q.to) {
    to.value = Number(q.to)
    from.value = Number(q.from ?? 0)
    diff.value = await api.diff(page.value.id, from.value, to.value)
  } else if (revisions.value.length) {
    to.value = latest.value
    from.value = revisions.value[1]?.version ?? 0
  }
}

function compare(f, t) {
  router.push({ query: { from: f, to: t } })
}

async function restore(version) {
  if (!confirm(`v${version} 내용으로 복원할까요? 새 버전으로 저장됩니다.`)) return
  await api.restoreRevision(page.value.id, version)
  router.push('/w/' + encodePath(page.value.path))
}

function expand(key) {
  expanded.value = new Set([...expanded.value, key])
}

watch(path, load, { immediate: true })
watch(() => route.query, () => { if (status.value === 'ok') showQuery() })
</script>

<template>
  <article class="doc-card history">
    <p v-if="status === 'loading'" class="muted">불러오는 중…</p>
    <p v-else-if="status === 'missing'">문서가 없습니다.</p>
    <p v-else-if="status === 'error'">이력을 불러오지 못했습니다.</p>
    <template v-else>
      <div class="history-head">
        <h1 class="doc-title">역사: {{ page.title }}</h1>
        <div class="doc-actions">
          <router-link class="btn" :to="'/blame/' + encodePath(page.path)">행별 이력</router-link>
          <router-link class="btn" :to="'/w/' + encodePath(page.path)">문서로</router-link>
        </div>
      </div>

      <section v-if="diff" class="history-diff">
        <p>
          <strong>v{{ diff.from }} → v{{ diff.to }}</strong>
          <router-link class="btn" :to="{ query: {} }">목록</router-link>
        </p>
        <table class="diff-table">
          <colgroup><col class="diff-no-col"><col><col class="diff-no-col"><col></colgroup>
          <tbody>
            <template v-for="b in blocks" :key="b.key">
              <tr v-if="b.hidden" class="diff-gap">
                <td colspan="4"><button type="button" class="link-like" @click="expand(b.key)">… {{ b.hidden }}줄 같음 (펼치기)</button></td>
              </tr>
              <tr v-for="r in b.rows" v-else :key="b.key + '-' + (r.oldLine ?? '') + '-' + (r.newLine ?? '')" :class="'diff-' + r.type">
                <td class="diff-no">{{ r.oldLine ?? '' }}</td>
                <td class="diff-old">{{ r.oldText ?? '' }}</td>
                <td class="diff-no">{{ r.newLine ?? '' }}</td>
                <td class="diff-new">{{ r.newText ?? '' }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </section>

      <section v-else-if="view" class="history-view">
        <p>
          <strong>v{{ view.version }}</strong> · {{ view.title }} · {{ formatDate(view.createdAt) }}
          <button type="button" @click="showSource = !showSource">{{ showSource ? '렌더링 보기' : '원문 보기' }}</button>
          <button v-if="canEdit() && view.version !== latest" type="button" @click="restore(view.version)">이 버전으로 복원</button>
          <router-link class="btn" :to="{ query: {} }">목록</router-link>
        </p>
        <pre v-if="showSource" class="history-source">{{ view.content }}</pre>
        <div v-else class="doc-body" v-html="rendered" />
      </section>

      <section v-else>
        <p class="history-compare">
          <label>비교: v<select v-model.number="from"><option :value="0">0 (빈 문서)</option>
            <option v-for="r in revisions" :key="r.version" :value="r.version">{{ r.version }}</option></select></label>
          → v<select v-model.number="to"><option v-for="r in revisions" :key="r.version" :value="r.version">{{ r.version }}</option></select>
          <button type="button" class="primary" :disabled="from === to" @click="compare(from, to)">비교</button>
        </p>
        <table class="recent-table">
          <thead><tr><th>버전</th><th>편집자</th><th>출처</th><th>요약</th><th>변경량</th><th>시각</th><th /></tr></thead>
          <tbody>
            <tr v-for="r in revisions" :key="r.version">
              <td><router-link :to="{ query: { v: r.version } }">v{{ r.version }}</router-link>
                <span v-if="r.action !== 'updated'" class="muted"> {{ r.action }}</span></td>
              <td>{{ r.author ?? '-' }}</td>
              <td><span class="badge" :class="'src-' + r.source">{{ SOURCE_LABELS[r.source] ?? r.source }}{{ r.tokenName ? ' · ' + r.tokenName : '' }}</span></td>
              <td>{{ r.summary ?? '' }}</td>
              <td :class="r.deltaBytes >= 0 ? 'plus' : 'minus'">{{ r.deltaBytes >= 0 ? '+' : '' }}{{ r.deltaBytes }}</td>
              <td class="muted">{{ formatDate(r.createdAt) }}</td>
              <td class="history-actions">
                <button v-if="r.version > 1" type="button" @click="compare(r.version - 1, r.version)">이전과 비교</button>
                <button v-if="canEdit() && r.version !== latest" type="button" @click="restore(r.version)">복원</button>
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </article>
</template>
