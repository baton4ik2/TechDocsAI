import { useEffect, useState } from 'react'
import { api } from '../api'
import { EngineeringSystem, Facility } from '../types'
import ChatPanel from '../components/ChatPanel'

export default function ChatPage() {
  const [facilities, setFacilities] = useState<Facility[]>([])
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [facilityId, setFacilityId] = useState('')
  const [systemId, setSystemId] = useState('')

  useEffect(() => {
    api.get<Facility[]>('/api/facilities').then(setFacilities)
  }, [])

  useEffect(() => {
    setSystemId('')
    if (facilityId) {
      api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
    } else {
      setSystems([])
    }
  }, [facilityId])

  return (
    <div className="flex flex-col h-screen">
      <div className="p-6 border-b border-slate-200 bg-white">
        <h1 className="text-xl font-semibold text-slate-900 mb-3">Чат с ИИ</h1>
        <div className="flex gap-3 flex-wrap">
          <select className="input w-auto" value={facilityId} onChange={(e) => setFacilityId(e.target.value)}>
            <option value="">Объект: все объекты</option>
            {facilities.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
          <select className="input w-auto" value={systemId} onChange={(e) => setSystemId(e.target.value)} disabled={!facilityId}>
            <option value="">Система: все</option>
            {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
      </div>
      <div className="flex-1 min-h-0 bg-slate-50">
        <ChatPanel
          facilityId={facilityId ? Number(facilityId) : undefined}
          systemId={systemId ? Number(systemId) : undefined}
          placeholder="Например: на каких объектах используется ТВ7?"
        />
      </div>
    </div>
  )
}
