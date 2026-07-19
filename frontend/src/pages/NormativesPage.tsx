import { FormEvent, useEffect, useRef, useState } from 'react'
import { api, openNormative } from '../api'
import { NormativeRate, NormativeSourcebook } from '../types'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

export default function NormativesPage() {
  const [books, setBooks] = useState<NormativeSourcebook[]>([])
  const [showUpload, setShowUpload] = useState(false)
  const [deleting, setDeleting] = useState<NormativeSourcebook | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    api.get<NormativeSourcebook[]>('/api/normatives')
      .then(setBooks)
      .catch((e) => setError(e.message))
  }

  useEffect(() => { load() }, [])

  // пока есть обрабатываемые сборники — периодически обновляем статус
  useEffect(() => {
    if (!books.some((b) => b.status === 'PROCESSING' || b.status === 'UPLOADED')) return
    const t = setInterval(load, 3000)
    return () => clearInterval(t)
  }, [books])

  const remove = async () => {
    if (!deleting) return
    try {
      await api.delete(`/api/normatives/${deleting.id}`)
      toast('Сборник удалён', 'success')
      setDeleting(null)
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка удаления', 'error')
    }
  }

  const reprocess = async (b: NormativeSourcebook) => {
    try {
      await api.post(`/api/normatives/${b.id}/reprocess`)
      toast('Повторная обработка запущена', 'info')
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Нормативы</h1>
          <p className="text-sm text-slate-500 mt-1">
            Сборники СН-2012 и каталог расценок для расчёта смет обслуживания
          </p>
        </div>
        <button className="btn-primary" onClick={() => setShowUpload(true)}>+ Загрузить сборник</button>
      </div>

      {error && <div className="text-red-600 text-sm">{error}</div>}

      <RateSearch />

      <div className="card divide-y divide-slate-100">
        {books.map((b) => (
          <div key={b.id} className="flex items-center gap-4 px-5 py-4">
            <div className="text-2xl">📚</div>
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="font-medium text-slate-900 truncate">{b.name}</span>
                {b.code && <span className="text-xs text-slate-400">{b.code}</span>}
                <StatusBadge status={b.status} />
              </div>
              <div className="text-xs text-slate-500 mt-0.5">
                {b.status === 'READY'
                  ? `${b.rateCount} расценок${b.pageCount ? ` · ${b.pageCount} стр.` : ''}`
                  : b.status === 'PROCESSING' || b.status === 'UPLOADED'
                    ? 'Извлекаем расценки…'
                    : b.errorMessage || 'Нет данных'}
              </div>
            </div>
            <div className="flex items-center gap-1 shrink-0">
              <button className="btn-ghost text-sm" onClick={() => openNormative(b.id)}>Открыть PDF</button>
              {b.status === 'ERROR' && (
                <button className="btn-ghost text-sm" onClick={() => reprocess(b)}>Повторить</button>
              )}
              <button className="btn-ghost text-sm text-red-600" onClick={() => setDeleting(b)}>Удалить</button>
            </div>
          </div>
        ))}
        {books.length === 0 && (
          <div className="text-center text-slate-400 py-16">
            Пока нет сборников. Загрузите текстовый PDF сборника СН-2012.
          </div>
        )}
      </div>

      {showUpload && (
        <UploadModal onClose={() => setShowUpload(false)} onUploaded={() => { setShowUpload(false); load() }} />
      )}
      {deleting && (
        <ConfirmDialog
          title="Удалить сборник?"
          message={`Сборник «${deleting.name}» и все ${deleting.rateCount} расценок будут удалены.`}
          confirmLabel="Удалить"
          danger
          onConfirm={remove}
          onClose={() => setDeleting(null)}
        />
      )}
    </div>
  )
}

function UploadModal({ onClose, onUploaded }: { onClose: () => void; onUploaded: () => void }) {
  const [name, setName] = useState('')
  const [code, setCode] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!file) { setError('Выберите PDF-файл сборника'); return }
    setLoading(true)
    setError('')
    try {
      const form = new FormData()
      form.append('file', file)
      if (name.trim()) form.append('name', name.trim())
      if (code.trim()) form.append('code', code.trim())
      await api.postForm('/api/normatives/upload', form)
      toast('Сборник загружен, извлекаем расценки', 'success')
      onUploaded()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка загрузки')
      setLoading(false)
    }
  }

  return (
    <Modal title="Загрузить сборник СН-2012" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">Файл сборника (PDF) *</label>
          <input
            ref={fileRef}
            type="file"
            accept="application/pdf,.pdf"
            className="input"
            onChange={(e) => {
              const f = e.target.files?.[0] ?? null
              setFile(f)
              if (f && !name.trim()) setName(f.name.replace(/\.pdf$/i, ''))
            }}
          />
          <p className="text-xs text-slate-400 mt-1">
            Нужен текстовый PDF (не скан). Расценки извлекаются автоматически — ИИ не используется.
          </p>
        </div>
        <div>
          <label className="label">Название</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)}
                 placeholder="СН-2012. Системы безопасности" />
        </div>
        <div>
          <label className="label">Шифр / номер сборника</label>
          <input className="input" value={code} onChange={(e) => setCode(e.target.value)}
                 placeholder="напр. Сборник 22" />
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

/** Проверка каталога: полнотекстовый поиск расценок по всем сборникам. */
function RateSearch() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<NormativeRate[] | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!query.trim()) { setResults(null); return }
    const t = setTimeout(async () => {
      setLoading(true)
      try {
        const r = await api.get<NormativeRate[]>(`/api/normatives/rates/search?query=${encodeURIComponent(query)}&limit=15`)
        setResults(r)
      } catch {
        setResults([])
      } finally {
        setLoading(false)
      }
    }, 300)
    return () => clearTimeout(t)
  }, [query])

  const money = (v?: number) => (v == null ? '—' : v.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))

  return (
    <div className="card p-5 space-y-3">
      <div>
        <label className="label">Поиск расценки в каталоге</label>
        <input
          className="input"
          placeholder="напр. техническое обслуживание извещателя пожарного"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {loading && <div className="text-sm text-slate-400">Поиск…</div>}
      {results && results.length === 0 && !loading && (
        <div className="text-sm text-slate-400">Ничего не найдено. Загрузите сборник или измените запрос.</div>
      )}
      {results && results.length > 0 && (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
                <th className="py-2 pr-3 font-medium">Шифр</th>
                <th className="py-2 pr-3 font-medium">Наименование</th>
                <th className="py-2 pr-3 font-medium">Изм.</th>
                <th className="py-2 pr-3 font-medium text-right">ЗП</th>
                <th className="py-2 pr-3 font-medium text-right">ЭМ</th>
                <th className="py-2 pr-3 font-medium text-right">МР</th>
                <th className="py-2 pr-3 font-medium text-right">Труд, ч</th>
              </tr>
            </thead>
            <tbody>
              {results.map((r) => (
                <tr key={r.id} className="border-b border-slate-50 align-top">
                  <td className="py-2 pr-3 font-mono text-xs whitespace-nowrap">{r.code}</td>
                  <td className="py-2 pr-3">{r.name}</td>
                  <td className="py-2 pr-3 text-xs text-slate-500 whitespace-nowrap">{r.unit || '—'}</td>
                  <td className="py-2 pr-3 text-right whitespace-nowrap">{money(r.laborCost)}</td>
                  <td className="py-2 pr-3 text-right whitespace-nowrap">{money(r.machineCost)}</td>
                  <td className="py-2 pr-3 text-right whitespace-nowrap">{money(r.materialCost)}</td>
                  <td className="py-2 pr-3 text-right whitespace-nowrap">{money(r.laborHours)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
