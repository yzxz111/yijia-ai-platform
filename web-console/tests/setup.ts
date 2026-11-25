import { config } from '@vue/test-utils'
import { afterEach } from 'vitest'
import { ElMessage } from 'element-plus'

import TopErrorMessage from '../src/components/feedback/TopErrorMessage.vue'

config.global.components.TopErrorMessage = TopErrorMessage

afterEach(() => {
  ElMessage.closeAll()
  document.querySelectorAll('.el-message').forEach((message) => message.remove())
})
