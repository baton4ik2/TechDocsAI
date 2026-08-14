import { FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { Facility } from '../types'
import Modal from '../components/Modal'
import StatusBadge from '../components/StatusBadge'



export default function FacilitiesPage() {
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [search, setSearch] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [error, setError] = useState('')

  const load = (term = '') => {
    api.get<Facility[]>(`/api/facilities${term ? `?search=${encodeURIComponent(term)}` : ''}`)
      .then(setFacilities)
      .catch((e) => setError(e.message))
  }

  useEffect(() => { load() }, [])

  useEffect(() => {
    const timer = setTimeout(() => load(search), 300)
    return () => clearTimeout(timer)
  }, [search])

  return (
    <div className="p-8 space-y-6">
      <div className="flex items-center justify-between gap-4 flex-wrap">
        <h1 className="text-2xl font-semibold text-slate-900">Объекты</h1>
        <button className="btn-primary" onClick={() => setShowCreate(true)}>+ Создать объект</button>
      </div>

      <input
        className="input max-w-md"
        placeholder="Поиск объектов…"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
      />

      {error && <div className="text-red-600 text-sm">{error}</div>}

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5">
        {facilities.map((f) => (
          <Link key={f.id} to={`/facilities/${f.id}`} className="card overflow-hidden hover:shadow-md transition-shadow">
            <div className="h-32 bg-gradient-to-br from-primary-500/20 via-primary-100 to-slate-100 flex items-center justify-center text-5xl">
              🏢
            </div>
            <div className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="font-semibold text-slate-900">{f.name}</div>
                  {f.address && <div className="text-xs text-slate-500 mt-0.5">{f.address}</div>}
                </div>
                <StatusBadge status={f.status} />
              </div>
              <div className="flex gap-4 mt-3 text-xs text-slate-500">
                <span>{f.systemCount} систем</span>
                <span>{f.documentCount} док.</span>
                <span>{f.equipmentCount} моделей об.</span>
                {f.errorCount > 0 && <span className="text-red-500">{f.errorCount} ошибок</span>}
              </div>
            </div>
          </Link>
        ))}
        {facilities.length === 0 && (
          <div className="col-span-full text-center text-slate-400 py-16">
            Объектов не найдено. Создайте первый объект.
          </div>
        )}
      </div>

      {showCreate && (
        <CreateFacilityModal
          onClose={() => setShowCreate(false)}
          onCreated={() => { setShowCreate(false); load() }}
        />
      )}
    </div>
  )
}

function CreateFacilityModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [areaSqm, setAreaSqm] = useState('')
  const [description, setDescription] = useState('')
  const [systems, setSystems] = useState<string[]>([])
  // системы — константный справочник приложения, свой текст не вводится
  const [catalog, setCatalog] = useState<string[]>([])
  useEffect(() => {
    api.get<string[]>('/api/facilities/system-catalog').then(setCatalog).catch(() => {})
  }, [])
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const toggleSystem = (s: string) => {
    setSystems((prev) => prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s])
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError('')
    try {
      await api.post('/api/facilities', {
        name, address, description,
        areaSqm: areaSqm.trim() ? Number(areaSqm.replace(',', '.')) : null,
        systems,
      })
      onCreated()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
      setLoading(false)
    }
  }

  return (
    <Modal title="Создать объект" onClose={onClose}>
      <form onSubmit={submit} className="space-y-4">
        <div>
          <label className="label">Название *</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} required placeholder="ЖК «Лето»" />
        </div>
        <div>
          <label className="label">Адрес</label>
          <input className="input" value={address} onChange={(e) => setAddress(e.target.value)} placeholder="г. Казань, ул. …" />
        </div>
        <div>
          <label className="label">Площадь объекта, м²</label>
          <input className="input" type="number" step="0.01" min="0" value={areaSqm}
                 onChange={(e) => setAreaSqm(e.target.value)} placeholder="необязательно" />
          <p className="text-xs text-slate-400 mt-1">
            Нужна расценкам с измерителем в м² (например, проверка работоспособности систем
            противопожарной защиты — измеритель 1000 м²).
          </p>
        </div>
        <div>
          <label className="label">Описание</label>
          <textarea className="input" rows={2} value={description} onChange={(e) => setDescription(e.target.value)} />
        </div>
        <div>
          <label className="label">Инженерные системы</label>
          <div className="flex flex-wrap gap-2">
            {catalog.map((s) => (
              <button
                type="button"
                key={s}
                onClick={() => toggleSystem(s)}
                className={`rounded-full px-3 py-1 text-xs font-medium border transition-colors ${
                  systems.includes(s)
                    ? 'bg-primary-600 text-white border-primary-600'
                    : 'bg-white text-slate-600 border-slate-200 hover:border-primary-300'
                }`}
              >
                {s}
              </button>
            ))}
          </div>
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
