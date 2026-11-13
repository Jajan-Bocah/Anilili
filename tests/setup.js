// Vitest setup file — jsdom environment configured in vite.config.js
import { indexedDB } from 'fake-indexeddb'
import {
  IDBCursor,
  IDBCursorWithValue,
  IDBDatabase,
  IDBIndex,
  IDBKeyRange,
  IDBObjectStore,
  IDBOpenDBRequest,
  IDBRequest,
  IDBTransaction,
  IDBVersionChangeEvent,
} from 'fake-indexeddb'

// Expose all IDB globals so the `idb` library's instanceof checks work
globalThis.indexedDB = indexedDB
globalThis.IDBCursor = IDBCursor
globalThis.IDBCursorWithValue = IDBCursorWithValue
globalThis.IDBDatabase = IDBDatabase
globalThis.IDBIndex = IDBIndex
globalThis.IDBKeyRange = IDBKeyRange
globalThis.IDBObjectStore = IDBObjectStore
globalThis.IDBOpenDBRequest = IDBOpenDBRequest
globalThis.IDBRequest = IDBRequest
globalThis.IDBTransaction = IDBTransaction
globalThis.IDBVersionChangeEvent = IDBVersionChangeEvent
