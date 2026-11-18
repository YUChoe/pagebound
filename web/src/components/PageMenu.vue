<script setup>
// 문서 제목 옆 메뉴 (spec/features/F5-page-view.md 1·2.1절)
// - 🔗 영구 링크 복사 + ▾ 형식 선택(URL / 마크다운 / 위키 링크, 마지막 선택 기억), 단축키 y
// - ⋮ 메뉴: 역링크, 원문(.md)
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { copyText, load, save } from '../enhance/storage.js'
import { permalinkUrl } from '../permalink.js'

const props = defineProps({ page: { type: Object, required: true } })

const FORMATS = { url: 'URL', markdown: '마크다운 [제목](URL)', wiki: '위키 링크 [[경로]]' }
const format = ref(load('pb.linkFormat', 'url'))
const copied = ref(false)
const formatOpen = ref(false)
const menuOpen = ref(false)
const backlinks = ref(null)

function text(kind) {
  const url = permalinkUrl(props.page)
  if (kind === 'markdown') return `[${props.page.title}](${url})`
  if (kind === 'wiki') return `[[${props.page.path}]]`
  return url
}

async function copy(kind = format.value) {
  format.value = kind
  save('pb.linkFormat', kind)
  formatOpen.value = false
  await copyText(text(kind))
  copied.value = true
  setTimeout(() => { copied.value = false }, 1500)
}

async function openMenu() {
  menuOpen.value = !menuOpen.value
  if (menuOpen.value && !backlinks.value) backlinks.value = await api.backlinks(props.page.id)
}

function onKey(e) {
  if (e.key !== 'y' || e.ctrlKey || e.metaKey || e.altKey) return
  const t = e.target
  if (t instanceof HTMLElement && (t.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName))) return
  copy()
}

function closeAll(e) {
  if (!e.target.closest?.('.page-menu')) {
    formatOpen.value = false
    menuOpen.value = false
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  document.addEventListener('click', closeAll)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('click', closeAll)
})
</script>

<template>
  <span class="page-menu">
    <span class="split-btn">
      <button type="button" class="permalink-btn" :title="`영구 링크 복사 (${FORMATS[format]}) · 단축키 y`" @click="copy()">
        {{ copied ? '복사됨' : '🔗 링크 복사' }}
      </button><button type="button" class="split-toggle" title="복사 형식" @click="formatOpen = !formatOpen">▾</button>
      <ul v-if="formatOpen" class="dropdown">
        <li v-for="(label, k) in FORMATS" :key="k"><button type="button" :class="{ active: k === format }" @click="copy(k)">{{ label }}</button></li>
      </ul>
    </span>
    <span class="split-btn">
      <button type="button" title="더 보기" @click="openMenu">⋮</button>
      <div v-if="menuOpen" class="dropdown menu-panel">
        <router-link :to="'/source/' + encodePath(page.path)">원문 보기</router-link> ·
        <a :href="'/w/' + encodePath(page.path) + '.md'" target="_blank" rel="noopener">.md</a>
        <h4>역링크 <span class="muted">{{ backlinks?.length ?? '' }}</span></h4>
        <p v-if="backlinks && !backlinks.length" class="muted">이 문서를 참조하는 문서가 없습니다.</p>
        <ul v-else>
          <li v-for="b in backlinks" :key="b.path"><router-link :to="'/w/' + encodePath(b.path)">{{ b.title }}</router-link>
            <span class="muted"> {{ b.path }}</span></li>
        </ul>
      </div>
    </span>
  </span>
</template>
