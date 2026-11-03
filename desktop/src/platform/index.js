'use strict';
function create(electron) {
  if (process.platform === 'win32') return new (require('./windows').Windows)(electron);
  if (process.platform === 'darwin') throw new Error('macOS is not supported yet');
  return new (require('./linux').Linux)(electron);
}
module.exports = { create };
