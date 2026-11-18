<script setup>
// 분류(태그) 칩 입력 (spec/features/F4-tags.md 2절): Enter/콤마 확정, Backspace 삭제, 자동완성
import { ref, watch } from 'vue'
import { api } from '../../api.js'

const model = defineModel({ type: Array, default: () => [] })
const text = ref('')
const suggestions = ref([])
let timer = null

function commit() {
  const parts = text.value.split(',').map((s) => s.trim().replace(/\s+/g, ' ')).filter(Boolean)
  const lower = new Set(model.value.map((t) => t.toLowerCase()))
  const added = parts.filter((p) => !lower.has(p.toLowerCase()) && lower.add(p.toLowerCase()))
  if (added.length) model.value = [...model.value, ...added]
  text.value = ''
}

function onKey(e) {
  if (e.key === 'Enter' || e.key === ',') {
    e.preventDefault()
    commit()
  } else if (e.key === 'Backspace' && !text.value && model.value.length) {
    model.value = model.value.slice(0, -1)
  }
}

function remove(tag) {
  model.value = model.value.filter((t) => t !== tag)
}

watch(text, (value) => {
  clearTimeout(timer)
  if (!value.trim()) return
  timer = setTimeout(async () => {
    try {
      suggestions.value = await api.tags(value.trim())
    } catch {
      suggestions.value = []
    }
  }, 200)
})
</script>

<template>
  <div class="tag-input">
    <span class="tag-label">분류</span>
    <span v-for="t in model" :key="t" class="chip">{{ t }}<button type="button" @click="remove(t)">×</button></span>
    <input v-model="text" list="tag-suggestions" placeholder="태그 입력 후 Enter" @keydown="onKey" @blur="commit" />
    <datalist id="tag-suggestions">
      <option v-for="s in suggestions" :key="s.name" :value="s.name">{{ s.count }}</option>
    </datalist>
  </div>
</template>
