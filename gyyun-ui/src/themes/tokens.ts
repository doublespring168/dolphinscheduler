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

export const alaTokens = {
  fontFamily: "'PingFang SC', 'Microsoft YaHei', Arial, simsun, sans-serif",
  radius: '2px',
  buttonRadius: '4px',
  headerHeight: '3.4rem',
  siderWidth: 300,
  siderCollapsedWidth: 44,
  contentPadding: '8px',

  colorBg: '#F3F7FA',
  colorBgTableTitle: '#FAFAFA',
  colorBorder: '#D8DAE1',
  colorBorderBase: '#dee0e3',
  colorTips: '#B3B5C4',
  colorNavy: '#3D446E',
  colorText: '#1f2329',

  platformBlue: '#1D78FF',
  platformBlueHover: '#448FF6',
  platformBluePressed: '#1458C8',
  platformBlueLight: '#EAF1FE',

  primary: '#409eff',
  primaryHover: '#66b1ff',
  primaryPressed: '#337ecc',
  primaryLight: '#ecf5ff',
  success: '#67c23a',
  successHover: '#85ce61',
  successPressed: '#529b2e',
  warning: '#e6a23c',
  warningHover: '#ebb563',
  warningPressed: '#b88230',
  danger: '#f56c6c',
  dangerHover: '#f78989',
  dangerPressed: '#c45656',
  info: '#909399',
  infoHover: '#a6a9ad',
  infoPressed: '#73767a'
}

export const buildAlaTheme = (isDark = false) => {
  const baseColor = isDark ? '#141414' : '#ffffff'
  const bodyColor = isDark ? '#141414' : alaTokens.colorBg
  const cardColor = isDark ? '#1f1f1f' : '#ffffff'
  const hoverColor = isDark ? '#242b3a' : alaTokens.platformBlueLight
  const borderColor = isDark ? '#333846' : alaTokens.colorBorder
  const textColor = isDark ? '#e5e7eb' : alaTokens.colorNavy
  const textColorSecondary = isDark ? '#c7ccd8' : alaTokens.colorNavy

  return {
    common: {
      baseColor,
      bodyColor,
      cardColor,
      modalColor: cardColor,
      popoverColor: cardColor,
      tableColor: cardColor,
      tableHeaderColor: isDark ? '#242b3a' : alaTokens.colorBgTableTitle,
      hoverColor,
      borderColor,
      dividerColor: borderColor,
      fontFamily: alaTokens.fontFamily,
      fontSize: '14px',
      fontSizeSmall: '13px',
      fontSizeMedium: '14px',
      fontSizeLarge: '16px',
      borderRadius: alaTokens.radius,
      borderRadiusSmall: alaTokens.radius,
      primaryColor: alaTokens.primary,
      primaryColorHover: alaTokens.primaryHover,
      primaryColorPressed: alaTokens.primaryPressed,
      primaryColorSuppl: alaTokens.primary,
      infoColor: alaTokens.primary,
      infoColorHover: alaTokens.primaryHover,
      infoColorPressed: alaTokens.primaryPressed,
      infoColorSuppl: alaTokens.primary,
      successColor: alaTokens.success,
      successColorHover: alaTokens.successHover,
      successColorPressed: alaTokens.successPressed,
      successColorSuppl: alaTokens.success,
      warningColor: alaTokens.warning,
      warningColorHover: alaTokens.warningHover,
      warningColorPressed: alaTokens.warningPressed,
      warningColorSuppl: alaTokens.warning,
      errorColor: alaTokens.danger,
      errorColorHover: alaTokens.dangerHover,
      errorColorPressed: alaTokens.dangerPressed,
      errorColorSuppl: alaTokens.danger,
      textColor1: textColor,
      textColor2: textColorSecondary,
      textColor3: isDark ? '#8b93a6' : alaTokens.colorTips,
      placeholderColor: isDark ? '#6f7788' : alaTokens.colorTips
    },
    Layout: {
      color: bodyColor,
      colorEmbedded: bodyColor,
      headerColor: cardColor,
      siderColor: cardColor,
      headerBorderColor: borderColor,
      siderBorderColor: borderColor,
      siderToggleBarColor: 'rgba(211, 220, 230, 0.7)',
      siderToggleBarColorHover: alaTokens.platformBlue
    },
    Menu: {
      color: cardColor,
      borderRadius: alaTokens.radius,
      fontSize: '1rem',
      itemHeight: alaTokens.headerHeight,
      itemTextColor: textColor,
      itemTextColorHover: alaTokens.platformBlue,
      itemTextColorActive: alaTokens.platformBlue,
      itemTextColorActiveHover: alaTokens.platformBlue,
      itemTextColorChildActive: alaTokens.platformBlue,
      itemTextColorChildActiveHover: alaTokens.platformBlue,
      itemIconColor: textColor,
      itemIconColorHover: alaTokens.platformBlue,
      itemIconColorActive: alaTokens.platformBlue,
      itemIconColorActiveHover: alaTokens.platformBlue,
      itemIconColorChildActive: alaTokens.platformBlue,
      itemIconColorChildActiveHover: alaTokens.platformBlue,
      itemColorHover: hoverColor,
      itemColorActive: hoverColor,
      itemColorActiveHover: hoverColor,
      itemColorActiveCollapsed: hoverColor,
      arrowColor: textColor,
      arrowColorHover: alaTokens.platformBlue,
      arrowColorActive: alaTokens.platformBlue,
      arrowColorActiveHover: alaTokens.platformBlue,
      arrowColorChildActive: alaTokens.platformBlue,
      arrowColorChildActiveHover: alaTokens.platformBlue,
      borderColorHorizontal: 'transparent',
      dividerColor: borderColor
    },
    Button: {
      heightTiny: '24px',
      heightSmall: '32px',
      heightMedium: '32px',
      heightLarge: '40px',
      fontSizeTiny: '12px',
      fontSizeSmall: '14px',
      fontSizeMedium: '14px',
      fontSizeLarge: '14px',
      borderRadiusTiny: '3px',
      borderRadiusSmall: alaTokens.buttonRadius,
      borderRadiusMedium: alaTokens.buttonRadius,
      borderRadiusLarge: alaTokens.buttonRadius,
      fontWeight: '400',
      colorPrimary: alaTokens.primary,
      colorHoverPrimary: alaTokens.primaryHover,
      colorPressedPrimary: alaTokens.primaryPressed,
      colorFocusPrimary: alaTokens.primary,
      colorInfo: alaTokens.primary,
      colorHoverInfo: alaTokens.primaryHover,
      colorPressedInfo: alaTokens.primaryPressed,
      colorFocusInfo: alaTokens.primary,
      colorSuccess: alaTokens.success,
      colorHoverSuccess: alaTokens.successHover,
      colorPressedSuccess: alaTokens.successPressed,
      colorWarning: alaTokens.warning,
      colorHoverWarning: alaTokens.warningHover,
      colorPressedWarning: alaTokens.warningPressed,
      colorError: alaTokens.danger,
      colorHoverError: alaTokens.dangerHover,
      colorPressedError: alaTokens.dangerPressed,
      paddingSmall: '12px',
      paddingMedium: '12px',
      paddingLarge: '16px'
    },
    Input: {
      heightSmall: '32px',
      heightMedium: '32px',
      heightLarge: '40px',
      fontSizeSmall: '14px',
      fontSizeMedium: '14px',
      fontSizeLarge: '14px',
      borderRadius: alaTokens.radius,
      textColor,
      border: `1px solid ${borderColor}`,
      borderHover: `1px solid ${alaTokens.primary}`,
      borderFocus: `1px solid ${alaTokens.primary}`,
      boxShadowFocus: `0 0 0 1px ${alaTokens.primary} inset`,
      caretColor: alaTokens.primary,
      placeholderColor: isDark ? '#6f7788' : alaTokens.colorTips
    },
    Card: {
      color: cardColor,
      colorModal: cardColor,
      colorPopover: cardColor,
      colorTarget: cardColor,
      textColor,
      titleTextColor: textColor,
      borderColor,
      borderRadius: alaTokens.radius,
      fontSizeSmall: '14px',
      fontSizeMedium: '14px',
      titleFontSizeSmall: '14px',
      titleFontSizeMedium: '14px',
      titleFontWeight: '600',
      paddingSmall: '12px 16px',
      paddingMedium: '12px 16px'
    },
    DataTable: {
      borderRadius: alaTokens.radius,
      fontSizeSmall: '14px',
      fontSizeMedium: '14px',
      borderColor,
      thColor: isDark ? '#242b3a' : alaTokens.colorBgTableTitle,
      thColorHover: isDark ? '#2b3448' : alaTokens.colorBgTableTitle,
      thTextColor: textColor,
      thFontWeight: '500',
      tdTextColor: textColor,
      tdColorHover: hoverColor,
      tdColorStriped: isDark ? '#1b2130' : '#fafcff',
      thPaddingSmall: '8px 12px',
      tdPaddingSmall: '8px 12px'
    },
    Form: {
      labelTextColor: textColor,
      labelFontSizeTopSmall: '14px',
      labelFontSizeTopMedium: '14px',
      labelFontSizeLeftSmall: '14px',
      labelFontSizeLeftMedium: '14px',
      labelHeightSmall: '32px',
      labelHeightMedium: '32px',
      blankHeightSmall: '32px',
      blankHeightMedium: '32px'
    },
    Pagination: {
      itemBorderRadius: alaTokens.radius,
      itemColor: '#ffffff',
      buttonColor: '#ffffff',
      itemColorActive: alaTokens.platformBlueLight,
      itemTextColorActive: alaTokens.platformBlue,
      itemBorderActive: `1px solid ${alaTokens.platformBlue}`,
      itemSizeSmall: '32px',
      itemSizeMedium: '32px'
    },
    Dropdown: {
      borderRadius: alaTokens.radius,
      optionHeightSmall: '32px',
      optionHeightMedium: '32px',
      optionTextColor: textColor,
      optionTextColorActive: alaTokens.platformBlue,
      optionColorPending: hoverColor
    },
    Select: {
      peers: {
        InternalSelection: {
          heightSmall: '32px',
          heightMedium: '32px',
          borderRadius: alaTokens.radius,
          borderActive: `1px solid ${alaTokens.primary}`,
          borderFocus: `1px solid ${alaTokens.primary}`,
          boxShadowFocus: `0 0 0 1px ${alaTokens.primary} inset`
        },
        InternalSelectMenu: {
          borderRadius: alaTokens.radius,
          optionColorPending: hoverColor,
          optionColorActive: hoverColor,
          optionTextColorActive: alaTokens.platformBlue
        }
      }
    }
  }
}
