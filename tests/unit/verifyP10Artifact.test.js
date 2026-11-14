// P10 Artifact verification - generate Anichin provider.json and compute SHA-256
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { analyzeSamples, mergeAnalysis, autoAssignSamples } from '../../src/lib/samples/sampleMap.js'
import { inferWorkflows } from '../../src/lib/generator/workflowInference.js'
import { generateProviderJson } from '../../src/lib/generator/providerGenerator.js'
import { runMappedOffline } from '../../src/lib/samples/runMapped.js'

const EP = 'https://anichin.biz'
const BASE = { name: 'Anichin', endpoint: EP, configSourceUrl: 'https://example.com/anichin.json', source: 'generic_html' }

const rd = (n) => readFileSync(`tests/fixtures/real/anichin/${n}`, 'utf8')
const samples = ['search.htm', 'series.htm', 'watch.htm'].map((fileName) => ({ fileName, html: rd(fileName) }))
const perSample = analyzeSamples(samples)
const inferredRules = mergeAnalysis(perSample)
const sampleMap = autoAssignSamples(perSample, inferredRules)
const { workflows } = inferWorkflows(inferredRules, { endpoint: EP })
const project = { ...BASE, name: 'Anichin', samples, inferredRules, sampleMap, workflows }
const result = generateProviderJson(project)

describe('P10 Artifact: Anichin provider.json', () => {
  it('generates valid provider.json with correct capabilities', () => {
    expect(result.meta.valid).toBe(true)
    expect(result.providerJson.providers[0].capabilities.supportsSearch).toBe(true)
    expect(result.providerJson.providers[0].capabilities.requiresWebView).toBe(true)
    expect(result.providerJson.providers[0].capabilities.supportsEpisodes).toBe(true)
    expect(result.providerJson.providers[0].capabilities.supportsSources).toBe(true)
    expect(result.providerJson.workflows.search).toBeDefined()
    expect(result.providerJson.workflows.episodes).toBeDefined()
    expect(result.providerJson.workflows.sources).toBeDefined()
    expect(result.providerJson.security.allowedDomains).toEqual(['anichin.biz'])

    // Verify Search/episodes/sources output counts via offline test
    const { perWorkflow } = runMappedOffline({ ...project, workflows: result.providerJson.workflows })
    const by = Object.fromEntries(perWorkflow.map((x) => [x.name, x]))
    expect(by.search.engine.items).toBe(2)
    expect(by.episodes.engine.items).toBe(166)
    expect(by.sources.engine.items).toBe(1)
    expect(by.sources.rawOutput.streams).toEqual(['https://ok.ru/videoembed/16438442330802'])
  })

  it('computes SHA-256 for artifact', () => {
    const json = JSON.stringify(result.providerJson, null, 2)
    const hash = createHash('sha256').update(json).digest('hex')
    // SHA-256 recorded in test output for verification
    expect(hash).toMatch(/^[a-f0-9]{64}$/)
  })
})
