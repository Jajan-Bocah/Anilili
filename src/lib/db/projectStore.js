// IndexedDB wrapper untuk penyimpanan proyek secara lokal di browser
import { openDB } from 'idb'

const DB_NAME = 'anilili-provider-generator'
const DB_VERSION = 1
const STORE_NAME = 'projects'

let dbPromise = null

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: 'id', autoIncrement: true })
        }
      },
    })
  }
  return dbPromise
}

export const projectStore = {
  async save(project) {
    const db = await getDB()
    const now = new Date().toISOString()
    const record = {
      ...project,
      updatedAt: now,
      createdAt: project.createdAt || now,
    }
    const id = await db.put(STORE_NAME, record)
    return { id, ...record }
  },

  async getAll() {
    const db = await getDB()
    return db.getAll(STORE_NAME)
  },

  async get(id) {
    const db = await getDB()
    return db.get(STORE_NAME, id)
  },

  async delete(id) {
    const db = await getDB()
    return db.delete(STORE_NAME, id)
  },
}
