<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageToc from './PageToc.vue'
import { api, ApiError } from '../api.js'
import { routePath } from '../router.js'
import { renderMarkdown } from '../markdown/render.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { enhanceSections } from '../enhance/sections.js'
import { enhanceCodeBlocks } from '../enhance/codeBlocks.js'
import { enhanceImages, enhanceMermaid } from '../enhance/media.js'
import { load, save } from '../enhance/storage.js'
import { formatDate } from '../time.js'

const route = useRoute()
const router = useRouter()
const page = ref(null)
const status = ref('loading') // loading | ok | missing | login | error
const body = ref(null)
const codeCount = ref(0)
const wide = ref(false)
const showNumbers = ref(load('pb.hnum', true) !== false)
const lightbox = ref(null)
let code = null
let mermaidObserver = null

const rendered = computed(() => (page.value
  ? renderMarkdown(page.value.content, { path: page.value.path, links: page.value.links })
  : { html: '', sections: [] }))

async function loadPage() {
  const path = routePath(route)
  status.value = 'loading'
  try {
    page.value = await api.page(path)
    status.value = 'ok'
    wide.value = load(`pb.wide.${page.value.id}`, false) === true
    document.title = `${page.value.title} - Pagebound`
  } catch (e) {
    page.value = null
    if (e instanceof ApiError && e.status === 404 && e.body?.redirect) {
      router.replace('/w/' + encodePath(e.body.redirect) + route.hash)
      return
    }
    status.value = e instanceof ApiError && e.status === 404 ? 'missing'
      : e instanceof ApiError && e.status === 401 ? 'login' : 'error'
  }
}

function cleanup() {
  code?.disconnect()
  mermaidObserver?.disconnect()
  code = null
  mermaidObserver = null
}

async function enhance() {
  cleanup()
  await nextTick()
  const el = body.value
  if (!el || !page.value) return
  const t0 = performance.now()
  el.innerHTML = rendered.value.html
  enhanceSections(el)
  code = enhanceCodeBlocks(el, page.value.id)
  codeCount.value = code.count
  mermaidObserver = enhanceMermaid(el)
  enhanceImages(el, (src, alt) => { lightbox.value = { src, alt } })
  // 성능 측정점 (spec/features/F5-page-view.md 4절): DOM 삽입 + 보강 소요 시간
  performance.measure('pb:enhance', { start: t0 })
  if (route.hash) document.getElementById(decodeURIComponent(route.hash.substring(1)))?.scrollIntoView()
}

// 본문 내부 링크는 SPA 라우팅
function onBodyClick(e) {
  const a = e.target.closest('a')
  if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return
  const href = a.getAttribute('href') ?? ''
  if (href.startsWith('/w/') || href.startsWith('#')) {
    e.preventDefault()
    if (href.startsWith('#')) document.getElementById(decodeURIComponent(href.substring(1)))?.scrollIntoView()
    else router.push(href)
  }
}

function toggleWide() {
  wide.value = !wide.value
  save(`pb.wide.${page.value.id}`, wide.value)
}

function toggleNumbers() {
  showNumbers.value = !showNumbers.value
  save('pb.hnum', showNumbers.value)
}

watch(() => routePath(route), loadPage, { immediate: true })
watch(rendered, enhance)
onBeforeUnmount(cleanup)
</script>

<template>
  <div v-if="status === 'loading'" class="muted pad">불러오는 중…</div>
  <div v-else-if="status === 'missing'" class="doc-card">
    <h1 class="doc-title">{{ routePath(route) }}</h1>
    <p>문서가 없습니다. (새 문서 작성은 M3에서 지원)</p>
  </div>
  <div v-else-if="status === 'login'" class="doc-card">
    <p>로그인이 필요합니다. <router-link :to="{ name: 'login', query: { next: route.fullPath } }">로그인</router-link></p>
  </div>
  <div v-else-if="status === 'error'" class="doc-card"><p>문서를 불러오지 못했습니다.</p></div>
  <article v-else class="doc-card" :class="{ wide, 'hide-hnum': !showNumbers }">
    <header class="doc-head">
      <div class="doc-title-row">
        <h1 class="doc-title">{{ page.title }}</h1>
        <div class="doc-actions">
          <button v-if="codeCount" type="button" @click="code?.setAll(true)">코드 모두 접기</button>
          <button v-if="codeCount" type="button" @click="code?.setAll(false)">코드 모두 펼치기</button>
          <button type="button" @click="toggleNumbers">{{ showNumbers ? '번호 숨기기' : '번호 보이기' }}</button>
          <button type="button" @click="toggleWide">{{ wide ? '기본 폭' : '넓게 보기' }}</button>
          <a :href="'/w/' + encodePath(page.path) + '.md'" target="_blank" rel="noopener">원문</a>
          <a :href="'/p/' + page.id" title="영구 링크">#{{ page.id }}</a>
        </div>
      </div>
      <p class="doc-meta muted">
        최근 수정 {{ formatDate(page.updatedAt) }}<template v-if="page.updatedBy"> · {{ page.updatedBy }}</template>
        · v{{ page.version }}
      </p>
      <nav v-if="page.breadcrumbs.length" class="crumbs">
        상위 문서:
        <template v-for="(c, i) in page.breadcrumbs" :key="c.path">
          <span v-if="i" class="sep">›</span>
          <router-link :to="'/w/' + encodePath(c.path)">{{ c.title }}</router-link>
        </template>
      </nav>
      <div v-if="page.tags.length" class="category-bar">
        분류:
        <span v-for="(t, i) in page.tags" :key="t"><span v-if="i" class="sep">|</span> {{ t }}</span>
      </div>
    </header>

    <PageToc v-if="rendered.sections.length > 1" :sections="rendered.sections" />

    <div ref="body" class="markdown-body" @click="onBodyClick" />

    <section v-if="!page.content.trim() && page.children.length" class="child-cards">
      <router-link v-for="c in page.children" :key="c.path" class="child-card" :to="'/w/' + encodePath(c.path)">
        {{ c.title }}
      </router-link>
    </section>

    <section v-if="page.attachments.length" class="attachments">
      <h2>첨부 파일</h2>
      <ul>
        <li v-for="a in page.attachments" :key="a.filename">
          <a :href="a.url" target="_blank" rel="noopener">{{ a.filename }}</a>
          <span class="muted">{{ (a.size / 1024).toFixed(1) }} KB</span>
        </li>
      </ul>
    </section>

    <section v-if="page.content.trim() && page.children.length" class="children">
      <h2>하위 문서</h2>
      <ul>
        <li v-for="c in page.children" :key="c.path">
          <router-link :to="'/w/' + encodePath(c.path)">{{ c.title }}</router-link>
        </li>
      </ul>
    </section>
  </article>

  <div v-if="lightbox" class="lightbox" @click="lightbox = null">
    <img :src="lightbox.src" :alt="lightbox.alt" />
  </div>
</template>
