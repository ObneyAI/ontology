#!/usr/bin/env python3
"""Exercise coverage-checker failures in an isolated temporary checkout."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile

module_spec = importlib.util.spec_from_file_location('checker', Path(__file__).with_name('check_obligations.py'))
checker = importlib.util.module_from_spec(module_spec)
module_spec.loader.exec_module(checker)
root = checker.ROOT
with tempfile.TemporaryDirectory(prefix='ontology-coverage-audit-') as directory:
    checker.ROOT = Path(directory)
    for path in ['specs', 'test']:
        shutil.copytree(root / path, checker.ROOT / path)
    checker.SPEC = checker.ROOT / 'specs/ontology.allium'
    ledger_path = checker.ROOT / 'specs/test-coverage.json'
    baseline = json.loads(ledger_path.read_text())
    with contextlib.redirect_stdout(io.StringIO()):
        checker.verify()
    outcomes = []
    for mode in ['missing-obligation', 'obsolete-obligation', 'missing-test', 'empty-review', 'stale-spec']:
        ledger = copy.deepcopy(baseline)
        key = next(iter(ledger['obligations']))
        if mode == 'missing-obligation':
            del ledger['obligations'][key]
        elif mode == 'obsolete-obligation':
            ledger['obligations']['obsolete'] = ledger['obligations'][key]
        elif mode == 'missing-test':
            ledger['obligations'][key]['tests'] = ['missing/test']
        elif mode == 'empty-review':
            ledger['obligations'][key]['review'] = ''
        elif mode == 'stale-spec':
            checker.SPEC.write_text(checker.SPEC.read_text() + '\n-- altered spec\n')
        ledger_path.write_text(json.dumps(ledger))
        try:
            checker.verify()
        except AssertionError:
            outcomes.append({'regression': mode, 'detected': True})
        else:
            raise AssertionError(f'Coverage checker missed {mode}')
    print(json.dumps({'control': 'pass', 'cases': outcomes}))
