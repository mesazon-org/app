data "digitalocean_database_cluster" "postgres_cluster" {
  name = local.database_cluster_name
}

data "digitalocean_database_user" "database_user" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id
  name       = local.database_user
}

module "gateway_flyway_app" {
  source = "../../modules/app-job"

  is_first_deployment = true

  project_id  = var.project_id
  environment = local.environment

  image_name = var.image_name
  image_tag  = var.image_tag

  app_name_raw = local.app_name_raw
  region       = local.region
  replicas     = 1
  app_size     = "apps-s-1vcpu-0.5gb"

  vpc_id = data.digitalocean_database_cluster.postgres_cluster.private_network_uuid

  env_vars = {
    FLYWAY_LOCATIONS           = "filesystem:/flyway/sql"
    FLYWAY_SCHEMAS             = "gateway_schema_fra1_dev"
    FLYWAY_CONNECT_RETRIES     = "5"
    FLYWAY_BASELINE_ON_MIGRATE = "true" # When run for the first time against an existing DB should be set to true.
    FLYWAY_URL                 = "jdbc:postgresql://${data.digitalocean_database_cluster.postgres_cluster.private_host}:${data.digitalocean_database_cluster.postgres_cluster.port}/${local.database_name}?sslmode=require"
  }

  secret_vars = {
    FLYWAY_USER     = data.digitalocean_database_user.database_user.name
    FLYWAY_PASSWORD = data.digitalocean_database_user.database_user.password
  }
}

