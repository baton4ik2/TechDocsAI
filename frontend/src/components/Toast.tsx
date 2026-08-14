import { useEffect, useState } from 'react'

type ToastType = 'success' | 'error' | 'info'
interface ToastItem { id: number; message: string; type: ToastType }

let counter = 0
const listeners = new Set<(items: ToastItem[]) => void>()
let items: ToastItem[] = []

function emit() {
  listeners.forEach((l) => l([...items]))
}

/** Показать всплывающее уведомление из любого места приложения. */
export function toast(message: string, type: ToastType = 'info') {
  const id = ++counter
  items = [...items, { id, message, type }]
  emit()
  setTimeout(() => {
    items = items.filter((t) => t.id !== id)
    emit()
  }, 4000)
}

const styles: Record<ToastType, string> = {
  success: 'bg-emerald-600',
  error: 'bg-red-600',
  info: 'bg-slate-800',
}

const icons: Record<ToastType, string> = { success: '✓', error: '⚠', info: 'ℹ' }

/** Контейнер уведомлений — монтируется один раз в корне приложения. */
export default function ToastHost() {
  const [list, setList] = useState<ToastItem[]>([])

  useEffect(() => {
    listeners.add(setList)
    return () => { listeners.delete(setList) }
  }, [])

  return (
    <div className="fixed bottom-6 right-6 z-[100] flex flex-col gap-2">
      {list.map((t) => (
        <div key={t.id}
             className={`flex items-center gap-2.5 rounded-lg px-4 py-3 text-sm text-white shadow-lg ${styles[t.type]} animate-[fadeIn_0.2s_ease-out]`}>
          <span className="text-base leading-none">{icons[t.type]}</span>
          <span>{t.message}</span>
        </div>
      ))}
    </div>
  )
}
