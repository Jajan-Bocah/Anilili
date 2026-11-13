// CreateProject.jsx — Langkah 1 wizard: isi identitas proyek (nama, endpoint, source).
// Data disimpan ke state proyek di App.jsx dan digunakan oleh langkah review/export.

import PropTypes from 'prop-types'
import { SUPPORTED_SOURCES, SUPPORTED_CATEGORIES } from '../lib/contract/engineContract.js'
import { projectShape } from '../lib/projectPropTypes.js'

export default function CreateProject({ project, onChange }) {
  // Turunan langsung dari project (sumber kebenaran tunggal) agar proyek yang dipulihkan/dibuka/diimpor langsung tampil.
  const form = {
    name: project.name || '',
    id: project.id || '',
    endpoint: project.endpoint || '',
    source: project.source || 'generic_html',
    category: project.category || 'general',
    configSourceUrl: project.configSourceUrl || '',
  }
  const configSourceUrlInvalid = form.configSourceUrl.trim() !== '' && !/^https?:\/\/\S+$/i.test(form.configSourceUrl.trim())

  const handleChange = (e) => {
    const { name, value } = e.target
    // Update project state immediately so navigation works without form submit
    const next = {
      ...project,
      [name]: value,
    }
    // Auto-generate id from name if id is empty
    if (name === 'name' && !form.id) {
      next.id = value.trim().toLowerCase().replace(/[^a-z0-9_-]/g, '_')
    }
    onChange(next)
  }

  const handleSubmit = (e) => {
    e.preventDefault()
    // Final update with trimmed values
    const next = {
      ...project,
      name: form.name.trim(),
      id: form.id.trim() || form.name.trim().toLowerCase().replace(/[^a-z0-9_-]/g, '_'),
      endpoint: form.endpoint.trim(),
      configSourceUrl: form.configSourceUrl.trim(),
      source: form.source,
    }
    onChange(next)
  }

  return (
    <div className="card space-y-5 max-w-2xl">
      <h2 className="text-lg font-semibold text-slate-900">Identitas Proyek</h2>
      <p className="text-sm text-slate-500">
        Isi nama dan endpoint provider. Endpoint harus HTTPS sesuai ketentuan engine Anilili.
      </p>

      <form onSubmit={handleSubmit} className="space-y-4">
        <div className="space-y-2">
          <label htmlFor="proj-name" className="text-sm font-semibold text-slate-700 block">Nama Provider</label>
          <input
            id="proj-name"
            name="name"
            className="input-field"
            value={form.name}
            onChange={handleChange}
            placeholder="contoh: Animeindo"
            required
          />
          <p className="text-xs text-slate-500">Digunakan sebagai nama provider dan dasar ID.</p>
        </div>

        <div className="space-y-2">
          <label htmlFor="proj-id" className="text-sm font-semibold text-slate-700 block">Provider ID</label>
          <input
            id="proj-id"
            name="id"
            className="input-field"
            value={form.id}
            onChange={handleChange}
            placeholder="contoh: animeindo"
          />
          <p className="text-xs text-slate-500">
            ID singkat untuk identifier provider (a-z, 0-9, _, -). Kosongkan untuk otomatis dari nama.
          </p>
        </div>

        <div className="space-y-2">
          <label htmlFor="proj-endpoint" className="text-sm font-semibold text-slate-700 block">Provider Endpoint (base URL situs/API)</label>
          <input
            id="proj-endpoint"
            name="endpoint"
            className="input-field"
            value={form.endpoint}
            onChange={handleChange}
            placeholder="https://example.com/"
            required
          />
          <p className="text-xs text-slate-500">
            Base URL situs/API yang di-fetch workflow. Harus diawali https://.
          </p>
        </div>

        <div className="space-y-2">
          <label htmlFor="proj-config-source-url" className="text-sm font-semibold text-slate-700 block">Config Source URL (lokasi hosting provider.json)</label>
          <input
            id="proj-config-source-url"
            name="configSourceUrl"
            className="input-field"
            value={form.configSourceUrl}
            onChange={handleChange}
            placeholder="https://example.com/provider.json"
            aria-describedby="proj-config-source-url-help"
            aria-invalid={configSourceUrlInvalid}
          />
          <p id="proj-config-source-url-help" className="text-xs text-slate-500">
            Opsional di langkah ini, wajib untuk generate. Menjadi metadata.sourceUrl di provider.json; berbeda dari endpoint.
          </p>
          {configSourceUrlInvalid && (
            <p className="text-xs text-red-700" role="alert">Config Source URL harus diawali http:// atau https://</p>
          )}
        </div>

        <div className="space-y-2">
          <label htmlFor="proj-source" className="text-sm font-semibold text-slate-700 block">Sumber Data</label>
          <select
            id="proj-source"
            name="source"
            className="input-field"
            value={form.source}
            onChange={handleChange}
          >
            {SUPPORTED_SOURCES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        <div className="space-y-2">
          <label htmlFor="proj-category" className="text-sm font-semibold text-slate-700 block">Kategori</label>
          <select
            id="proj-category"
            name="category"
            className="input-field"
            value={form.category || 'general'}
            onChange={handleChange}
          >
            {SUPPORTED_CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        <button type="submit" className="btn-primary">
          Simpan dan Lanjut
        </button>
      </form>
    </div>
  )
}

CreateProject.propTypes = {
  project: projectShape.isRequired,
  onChange: PropTypes.func.isRequired,
}
