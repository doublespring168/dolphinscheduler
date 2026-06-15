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

import {
  defineComponent,
  getCurrentInstance,
  onMounted,
  onUnmounted,
  ref,
  toRefs,
  withKeys
} from 'vue'
import styles from './index.module.scss'
import {
  NInput,
  NButton,
  NSwitch,
  NForm,
  NFormItem,
  useMessage,
  NSpace,
  NDivider,
  NImage
} from 'naive-ui'
import { useForm } from './use-form'
import { useTranslate } from './use-translate'
import { useLogin } from './use-login'
import { useLocalesStore } from '@/store/locales/locales'
import { useThemeStore } from '@/store/theme/theme'
import cookies from 'js-cookie'
import { ssoLoginUrl } from '@/service/modules/login'
import type {
  OAuth2Provider,
  OidcProvider
} from '@/service/modules/login/types'

const websites = [
  { name: '中文站', url: 'gyyun.com', flag: 'cn' },
  { name: '英语站', url: 'us.gyyun.com', flag: 'en' },
]

const formatDateTime = (date: Date) => {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate()
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

const login = defineComponent({
  name: 'login',
  setup() {
    window.$message = useMessage()
    const { state, t, locale } = useForm()
    const { handleChange } = useTranslate(locale)
    const {
      handleLogin,
      handleGetOAuth2Provider,
      handleGetOidcProviders,
      oauth2Providers,
      oidcProviders,
      gotoOAuth2Page,
      handleRedirect
    } = useLogin(state)
    const localesStore = useLocalesStore()
    const themeStore = useThemeStore()

    if (themeStore.getTheme) {
      themeStore.setDarkTheme()
    }

    const trim = getCurrentInstance()?.appContext.config.globalProperties.trim
    const currentTime = ref(formatDateTime(new Date()))
    let timer: number | undefined

    cookies.set('language', localesStore.getLocales, { path: '/' })

    onMounted(() => {
      timer = window.setInterval(() => {
        currentTime.value = formatDateTime(new Date())
      }, 1000)
    })

    onUnmounted(() => {
      if (timer) {
        window.clearInterval(timer)
      }
    })

    onMounted(async () => {
      const ssoLoginUrlRes = await ssoLoginUrl()
      state.loginForm.ssoLoginUrl = ssoLoginUrlRes
      if (state.loginForm.ssoLoginUrl) {
        const url = new URL(window.location.href)
        const ssoState = url.searchParams.get('state')
        const ssoCode = url.searchParams.get('code')
        if (ssoState && ssoCode) {
          state.loginForm.userName = ssoState
          state.loginForm.userPassword = ssoCode
          handleLogin()
        }
      } else {
        state.loginForm.ssoLoginUrl = ''
      }
      handleRedirect()
    })

    handleGetOAuth2Provider()
    handleGetOidcProviders()
    return {
      t,
      handleChange,
      handleLogin,
      ...toRefs(state),
      localesStore,
      trim,
      currentTime,
      oauth2Providers,
      oidcProviders,
      gotoOAuth2Page
    }
  },
  render() {
    return (
      <div class={styles.container}>
        <header class={styles['top-panel']}>
          <div class={styles.websites}>
            {websites.map((item) => (
              <div class={styles.website} key={item.url}>
                <div class={styles['website-icon']}>
                  <img
                    src={`${import.meta.env.BASE_URL}images/flags/${item.flag}.svg`}
                    alt=''
                    aria-hidden='true'
                  />
                </div>
                <div>
                  <span class={styles['website-name']}>{item.name}</span>
                  <a
                    class={styles.url}
                    target='_blank'
                    rel='noreferrer'
                    href={`https://${item.url}`}
                  >
                    {item.url}
                  </a>
                </div>
              </div>
            ))}
          </div>
          <div class={styles['company-info']}>
            <span>深圳市谷雨云科技有限公司</span>
            <span>( GYYun Technology Co.,Ltd. )</span>
          </div>
          <div class={styles['top-actions']}>
            <span class={styles['current-date-time']}>当前时间：{this.currentTime}</span>
            <span class={styles['phone-number']}>400-800-9202</span>
            <NSwitch
              onUpdateValue={this.handleChange}
              default-value={this.localesStore.getLocales}
              checked-value='en_US'
              unchecked-value='zh_CN'
            >
              {{
                checked: () => 'en_US',
                unchecked: () => 'zh_CN'
              }}
            </NSwitch>
          </div>
        </header>

        <main class={styles['login-layout']}>
          <div class={styles.title}>
            <h1>GYYun 调度中枢</h1>
          </div>
          <section class={styles['section-left']}>
            <h2>领先的作业调度与数据处理中枢平台。</h2>
            <p>
              GYYun 调度中枢面向企业级数据处理、业务批量作业、实时任务、离线计算和跨系统流程协同场景，提供统一的任务编排、依赖管理、调度执行、资源管控、运行监控、告警通知、日志追踪和权限治理能力。平台能够帮助研发、数据、运维和业务团队在同一工作台中管理作业全生命周期，从任务创建、参数配置、发布上线、周期运行到异常恢复形成闭环，减少分散脚本、人工巡检和重复运维带来的不确定性。通过标准化的作业模型、可视化的运行视图和稳定的调度能力，企业可以更清晰地掌握核心任务状态，更快速地定位问题，更可靠地保障关键链路按时完成。平台坚持安全、稳定、高效、可扩展的建设理念，支持多项目、多环境、多角色协同使用，持续降低系统建设成本和日常运维复杂度，为企业数字化运营提供可信赖的作业中枢支撑。
            </p>
          </section>

          <section class={styles['section-right']}>
            <div class={styles['form-stack']}>
              <div class={styles['form-background-2']} />
              <div class={styles['form-background-1']} />
              <div class={styles['login-model']}>
                <div class={styles.logo}>
                  <div class={styles['logo-text']}>GYYun 调度中枢</div>
                  <h3>欢迎回来</h3>
                </div>
                <div
                  class={styles['form-model']}
                  v-show={this.loginForm.ssoLoginUrl.length === 0}
                >
                  <NForm rules={this.rules} ref='loginFormRef'>
                    <NFormItem
                      label={this.t('login.userName')}
                      label-style={{ color: '#333333' }}
                      path='userName'
                    >
                      <NInput
                        allowInput={this.trim}
                        class='input-user-name'
                        type='text'
                        size='large'
                        v-model={[this.loginForm.userName, 'value']}
                        placeholder={this.t('login.userName_tips')}
                        autofocus
                        onKeydown={withKeys(this.handleLogin, ['enter'])}
                      />
                    </NFormItem>
                    <NFormItem
                      label={this.t('login.userPassword')}
                      label-style={{ color: '#333333' }}
                      path='userPassword'
                    >
                      <NInput
                        allowInput={this.trim}
                        class='input-password'
                        type='password'
                        size='large'
                        v-model={[this.loginForm.userPassword, 'value']}
                        placeholder={this.t('login.userPassword_tips')}
                        onKeydown={withKeys(this.handleLogin, ['enter'])}
                      />
                    </NFormItem>
                  </NForm>
                  <NButton
                    class='btn-login'
                    round
                    type='info'
                    disabled={
                      !this.loginForm.userName || !this.loginForm.userPassword
                    }
                    style={{ width: '100%' }}
                    onClick={this.handleLogin}
                  >
                    {this.t('login.login')}
                  </NButton>
                </div>
                <div
                  class={styles['form-model']}
                  v-show={this.loginForm.ssoLoginUrl.length !== 0}
                >
                  <a
                    href={this.loginForm.ssoLoginUrl}
                    style='text-decoration:none'
                  >
                    <NButton
                      class='btn-login-sso'
                      round
                      type='info'
                      style={{ width: '100%', marginTop: '30px' }}
                      onClick={this.handleLogin}
                    >
                      {this.t('login.ssoLogin')}
                    </NButton>
                  </a>
                </div>
                {(this.oauth2Providers.length > 0 ||
                  this.oidcProviders.length > 0) && (
                  <NDivider>{this.t('login.loginWithOAuth2')}</NDivider>
                )}

                <NSpace class={styles['oauth2-provider']} justify='center'>
                  {this.oauth2Providers?.map((e: OAuth2Provider) => {
                    return e.iconUri ? (
                      <div onClick={() => this.gotoOAuth2Page(e)}>
                        <NImage
                          preview-disabled
                          width='30'
                          src={e.iconUri}
                        ></NImage>{' '}
                      </div>
                    ) : (
                      <NButton onClick={() => this.gotoOAuth2Page(e)}>
                        {e.provider}
                      </NButton>
                    )
                  })}
                  {this.oidcProviders?.map((e: OidcProvider) => {
                    const authUrl = `/gyyun/oauth2/authorization/${e.id}`
                    return (
                      <a href={authUrl} class={styles['oidc-provider-link']}>
                        <NButton block class={styles['oidc-provider-btn']}>
                          <div class={styles['oidc-btn-content']}>
                            {e.iconUri && (
                              <img
                                src={e.iconUri}
                                class={styles['oidc-btn-icon']}
                              />
                            )}
                            <span>{e.displayName}</span>
                          </div>
                        </NButton>
                      </a>
                    )
                  })}
                </NSpace>
              </div>
            </div>
          </section>
        </main>

        <footer class={styles.bottom}>
          <p class={styles['remark-cn']}>
            深圳市谷雨云科技有限公司致力于构建透明、公开的商业合作环境，以尊重并保护合作伙伴和自身共同利益。为此，公司也希望与合作伙伴共同遵守所有适用的法律法规，包括联合国安理会、中国、美国、欧盟等，以上感谢。
          </p>
          <p class={styles['remark-en']}>
            GYYun is committed to building an open, transparent business
            community. We value and aim to protect mutual interests of both
            cooperative partners and GYYun .To this end, GYYun works together
            with cooperative partners to comply with all applicable laws and
            regulations of the United Nations Security Council, China, United
            States, and the European Union, Thanks.
          </p>
          <p class={styles.copyright}>
            @Copyright 2025~{new Date().getFullYear()} 深圳市谷雨云科技有限公司
          </p>
        </footer>
      </div>
    )
  }
})

export default login
