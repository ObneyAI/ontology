#!/usr/bin/env python3
"""Run ontology benchmark cases in separate JVMs, sequentially, with hard time limits."""
import argparse
import csv
import datetime
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OPERATIONS = ['projection-replay', 'lookup-uri', 'list-concepts', 'search-lexical', 'search-graph',
              'search-vector', 'export-current', 'export-first-import', 'retry-contribution',
              'update-concept', 'import-resolved', 'import-delta']

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sizes', default='10000,20000')
    parser.add_argument('--backends', default='in-memory,sqlite')
    parser.add_argument('--operations', default='projection-replay,lookup-uri,import-delta')
    parser.add_argument('--samples', type=int, default=5)
    parser.add_argument('--warmup', type=int, default=2)
    parser.add_argument('--batch-size', type=int, default=1000)
    parser.add_argument('--history', type=int, default=0)
    parser.add_argument('--timeout', type=float, default=180, help='Seconds for the entire case JVM, including startup/setup/warmup')
    parser.add_argument('--heap', default='2g')
    parser.add_argument('--output', type=Path, default=ROOT / 'target/benchmarks' / datetime.datetime.now().strftime('%Y%m%d-%H%M%S'))
    args = parser.parse_args()
    sizes = [int(x) for x in args.sizes.split(',')]
    backends = args.backends.split(',')
    operations = args.operations.split(',')
    if not (all(n > 0 for n in sizes) and len(sizes) == len(set(sizes))
            and len(backends) == len(set(backends)) and len(operations) == len(set(operations)) and set(backends) <= {'in-memory', 'sqlite'} and set(operations) <= set(OPERATIONS)
            and args.samples > 0 and args.warmup >= 0 and args.history >= 0 and args.batch_size > 0 and args.timeout > 0):
        parser.error('Invalid sizes, backend, operation, counts, or timeout')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cases = []
    start = time.time()
    for backend in backends:
        for size in sizes:
            for operation in operations:
                name = f'{backend}-{size}-{operation}'
                case_dir = output / name
                log_path = output / f'{name}.log'
                scratch = tempfile.TemporaryDirectory(prefix='ontology-benchmark-case-')
                cmd = ['clojure', f'-J-Djava.io.tmpdir={scratch.name}', '-J-Xms256m', f'-J-Xmx{args.heap}', '-M:bench', '--sizes', str(size),
                       '--backends', backend, '--operations', operation, '--samples', str(args.samples),
                       '--warmup', str(args.warmup), '--batch-size', str(args.batch_size), '--history', str(args.history),
                       '--output', str(case_dir)]
                print(f'Running {name} (case budget {args.timeout:g}s)', flush=True)
                began = time.monotonic()
                with log_path.open('w') as log:
                    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
                    try:
                        code = proc.wait(timeout=args.timeout)
                        status = 'complete' if code == 0 else 'failed'
                    except subprocess.TimeoutExpired:
                        status = 'timeout'
                        # Signal the benchmark's process group; no unrelated JVMs are touched.
                        os.killpg(proc.pid, signal.SIGTERM)
                        try:
                            proc.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            os.killpg(proc.pid, signal.SIGKILL)
                            proc.wait()
                        code = proc.returncode
                    except BaseException:
                        os.killpg(proc.pid, signal.SIGKILL)
                        proc.wait()
                        scratch.cleanup()
                        raise
                scratch.cleanup()
                rows = []
                if (case_dir / 'results.csv').exists():
                    with (case_dir / 'results.csv').open() as file:
                        rows = list(csv.DictReader(file))
                measured = next((r for r in rows if r['operation'] == operation), None)
                if status == 'complete' and measured is None:
                    status = 'failed'
                lines = log_path.read_text().splitlines()
                phase = next((line for line in reversed(lines) if line.startswith(('Populating ', 'Measuring '))), 'JVM startup')
                case = {'case': name, 'backend': backend, 'concepts': size, 'operation': operation,
                        'status': status, 'returncode': code, 'wall_seconds': time.monotonic() - began,
                        'last_phase': phase, 'command': cmd, 'measurement': measured,
                        'setup': next((r for r in rows if r['operation'] == 'populate'), None)}
                cases.append(case)
                manifest = {'started_unix': start, 'config': {**vars(args), 'output': str(output)}, 'cases': cases}
                (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
                print(f'  {status}: {case["wall_seconds"]:.1f}s; {phase}', flush=True)
    report = ['# Ontology benchmark campaign', '',
              'Sequential cases, each in a fresh JVM. Timed operations follow the configured warmup; startup and fixture construction are excluded from operation latency.', '',
              '**Timeouts are whole-case budget exhaustion, not measured operation latencies.** Last phase identifies where the case stopped; successful setup time is reported separately.', '',
              '| Backend | Concepts | Operation | Status | Setup ms | Median ms | Min–max ms | Allocated MiB/op |',
              '| --- | ---: | --- | --- | ---: | ---: | ---: | ---: |']
    for c in cases:
        m = c['measurement'] or {}
        def number(field):
            return f'{float(m[field]):.2f}' if m.get(field) else '—'
        allocated = f'{float(m["median-allocated-bytes"])/1048576:.2f}' if m.get('median-allocated-bytes') else '—'
        setup = f'{float(c["setup"]["elapsed-ms"]):.2f}' if c['setup'] else '—'
        report.append(f'| {c["backend"]} | {c["concepts"]} | {c["operation"]} | {c["status"]} | {setup} | {number("median-ms")} | {number("min-ms")}–{number("max-ms")} | {allocated} |')
    (output / 'report.md').write_text('\n'.join(report) + '\n')
    print(f'Report: {output / "report.md"}')
    return 1 if any(c['status'] != 'complete' for c in cases) else 0

if __name__ == '__main__':
    raise SystemExit(main())
