data "digitalocean_database_cluster" "postgres_cluster" {
  name = local.database_cluster_name
}

data "digitalocean_database_user" "database_user" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id
  name       = local.database_user
}

module "gateway_flyway_app" {
  source = "../../modules/app-job"

  project_id  = var.project_id
  environment = local.environment

  image_name = var.image_name
  image_tag  = var.image_tag

  app_name_raw = local.app_name
  region       = local.region
  replicas     = 1
  app_size     = "apps-s-1vcpu-0.5gb"

  wait_for_deployment = false

  env_vars = {
    FLYWAY_URL                 = "jdbc:postgresql://${data.digitalocean_database_cluster.postgres_cluster.private_host}:${data.digitalocean_database_cluster.postgres_cluster.port}/${local.database_name}?sslmode=require"
    FLYWAY_BASELINE_ON_MIGRATE = "true"
  }

  secret_vars = {
    FLYWAY_USER     = data.digitalocean_database_user.database_user.name
    FLYWAY_PASSWORD = data.digitalocean_database_user.database_user.password
  }
}

resource "digitalocean_database_firewall" "postgres_fw" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id

  rule {
    type  = "app"
    value = module.gateway_flyway_app.app_id
  }
}

