#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/check_obligations.py | tee evidence/obligation-review.json
python3 scripts/audit_coverage_checker.py | tee evidence/coverage-checker-audit.json
# Allium uses nonzero status for characterized diagnostics. Inspect the JSON
# instead of treating a warning as an error or ignoring genuine failures.
allium check specs > evidence/rigorous-spec-check.json || true
allium analyse specs > evidence/rigorous-spec-analyse.json || true
python3 - <<'PY'
import collections, json
from pathlib import Path
for name in ['check', 'analyse']:
    result = json.loads(Path(f'evidence/rigorous-spec-{name}.json').read_text())
    diagnostics = result['diagnostics']
    assert not any(d['severity'] == 'error' for d in diagnostics), result
    assert not result.get('findings'), result
    print('Allium', name, dict(collections.Counter(d['severity'] for d in diagnostics)), '0 errors, 0 findings')
PY
clojure -M:test | tee evidence/rigorous-current.log
ONTOLOGY_TEST_SEED=7 ONTOLOGY_TEST_TRIALS=500 clojure -M:test ai.obney.ontology.property-test | tee evidence/property-campaign.log
clojure -Sdeps '{:paths ["src" "test"]}' -M scripts/mutation_audit.clj | tee evidence/mutation-audit.log
