<script setup lang="ts">
defineProps<{ name: string }>()

const paths: Record<string, string> = {
  events: 'M4 4h16v16H4z M8 8h8 M8 12h8 M8 16h5',
  risk: 'M12 3 3 7v5c0 5 9 9 9 9s9-4 9-9V7z M12 8v5 M12 16h.01',
  intake: 'M14 3H5v18h14V8z M14 3v5h5 M8 13h8 M8 17h5',
  accounts: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M17 4a4 4 0 0 1 0 8 M22 21v-2a4 4 0 0 0-3-3.87',
  catalog: 'M3 7h7l2 2h9v11H3z M3 7V4h7l2 3h8v2',
  tools: 'M4 6h16 M4 12h16 M4 18h16 M8 3v6 M16 9v6 M10 15v6',
  archive: 'M4 3h16v4H4z M5 7v14h14V7 M9 12h6 M9 16l2 2 4-4',
  cases: 'M10.3 4.3 2.5 18a2 2 0 0 0 1.7 3h15.6a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0 M12 9v5 M12 17h.01',
  insights: 'M4 3v18h17 M8 16v-5 M13 16V7 M18 16V4',
}
</script>

<template>
  <svg class="nav-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>

<style scoped>
.nav-icon { width: 18px; height: 18px; flex-shrink: 0; }
</style>
