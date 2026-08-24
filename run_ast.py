import sys, json
from graphify.extract import collect_files, extract
from pathlib import Path

# Read detection JSON handling UTF-16 BOM
content = Path('graphify-out/.graphify_detect.json').read_bytes()
if content.startswith(b'\xff\xfe') or content.startswith(b'\xfe\xff'):
    content = content.decode('utf-16')
else:
    content = content.decode('utf-8')
detect = json.loads(content)

code_files = []
for f in detect.get('files', {}).get('code', []):
    code_files.extend(collect_files(Path(f)) if Path(f).is_dir() else [Path(f)])

if code_files:
    result = extract(code_files, cache_root=Path('.'))
    Path('graphify-out/.graphify_ast.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print(f'AST: {len(result["nodes"])} nodes, {len(result["edges"])} edges')
else:
    Path('graphify-out/.graphify_ast.json').write_text(json.dumps({'nodes':[],'edges':[],'input_tokens':0,'output_tokens':0}, ensure_ascii=False), encoding='utf-8')
    print('No code files - skipping AST extraction')