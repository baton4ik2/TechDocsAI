import { useEffect, useState } from 'react'
import { api } from '../api'
import { MidioPending, MidioSyncResult, MidioWorkPreview } from '../types'
import { toast } from './Toast'

/**
 * Перенос готовых регламентов из Midio. Кнопка появляется только когда
 * интеграция настроена — иначе она обещала бы то, чего нет.
 */
export default function MidioSync({ onChange, extraAction }: {
  onChange: () => void; extraAction?: React.ReactNode
}) {
  const [configured, setConfigured] = useState(false)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<MidioSyncResult | null>(null)
  // «Нет в реестре» длинный и требует не решения, а синхронизации с объектами —
  // по умолчанию свёрнут, чтобы не топить список настоящих подтверждений
  const [showUnknown, setShowUnknown] = useState(false)

  useEffect(() => {
    api.get<{ configured: boolean }>('/api/midio/status')
      .then((s) => setConfigured(s.configured))
      .catch(() => {})
    // отчёт хранится на бэкенде: уход со страницы его не теряет
    api.get<MidioSyncResult | undefined>('/api/midio/last-sync')
      .then((saved) => { if (saved) setResult(saved) })
      .catch(() => {})
  }, [])

  const sync = async () => {
    setRunning(true)
    try {
      const res = await api.post<MidioSyncResult>('/api/midio/sync')
      setResult(res)
      toast(`Из Midio перенесено работ: ${res.importedWorks}`, 'success')
      onChange()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка синхронизации', 'error')
    } finally {
      setRunning(false)
    }
  }

  const link = async (uniqueEquipmentIds: number[], midioId: string) => {
    try {
      await api.post('/api/midio/link', { uniqueEquipmentIds, midioId })
      // связь запомнена, но работы переносит именно синхронизация
      toast('Связь сохранена. Запустите синхронизацию, чтобы перенести работы.', 'success')
      setResult((r) => r && { ...r, pending: r.pending.filter((p) => p.externalId !== midioId) })
    } catch (e) {
      toast(e instanceof Error ? e.message : 'Ошибка', 'error')
    }
  }

  if (!configured) return null

  return (
    <div className="space-y-3">
      <div className="card p-5 flex items-center gap-4 flex-wrap">
        <div className="text-2xl">🔗</div>
        <div className="min-w-0 flex-1">
          <div className="font-medium text-slate-900">Регламенты из Midio</div>
          <div className="text-xs text-slate-500 mt-0.5">
            Плановые работы, заведённые в Midio, переносятся к оборудованию реестра и
            используются в сметах вместо извлечённых из паспорта.
          </div>
        </div>
        <button className="btn-secondary text-sm" disabled={running} onClick={sync}>
          {running ? 'Синхронизация…' : '⟳ Синхронизировать с Midio'}
        </button>
        {extraAction}
      </div>

      {result && (
        <div className="card p-5 space-y-4">
          <div className="flex gap-6 text-sm flex-wrap">
            <Stat label="оборудование сопоставлено" value={result.linkedEquipment}
                  hint="Записей реестра, у которых найдена карточка в Midio. Связь запомнена — дальше работы приходят по ней." />
            <Stat label="работ перенесено" value={result.importedWorks} accent="text-emerald-600"
                  hint="Плановых работ скопировано из Midio к оборудованию реестра за этот прогон." />
            {result.skippedWorks > 0 && (
              <Stat label="работ ждёт" value={result.skippedWorks} accent="text-amber-600"
                    hint="Работы оборудования, которое не удалось узнать однозначно или которого нет в реестре. Перенесутся после подтверждения связи или синхронизации с объектами." />
            )}
          </div>

          {result.pending.length > 0 && (
            <Section title="Требуется подтверждение"
                     hint="Подходит несколько записей реестра. Пока связь не выбрана, работы не переносятся.">
              {result.pending.map((p) => (
                <PendingRow key={p.externalId} item={p} onLink={link} />
              ))}
            </Section>
          )}

          {(result.review?.length ?? 0) > 0 && (
            <Section title="⚠ Спорные привязки"
                     hint="Карточка Midio уже привязана, но в реестре появились похожие записи без связи (например, та же модель другой ревизии). Текущие привязки отмечены — добавьте недостающие или оставьте как есть.">
              {result.review!.map((p) => (
                <PendingRow key={p.externalId} item={p} onLink={(ids, mid) => {
                  link(ids, mid)
                  setResult((r) => r && { ...r, review: (r.review || []).filter((x) => x.externalId !== mid) })
                }} />
              ))}
            </Section>
          )}

          {result.unknown.length > 0 && (
            <div className="border-t border-slate-100 pt-3">
              <button className="flex items-center gap-2 text-sm font-medium text-slate-900"
                      onClick={() => setShowUnknown(!showUnknown)}>
                <span className={`transition-transform text-slate-400 ${showUnknown ? 'rotate-90' : ''}`}>▸</span>
                Нет в реестре
                <span className="rounded-full bg-slate-100 text-slate-500 px-2 py-0.5 text-xs">
                  {result.unknown.length}
                </span>
              </button>
              {showUnknown && (
                <>
                  <div className="text-xs text-slate-500 mt-1 mb-2">
                    Это оборудование ещё не попало в реестр — нажмите «Синхронизировать с
                    объектами» и повторите синхронизацию с Midio.
                  </div>
                  <div className="divide-y divide-slate-50">
                    {result.unknown.map((p) => (
                      <div key={p.externalId} className="py-1.5">
                        <div className="text-sm text-slate-600">
                          {[p.name, p.model, p.manufacturer].filter(Boolean).join(' · ') || p.externalId}
                        </div>
                        <WorksPreview works={p.works} />
                      </div>
                    ))}
                  </div>
                </>
              )}
            </div>
          )}

          {result.pending.length === 0 && result.unknown.length === 0 && (
            <div className="text-sm text-emerald-600">Всё оборудование Midio узнано в реестре.</div>
          )}
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, accent, hint }: {
  label: string; value: number; accent?: string; hint?: string
}) {
  return (
    <div title={hint} className={hint ? 'cursor-help' : undefined}>
      <div className={`text-xl font-semibold ${accent || 'text-slate-900'}`}>{value}</div>
      <div className="text-xs text-slate-500 border-b border-dotted border-slate-300 inline-block">{label}</div>
    </div>
  )
}

function Section({ title, hint, children }: {
  title: string; hint: string; children: React.ReactNode
}) {
  return (
    <div className="border-t border-slate-100 pt-3">
      <div className="font-medium text-slate-900 text-sm">{title}</div>
      <div className="text-xs text-slate-500 mt-0.5 mb-2">{hint}</div>
      <div className="divide-y divide-slate-50">{children}</div>
    </div>
  )
}

function PendingRow({ item, onLink }: {
  item: MidioPending; onLink: (uniqueEquipmentIds: number[], midioId: string) => void
}) {
  // одна карточка Midio законно соответствует нескольким записям реестра
  // (одна модель под разными названиями/системами) — выбор множественный;
  // уже привязанные записи предотмечены — выбор трактуется как полный список
  const [selected, setSelected] = useState<number[]>(
    () => item.candidates.filter((c) => c.linked).map((c) => c.uniqueEquipmentId))
  const toggle = (id: number) => setSelected((s) =>
    s.includes(id) ? s.filter((x) => x !== id) : [...s, id])

  return (
    <div className="py-2.5">
      <div className="text-sm text-slate-900">
        {[item.name, item.model, item.manufacturer].filter(Boolean).join(' · ') || item.externalId}
      </div>
      <WorksPreview works={item.works} />
      <div className="text-xs text-slate-500 mt-1">
        Отметьте одну или несколько записей реестра — работы получит каждая.
      </div>
      <div className="flex gap-2 flex-wrap mt-2 items-center">
        {item.candidates.map((c) => (
          <label key={c.uniqueEquipmentId}
                 className={`flex items-center gap-2 rounded-lg border px-3 py-1.5 text-xs cursor-pointer transition-colors ${
                   selected.includes(c.uniqueEquipmentId)
                     ? 'border-primary-400 bg-primary-50 text-primary-800'
                     : 'border-slate-200 text-slate-600 hover:bg-slate-50'
                 }`}>
            <input type="checkbox" className="accent-primary-600"
                   checked={selected.includes(c.uniqueEquipmentId)}
                   onChange={() => toggle(c.uniqueEquipmentId)} />
            {[c.name, c.model].filter(Boolean).join(' · ') || `#${c.uniqueEquipmentId}`}
            {c.linked && <span className="text-[10px] text-primary-500">привязано</span>}
          </label>
        ))}
        <button className="btn-primary text-xs py-1.5" disabled={selected.length === 0}
                onClick={() => onLink(selected, item.externalId)}>
          Привязать{selected.length > 0 ? ` (${selected.length})` : ''}
        </button>
      </div>
    </div>
  )
}

/** Состав работ Midio прямо в отчёте: работа раскрывается кликом, как в карточке. */
function WorksPreview({ works }: { works?: MidioWorkPreview[] }) {
  const [open, setOpen] = useState<Set<number>>(new Set())
  if (!works || works.length === 0) return null
  const toggle = (i: number) => setOpen((prev) => {
    const next = new Set(prev)
    if (next.has(i)) next.delete(i); else next.add(i)
    return next
  })
  return (
    <div className="mt-1 space-y-0.5">
      {works.map((w, i) => (
        <div key={i}>
          <div className="text-xs text-slate-500 flex items-center gap-2">
            {w.composition ? (
              <button className="text-slate-400 hover:text-slate-600" title="Показать состав работ"
                      onClick={() => toggle(i)}>
                <span className={`inline-block transition-transform ${open.has(i) ? 'rotate-90' : ''}`}>▸</span>
              </button>
            ) : (
              <span className="text-slate-300">•</span>
            )}
            <span>{w.name}</span>
            {w.periodicity && <span className="text-slate-400">— {w.periodicity}</span>}
            {w.mandatory === true && (
              <span className="rounded-full bg-emerald-50 text-emerald-700 px-1.5 py-px text-[10px]">обязательная</span>
            )}
            {w.mandatory === false && (
              <span className="rounded-full bg-amber-50 text-amber-700 px-1.5 py-px text-[10px]">рекомендуемая</span>
            )}
          </div>
          {open.has(i) && w.composition && (
            <div className="ml-6 mt-1 mb-1 text-xs text-slate-500 bg-slate-50 rounded-lg p-2.5 whitespace-pre-line">
              {w.composition.split('; ').join('\n')}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
