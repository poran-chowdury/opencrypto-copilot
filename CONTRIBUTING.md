# Contributing

## Workflow

1. Branch from `main`: `git switch -c feat/jwt-auth` (prefixes: `feat/`, `fix/`, `docs/`, `chore/`).
2. Commit in small steps with messages that say what and why: `fix(wallet): release hold when swap times out`.
3. Push and open a pull request. CI must be green before merging; `main` is protected.
4. Squash-merge. The push to `main` runs CI again and publishes images to ghcr.io (see
   [docs/engineering/12-github-ci-cd.md](docs/engineering/12-github-ci-cd.md)).
5. Releases: `git tag v1.2.0 && git push origin v1.2.0` publishes versioned images and a GitHub Release.

## Rules

- Add tests for business rules and failure paths.
- Never commit credentials or user data. Secrets go in `.env` (git-ignored) or GitHub Actions secrets.
- Keep demo values clearly labeled.
- Run `mvn clean verify` before opening a pull request.
- Every engineering change gets a learning note in `docs/engineering/`.
