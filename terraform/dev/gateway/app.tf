data "digitalocean_database_cluster" "postgres_cluster" {
  name = local.database_cluster_name
}

data "digitalocean_database_user" "database_user" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id
  name       = local.database_user
}

module "gateway_core_app" {
  source = "../../modules/app-service"

  is_first_deployment = true

  project_id  = var.project_id
  environment = local.environment

  image_name = var.image_name
  image_tag  = var.image_tag

  app_name_raw = local.app_name_raw
  region       = local.region
  replicas     = 1
  app_size     = "apps-s-1vcpu-1gb"

  env_vars = {
    DATABASE_NAME     = local.database_name
    DATABASE_HOST     = data.digitalocean_database_cluster.postgres_cluster.private_host
    DATABASE_PORT     = data.digitalocean_database_cluster.postgres_cluster.port
    DATABASE_USERNAME = data.digitalocean_database_user.database_user.name
    DATABASE_PASSWORD = data.digitalocean_database_user.database_user.password
  }
}
