import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, setToken } from '../api'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const result = await api.post<{ token: string }>('/api/auth/login', { email, password })
      setToken(result.token)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Ошибка входа')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-slate-50 p-4">
      <div className="card w-full max-w-md p-8">
        <div className="text-center mb-8">
          <div className="mx-auto w-14 h-14 rounded-2xl bg-primary-600 text-white flex items-center justify-center text-2xl font-bold mb-4">
            T
          </div>
          <h1 className="text-2xl font-semibold text-primary-700">TechDocs AI</h1>
          <p className="text-sm text-slate-500 mt-1">Интеллектуальная база документации</p>
        </div>

        <form onSubmit={submit} className="space-y-4">
          <div>
            <label className="label">Email</label>
            <input
              type="email"
              className="input"
              placeholder="user@mail.ru"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div>
            <label className="label">Пароль</label>
            <input
              type="password"
              className="input"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </div>
          {error && <div className="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">{error}</div>}
          <button type="submit" className="btn-primary w-full justify-center" disabled={loading}>
            {loading ? 'Вход…' : 'Войти'}
          </button>
        </form>

        <p className="text-center text-xs text-slate-400 mt-6">
          По умолчанию: admin@techdocs.local / admin123
        </p>
      </div>
    </div>
  )
}
