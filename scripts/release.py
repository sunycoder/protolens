import hashlib,json,os,pathlib,subprocess,sys,urllib.request

def gh(*args): return subprocess.check_output(['gh',*args],text=True)
def expected(version):
 return sorted(['ProtoLens-latest.exe',f'ProtoLens-{version}-windows-x64.zip',f'ProtoLens-{version}-macos-arm64.dmg',f'ProtoLens-{version}-macos-arm64.tar.gz',f'ProtoLens-{version}-macos-x64.dmg',f'ProtoLens-{version}-macos-x64.tar.gz',f'ProtoLens-{version}-linux-x64.tar.gz'])
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
 return h.hexdigest()
def verify(directory,version):
 names=sorted(p.name for p in directory.iterdir() if p.is_file() and p.name!='SHA256SUMS.txt')
 if names!=expected(version):raise RuntimeError('Unexpected or missing release assets')
 for name in names:
  p=directory/name
  if p.stat().st_size==0:raise RuntimeError('Empty asset')
  with p.open('rb') as f:head=f.read(4)
  magic=b'MZ' if name.endswith('.exe') else b'PK' if name.endswith('.zip') else b'\x1f\x8b' if name.endswith('.tar.gz') else None
  if magic and not head.startswith(magic):raise RuntimeError('Unexpected asset file format')
 (directory/'SHA256SUMS.txt').write_text(''.join(digest(directory/n)+'  '+n+'\n' for n in names))
def publish(directory,tag,notes):
 repo=os.environ['GITHUB_REPOSITORY']
 gh('release','create',tag,'--repo',repo,'--draft','--latest=false','--target',os.environ['GITHUB_SHA'],'--title',tag,'--notes',notes)
 gh('release','upload',tag,'--repo',repo,*[str(p) for p in sorted(directory.iterdir()) if p.is_file()])
 gh('release','edit',tag,'--repo',repo,'--draft=false','--latest')
if sys.argv[1]=='publish':
 version=os.environ['VERSION'];directory=pathlib.Path('dist');verify(directory,version)
 artifacts=[]
 for name in expected(version):
  platform='windows-x64' if name.endswith(('.exe','.zip')) else 'macos-arm64' if '-macos-arm64.' in name else 'macos-x64' if '-macos-x64.' in name else 'linux-x64'
  system,architecture=platform.split('-');p=directory/name
  artifacts.append({'name':name,'platform':system,'architecture':architecture,'size':p.stat().st_size,'sha256':digest(p),'url':'https://github.com/'+os.environ['GITHUB_REPOSITORY']+'/releases/download/v'+version+'/'+name})
 (directory/'release-manifest.json').write_text(json.dumps({'version':version,'sourceSha':os.environ['SOURCE_SHA'],'artifacts':artifacts},indent=2)+'\n')
 publish(directory,'v'+version,'ProtoLens '+version+'\n\n公开安装包与便携包；源码独立私有保存。')
elif sys.argv[1]=='mirror':
 source=os.environ['SOURCE_REPOSITORY'];repo=os.environ['GITHUB_REPOSITORY']
 releases=json.loads(gh('api',f'repos/{source}/releases?per_page=100'))
 for release in sorted([x for x in releases if not x['draft'] and not x['prerelease']],key=lambda x:x['published_at']):
  tag=release['tag_name'];directory=pathlib.Path('mirror')/tag;directory.mkdir(parents=True,exist_ok=True)
  existing=json.loads(gh('api',f'repos/{repo}/releases?per_page=100'))
  if any(x['tag_name']==tag for x in existing):raise RuntimeError('Release exists; inspect before resuming')
  for asset in release['assets']:
   name=asset['name']
   if not name.startswith('ProtoLens-') or '/' in name or '\\' in name or not name.endswith(('.exe','.zip','.tar.gz','.dmg','.pkg')):raise RuntimeError('Unexpected historical asset')
   gh('release','download',tag,'--repo',source,'--dir',str(directory),'--pattern',name)
   p=directory/name
   if p.stat().st_size!=asset['size'] or 'sha256:'+digest(p)!=asset.get('digest'):raise RuntimeError('Historical asset digest mismatch')
  (directory/'SHA256SUMS.txt').write_text(''.join(digest(p)+'  '+p.name+'\n' for p in sorted(directory.iterdir()) if p.is_file()))
  publish(directory,tag,'历史版本迁移。安装包为原发行附件，相同版本相同字节，完整 SHA-256 已校验。')
