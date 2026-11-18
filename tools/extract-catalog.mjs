import fs from 'node:fs'
import path from 'node:path'
import crypto from 'node:crypto'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const require = createRequire(new URL('../web/package.json', import.meta.url))
const { parse } = require('acorn')

/** 只解释字面量、常量引用和字典赋值，绝不执行来自竞品的函数或表达式。 */
export function extractCatalog(source) {
  const tree = parse(source, { ecmaVersion: 'latest', locations: true })
  const env = new Map()
  const outputs = Object.create(null)
  const fieldLines = Object.create(null)
  const nameLines = Object.create(null)
  function value(node) {
    if (!node) throw new Error('缺少静态表达式')
    if (node.type === 'Literal') return node.value
    if (node.type === 'Identifier' && env.has(node.name)) return env.get(node.name)
    if (node.type === 'ArrayExpression') return node.elements.map(value)
    if (node.type === 'ObjectExpression') {
      const object = Object.create(null)
      for (const item of node.properties) {
        if (item.type !== 'Property' || item.computed || item.kind !== 'init') throw new Error('不支持动态字典')
        object[item.key.name ?? item.key.value] = value(item.value)
      }
      return object
    }
    if (node.type === 'UnaryExpression' && node.operator === '-') return -value(node.argument)
    if (node.type === 'SequenceExpression') {
      let result
      for (const item of node.expressions) result = value(item)
      return result
    }
    if (node.type === 'AssignmentExpression' && node.operator === '=') {
      const target = node.left.type === 'MemberExpression' ? value(node.left.object) : undefined
      const result = value(node.right)
      if (node.left.type === 'Identifier') env.set(node.left.name, result)
      else if (node.left.type === 'MemberExpression') {
        if (!target || typeof target !== 'object') throw new Error('未知字典赋值目标')
        const key = node.left.computed ? value(node.left.property) : node.left.property.name
        target[key] = result
        fieldLines[key] = node.loc.start.line
      } else throw new Error('不支持赋值目标，行 ' + node.loc.start.line)
      return result
    }
    throw new Error(`无法静态解析 ${node.type}，行 ${node.loc.start.line}`)
  }
  function walk(node) {
    if (!node || typeof node !== 'object') return
    if (node.type === 'VariableDeclarator' && node.id.type === 'Identifier' && node.init) {
      try { env.set(node.id.name, value(node.init)) } catch { /* 宿主对象与函数不属于协议字典。 */ }
    }
    if (node.type === 'CallExpression' && node.callee.type === 'Identifier' && node.arguments[0]?.type === 'Literal') {
      const name = node.arguments[0].value
      if (['thriftData', 'messageData', 'structData', 'enumData'].includes(name)) {
        outputs[name] = value(node.arguments[1])
        if (name === 'messageData') {
          for (const item of node.arguments[1].elements) nameLines[value(item)[1]] = item.loc.start.line
        }
        return
      }
    }
    for (const [key, item] of Object.entries(node)) {
      if (key === 'loc') continue
      if (Array.isArray(item)) item.forEach(walk)
      else if (item && typeof item === 'object') walk(item)
    }
  }
  walk(tree)
  if (!outputs.thriftData || !outputs.messageData || !outputs.structData) throw new Error('未找到完整协议导出')
  const messages = Object.create(null)
  for (const [name, id] of outputs.messageData) {
    if (messages[id]) throw new Error(`重复消息编号 ${id}`)
    messages[id] = { name, direction: name.startsWith('Cg') ? 'CS' : name.startsWith('Gc') ? 'SC' : 'UNKNOWN', line: nameLines[id] }
    if (!Object.hasOwn(outputs.thriftData, id)) throw new Error(`缺少 ${name} 字段定义`)
  }
  // 抽样以外的所有字段也校验类型引用，版本变化时明确失败，不生成半份字典。
  function checkType(type) {
    if (typeof type === 'number') {
      if (![2, 3, 4, 6, 8, 10, 11].includes(type)) throw new Error(`不支持的 Thrift 类型 ${type}`)
      return
    }
    if (!Array.isArray(type)) throw new Error('非法字段类型')
    if (type[0] === 11) {
      // 新版客户端 StringKind：0 为文本，1 为二进制；两者 wire 类型均为 STRING。
      if (type.length !== 2 || ![0, 1].includes(type[1])) throw new Error('不支持的 STRING 子类型')
    } else if (type[0] === 12) {
      if (!Object.hasOwn(outputs.thriftData, type[1])) throw new Error(`结构体引用缺失 ${type[1]}`)
    } else if ([13, 14, 15].includes(type[0])) type.slice(1).forEach(checkType)
    else throw new Error(`不支持的容器 ${type[0]}`)
  }
  for (const fields of Object.values(outputs.thriftData)) {
    for (const entry of Object.values(fields)) {
      if (typeof entry[0] !== 'string') throw new Error('字段名未还原')
      checkType(entry[1])
    }
  }
  return { schemaVersion: 1, sha256: crypto.createHash('sha256').update(source).digest('hex'),
    messages, definitions: outputs.thriftData, enums: outputs.enumData ?? [], fieldLines }
}

/** 目录布局不作为版本约定；有界遍历找同名文件，不跟随目录链接，也不自动猜测多个候选的优先级。 */
export function discoverProtocolFiles(input) {
  const value = String(input || '').trim().replace(/^"(.*)"$/, '$1')
  if (!value) throw new Error('请填写客户端目录或 thrift_data.js 文件路径')
  const resolved = fs.realpathSync(value)
  const stat = fs.statSync(resolved)
  const root = stat.isFile() ? path.dirname(resolved) : resolved
  const candidates = []
  function add(file, info) {
    if (info.size > 64 * 1024 * 1024) throw new Error(`协议文件超过 64 MiB：${file}`)
    candidates.push({path: file, relativePath: path.relative(root, file).replaceAll('\\', '/'), sizeBytes: info.size, modifiedAt: info.mtime.toISOString()})
    if (candidates.length > 100) throw new Error('候选协议文件超过 100 个，请缩小目录或直接填写文件路径')
  }
  if (stat.isFile()) {
    if (path.basename(resolved).toLowerCase() !== 'thrift_data.js') throw new Error('请指定 thrift_data.js 文件')
    add(resolved, stat)
  } else if (stat.isDirectory()) {
    const pending = [{directory: root, depth: 0}]
    const excluded = new Set(['node_modules', '.git', '.svn', '.gradle'])
    const deadline = Date.now() + 10000
    let visited = 0
    while (pending.length) {
      const {directory, depth} = pending.pop()
      for (const entry of fs.readdirSync(directory, {withFileTypes: true})) {
        if (++visited > 200000 || Date.now() > deadline) throw new Error('扫描范围过大，请缩小目录或直接填写协议文件路径')
        const file = path.join(directory, entry.name)
        if (entry.isDirectory() && !excluded.has(entry.name.toLowerCase())) {
          if (depth >= 32) throw new Error('目录层级超过 32 层，请缩小目录')
          pending.push({directory: file, depth: depth + 1})
        } else if (entry.isFile() && entry.name.toLowerCase() === 'thrift_data.js') add(file, fs.statSync(file))
      }
    }
  } else throw new Error('路径必须为普通目录或协议文件')
  candidates.sort((a, b) => a.relativePath.localeCompare(b.relativePath, 'en'))
  return {root, input: resolved, candidates}
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  if (process.argv[2] === '--discover') {
    const [, input, destination] = process.argv.slice(2)
    fs.writeFileSync(destination, JSON.stringify(discoverProtocolFiles(input)), 'utf8')
  } else {
  const [input, destination, sourceRoot] = process.argv.slice(2)
  if (!input || !destination) throw new Error('用法：node tools/extract-catalog.mjs <客户端目录或协议文件> <catalog.json> [来源根目录]')
  const discovery = discoverProtocolFiles(input)
  if (discovery.candidates.length !== 1) throw new Error(discovery.candidates.length ? '找到多个 thrift_data.js，请在页面选择文件或直接传入文件路径' : '未找到 thrift_data.js，请检查路径')
  const file = discovery.candidates[0].path
  const root = sourceRoot ? fs.realpathSync(sourceRoot) : discovery.root
  const catalog = extractCatalog(fs.readFileSync(file, 'utf8'))
  catalog.sourceFile = path.relative(root, file).replaceAll('\\', '/')
  catalog.sourceRoot = root
  catalog.generatedAt = new Date().toISOString()
  fs.mkdirSync(path.dirname(destination), { recursive: true })
  const temporary = destination + '.tmp'
  fs.writeFileSync(temporary, JSON.stringify(catalog), 'utf8')
  fs.renameSync(temporary, destination)
  console.log(`协议字典已生成：${Object.keys(catalog.messages).length} 条消息，${Object.keys(catalog.definitions).length} 份字段定义，SHA256 ${catalog.sha256}`)
  }
}
