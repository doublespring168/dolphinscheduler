/*
 * @Author: darcy.zhang , tech.darcy.zhang@outlook.com
 * @Date: 2026-06-08 14:40:08
 * @LastEditors: darcy.zhang , tech.darcy.zhang@outlook.com
 * @LastEditTime: 2026-06-15 16:48:58
 * @FilePath: /gyyun-ui/src/layouts/content/components/logo/index.tsx
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

import { defineComponent } from 'vue'
import styles from './index.module.scss'

const Logo = defineComponent({
  name: 'Logo',
  render() {
    return <div class={styles.logo}>GYYun 调度中枢</div>
  }
})

export default Logo
