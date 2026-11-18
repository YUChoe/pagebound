<script setup>
import { ref } from 'vue'

defineProps({ sections: { type: Array, required: true } })
const open = ref(true)

function go(id) {
  document.getElementById(id)?.scrollIntoView()
  history.replaceState(history.state, '', '#' + encodeURIComponent(id))
}
</script>

<template>
  <nav class="toc-box">
    <button type="button" class="toc-head" @click="open = !open">목차 <span>{{ open ? '▾' : '▸' }}</span></button>
    <ol v-if="open" class="toc-list">
      <li v-for="s in sections" :key="s.index" :style="{ paddingLeft: s.depth * 1.2 + 'em' }">
        <a :href="'#' + s.id" @click.prevent="go(s.id)"><span class="toc-num">{{ s.number }}</span> {{ s.title }}</a>
      </li>
    </ol>
  </nav>
</template>
