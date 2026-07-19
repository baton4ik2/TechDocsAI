import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { setToken } from '../api'

const navItems = [
  { to: '/', label: 'Главная', icon: '🏠', end: true },
  { to: '/facilities', label: 'Объекты', icon: '🏢' },
  { to: '/normatives', label: 'Нормативы', icon: '📚' },
  { to: '/chat', label: 'Чат с ИИ', icon: '💬' },
]

export default function Layout() {
  const navigate = useNavigate()

  const logout = () => {
    setToken(null)
    navigate('/login')
  }

  return (
    <div className="min-h-screen flex">
      <aside className="w-60 shrink-0 border-r border-slate-200 bg-white flex flex-col">
        <div className="px-5 py-5 border-b border-slate-100">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-primary-600 text-white flex items-center justify-center font-bold">T</div>
            <div>
              <div className="font-semibold text-slate-900 leading-tight">TechDocs AI</div>
              <div className="text-xs text-slate-400">База документации</div>
            </div>
          </div>
        </div>
        <nav className="flex-1 p-3 space-y-1">
          {navItems.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  isActive
                    ? 'bg-primary-50 text-primary-700'
                    : 'text-slate-600 hover:bg-slate-50'
                }`
              }
            >
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="p-3 border-t border-slate-100">
          <button onClick={logout} className="w-full text-left flex items-center gap-3 rounded-lg px-3 py-2 text-sm text-slate-500 hover:bg-slate-50">
            <span>🚪</span> Выйти
          </button>
        </div>
      </aside>
      <main className="flex-1 min-w-0">
        <Outlet />
      </main>
    </div>
  )
}
