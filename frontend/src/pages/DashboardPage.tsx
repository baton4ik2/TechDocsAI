import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { Dashboard } from '../types'
import StatCard from '../components/StatCard'
import StatusBadge from '../components/StatusBadge'

export default function DashboardPage() {
  const [data, setData] = useState<Dashboard | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    api.get<Dashboard>('/api/dashboard').then(setData).catch((e) => setError(e.message))
  }, [])

  if (error) return <div className="p-8 text-red-600">{error}</div>
  if (!data) return <div className="p-8 text-slate-400">Загрузка…</div>

  return (
    <div className="p-8 space-y-8">
      <h1 className="text-2xl font-semibold text-slate-900">Главная</h1>

      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard icon="🏢" label="Объектов" value={data.facilityCount} />
        <StatCard icon="📄" label="Документов" value={data.documentCount} />
        <StatCard icon="⚙️" label="Оборудование" value={data.equipmentCount} accent="green" />
        <StatCard icon="⚠️" label="Ошибки обработки" value={data.errorCount} accent={data.errorCount > 0 ? 'red' : 'slate'} />
      </div>

      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold text-slate-900">Недавние объекты</h2>
          <Link to="/facilities" className="text-sm text-primary-600 hover:underline">Все объекты →</Link>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {data.recentFacilities.map((f) => (
            <Link key={f.id} to={`/facilities/${f.id}`} className="card p-4 hover:shadow-md transition-shadow">
              <div className="h-24 rounded-lg bg-gradient-to-br from-primary-100 to-primary-50 flex items-center justify-center text-4xl mb-3">
                🏢
              </div>
              <div className="font-medium text-slate-900">{f.name}</div>
              <div className="text-xs text-slate-500 mt-1">
                {f.systemCount} систем · {f.documentCount} док.
              </div>
            </Link>
          ))}
          {data.recentFacilities.length === 0 && (
            <div className="text-sm text-slate-400 col-span-full">
              Объектов пока нет. <Link to="/facilities" className="text-primary-600 hover:underline">Создайте первый объект</Link>.
            </div>
          )}
        </div>
      </section>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section className="card p-5">
          <h2 className="text-base font-semibold text-slate-900 mb-3">Последние документы</h2>
          <div className="space-y-2">
            {data.recentDocuments.slice(0, 6).map((d) => (
              <div key={d.id} className="flex items-center justify-between gap-3 text-sm">
                <span className="truncate text-slate-700">📄 {d.originalFilename}</span>
                <StatusBadge status={d.status} />
              </div>
            ))}
            {data.recentDocuments.length === 0 && (
              <div className="text-sm text-slate-400">Документов пока нет.</div>
            )}
          </div>
        </section>

        <section className="card p-5">
          <h2 className="text-base font-semibold text-slate-900 mb-3">Последние вопросы</h2>
          <div className="space-y-2">
            {data.recentChats.map((c) => (
              <Link key={c.id} to={`/chat?chat=${c.id}`} className="block text-sm text-slate-600 hover:text-primary-600 truncate">
                💬 {c.title || 'Диалог #' + c.id}
              </Link>
            ))}
            {data.recentChats.length === 0 && (
              <div className="text-sm text-slate-400">
                Вопросов пока не было. <Link to="/chat" className="text-primary-600 hover:underline">Задайте первый вопрос</Link>.
              </div>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}
