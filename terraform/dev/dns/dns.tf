# Zone only. Records inside it are created by App Platform for the domains declared in each app's
# spec (see terraform/dev/gateway), so this config never owns a digitalocean_record.
resource "digitalocean_domain" "mesazon" {
  name = local.domain_name
}

resource "digitalocean_project_resources" "mesazon" {
  project   = var.project_id
  resources = [digitalocean_domain.mesazon.urn]
}
