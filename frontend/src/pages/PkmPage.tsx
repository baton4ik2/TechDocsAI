import { Fragment, FormEvent, useEffect, useRef, useState } from 'react'
import { api } from '../api'
import { PkmDocument, PkmOperation } from '../types'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

const SYSTEM_TYPES = ['СКУД', 'АПС', 'СОУЭ', 'Видеонаблюдение', 'ОС', 'СКС', 'Домофония',
  'Вентиляция', 'Отопление', 'Водоснабжение', 'Электроснабжение', 'Лифты', 'Диспетчеризация']

export default function PkmPage() {
  const [docs, setDocs] = useState<PkmDocument[]>([])
  const [showUpload, setShowUpload] = useState(false)
  const [deleting, setDeleting] = useState<PkmDocument | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    api.get<PkmDocument[]>('/api/pkm').then(setDocs).catch((e) => setError(e.message))
  }

  useEffect(() => { load() }, [])

  useEffect(() => {
    if (!docs.some((d) => d.status === 'PROCESSING' || d.status === 'UPLOADED')) return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [docs])

  const remove = async () => {
    if (!deleting) return
    try {
      await api.delete(`/api/pkm/${deleting.id}`)
      toast('Регламент удалён', 'success')
      setDeleting(null)
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка удаления', 'error')
    }
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">ПКМ — регламенты работ</h1>
          <p className="text-sm text-slate-500 mt-1">
            Перечни контрольных мероприятий: обязательные работы и периодичность по типам систем.
            Используются как источник периодичности при расчёте смет.
          </p>
        </div>
        <button className="btn-primary" onClick={() => setShowUpload(true)}>+ Загрузить регламент</button>
      </div>

      {error && <div className="text-red-600 text-sm">{error}</div>}

      <div className="space-y-4">
        {docs.map((d) => <PkmCard key={d.id} doc={d} onDelete={() => setDeleting(d)} onChange={load} />)}
        {docs.length === 0 && (
          <div className="card text-center text-slate-400 py-16">
            Пока нет регламентов. Загрузите ПКМ (DOCX) — например, регламент работ по СКУД.
          </div>
        )}
      </div>

      {showUpload && (
        <UploadModal onClose={() => setShowUpload(false)} onUploaded={() => { setShowUpload(false); load() }} />
      )}
      {deleting && (
        <ConfirmDialog
          title="Удалить регламент?"
          message={`Регламент «${deleting.name}» и все ${deleting.operationCount} операций будут удалены.`}
          confirmLabel="Удалить"
          danger
          onConfirm={remove}
          onClose={() => setDeleting(null)}
        />
      )}
    </div>
  )
}

function PkmCard({ doc, onDelete, onChange }: { doc: PkmDocument; onDelete: () => void; onChange: () => void }) {
  const [open, setOpen] = useState(false)
  const [ops, setOps] = useState<PkmOperation[] | null>(null)

  const toggle = async () => {
    const next = !open
    setOpen(next)
    if (next && ops === null && doc.status === 'READY') {
      try {
        setOps(await api.get<PkmOperation[]>(`/api/pkm/${doc.id}/operations`))
      } catch {
        setOps([])
      }
    }
  }

  const reprocess = async () => {
    try {
      await api.post(`/api/pkm/${doc.id}/reprocess`)
      toast('Повторная обработка запущена', 'info')
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  return (
    <div className="card overflow-hidden">
      <div className="flex items-center gap-4 px-5 py-4">
        <button className="text-2xl" onClick={toggle}>📋</button>
        <div className="min-w-0 flex-1 cursor-pointer" onClick={toggle}>
          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-slate-400">{open ? '▾' : '▸'}</span>
            <span className="font-medium text-slate-900 truncate">{doc.name}</span>
            {doc.systemType && (
              <span className="rounded-full bg-primary-50 text-primary-700 px-2.5 py-0.5 text-xs font-medium">
                {doc.systemType}
              </span>
            )}
            <StatusBadge status={doc.status} />
          </div>
          <div className="text-xs text-slate-500 mt-0.5">
            {doc.status === 'READY'
              ? `${doc.operationCount} операций`
              : doc.status === 'PROCESSING' || doc.status === 'UPLOADED'
                ? 'Разбираем регламент…'
                : doc.errorMessage || 'Нет данных'}
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {doc.status === 'ERROR' && <button className="btn-ghost text-sm" onClick={reprocess}>Повторить</button>}
          <button className="btn-ghost text-sm text-red-600" onClick={onDelete}>Удалить</button>
        </div>
      </div>

      {open && doc.status === 'READY' && (
        <div className="border-t border-slate-100 px-5 py-3">
          {ops === null && <div className="text-sm text-slate-400">Загрузка…</div>}
          {ops && ops.length > 0 && <OperationsTable ops={ops} />}
          {ops && ops.length === 0 && <div className="text-sm text-slate-400">Операции не найдены.</div>}
        </div>
      )}
    </div>
  )
}

function OperationsTable({ ops }: { ops: PkmOperation[] }) {
  const [expanded, setExpanded] = useState<number | null>(null)
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
            <th className="py-2 pr-3 font-medium w-10">№</th>
            <th className="py-2 pr-3 font-medium">Категория</th>
            <th className="py-2 pr-3 font-medium">Операция</th>
            <th className="py-2 pr-3 font-medium">Периодичность</th>
            <th className="py-2 pr-3 font-medium text-right">раз/год</th>
          </tr>
        </thead>
        <tbody>
          {ops.map((op) => {
            const isOpen = expanded === op.id
            return (
              <Fragment key={op.id}>
                <tr onClick={() => setExpanded(isOpen ? null : op.id)}
                    className="border-b border-slate-50 align-top cursor-pointer hover:bg-slate-50">
                  <td className="py-2 pr-3 text-slate-400">{op.position ?? ''}</td>
                  <td className="py-2 pr-3 text-xs text-slate-500 whitespace-nowrap">{op.category || '—'}</td>
                  <td className="py-2 pr-3">
                    <span className="text-slate-400 mr-1">{isOpen ? '▾' : '▸'}</span>{op.operationName}
                  </td>
                  <td className="py-2 pr-3 whitespace-nowrap">{op.periodicity || '—'}</td>
                  <td className="py-2 pr-3 text-right whitespace-nowrap">
                    {op.periodicityPerYear ?? <span className="text-amber-500" title="не распознано">?</span>}
                  </td>
                </tr>
                {isOpen && (
                  <tr className="bg-slate-50/60">
                    <td colSpan={5} className="px-3 py-3">
                      <div className="text-xs font-medium text-slate-500 mb-1">Состав работ</div>
                      <div className="text-sm text-slate-700 whitespace-pre-line">
                        {op.workComposition || 'Состав работ не указан.'}
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function UploadModal({ onClose, onUploaded }: { onClose: () => void; onUploaded: () => void }) {
  const [name, setName] = useState('')
  const [systemType, setSystemType] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!file) { setError('Выберите DOCX-файл регламента'); return }
    setLoading(true)
    setError('')
    try {
      const form = new FormData()
      form.append('file', file)
      if (name.trim()) form.append('name', name.trim())
      if (systemType.trim()) form.append('systemType', systemType.trim())
      await api.postForm('/api/pkm/upload', form)
      toast('Регламент загружен, разбираем операции', 'success')
      onUploaded()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка загрузки')
      setLoading(false)
    }
  }

  return (
    <Modal title="Загрузить регламент ПКМ" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">Файл регламента (JSON или DOCX) *</label>
          <input
            ref={fileRef}
            type="file"
            accept=".json,application/json,.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            className="input"
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null
              setFile(f)
              if (f && !name.trim()) setName(f.name.replace(/\.(json|docx)$/i, ''))
            }}
          />
          <p className="text-xs text-slate-400 mt-1">
            JSON (рекомендуется) со списком операций и периодичностью, либо DOCX-таблица
            «Вид и состав работ» + «Периодичность». Тип системы можно взять из JSON.
          </p>
        </div>
        <div>
          <label className="label">Тип системы</label>
          <input className="input" list="pkm-systems" value={systemType}
                 onChange={(e) => setSystemType(e.target.value)} placeholder="напр. СКУД" />
          <datalist id="pkm-systems">
            {SYSTEM_TYPES.map((s) => <option key={s} value={s} />)}
          </datalist>
        </div>
        <div>
          <label className="label">Название</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)}
                 placeholder="Регламент эксплуатационных работ СКУД" />
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
