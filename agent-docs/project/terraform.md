# Terraform

Read when a feature adds/changes an external dependency (S3/Spaces bucket, third-party credential, new env var, new service) and before considering any feature's service slice complete.

## Layout

- `terraform/dev/<service>/` — one environment config per deployed component (`gateway`, `gateway-flyway`): `locals.tf` (raw + resolved resource names), `variables.tf` (`TF_VAR_*`-fed inputs, secrets marked `sensitive = true`), `providers.tf` (DigitalOcean provider, S3-compatible remote state backend), `app.tf` (data sources for existing infra, resources this config owns, the module call).
- Naming: build every resolved value, including hostnames, from `local.environment`/`local.region` — never hardcode `dev`. E.g. `app_domain = "${local.environment}-api.${local.dns_zone}"`.
- `terraform/dev/dns/` — owns the `mesazon.space` zone only, in its own state so no destroy workflow can reach it. No `digitalocean_record` here; App Platform creates records for domains an app declares (see [Custom domains](#custom-domains)).
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
- `pipeline-gateway-destroy.yml` (+ `job-tf-destroy.yml`): `terraform destroy` against `terraform/dev/gateway`, Mon/Wed/Fri 02:00 CET (`cron: '0 1 * * 1,3,5'`). 3×/week, not daily, because each destroy costs a fresh TLS certificate on redeploy, and Let's Encrypt caps that at 5 per exact name set per 7 days. Before destroying, `release-domains: true` re-applies with `custom_domain_enabled=false` — removing the domain while the app still exists avoids DigitalOcean's 24h hold on a deleted app's hostname. Destroy-only, no auto-recreate: push to `main` or dispatch `pipeline-gateway-cd.yml` to bring it back (expect a few minutes for cert provisioning).
- `pipeline-dns-cd.yml`: dispatch-only plan+apply of `terraform/dev/dns`. Create-once; rarely runs.

Always run `terraform fmt -recursive` from the repo root after editing any `.tf` file, before committing.

## Custom domains

Zone (`terraform/dev/dns`) and hostname (each app's spec) are separate states; neither owns a `digitalocean_record`.

1. `terraform/dev/dns` creates the zone — without it DO's nameservers answer REFUSED for `mesazon.space`.
2. The app declares the hostname via the `app-service` module's `domains` variable with `zone = "mesazon.space"`. That `zone` field makes App Platform create the record *and* the certificate. Never add a matching `digitalocean_record`.
3. `type = "PRIMARY"` marks the app's one canonical hostname.

No way to pin a pre-issued certificate — the `domain` block has no `certificate` field, so cert lifecycle = app lifecycle. That's why destroys are capped at 3×/week and release the domain first.

Never add CAA records to the zone unless they list both `letsencrypt.org` and `pki.goog`, or cert issuance fails.

## Feature-completion check

Before marking a feature's service slice (PR 5, see [Service](../features/flow/05-service.md)) complete, check whether it introduced anything terraform must provision or wire: a new bucket/external credential, a new required env var, a new service/module. If so, terraform must be updated in the same PR — verify by reading the PR's `job-tf-plan` comment and confirming it plans the expected new/changed resources, not "No changes."
