import { FormEvent, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'
import { Estimate, Facility } from '../types'
import Modal from '../components/Modal'
import ConfirmDialog from '../components/ConfirmDialog'
import { toast } from '../components/Toast'

export default function EstimatesPage() {
  const [estimates, setEstimates] = useState<Estimate[]>([])
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [showCreate, setShowCreate] = useState(false)
  const [deleting, setDeleting] = useState<Estimate | null>(null)
  const [error, setError] = useState('')

  const load = () => {
    api.get<Estimate[]>('/api/estimates').then(setEstimates).catch((e) => setError(e.message))
  }

  useEffect(() => {
    load()
    api.get<Facility[]>('/api/facilities').then(setFacilities).catch(() => {})
  }, [])

  const facilityName = (id: number) => facilities.find((f) => f.id === id)?.name ?? `Объект #${id}`

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
        <button className="btn-primary" onClick={() => setShowCreate(true)}>+ Новая смета</button>
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
      {deleting && (
        <ConfirmDialog title="Удалить смету?" message={`Смета «${deleting.name}» будет удалена.`}
                       confirmLabel="Удалить" danger onConfirm={remove} onClose={() => setDeleting(null)} />
      )}
    </div>
  )
}

function CreateModal({ facilities, onClose }: { facilities: Facility[]; onClose: () => void }) {
  const [name, setName] = useState('')
  const [facilityId, setFacilityId] = useState<number | ''>('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const navigate = useNavigate()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!facilityId) { setError('Выберите объект'); return }
    setLoading(true)
    setError('')
    try {
      const created = await api.post<Estimate>(`/api/estimates?facilityId=${facilityId}`, { name })
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
                 placeholder="Смета обслуживания СКУD на 2026 год" />
        </div>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
          <button type="submit" className="btn-primary" disabled={loading}>
            {loading ? 'Создание…' : 'Создать'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
