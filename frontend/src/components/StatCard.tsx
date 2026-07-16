interface Props {
  icon: string
  label: string
  value: string | number
  accent?: 'blue' | 'red' | 'green' | 'slate'
}

const accents = {
  blue: 'bg-primary-50 text-primary-600',
  red: 'bg-red-50 text-red-600',
  green: 'bg-emerald-50 text-emerald-600',
  slate: 'bg-slate-100 text-slate-600',
}

export default function StatCard({ icon, label, value, accent = 'blue' }: Props) {
  return (
    <div className="card p-4 flex items-center gap-4">
      <div className={`w-11 h-11 rounded-lg flex items-center justify-center text-xl ${accents[accent]}`}>
        {icon}
      </div>
      <div>
        <div className="text-2xl font-semibold text-slate-900">{value}</div>
        <div className="text-sm text-slate-500">{label}</div>
      </div>
    </div>
  )
}
