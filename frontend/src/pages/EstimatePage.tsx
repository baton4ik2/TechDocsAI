import { Fragment, FormEvent, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, exportEstimate } from '../api'
import { EstimateRowEntity, EstimateView, NormativeRate } from '../types'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

const money = (v?: number) =>
  v == null ? '—' : v.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

/** Откуда взялась расценка строки — бейдж «Источник». */
function SourceBadge({ source }: { source?: string }) {
  const map: Record<string, { label: string; cls: string; title: string }> = {
    LEARNED:     { label: 'эталон',       cls: 'bg-emerald-100 text-emerald-800', title: 'Расценка из памяти эталонов (та же модель уже считалась)' },
    ETALON_TYPE: { label: 'эталон (тип)', cls: 'bg-emerald-100 text-emerald-800', title: 'В эталоне есть оборудование с тем же наименованием — расценка и периодичность взяты оттуда детерминированно' },
    ETALON_XSYS: { label: 'эталон (др. сист.)', cls: 'bg-emerald-100 text-emerald-800', title: 'То же оборудование есть в эталоне другой инженерной системы — расценки и периодичность взяты оттуда' },
    AI_TYPE:     { label: 'эталон (ИИ)',  cls: 'bg-emerald-100 text-emerald-800', title: 'ИИ определил, что это то же оборудование, что в эталоне (синонимичное название); расценки и периодичность всех операций взяты из эталона' },
    AI_ETALON:   { label: 'ИИ ← эталон',  cls: 'bg-teal-100 text-teal-800',       title: 'ИИ выбрал расценку, которая есть в эталоне этой системы; периодичность тоже из эталона' },
    CHOICE:      { label: 'выбрать',      cls: 'bg-orange-100 text-orange-800',   title: 'Похожее оборудование есть в эталоне, но не точно — откройте строку и выберите расценку из вариантов' },
    AI:         { label: 'ИИ',          cls: 'bg-sky-100 text-sky-800',         title: 'Расценку подобрал ИИ из каталога СН-2012' },
    AI_FAILED: { label: 'ИИ не смог', cls: 'bg-amber-100 text-amber-800',    title: 'ИИ не подобрал расценку — выберите вручную' },
    CATALOG:   { label: 'поиск',     cls: 'bg-amber-100 text-amber-800',     title: 'Верхний результат поиска по каталогу (ИИ был выключен)' },
    SYSTEM:    { label: 'по системе', cls: 'bg-indigo-100 text-indigo-800',  title: 'Общесистемная работа: добавляется в каждую смету этой инженерной системы (например, комплексные испытания АПС). Количество — площадь объекта' },
    MANUAL:    { label: 'вручную',   cls: 'bg-slate-100 text-slate-700',     title: 'Расценка задана вручную' },
  }
  const s = source ? map[source] : undefined
  if (!s) return <span className="text-slate-300">—</span>
  return <span className={`rounded px-1.5 py-0.5 text-[11px] font-medium ${s.cls}`} title={s.title}>{s.label}</span>
}

export default function EstimatePage() {
  const { id } = useParams()
  const estimateId = Number(id)
  const [view, setView] = useState<EstimateView | null>(null)
  const [error, setError] = useState('')
  const [editing, setEditing] = useState<EstimateRowEntity | null | 'new'>(null)
  const [deletingRow, setDeletingRow] = useState<EstimateRowEntity | null>(null)

  const load = () => {
    api.get<EstimateView>(`/api/estimates/${estimateId}`).then(setView).catch((e) => setError(e.message))
  }
  useEffect(() => { load() }, [estimateId])

  const removeRow = async () => {
    if (!deletingRow) return
    try {
      await api.delete(`/api/estimates/rows/${deletingRow.id}`)
      setDeletingRow(null)
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  if (error) return <div className="p-8 text-red-600">{error}</div>
  if (!view) return <div className="p-8 text-slate-400">Загрузка…</div>

  const { estimate, rows, totals } = view
  const reviewCount = rows.filter((r) => r.row.needsReview).length

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <Link to="/estimates" className="text-sm text-primary-600">← К сметам</Link>
          <h1 className="text-2xl font-semibold text-slate-900 mt-1">{estimate.name}</h1>
        </div>
        <div className="flex gap-2">
          <GenerateButton estimateId={estimate.id} onDone={load} />
          <button className="btn-secondary"
                  title="Сохранить строки в память решений — переиспользуются на других объектах"
                  onClick={() => api.post<{ saved: number }>(`/api/estimates/${estimate.id}/promote`)
                    .then((r) => toast(`В эталон сохранено решений: ${r.saved}`, 'success'))
                    .catch((e) => toast(e.message, 'error'))}>
            ★ В эталон
          </button>
          <button className="btn-secondary" onClick={() => setEditing('new')}>+ Строка</button>
          <button className="btn-primary"
                  onClick={() => exportEstimate(estimate.id, `Смета_${estimate.name}.xlsx`).catch((e) => toast(e.message, 'error'))}>
            ⭳ Выгрузить XLSX
          </button>
        </div>
      </div>

      <Coefficients view={view} onSaved={load} />

      {reviewCount > 0 && (
        <div className="rounded-lg bg-amber-50 border border-amber-200 px-4 py-3 text-sm text-amber-800">
          ⚠ Строк на проверку: <b>{reviewCount}</b>. Для них ИИ не подобрал расценку
          (или подбор шёл без ИИ) — проверьте шифр вручную. Ручная правка шифра снимает пометку.
        </div>
      )}

      <div className="card overflow-x-auto">
        <table className="w-full text-sm whitespace-nowrap">
          <thead>
            <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
              <th className="py-2 px-3 font-medium">№</th>
              <th className="py-2 px-3 font-medium">Оборудование</th>
              <th className="py-2 px-3 font-medium">Мероприятие</th>
              <th className="py-2 px-3 font-medium">Шифр</th>
              <th className="py-2 px-3 font-medium">Источник</th>
              <th className="py-2 px-3 font-medium">Период.</th>
              <th className="py-2 px-3 font-medium text-right">Опер/год</th>
              <th className="py-2 px-3 font-medium text-right">Кол-во</th>
              <th className="py-2 px-3 font-medium text-right">ЗП</th>
              <th className="py-2 px-3 font-medium text-right">ЭМ</th>
              <th className="py-2 px-3 font-medium text-right">МР</th>
              <th className="py-2 px-3 font-medium text-right">НР</th>
              <th className="py-2 px-3 font-medium text-right">НП</th>
              <th className="py-2 px-3 font-medium text-right">Без НДС</th>
              <th className="py-2 px-3 font-medium text-right">С НДС</th>
              <th className="py-2 px-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {rows.map(({ row, calc }, i) => {
              const prevSection = i > 0 ? rows[i - 1].row.section : null
              const showHeader = row.section && row.section !== prevSection
              return (
              <Fragment key={row.id}>
                {showHeader && (
                  <tr className="bg-slate-100/70">
                    <td colSpan={16} className="px-3 py-2 text-xs font-semibold text-slate-600 uppercase tracking-wide">
                      {row.section}
                    </td>
                  </tr>
                )}
              <tr className={`border-b border-slate-50 cursor-pointer ${
                    row.needsReview ? 'bg-amber-50 hover:bg-amber-100' : 'hover:bg-slate-50'}`}
                  onClick={() => setEditing(row)}>
                <td className="py-2 px-3 text-slate-400">{row.position}</td>
                <td className="py-2 px-3 max-w-[220px] truncate" title={row.equipmentName}>
                  {row.needsReview && (
                    <span className="mr-1 align-middle rounded bg-amber-200 text-amber-900 text-[10px] font-semibold px-1 py-0.5"
                          title="ИИ не подобрал расценку — проверьте вручную">на проверку</span>
                  )}
                  {row.equipmentName || '—'}
                </td>
                <td className="py-2 px-3 max-w-[220px] truncate" title={row.operationName}>{row.operationName || '—'}</td>
                <td className="py-2 px-3 font-mono text-xs">{row.rateCode || '—'}</td>
                <td className="py-2 px-3"><SourceBadge source={row.matchSource} /></td>
                <td className="py-2 px-3 text-xs">
                  {row.periodicity || '—'}
                  {row.correction != null && Number(row.correction) !== 1 && (
                    <span className="ml-1 rounded bg-slate-100 text-slate-600 text-[10px] px-1 py-0.5"
                          title="Поправочный коэффициент (S) — задан вручную">
                      ×{row.correction}
                    </span>
                  )}
                </td>
                <td className="py-2 px-3 text-right">{row.opsPerYear ?? '—'}</td>
                <td className="py-2 px-3 text-right">{row.qty ?? '—'}</td>
                <td className="py-2 px-3 text-right">{money(calc.zp)}</td>
                <td className="py-2 px-3 text-right">{money(calc.em)}</td>
                <td className="py-2 px-3 text-right">{money(calc.mr)}</td>
                <td className="py-2 px-3 text-right">{money(calc.nr)}</td>
                <td className="py-2 px-3 text-right">{money(calc.np)}</td>
                <td className="py-2 px-3 text-right">{money(calc.totalNoVat)}</td>
                <td className="py-2 px-3 text-right font-medium">{money(calc.totalWithVat)}</td>
                <td className="py-2 px-3 text-right">
                  <button className="text-red-500 hover:text-red-700"
                          onClick={(e) => { e.stopPropagation(); setDeletingRow(row) }}>✕</button>
                </td>
              </tr>
              </Fragment>
              )
            })}
            {rows.length === 0 && (
              <tr><td colSpan={16} className="text-center text-slate-400 py-12">
                Строк пока нет. Добавьте строку — укажите шифр расценки, цены подтянутся из каталога.
              </td></tr>
            )}
          </tbody>
          {rows.length > 0 && (
            <tfoot>
              <tr className="border-t-2 border-slate-200 font-semibold">
                <td className="py-3 px-3" colSpan={13}>ИТОГО в год</td>
                <td className="py-3 px-3 text-right">{money(totals.totalNoVat)}</td>
                <td className="py-3 px-3 text-right">{money(totals.totalWithVat)}</td>
                <td></td>
              </tr>
              <tr className="text-xs text-slate-500">
                <td className="py-1 px-3" colSpan={13}>в уровне цен РТ (справочно)</td>
                <td className="py-1 px-3 text-right">{money(totals.totalNoVatRt)}</td>
                <td className="py-1 px-3 text-right">{money(totals.totalWithVatRt)}</td>
                <td></td>
              </tr>
            </tfoot>
          )}
        </table>
      </div>

      {editing && (
        <RowModal estimateId={estimateId} row={editing === 'new' ? null : editing}
                  onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load() }} />
      )}
      {deletingRow && (
        <ConfirmDialog title="Удалить строку?" message="Строка сметы будет удалена."
                       confirmLabel="Удалить" danger onConfirm={removeRow} onClose={() => setDeletingRow(null)} />
      )}
    </div>
  )
}

function GenerateButton({ estimateId, onDone }: { estimateId: number; onDone: () => void }) {
  const [loading, setLoading] = useState(false)
  const run = async () => {
    setLoading(true)
    try {
      const res = await api.post<{ created: number; skipped: number; aiUsed: boolean }>(
        `/api/estimates/${estimateId}/generate`)
      toast(`Добавлено строк: ${res.created}${res.skipped ? `, пропущено (уже есть): ${res.skipped}` : ''}` +
        `${res.aiUsed ? '' : ' · без ИИ (подбор по поиску)'}`, 'success')
      onDone()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка генерации', 'error')
    } finally {
      setLoading(false)
    }
  }
  return (
    <button className="btn-secondary" onClick={run} disabled={loading}
            title="Заполнить смету из реестра оборудования: подбор расценки ИИ + периодичность из ПКМ">
      {loading ? '🤖 Генерация…' : '🤖 ИИ-черновик'}
    </button>
  )
}

function Coefficients({ view, onSaved }: { view: EstimateView; onSaved: () => void }) {
  const e = view.estimate
  const [vals, setVals] = useState({
    nrZp: e.nrZp, npZp: e.npZp, nrEm: e.nrEm, npEm: e.npEm, vat: e.vat, rtCoefficient: e.rtCoefficient,
  })
  const [open, setOpen] = useState(false)

  const save = async () => {
    try {
      await api.patch(`/api/estimates/${e.id}`, vals)
      toast('Коэффициенты сохранены', 'success')
      onSaved()
    } catch (err) {
      toast(err instanceof Error ? err.message : 'Ошибка', 'error')
    }
  }

  const field = (key: keyof typeof vals, label: string) => (
    <div>
      <label className="text-xs text-slate-500">{label}</label>
      <input className="input py-1" type="number" step="0.0001" value={vals[key]}
             onChange={(ev) => setVals({ ...vals, [key]: Number(ev.target.value) })} />
    </div>
  )

  return (
    <div className="card p-4">
      <button className="text-sm font-medium text-slate-700" onClick={() => setOpen(!open)}>
        {open ? '▾' : '▸'} Коэффициенты расчёта
      </button>
      {open && (
        <div className="mt-3 space-y-3">
          <div className="grid grid-cols-2 md:grid-cols-6 gap-3">
            {field('nrZp', 'НР от ЗП')}
            {field('npZp', 'НП от ЗП')}
            {field('nrEm', 'НР от ЭМ')}
            {field('npEm', 'НП от ЭМ')}
            {field('vat', 'НДС')}
            {field('rtCoefficient', 'Коэф. РТ')}
          </div>
          <button className="btn-primary" onClick={save}>Сохранить и пересчитать</button>
        </div>
      )}
    </div>
  )
}

/**
 * Состав работ расценки СН-2012 приходит одним текстом. Разбираем на пункты:
 * сначала по нумерации («1. … 2. …»), иначе по переводам строк, точкам с запятой
 * или предложениям — чтобы получился читаемый чек-лист, а не абзац.
 */
function splitComposition(text?: string): string[] {
  if (!text) return []
  const cleaned = text.replace(/^\s*состав\s+работ\s*:?\s*/i, '').trim()
  if (!cleaned) return []

  const numbered = cleaned.split(/(?=(?:^|\s)\d{1,2}[.)]\s)/).map((s) => s.trim()).filter(Boolean)
  const items = numbered.length > 1
    ? numbered
    : cleaned.split(/\n+|;\s*/).map((s) => s.trim()).filter(Boolean)
  const parts = items.length > 1
    ? items
    : cleaned.split(/(?<=[а-яa-z0-9)])\.\s+(?=[А-ЯA-Z])/).map((s) => s.trim()).filter(Boolean)

  return parts
    .map((s) => s.replace(/^\d{1,2}[.)]\s*/, '').replace(/[.;]\s*$/, '').trim())
    .filter((s) => s.length > 1)
}

/**
 * Мероприятие строки сметы: карточка с шифром и наименованием расценки; по клику
 * раскрывается состав работ из СН-2012 чек-листом (отметки — для себя, при проверке
 * строки; они не сохраняются) и поле для правки названия мероприятия.
 */
function OperationCard({ name, rateCode, onChange }: {
  name: string; rateCode: string; onChange: (v: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [rate, setRate] = useState<NormativeRate | null>(null)
  const [done, setDone] = useState<Record<number, boolean>>({})

  useEffect(() => {
    setRate(null)
    setDone({})
    if (!rateCode) return
    api.get<NormativeRate>(`/api/normatives/rates/by-code?code=${encodeURIComponent(rateCode)}`)
      .then(setRate)
      .catch(() => setRate(null))
  }, [rateCode])

  const steps = splitComposition(rate?.workComposition)

  return (
    <div>
      <label className="label">Мероприятие</label>
      <div className="rounded-lg border border-slate-200 bg-white overflow-hidden">
        <button type="button" onClick={() => setOpen(!open)}
                className="w-full text-left px-3 py-2.5 hover:bg-slate-50 transition-colors">
          <div className="flex items-start gap-2">
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-slate-800">{name || 'Мероприятие не указано'}</div>
              {rateCode && (
                <div className="flex items-center gap-2 mt-1 flex-wrap">
                  <span className="font-mono text-xs text-slate-500">{rateCode}</span>
                  {rate?.unit && <span className="text-xs text-slate-400">· {rate.unit}</span>}
                </div>
              )}
              {rate?.name && <div className="text-xs text-slate-500 mt-0.5">{rate.name}</div>}
              {steps.length > 0 && !open && (
                <div className="text-[11px] text-slate-400 mt-1">
                  Состав работ: {steps.length} {steps.length === 1 ? 'пункт' : steps.length < 5 ? 'пункта' : 'пунктов'} — нажмите, чтобы раскрыть
                </div>
              )}
            </div>
            <span className={`text-slate-400 text-xs mt-1 transition-transform ${open ? 'rotate-180' : ''}`}>▼</span>
          </div>
        </button>

        {open && (
          <div className="border-t border-slate-100 px-3 py-3 space-y-3 bg-slate-50/60">
            <div>
              <label className="text-xs text-slate-500">Название мероприятия</label>
              <input className="input py-1.5 text-sm mt-1" value={name}
                     onChange={(e) => onChange(e.target.value)}
                     placeholder="Техническое обслуживание…" />
            </div>

            {steps.length > 0 ? (
              <div>
                <div className="text-xs font-medium text-slate-500 mb-1.5">Состав работ по расценке (СН-2012)</div>
                <ul className="space-y-1">
                  {steps.map((s, i) => (
                    <li key={i}>
                      <label className="flex items-start gap-2 text-sm text-slate-700 cursor-pointer">
                        <input type="checkbox" className="mt-1 shrink-0" checked={!!done[i]}
                               onChange={(e) => setDone({ ...done, [i]: e.target.checked })} />
                        <span className={done[i] ? 'line-through text-slate-400' : ''}>{s}</span>
                      </label>
                    </li>
                  ))}
                </ul>
                <div className="text-[11px] text-slate-400 mt-1.5">
                  Отметки — для себя при проверке строки, в смету не попадают.
                </div>
              </div>
            ) : (
              <div className="text-xs text-slate-400">
                {rateCode
                  ? 'Состав работ для этой расценки в каталоге не распознан.'
                  : 'Укажите шифр расценки — состав работ подтянется из каталога.'}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

function RowModal({ estimateId, row, onClose, onSaved }: {
  estimateId: number; row: EstimateRowEntity | null; onClose: () => void; onSaved: () => void
}) {
  const [f, setF] = useState({
    section: row?.section ?? '',
    equipmentName: row?.equipmentName ?? '',
    equipmentType: row?.equipmentType ?? '',
    manufacturer: row?.manufacturer ?? '',
    operationName: row?.operationName ?? '',
    rateCode: row?.rateCode ?? '',
    periodicity: row?.periodicity ?? '',
    opsPerYear: row?.opsPerYear?.toString() ?? '',
    qty: row?.qty?.toString() ?? '',
    correction: row?.correction?.toString() ?? '1',
    justification: row?.justification ?? '',
    unitBasis: row?.unitBasis?.toString() ?? '',
    priceZp: row?.priceZp?.toString() ?? '',
    priceEm: row?.priceEm?.toString() ?? '',
    priceZpm: row?.priceZpm?.toString() ?? '',
    priceMr: row?.priceMr?.toString() ?? '',
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof f, v: string) => setF({ ...f, [k]: v })
  // смена шифра → очищаем цены, чтобы они подтянулись из каталога заново
  const setRateCode = (v: string) =>
    setF({ ...f, rateCode: v, unitBasis: '', priceZp: '', priceEm: '', priceZpm: '', priceMr: '' })

  // варианты расценки для выбора (когда совпадение с эталоном неточное)
  type Suggestion = { source: string; rateCode: string; rateName?: string; periodicity?: string; note?: string }
  let suggestions: Suggestion[] = []
  try { if (row?.suggestions) suggestions = JSON.parse(row.suggestions) } catch { suggestions = [] }
  const pickSuggestion = (s: Suggestion) =>
    setF({ ...f, rateCode: s.rateCode, periodicity: s.periodicity ?? f.periodicity,
           unitBasis: '', priceZp: '', priceEm: '', priceZpm: '', priceMr: '' })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    const body: Record<string, unknown> = {
      section: f.section || null,
      equipmentName: f.equipmentName || null,
      equipmentType: f.equipmentType || null,
      manufacturer: f.manufacturer || null,
      operationName: f.operationName || null,
      rateCode: f.rateCode || null,
      periodicity: f.periodicity || null,
      justification: f.justification || null,
      opsPerYear: f.opsPerYear ? Number(f.opsPerYear) : null,
      qty: f.qty ? Number(f.qty) : null,
      correction: f.correction ? Number(f.correction) : null,
      unitBasis: f.unitBasis ? Number(f.unitBasis) : null,
      priceZp: f.priceZp ? Number(f.priceZp) : null,
      priceEm: f.priceEm ? Number(f.priceEm) : null,
      priceZpm: f.priceZpm ? Number(f.priceZpm) : null,
      priceMr: f.priceMr ? Number(f.priceMr) : null,
    }
    // строка с вариантами выбора считается разрешённой после сохранения
    if (row?.suggestions) { body.suggestions = ''; body.needsReview = false }
    try {
      if (row) await api.patch(`/api/estimates/rows/${row.id}`, body)
      else await api.post(`/api/estimates/${estimateId}/rows`, body)
      onSaved()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
      setLoading(false)
    }
  }

  const input = (k: keyof typeof f, label: string, placeholder = '') => (
    <div>
      <label className="label">{label}</label>
      <input className="input" value={f[k]} onChange={(e) => set(k, e.target.value)} placeholder={placeholder} />
    </div>
  )

  const priceInput = (k: keyof typeof f, label: string) => (
    <div>
      <label className="text-xs text-slate-500">{label}</label>
      <input className="input py-1 text-sm" type="number" step="0.01" value={f[k]}
             onChange={(e) => set(k, e.target.value)} />
    </div>
  )

  return (
    <Modal title={row ? 'Строка сметы' : 'Новая строка'} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3 max-h-[70vh] overflow-y-auto pr-1">
        {input('section', 'Раздел', 'РАЗДЕЛ А. СКУД')}
        {input('equipmentName', 'Оборудование', 'Сервер СКУД')}
        <div className="grid grid-cols-2 gap-3">
          {input('equipmentType', 'Тип')}
          {input('manufacturer', 'Производитель')}
        </div>
        <OperationCard name={f.operationName} rateCode={f.rateCode}
                       onChange={(v) => set('operationName', v)} />

        {suggestions.length > 0 && (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 space-y-2">
            <div className="text-xs font-medium text-amber-800">
              Похожее оборудование есть в эталоне, но точного совпадения нет — выберите расценку:
            </div>
            {suggestions.map((s, i) => (
              <button type="button" key={i} onClick={() => pickSuggestion(s)}
                      className={`w-full text-left rounded-md border px-3 py-2 text-sm transition-colors ${
                        f.rateCode === s.rateCode
                          ? 'border-primary-400 bg-primary-50'
                          : 'border-slate-200 bg-white hover:border-primary-300'}`}>
                <div className="flex items-center gap-2">
                  <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                    s.source === 'ETALON' ? 'bg-emerald-100 text-emerald-800' : 'bg-sky-100 text-sky-800'}`}>
                    {s.source === 'ETALON' ? 'эталон' : 'ИИ'}
                  </span>
                  <span className="font-mono text-xs text-slate-700">{s.rateCode}</span>
                  {s.periodicity && <span className="text-xs text-slate-400">· {s.periodicity}</span>}
                </div>
                {s.rateName && <div className="text-xs text-slate-600 mt-0.5">{s.rateName}</div>}
                {s.note && <div className="text-[11px] text-slate-400 mt-0.5">{s.note}</div>}
              </button>
            ))}
            <div className="text-[11px] text-amber-700">Клик подставит шифр и периодичность. Сохранение снимет пометку «на проверку».</div>
          </div>
        )}

        <div>
          <label className="label">Шифр расценки</label>
          <input className="input" value={f.rateCode} onChange={(e) => setRateCode(e.target.value)}
                 placeholder="22-2203-113-1/1" />
          <p className="text-xs text-slate-400 mt-1">
            Цены, наименование и измеритель подтянутся из каталога по шифру. При смене шифра — обновятся,
            а в блоке «Мероприятие» появится состав работ новой расценки.
          </p>
        </div>
        <div className="grid grid-cols-3 gap-3">
          {input('periodicity', 'Периодичность', 'раз в 1 мес.')}
          {input('opsPerYear', 'Опер/год', 'авто из период.')}
          {input('qty', 'Количество')}
        </div>

        <div className="rounded-lg border border-slate-200 p-3 space-y-2">
          <div className="text-xs font-medium text-slate-500">
            Цены расценки на единицу (из каталога — можно исправить, если распознались неверно)
          </div>
          <div className="grid grid-cols-5 gap-2">
            {priceInput('priceZp', 'ЗП')}
            {priceInput('priceEm', 'ЭМ')}
            {priceInput('priceZpm', 'ЗПМ')}
            {priceInput('priceMr', 'МР')}
            {priceInput('unitBasis', 'Измер. (M)')}
          </div>
        </div>

        {input('correction', 'Поправочный коэффициент (S)', '1')}
        <div>
          <label className="label">Обоснование периодичности</label>
          <textarea className="input" rows={2} value={f.justification}
                    onChange={(e) => set('justification', e.target.value)}
                    placeholder="ПКМ / паспорт / ГОСТ — чем установлена периодичность" />
          <p className="text-xs text-slate-400 mt-1">
            Идёт заказчику в колонку «обоснование периодичности».
          </p>
        </div>
        {row?.matchNote && (
          <div className="rounded-lg bg-slate-50 border border-slate-200 p-3">
            <div className="text-xs font-medium text-slate-500 mb-1">Как подобрана расценка (служебное)</div>
            <div className="text-sm text-slate-600">{row.matchNote}</div>
          </div>
        )}
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2 pt-1">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? 'Сохранение…' : 'Сохранить'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
