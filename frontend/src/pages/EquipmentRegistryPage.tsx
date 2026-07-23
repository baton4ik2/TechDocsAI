import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { UniqueEquipmentView } from '../types'
import { toast } from '../components/Toast'

export default function EquipmentRegistryPage() {
  const [items, setItems] = useState<UniqueEquipmentView[]>([])
  const [syncing, setSyncing] = useState(false)
  const [filter, setFilter] = useState('')   // '' = все, 'none' = без системы, иначе имя системы
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

      {items.length === 0 ? (
        <div className="card text-center text-slate-400 py-16">
          Реестр пуст. Нажмите «Синхронизировать» — оборудование объектов сгруппируется по моделям.
        </div>
      ) : (
        <>
          <SystemChips items={items} filter={filter} onChange={setFilter} />
          <div className="card divide-y divide-slate-100">
            {items.filter((it) => match(it, filter)).map((it) => (
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
                  {it.system && <span className="text-slate-400">{it.system}</span>}
                  <span>{it.objectCount} на объектах</span>
                  <span className={it.plannedWorkCount > 0 ? 'text-emerald-600' : ''}>
                    {it.plannedWorkCount} план. работ
                  </span>
                  {it.equipment.passportFilename && <span title="Есть паспорт">📄</span>}
                </div>
              </Link>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function match(it: UniqueEquipmentView, filter: string): boolean {
  if (filter === '') return true
  if (filter === 'none') return !it.system
  return it.system === filter
}

function SystemChips({ items, filter, onChange }: {
  items: UniqueEquipmentView[]; filter: string; onChange: (f: string) => void
}) {
  const systems = Array.from(new Set(items.map((i) => i.system).filter(Boolean) as string[]))
    .sort((a, b) => a.localeCompare(b, 'ru'))
  const count = (sys: string) => items.filter((i) => i.system === sys).length
  const noneCount = items.filter((i) => !i.system).length

  const Chip = ({ active, onClick, label, n }: {
    active: boolean; onClick: () => void; label: string; n: number
  }) => (
    <button onClick={onClick}
            className={`inline-flex items-center gap-1.5 rounded-full pl-3 pr-1.5 py-1 text-xs font-medium transition-colors ${
              active ? 'bg-primary-600 text-white shadow-sm' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
            }`}>
      {label}
      <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${
        active ? 'bg-white/25 text-white' : 'bg-white text-slate-500'
      }`}>{n}</span>
    </button>
  )

  return (
    <div className="flex gap-2 flex-wrap items-center">
      <Chip active={filter === ''} label="Все" n={items.length} onClick={() => onChange('')} />
      {systems.map((s) => (
        <Chip key={s} active={filter === s} label={s} n={count(s)} onClick={() => onChange(s)} />
      ))}
      {noneCount > 0 && (
        <Chip active={filter === 'none'} label="Без системы" n={noneCount} onClick={() => onChange('none')} />
      )}
    </div>
  )
}
