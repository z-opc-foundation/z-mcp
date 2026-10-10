import { HomeOutlined, MonitorOutlined } from '@ant-design/icons'
import HomePage from './HomePage'
import StatusPage from './StatusPage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地，suit 内联形态）。 */
export const appMeta = { title: 'mcp 服务台', short: 'z-mcp' }

export const menuItems = [
    { key: '/z-mcp/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-mcp/status', label: '服务状态', icon: <MonitorOutlined /> },
]

export const routes = [
    { path: '/z-mcp/home', Component: HomePage },
    { path: '/z-mcp/status', Component: StatusPage },
]

export { default as HomePage } from './HomePage'
export { default as LoginPage } from './LoginPage'
