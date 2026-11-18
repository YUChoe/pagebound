<script setup>
import { onMounted, ref } from 'vue'
import { api } from '../api.js'
import { encodePath } from '../markdown/wikiPaths.js'
import { relativeTime } from '../time.js'

const items = ref([])
onMounted(async () => {
  try {
    items.value = await api.recent(10)
  } catch {
    items.value = []
  }
})
</script>

<template>
  <section class="recent-box">
    <h2><router-link to="/recent">최근 변경</router-link></h2>
    <ul>
      <li v-for="r in items" :key="r.path + r.version">
        <router-link :to="'/w/' + encodePath(r.path)" :title="r.path">{{ r.title }}</router-link>
        <span class="muted">{{ relativeTime(r.createdAt) }}</span>
      </li>
    </ul>
  </section>
</template>
