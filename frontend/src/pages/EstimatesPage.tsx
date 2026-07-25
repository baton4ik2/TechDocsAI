import { FormEvent, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { EngineeringSystem, Estimate, Facility } from '../types'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

interface DecisionView {
  decision: {
    id: number
    operationName: string | null
    rateCode: string | null
    rateName: string | null
    periodicity: string | null
    source: string
  }
  equipmentName: string | null
  model: string | null
  manufacturer: string | null
  system: string | null
}

export default function EstimatesPage() {
  const [estimates, setEstimates] = useState<Estimate[]>([])
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [showCreate, setShowCreate] = useState(false)
  const [showDecisions, setShowDecisions] = useState(false)
  const [decisionCount, setDecisionCount] = useState<number | null>(null)
  const [deleting, setDeleting] = useState<Estimate | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    api.get<Estimate[]>('/api/estimates').then(setEstimates).catch((e) => setError(e.message))
  }

  const loadDecisionCount = () => {
    api.get<DecisionView[]>('/api/estimate-decisions').then((d) => setDecisionCount(d.length)).catch(() => {})
  }

  useEffect(() => {
    load()
    loadDecisionCount()
    api.get<Facility[]>('/api/facilities').then(setFacilities).catch(() => {})
  }, [])

  const facilityName = (id: number) => facilities.find((f) => f.id === id)?.name ?? `Объект #${id}`
  const refFile = useRef<HTMLInputElement>(null)

  const importReference = async (file: File) => {
    try {
      const form = new FormData()
      form.append('file', file)
      const res = await api.postForm<{ imported: number; rows: number }>('/api/estimates/import-reference', form)
      toast(`Эталон загружен: сохранено решений ${res.imported} из ${res.rows} строк`, 'success')
      loadDecisionCount()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка загрузки эталона', 'error')
    }
  }

  const remove = async () => {
    if (!deleting) return
    try {
      await api.delete(`/api/estimates/${deleting.id}`)
      toast('Смета удалена', 'success')
      setDeleting(null)
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Плановые работы — сметы</h1>
          <p className="text-sm text-slate-500 mt-1">
            Расчёт стоимости планового обслуживания по системам объекта (СН-2012).
          </p>
        </div>
        <div className="flex gap-2">
          <input ref={refFile} type="file" accept=".xlsx,.xls" className="hidden"
                 onChange={(e) => { const f = e.target.files?.[0]; if (f) importReference(f); e.target.value = '' }} />
          <button className="btn-secondary" onClick={() => refFile.current?.click()}
                  title="Загрузить готовую смету — её решения переиспользуются на других объектах">
            ⭱ Загрузить эталон
          </button>
          <button className="btn-secondary" onClick={() => setShowDecisions(true)}
                  title="Просмотр памяти эталонных решений, наполняемой загрузкой эталона и кнопкой «В эталон»">
            📚 Эталонные решения{decisionCount != null ? ` (${decisionCount})` : ''}
          </button>
          <button className="btn-primary" onClick={() => setShowCreate(true)}>+ Новая смета</button>
        </div>
      </div>

      {error && <div className="text-red-600 text-sm">{error}</div>}

      <div className="card divide-y divide-slate-100">
        {estimates.map((e) => (
          <Link key={e.id} to={`/estimates/${e.id}`}
                className="flex items-center gap-4 px-5 py-4 hover:bg-slate-50">
            <div className="text-2xl">📊</div>
            <div className="min-w-0 flex-1">
              <div className="font-medium text-slate-900 truncate">{e.name}</div>
              <div className="text-xs text-slate-500 mt-0.5">{facilityName(e.facilityId)}</div>
            </div>
            <button className="btn-ghost text-sm text-red-600"
                    onClick={(ev) => { ev.preventDefault(); setDeleting(e) }}>Удалить</button>
          </Link>
        ))}
        {estimates.length === 0 && (
          <div className="text-center text-slate-400 py-16">
            Смет пока нет. Создайте первую смету для объекта.
          </div>
        )}
      </div>

      {showCreate && (
        <CreateModal facilities={facilities} onClose={() => setShowCreate(false)} />
      )}
      {showDecisions && (
        <DecisionsModal onClose={() => setShowDecisions(false)} onChange={loadDecisionCount} />
      )}
      {deleting && (
        <ConfirmDialog title="Удалить смету?" message={`Смета «${deleting.name}» будет удалена.`}
                       confirmLabel="Удалить" danger onConfirm={remove} onClose={() => setDeleting(null)} />
      )}
    </div>
  )
}

function DecisionsModal({ onClose, onChange }: { onClose: () => void; onChange: () => void }) {
  const [rows, setRows] = useState<DecisionView[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')

  const load = () => {
    setLoading(true)
    api.get<DecisionView[]>('/api/estimate-decisions')
      .then(setRows).catch(() => {}).finally(() => setLoading(false))
  }
  useEffect(load, [])

  const remove = async (id: number) => {
    try {
      await api.delete(`/api/estimate-decisions/${id}`)
      setRows((prev) => prev.filter((r) => r.decision.id !== id))
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка удаления', 'error')
    }
  }

  const sourceLabel = (s: string) => (s === 'REFERENCE' ? 'эталон' : s === 'APPROVED' ? '«В эталон»' : s)
  const filtered = rows.filter((r) => {
    if (!query.trim()) return true
    const q = query.toLowerCase()
    return [r.equipmentName, r.model, r.manufacturer, r.system, r.decision.operationName, r.decision.rateCode]
      .some((v) => (v ?? '').toLowerCase().includes(q))
  })

  return (
    <Modal title="Эталонные решения" onClose={onClose} wide>
      <p className="text-sm text-slate-500 -mt-2 mb-3">
        Память решений: для оборудования в конкретной системе — какая расценка и периодичность.
        Наполняется загрузкой эталона и кнопкой «В эталон»; при сборке сметы берётся вместо ИИ.
      </p>
      <input className="input mb-3" placeholder="Поиск по оборудованию, системе, шифру…"
             value={query} onChange={(e) => setQuery(e.target.value)} />
      {loading ? (
        <div className="text-center text-slate-400 py-10">Загрузка…</div>
      ) : filtered.length === 0 ? (
        <div className="text-center text-slate-400 py-10">
          {rows.length === 0 ? 'Пока нет ни одного эталонного решения. Загрузите эталон.' : 'Ничего не найдено.'}
        </div>
      ) : (
        <div className="max-h-[60vh] overflow-auto -mx-1">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-white">
              <tr className="text-left text-xs text-slate-500 border-b border-slate-200">
                <th className="py-2 pr-3">Оборудование</th>
                <th className="py-2 pr-3">Система</th>
                <th className="py-2 pr-3">Операция</th>
                <th className="py-2 pr-3">Шифр</th>
                <th className="py-2 pr-3">Периодичность</th>
                <th className="py-2 pr-3">Источник</th>
                <th className="py-2"></th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.decision.id} className="border-b border-slate-100 align-top">
                  <td className="py-2 pr-3">
                    <div className="font-medium text-slate-800">{r.equipmentName ?? '—'}</div>
                    <div className="text-xs text-slate-400">
                      {[r.model, r.manufacturer].filter(Boolean).join(' · ') || '—'}
                    </div>
                  </td>
                  <td className="py-2 pr-3">
                    {r.system
                      ? <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">{r.system}</span>
                      : <span className="text-xs text-slate-300">без системы</span>}
                  </td>
                  <td className="py-2 pr-3 text-slate-700">{r.decision.operationName ?? '—'}</td>
                  <td className="py-2 pr-3 font-mono text-xs text-slate-700">{r.decision.rateCode ?? '—'}</td>
                  <td className="py-2 pr-3 text-slate-600">{r.decision.periodicity ?? '—'}</td>
                  <td className="py-2 pr-3">
                    <span className="text-xs text-slate-500">{sourceLabel(r.decision.source)}</span>
                  </td>
                  <td className="py-2 text-right">
                    <button className="btn-ghost text-xs text-red-600" onClick={() => remove(r.decision.id)}>
                      Удалить
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Modal>
  )
}

function CreateModal({ facilities, onClose }: { facilities: Facility[]; onClose: () => void }) {
  const [name, setName] = useState('')
  const [facilityId, setFacilityId] = useState<number | ''>('')
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [selectedSystems, setSelectedSystems] = useState<number[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  useEffect(() => {
    setSystems([]); setSelectedSystems([])
    if (!facilityId) return
    api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems).catch(() => {})
  }, [facilityId])

  const toggleSystem = (id: number) =>
    setSelectedSystems((prev) => prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!facilityId) { setError('Выберите объект'); return }
    setLoading(true)
    setError('')
    try {
      const created = await api.post<Estimate>(`/api/estimates?facilityId=${facilityId}`, { name })
      // сразу собираем ИИ-черновик по выбранным системам (блоки по системам)
      if (selectedSystems.length > 0) {
        const q = selectedSystems.map((s) => `systemIds=${s}`).join('&')
        const res = await api.post<{ created: number; aiUsed: boolean }>(
          `/api/estimates/${created.id}/generate?${q}`)
        toast(`Черновик собран: строк ${res.created}${res.aiUsed ? '' : ' · без ИИ (поиск по каталогу)'}`, 'success')
      }
      navigate(`/estimates/${created.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
      setLoading(false)
    }
  }

  return (
    <Modal title="Новая смета" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">Объект *</label>
          <select className="input" value={facilityId}
                  onChange={(e) => setFacilityId(e.target.value ? Number(e.target.value) : '')}>
            <option value="">— выберите объект —</option>
            {facilities.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
        </div>
        <div>
          <label className="label">Название сметы *</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required
                 placeholder="Смета обслуживания на 2026 год" />
        </div>
        {facilityId !== '' && (
          <div>
            <label className="label">Системы для ИИ-черновика</label>
            {systems.length === 0 ? (
              <div className="text-xs text-slate-400">У объекта нет систем.</div>
            ) : (
              <>
                <div className="flex flex-wrap gap-2">
                  {systems.map((s) => (
                    <button type="button" key={s.id} onClick={() => toggleSystem(s.id)}
                            className={`rounded-full px-3 py-1 text-xs font-medium border transition-colors ${
                              selectedSystems.includes(s.id)
                                ? 'bg-primary-600 text-white border-primary-600'
                                : 'bg-white text-slate-600 border-slate-200 hover:border-primary-300'
                            }`}>
                      {s.name}
                    </button>
                  ))}
                </div>
                <p className="text-xs text-slate-400 mt-1">
                  Выберите одну или несколько систем — каждая станет отдельным блоком (напр. АПС, СОУЭ).
                  Не выбирать — создать пустую смету.
                </p>
              </>
            )}
          </div>
        )}
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? (selectedSystems.length ? 'Создание и сборка…' : 'Создание…') : 'Создать'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
