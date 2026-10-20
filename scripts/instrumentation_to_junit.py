#!/usr/bin/env python3
"""Convert `am instrument -r` raw output into JUnit XML and a pass/fail exit code.

Usage: instrumentation_to_junit.py instrumentation.txt junit.xml
Exit 0 only when at least one test ran and none failed, errored or crashed the instrumentation. Skipped tests
(assumption failures) are reported as skipped and never counted as passing.
"""
import re
import sys
from xml.sax.saxutils import escape, quoteattr


def parse(text):
    """Returns ([{name, outcome, stack}], crash line or None). Continuation lines belong to the previous status key."""
    tests, current, crashed, status, last_key = [], None, None, {}, None
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            status[key], last_key = value, key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1].strip())
            name = f"{status.get('class', '?')}#{status.get('test', '?')}"
            if code == 1:
                current = name
            else:
                outcome = {0: "passed", -1: "error", -2: "failure", -3: "skipped", -4: "skipped"}.get(code, "error")
                tests.append({"name": name, "outcome": outcome, "stack": status.get("stack", "")})
                current = None
            status, last_key = {}, None
        elif line.startswith(("INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_RESULT: longMsg=", "INSTRUMENTATION_FAILED")):
            crashed = line[:300]
        elif line.startswith("INSTRUMENTATION_"):
            last_key = None
        elif last_key:
            status[last_key] += "\n" + line
    if current is not None:
        tests.append({"name": current, "outcome": "error", "stack": crashed or "The instrumentation stopped during this test"})
    return tests, crashed


def main(argv):
    text = open(argv[0], encoding="utf-8", errors="replace").read()
    tests, crashed = parse(text)
    failures = [t for t in tests if t["outcome"] in ("failure", "error")]
    skipped = [t for t in tests if t["outcome"] == "skipped"]
    cases = []
    for test in tests:
        classname, _, name = test["name"].partition("#")
        body = ""
        if test["outcome"] in ("failure", "error"):
            body = f"<failure message={quoteattr(test['outcome'])}>{escape(test.get('stack', '')[:20000])}</failure>"
        elif test["outcome"] == "skipped":
            body = "<skipped/>"
        cases.append(f"  <testcase classname={quoteattr(classname)} name={quoteattr(name)}>{body}</testcase>")
    xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="release-e2e" tests="{len(tests)}" '
           f'failures="{len(failures)}" skipped="{len(skipped)}">\n' + "\n".join(cases) + "\n</testsuite>\n")
    open(argv[1], "w").write(xml)
    passed = len(tests) - len(failures) - len(skipped)
    print(f"Instrumentation: {passed} passed, {len(failures)} failed, {len(skipped)} skipped"
          + (f"; crashed: {crashed}" if crashed else ""))
    return 0 if tests and not failures and not crashed and passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
