# Releasing to Maven Central

This project publishes to **Maven Central** through the **Sonatype Central Portal**
(`central.sonatype.com`), driven by the [`release.yml`](.github/workflows/release.yml)
GitHub Actions workflow.

- **Group id:** `io.github.a-simeshin` (verified via the `a-simeshin` GitHub account)
- **Published modules:** `spring-ai-mcp-inspector-parent`, `-core`, `-ui`,
  `-starter-webmvc`, `-starter-webflux`
- **Not published:** `spring-ai-mcp-inspector-demo` (`maven.deploy.skip=true`)

Day-to-day `mvn verify` does **not** require any of this — the signing/publish plugins
live in the `release` profile only.

---

## One-time setup

### 1. Central Portal account + namespace verification

1. Sign in at https://central.sonatype.com with GitHub.
2. **Add namespace** → `io.github.a-simeshin`.
3. The Portal shows a verification key and asks you to create a **public GitHub repo**
   named after that key under `github.com/a-simeshin`. Create it, then click *Verify*.
   Once verified the namespace is permanent (the temp repo can be deleted).
4. **Account → Generate User Token.** Save the two values — they become the
   `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` secrets (a token *name* and *secret*, not your
   login).

### 2. GPG signing key

Central requires every artifact to be signed and the public key to be on a keyserver.

```bash
# Generate a key (Ed25519 or RSA 4096). Use a real name + the email tied to the project.
gpg --full-generate-key

# List it and copy the long key id (the 40-hex fingerprint, or the 16-hex long id).
gpg --list-secret-keys --keyid-format LONG

# Publish the PUBLIC key so Central can verify signatures.
gpg --keyserver keyserver.ubuntu.com   --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org       --send-keys <KEY_ID>

# Export the PRIVATE key (ASCII-armored) for the GitHub secret. Keep this file secret.
gpg --armor --export-secret-keys <KEY_ID> > private-key.asc
```

> If you used `keys.openpgp.org`, also verify the key's email via the confirmation mail it
> sends — otherwise that server won't serve the key to Central.

### 3. GitHub repository secrets

`Settings → Secrets and variables → Actions → New repository secret`:

| Secret | Value |
|--------|-------|
| `CENTRAL_USERNAME` | Central Portal user-token **name** |
| `CENTRAL_PASSWORD` | Central Portal user-token **secret** |
| `GPG_PRIVATE_KEY`  | full contents of `private-key.asc` (the armored block) |
| `GPG_PASSPHRASE`   | the key's passphrase |

After adding the secret, delete the local `private-key.asc`.

---

## Cutting a release

The POMs carry a `-SNAPSHOT` development version; the workflow stamps the real version
from the tag, so there is nothing to hand-edit.

```bash
# 1. Make sure main is green and at the commit you want to release.
git checkout main && git pull

# 2. Tag it. The tag (minus the leading v) becomes the Maven version.
git tag v1.0.0
git push origin v1.0.0
```

Pushing the tag triggers `release.yml`, which:

1. sets every module to `1.0.0`,
2. builds + generates sources/javadoc jars,
3. GPG-signs all artifacts,
4. publishes to the Central Portal with `autoPublish=true` and waits until it is live.

You can also run it manually: **Actions → Release to Maven Central → Run workflow**, and
type the version.

### Verifying

- Watch the run under the **Actions** tab.
- The deployment also appears under **Deployments** in the Central Portal.
- Artifacts land at `https://repo1.maven.org/maven2/io/github/a-simeshin/` (search index
  on `central.sonatype.com` can lag a few hours).

---

## Local dry run (optional)

To rehearse the release build locally without publishing — produces signed jars in each
`target/` but does **not** deploy:

```bash
# Needs a local GPG key; omit -Dgpg.skip to actually sign.
./mvnw -Prelease -DskipTests -Dgpg.skip=true install
```

To exercise signing end-to-end locally, pass the passphrase and drop `-Dgpg.skip`:

```bash
MAVEN_GPG_PASSPHRASE=… ./mvnw -Prelease -DskipTests install
```
