import { FormEvent, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, exportEstimate } from '../api'
import { EstimateRowEntity, EstimateView } from '../types'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

const money = (v?: number) =>
  v == null ? '—' : v.toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

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

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <div>
          <Link to="/estimates" className="text-sm text-primary-600">← К сметам</Link>
          <h1 className="text-2xl font-semibold text-slate-900 mt-1">{estimate.name}</h1>
        </div>
        <div className="flex gap-2">
          <button className="btn-secondary" onClick={() => setEditing('new')}>+ Строка</button>
          <button className="btn-primary"
                  onClick={() => exportEstimate(estimate.id, `Смета_${estimate.name}.xlsx`).catch((e) => toast(e.message, 'error'))}>
            ⭳ Выгрузить XLSX
          </button>
        </div>
      </div>

      <Coefficients view={view} onSaved={load} />

      <div className="card overflow-x-auto">
        <table className="w-full text-sm whitespace-nowrap">
          <thead>
            <tr className="text-left text-xs text-slate-400 border-b border-slate-100">
              <th className="py-2 px-3 font-medium">№</th>
              <th className="py-2 px-3 font-medium">Оборудование</th>
              <th className="py-2 px-3 font-medium">Мероприятие</th>
              <th className="py-2 px-3 font-medium">Шифр</th>
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
            {rows.map(({ row, calc }) => (
              <tr key={row.id} className="border-b border-slate-50 hover:bg-slate-50 cursor-pointer"
                  onClick={() => setEditing(row)}>
                <td className="py-2 px-3 text-slate-400">{row.position}</td>
                <td className="py-2 px-3 max-w-[220px] truncate" title={row.equipmentName}>{row.equipmentName || '—'}</td>
                <td className="py-2 px-3 max-w-[220px] truncate" title={row.operationName}>{row.operationName || '—'}</td>
                <td className="py-2 px-3 font-mono text-xs">{row.rateCode || '—'}</td>
                <td className="py-2 px-3 text-xs">{row.periodicity || '—'}</td>
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
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={15} className="text-center text-slate-400 py-12">
                Строк пока нет. Добавьте строку — укажите шифр расценки, цены подтянутся из каталога.
              </td></tr>
            )}
          </tbody>
          {rows.length > 0 && (
            <tfoot>
              <tr className="border-t-2 border-slate-200 font-semibold">
                <td className="py-3 px-3" colSpan={12}>ИТОГО в год</td>
                <td className="py-3 px-3 text-right">{money(totals.totalNoVat)}</td>
                <td className="py-3 px-3 text-right">{money(totals.totalWithVat)}</td>
                <td></td>
              </tr>
              <tr className="text-xs text-slate-500">
                <td className="py-1 px-3" colSpan={12}>в уровне цен РТ (справочно)</td>
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
  })
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const set = (k: keyof typeof f, v: string) => setF({ ...f, [k]: v })

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
    }
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

  return (
    <Modal title={row ? 'Строка сметы' : 'Новая строка'} onClose={onClose}>
      <form onSubmit={submit} className="space-y-3 max-h-[70vh] overflow-y-auto pr-1">
        {input('section', 'Раздел', 'РАЗДЕЛ А. СКУД')}
        {input('equipmentName', 'Оборудование', 'Сервер СКУД')}
        <div className="grid grid-cols-2 gap-3">
          {input('equipmentType', 'Тип')}
          {input('manufacturer', 'Производитель')}
        </div>
        {input('operationName', 'Мероприятие', 'Техническое обслуживание…')}
        {input('rateCode', 'Шифр расценки', '22-2203-113-1/1')}
        <p className="text-xs text-slate-400 -mt-2">
          Цены (ЗП/ЭМ/ЗПМ/МР), наименование и измеритель подтянутся из каталога по шифру.
          {row?.rateName && <span className="block text-slate-500 mt-0.5">Расценка: {row.rateName}</span>}
        </p>
        <div className="grid grid-cols-3 gap-3">
          {input('periodicity', 'Периодичность', 'раз в 1 мес.')}
          {input('opsPerYear', 'Опер/год', 'авто из период.')}
          {input('qty', 'Количество')}
        </div>
        {input('correction', 'Поправочный коэффициент (S)', '1')}
        <div>
          <label className="label">Обоснование</label>
          <textarea className="input" rows={2} value={f.justification}
                    onChange={(e) => set('justification', e.target.value)} />
        </div>
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
