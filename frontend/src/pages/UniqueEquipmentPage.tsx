import { FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { PassportModels, PlannedWork, UniqueEquipment } from '../types'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

export default function UniqueEquipmentPage() {
  const { id } = useParams()
  const ueId = Number(id)
  const [ue, setUe] = useState<UniqueEquipment | null>(null)
  const [works, setWorks] = useState<PlannedWork[]>([])
  const [editing, setEditing] = useState<PlannedWork | null | 'new'>(null)
  const [deleting, setDeleting] = useState<PlannedWork | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    api.get<UniqueEquipment>(`/api/unique-equipment/${ueId}`).then(setUe).catch((e) => setError(e.message))
    api.get<PlannedWork[]>(`/api/unique-equipment/${ueId}/planned-works`).then(setWorks).catch(() => {})
  }
  useEffect(() => { load() }, [ueId])

  // паспорт обрабатывается асинхронно — опрашиваем статус
  useEffect(() => {
    if (ue?.passportStatus !== 'PROCESSING' && ue?.passportStatus !== 'UPLOADED') return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [ue?.passportStatus])

  const removeWork = async () => {
    if (!deleting) return
    try {
      await api.delete(`/api/unique-equipment/planned-works/${deleting.id}`)
      setDeleting(null)
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  if (error) return <div className="p-8 text-red-600">{error}</div>
  if (!ue) return <div className="p-8 text-slate-400">Загрузка…</div>

  return (
    <div className="p-8 space-y-6">
      <div>
        <Link to="/equipment-registry" className="text-sm text-primary-600">← К реестру</Link>
        <h1 className="text-2xl font-semibold text-slate-900 mt-1">{ue.name || 'Оборудование'}</h1>
        <div className="text-sm text-slate-500 mt-0.5">
          {[ue.model, ue.manufacturer].filter(Boolean).join(' · ') || '—'}
        </div>
      </div>

      <PassportCard ue={ue} onChange={load} />

      <div className="card">
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100">
          <h2 className="font-semibold text-slate-900">Плановые работы</h2>
          <button className="btn-secondary text-sm" onClick={() => setEditing('new')}>+ Добавить работу</button>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
                <th className="py-2 px-4 font-medium">Тип</th>
                <th className="py-2 px-4 font-medium">Наименование</th>
                <th className="py-2 px-4 font-medium">Периодичность</th>
                <th className="py-2 px-4 font-medium text-right">раз/год</th>
                <th className="py-2 px-4 font-medium">Основание</th>
                <th className="py-2 px-4 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              {works.map((w) => (
                <tr key={w.id} className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer"
                    onClick={() => setEditing(w)}>
                  <td className="py-2 px-4 text-xs text-slate-500 whitespace-nowrap">{w.workType || '—'}</td>
                  <td className="py-2 px-4">
                    {w.name}
                    {w.mandatory === false && (
                      <span className="ml-2 text-[10px] text-amber-600 align-middle"
                            title="Рекомендуемая работа — заказчик не обязан её оплачивать">
                        рекомендуемая
                      </span>
                    )}
                  </td>
                  <td className="py-2 px-4 whitespace-nowrap">{w.periodicity || '—'}</td>
                  <td className="py-2 px-4 text-right">{w.periodicityPerYear ?? '—'}</td>
                  <td className="py-2 px-4 text-xs">
                    <SourceCell work={w} />
                  </td>
                  <td className="py-2 px-4 text-right">
                    <button className="text-red-500 hover:text-red-700"
                            onClick={(e) => { e.stopPropagation(); setDeleting(w) }}>✕</button>
                  </td>
                </tr>
              ))}
              {works.length === 0 && (
                <tr><td colSpan={6} className="text-center text-slate-400 py-10">
                  Плановых работ нет. Загрузите паспорт для авто-извлечения или добавьте вручную.
                </td></tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <WorkModal ueId={ueId} work={editing === 'new' ? null : editing}
                   onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load() }} />
      )}
      {deleting && (
        <ConfirmDialog title="Удалить плановую работу?" message={`«${deleting.name}» будет удалена.`}
                       confirmLabel="Удалить" danger onConfirm={removeWork} onClose={() => setDeleting(null)} />
      )}
    </div>
  )
}

/** Сколько уже идёт разбор — чтобы «Обрабатывается» не выглядело вечным. */
function Elapsed({ since }: { since?: string }) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 10000)
    return () => clearInterval(t)
  }, [])
  if (!since) return null
  const min = Math.floor((now - new Date(since).getTime()) / 60000)
  return <span className="text-slate-400">идёт {min < 1 ? 'меньше минуты' : `${min} мин`}</span>
}

const MODE_LABELS: Record<string, string> = {
  TEXT: 'текстовый слой',
  OCR: 'распознан OCR',
  VISION: 'страницы читала vision-модель',
}

/** Основание плановой работы: страница паспорта и подтверждена ли цитата. */
function SourceCell({ work }: { work: PlannedWork }) {
  if (work.source === 'MIDIO') return <span className="text-primary-600">Midio</span>
  if (work.source !== 'PASSPORT') return <span className="text-slate-400">вручную</span>
  const label = work.sourceLabel || 'Паспорт'
  if (work.quoteVerified === false) {
    return (
      <span className="text-amber-600" title={work.sourceQuote || 'Цитата не найдена в тексте паспорта'}>
        ⚠ {label} · не подтверждено
      </span>
    )
  }
  return (
    <span className="text-emerald-600" title={work.sourceQuote || ''}>
      {label}
    </span>
  )
}

function PassportCard({ ue, onChange }: { ue: UniqueEquipment; onChange: () => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [models, setModels] = useState<PassportModels | null>(null)
  const [model, setModel] = useState('')

  useEffect(() => {
    api.get<PassportModels>('/api/unique-equipment/passport-models')
      .then((m) => { setModels(m); setModel(ue.passportModel || m.defaultModel || '') })
      .catch(() => {})
  }, [])

  const upload = async (file: File) => {
    setUploading(true)
    try {
      const form = new FormData()
      form.append('file', file)
      const query = model ? `?model=${encodeURIComponent(model)}` : ''
      await api.postForm(`/api/unique-equipment/${ue.id}/passport${query}`, form)
      toast('Паспорт загружен, извлекаем плановые работы', 'success')
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка загрузки', 'error')
    } finally {
      setUploading(false)
    }
  }

  const reprocess = async () => {
    try {
      const query = model ? `?model=${encodeURIComponent(model)}` : ''
      await api.post(`/api/unique-equipment/${ue.id}/passport/reprocess${query}`)
      toast('Пересобираем работы из паспорта', 'success')
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  const busy = ue.passportStatus === 'PROCESSING' || ue.passportStatus === 'UPLOADED'

  return (
    <div className="card p-5 flex items-center gap-4 flex-wrap">
      <div className="text-2xl">📄</div>
      <div className="min-w-0 flex-1">
        <div className="font-medium text-slate-900">Паспорт оборудования</div>
        {ue.passportFilename ? (
          <div className="text-xs text-slate-500 mt-0.5 flex items-center gap-2 flex-wrap">
            <span className="truncate">{ue.passportFilename}</span>
            {ue.passportStatus && <StatusBadge status={ue.passportStatus} />}
            {ue.passportMode && (
              <span className="text-slate-400">{MODE_LABELS[ue.passportMode] || ue.passportMode}</span>
            )}
            {ue.passportModel && <span className="text-slate-400">· {ue.passportModel}</span>}
            {busy && <Elapsed since={ue.passportStartedAt} />}
            {ue.passportError && <span className="text-amber-600">{ue.passportError}</span>}
          </div>
        ) : (
          <div className="text-xs text-slate-400 mt-0.5">
            Загрузите PDF/DOCX/TXT — ИИ выделит плановые работы (осмотр, ТО, контроль…) с цитатой из паспорта.
          </div>
        )}
      </div>
      {models && models.models.length > 1 && (
        <label className="text-xs text-slate-500 flex items-center gap-2">
          Модель
          <select className="input py-1 text-xs w-56" value={model} onChange={(e) => setModel(e.target.value)}>
            {models.models.map((m) => (
              <option key={m} value={m}>{m}{m === models.defaultModel ? ' (по умолчанию)' : ''}</option>
            ))}
          </select>
        </label>
      )}
      <input ref={fileRef} type="file" accept=".pdf,.docx,.txt,.doc" className="hidden"
             onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f) }} />
      <button className="btn-secondary text-sm" disabled={uploading || busy}
              onClick={() => fileRef.current?.click()}>
        {uploading ? 'Загрузка…' : ue.passportFilename ? 'Заменить паспорт' : 'Загрузить паспорт'}
      </button>
      {ue.passportFilename && (
        // намеренно доступна и во время разбора: зависший прогон иначе не перебить,
        // а поздний ответ старого запроса уже не затрёт результат нового
        <button className="btn-ghost text-sm" onClick={reprocess}>
          {busy ? 'Разобрать заново' : 'Пересобрать работы'}
        </button>
      )}
    </div>
  )
}

function WorkModal({ ueId, work, onClose, onSaved }: {
  ueId: number; work: PlannedWork | null; onClose: () => void; onSaved: () => void
}) {
  const [f, setF] = useState({
    workType: work?.workType ?? '',
    name: work?.name ?? '',
    periodicity: work?.periodicity ?? '',
    workComposition: work?.workComposition ?? '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const set = (k: keyof typeof f, v: string) => setF({ ...f, [k]: v })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    const body = {
      workType: f.workType || null,
      name: f.name || null,
      periodicity: f.periodicity || null,
      workComposition: f.workComposition || null,
    }
    try {
      if (work) await api.patch(`/api/unique-equipment/planned-works/${work.id}`, body)
      else await api.post(`/api/unique-equipment/${ueId}/planned-works`, body)
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
      setLoading(false)
    }
  }

  return (
    <Modal title={work ? 'Плановая работа' : 'Новая плановая работа'} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label">Тип</label>
            <input className="input" list="work-types" value={f.workType}
                   onChange={(e) => set('workType', e.target.value)} placeholder="ТО" />
            <datalist id="work-types">
              <option value="осмотр" /><option value="ТО" />
              <option value="контроль функционирования" /><option value="проверка" />
            </datalist>
          </div>
          <div>
            <label className="label">Периодичность</label>
            <input className="input" value={f.periodicity} onChange={(e) => set('periodicity', e.target.value)}
                   placeholder="Ежемесячно / раз в 6 мес." />
          </div>
        </div>
        <div>
          <label className="label">Наименование *</label>
          <input className="input" value={f.name} onChange={(e) => set('name', e.target.value)} required
                 placeholder="Техническое обслуживание" />
        </div>
        <div>
          <label className="label">Состав работ</label>
          <textarea className="input" rows={3} value={f.workComposition}
                    onChange={(e) => set('workComposition', e.target.value)} />
        </div>
        {work?.source === 'PASSPORT' && (
          <div className={`rounded-lg border p-3 text-xs ${
            work.quoteVerified === false
              ? 'border-amber-200 bg-amber-50 text-amber-800'
              : 'border-slate-200 bg-slate-50 text-slate-600'}`}>
            <div className="font-medium mb-1">
              {work.sourceLabel || 'Паспорт'}
              {work.quoteVerified === false && ' · цитата не найдена в тексте паспорта'}
              {work.quoteVerified === true && ' · цитата подтверждена'}
            </div>
            <div className="italic">{work.sourceQuote ? `«${work.sourceQuote}»` : 'Модель не привела цитату.'}</div>
          </div>
        )}
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? 'Сохранение…' : 'Сохранить'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
