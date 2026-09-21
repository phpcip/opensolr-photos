# Contributing to Opensolr Photos

Bug reports, fixes, features and documentation are all welcome.

**Become a contributor:** to join Opensolr Photos, or any other Opensolr open source project, write to
[support@opensolr.com](mailto:support@opensolr.com?subject=Contributor%20request%3A%20Opensolr%20Photos).
Tell us who you are, which project, and what you would like to work on. Issues and pull requests on GitHub
are open to everyone without asking first.

Start with [the project structure](docs/project-structure.md): it explains every folder and where each kind
of change goes.

## How a change gets in

1. **Open an issue** describing the bug or the idea, so nobody does the same work twice.
2. **Fork the repository and create a branch** for that one change.
3. **Make the change** following the conventions below, and build it (`./gradlew assembleDebug`) with no new
   warnings.
4. **Try it on a real phone**: sign in, sync, search, and whatever your change touches.
5. **Update the documentation** in `docs/` in the same pull request when behaviour changes.
6. **Open a pull request** saying what changed, why, and how you checked it.

## Conventions

- Kotlin official style, 4-space indentation.
- Documentation comments only: every class and function has a KDoc comment saying what it does and why; no
  comments inside function bodies. If a line needs explaining, give it a function with a good name.
- Keep the layers: screens never talk to the network, `net/` never touches the UI, and a photo's id is only
  ever computed by `MediaScanner.photoId()`.
- No new dependency without a reason in the pull request, and never one that sends data anywhere.
- The design stays flat: 2 dp corners, one accent colour, hairlines instead of shadows, no text smaller than 14.

## Security rules every change keeps

- User text reaches Solr only as a bound parameter; filter values only through `{!term}` with a bound value.
- Secrets are stored only through `SecureStore`, never logged, never put in a URL.
- HTTPS only; no redirects followed on credentialed calls.
- No analytics, advertising, tracking or crash-reporting code.
- The app only reads photos; it never writes, moves or deletes them.
- Vulnerabilities are reported privately as described in [SECURITY.md](SECURITY.md).
