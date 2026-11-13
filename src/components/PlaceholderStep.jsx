// PlaceholderStep.jsx — Placeholder informatif untuk langkah wizard yang
// belum diimplementasikan. Menampilkan application state yang relevan
// (empty/loading/error/success/disabled/warning) tanpa merusak alur wizard.

import PropTypes from 'prop-types'

export default function PlaceholderStep({ step, state = 'empty', onAction, actionLabel }) {
  const configs = {
    empty: {
      icon: '○',
      color: 'text-slate-500',
      title: 'Belum ada data',
      desc: 'Langkah ini akan diimplementasikan pada fase berikutnya.',
      action: 'Mulai implementasi',
    },
    loading: {
      icon: '⟳',
      color: 'text-brand-700 animate-spin inline-block',
      title: 'Sedang memproses...',
      desc: 'Tunggu hingga proses selesai.',
    },
    error: {
      icon: '⚠',
      color: 'text-red-700',
      title: 'Terjadi kesalahan',
      desc: 'Coba lagi atau periksa input Anda.',
    },
    success: {
      icon: '✓',
      color: 'text-green-700',
      title: 'Selesai',
      desc: 'Langkah ini berhasil diselesaikan.',
    },
    disabled: {
      icon: '⊗',
      color: 'text-slate-500',
      title: 'Dinonaktifkan',
      desc: 'Lengkapi langkah sebelumnya terlebih dahulu.',
    },
    warning: {
      icon: '!',
      color: 'text-yellow-700',
      title: 'Peringatan validasi',
      desc: 'Perbaiki input sebelum melanjutkan.',
    },
  }

  const c = configs[state] || configs.empty

  return (
    <div className="card text-center py-12">
      <div className={`text-4xl mb-4 ${c.color}`} aria-hidden="true">
        {c.icon}
      </div>
      <h3 className="text-lg font-semibold text-slate-900">{c.title}</h3>
      <p className="text-sm text-slate-500 mt-2">{c.desc}</p>
      {step && (
        <p className="text-xs text-slate-500 mt-4">
          Langkah: <strong>{step.label}</strong> ({step.id})
        </p>
      )}
      {onAction && c.action && (
        <button
          onClick={onAction}
          className={`mt-6 btn-secondary ${state === 'disabled' ? 'disabled' : ''}`}
          disabled={state === 'disabled'}
        >
          {actionLabel || c.action}
        </button>
      )}
    </div>
  )
}

PlaceholderStep.propTypes = {
  step: PropTypes.shape({ id: PropTypes.string, label: PropTypes.string }).isRequired,
  state: PropTypes.string,
  onAction: PropTypes.func,
  actionLabel: PropTypes.string,
}
