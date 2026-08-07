# Terraform

Read when a feature adds/changes an external dependency (S3/Spaces bucket, third-party credential, new env var, new service) and before considering any feature's service slice complete.

## Layout

- `terraform/dev/<service>/` — one environment config per deployed component (`gateway`, `gateway-flyway`): `locals.tf` (raw + resolved resource names), `variables.tf` (`TF_VAR_*`-fed inputs, secrets marked `sensitive = true`), `providers.tf` (DigitalOcean provider, S3-compatible remote state backend), `app.tf` (data sources for existing infra, resources this config owns, the module call).
- `terraform/modules/app-service` (long-running app, e.g. `gateway_core_app`) and `terraform/modules/app-job` (one-shot job, e.g. Flyway) — reusable DigitalOcean App Platform wrappers. Both take `env_vars`/`secret_vars` maps and emit them as `digitalocean_app` `env` blocks (`type = "GENERAL"` vs `"SECRET"`).

## Adding a new external dependency (e.g. a bucket)

Mirror the existing `organization_logos_bucket` / `organization_media_bucket` pair in `terraform/dev/gateway/{locals,app}.tf`. All three steps are required — the first two alone provision the infra but never connect it to the app:

1. `locals.tf`: add a `<name>_raw` and resolved `<name>` (`"${raw}-${region}-${environment}"`).
2. `app.tf`: add a `data "digitalocean_spaces_bucket" "<name>"` (the bucket itself is provisioned out-of-band, not by this config) and a `resource "digitalocean_spaces_key" "<name>"` granting the app's app-service module access.
3. `app.tf`, inside the `module "gateway_core_app"` block: wire the data/resource attributes into `env_vars` (`..._URI`, `..._REGION`, `..._BUCKET`, and `..._USE_MOCK = "false"`) and `secret_vars` (`..._ACCESS_KEY_ID`, `..._SECRET_ACCESS_KEY`).

Step 3's env var names must match **exactly** what `application.conf` reads via `${?ENV_VAR}` — check the Scala config class/`application.conf` block for the feature (e.g. `s3-client-organization-media` → `S3_CLIENT_ORGANIZATION_MEDIA_*`). A missing or misnamed var doesn't fail the deploy: the app silently falls back to its `application.conf` default (often `use-mock = true`), so the feature ships to `dev` quietly running against a mock instead of the real dependency.

## CI/CD

- `pipeline-tf-ci.yml`: any PR/push touching `terraform/**` or `.github/**` runs `job-tf-fmt` (`terraform fmt -check -recursive`) — fails the build on unformatted files.
- `pipeline-gateway-ci.yml`: every PR/push runs `job-tf-plan` against `terraform/dev/gateway` and posts the plan as a PR comment — this is where an incomplete wiring becomes visible (no planned change for the new env vars). On push to `main`, `job-tf-apply` runs after, applying to the real `dev` DigitalOcean environment automatically — no manual apply step.
- `pipeline-gateway-destroy-cron.yml` (+ `job-tf-destroy.yml`): scheduled `terraform destroy` against `terraform/dev/gateway`, at 01:00 and 13:00 CET (`cron: '0 0 * * *'` / `'0 12 * * *'`, fixed UTC+1 — drifts 1h during CEST, GitHub Actions cron has no DST support) to stop paying for the dev app outside its ~13:00–01:00 usage window. Destroy-only, no scheduled recreate — the state's two resources (`digitalocean_app.app_service`, `digitalocean_spaces_key.organization_media_bucket`) are stateless and cheap to rebuild, but the Postgres cluster they depend on is only read via a `data` source here and is left untouched. Running it against an already-destroyed state is a safe no-op. Bring the app back up manually: push to `main`, or dispatch `pipeline-gateway-cd.yml`.

Always run `terraform fmt -recursive` from the repo root after editing any `.tf` file, before committing.

## Feature-completion check

Before marking a feature's service slice (PR 5, see [Service](../features/flow/05-service.md)) complete, check whether it introduced anything terraform must provision or wire: a new bucket/external credential, a new required env var, a new service/module. If so, terraform must be updated in the same PR — verify by reading the PR's `job-tf-plan` comment and confirming it plans the expected new/changed resources, not "No changes."
