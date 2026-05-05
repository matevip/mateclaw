<template>
  <el-config-provider :locale="elementLocale">
    <router-view />
    <!-- Mounted once at the app root so mcConfirm() can pop a dialog
         from anywhere without each caller wiring its own host. -->
    <McConfirmHost />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { useI18n } from 'vue-i18n'
import en from 'element-plus/es/locale/lang/en'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { currentLocale } from '@/i18n'
import { useThemeStore } from '@/stores/useThemeStore'
import McConfirmHost from '@/components/common/McConfirmHost.vue'

// Initialize theme — applies .dark class to <html> immediately
useThemeStore()

const { t } = useI18n()

watchEffect(() => {
  document.title = t('app.title')
})

const elementLocale = computed(() => (currentLocale.value === 'en-US' ? en : zhCn))
</script>
