import { Fragment, FormEvent, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, exportEstimate } from '../api'
import { EstimateRowEntity, EstimateView, NormativeRate } from '../types'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

type Fix = {
  action: string; rateCode?: string; periodicity?: string
  opsPerYear?: number; qty?: number; operationName?: string
}
type Finding = {
  position?: number; severity: string; category?: string
  title: string; detail?: string; impact?: string
  fix?: Fix; applied?: boolean; explanation?: string
}

/** Что именно сделает правка — человеку, а не кодом действия. */
function fixLabel(fix: Fix): string {
  switch (fix.action) {
    case 'SET_RATE': return `заменить расценку на ${fix.rateCode}`
    case 'SET_PERIODICITY': return fix.periodicity
      ? `периодичность → ${fix.periodicity}${fix.opsPerYear != null ? `, ${fix.opsPerYear} опер./год` : ''}`
      : `операций в год → ${fix.opsPerYear}`
    case 'SET_QTY': return `количество → ${fix.qty}`
    case 'SET_OPERATION_NAME': return `мероприятие → ${fix.operationName}`
    case 'ADD_ROW': return `добавить работу ${fix.rateCode}`
      + (fix.periodicity ? ` (${fix.periodicity})` : '')
    default: return fix.action
  }
}
type ReviewResult = { findings: Finding[]; aiConfigured: boolean; error?: string; model?: string }
type ReviewModels = { models: string[]; defaultModel?: string }

/**
 * Проверка готовой сметы сильной моделью. Запускается вручную перед сдачей:
 * это не подсказка в процессе, а вычитка целиком — и она ничего не меняет.
 * Если в настройках перечислено несколько моделей, рядом появляется выбор:
 * так их можно сравнить на одной смете, не пересобирая контейнер.
 */
function ReviewButton({ estimateId, hasSaved, onFindings }: {
  estimateId: number; hasSaved: boolean; onFindings: (r: ReviewResult) => void
}) {
  const [loading, setLoading] = useState(false)
  const [models, setModels] = useState<string[]>([])
  const [model, setModel] = useState('')
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    api.get<ReviewModels>('/api/estimates/review-models')
      .then((r) => { setModels(r.models ?? []); setModel(r.defaultModel ?? '') })
      .catch(() => setModels([]))
  }, [])

  // повторный прогон затирает сохранённые замечания и стоит денег — спрашиваем
  const start = () => (hasSaved ? setConfirming(true) : run())

  const run = async () => {
    setConfirming(false)
    setLoading(true)
    try {
      const query = model ? `?model=${encodeURIComponent(model)}` : ''
      const result = await api.post<ReviewResult>(`/api/estimates/${estimateId}/review${query}`)
      onFindings(result)
      if (result.error) toast(result.error, 'error')
      else if (result.findings.length === 0) toast('Замечаний не найдено', 'success')
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка проверки', 'error')
    } finally {
      setLoading(false)
    }
  }

  // выбор модели нужен только при сравнении, поэтому он спрятан под стрелку,
  // а не занимает место в шапке наравне с действиями
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="relative flex items-stretch">
      <button className="toolbar-btn" onClick={start} disabled={loading}
              title="Проверить смету целиком: пропущенные работы, чужие расценки, противоречия">
        <span className="text-slate-400">🔍</span> {loading ? 'Проверяем…' : 'Проверить'}
      </button>
      {confirming && (
        <ConfirmDialog title="Проверить заново?"
                       message={'Сохранённые замечания предыдущей проверки будут удалены безвозвратно, '
                         + 'а прогон модели снова потратит деньги. Если замечания ещё нужны — сначала разберите их.'}
                       confirmLabel="Проверить заново" danger
                       onConfirm={run} onClose={() => setConfirming(false)} />
      )}
      {models.length > 1 && (
        <button type="button" className="toolbar-btn px-1.5 text-slate-400"
                onClick={() => setMenuOpen(!menuOpen)} disabled={loading}
                title={`Модель проверки: ${model}`}>▾</button>
      )}
      {menuOpen && (
        <>
          <div className="fixed inset-0 z-10" onClick={() => setMenuOpen(false)} />
          <div className="absolute right-0 top-full mt-1 z-20 w-64 card p-1">
            <div className="px-2 py-1 text-[11px] text-slate-400">Модель проверки</div>
            {models.map((m) => (
              <button type="button" key={m}
                      className={`w-full text-left rounded px-2 py-1.5 text-xs hover:bg-slate-50 ${
                        m === model ? 'text-primary-700 font-medium' : 'text-slate-600'}`}
                      onClick={() => { setModel(m); setMenuOpen(false) }}>
                {m === model ? '✓ ' : '   '}{m}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Замечания проверки: список со ссылкой на строку сметы. Сворачивается, но не
 * выбрасывается — прогон стоит денег и времени, терять его по нажатию «Скрыть»
 * нельзя. Убрать результат совсем можно крестиком.
 */
function ReviewPanel({ result, collapsed, onToggle, onDismiss, onOpenRow, onApply,
                       onShowRow, onExplain }: {
  result: ReviewResult; collapsed: boolean; onToggle: () => void
  onDismiss: () => void; onOpenRow: (position: number) => void
  onApply: (indexes: number[]) => Promise<void>
  onShowRow: (position: number) => void
  onExplain: (index: number) => Promise<string>
}) {
  const [applying, setApplying] = useState<number | 'all' | null>(null)
  const [confirmAll, setConfirmAll] = useState(false)
  // пояснения раскрываются по клику и кэшируются на сервере: повторное открытие бесплатно
  const [openExplain, setOpenExplain] = useState<Record<number, boolean>>({})
  const [explanations, setExplanations] = useState<Record<number, string>>({})
  const [explaining, setExplaining] = useState<number | null>(null)

  const toggleExplain = async (index: number, saved?: string) => {
    const isOpen = !!openExplain[index]
    setOpenExplain({ ...openExplain, [index]: !isOpen })
    if (isOpen || saved || explanations[index]) return
    setExplaining(index)
    try {
      const text = await onExplain(index)
      setExplanations((prev) => ({ ...prev, [index]: text }))
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Не удалось разобрать замечание', 'error')
    } finally {
      setExplaining(null)
    }
  }
  // применить можно только замечания с конкретной правкой и ещё не применённые
  const pending = result.findings
    .map((f, i) => ({ f, i }))
    .filter(({ f }) => f.fix && !f.applied)

  const apply = async (indexes: number[], marker: number | 'all') => {
    setApplying(marker)
    setConfirmAll(false)
    try { await onApply(indexes) } finally { setApplying(null) }
  }

  const cls: Record<string, string> = {
    HIGH: 'border-red-200 bg-red-50', MEDIUM: 'border-amber-200 bg-amber-50',
    LOW: 'border-slate-200 bg-slate-50',
  }
  const label: Record<string, string> = { HIGH: 'важно', MEDIUM: 'проверить', LOW: 'оформление' }
  const counts = { HIGH: 0, MEDIUM: 0, LOW: 0 } as Record<string, number>
  result.findings.forEach((f) => { counts[f.severity] = (counts[f.severity] ?? 0) + 1 })

  return (
    <div className="card p-5 space-y-3">
      <div className="flex items-center justify-between gap-4">
        <div className="min-w-0">
          <div className="font-medium text-slate-900">
            Проверка сметы
            {collapsed && result.findings.length > 0 && (
              <span className="text-slate-500 font-normal">
                {' — '}замечаний: {result.findings.length}
                {counts.HIGH > 0 && <span className="text-red-600"> · важно {counts.HIGH}</span>}
              </span>
            )}
          </div>
          <p className="text-sm text-slate-500 mt-0.5 truncate">
            Замечания модели. Смета не изменена — решения за вами.
            {result.model && <span className="text-slate-400"> · {result.model}</span>}
          </p>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          {pending.length > 0 && (
            <button className="btn-secondary text-sm py-1.5" onClick={() => setConfirmAll(true)}
                    disabled={applying !== null}
                    title="Применить все замечания, для которых предложена конкретная правка">
              {applying === 'all' ? 'Применяем…' : `Применить всё (${pending.length})`}
            </button>
          )}
          <button className="btn-ghost text-sm" onClick={onToggle}>
            {collapsed ? 'Показать' : 'Свернуть'}
          </button>
          <button className="btn-ghost text-sm text-slate-400" onClick={onDismiss}
                  title="Убрать результат проверки">✕</button>
        </div>
        {confirmAll && (
          <ConfirmDialog title={`Применить ${pending.length} правк${pending.length === 1 ? 'у' : 'и'}?`}
                         message={'Смета изменится: ' + pending.map(({ f }) => fixLabel(f.fix!)).join('; ')
                           + '. Остальные замечания требуют вашего решения и применены не будут.'}
                         confirmLabel="Применить"
                         onConfirm={() => apply(pending.map(({ i }) => i), 'all')}
                         onClose={() => setConfirmAll(false)} />
        )}
      </div>
      {!collapsed && (result.error ? (
        <div className="text-sm text-red-600">{result.error}</div>
      ) : result.findings.length === 0 ? (
        <div className="text-sm text-emerald-700">Замечаний не найдено.</div>
      ) : (
        <div className="space-y-2">
          {result.findings.map((fnd, i) => (
            <div key={i} className={`rounded-lg border px-3 py-2 ${
              fnd.applied ? 'border-emerald-200 bg-emerald-50' : cls[fnd.severity] ?? cls.MEDIUM}`}>
              <div className="flex items-center gap-2 flex-wrap">
                <span className="rounded bg-white/70 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600">
                  {fnd.applied ? 'применено' : label[fnd.severity] ?? 'проверить'}
                </span>
                {fnd.position != null && (
                  <>
                    <button className="text-xs text-primary-600 hover:underline"
                            onClick={() => onShowRow(fnd.position!)}
                            title="Прокрутить смету к этой строке и подсветить её">
                      показать строку {fnd.position}
                    </button>
                    <button className="text-xs text-slate-500 hover:underline"
                            onClick={() => onOpenRow(fnd.position!)}
                            title="Открыть строку для правки">открыть</button>
                  </>
                )}
                {fnd.category && <span className="text-[11px] text-slate-500">{fnd.category}</span>}
              </div>
              <button type="button" className="text-left w-full"
                      onClick={() => toggleExplain(i, fnd.explanation)}
                      title="Разобрать замечание подробно">
                <div className="text-sm font-medium text-slate-800 mt-1 hover:underline">
                  {fnd.title}
                  <span className="ml-1 text-slate-400 text-xs">{openExplain[i] ? '▴' : '▾'}</span>
                </div>
              </button>
              {fnd.detail && <div className="text-sm text-slate-600 mt-0.5">{fnd.detail}</div>}
              {fnd.impact && <div className="text-xs text-slate-500 mt-0.5">Влияние: {fnd.impact}</div>}

              {openExplain[i] && (
                <div className="mt-2 rounded-md bg-white/70 border border-slate-200 px-3 py-2">
                  <div className="text-[11px] font-medium text-slate-500 mb-1">Разбор замечания</div>
                  {explaining === i ? (
                    <div className="text-sm text-slate-400">Разбираем…</div>
                  ) : (
                    <div className="text-sm text-slate-700 whitespace-pre-line">
                      {fnd.explanation || explanations[i] || 'Пояснение получить не удалось.'}
                    </div>
                  )}
                </div>
              )}

              {fnd.fix && (
                <div className="flex items-center gap-2 flex-wrap mt-2">
                  <span className="text-xs text-slate-500">Правка: {fixLabel(fnd.fix)}</span>
                  {!fnd.applied && (
                    <button className="btn-secondary text-xs py-1" disabled={applying !== null}
                            onClick={() => apply([i], i)}>
                      {applying === i ? 'Применяем…' : 'Применить'}
                    </button>
                  )}
                </div>
              )}
              {!fnd.fix && !fnd.applied && (
                <div className="text-[11px] text-slate-400 mt-2">
                  Автоматической правки нет — решение за инженером.
                </div>
              )}
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}

type Alternative = {
  rateCode: string; rateName?: string; reason?: string; unit?: string
  laborCost?: number; machineCost?: number; machineLabor?: number
  materialCost?: number; laborHours?: number; workComposition?: string
}
type Alternatives = { options: Alternative[]; aiConfigured: boolean }

/**
 * Аналоги, подобранные ИИ, кэшируются на время сессии по строке и текущему шифру:
 * повторное открытие той же строки уже не обращается к модели.
 */
const alternativesCache = new Map<string, Alternatives>()

/** Число из каталога в значение поля формы; пусто, если значения нет. */
const num = (v?: number) => (v == null ? '' : String(v))

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
  const [merging, setMerging] = useState(false)
  const [findings, setFindings] = useState<ReviewResult | null>(null)
  const [findingsCollapsed, setFindingsCollapsed] = useState(false)
  // строка, к которой перешли из замечания: подсвечиваем ненадолго, чтобы её
  // было видно среди полусотни других, но подсветка не осталась насовсем
  const [highlighted, setHighlighted] = useState<number | null>(null)

  const showRow = (position: number) => {
    document.getElementById(`estimate-row-${position}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    setHighlighted(position)
    setTimeout(() => setHighlighted((current) => (current === position ? null : current)), 2500)
  }

  const load = () => {
    api.get<EstimateView>(`/api/estimates/${estimateId}`).then(setView).catch((e) => setError(e.message))
  }
  useEffect(() => { load() }, [estimateId])

  // сохранённая проверка переживает обновление страницы; показываем её свёрнутой,
  // чтобы итог был на виду, но не заслонял смету
  useEffect(() => {
    api.get<ReviewResult | undefined>(`/api/estimates/${estimateId}/review`)
      .then((r) => { if (r) { setFindings(r); setFindingsCollapsed(true) } })
      .catch(() => setFindings(null))
  }, [estimateId])

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
      <div className="flex items-start justify-between gap-6 flex-wrap">
        <div className="min-w-0">
          <Link to="/estimates" className="text-sm text-primary-600 hover:underline">← К сметам</Link>
          <h1 className="text-2xl font-semibold text-slate-900 mt-1 truncate">{estimate.name}</h1>
          <div className="text-sm text-slate-500 mt-0.5">
            {rows.length} {rows.length === 1 ? 'строка' : rows.length < 5 ? 'строки' : 'строк'}
            {reviewCount > 0 && (
              <span className="text-amber-700"> · {reviewCount} на проверку</span>
            )}
          </div>
        </div>

        {/*
          Работа со сметой — одной группой, отдельно от выгрузки результата.
          Значки различают природу действия: эмодзи у того, что делает ИИ,
          простые знаки у ручных операций.
        */}
        <div className="flex items-center gap-2 flex-wrap">
          <div className="toolbar">
            <GenerateButton estimateId={estimate.id} onDone={load} />
            <ReviewButton estimateId={estimate.id} hasSaved={findings != null && !findings.error}
                          onFindings={(r) => { setFindings(r); setFindingsCollapsed(false) }} />
            <button className="toolbar-btn" onClick={() => setMerging(true)}
                    title="Объединить строки с одной расценкой в одну позицию">
              <span className="text-slate-400">⇄</span> Объединить
            </button>
            <button className="toolbar-btn" onClick={() => setEditing('new')}
                    title="Добавить строку вручную">
              <span className="text-slate-400">+</span> Строка
            </button>
            <button className="toolbar-btn"
                    title="Сохранить строки в память решений — переиспользуются на других объектах"
                    onClick={() => api.post<{ saved: number }>(`/api/estimates/${estimate.id}/promote`)
                      .then((r) => toast(`В эталон сохранено решений: ${r.saved}`, 'success'))
                      .catch((e) => toast(e.message, 'error'))}>
              <span className="text-amber-500">★</span> В эталон
            </button>
          </div>
          <button className="btn-primary"
                  onClick={() => exportEstimate(estimate.id, `Смета_${estimate.name}.xlsx`).catch((e) => toast(e.message, 'error'))}>
            ⭳ Выгрузить XLSX
          </button>
        </div>
      </div>

      <Coefficients view={view} onSaved={load} />

      {findings && (
        <ReviewPanel result={findings} collapsed={findingsCollapsed}
                     onToggle={() => setFindingsCollapsed(!findingsCollapsed)}
                     onDismiss={() => setFindings(null)}
                     onOpenRow={(p) => {
                       const target = rows.find((r) => r.row.position === p)
                       if (target) setEditing(target.row)
                     }}
                     onShowRow={showRow}
                     onExplain={async (index) => {
                       const res = await api.post<{ explanation: string }>(
                         `/api/estimates/${estimateId}/review/explain?index=${index}`)
                       return res.explanation
                     }}
                     onApply={async (indexes) => {
                       try {
                         const res = await api.post<{ applied: number; skipped: number; messages: string[] }>(
                           `/api/estimates/${estimateId}/review/apply`, { indexes })
                         toast(res.applied > 0
                           ? `Применено правок: ${res.applied}${res.skipped ? `, пропущено: ${res.skipped}` : ''}`
                           : 'Ничего не применено', res.applied > 0 ? 'success' : 'info')
                         // смета изменилась, а замечания получили отметку «применено»
                         load()
                         const fresh = await api.get<ReviewResult | undefined>(
                           `/api/estimates/${estimateId}/review`)
                         if (fresh) setFindings(fresh)
                       } catch (e) {
                         toast(e instanceof Error ? e.message : 'Ошибка применения', 'error')
                       }
                     }} />
      )}

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
              <tr id={`estimate-row-${row.position}`}
                  className={`border-b border-slate-50 cursor-pointer transition-colors ${
                    highlighted === row.position
                      ? 'bg-primary-100 ring-2 ring-inset ring-primary-400'
                      : row.needsReview ? 'bg-amber-50 hover:bg-amber-100' : 'hover:bg-slate-50'}`}
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
      {merging && (
        <MergeModal estimateId={estimateId} onClose={() => setMerging(false)}
                    onMerged={() => { setMerging(false); load() }} />
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
    <button className="toolbar-btn" onClick={run} disabled={loading}
            title="Заполнить смету из реестра оборудования: подбор расценки ИИ + периодичность из ПКМ">
      <span>🤖</span> {loading ? 'Генерация…' : 'ИИ-черновик'}
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

type DuplicateGroup = {
  equipmentName?: string; equipmentNames: string[]; rateCode: string; periodicity?: string
  totalQty?: number; rowIds: number[]
}

/**
 * Объединение одинаковых строк: одно оборудование с одной расценкой часто разнесено
 * по этажам или шлейфам, а в смете это одна позиция с суммарным количеством.
 * Приложение показывает найденные группы, объединяет только отмеченные.
 */
function MergeModal({ estimateId, onClose, onMerged }: {
  estimateId: number; onClose: () => void; onMerged: () => void
}) {
  const [groups, setGroups] = useState<DuplicateGroup[] | null>(null)
  const [picked, setPicked] = useState<Record<number, boolean>>({})
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.get<DuplicateGroup[]>(`/api/estimates/${estimateId}/duplicate-groups`)
      .then((g) => {
        setGroups(g)
        setPicked(Object.fromEntries(g.map((_, i) => [i, true])))
      })
      .catch((e) => { toast(e.message, 'error'); onClose() })
  }, [estimateId])

  const merge = async () => {
    if (!groups) return
    const selected = groups.filter((_, i) => picked[i])
    if (selected.length === 0) return
    setBusy(true)
    try {
      for (const g of selected) {
        await api.post(`/api/estimates/${estimateId}/merge-rows`, { rowIds: g.rowIds })
      }
      toast(`Объединено групп: ${selected.length}`, 'success')
      onMerged()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка объединения', 'error')
      setBusy(false)
    }
  }

  const count = groups?.filter((_, i) => picked[i]).length ?? 0

  return (
    <Modal title="Объединить одинаковые строки" onClose={onClose}>
      {!groups ? (
        <div className="text-slate-400 py-6 text-center">Ищем одинаковые строки…</div>
      ) : groups.length === 0 ? (
        <div className="text-slate-500 py-6 text-center">
          Одинаковых строк нет. Объединяются позиции с одной расценкой, одной периодичностью,
          одним числом операций в год и одинаковыми ценами.
        </div>
      ) : (
        <div className="space-y-3">
          <div className="text-sm text-slate-500">
            Количество суммируется, лишние строки удаляются. Снимите отметку с групп, которые
            нужно оставить как есть.
          </div>
          <div className="space-y-2 max-h-[50vh] overflow-y-auto pr-1">
            {groups.map((g, i) => (
              <label key={i} className="flex items-start gap-2 rounded-lg border border-slate-200 px-3 py-2 cursor-pointer hover:border-primary-300">
                <input type="checkbox" className="mt-1 shrink-0" checked={!!picked[i]}
                       onChange={(e) => setPicked({ ...picked, [i]: e.target.checked })} />
                <div className="min-w-0">
                  <div className="text-sm font-medium text-slate-800">{g.equipmentName || '—'}</div>
                  <div className="text-xs text-slate-500 mt-0.5">
                    <span className="font-mono">{g.rateCode}</span>
                    {g.periodicity && <span> · {g.periodicity}</span>}
                  </div>
                  {g.equipmentNames?.length > 1 && (
                    <div className="text-xs text-amber-700 mt-1">
                      Разные модели по одной расценке:
                      <ul className="list-disc list-inside text-slate-500 mt-0.5">
                        {g.equipmentNames.map((n, k) => <li key={k}>{n}</li>)}
                      </ul>
                    </div>
                  )}
                  <div className="text-xs text-slate-400 mt-0.5">
                    {g.rowIds.length} строк(и) → 1, количество {g.totalQty ?? '—'}
                  </div>
                </div>
              </label>
            ))}
          </div>
          <div className="flex justify-end gap-2 pt-1">
            <button className="btn-secondary" onClick={onClose}>Отмена</button>
            <button className="btn-primary" onClick={merge} disabled={busy || count === 0}>
              {busy ? 'Объединяем…' : `Объединить (${count})`}
            </button>
          </div>
        </div>
      )}
    </Modal>
  )
}

/** Значение поля формы в число; пусто и мусор — undefined, а не 0. */
const numOrUndef = (v: string) => {
  const n = Number(String(v).replace(',', '.'))
  return v.trim() === '' || Number.isNaN(n) ? undefined : n
}

/**
 * Карточка аналога: шифр, наименование и довод ИИ. Раскрывается на месте —
 * показывает цены с отклонением от текущей расценки и состав работ, чтобы
 * сравнить вариант, не подставляя его в смету.
 */
function AlternativeCard({ alt, badge, periodicity, selected, current, onPick }: {
  alt: Alternative
  badge: 'ИИ' | 'эталон'
  periodicity?: string
  selected: boolean
  current: { zp?: number; em?: number; zpm?: number; mr?: number; hours?: number }
  onPick: () => void
}) {
  const [open, setOpen] = useState(false)
  // у эталонных вариантов цен и состава работ в подсказке нет — подтягиваем их
  // из каталога, но только когда карточку действительно раскрыли
  const [details, setDetails] = useState<NormativeRate | null>(null)
  useEffect(() => {
    if (!open || alt.laborCost != null || details) return
    api.get<NormativeRate>(`/api/normatives/rates/by-code?code=${encodeURIComponent(alt.rateCode)}`)
      .then(setDetails)
      .catch(() => setDetails(null))
  }, [open])

  const full = alt.laborCost != null ? alt : { ...alt, ...(details ?? {}) }
  const steps = splitComposition(full.workComposition)

  return (
    <div className={`rounded-md border text-sm transition-colors ${
      selected ? 'border-primary-400 bg-primary-50' : 'border-slate-200 bg-white'}`}>
      <div className="px-3 py-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${
            badge === 'ИИ' ? 'bg-sky-100 text-sky-800' : 'bg-emerald-100 text-emerald-800'}`}>{badge}</span>
          <span className="font-mono text-xs text-slate-700">{alt.rateCode}</span>
          {periodicity && <span className="text-[11px] text-slate-400">· {periodicity}</span>}
          {full.unit && <span className="text-[11px] text-slate-400">· {full.unit}</span>}
        </div>
        {alt.rateName && <div className="text-xs text-slate-600 mt-0.5">{alt.rateName}</div>}
        {alt.reason && <div className="text-[11px] text-slate-400 mt-0.5">{alt.reason}</div>}
        <div className="flex gap-2 mt-2">
          <button type="button" className="btn-ghost text-xs py-1" onClick={() => setOpen(!open)}>
            {open ? 'Свернуть' : 'Состав работ и цены'}
          </button>
          <button type="button" className="btn-secondary text-xs py-1" onClick={onPick}>
            Взять эту
          </button>
        </div>
      </div>

      {open && (
        <div className="border-t border-slate-100 px-3 py-2 space-y-3 bg-slate-50/60">
          <div className="grid grid-cols-5 gap-2">
            <Delta label="ЗП" value={full.laborCost} current={current.zp} />
            <Delta label="ЭМ" value={full.machineCost} current={current.em} />
            <Delta label="ЗПМ" value={full.machineLabor} current={current.zpm} />
            <Delta label="МР" value={full.materialCost} current={current.mr} />
            <Delta label="чел.-ч" value={full.laborHours} current={current.hours} />
          </div>
          {steps.length > 0 ? (
            <div>
              <div className="text-xs font-medium text-slate-500 mb-1">Состав работ по расценке</div>
              <ol className="space-y-1 list-decimal list-inside">
                {steps.map((s, i) => <li key={i} className="text-sm text-slate-700">{s}</li>)}
              </ol>
            </div>
          ) : (
            <div className="text-xs text-slate-400">
              {full.laborCost == null && !details
                ? 'Загружаем расценку из каталога…'
                : 'Состав работ для этой расценки в каталоге не распознан.'}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

/**
 * Показатель аналога рядом с текущей расценкой: значение и отклонение от неё.
 * Дороже — зелёным, дешевле — красным: инженер сразу видит, во что обойдётся замена.
 */
function Delta({ label, value, current }: { label: string; value?: number; current?: number }) {
  const diff = value != null && current != null ? value - current : null
  const sign = diff == null || Math.abs(diff) < 0.005 ? null : diff > 0 ? 'up' : 'down'
  return (
    <div>
      <div className="text-[10px] text-slate-400">{label}</div>
      <div className="text-sm text-slate-700">{value == null ? '—' : money(value)}</div>
      {sign && (
        <div className={`text-[11px] ${sign === 'up' ? 'text-emerald-600' : 'text-red-600'}`}>
          {diff! > 0 ? '+' : '−'}{money(Math.abs(diff!))}
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
function OperationCard({ name, rateCode, rate, onChange }: {
  name: string; rateCode: string; rate: NormativeRate | null; onChange: (v: string) => void
}) {
  const [open, setOpen] = useState(false)
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
                <ol className="space-y-1 list-decimal list-inside">
                  {steps.map((s, i) => (
                    <li key={i} className="text-sm text-slate-700">{s}</li>
                  ))}
                </ol>
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
  const [rate, setRate] = useState<NormativeRate | null>(null)
  const [alternatives, setAlternatives] = useState<Alternatives | null>(null)
  const [altLoading, setAltLoading] = useState(false)

  /**
   * Аналоги от ИИ для уже подобранной расценки — чтобы было с чем сравнить выбор.
   * Модель видит только короткий список найденных по каталогу расценок, поэтому
   * запрос дешёвый; результат кэшируется на сессию.
   */
  useEffect(() => {
    if (!row?.id || !row.rateCode) return
    const key = `${row.id}|${row.rateCode}`
    const cached = alternativesCache.get(key)
    if (cached) { setAlternatives(cached); return }
    setAltLoading(true)
    api.get<Alternatives>(`/api/estimates/rows/${row.id}/alternatives`)
      .then((a) => { alternativesCache.set(key, a); setAlternatives(a) })
      .catch(() => setAlternatives(null))
      .finally(() => setAltLoading(false))
  }, [row?.id])

  /**
   * Расценка по шифру: её состав работ показывает карточка мероприятия, а цены
   * подставляются в пустые поля сразу — раньше они появлялись только после
   * сохранения, и выбранный вариант выглядел «пустым».
   */
  useEffect(() => {
    const code = f.rateCode.trim()
    if (!code) { setRate(null); return }
    const timer = setTimeout(() => {
      api.get<NormativeRate>(`/api/normatives/rates/by-code?code=${encodeURIComponent(code)}`)
        .then((r) => {
          setRate(r)
          setF((prev) => prev.rateCode.trim() !== code ? prev : {
            ...prev,
            // не затираем то, что инженер уже поправил руками
            priceZp: prev.priceZp || num(r.laborCost),
            priceEm: prev.priceEm || num(r.machineCost),
            priceZpm: prev.priceZpm || num(r.machineLabor),
            priceMr: prev.priceMr || num(r.materialCost),
            // измеритель не трогаем: он выводится из единицы расценки на сервере,
            // и пустое поле означает «взять из каталога», а не «единица»
          })
        })
        .catch(() => setRate(null))
    }, 300)   // шифр вводят посимвольно — не дёргаем каталог на каждую букву
    return () => clearTimeout(timer)
  }, [f.rateCode])

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
        <OperationCard name={f.operationName} rateCode={f.rateCode} rate={rate}
                       onChange={(v) => set('operationName', v)} />

        {suggestions.length > 0 && (
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 space-y-2">
            <div className="text-xs font-medium text-amber-800">
              Похожее оборудование есть в эталоне, но точного совпадения нет — выберите расценку:
            </div>
            {suggestions.map((s, i) => (
              <AlternativeCard key={i}
                               alt={{ rateCode: s.rateCode, rateName: s.rateName, reason: s.note }}
                               badge={s.source === 'ETALON' ? 'эталон' : 'ИИ'}
                               periodicity={s.periodicity}
                               selected={f.rateCode === s.rateCode}
                               current={{ zp: numOrUndef(f.priceZp), em: numOrUndef(f.priceEm),
                                          zpm: numOrUndef(f.priceZpm), mr: numOrUndef(f.priceMr),
                                          hours: rate?.laborHours }}
                               onPick={() => pickSuggestion(s)} />
            ))}
            <div className="text-[11px] text-amber-700">
              «Состав работ и цены» покажет вариант с отклонением от текущей расценки, ничего не меняя.
              «Взять эту» подставит шифр и периодичность; сохранение снимет пометку «на проверку».
            </div>
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
        {row?.rateCode && (altLoading || alternatives) && (
          <div className="rounded-lg border border-sky-200 bg-sky-50/60 p-3 space-y-2">
            <div className="text-xs font-medium text-sky-800">
              Аналоги от ИИ — с чем сравнить выбранную расценку
            </div>
            {altLoading ? (
              <div className="text-sm text-slate-400">Подбираем аналоги…</div>
            ) : !alternatives?.aiConfigured ? (
              <div className="text-sm text-slate-500">ИИ выключен — аналоги не подбираются.</div>
            ) : alternatives.options.length === 0 ? (
              <div className="text-sm text-slate-500">
                ИИ не нашёл в каталоге других подходящих расценок.
              </div>
            ) : (
              <>
                {alternatives.options.map((a, i) => (
                  <AlternativeCard key={i} alt={a} badge="ИИ" selected={f.rateCode === a.rateCode}
                                   current={{ zp: numOrUndef(f.priceZp), em: numOrUndef(f.priceEm),
                                              zpm: numOrUndef(f.priceZpm), mr: numOrUndef(f.priceMr),
                                              hours: rate?.laborHours }}
                                   onPick={() => setRateCode(a.rateCode)} />
                ))}
                <div className="text-[11px] text-sky-700">
                  Отклонения — от текущей расценки строки. Состав работ можно посмотреть,
                  не выбирая аналог; «Взять эту» подставит шифр и цены.
                </div>
              </>
            )}
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
