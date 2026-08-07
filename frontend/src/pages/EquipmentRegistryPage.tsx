import { useEffect, useRef, useState } from 'react'
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

  // паспорта разбираются асинхронно — пока хоть один в работе, обновляем список
  const busy = items.some((it) => it.equipment.passportStatus === 'PROCESSING'
    || it.equipment.passportStatus === 'UPLOADED')
  useEffect(() => {
    if (!busy) return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [busy])

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
            Одна запись на модель — общая для всех объектов. Паспорт загружается здесь,
            кнопкой в строке: из него ИИ вытащит плановые работы с периодичностью, и они
            подставятся в сметы везде, где это оборудование встречается.
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
              <div key={it.equipment.id} className="flex items-center gap-4 px-5 py-4 hover:bg-slate-50">
                <Link to={`/equipment-registry/${it.equipment.id}`}
                      className="flex items-center gap-4 min-w-0 flex-1">
                  <div className="text-2xl">🔧</div>
                  <div className="min-w-0 flex-1">
                    <div className="font-medium text-slate-900 truncate">{it.equipment.name || 'Без названия'}</div>
                    <div className="text-xs text-slate-500 mt-0.5">
                      {[it.equipment.model, it.equipment.manufacturer].filter(Boolean).join(' · ') || '—'}
                    </div>
                  </div>
                </Link>
                <div className="flex items-center gap-4 text-xs text-slate-500 shrink-0">
                  {it.system && <span className="text-slate-400">{it.system}</span>}
                  <span>{it.objectCount} на объектах</span>
                  <span className={it.plannedWorkCount > 0 ? 'text-emerald-600' : ''}>
                    {it.plannedWorkCount} план. работ
                  </span>
                  <PassportCell item={it} onChange={load} />
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Паспорт прямо в строке реестра: раньше загрузка жила только внутри карточки
 * оборудования, и найти её было нечем — список ничем на неё не намекал.
 */
function PassportCell({ item, onChange }: { item: UniqueEquipmentView; onChange: () => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const ue = item.equipment
  const busy = uploading || ue.passportStatus === 'PROCESSING' || ue.passportStatus === 'UPLOADED'

  const upload = async (file: File) => {
    setUploading(true)
    try {
      const form = new FormData()
      form.append('file', file)
      await api.postForm(`/api/unique-equipment/${ue.id}/passport`, form)
      toast('Паспорт загружен, извлекаем плановые работы', 'success')
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка загрузки', 'error')
    } finally {
      setUploading(false)
    }
  }

  return (
    <div className="flex items-center gap-2">
      {ue.passportFilename && ue.passportStatus === 'ERROR' && (
        <span className="text-red-600" title={ue.passportError || 'Ошибка разбора паспорта'}>⚠</span>
      )}
      {ue.passportFilename && ue.passportStatus === 'READY' && (
        <span className="text-emerald-600" title={ue.passportFilename}>📄</span>
      )}
      <input ref={fileRef} type="file" accept=".pdf,.docx,.txt,.doc,.png,.jpg,.jpeg" className="hidden"
             onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
      <button
        className={`text-xs whitespace-nowrap ${ue.passportFilename ? 'btn-ghost' : 'btn-secondary px-3 py-1.5'}`}
        disabled={busy} onClick={() => fileRef.current?.click()}>
        {busy ? 'Разбираем…' : ue.passportFilename ? 'Заменить паспорт' : '📄 Загрузить паспорт'}
      </button>
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
