import { useEffect, useState } from 'react'
import { api } from '../api'
import { MidioPending, MidioSyncResult } from '../types'
import { toast } from './Toast'

/**
 * Перенос готовых регламентов из Midio. Кнопка появляется только когда
 * интеграция настроена — иначе она обещала бы то, чего нет.
 */
export default function MidioSync({ onChange }: { onChange: () => void }) {
  const [configured, setConfigured] = useState(false)
  const [running, setRunning] = useState(false)
  const [result, setResult] = useState<MidioSyncResult | null>(null)

  useEffect(() => {
    api.get<{ configured: boolean }>('/api/midio/status')
      .then((s) => setConfigured(s.configured))
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

  const link = async (uniqueEquipmentId: number, midioId: string) => {
    try {
      await api.post('/api/midio/link', { uniqueEquipmentId, midioId })
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
      </div>

      {result && (
        <div className="card p-5 space-y-4">
          <div className="flex gap-6 text-sm flex-wrap">
            <Stat label="привязано оборудования" value={result.linkedEquipment} />
            <Stat label="перенесено работ" value={result.importedWorks} accent="text-emerald-600" />
            {result.skippedWorks > 0 && (
              <Stat label="работ отложено" value={result.skippedWorks} accent="text-amber-600" />
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

          {result.unknown.length > 0 && (
            <Section title="Нет в реестре"
                     hint="Это оборудование ещё не попало в реестр — нажмите «Синхронизировать с объектами» и повторите.">
              {result.unknown.map((p) => (
                <div key={p.externalId} className="text-sm text-slate-600 py-1.5">
                  {[p.name, p.model, p.manufacturer].filter(Boolean).join(' · ') || p.externalId}
                </div>
              ))}
            </Section>
          )}

          {result.pending.length === 0 && result.unknown.length === 0 && (
            <div className="text-sm text-emerald-600">Всё оборудование Midio узнано в реестре.</div>
          )}
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: number; accent?: string }) {
  return (
    <div>
      <div className={`text-xl font-semibold ${accent || 'text-slate-900'}`}>{value}</div>
      <div className="text-xs text-slate-500">{label}</div>
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
  item: MidioPending; onLink: (uniqueEquipmentId: number, midioId: string) => void
}) {
  return (
    <div className="py-2.5">
      <div className="text-sm text-slate-900">
        {[item.name, item.model, item.manufacturer].filter(Boolean).join(' · ') || item.externalId}
      </div>
      <div className="text-xs text-slate-500 mt-0.5">{item.reason}</div>
      <div className="flex gap-2 flex-wrap mt-2">
        {item.candidates.map((c) => (
          <button key={c.uniqueEquipmentId} className="btn-ghost text-xs border border-slate-200"
                  onClick={() => onLink(c.uniqueEquipmentId, item.externalId)}>
            {[c.name, c.model].filter(Boolean).join(' · ') || `#${c.uniqueEquipmentId}`}
          </button>
        ))}
      </div>
    </div>
  )
}
