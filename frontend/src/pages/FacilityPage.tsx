import { FormEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, openDocument } from '../api'
import { Doc, DocumentType, EngineeringSystem, Equipment, Facility } from '../types'
import ChatPanel from '../components/ChatPanel'
import ConfirmDialog from '../components/ConfirmDialog'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'
import { toast } from '../components/Toast'

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
        {tab === 'overview' && <OverviewTab facility={facility} onChange={load} />}
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

function OverviewTab({ facility, onChange }: { facility: Facility; onChange: () => void }) {
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
      <DriveCard facilityId={facility.id} onSynced={onChange} />
    </div>
  )
}

interface DriveStatus {
  available: boolean
  folderId: string | null
  syncedAt: string | null
}

function DriveCard({ facilityId, onSynced }: { facilityId: number; onSynced: () => void }) {
  const [status, setStatus] = useState<DriveStatus | null>(null)
  const [folder, setFolder] = useState('')
  const [saving, setSaving] = useState(false)
  const [syncing, setSyncing] = useState(false)

  const load = useCallback(() => {
    api.get<DriveStatus>(`/api/facilities/${facilityId}/drive`).then((s) => {
      setStatus(s)
      setFolder(s.folderId ?? '')
    })
  }, [facilityId])

  useEffect(() => { load() }, [load])

  const saveFolder = async () => {
    setSaving(true)
    try {
      await api.put(`/api/facilities/${facilityId}/drive/folder`, { folder })
      toast('Папка Google Drive сохранена', 'success')
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    } finally {
      setSaving(false)
    }
  }

  const sync = async () => {
    setSyncing(true)
    try {
      const r = await api.post<{ added: number; updated: number; skipped: number; failed: number }>(
        `/api/facilities/${facilityId}/drive/sync`)
      toast(`Синхронизация: +${r.added} новых, ${r.updated} обновлено, ${r.skipped} без изменений${r.failed ? `, ${r.failed} ошибок` : ''}`, 'success')
      load()
      onSynced()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка синхронизации', 'error')
    } finally {
      setSyncing(false)
    }
  }

  if (!status) return null

  return (
    <div className="card p-5">
      <div className="flex items-center gap-2 mb-3">
        <span className="text-lg">📁</span>
        <h3 className="font-medium text-slate-900">Google Drive</h3>
        {status.syncedAt && (
          <span className="text-xs text-slate-400 ml-auto">
            Синхронизировано: {new Date(status.syncedAt).toLocaleString('ru')}
          </span>
        )}
      </div>

      {!status.available ? (
        <p className="text-sm text-slate-500">
          Интеграция не настроена. Администратору нужно указать ключ сервисного аккаунта Google
          (переменная <code className="text-xs bg-slate-100 px-1 rounded">DRIVE_SERVICE_ACCOUNT_KEY</code>)
          и расшарить папки Drive на его email.
        </p>
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-slate-500">
            Укажите папку объекта в Google Drive (ссылку или ID). Подпапки внутри неё станут
            инженерными системами. Папку нужно расшарить на email сервисного аккаунта.
          </p>
          <div className="flex gap-2">
            <input className="input" placeholder="Ссылка на папку или её ID"
                   value={folder} onChange={(e) => setFolder(e.target.value)} />
            <button className="btn-secondary shrink-0" onClick={saveFolder} disabled={saving}>
              Сохранить
            </button>
          </div>
          <button className="btn-primary" onClick={sync} disabled={syncing || !status.folderId}>
            {syncing ? 'Синхронизация…' : '🔄 Синхронизировать'}
          </button>
        </div>
      )}
    </div>
  )
}

function SystemsTab({ facilityId, onChange }: { facilityId: number; onChange: () => void }) {
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [name, setName] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editName, setEditName] = useState('')
  const [mergeSource, setMergeSource] = useState<EngineeringSystem | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<EngineeringSystem | null>(null)

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

  const saveRename = async (systemId: number) => {
    if (editName.trim()) {
      await api.patch(`/api/facilities/${facilityId}/systems/${systemId}`, { name: editName.trim() })
    }
    setEditingId(null)
    load()
    onChange()
  }

  const doMerge = async (targetId: number) => {
    if (!mergeSource) return
    await api.post(`/api/facilities/${facilityId}/systems/${mergeSource.id}/merge`, { targetSystemId: targetId })
    setMergeSource(null)
    load()
    onChange()
  }

  const doDelete = async () => {
    if (!confirmDelete) return
    await api.delete(`/api/facilities/${facilityId}/systems/${confirmDelete.id}`)
    setConfirmDelete(null)
    load()
    onChange()
  }

  return (
    <div className="p-8 space-y-5">
      <form onSubmit={add} className="flex gap-2 max-w-md">
        <input className="input" placeholder="Название системы (например, АПС)" value={name} onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="btn-primary shrink-0">Добавить</button>
      </form>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
        {systems.map((s) => (
          <div key={s.id} className="card p-4">
            {editingId === s.id ? (
              <div className="flex gap-2">
                <input className="input py-1" value={editName} autoFocus
                       onChange={(e) => setEditName(e.target.value)}
                       onKeyDown={(e) => { if (e.key === 'Enter') saveRename(s.id) }} />
                <button className="btn-primary py-1 px-3 shrink-0" onClick={() => saveRename(s.id)}>✓</button>
                <button className="btn-secondary py-1 px-3 shrink-0" onClick={() => setEditingId(null)}>×</button>
              </div>
            ) : (
              <>
                <div className="flex items-start justify-between gap-2">
                  <div className="font-medium text-slate-900">{s.name}</div>
                  <span className="text-xs text-slate-400 shrink-0">{s.documentCount ?? 0} док.</span>
                </div>
                <div className="flex gap-3 mt-3 text-xs">
                  <button className="text-slate-400 hover:text-primary-600"
                          onClick={() => { setEditingId(s.id); setEditName(s.name) }}>
                    ✎ Переименовать
                  </button>
                  {systems.length > 1 && (
                    <button className="text-slate-400 hover:text-primary-600"
                            onClick={() => setMergeSource(s)}>
                      ⛙ Объединить
                    </button>
                  )}
                  <button className="text-slate-400 hover:text-red-500"
                          onClick={() => setConfirmDelete(s)}>
                    🗑 Удалить
                  </button>
                </div>
              </>
            )}
          </div>
        ))}
        {systems.length === 0 && (
          <div className="col-span-full text-sm text-slate-400">Систем пока нет.</div>
        )}
      </div>

      {mergeSource && (
        <Modal title="Объединить систему" onClose={() => setMergeSource(null)}>
          <p className="text-sm text-slate-600 mb-3">
            Всё оборудование и документы системы <span className="font-medium">«{mergeSource.name}»</span> будут
            перенесены в выбранную систему, а «{mergeSource.name}» — удалена. Действие необратимо.
          </p>
          <label className="label">Перенести в систему:</label>
          <div className="space-y-1 max-h-64 overflow-y-auto">
            {systems.filter((s) => s.id !== mergeSource.id).map((s) => (
              <button key={s.id}
                      onClick={() => doMerge(s.id)}
                      className="w-full text-left rounded-lg border border-slate-200 px-3 py-2 text-sm hover:border-primary-400 hover:bg-primary-50 transition-colors">
                {s.name}
              </button>
            ))}
          </div>
        </Modal>
      )}

      {confirmDelete && (
        <ConfirmDialog
          title="Удаление системы"
          confirmLabel="Удалить"
          danger
          onClose={() => setConfirmDelete(null)}
          onConfirm={doDelete}
          message={
            <p>
              Удалить систему <span className="font-medium">«{confirmDelete.name}»</span>?
              Оборудование и документы этой системы останутся на объекте, но без привязки к системе.
              Чтобы сохранить привязку, используйте «Объединить».
            </p>
          }
        />
      )}
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

  const [deleteDoc, setDeleteDoc] = useState<Doc | null>(null)
  const remove = async () => {
    if (!deleteDoc) return
    const id = deleteDoc.id
    setDeleteDoc(null)
    await api.delete(`/api/documents/${id}`)
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
      toast(err instanceof Error ? err.message : 'Ошибка', 'error')
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
                  <button onClick={() => setDeleteDoc(d)} title="Удалить"
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

      {deleteDoc && (
        <ConfirmDialog
          title="Удаление документа"
          confirmLabel="Удалить"
          danger
          onClose={() => setDeleteDoc(null)}
          onConfirm={remove}
          message={
            <p>
              Удалить документ <span className="font-medium">«{deleteDoc.originalFilename}»</span>?
              Файл, распознанный текст и связанные записи будут удалены. Действие необратимо.
            </p>
          }
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

interface ImportPreviewItem {
  manufacturer?: string
  name: string
  model?: string
  quantity: number
  unit: string
  systemName?: string
  quantityMissing?: boolean
  duplicateGroup?: number
  existsInRegistry: boolean
}

function EquipmentTab({ facilityId }: { facilityId: number }) {
  const [items, setItems] = useState<Equipment[]>([])
  const [search, setSearch] = useState('')
  const [selected, setSelected] = useState<number[]>([])
  const [editing, setEditing] = useState<Equipment | null>(null)
  const [showAdd, setShowAdd] = useState(false)
  const [showImport, setShowImport] = useState(false)
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  // фильтр по инженерной системе: '' — все, 'none' — без системы, иначе id
  const [systemFilter, setSystemFilter] = useState('')

  const load = useCallback(() => {
    const params = new URLSearchParams({ facilityId: String(facilityId) })
    if (search.trim()) params.set('search', search.trim())
    api.get<Equipment[]>(`/api/equipment?${params}`).then(setItems)
  }, [facilityId, search])

  useEffect(() => {
    const timer = setTimeout(load, 300)
    return () => clearTimeout(timer)
  }, [load])

  const loadSystems = useCallback(() => {
    api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
  }, [facilityId])

  useEffect(() => { loadSystems() }, [loadSystems])

  type PendingAction =
    | { type: 'confirm'; ids: number[] }
    | { type: 'merge'; ids: number[] }
    | { type: 'delete'; ids: number[] }
  const [pending, setPending] = useState<PendingAction | null>(null)

  const toggleSelect = (id: number) => {
    setSelected((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])
  }

  // клиентский фильтр по системе (список объекта уже загружен целиком)
  const visibleItems = items.filter((i) => {
    if (systemFilter === '') return true
    if (systemFilter === 'none') return !i.engineeringSystemId
    return String(i.engineeringSystemId) === systemFilter
  })

  const systemName = (id?: number) =>
    id ? (systems.find((s) => s.id === id)?.name ?? `#${id}`) : null

  const countBySystem = (filter: string) =>
    items.filter((i) => filter === 'none' ? !i.engineeringSystemId : String(i.engineeringSystemId) === filter).length

  const allSelected = visibleItems.length > 0 && selected.length === visibleItems.length
  const toggleSelectAll = () => {
    setSelected(allSelected ? [] : visibleItems.map((i) => i.id))
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
      toast(err instanceof Error ? err.message : 'Ошибка', 'error')
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
        <button className="btn-secondary" onClick={() => setShowImport(true)}>⬆ Из файла</button>
        <button className="btn-primary" onClick={() => setShowAdd(true)}>+ Добавить</button>
      </div>

      {(() => {
        // показываем только непустые системы — пустые лишь захламляют фильтр
        const nonEmpty = systems.filter((s) => countBySystem(String(s.id)) > 0)
        const hasNoSystem = items.some((i) => !i.engineeringSystemId)
        if (nonEmpty.length === 0 && !hasNoSystem) return null

        const Chip = ({ active, onClick, label, count }: {
          active: boolean; onClick: () => void; label: string; count: number
        }) => (
          <button
            onClick={onClick}
            className={`inline-flex items-center gap-1.5 rounded-full pl-3 pr-1.5 py-1 text-xs font-medium transition-colors ${
              active ? 'bg-primary-600 text-white shadow-sm'
                : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
            }`}
          >
            {label}
            <span className={`rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${
              active ? 'bg-white/25 text-white' : 'bg-white text-slate-500'
            }`}>{count}</span>
          </button>
        )

        return (
          <div className="flex gap-2 flex-wrap items-center">
            <Chip active={systemFilter === ''} label="Все" count={items.length}
                  onClick={() => { setSystemFilter(''); setSelected([]) }} />
            {nonEmpty.map((s) => (
              <Chip key={s.id} active={systemFilter === String(s.id)}
                    label={s.name} count={countBySystem(String(s.id))}
                    onClick={() => { setSystemFilter(String(s.id)); setSelected([]) }} />
            ))}
            {hasNoSystem && (
              <Chip active={systemFilter === 'none'} label="Без системы" count={countBySystem('none')}
                    onClick={() => { setSystemFilter('none'); setSelected([]) }} />
            )}
          </div>
        )
      })()}

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
              <th className="px-3 py-3 font-medium">Система</th>
              <th className="px-3 py-3 font-medium text-right">Кол-во</th>
              <th className="px-3 py-3 font-medium">Источник</th>
              <th className="px-3 py-3 font-medium">Статус</th>
              <th className="px-3 py-3"></th>
            </tr>
          </thead>
          <tbody>
            {visibleItems.map((item) => (
              <tr key={item.id} className="border-b border-slate-50 hover:bg-slate-50/50">
                <td className="px-3 py-2">
                  <input type="checkbox" checked={selected.includes(item.id)} onChange={() => toggleSelect(item.id)} />
                </td>
                <td className="px-3 py-2 text-slate-500">{item.manufacturer ?? '—'}</td>
                <td className="px-3 py-2 text-slate-800">{item.name ?? '—'}</td>
                <td className="px-3 py-2 font-medium text-slate-800">{item.model ?? '—'}</td>
                <td className="px-3 py-2 text-slate-500 text-xs">{systemName(item.engineeringSystemId) ?? '—'}</td>
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
              <tr><td colSpan={9} className="px-4 py-12 text-center text-slate-400">
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

      {showImport && (
        <ImportModal
          facilityId={facilityId}
          systems={systems}
          onClose={() => setShowImport(false)}
          onImported={() => { setShowImport(false); load(); loadSystems() }}
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

// Синонимы названий систем: как в файлах ⇄ как принято на объектах
const SYSTEM_ALIASES: Record<string, string> = {
  'пожарнаясигнализация': 'АПС',
  'автоматическаяпожарнаясигнализация': 'АПС',
  'спс': 'АПС',
  'аупс': 'АПС',
  'пс': 'АПС',
  'охраннаясигнализация': 'ОС',
  'энергоучет': 'АСКУЭ',
  'учетэнергоресурсов': 'АСКУЭ',
  'видеонаблюдение': 'Видеонаблюдение',
  'ктсо': 'СКУД',
}

const normalizeSystemName = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')

function ImportModal({ facilityId, systems, onClose, onImported }: {
  facilityId: number
  systems: EngineeringSystem[]
  onClose: () => void
  onImported: () => void
}) {
  const [systemId, setSystemId] = useState('')
  const [fileName, setFileName] = useState('')
  const [preview, setPreview] = useState<ImportPreviewItem[] | null>(null)
  const [include, setInclude] = useState<boolean[]>([])
  const [mergeGroup, setMergeGroup] = useState<Record<number, boolean>>({})
  // сопоставление «система из файла» → id существующей системы или 'new'
  const [sysMapping, setSysMapping] = useState<Record<string, string>>({})
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const defaultMapping = (fileSystem: string): string => {
    const norm = normalizeSystemName(fileSystem)
    const exact = systems.find((s) => normalizeSystemName(s.name) === norm)
    if (exact) return String(exact.id)
    const aliasTarget = SYSTEM_ALIASES[norm]
    if (aliasTarget) {
      const aliased = systems.find((s) => normalizeSystemName(s.name) === normalizeSystemName(aliasTarget))
      if (aliased) return String(aliased.id)
    }
    return 'new'
  }

  const onFile = async (file: File | null) => {
    if (!file) return
    setError('')
    setLoading(true)
    setFileName(file.name)
    try {
      const form = new FormData()
      form.append('file', file)
      const items = await api.postForm<ImportPreviewItem[]>(
        `/api/equipment/import/preview?facilityId=${facilityId}`, form)
      setPreview(items)
      // по умолчанию: всё включено, кроме уже существующих в реестре; дубли — объединять
      setInclude(items.map((i) => !i.existsInRegistry))
      const groups: Record<number, boolean> = {}
      items.forEach((i) => { if (i.duplicateGroup) groups[i.duplicateGroup] = true })
      setMergeGroup(groups)
      // сопоставление систем файла с системами объекта (синонимы учтены)
      const mapping: Record<string, string> = {}
      items.forEach((i) => {
        if (i.systemName && !(i.systemName in mapping)) {
          mapping[i.systemName] = defaultMapping(i.systemName)
        }
      })
      setSysMapping(mapping)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка чтения файла')
      setPreview(null)
    } finally {
      setLoading(false)
    }
  }

  const groupSum = (group: number) => {
    const rows = (preview ?? []).filter((item, idx) => item.duplicateGroup === group && include[idx])
    if (rows.length > 0) return rows.reduce((s, r) => s + r.quantity, 0)
    // ни одна строка группы не включена — показываем сумму всей группы для ориентира
    return (preview ?? []).filter((i) => i.duplicateGroup === group)
        .reduce((s, r) => s + r.quantity, 0)
  }

  // имя системы для импорта: выбранная существующая или исходное (будет создана)
  const mappedSystemName = (name?: string) => {
    if (!name) return undefined
    const target = sysMapping[name]
    if (!target || target === 'new') return name
    return systems.find((s) => String(s.id) === target)?.name ?? name
  }

  const submit = async () => {
    if (!preview) return
    setLoading(true)
    setError('')
    try {
      const items: { manufacturer?: string; name: string; model?: string; quantity: number; unit: string; comment?: string; systemName?: string }[] = []
      const mergedGroups = new Set<number>()
      preview.forEach((item, idx) => {
        if (!include[idx]) return
        const group = item.duplicateGroup
        if (group && mergeGroup[group]) {
          if (mergedGroups.has(group)) return // группа уже добавлена одной строкой
          mergedGroups.add(group)
          const rows = preview.filter((p, i) => p.duplicateGroup === group && include[i])
          items.push({
            manufacturer: item.manufacturer, name: item.name, model: item.model,
            quantity: rows.reduce((s, r) => s + r.quantity, 0), unit: item.unit,
            systemName: mappedSystemName(item.systemName),
            comment: `Объединено из ${rows.length} строк файла`,
          })
        } else {
          items.push({
            manufacturer: item.manufacturer, name: item.name, model: item.model,
            quantity: item.quantity, unit: item.unit,
            systemName: mappedSystemName(item.systemName),
            comment: item.quantityMissing ? 'Кол-во в файле не указано — проверьте' : undefined,
          })
        }
      })
      if (items.length === 0) {
        setError('Не выбрано ни одной позиции')
        setLoading(false)
        return
      }
      const result = await api.post<{ created: number }>(`/api/equipment/import`, {
        facilityId,
        engineeringSystemId: systemId ? Number(systemId) : null,
        fileName,
        items,
      })
      toast(`Импортировано позиций: ${result.created}`, 'success')
      onImported()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка импорта')
      setLoading(false)
    }
  }

  const duplicateGroups = [...new Set((preview ?? [])
    .map((i) => i.duplicateGroup).filter((g): g is number => !!g))]

  return (
    <Modal title="Импорт оборудования из Excel" onClose={onClose}>
      <div className="space-y-4 max-h-[70vh] overflow-y-auto">
        {preview?.some((i) => i.systemName) ? (
          <div className="rounded-lg bg-emerald-50 border border-emerald-200 p-3 space-y-2">
            <div className="text-xs font-medium text-emerald-800">
              Файл распознан как реестр с разбивкой по системам. Проверьте сопоставление
              с системами объекта — при необходимости измените:
            </div>
            {Object.keys(sysMapping).map((fileSystem) => (
              <div key={fileSystem} className="flex items-center gap-2 text-xs">
                <span className="w-44 truncate text-slate-700" title={fileSystem}>{fileSystem}</span>
                <span className="text-slate-400">→</span>
                <select
                  className="input py-1 text-xs flex-1"
                  value={sysMapping[fileSystem]}
                  onChange={(e) => setSysMapping({ ...sysMapping, [fileSystem]: e.target.value })}
                >
                  {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                  <option value="new">➕ Создать новую «{fileSystem}»</option>
                </select>
              </div>
            ))}
          </div>
        ) : (
          <div>
            <label className="label">Инженерная система (для всех позиций файла)</label>
            <select className="input" value={systemId} onChange={(e) => setSystemId(e.target.value)}>
              <option value="">Не указана</option>
              {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
        )}
        <div>
          <label className="label">Файл Excel (нужны колонки «Наименование» и «Кол-во»)</label>
          <input type="file" accept=".xlsx,.xls" className="input"
                 onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
        </div>

        {loading && <div className="text-sm text-slate-400">Обработка…</div>}
        {error && <div className="text-sm text-red-600">{error}</div>}

        {preview && (
          <>
            {duplicateGroups.length > 0 && (
              <div className="rounded-lg bg-amber-50 border border-amber-200 p-3 space-y-2">
                <div className="text-xs font-medium text-amber-800">
                  В файле найдены повторяющиеся позиции — решите, что с ними делать:
                </div>
                {duplicateGroups.map((group) => {
                  const first = preview.find((i) => i.duplicateGroup === group)!
                  const count = preview.filter((i) => i.duplicateGroup === group).length
                  return (
                    <label key={group} className="flex items-center gap-2 text-xs text-amber-900">
                      <input type="checkbox" checked={mergeGroup[group] ?? true}
                             onChange={(e) => setMergeGroup({ ...mergeGroup, [group]: e.target.checked })} />
                      <span>
                        Объединить {count} строки «{first.name}{first.model ? ` ${first.model}` : ''}»
                        в одну ({groupSum(group)} {first.unit})
                      </span>
                    </label>
                  )
                })}
              </div>
            )}

            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-slate-400 border-b border-slate-100">
                  <th className="py-1 pr-2"></th>
                  <th className="py-1 pr-2 font-medium">Наименование</th>
                  <th className="py-1 pr-2 font-medium">Модель</th>
                  {preview.some((i) => i.systemName) && (
                    <th className="py-1 pr-2 font-medium">Система</th>
                  )}
                  <th className="py-1 font-medium text-right">Кол-во</th>
                </tr>
              </thead>
              <tbody>
                {preview.map((item, idx) => (
                  <tr key={idx} className={`border-b border-slate-50 ${
                    item.duplicateGroup ? 'bg-amber-50' : item.existsInRegistry ? 'bg-sky-50' : ''
                  }`}>
                    <td className="py-1 pr-2">
                      <input type="checkbox" checked={include[idx] ?? false}
                             onChange={() => setInclude(include.map((v, i) => i === idx ? !v : v))} />
                    </td>
                    <td className="py-1 pr-2 text-slate-800">
                      {item.name}
                      {item.existsInRegistry && (
                        <span className="ml-1 text-sky-600">(уже в реестре)</span>
                      )}
                    </td>
                    <td className="py-1 pr-2 text-slate-600">{item.model ?? '—'}</td>
                    {preview.some((i) => i.systemName) && (
                      <td className="py-1 pr-2 text-slate-500">{item.systemName ?? '—'}</td>
                    )}
                    <td className="py-1 text-right text-slate-800">
                      {item.quantity} {item.unit}
                      {item.quantityMissing && (
                        <span className="text-amber-600" title="Кол-во в файле не указано">*</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-xs text-slate-400">
              Жёлтым — дубли внутри файла, голубым — позиции, уже существующие в реестре
              (по умолчанию не импортируются). Снимите или поставьте галочки по необходимости.
            </p>
          </>
        )}
      </div>

      <div className="flex justify-end gap-2 mt-4">
        <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
        <button type="button" className="btn-primary" disabled={!preview || loading} onClick={submit}>
          Импортировать
        </button>
      </div>
    </Modal>
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
