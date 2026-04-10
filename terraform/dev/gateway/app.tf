data "digitalocean_database_cluster" "postgres_cluster" {
  name = local.database_cluster_name
}

data "digitalocean_database_user" "database_user" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id
  name       = local.database_user
}

module "gateway_core_app" {
  source = "../../modules/app-service"

  project_id  = var.project_id
  environment = local.environment

  image_name = var.image_name
  image_tag  = var.image_tag

  service_port = 8080

  internal_ports = [8081, 8082]

  readiness_port = 8082

  vpc_name_raw = "gateway-vpc"

  app_name_raw = local.app_name_raw
  region       = local.region
  replicas     = 1
  app_size     = "apps-s-1vcpu-1gb-fixed"

  env_vars = {
    REPOSITORY_SCHEMA   = local.repository_schema
    SERVER_ENABLE_DOCS  = "true"
    JAVA_OPTS           = "-XX:InitialRAMPercentage=65.0 -XX:MaxRAMPercentage=65.0 -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError"
    DATABASE_NAME       = local.database_name
    DATABASE_HOST       = data.digitalocean_database_cluster.postgres_cluster.private_host
    DATABASE_PORT       = data.digitalocean_database_cluster.postgres_cluster.port
    EMAIL_PROVIDER_HOST = "smtp.gmail.com"
    EMAIL_PROVIDER_PORT = "587"
    EMAIL_ENABLE_TLS    = "true"
  }

  secret_vars = {
    EMAIL_SENDER_EMAIL    = "mesazon.dev@gmail.com"
    DATABASE_USERNAME     = data.digitalocean_database_user.database_user.name
    DATABASE_PASSWORD     = data.digitalocean_database_user.database_user.password
    EMAIL_SENDER_PASSWORD = var.email_sender_password
    JWT_SECRET_KEY        = var.jwt_secret_key
  }
}
