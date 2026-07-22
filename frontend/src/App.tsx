import { Navigate, Route, Routes } from 'react-router-dom'
import { getToken } from './api'
import Layout from './components/Layout'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import FacilitiesPage from './pages/FacilitiesPage'
import FacilityPage from './pages/FacilityPage'
import NormativesPage from './pages/NormativesPage'
import PkmPage from './pages/PkmPage'
import ChatPage from './pages/ChatPage'
import ToastHost from './components/Toast'

function RequireAuth({ children }: { children: JSX.Element }) {
  if (!getToken()) return <Navigate to="/login" replace />
  return children
}

export default function App() {
  return (
    <>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
          <Route index element={<DashboardPage />} />
          <Route path="facilities" element={<FacilitiesPage />} />
          <Route path="facilities/:id" element={<FacilityPage />} />
          <Route path="normatives" element={<NormativesPage />} />
          <Route path="pkm" element={<PkmPage />} />
          <Route path="chat" element={<ChatPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <ToastHost />
    </>
  )
}
