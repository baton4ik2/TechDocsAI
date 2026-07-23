import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { UniqueEquipmentView } from '../types'
import { toast } from '../components/Toast'

export default function EquipmentRegistryPage() {
  const [items, setItems] = useState<UniqueEquipmentView[]>([])
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState('')

  const load = () => {
    api.get<UniqueEquipmentView[]>('/api/unique-equipment').then(setItems).catch((e) => setError(e.message))
  }
  useEffect(() => { load() }, [])

  const sync = async () => {
    setSyncing(true)
    try {
      const res = await api.post<{ linked: number }>('/api/unique-equipment/sync')
      toast(`Привязано оборудования: ${res.linked}`, 'success')
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    } finally {
      setSyncing(false)
    }
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Реестр уникального оборудования</h1>
          <p className="text-sm text-slate-500 mt-1">
            Одна запись на модель — общая для всех объектов. Паспорт и плановые работы
            привязываются к оборудованию и применяются везде, где оно встречается.
          </p>
        </div>
        <button className="btn-primary" onClick={sync} disabled={syncing}>
          {syncing ? 'Синхронизация…' : '⟳ Синхронизировать с объектами'}
        </button>
      </div>

      {error && <div className="text-red-600 text-sm">{error}</div>}

      {items.length === 0 && (
        <div className="card text-center text-slate-400 py-16">
          Реестр пуст. Нажмите «Синхронизировать» — оборудование объектов сгруппируется по моделям.
        </div>
      )}

      {groupBySystem(items).map(([system, rows]) => (
        <div key={system} className="space-y-2">
          <h2 className="text-sm font-semibold text-slate-500 uppercase tracking-wide">
            {system} <span className="text-slate-300 font-normal">· {rows.length}</span>
          </h2>
          <div className="card divide-y divide-slate-100">
            {rows.map((it) => (
              <Link key={it.equipment.id} to={`/equipment-registry/${it.equipment.id}`}
                    className="flex items-center gap-4 px-5 py-4 hover:bg-slate-50">
                <div className="text-2xl">🔧</div>
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-slate-900 truncate">{it.equipment.name || 'Без названия'}</div>
                  <div className="text-xs text-slate-500 mt-0.5">
                    {[it.equipment.model, it.equipment.manufacturer].filter(Boolean).join(' · ') || '—'}
                  </div>
                </div>
                <div className="flex items-center gap-4 text-xs text-slate-500 shrink-0">
                  <span>{it.objectCount} на объектах</span>
                  <span className={it.plannedWorkCount > 0 ? 'text-emerald-600' : ''}>
                    {it.plannedWorkCount} план. работ
                  </span>
                  {it.equipment.passportFilename && <span title="Есть паспорт">📄</span>}
                </div>
              </Link>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}

/** Группировка по системе; «Без системы» — в конце. */
function groupBySystem(items: UniqueEquipmentView[]): [string, UniqueEquipmentView[]][] {
  const groups = new Map<string, UniqueEquipmentView[]>()
  for (const it of items) {
    const key = it.system || 'Без системы'
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(it)
  }
  return Array.from(groups.entries()).sort(([a], [b]) => {
    if (a === 'Без системы') return 1
    if (b === 'Без системы') return -1
    return a.localeCompare(b, 'ru')
  })
}
