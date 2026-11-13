// projectPropTypes.js — bentuk objek `project` (state wizard) yang dipakai lintas halaman.

import PropTypes from 'prop-types'

export const projectShape = PropTypes.shape({
  id: PropTypes.string,
  name: PropTypes.string,
  endpoint: PropTypes.string,
  configSourceUrl: PropTypes.string,
  source: PropTypes.string,
  category: PropTypes.string,
  samples: PropTypes.arrayOf(PropTypes.shape({
    fileName: PropTypes.string,
    type: PropTypes.string,
    html: PropTypes.string,
  })),
  inferredRules: PropTypes.object,
  ruleValues: PropTypes.object,
  // kategori → [fileName]: search, episodes, sources, servers, homepage
  sampleMap: PropTypes.object,
  // nama workflow → 'auto' | 'manual' (hanya 'auto' yang boleh diperbarui inferensi)
  workflowOrigin: PropTypes.objectOf(PropTypes.oneOf(['auto', 'manual'])),
  workflows: PropTypes.objectOf(PropTypes.shape({
    steps: PropTypes.arrayOf(PropTypes.object),
  })),
})

export const inferredRulesShape = PropTypes.shape({
  search: PropTypes.arrayOf(PropTypes.object),
  episodes: PropTypes.arrayOf(PropTypes.object),
  stream: PropTypes.arrayOf(PropTypes.object),
})
