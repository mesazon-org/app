# Terraform

Read when a feature adds/changes an external dependency (S3/Spaces bucket, third-party credential, new env var, new service) and before considering any feature's service slice complete.

## Layout

- `terraform/dev/<service>/` — one environment config per deployed component (`gateway`, `gateway-flyway`): `locals.tf` (raw + resolved resource names), `variables.tf` (`TF_VAR_*`-fed inputs, secrets marked `sensitive = true`), `providers.tf` (DigitalOcean provider, S3-compatible remote state backend), `app.tf` (data sources for existing infra, resources this config owns, the module call).
- Naming: every resolved value in `locals.tf` is built from `local.environment` and `local.region` — never hardcode `dev`. This covers the public hostname too (`app_domain = "${local.environment}-api.${local.dns_zone}"`), so standing up `terraform/prod/gateway` produces `prod-api.mesazon.space` by changing `local.environment` alone. A hardcoded environment in one local is invisible until the config is copied and two environments silently collide on the same resource.
- `terraform/dev/dns/` — owns the `mesazon.space` DNS zone (`digitalocean_domain`) and nothing else. Separate state on purpose: destroying it takes every hostname under the domain offline, so no scheduled or destroy workflow may reach it. It holds no `digitalocean_record` — records are created by App Platform for the domains each app declares (see [Custom domains](#custom-domains)).
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
- `pipeline-gateway-destroy.yml` (+ `job-tf-destroy.yml`): `terraform destroy` against `terraform/dev/gateway`, scheduled 01:00 CET Wednesday and Sunday (`cron: '0 0 * * 3'` / `'0 0 * * 0'`, fixed UTC+1 — drifts 1h during CEST, GitHub Actions cron has no DST support). Twice a week rather than twice a day because each destroy costs a fresh TLS certificate on the next deploy, capped by Let's Encrypt at 5 per exact name set per 7 days (global, no overrides). Two gates:
  - `check-activity` skips the scheduled destroy when `pipeline-gateway-ci.yml` or `pipeline-gateway-cd.yml` last succeeded inside `INACTIVITY_HOURS` (48h) — an app being deployed against is an app in use. `workflow_dispatch` bypasses the check and always destroys.
  - `release-domains: true` makes the job re-apply with `custom_domain_enabled=false` before deleting, pinning the already-deployed `image_tag` from the state's output so the release apply changes nothing else. DigitalOcean holds a deleted app's custom domain for up to 24h; removing it from the spec first frees the hostname immediately.

  Destroy-only, no scheduled recreate — the state's resources are stateless and cheap to rebuild, but the Postgres cluster they depend on is only read via a `data` source here and is left untouched. Running it against an already-destroyed state is a safe no-op. Bring the app back up: push to `main`, or dispatch `pipeline-gateway-cd.yml` — then expect a few minutes of certificate provisioning before HTTPS works.
- `pipeline-dns-cd.yml`: dispatch-only plan+apply of `terraform/dev/dns` (module `dns-dev`). Passes `"unused"` for `docker-image-name`/`docker-image-tag` because the shared plan/apply jobs require them; Terraform warns about the undeclared `TF_VAR_image_*` and continues. The zone is create-once, so this rarely runs.

Always run `terraform fmt -recursive` from the repo root after editing any `.tf` file, before committing.

## Custom domains

The zone (`terraform/dev/dns`) and the hostnames served from it (each app's spec) live in different
states, and neither writes DNS records directly:

1. `terraform/dev/dns` creates the zone. DigitalOcean's nameservers only answer for `mesazon.space`
   because this resource exists — registrar delegation alone yields REFUSED/SERVFAIL.
2. The app declares the hostname via the `app-service` module's `domains` variable, with
   `zone = "mesazon.space"`. That `zone` field is what makes App Platform create the DNS record in
   the zone *and* provision the certificate. Never add a matching `digitalocean_record`; it fights
   whatever App Platform manages.
3. `type = "PRIMARY"` marks the app's canonical hostname — only one domain per app may be primary.

`dev` therefore serves `dev-api.mesazon.space` and a future prod config serves
`prod-api.mesazon.space`, with no change to the module.

There is no way to pin a pre-issued certificate: the provider's `domain` block accepts only `name`,
`type`, `wildcard`, and `zone`, so the certificate's lifecycle is the app's lifecycle. Anything that
deletes the app therefore costs a reissue on the way back, which is what caps the destroy schedule
at twice a week and why `custom_domain_enabled=false` is applied before a destroy.

Do not add CAA records to the zone. If any are ever added they must list both `letsencrypt.org` and
`pki.goog`, the two CAs App Platform issues from, or certificate provisioning fails.

## Feature-completion check

Before marking a feature's service slice (PR 5, see [Service](../features/flow/05-service.md)) complete, check whether it introduced anything terraform must provision or wire: a new bucket/external credential, a new required env var, a new service/module. If so, terraform must be updated in the same PR — verify by reading the PR's `job-tf-plan` comment and confirming it plans the expected new/changed resources, not "No changes."
