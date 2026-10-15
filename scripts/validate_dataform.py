"""Compile the actual Dataform project locally, without credentials or remote jobs."""
import json
import shutil
import subprocess
from pathlib import Path
from scripts.sync_dataform import ROOT, TARGET, generated


def main():
    if not TARGET.exists() or TARGET.read_text(encoding='utf-8') != generated():
        raise SystemExit('Dataform SQL is stale: run python -m scripts.sync_dataform.')
    node = shutil.which('node')
    cli = ROOT / 'tools/dataform/node_modules/@dataform/cli/bundle.js'
    if not node or not cli.is_file():
        raise SystemExit('Install Node.js and run npm ci in tools/dataform and warehouse/dataform (README).')
    process = subprocess.run([node, str(cli), 'compile', str(ROOT / 'warehouse/dataform'), '--json'],
                             cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=60)
    if process.returncode:
        raise SystemExit(process.stderr)
    graph = json.loads(process.stdout)
    if graph.get('graphErrors'):
        raise SystemExit('Dataform compilation failed: ' + json.dumps(graph['graphErrors']))
    operations = {operation['target']['name']: operation for operation in graph.get('operations', [])}
    if set(operations) != {'staging_quality', 'publish_campaign'}:
        raise SystemExit('Expected exactly staging_quality and publish_campaign Dataform operations.')
    quality = '\n'.join(operations['staging_quality']['queries'])
    publish = '\n'.join(operations['publish_campaign']['queries'])
    if 'BEGIN TRANSACTION;' in quality or 'CONFLICTING_DUPLICATE_GRAIN' not in quality:
        raise SystemExit('Quality must validate staging before starting publication.')
    if 'COMMIT TRANSACTION;' not in publish or '@run_id' not in publish or 'YOUR_PROJECT' in publish:
        raise SystemExit('Compiled publication must retain its run scope and transaction.')
    dependencies = operations['publish_campaign'].get('dependencyTargets', [])
    if not any(target['name'] == 'staging_quality' for target in dependencies):
        raise SystemExit('Dataform publish must depend on staging_quality.')
    print('Dataform 3.1.0 compiled: staging quality + transactional campaign publication; no cloud jobs submitted.')


if __name__ == '__main__':
    main()
