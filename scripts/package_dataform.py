"""Prepare a source-only Dataform repository archive; never uploads or publishes it."""
import argparse
import zipfile
from pathlib import Path
from scripts.sync_dataform import ROOT, TARGET, generated


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    if TARGET.read_text(encoding='utf-8') != generated():
        raise SystemExit('Run python -m scripts.sync_dataform before packaging.')
    source = ROOT / 'warehouse/dataform'
    files = [source / name for name in ('package.json', 'package-lock.json', 'workflow_settings.yaml')]
    files += sorted((source / 'definitions').glob('*.sqlx')) + sorted((source / 'includes').glob('*.js'))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(source).as_posix())
    print(output.resolve())
