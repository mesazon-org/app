locals {
  region                    = "fra1"
  environment               = "dev"
  app_name_raw              = "gateway-core"
  database_cluster_name_raw = "gateway"
  database_cluster_name     = "${local.database_cluster_name_raw}-${local.region}-${local.environment}"
  database_name_raw         = "gateway_db"
  database_name             = "${local.database_name_raw}_${local.region}_${local.environment}"
  database_user_raw         = "gateway_user"
  database_user             = "${local.database_user_raw}_${local.region}_${local.environment}"
}
