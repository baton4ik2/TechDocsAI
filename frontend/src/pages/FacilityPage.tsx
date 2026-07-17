import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, openDocument } from '../api'
import { Doc, DocumentType, EngineeringSystem, Equipment, Facility } from '../types'
import ChatPanel from '../components/ChatPanel'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'

type Tab = 'overview' | 'systems' | 'documents' | 'equipment' | 'chat'

const tabs: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Обзор' },
  { key: 'systems', label: 'Системы' },
  { key: 'documents', label: 'Документы' },
  { key: 'equipment', label: 'Оборудование' },
  { key: 'chat', label: 'Чат с ИИ' },
]

export default function FacilityPage() {
  const { id } = useParams()
  const facilityId = Number(id)
  const [facility, setFacility] = useState<Facility | null>(null)
  const [tab, setTab] = useState<Tab>('overview')
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api.get<Facility>(`/api/facilities/${facilityId}`).then(setFacility).catch((e) => setError(e.message))
  }, [facilityId])

  useEffect(() => { load() }, [load])

  if (error) return <div className="p-8 text-red-600">{error}</div>
  if (!facility) return <div className="p-8 text-slate-400">Загрузка…</div>

  return (
    <div className="flex flex-col h-screen">
      <div className="p-8 pb-0">
        <div className="text-sm text-slate-400 mb-2">
          <Link to="/facilities" className="hover:text-primary-600">Объекты</Link> / {facility.name}
        </div>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div>
            <h1 className="text-2xl font-semibold text-slate-900">{facility.name}</h1>
            {facility.address && <div className="text-sm text-slate-500 mt-1">{facility.address}</div>}
          </div>
          <StatusBadge status={facility.status} />
        </div>

        <div className="flex gap-1 mt-6 border-b border-slate-200">
          {tabs.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`px-4 py-2.5 text-sm font-medium border-b-2 -mb-px transition-colors ${
                tab === t.key
                  ? 'border-primary-600 text-primary-700'
                  : 'border-transparent text-slate-500 hover:text-slate-700'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto">
        {tab === 'overview' && <OverviewTab facility={facility} />}
        {tab === 'systems' && <SystemsTab facilityId={facilityId} onChange={load} />}
        {tab === 'documents' && <DocumentsTab facilityId={facilityId} onChange={load} />}
        {tab === 'equipment' && <EquipmentTab facilityId={facilityId} />}
        {tab === 'chat' && (
          <div className="h-full bg-slate-50">
            <ChatPanel facilityId={facilityId} placeholder={`Вопрос по объекту «${facility.name}»…`} />
          </div>
        )}
      </div>
    </div>
  )
}

function OverviewTab({ facility }: { facility: Facility }) {
  const stats = [
    { label: 'Документов', value: facility.documentCount },
    { label: 'Систем', value: facility.systemCount },
    { label: 'Моделей оборудования', value: facility.equipmentCount },
    { label: 'Единиц оборудования', value: facility.equipmentUnits },
    { label: 'Документов с ошибками', value: facility.errorCount },
  ]
  return (
    <div className="p-8 space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        {stats.map((s) => (
          <div key={s.label} className="card p-4 text-center">
            <div className="text-2xl font-semibold text-slate-900">{s.value}</div>
            <div className="text-xs text-slate-500 mt-1">{s.label}</div>
          </div>
        ))}
      </div>
      {facility.description && (
        <div className="card p-5">
          <h3 className="font-medium text-slate-900 mb-2">Описание</h3>
          <p className="text-sm text-slate-600 whitespace-pre-wrap">{facility.description}</p>
        </div>
      )}
    </div>
  )
}

function SystemsTab({ facilityId, onChange }: { facilityId: number; onChange: () => void }) {
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [name, setName] = useState('')

  const load = useCallback(() => {
    api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
  }, [facilityId])

  useEffect(() => { load() }, [load])

  const add = async (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim()) return
    await api.post(`/api/facilities/${facilityId}/systems`, { name: name.trim() })
    setName('')
    load()
    onChange()
  }

  const remove = async (systemId: number) => {
    if (!confirm('Удалить систему?')) return
    await api.delete(`/api/facilities/${facilityId}/systems/${systemId}`)
    load()
    onChange()
  }

  return (
    <div className="p-8 space-y-4">
      <form onSubmit={add} className="flex gap-2 max-w-md">
        <input className="input" placeholder="Название системы (например, АПС)" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="btn-primary shrink-0">Добавить</button>
      </form>
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-4 gap-4">
        {systems.map((s) => (
          <div key={s.id} className="card p-4 flex items-start justify-between gap-2">
            <div>
              <div className="font-medium text-slate-900">{s.name}</div>
              <div className="text-xs text-slate-500 mt-1">{s.documentCount ?? 0} документов</div>
            </div>
            <button onClick={() => remove(s.id)} className="text-slate-300 hover:text-red-500">×</button>
          </div>
        ))}
        {systems.length === 0 && (
          <div className="col-span-full text-sm text-slate-400">Систем пока нет.</div>
        )}
      </div>
    </div>
  )
}

function DocumentsTab({ facilityId, onChange }: { facilityId: number; onChange: () => void }) {
  const [documents, setDocuments] = useState<Doc[]>([])
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [types, setTypes] = useState<DocumentType[]>([])
  const [filterSystem, setFilterSystem] = useState('')
  const [filterType, setFilterType] = useState('')
  const [showUpload, setShowUpload] = useState(false)
  const pollRef = useRef<number | null>(null)

  const load = useCallback(() => {
    const params = new URLSearchParams({ facilityId: String(facilityId) })
    if (filterSystem) params.set('systemId', filterSystem)
    if (filterType) params.set('typeId', filterType)
    api.get<Doc[]>(`/api/documents?${params}`).then((docs) => {
      setDocuments(docs)
      // пока есть документы в обработке — обновляем список
      const processing = docs.some((d) => d.status === 'PROCESSING' || d.status === 'UPLOADED')
      if (pollRef.current) window.clearTimeout(pollRef.current)
      if (processing) pollRef.current = window.setTimeout(load, 3000)
    })
  }, [facilityId, filterSystem, filterType])

  useEffect(() => {
    load()
    api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
    api.get<DocumentType[]>('/api/documents/types').then(setTypes)
    return () => { if (pollRef.current) window.clearTimeout(pollRef.current) }
  }, [facilityId, load])

  const remove = async (docId: number) => {
    if (!confirm('Удалить документ?')) return
    await api.delete(`/api/documents/${docId}`)
    load()
    onChange()
  }

  const reprocess = async (docId: number) => {
    await api.post(`/api/documents/${docId}/reprocess`)
    load()
  }

  const formatSize = (bytes: number) => {
    if (bytes > 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' МБ'
    return Math.max(1, Math.round(bytes / 1024)) + ' КБ'
  }

  return (
    <div className="p-8 space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        <select className="input w-auto" value={filterSystem} onChange={(e) => setFilterSystem(e.target.value)}>
          <option value="">Система: все</option>
          {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <select className="input w-auto" value={filterType} onChange={(e) => setFilterType(e.target.value)}>
          <option value="">Тип: все</option>
          {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
        <div className="flex-1" />
        <button className="btn-primary" onClick={() => setShowUpload(true)}>⬆ Загрузить</button>
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-left text-xs text-slate-400">
              <th className="px-4 py-3 font-medium">Название</th>
              <th className="px-4 py-3 font-medium">Тип</th>
              <th className="px-4 py-3 font-medium">Размер</th>
              <th className="px-4 py-3 font-medium">Страниц</th>
              <th className="px-4 py-3 font-medium">Статус</th>
              <th className="px-4 py-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {documents.map((d) => (
              <tr key={d.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                <td className="px-4 py-3">
                  <button type="button" onClick={() => openDocument(d.id)}
                          className="text-slate-800 hover:text-primary-600 font-medium text-left">
                    📄 {d.originalFilename}
                  </button>
                  {d.errorMessage && <div className="text-xs text-red-500 mt-1">{d.errorMessage}</div>}
                </td>
                <td className="px-4 py-3 text-slate-500">
                  {types.find((t) => t.id === d.documentTypeId)?.name ?? '—'}
                </td>
                <td className="px-4 py-3 text-slate-500">{formatSize(d.size)}</td>
                <td className="px-4 py-3 text-slate-500">{d.pageCount ?? '—'}</td>
                <td className="px-4 py-3"><StatusBadge status={d.status} /></td>
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {(d.status === 'ERROR' || d.status === 'NEEDS_OCR' || d.status === 'READY') && (
                    <button onClick={() => reprocess(d.id)} title="Повторная обработка"
                            className="text-slate-400 hover:text-primary-600 mr-3">↻</button>
                  )}
                  <button onClick={() => remove(d.id)} title="Удалить"
                          className="text-slate-400 hover:text-red-500">🗑</button>
                </td>
              </tr>
            ))}
            {documents.length === 0 && (
              <tr><td colSpan={6} className="px-4 py-12 text-center text-slate-400">
                Документов нет. Нажмите «Загрузить», чтобы добавить документацию.
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {showUpload && (
        <UploadModal
          facilityId={facilityId}
          systems={systems}
          types={types}
          onClose={() => setShowUpload(false)}
          onUploaded={() => { setShowUpload(false); load(); onChange() }}
        />
      )}
    </div>
  )
}

function UploadModal({ facilityId, systems, types, onClose, onUploaded }: {
  facilityId: number
  systems: EngineeringSystem[]
  types: DocumentType[]
  onClose: () => void
  onUploaded: () => void
}) {
  const [systemId, setSystemId] = useState('')
  const [typeId, setTypeId] = useState('')
  const [files, setFiles] = useState<FileList | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!files || files.length === 0) { setError('Выберите файлы'); return }
    setLoading(true)
    setError('')
    try {
      const form = new FormData()
      for (const file of Array.from(files)) form.append('files', file)
      const params = new URLSearchParams({ facilityId: String(facilityId) })
      if (systemId) params.set('systemId', systemId)
      if (typeId) params.set('typeId', typeId)
      await api.postForm(`/api/documents/upload?${params}`, form)
      onUploaded()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка загрузки')
      setLoading(false)
    }
  }

  return (
    <Modal title="Загрузка документов" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">Инженерная система</label>
          <select className="input" value={systemId} onChange={(e) => setSystemId(e.target.value)}>
            <option value="">Не указана</option>
            {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">Тип документа</label>
          <select className="input" value={typeId} onChange={(e) => setTypeId(e.target.value)}>
            <option value="">Не указан</option>
            {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">Файлы (PDF, DOCX, XLSX, XLS, TXT, CSV, JPG, PNG)</label>
          <input
            type="file"
            multiple
            accept=".pdf,.docx,.doc,.xlsx,.xls,.txt,.csv,.jpg,.jpeg,.png"
            className="input"
            onChange={(e) => setFiles(e.target.files)}
          />
        </div>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? 'Загрузка…' : 'Загрузить'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

function EquipmentTab({ facilityId }: { facilityId: number }) {
  const [items, setItems] = useState<Equipment[]>([])
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const [editing, setEditing] = useState<Equipment | null>(null)
  const [showAdd, setShowAdd] = useState(false)
  const [systems, setSystems] = useState<EngineeringSystem[]>([])

  const load = useCallback(() => {
    const params = new URLSearchParams({ facilityId: String(facilityId) })
    if (search.trim()) params.set('search', search.trim())
    api.get<Equipment[]>(`/api/equipment?${params}`).then(setItems)
  }, [facilityId, search])

  useEffect(() => {
    const timer = setTimeout(load, 300)
    return () => clearTimeout(timer)
  }, [load])

  useEffect(() => {
    api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
  }, [facilityId])

  const toggleSelect = (id: number) => {
    setSelected((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  const merge = async () => {
    if (selected.length < 2) return
    if (!confirm(`Объединить ${selected.length} записи в одну? Количество будет просуммировано.`)) return
    await api.post('/api/equipment/merge', { ids: selected })
    setSelected([])
    load()
  }

  const remove = async (id: number) => {
    if (!confirm('Удалить запись оборудования?')) return
    await api.delete(`/api/equipment/${id}`)
    load()
  }

  const confirmItem = async (item: Equipment) => {
    await api.put(`/api/equipment/${item.id}`, { ...item, status: 'CONFIRMED' })
    load()
  }

  return (
    <div className="p-8 space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        <input className="input max-w-xs" placeholder="Поиск оборудования…" value={search} onChange={(e) => setSearch(e.target.value)} />
        <div className="flex-1" />
        {selected.length >= 2 && (
          <button className="btn-secondary" onClick={merge}>Объединить ({selected.length})</button>
        )}
        <button className="btn-primary" onClick={() => setShowAdd(true)}>+ Добавить</button>
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-left text-xs text-slate-400">
              <th className="px-3 py-3"></th>
              <th className="px-3 py-3 font-medium">Производитель</th>
              <th className="px-3 py-3 font-medium">Наименование</th>
              <th className="px-3 py-3 font-medium">Модель</th>
              <th className="px-3 py-3 font-medium text-right">Кол-во</th>
              <th className="px-3 py-3 font-medium">Статус</th>
              <th className="px-3 py-3"></th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <tr key={item.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                <td className="px-3 py-2">
                  <input type="checkbox" checked={selected.includes(item.id)} onChange={() => toggleSelect(item.id)} />
                </td>
                <td className="px-3 py-2 text-slate-500">{item.manufacturer ?? '—'}</td>
                <td className="px-3 py-2 text-slate-800">{item.name ?? '—'}</td>
                <td className="px-3 py-2 font-medium text-slate-800">{item.model ?? '—'}</td>
                <td className="px-3 py-2 text-right text-slate-800">{item.quantity} {item.unit}</td>
                <td className="px-3 py-2"><StatusBadge status={item.status} /></td>
                <td className="px-3 py-2 text-right whitespace-nowrap">
                  {item.status !== 'CONFIRMED' && (
                    <button onClick={() => confirmItem(item)} title="Подтвердить"
                            className="text-slate-400 hover:text-emerald-600 mr-2">✓</button>
                  )}
                  <button onClick={() => setEditing(item)} title="Редактировать"
                          className="text-slate-400 hover:text-primary-600 mr-2">✎</button>
                  <button onClick={() => remove(item.id)} title="Удалить"
                          className="text-slate-400 hover:text-red-500">🗑</button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr><td colSpan={7} className="px-4 py-12 text-center text-slate-400">
                Оборудования нет. Загрузите Excel-спецификацию — данные будут извлечены автоматически, либо добавьте вручную.
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {(editing || showAdd) && (
        <EquipmentModal
          facilityId={facilityId}
          systems={systems}
          item={editing}
          onClose={() => { setEditing(null); setShowAdd(false) }}
          onSaved={() => { setEditing(null); setShowAdd(false); load() }}
        />
      )}
    </div>
  )
}

function EquipmentModal({ facilityId, systems, item, onClose, onSaved }: {
  facilityId: number
  systems: EngineeringSystem[]
  item: Equipment | null
  onClose: () => void
  onSaved: () => void
}) {
  const [form, setForm] = useState({
    manufacturer: item?.manufacturer ?? '',
    name: item?.name ?? '',
    model: item?.model ?? '',
    quantity: item?.quantity ?? 1,
    unit: item?.unit ?? 'шт.',
    location: item?.location ?? '',
    engineeringSystemId: item?.engineeringSystemId ? String(item.engineeringSystemId) : '',
  })
  const [error, setError] = useState('')

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    try {
      const body = {
        facilityId,
        manufacturer: form.manufacturer || null,
        name: form.name,
        model: form.model || null,
        quantity: form.quantity,
        unit: form.unit,
        location: form.location || null,
        engineeringSystemId: form.engineeringSystemId ? Number(form.engineeringSystemId) : null,
        status: 'CONFIRMED',
      }
      if (item) await api.put(`/api/equipment/${item.id}`, body)
      else await api.post('/api/equipment', body)
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
    }
  }

  return (
    <Modal title={item ? 'Редактирование оборудования' : 'Добавить оборудование'} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label">Производитель</label>
            <input className="input" value={form.manufacturer} onChange={(e) => setForm({ ...form, manufacturer: e.target.value })} />
          </div>
          <div>
            <label className="label">Модель</label>
            <input className="input" value={form.model} onChange={(e) => setForm({ ...form, model: e.target.value })} />
          </div>
        </div>
        <div>
          <label className="label">Наименование *</label>
          <input className="input" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required />
        </div>
        <div className="grid grid-cols-3 gap-3">
          <div>
            <label className="label">Количество</label>
            <input type="number" step="any" min="0" className="input" value={form.quantity}
                   onChange={(e) => setForm({ ...form, quantity: Number(e.target.value) })} />
          </div>
          <div>
            <label className="label">Ед. изм.</label>
            <input className="input" value={form.unit} onChange={(e) => setForm({ ...form, unit: e.target.value })} />
          </div>
          <div>
            <label className="label">Система</label>
            <select className="input" value={form.engineeringSystemId}
                    onChange={(e) => setForm({ ...form, engineeringSystemId: e.target.value })}>
              <option value="">—</option>
              {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
        </div>
        <div>
          <label className="label">Место установки</label>
          <input className="input" value={form.location} onChange={(e) => setForm({ ...form, location: e.target.value })} />
        </div>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary">Сохранить</button>
        </div>
      </form>
    </Modal>
  )
}
