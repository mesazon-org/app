locals {
  app_name     = "${var.app_name_raw}-${var.region}-${var.environment}"
  service_name = "${var.app_name_raw}-service-${var.region}-${var.environment}"

  noop_image_name = "alpine"
  noop_image_tag  = "latest"
  noop_registry   = "DOCKER_HUB"
  noop_replicas   = 1
  noop_app_size   = "apps-s-1vcpu-0.5gb"
}