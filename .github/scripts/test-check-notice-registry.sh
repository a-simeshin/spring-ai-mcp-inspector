#!/usr/bin/env bash
# Self-test for check-notice-registry.sh.
#
# Creates a temporary git repo with the vendored-client structure, runs the
# gate script against several scenarios, and exits non-zero if any expected
# pass/fail is violated.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GATE="$SCRIPT_DIR/check-notice-registry.sh"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# ----- helpers ---------------------------------------------------------------
pass()  { echo "  PASS: $1"; }
fail()  { echo "  FAIL: $1" >&2; failures=$((failures+1)); }
failures=0

# Create a git repo with the project structure and an initial commit.
setup_repo() {
  cd "$TMPDIR"

  git init -q
  git config user.email test@example.com
  git config user.name test

  mkdir -p spring-ai-mcp-inspector-ui/upstream-client/src/components
  mkdir -p spring-ai-mcp-inspector-ui/upstream-client/NOTICE.d

  # NOTICE.txt header
  cat > spring-ai-mcp-inspector-ui/upstream-client/NOTICE.txt <<'EOF'
This project contains source code from modelcontextprotocol/inspector.
See NOTICE.d/ for the list of local patches.
EOF

  # A vendored config file
  cat > spring-ai-mcp-inspector-ui/upstream-client/package.json <<'EOF'
{
  "name": "upstream-client",
  "version": "1.0.0"
}
EOF

  # Generated files excluded from notice registry
  echo '{}' > spring-ai-mcp-inspector-ui/upstream-client/package-lock.json
  echo 'MIT License' > spring-ai-mcp-inspector-ui/upstream-client/LICENSE
  echo '# README' > spring-ai-mcp-inspector-ui/upstream-client/README.md

  # A source file with PATCH marker (already patched)
  cat > spring-ai-mcp-inspector-ui/upstream-client/src/components/App.tsx <<'EOF'
// [spring-ai-mcp-inspector PATCH] Test fixture
export const App = () => <div>hello</div>;
EOF

  # A NOTICE.d entry for the existing patch
  cat > spring-ai-mcp-inspector-ui/upstream-client/NOTICE.d/app-patch.txt <<'EOF'
File: src/components/App.tsx
What: Test fixture
EOF

  git add -A
  git commit -q -m "initial commit"
  base_rev="$(git rev-parse HEAD)"
  echo "$base_rev"
}

# ----- Scenario: OK - no changes to vendored code ---------------------------
test_no_changes() {
  cd "$TMPDIR"
  git checkout -q -b test-no-changes "$base_rev"

  # Touch a file outside the vendored area
  echo "readme" > README.md
  git add README.md
  git commit -q -m "add readme"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "no changes to vendored code: exit 0"
  else
    fail "no changes to vendored code: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: FAIL - src change without NOTICE.d --------------------------
test_src_no_notice_d() {
  cd "$TMPDIR"
  git checkout -q -b test-src-no-notice-d "$base_rev"

  # Change a src file without adding NOTICE.d entry
  echo "// extra" >> spring-ai-mcp-inspector-ui/upstream-client/src/components/App.tsx
  git add -A
  git commit -q -m "change src without notice"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    fail "src change without NOTICE.d: expected non-zero exit"
    echo "    $output"
  else
    pass "src change without NOTICE.d: exit non-zero"
  fi
}

# ----- Scenario: FAIL - vendored file outside src without NOTICE.d ------------
test_vendored_no_notice_d() {
  cd "$TMPDIR"
  git checkout -q -b test-vendored-no-notice-d "$base_rev"

  # Change package.json without adding NOTICE.d entry
  echo "// updated" >> spring-ai-mcp-inspector-ui/upstream-client/package.json
  git add -A
  git commit -q -m "change package.json without notice"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    fail "package.json change without NOTICE.d: expected non-zero exit"
    echo "    $output"
  else
    pass "package.json change without NOTICE.d: exit non-zero"
  fi
}

# ----- Scenario: FAIL - src change with NOTICE.d but without PATCH marker -----
test_src_no_patch_marker() {
  cd "$TMPDIR"
  git checkout -q -b test-src-no-patch-marker "$base_rev"

  # Create a new src file WITHOUT PATCH marker, but WITH a NOTICE.d entry
  cat > spring-ai-mcp-inspector-ui/upstream-client/src/components/NewFile.tsx <<'EOF'
export const NewFile = () => <div>new</div>;
EOF
  cat > spring-ai-mcp-inspector-ui/upstream-client/NOTICE.d/new-file.txt <<'EOF'
File: src/components/NewFile.tsx
What: New file without marker
EOF
  git add -A
  git commit -q -m "add new src file without PATCH marker, with NOTICE.d"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    fail "src change without PATCH marker (with NOTICE.d): expected non-zero exit"
    echo "    $output"
  else
    pass "src change without PATCH marker (with NOTICE.d): exit non-zero"
  fi
}

# ----- Scenario: FAIL - numbered entry in NOTICE.txt -------------------------
test_notice_txt_numbered() {
  cd "$TMPDIR"
  git checkout -q -b test-notice-txt-numbered "$base_rev"

  # Add a numbered entry to NOTICE.txt
  sed -i 's|See NOTICE.d/|1. First patch\nSee NOTICE.d/|' spring-ai-mcp-inspector-ui/upstream-client/NOTICE.txt
  git add -A
  git commit -q -m "add numbered entry to NOTICE.txt"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    fail "numbered entry in NOTICE.txt: expected non-zero exit"
    echo "    $output"
  else
    pass "numbered entry in NOTICE.txt: exit non-zero"
  fi
}

# ----- Scenario: OK - src change with NOTICE.d AND PATCH marker ---------------
test_src_with_marker_and_notice() {
  cd "$TMPDIR"
  git checkout -q -b test-src-with-marker "$base_rev"

  # Modify an already-patched file (marker already present) and add NOTICE.d entry
  echo "// extra" >> spring-ai-mcp-inspector-ui/upstream-client/src/components/App.tsx
  cat > spring-ai-mcp-inspector-ui/upstream-client/NOTICE.d/app-patch-2.txt <<'EOF'
File: src/components/App.tsx
What: Second patch
EOF
  git add -A
  git commit -q -m "change src with marker and notice entry"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "src change with marker and NOTICE.d: exit 0"
  else
    fail "src change with marker and NOTICE.d: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: OK - NOTICE.d change without src change (e.g. adding doc) ----
test_notice_d_only() {
  cd "$TMPDIR"
  git checkout -q -b test-notice-d-only "$base_rev"

  # Add only a NOTICE.d entry, no vendored code change
  cat > spring-ai-mcp-inspector-ui/upstream-client/NOTICE.d/doc-only.txt <<'EOF'
File: (documentation only)
What: No code change, just registry
EOF
  git add -A
  git commit -q -m "add notice entry only"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "NOTICE.d only (no vendored code change): exit 0"
  else
    fail "NOTICE.d only: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: OK - deleted vendored file -----------------------------------
test_deleted_vendored_file() {
  cd "$TMPDIR"
  git checkout -q -b test-deleted-vendored "$base_rev"

  # Delete a vendored file (should not require registration)
  git rm spring-ai-mcp-inspector-ui/upstream-client/package.json >/dev/null 2>&1
  git commit -q -m "delete package.json"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "deleted vendored file: exit 0"
  else
    fail "deleted vendored file: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: OK - package-lock.json change without NOTICE.d (generated file) --
test_package_lock_change() {
  cd "$TMPDIR"
  git checkout -q -b test-package-lock "$base_rev"

  # Change package-lock.json (generated, should be excluded)
  echo '{"lockfileVersion": 2}' > spring-ai-mcp-inspector-ui/upstream-client/package-lock.json
  git add -A
  git commit -q -m "update package-lock.json"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "package-lock.json change without NOTICE.d: exit 0"
  else
    fail "package-lock.json change without NOTICE.d: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: OK - LICENSE change without NOTICE.d (copied file) -----------
test_license_change() {
  cd "$TMPDIR"
  git checkout -q -b test-license "$base_rev"

  # Change LICENSE (copied from upstream, should be excluded)
  echo 'Apache 2.0' > spring-ai-mcp-inspector-ui/upstream-client/LICENSE
  git add -A
  git commit -q -m "update LICENSE"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "LICENSE change without NOTICE.d: exit 0"
  else
    fail "LICENSE change without NOTICE.d: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- Scenario: OK - README.md change without NOTICE.d (copied file) ---------
test_readme_change() {
  cd "$TMPDIR"
  git checkout -q -b test-readme "$base_rev"

  # Change README.md (copied from upstream, should be excluded)
  echo '# Upstream Client' > spring-ai-mcp-inspector-ui/upstream-client/README.md
  git add -A
  git commit -q -m "update README.md"

  if output=$("$GATE" "origin/HEAD" 2>&1); then
    pass "README.md change without NOTICE.d: exit 0"
  else
    fail "README.md change without NOTICE.d: expected exit 0, got $?"
    echo "    $output"
  fi
}

# ----- main ------------------------------------------------------------------
echo "=== check-notice-registry.sh self-test ==="
base_rev="$(setup_repo)"
echo "Base commit: ${base_rev:0:7}"

# Need a remote-tracking ref for the base; fake it
git -C "$TMPDIR" branch -f origin/HEAD "$base_rev" >/dev/null 2>&1

test_no_changes
test_src_no_notice_d
test_vendored_no_notice_d
test_src_no_patch_marker
test_notice_txt_numbered
test_src_with_marker_and_notice
test_notice_d_only
test_deleted_vendored_file
test_package_lock_change
test_license_change
test_readme_change

echo "---"
if [[ $failures -eq 0 ]]; then
  echo "ALL TESTS PASSED"
else
  echo "$failures TEST(S) FAILED"
fi
exit $failures