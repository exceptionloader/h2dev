const { spiral, ViewRect, chunkPos } = require('./simpleUtils')
const { Vec3 } = require('vec3')
const EventEmitter = require('events')

// MineSMP-Patch: 1.21.11-Block-IDs auf 1.21.4 (Texturen des Viewers) umrechnen
const SRC = '1.21.11', DST = '1.21.4'
let remap = null, DstChunk = null
function buildRemap () {
  const src = require('prismarine-block')(SRC), dst = require('prismarine-block')(DST)
  const mdS = require('minecraft-data')(SRC), mdD = require('minecraft-data')(DST)
  DstChunk = require('prismarine-chunk')(DST)
  const max = Math.max(...mdS.blocksArray.map(b => b.maxStateId))
  remap = new Uint32Array(max + 1)
  for (const b of mdS.blocksArray) {
    const target = mdD.blocksByName[b.name]
    for (let id = b.minStateId; id <= b.maxStateId; id++) {
      if (!target) { remap[id] = mdD.blocksByName.stone.defaultState; continue }
      let out = target.defaultState
      try {
        const props = src.fromStateId(id, 0).getProperties()
        const nb = dst.fromProperties(b.name, props, 0)
        if (nb && nb.stateId !== undefined) out = nb.stateId
      } catch (e) {}
      remap[id] = out
    }
  }
}
function convertColumn (col) {
  if (!remap) buildRemap()
  const minY = col.minY ?? 0, h = col.worldHeight ?? 256
  const out = new DstChunk({ minY, worldHeight: h })
  const p = new Vec3(0, 0, 0)
  for (let sy = 0; sy < h / 16; sy++) {
    const sec = col.sections && col.sections[sy]
    const empty = !sec || sec.solidBlockCount === 0
    for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      p.x = x; p.y = minY + sy * 16 + y; p.z = z
      if (!empty) {
        const id = col.getBlockStateId(p)
        if (id) out.setBlockStateId(p, remap[id] ?? 1)
      }
      out.setSkyLight(p, 15)
    }
  }
  return out
}

class WorldView extends EventEmitter {
  constructor (world, viewDistance, position = new Vec3(0, 0, 0), emitter = null) {
    super()
    this.world = world
    this.viewDistance = viewDistance
    this.loadedChunks = {}
    this.lastPos = new Vec3(0, 0, 0).update(position)
    this.emitter = emitter || this

    this.listeners = {}
    this.emitter.on('mouseClick', async (click) => {
      const ori = new Vec3(click.origin.x, click.origin.y, click.origin.z)
      const dir = new Vec3(click.direction.x, click.direction.y, click.direction.z)
      const block = this.world.raycast(ori, dir, 256)
      if (!block) return
      this.emit('blockClicked', block, block.face, click.button)
    })
  }

  listenToBot (bot) {
    const worldView = this
    this.listeners[bot.username] = {
      // 'move': botPosition,
      entitySpawn: function (e) {
        if (e === bot.entity) return
        worldView.emitter.emit('entity', { id: e.id, name: e.name, pos: e.position, width: e.width, height: e.height, username: e.username })
      },
      entityMoved: function (e) {
        worldView.emitter.emit('entity', { id: e.id, pos: e.position, pitch: e.pitch, yaw: e.yaw })
      },
      entityGone: function (e) {
        worldView.emitter.emit('entity', { id: e.id, delete: true })
      },
      chunkColumnLoad: function (pos) {
        worldView.loadChunk(pos)
      },
      blockUpdate: function (oldBlock, newBlock) {
        if (!remap) buildRemap()
        const stateId = remap[newBlock.stateId] ?? 0
        worldView.emitter.emit('blockUpdate', { pos: oldBlock.position, stateId })
      }
    }

    for (const [evt, listener] of Object.entries(this.listeners[bot.username])) {
      bot.on(evt, listener)
    }

    for (const id in bot.entities) {
      const e = bot.entities[id]
      if (e && e !== bot.entity) {
        this.emitter.emit('entity', { id: e.id, name: e.name, pos: e.position, width: e.width, height: e.height, username: e.username })
      }
    }
  }

  removeListenersFromBot (bot) {
    for (const [evt, listener] of Object.entries(this.listeners[bot.username])) {
      bot.removeListener(evt, listener)
    }
    delete this.listeners[bot.username]
  }

  async init (pos) {
    const [botX, botZ] = chunkPos(pos)

    const positions = []
    spiral(this.viewDistance * 2, this.viewDistance * 2, (x, z) => {
      const p = new Vec3((botX + x) * 16, 0, (botZ + z) * 16)
      positions.push(p)
    })

    this.lastPos.update(pos)
    await this._loadChunks(positions)
  }

  async _loadChunks (positions, sliceSize = 5, waitTime = 0) {
    for (let i = 0; i < positions.length; i += sliceSize) {
      await new Promise((resolve) => setTimeout(resolve, waitTime))
      await Promise.all(positions.slice(i, i + sliceSize).map(p => this.loadChunk(p)))
    }
  }

  async loadChunk (pos) {
    const [botX, botZ] = chunkPos(this.lastPos)
    const dx = Math.abs(botX - Math.floor(pos.x / 16))
    const dz = Math.abs(botZ - Math.floor(pos.z / 16))
    if (dx < this.viewDistance && dz < this.viewDistance) {
      const column = await this.world.getColumnAt(pos)
      if (column) {
        const chunk = convertColumn(column).toJson()
        this.emitter.emit('loadChunk', { x: pos.x, z: pos.z, chunk })
        this.loadedChunks[`${pos.x},${pos.z}`] = true
      }
    }
  }

  unloadChunk (pos) {
    this.emitter.emit('unloadChunk', { x: pos.x, z: pos.z })
    delete this.loadedChunks[`${pos.x},${pos.z}`]
  }

  async updatePosition (pos, force = false) {
    const [lastX, lastZ] = chunkPos(this.lastPos)
    const [botX, botZ] = chunkPos(pos)
    if (lastX !== botX || lastZ !== botZ || force) {
      const newView = new ViewRect(botX, botZ, this.viewDistance)
      for (const coords of Object.keys(this.loadedChunks)) {
        const x = parseInt(coords.split(',')[0])
        const z = parseInt(coords.split(',')[1])
        const p = new Vec3(x, 0, z)
        if (!newView.contains(Math.floor(x / 16), Math.floor(z / 16))) {
          this.unloadChunk(p)
        }
      }
      const positions = []
      spiral(this.viewDistance * 2, this.viewDistance * 2, (x, z) => {
        const p = new Vec3((botX + x) * 16, 0, (botZ + z) * 16)
        if (!this.loadedChunks[`${p.x},${p.z}`]) {
          positions.push(p)
        }
      })
      this.lastPos.update(pos)
      await this._loadChunks(positions)
    } else {
      this.lastPos.update(pos)
    }
  }
}

module.exports = { WorldView }
