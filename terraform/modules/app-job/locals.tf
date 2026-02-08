locals {
  app_name     = "${var.app_name_raw}-${var.region}-${var.environment}"
  service_name = "${var.app_name_raw}-job-${var.region}-${var.environment}"
}
