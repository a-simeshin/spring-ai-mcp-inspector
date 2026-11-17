#!/usr/bin/env python3
"""CI gate: forbid fragile UI selectors in the demo browser E2E suite (issue #378).

The demo suite must drive the vendored inspector UI through data-testid anchors
only. Selectors by Tailwind classes or Radix internals break on every
re-vendor; this script fails the build when one re-enters the suite.

Scanned: the demo E2E/stress/smoke sources that open the inspector UI.

Banned selector shapes (string literals at Selenide/driver call-sites):
  - CSS class selectors (`.cursor-pointer`, `ol.fixed`, `div.border-l-2`)
  - Radix internals (`[role=...]`, `[data-state=...]`, `[data-radix...]`,
    `[data-orientation...]`, `[cmdk...]`)
  - XPath (`$x(`, `By.xpath(`)

Banned Condition shapes (class-based matching on located elements, review of
PR #389 finding 1):
  - `attributeMatching("class", ...)`: matching an element by its class
    attribute re-couples the test to styling even when the locator itself is
    a testid, so any class-attribute matcher fails the gate. Matchers on
    content attributes such as `attributeMatching("title", ...)` stay legal.

Explicit allowlist for state assertions (issue #378 gate policy):
  - `cssClass("<value>")` asserts a class on an element already located
    through a data-testid anchor; it is a state assertion, not a locator.
    Only whitelisted state values pass:

      bg-muted: selected chat row highlight (InspectorUiIT 1617/1622/1623)
      dark:     html theme class, theme contract (InspectorUiIT 2242/2246)

    Any other value fails the gate. Adding a value to the allowlist is a
    conscious, reviewable decision; the preferred long-term shape is an
    assertion on `aria-selected`/`data-state` instead (tracked as follow-up
    work on issue #378).

Run `--self-test` to feed deliberate violations to the scanner and verify it
still catches every banned shape (wired as its own CI step).
"""

from __future__ import annotations

import re
import shutil
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

SCANNED = [
    "spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/e2e/InspectorUiIT.java",
    "spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/stress/UiBigDataIT.java",
    "spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/e2e/ConnectFailureIT.java",
    "spring-ai-mcp-inspector-demo-webmvc/src/test/java/io/inspector/mcp/demo/e2e/BootstrapRegressionE2ETest.java",
    "spring-ai-mcp-inspector-demo-app/src/test/java/io/inspector/mcp/demo/ui/InspectorUiSmokeIT.java",
]

SELECTOR_STRING = re.compile(r'"((?:[^"\\]|\\.)*)"')
# Selenide/By call-site openers; the selector literal follows on the same line
# or as the first token of the next line (multiline argument).
CALL_SITE = re.compile(
    r'(?:\$\$?\(|By\.cssSelector\(|\.find\(|\.closest\()')
OPEN_AT_EOL = re.compile(
    r'(?:\$\$?\(|By\.cssSelector\(|\.find\(|\.closest\()\s*$')
# JS querySelector arguments (single-quoted) inside executeJavaScript strings
JS_SELECTOR = re.compile(r"querySelector(?:All)?\(\s*'((?:[^'\\]|\\.)*)'")
XPATH_CODE_RE = re.compile(r'\$x\(|By\.xpath\(')
RADIX_RE = re.compile(r'\[role=|\[data-state|\[data-radix|\[data-orientation|\[cmdk')
# a class token anywhere in the selector: tag.class, .class, a.b.c, [..] .class
CLASS_TOKEN_RE = re.compile(r'(?:^|[\s>+~\[])'
                            r'(?:[a-zA-Z][\w-]*)?'      # optional tag
                            r'(?:\.[a-zA-Z][\w-]*)+')   # one or more .class tokens

# Condition-based class matching: the first argument is the attribute name,
# so this catches attributeMatching("class", <any regex>) at any call-site.
CLASS_ATTRIBUTE_MATCHER_RE = re.compile(r'attributeMatching\(\s*["\']class["\']')

# cssClass("<value>") / Condition.cssClass("<value>"): state assertion on an
# already-located element. Allowed only for the whitelisted values above.
CSS_CLASS_ASSERT_RE = re.compile(r'cssClass\(\s*["\']([A-Za-z0-9_-]+)["\']')

ALLOWED_CSS_CLASS_ASSERTS = {
    "bg-muted",
    "dark",
}


def classify(selector: str):
    if not re.match(r'^(//|\(|[a-zA-Z]+[ .\[#:]|\.|#|\[)', selector):
        return None  # not a CSS/XPath selector shape
    # an XPath literal starts with // or carries an axis step; a bare "(text)"
    # literal is a byText/text() argument, not a selector
    if selector.startswith("//") or "::" in selector:
        return "xpath"
    if RADIX_RE.search(selector):
        return "radix"
    if CLASS_TOKEN_RE.search(selector):
        return "css-class"
    return None


def scan(path: Path, rel: str, violations: list) -> None:
    lines = path.read_text().splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith(("//", "*", "/*")):
            continue
        if XPATH_CODE_RE.search(line):
            violations.append(f"{rel}:{i + 1}: xpath selector: {stripped[:120]}")
            continue
        if CLASS_ATTRIBUTE_MATCHER_RE.search(line):
            violations.append(
                f"{rel}:{i + 1}: class-attribute matcher: {stripped[:120]}")
            continue
        for m in CSS_CLASS_ASSERT_RE.finditer(line):
            cls = m.group(1)
            if cls not in ALLOWED_CSS_CLASS_ASSERTS:
                violations.append(
                    f"{rel}:{i + 1}: css class state assertion not in the "
                    f'allowlist: cssClass("{cls}")')
        candidates = []
        # direct selector argument: the literal that immediately follows a
        # call-site opener (wrapped args such as byText("...") are not selectors)
        for m in CALL_SITE.finditer(line):
            tail = line[m.end():].lstrip()
            if tail.startswith('"'):
                sm = SELECTOR_STRING.match(tail)
                if sm:
                    candidates.append(sm.group(1))
        # JS selectors inside executeJavaScript strings (single-quoted)
        for m in JS_SELECTOR.finditer(line):
            candidates.append(m.group(1))
        # literal as continuation: line is a bare quoted argument of the previous
        # line, whose call-site opener is the last token
        if not candidates and i > 0 \
                and OPEN_AT_EOL.search(lines[i - 1]) \
                and re.match(r'^\s*"', line):
            sm = SELECTOR_STRING.search(line)
            if sm:
                candidates.append(sm.group(1))
        for sel in candidates:
            kind = classify(sel)
            if kind:
                violations.append(f"{rel}:{i + 1}: {kind} selector: {sel[:120]}")


# Each entry: one source line, and the violation prefix the scanner must
# produce for it, or None when the line must pass the gate. The banned shapes
# cover every message the gate can emit; the allowed lines include the two
# whitelisted state assertions and testid-driven calls.
SELF_TEST_LINES = [
    ('el.shouldHave(attributeMatching("class", ".*border-red-500.*"));',
     "class-attribute matcher"),
    ('el.shouldHave(Condition.attributeMatching("class", ".*border-red-500.*"));',
     "class-attribute matcher"),
    ('el.shouldHave(cssClass("text-sm"));',
     'css class state assertion not in the allowlist: cssClass("text-sm")'),
    ('el.shouldNotHave(Condition.cssClass("p-4"));',
     'css class state assertion not in the allowlist: cssClass("p-4")'),
    ('$(".cursor-pointer").click();', "css-class selector"),
    ('$$("ol.fixed li").shouldHave(size(3));', "css-class selector"),
    ('el.$("[data-state=checked]").click();', "radix selector"),
    ('By.xpath("//div[@id=\'x\']");', "xpath selector"),
    ('el.shouldHave(attributeMatching("title", ".*Spec default.*"));', None),
    ('el.shouldHave(cssClass("bg-muted"));', None),
    ('el.shouldNotHave(cssClass("dark"));', None),
    ('el.$("[data-testid=history-item]").click();', None),
    ('By.cssSelector("[data-testid=tools-row]");', None),
]


def self_test() -> int:
    """Feed deliberate violations through scan() and check every verdict."""
    tmp = Path(tempfile.mkdtemp(prefix="e2e-selector-selftest-"))
    try:
        fake = tmp / "FakeUiIT.java"
        fake.write_text("\n".join(line for line, _ in SELF_TEST_LINES) + "\n")
        violations: list[str] = []
        scan(fake, "FakeUiIT.java", violations)
        flagged: dict[int, str] = {}
        for v in violations:
            flagged[int(v.split(":")[1]) - 1] = v
        ok = True
        expected_violations = 0
        for idx, (line, expected) in enumerate(SELF_TEST_LINES):
            got = flagged.get(idx)
            if expected is None:
                if got is not None:
                    print(f"self-test FAIL: allowed line {idx + 1} flagged: {got}")
                    ok = False
            else:
                expected_violations += 1
                if got is None:
                    print(f"self-test FAIL: violation missed on line {idx + 1}: {line[:80]}")
                    ok = False
                elif not got.startswith(f"FakeUiIT.java:{idx + 1}: {expected}"):
                    print(f"self-test FAIL: wrong verdict on line {idx + 1}: "
                          f"expected [{expected}], got [{got}]")
                    ok = False
        if ok and len(violations) != expected_violations:
            print(f"self-test FAIL: {len(violations)} violations reported, "
                  f"expected {expected_violations}: {violations}")
            ok = False
        if ok:
            print(f"self-test ok: {expected_violations} deliberate violations "
                  "flagged, allowed shapes pass")
            return 0
        print("self-test FAILED: the gate must flag every banned shape above")
        return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main() -> int:
    if "--self-test" in sys.argv[1:]:
        return self_test()
    violations = []
    for rel in SCANNED:
        path = REPO_ROOT / rel
        if not path.exists():
            print(f"gate: missing scanned file {rel}")
            return 2
        scan(path, rel, violations)
    if violations:
        print(f"gate FAILED: {len(violations)} fragile selector(s) in the demo E2E suite:")
        for v in violations:
            print(f"  {v}")
        print("drive the UI through data-testid anchors only (issue #378)")
        return 1
    print("gate ok: no Tailwind-class, Radix-internal or class-attribute "
          "matching in the demo E2E suite")
    return 0


if __name__ == "__main__":
    sys.exit(main())
