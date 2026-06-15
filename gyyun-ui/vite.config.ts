/*
 * @Author: darcy.zhang , tech.darcy.zhang@outlook.com
 * @Date: 2026-06-08 19:14:37
 * @LastEditors: darcy.zhang , tech.darcy.zhang@outlook.com
 * @LastEditTime: 2026-06-15 16:17:14
 * @FilePath: /gyyun-ui/vite.config.ts
 * @Description: 
 * 
 * Copyright (c) 2026 by 【 tech.darcy.zhang@outlook.com 】, All Rights Reserved. 
 */
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueJsx from '@vitejs/plugin-vue-jsx'
import viteCompression from 'vite-plugin-compression'
import path from 'path'

export default defineConfig({
  base: process.env.NODE_ENV === 'production' ? '/gyyun/ui/' : '/',
  plugins: [
    vue(),
    vueJsx(),
    viteCompression({
      verbose: true,
      disable: false,
      threshold: 10240,
      algorithm: 'gzip',
      ext: '.gz',
      deleteOriginFile: false
    })
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
      // resolve vue-i18n warning: You are running the esm-bundler build of vue-i18n.
    }
  },
  server: {
    port: 1221,
    proxy: {
      '/gyyun': {
        target: loadEnv('development', './').VITE_APP_DEV_WEB_URL,
        changeOrigin: true
      }
    }
  }
})
