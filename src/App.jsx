import { useState, useEffect, useCallback } from 'react'
import ReviewRules from './pages/ReviewRules.jsx'
import CreateProject from './pages/CreateProject.jsx'
import Export from './pages/Export.jsx'
import TestCenter from './pages/TestCenter.jsx'
import UploadSamples from './pages/UploadSamples.jsx'
import Analyze from './pages/Analyze.jsx'
import GenerateJson from './pages/GenerateJson.jsx'
import PlaceholderStep from './components/PlaceholderStep.jsx'
import { projectStore } from './lib/db/projectStore.js'
import { migrateConfigSourceUrl } from './lib/import/importProject.js'
import { applyInference, markManual, regenerateWorkflow } from './lib/generator/workflowSync.js'

const STEPS = [
  { id: 'create', label: 'Buat Proyek' },
  { id: 'upload', label: 'Unggah Sampel' },
  { id: 'analyze', label: 'Analisis' },
  { id: 'review', label: 'Tinjau Aturan' },
  { id: 'generate', label: 'Generate JSON' },
  { id: 'test', label: 'Uji Provider' },
  { id: 'export', label: 'Ekspor' },
]

export default function App() {
  const [activeStep, setActiveStep] = useState(0)
  const [project, setProject] = useState({
    name: '',
    id: '',
    endpoint: '',
    configSourceUrl: '',
    source: 'generic_html',
    category: 'general',
    languages: [],
    subtitleVariants: [],
    priority: 0,
    enabled: true,
    capabilities: {},
    metadata: {},
    workflows: {},
    security: null,
    update: null,
  })
  const [savedProjects, setSavedProjects] = useState([])
  const [isLoading, setIsLoading] = useState(true)

  // Load saved projects from IndexedDB on mount
  useEffect(() => {
    const loadProjects = async() => {
      try {
        const projects = (await projectStore.getAll()).map(migrateConfigSourceUrl)
        setSavedProjects(projects)
        // Auto-load the most recent project if exists
        if (projects.length > 0) {
          const latest = projects.sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt))[0]
          setProject(latest)
        }
      } catch {
        // eslint-disable-next-line no-console
        console.error('Failed to load projects')
      } finally {
        setIsLoading(false)
      }
    }
    loadProjects()
  }, [])

  // Auto-save to IndexedDB when project changes (debounced)
  const saveProject = useCallback(async(p) => {
    if (!p.name || !p.endpoint) return
    try {
      const saved = await projectStore.save(p)
      setSavedProjects((prev) => {
        const idx = prev.findIndex((proj) => proj.id === saved.id)
        if (idx >= 0) {
          const next = [...prev]
          next[idx] = saved
          return next
        }
        return [saved, ...prev]
      })
    } catch {
      // eslint-disable-next-line no-console
      console.error('Auto-save failed')
    }
  }, [])

  useEffect(() => {
    if (!isLoading && project.name && project.endpoint) {
      const timer = setTimeout(() => saveProject(project), 1000)
      return () => clearTimeout(timer)
    }
  }, [project, isLoading, saveProject])

  // Harus stabil (dipakai di useEffect RuleEditor). Inferensi hanya memperbarui workflow otomatis (lihat workflowSync).
  const handleInferred = useCallback((inferred) => setProject((prev) => applyInference(prev, inferred)), [])
  const handleEditWorkflow = useCallback((name, wf) => setProject((prev) => markManual(prev, name, wf)), [])
  const handleRegenerate = useCallback((name, wf) => setProject((prev) => regenerateWorkflow(prev, name, wf)), [])

  const currentStep = STEPS[activeStep]

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="border-b border-slate-200 bg-white">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between h-16">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 rounded-lg bg-brand-600 flex items-center justify-center text-white font-bold text-lg">
                A
              </div>
              <h1 className="text-lg font-semibold text-slate-900 tracking-tight">
                Anilili Provider JSON Generator
              </h1>
            </div>
            <p className="text-sm text-slate-500 hidden sm:block">
              Buat dan uji provider.json untuk Anilili tanpa mengubah kode Anilili
            </p>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="flex items-end justify-between mb-8">
          <div>
            <h2 className="text-2xl font-bold text-slate-900">{currentStep.label}</h2>
            <p className="text-slate-500 mt-1">
              Langkah {activeStep + 1} dari {STEPS.length}
            </p>
          </div>
          <div className="flex gap-2">
            {activeStep > 0 && (
              <button
                className="btn-secondary"
                onClick={() => setActiveStep((s) => Math.max(0, s - 1))}
              >
                Sebelumnya
              </button>
            )}
            {activeStep < STEPS.length - 1 && (
              <button
                className="btn-primary"
                onClick={() => setActiveStep((s) => Math.min(STEPS.length - 1, s + 1))}
              >
                Berikutnya
              </button>
            )}
          </div>
        </div>

        <ol aria-label="Langkah wizard" className="flex justify-between sm:justify-start gap-1 sm:gap-4 mb-8">
          {STEPS.map((step, i) => (
            <li
              key={step.id}
              aria-current={i === activeStep ? 'step' : undefined}
              className={`flex flex-col items-center gap-2 sm:px-2 ${
                i < activeStep
                  ? 'text-brand-700'
                  : i === activeStep
                    ? 'text-brand-700 font-medium'
                    : 'text-slate-600'
              }`}
            >
              <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-bold border transition-colors ${
                i < activeStep
                  ? 'bg-brand-100 border-brand-600'
                  : i === activeStep
                    ? 'bg-brand-600 border-brand-600 text-white'
                    : 'bg-white border-slate-300'
              }`}>
                {i + 1}
              </div>
              <span className="text-xs text-center sr-only sm:not-sr-only sm:w-16">{step.label}</span>
            </li>
          ))}
        </ol>

        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
          <div className="lg:col-span-3 space-y-4">
            <div className="card space-y-3">
              <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">Proyek</h3>
              <div className="space-y-2">
                <div className="flex items-center justify-between text-sm">
                  <span className="text-slate-500">Nama</span>
                  <span className="text-slate-900 font-medium truncate max-w-xs">
                    {project.name || 'Belum ditetapkan'}
                  </span>
                </div>
                <div className="flex items-center justify-between text-sm">
                  <span className="text-slate-500">Status</span>
                  <span className="badge badge-not-tested">
                    {Object.keys(project.workflows || {}).length > 0 ? 'Aturan siap' : 'Belum aktif'}
                  </span>
                </div>
              </div>
            </div>

            <div className="card space-y-3">
              <h3 className="text-sm font-semibold text-slate-900 uppercase tracking-wider">Proyek Tersimpan</h3>
              {isLoading ? (
                <div className="text-sm text-slate-500 text-center py-4">Memuat...</div>
              ) : savedProjects.length === 0 ? (
                <div className="text-sm text-slate-500 text-center py-4">Belum ada proyek tersimpan</div>
              ) : (
                <div className="space-y-2 max-h-64 overflow-y-auto">
                  {savedProjects.map((p) => (
                    <div
                      key={p.id}
                      className={`flex items-center justify-between p-2 rounded border text-sm ${
                        project.id === p.id
                          ? 'bg-brand-50 border-brand-200'
                          : 'bg-white border-slate-200 hover:bg-slate-50'
                      }`}
                    >
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-slate-900 truncate">{p.name}</p>
                        <p className="text-xs text-slate-600 truncate">{p.endpoint}</p>
                      </div>
                      <div className="flex items-center gap-1 ml-2">
                        {project.id !== p.id && (
                          <button
                            onClick={() => setProject(p)}
                            className="btn-secondary text-xs px-2 py-1"
                            title="Buka proyek"
                          >
                            Buka
                          </button>
                        )}
                        <button
                          onClick={async() => {
                            await projectStore.delete(p.id)
                            setSavedProjects((prev) => prev.filter((proj) => proj.id !== p.id))
                            if (project.id === p.id) {
                              setProject({
                                name: '',
                                id: '',
                                endpoint: '',
                                configSourceUrl: '',
                                source: 'generic_html',
                                category: 'general',
                                languages: [],
                                subtitleVariants: [],
                                priority: 0,
                                enabled: true,
                                capabilities: {},
                                metadata: {},
                                workflows: {},
                                security: null,
                                update: null,
                              })
                            }
                          }}
                          className="btn-secondary text-xs px-2 py-1 text-red-700 hover:bg-red-50"
                          title="Hapus proyek"
                        >
                          Hapus
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div className="lg:col-span-9">
            <div className="card min-h-[500px] flex flex-col">
              <div className="flex items-center justify-between border-b border-slate-100 pb-4 mb-6">
                <h3 className="text-lg font-semibold text-slate-900">{currentStep.label}</h3>
              </div>
              <div className="flex-1 p-4 overflow-y-auto">
                {currentStep.id === 'review' ? (
                  <ReviewRules
                    endpoint={project.endpoint}
                    inferredRules={project.inferredRules}
                    ruleValues={project.ruleValues}
                    onRuleValuesChange={(ruleValues) => setProject((prev) => ({ ...prev, ruleValues }))}
                    workflows={project.workflows}
                    workflowOrigin={project.workflowOrigin}
                    onInferred={handleInferred}
                    onEditWorkflow={handleEditWorkflow}
                    onRegenerate={handleRegenerate}
                  />
                ) : currentStep.id === 'create' ? (
                  <CreateProject
                    project={project}
                    onChange={(next) => setProject(next)}
                  />
                ) : currentStep.id === 'upload' ? (
                  <UploadSamples project={project} onProjectChange={(next) => setProject(next)} />
                ) : currentStep.id === 'analyze' ? (
                  <Analyze project={project} onProjectChange={(next) => setProject(next)} />
                ) : currentStep.id === 'generate' ? (
                  <GenerateJson project={project} onProjectChange={(next) => setProject(next)} />
                ) : currentStep.id === 'test' ? (
                  <TestCenter project={project} onProjectChange={(next) => setProject(next)} />
                ) : currentStep.id === 'export' ? (
                  <Export
                    project={project}
                    onProjectChange={(next) => setProject(next)}
                    onImportSuccess={() => {
                      setActiveStep(0)
                    }}
                  />
                ) : (
                  <PlaceholderStep step={currentStep} state="empty" />
                )}
              </div>
            </div>
          </div>
        </div>
      </main>

      <footer className="border-t border-slate-200 bg-white mt-auto">
        <div className="max-w-7xl mx-auto px-4 py-6">
          <p className="text-xs text-slate-500">
            Anilili Provider JSON Generator — prototype development. Tidak untuk produksi.
          </p>
        </div>
      </footer>
    </div>
  )
}
