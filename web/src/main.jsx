import React from 'react'
import ReactDOM from 'react-dom/client'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { BRAND, CLAY, LAYOUT } from './theme'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: BRAND.primary,
          colorInfo: BRAND.primary,
          colorSuccess: CLAY.mint,
          colorWarning: CLAY.mustard,
          colorError: CLAY.coral,
          colorText: CLAY.ink,
          colorTextSecondary: CLAY.inkSoft,
          colorBgLayout: LAYOUT.bgLayout,
          fontFamily:
            "'Nunito', 'Quicksand', -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif",
          borderRadius: 16,
          wireframe: false,
        },
        components: {
          Layout: {
            headerHeight: LAYOUT.headerHeight,
            headerBg: 'transparent',
            bodyBg: LAYOUT.bgLayout,
            siderBg: '#ffffff',
          },
          Menu: {
            itemBg: 'transparent',
            itemColor: CLAY.inkSoft,
            itemSelectedBg: CLAY.purple,
            itemSelectedColor: '#fff',
            itemHoverBg: CLAY.purpleTint,
            itemBorderRadius: 999,
            itemMarginInline: 10,
          },
          Card: {
            borderRadiusLG: 28,
            paddingLG: 24,
          },
          Table: {
            headerBg: CLAY.purpleTint,
            headerColor: CLAY.purple,
          },
          Statistic: {
            titleFontSize: 14,
          },
          Button: {
            borderRadius: 999,
            controlHeightLG: 48,
          },
        },
      }}
    >
      <AntApp>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
)
