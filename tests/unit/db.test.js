import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { projectStore } from '../../src/lib/db/projectStore.js'

describe('projectStore (IndexedDB)', () => {
  beforeEach(async() => {
    // Clear all projects before each test
    const all = await projectStore.getAll()
    for (const p of all) {
      await projectStore.delete(p.id)
    }
  })

  afterEach(async() => {
    const all = await projectStore.getAll()
    for (const p of all) {
      await projectStore.delete(p.id)
    }
  })

  it('should save and retrieve a project', async() => {
    const project = {
      name: 'Test Provider',
      source: 'generic_html',
      endpoint: 'https://example.com/provider.json',
    }

    const saved = await projectStore.save(project)

    expect(saved.id).toBeDefined()
    expect(saved.name).toBe('Test Provider')
    expect(saved.source).toBe('generic_html')
    expect(saved.createdAt).toBeDefined()
    expect(saved.updatedAt).toBeDefined()
  })

  it('should update existing project when id provided', async() => {
    const saved = await projectStore.save({ name: 'Original' })

    const updated = await projectStore.save({ id: saved.id, name: 'Updated' })

    expect(updated.id).toBe(saved.id)
    expect(updated.name).toBe('Updated')

    const all = await projectStore.getAll()
    expect(all.length).toBe(1)
    expect(all[0].name).toBe('Updated')
  })

  it('should get all projects', async() => {
    await projectStore.save({ name: 'Project A' })
    await projectStore.save({ name: 'Project B' })

    const all = await projectStore.getAll()
    expect(all.length).toBe(2)
  })

  it('should delete a project', async() => {
    const saved = await projectStore.save({ name: 'To Delete' })

    await projectStore.delete(saved.id)

    const all = await projectStore.getAll()
    expect(all.length).toBe(0)
  })

  it('should get a single project by id', async() => {
    const saved = await projectStore.save({ name: 'Single' })

    const found = await projectStore.get(saved.id)

    expect(found.id).toBe(saved.id)
    expect(found.name).toBe('Single')
  })
})
