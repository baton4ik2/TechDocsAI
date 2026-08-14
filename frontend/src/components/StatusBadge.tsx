const statusMap: Record<string, { label: string; className: string }> = {
  UPLOADED: { label: 'Загружен', className: 'bg-slate-100 text-slate-600' },
  PROCESSING: { label: 'Обрабатывается', className: 'bg-amber-50 text-amber-700' },
  READY: { label: 'Готов', className: 'bg-emerald-50 text-emerald-700' },
  ERROR: { label: 'Ошибка', className: 'bg-red-50 text-red-700' },
  NEEDS_OCR: { label: 'Требуется OCR', className: 'bg-orange-50 text-orange-700' },
  ACTIVE: { label: 'Действующий', className: 'bg-emerald-50 text-emerald-700' },
  ARCHIVED: { label: 'Архивный', className: 'bg-slate-100 text-slate-500' },
  AUTO_EXTRACTED: { label: 'Извлечено автоматически', className: 'bg-sky-50 text-sky-700' },
  CONFIRMED: { label: 'Подтверждено', className: 'bg-emerald-50 text-emerald-700' },
  NEEDS_REVIEW: { label: 'Требует проверки', className: 'bg-amber-50 text-amber-700' },
  ACTUAL: { label: 'Актуальный', className: 'bg-emerald-50 text-emerald-700' },
  OUTDATED: { label: 'Устаревший', className: 'bg-slate-100 text-slate-500' },
}

export default function StatusBadge({ status }: { status: string }) {
  const info = statusMap[status] ?? { label: status, className: 'bg-slate-100 text-slate-600' }
  return (
    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${info.className}`}>
      {info.label}
    </span>
  )
}
