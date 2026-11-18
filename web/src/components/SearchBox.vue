<script setup>
// 헤더 검색창 (spec/features/F11-search.md 2절): `/` 단축키, 제목 자동완성, 정확 일치 바로가기
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'

const router = useRouter()
const route = useRoute()
const input = ref(null)
const text = ref('')
const items = ref([])
const active = ref(-1)
const open = ref(false)
let timer = null
let seq = 0

watch(() => route.query.q, (q) => { if (route.name === 'search') text.value = q ?? '' }, { immediate: true })

watch(text, (value) => {
  clearTimeout(timer)
  const q = value.trim()
  if (!q) {
    items.value = []
    return
  }
  timer = setTimeout(async () => {
    const mine = ++seq
    try {
      const result = await api.suggest(q)
      if (mine === seq) {
        items.value = result
        active.value = result[0]?.exact ? 0 : -1
      }
    } catch {
      items.value = []
    }
  }, 200)
})

function go(item) {
  open.value = false
  input.value.blur()
  router.push('/w/' + encodePath(item.path))
}

function submit() {
  const q = text.value.trim()
  if (!q) return
  if (active.value >= 0 && items.value[active.value]) {
    go(items.value[active.value])
    return
  }
  open.value = false
  input.value.blur()
  router.push({ name: 'search', query: { q } })
}

function onKey(e) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    active.value = Math.min(items.value.length - 1, active.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    active.value = Math.max(-1, active.value - 1)
  } else if (e.key === 'Escape') {
    open.value = false
    input.value.blur()
  }
}

// 목록 클릭(mousedown)이 먼저 처리되도록 닫기를 늦춘다
function onBlur() {
  setTimeout(() => { open.value = false }, 150)
}

// `/` 키로 검색창 포커스 (입력 중일 때는 무시)
function onGlobalKey(e) {
  if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return
  const el = document.activeElement
  if (el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))) return
  e.preventDefault()
  input.value.focus()
}
onMounted(() => window.addEventListener('keydown', onGlobalKey))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKey))
</script>

<template>
  <form class="searchbox" role="search" @submit.prevent="submit">
    <input ref="input" v-model="text" type="search" placeholder="검색 ( / )" autocomplete="off"
           @focus="open = true" @blur="onBlur" @keydown="onKey" />
    <ul v-if="open && items.length" class="suggest">
      <li v-for="(s, i) in items" :key="s.path" :class="{ active: i === active }" @mousedown.prevent="go(s)">
        <span v-if="s.exact" class="go">→ 바로 이동</span>
        <span class="title">{{ s.title }}</span>
        <span class="muted">{{ s.path }}</span>
      </li>
      <li class="all" @mousedown.prevent="active = -1; submit()">‘{{ text.trim() }}’ 전체 검색</li>
    </ul>
  </form>
</template>
