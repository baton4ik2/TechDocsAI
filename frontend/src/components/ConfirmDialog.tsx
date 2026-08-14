import { ReactNode } from 'react'
import Modal from './Modal'

interface Props {
  title: string
  message: ReactNode
  confirmLabel: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}

export default function ConfirmDialog({ title, message, confirmLabel, danger, onConfirm, onClose }: Props) {
  return (
    <Modal title={title} onClose={onClose}>
      <div className="text-sm text-slate-600">{message}</div>
      <div className="flex justify-end gap-2 mt-5">
        <button type="button" className="btn-secondary" onClick={onClose}>Отмена</button>
        <button
          type="button"
          className={danger ? 'btn-danger' : 'btn-primary'}
          onClick={onConfirm}
        >
          {confirmLabel}
        </button>
      </div>
    </Modal>
  )
}
