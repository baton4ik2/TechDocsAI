import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, openDocument } from '../api'
import { Doc, DocumentType, EngineeringSystem, Equipment, Facility } from '../types'
import ChatPanel from '../components/ChatPanel'
import ConfirmDialog from '../components/ConfirmDialog'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'

type Tab = 'overview' | 'systems' | 'documents' | 'equipment' | 'chat'

interface ExtractProgress {
  status: 'NONE' | 'RUNNING' | 'DONE'
  totalPages: number
  processedPages: number
  created: number
  skippedDuplicates: number
  failedPages?: number
  error: string | null
}

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

  const [extractDoc, setExtractDoc] = useState<Doc | null>(null)
  const [extractProgress, setExtractProgress] = useState<Record<number, ExtractProgress>>({})
  const extractTimers = useRef<Record<number, number>>({})

  const pollExtractStatus = useCallback((docId: number) => {
    api.get<ExtractProgress>(`/api/documents/${docId}/extract-equipment/status`).then((p) => {
      setExtractProgress((prev) => ({ ...prev, [docId]: p }))
      if (p.status === 'RUNNING') {
        extractTimers.current[docId] = window.setTimeout(() => pollExtractStatus(docId), 2000)
      } else {
        onChange() // обновить счётчики оборудования на карточке объекта
      }
    })
  }, [onChange])

  // восстановление прогресса после перезахода на вкладку или обновления страницы:
  // извлечение продолжается на сервере, подтягиваем его состояние
  useEffect(() => {
    documents
      .filter((d) => d.status === 'READY')
      .forEach((d) => {
        api.get<ExtractProgress>(`/api/documents/${d.id}/extract-equipment/status`).then((p) => {
          if (p.status === 'NONE') return
          setExtractProgress((prev) => ({ ...prev, [d.id]: p }))
          if (p.status === 'RUNNING' && !extractTimers.current[d.id]) {
            extractTimers.current[d.id] = window.setTimeout(() => pollExtractStatus(d.id), 2000)
          }
        })
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documents.map((d) => d.id).join(',')])

  useEffect(() => () => {
    Object.values(extractTimers.current).forEach((t) => window.clearTimeout(t))
  }, [])

  const [extractPages, setExtractPages] = useState('')

  const startExtraction = async (doc: Doc) => {
    setExtractDoc(null)
    try {
      await api.post(`/api/documents/${doc.id}/extract-equipment`,
        extractPages.trim() ? { pages: extractPages.trim() } : {})
      setExtractProgress((prev) => ({
        ...prev,
        [doc.id]: { status: 'RUNNING', totalPages: 0, processedPages: 0, created: 0, skippedDuplicates: 0, error: null },
      }))
      pollExtractStatus(doc.id)
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Ошибка')
    }
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
                  <ExtractProgressBar progress={extractProgress[d.id]} />
                </td>
                <td className="px-4 py-3 text-slate-500">
                  {types.find((t) => t.id === d.documentTypeId)?.name ?? '—'}
                </td>
                <td className="px-4 py-3 text-slate-500">{formatSize(d.size)}</td>
                <td className="px-4 py-3 text-slate-500">{d.pageCount ?? '—'}</td>
                <td className="px-4 py-3"><StatusBadge status={d.status} /></td>
                <td className="px-4 py-3 text-right whitespace-nowrap">
                  {d.status === 'READY' && extractProgress[d.id]?.status !== 'RUNNING' && (
                    <button onClick={() => { setExtractDoc(d); setExtractPages('') }}
                            title="Извлечь оборудование в реестр (ИИ)"
                            className="text-slate-400 hover:text-primary-600 mr-3">⚙</button>
                  )}
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

      {extractDoc && (
        <Modal title="Извлечь оборудование в реестр" onClose={() => setExtractDoc(null)}>
          <div className="space-y-3 text-sm text-slate-600">
            <p>
              ИИ просканирует документ <span className="font-medium text-slate-900">«{extractDoc.originalFilename}»</span>,
              найдёт страницы с ведомостями и спецификациями и добавит позиции оборудования
              в реестр со статусом <span className="font-medium">«Требует проверки»</span>.
            </p>
            <div>
              <label className="label">
                Страницы файла (необязательно) — обработаются только они, это в разы быстрее
              </label>
              <input
                className="input"
                placeholder="Например: 91-93 или 91, 95, 96. Пусто — искать автоматически"
                value={extractPages}
                onChange={(e) => setExtractPages(e.target.value)}
              />
              <p className="text-xs text-slate-400 mt-1">
                Номера — это страницы файла (как в просмотре PDF), а не печатные номера листов.
              </p>
            </div>
            <ul className="list-disc pl-5 space-y-1 text-xs text-slate-500">
              <li>Позиции, уже существующие в реестре (та же модель), будут пропущены — дубликаты не создаются.</li>
              <li>Без указания страниц ИИ сам ищет ведомости по всему документу — до 10 страниц, на медленном сервере это может занять 10–15 минут.</li>
              <li>После завершения проверьте и подтвердите позиции на вкладке «Оборудование».</li>
            </ul>
          </div>
          <div className="flex justify-end gap-2 mt-5">
            <button type="button" className="btn-secondary" onClick={() => setExtractDoc(null)}>Отмена</button>
            <button type="button" className="btn-primary" onClick={() => startExtraction(extractDoc)}>
              Запустить извлечение
            </button>
          </div>
        </Modal>
      )}
    </div>
  )
}

function ExtractProgressBar({ progress }: { progress?: ExtractProgress }) {
  if (!progress || progress.status === 'NONE') return null

  if (progress.status === 'RUNNING') {
    const percent = progress.totalPages > 0
      ? Math.round((progress.processedPages / progress.totalPages) * 100)
      : 5
    return (
      <div className="mt-1.5 max-w-xs">
        <div className="flex justify-between text-xs text-slate-500 mb-0.5">
          <span>Извлечение оборудования…</span>
          <span>
            {progress.totalPages > 0
              ? `${progress.processedPages}/${progress.totalPages} стр.`
              : 'поиск страниц…'}
          </span>
        </div>
        <div className="h-1.5 rounded-full bg-slate-100 overflow-hidden">
          <div className="h-full bg-primary-500 rounded-full transition-all duration-500"
               style={{ width: `${Math.max(percent, 5)}%` }} />
        </div>
        {progress.created > 0 && (
          <div className="text-xs text-slate-400 mt-0.5">найдено позиций: {progress.created}</div>
        )}
      </div>
    )
  }

  // DONE
  const hasResult = progress.created > 0 || progress.skippedDuplicates > 0
  return (
    <div className="mt-1 text-xs space-y-0.5">
      {hasResult && (
        <span className="text-emerald-600">
          ✓ Добавлено позиций: {progress.created}
          {progress.skippedDuplicates > 0 && `, пропущено дубликатов: ${progress.skippedDuplicates}`}
          {' '}— проверьте вкладку «Оборудование»
        </span>
      )}
      {progress.error && (
        <div className="text-amber-600">⚠ {progress.error}</div>
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

  type PendingAction =
    | { type: 'confirm'; ids: number[] }
    | { type: 'merge'; ids: number[] }
    | { type: 'delete'; ids: number[] }
  const [pending, setPending] = useState<PendingAction | null>(null)

  const toggleSelect = (id: number) => {
    setSelected((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  const allSelected = items.length > 0 && selected.length === items.length
  const toggleSelectAll = () => {
    setSelected(allSelected ? [] : items.map((i) => i.id))
  }

  const runPending = async () => {
    if (!pending) return
    const { type, ids } = pending
    setPending(null)
    try {
      if (type === 'confirm') await api.post('/api/equipment/confirm', { ids })
      if (type === 'merge') await api.post('/api/equipment/merge', { ids })
      if (type === 'delete') await api.post('/api/equipment/delete-batch', { ids })
      setSelected([])
      load()
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Ошибка')
    }
  }

  const itemLabel = (id: number) => {
    const item = items.find((i) => i.id === id)
    return item ? (item.model || item.name || `#${id}`) : `#${id}`
  }

  return (
    <div className="p-8 space-y-4">
      <div className="flex items-center gap-3 flex-wrap">
        <input className="input max-w-xs" placeholder="Поиск оборудования…" value={search} onChange={(e) => setSearch(e.target.value)} />
        <div className="flex-1" />
        {selected.length > 0 && (
          <>
            <button className="btn-secondary"
                    onClick={() => setPending({ type: 'confirm', ids: selected })}>
              ✓ Подтвердить ({selected.length})
            </button>
            {selected.length >= 2 && (
              <button className="btn-secondary"
                      onClick={() => setPending({ type: 'merge', ids: selected })}>
                Объединить ({selected.length})
              </button>
            )}
            <button className="btn-danger"
                    onClick={() => setPending({ type: 'delete', ids: selected })}>
              🗑 Удалить ({selected.length})
            </button>
          </>
        )}
        <button className="btn-primary" onClick={() => setShowAdd(true)}>+ Добавить</button>
      </div>

      <div className="card overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-100 text-left text-xs text-slate-400">
              <th className="px-3 py-3">
                <input type="checkbox" checked={allSelected} onChange={toggleSelectAll}
                       title={allSelected ? 'Снять выделение' : 'Выбрать все'} />
              </th>
              <th className="px-3 py-3 font-medium">Производитель</th>
              <th className="px-3 py-3 font-medium">Наименование</th>
              <th className="px-3 py-3 font-medium">Модель</th>
              <th className="px-3 py-3 font-medium text-right">Кол-во</th>
              <th className="px-3 py-3 font-medium">Источник</th>
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
                <td className="px-3 py-2">
                  {item.sources && item.sources.length > 0 ? (
                    <div className="space-y-0.5">
                      {item.sources.map((s, i) => (
                        <button
                          key={i}
                          type="button"
                          onClick={() => openDocument(s.documentId, s.pageNumber)}
                          title={`Открыть ${s.documentName}${s.pageNumber ? ` на стр. ${s.pageNumber}` : ''}`}
                          className="block text-xs text-primary-600 hover:underline text-left"
                        >
                          📄 {s.documentName}{s.pageNumber ? `, стр. ${s.pageNumber}` : ''}
                        </button>
                      ))}
                    </div>
                  ) : (
                    <span className="text-xs text-slate-300">вручную</span>
                  )}
                </td>
                <td className="px-3 py-2"><StatusBadge status={item.status} /></td>
                <td className="px-3 py-2 text-right whitespace-nowrap">
                  {item.status !== 'CONFIRMED' && (
                    <button onClick={() => setPending({ type: 'confirm', ids: [item.id] })} title="Подтвердить"
                            className="text-slate-400 hover:text-emerald-600 mr-2">✓</button>
                  )}
                  <button onClick={() => setEditing(item)} title="Редактировать"
                          className="text-slate-400 hover:text-primary-600 mr-2">✎</button>
                  <button onClick={() => setPending({ type: 'delete', ids: [item.id] })} title="Удалить"
                          className="text-slate-400 hover:text-red-500">🗑</button>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr><td colSpan={8} className="px-4 py-12 text-center text-slate-400">
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

      {pending && (
        <ConfirmDialog
          title={
            pending.type === 'delete' ? 'Удаление оборудования'
              : pending.type === 'merge' ? 'Объединение записей'
              : 'Подтверждение записей'
          }
          confirmLabel={
            pending.type === 'delete' ? `Удалить (${pending.ids.length})`
              : pending.type === 'merge' ? 'Объединить'
              : 'Подтвердить'
          }
          danger={pending.type === 'delete'}
          onClose={() => setPending(null)}
          onConfirm={runPending}
          message={
            <div className="space-y-2">
              {pending.type === 'delete' && (
                <p>
                  Удалить {pending.ids.length === 1 ? 'запись' : `${pending.ids.length} записи(ей)`} из
                  реестра оборудования? Действие необратимо, источники записи также будут удалены.
                </p>
              )}
              {pending.type === 'merge' && (
                <p>
                  Объединить {pending.ids.length} записи(ей) в одну? Количество будет просуммировано,
                  источники всех записей сохранятся, результат получит статус «Требует проверки».
                </p>
              )}
              {pending.type === 'confirm' && (
                <p>
                  Подтвердить {pending.ids.length === 1 ? 'запись' : `${pending.ids.length} записи(ей)`}?
                  Подтверждённые данные считаются проверенными и используются для точных ответов
                  на количественные вопросы в чате.
                </p>
              )}
              <ul className="list-disc pl-5 text-xs text-slate-500 max-h-32 overflow-y-auto">
                {pending.ids.slice(0, 10).map((id) => <li key={id}>{itemLabel(id)}</li>)}
                {pending.ids.length > 10 && <li>…и ещё {pending.ids.length - 10}</li>}
              </ul>
            </div>
          }
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
