import { FormEvent, useEffect, useRef, useState } from 'react'
import { api, openDocument } from '../api'
import { Chat, ChatMessageDto } from '../types'

interface Props {
  facilityId?: number
  systemId?: number
  documentId?: number
  placeholder?: string
  /** Открыть конкретный диалог (например, из «последних вопросов» на главной). */
  initialChatId?: number
}

export default function ChatPanel({ facilityId, systemId, documentId, placeholder, initialChatId }: Props) {
  const [chatId, setChatId] = useState<number | null>(null)
  const [messages, setMessages] = useState<ChatMessageDto[]>([])
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const bottomRef = useRef<HTMLDivElement>(null)

  // При смене области поиска продолжаем последний диалог этой области,
  // чтобы история не «пропадала» при каждом заходе на вкладку.
  useEffect(() => {
    setChatId(null)
    setMessages([])
    let cancelled = false

    const resume = async () => {
      try {
        let id = initialChatId ?? null
        if (!id) {
          const chats = await api.get<Chat[]>('/api/chats')
          const match = chats.find((c) =>
            (c.facilityId ?? null) === (facilityId ?? null) &&
            (c.engineeringSystemId ?? null) === (systemId ?? null) &&
            (c.documentId ?? null) === (documentId ?? null))
          id = match?.id ?? null
        }
        if (!id) return
        const history = await api.get<ChatMessageDto[]>(`/api/chats/${id}/messages`)
        if (!cancelled) {
          setChatId(id)
          setMessages(history)
        }
      } catch {
        // не удалось восстановить историю — просто начнём новый диалог
      }
    }
    resume()
    return () => { cancelled = true }
  }, [facilityId, systemId, documentId, initialChatId])

  const startNewChat = () => {
    setChatId(null)
    setMessages([])
    setError('')
  }

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  const send = async (e: FormEvent) => {
    e.preventDefault()
    const q = question.trim()
    if (!q || loading) return
    setQuestion('')
    setError('')
    setLoading(true)

    const userMessage: ChatMessageDto = {
      id: Date.now(), role: 'user', content: q, createdAt: new Date().toISOString(), sources: [],
    }
    setMessages((prev) => [...prev, userMessage])

    try {
      let id = chatId
      if (!id) {
        const chat = await api.post<{ id: number }>('/api/chats', {
          facilityId, engineeringSystemId: systemId, documentId,
        })
        id = chat.id
        setChatId(id)
      }
      const answer = await api.post<ChatMessageDto>(`/api/chats/${id}/messages`, { question: q })
      setMessages((prev) => [...prev, answer])
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flex flex-col h-full min-h-[480px]">
      <div className="flex-1 overflow-y-auto space-y-4 p-4">
        {messages.length === 0 && (
          <div className="text-center text-slate-400 py-16 text-sm">
            Задайте вопрос по загруженной документации.<br />
            Например: «Сколько дымовых извещателей на объекте?»
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
            <div
              className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm whitespace-pre-wrap ${
                m.role === 'user'
                  ? 'bg-primary-600 text-white rounded-br-md'
                  : 'bg-white border border-slate-200 text-slate-800 rounded-bl-md shadow-sm'
              }`}
            >
              {m.content}
              {m.sources.length > 0 && (
                <div className="mt-3 pt-3 border-t border-slate-100 space-y-1">
                  <div className="text-xs font-medium text-slate-500">Источники:</div>
                  {m.sources.map((s, i) => (
                    <div key={i}>
                      <button
                        type="button"
                        onClick={() => openDocument(s.documentId, s.pageNumber).catch(() => setError('Не удалось открыть документ'))}
                        className="block text-xs text-primary-600 hover:underline text-left"
                      >
                        {i + 1}. {s.documentName}
                        {s.pageNumber ? `, стр. ${s.pageNumber} (страница файла)` : ''}
                      </button>
                      {s.snippet && (
                        <div className="mt-0.5 pl-3 border-l-2 border-slate-200 text-xs text-slate-400 italic">
                          «{s.snippet}»
                        </div>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        ))}
        {loading && (
          <div className="flex justify-start">
            <div className="bg-white border border-slate-200 rounded-2xl rounded-bl-md px-4 py-3 text-sm text-slate-400 shadow-sm">
              Ищу ответ в документации…
            </div>
          </div>
        )}
        {error && <div className="text-sm text-red-600 text-center">{error}</div>}
        <div ref={bottomRef} />
      </div>

      <form onSubmit={send} className="p-4 border-t border-slate-200 bg-white flex gap-2">
        {messages.length > 0 && (
          <button type="button" className="btn-secondary shrink-0" title="Начать новый диалог"
                  onClick={startNewChat}>
            + Новый
          </button>
        )}
        <input
          className="input flex-1"
          placeholder={placeholder ?? 'Введите вопрос…'}
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          disabled={loading}
        />
        <button type="submit" className="btn-primary" disabled={loading || !question.trim()}>
          ➤
        </button>
      </form>
    </div>
  )
}
