import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { Chat, EngineeringSystem, Facility } from '../types'
import ChatPanel from '../components/ChatPanel'

export default function ChatPage() {
  const [searchParams] = useSearchParams()
  const chatParam = searchParams.get('chat')

  const [facilities, setFacilities] = useState<Facility[]>([])
  const [systems, setSystems] = useState<EngineeringSystem[]>([])
  const [facilityId, setFacilityId] = useState('')
  const [systemId, setSystemId] = useState('')
  // диалог, открытый по ссылке (например, из «последних вопросов» на главной)
  const [resumeChatId, setResumeChatId] = useState<number | undefined>(
    chatParam ? Number(chatParam) : undefined)
  const [ready, setReady] = useState(!chatParam)

  useEffect(() => {
    api.get<Facility[]>('/api/facilities').then(setFacilities)
  }, [])

  // при открытии по ссылке восстанавливаем область поиска этого диалога
  useEffect(() => {
    if (!chatParam) return
    api.get<Chat[]>('/api/chats').then((chats) => {
      const chat = chats.find((c) => c.id === Number(chatParam))
      if (chat?.facilityId) setFacilityId(String(chat.facilityId))
      setReady(true)
    }).catch(() => setReady(true))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chatParam])

  useEffect(() => {
    if (facilityId) {
      api.get<EngineeringSystem[]>(`/api/facilities/${facilityId}/systems`).then(setSystems)
    } else {
      setSystems([])
    }
  }, [facilityId])

  const changeFacility = (value: string) => {
    setFacilityId(value)
    setSystemId('')
    setResumeChatId(undefined) // ручная смена области — выходим из открытого по ссылке диалога
  }

  const changeSystem = (value: string) => {
    setSystemId(value)
    setResumeChatId(undefined)
  }

  return (
    <div className="flex flex-col h-screen">
      <div className="p-6 border-b border-slate-200 bg-white">
        <h1 className="text-xl font-semibold text-slate-900 mb-3">Чат с ИИ</h1>
        <div className="flex gap-3 flex-wrap">
          <select className="input w-auto" value={facilityId} onChange={(e) => changeFacility(e.target.value)}>
            <option value="">Объект: все объекты</option>
            {facilities.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
          </select>
          <select className="input w-auto" value={systemId} onChange={(e) => changeSystem(e.target.value)} disabled={!facilityId}>
            <option value="">Система: все</option>
            {systems.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </div>
      </div>
      <div className="flex-1 min-h-0 bg-slate-50">
        {ready && (
          <ChatPanel
            facilityId={facilityId ? Number(facilityId) : undefined}
            systemId={systemId ? Number(systemId) : undefined}
            initialChatId={resumeChatId}
            placeholder="Например: на каких объектах используется ТВ7?"
          />
        )}
      </div>
    </div>
  )
}
