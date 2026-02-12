locals {
  app_name     = "${var.app_name_raw}-${var.region}-${var.environment}"
  service_name = "${var.app_name_raw}-service-${var.region}-${var.environment}"
  vpc_name     = "${var.vpc_name_raw}-${var.region}-${var.environment}"
}
