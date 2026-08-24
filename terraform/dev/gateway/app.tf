data "digitalocean_database_cluster" "postgres_cluster" {
  name = local.database_cluster_name
}

data "digitalocean_database_user" "database_user" {
  cluster_id = data.digitalocean_database_cluster.postgres_cluster.id
  name       = local.database_user
}

data "digitalocean_spaces_bucket" "organization_media_bucket" {
  name   = local.spaces_organization_media_bucket
  region = local.region
}

resource "digitalocean_spaces_key" "organization_media_bucket" {
  name = "${local.spaces_organization_media_bucket}-key"

  grant {
    bucket     = data.digitalocean_spaces_bucket.organization_media_bucket.name
    permission = "readwrite"
  }
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

  # `zone` hands DNS record creation and TLS certificate provisioning to App Platform.
  # The zone itself is owned by the mesazon-tf-do repo, not this one.
  domains = var.custom_domain_enabled ? [{
    name = local.app_domain
    type = "PRIMARY"
    zone = local.dns_zone
  }] : []

  env_vars = {
    IS_DEV = "true"

    SERVER_ENABLE_DOCS = "true"

    S3_CLIENT_ORGANIZATION_MEDIA_USE_MOCK = "false"
    S3_CLIENT_ORGANIZATION_MEDIA_URI      = "https://${data.digitalocean_spaces_bucket.organization_media_bucket.endpoint}"
    S3_CLIENT_ORGANIZATION_MEDIA_REGION   = data.digitalocean_spaces_bucket.organization_media_bucket.region
    S3_CLIENT_ORGANIZATION_MEDIA_BUCKET   = data.digitalocean_spaces_bucket.organization_media_bucket.name

    # JAVA_TOOL_OPTIONS (not JAVA_OPTS): the image ENTRYPOINT is the bare `java` binary,
    # which reads JAVA_TOOL_OPTIONS natively but ignores the wrapper-script JAVA_OPTS
    # convention. UseCompactObjectHeaders is a JDK 25 product feature (JEP 519), off by
    # default, that shrinks object headers 12->8 bytes.
    JAVA_TOOL_OPTIONS = "-XX:InitialRAMPercentage=65.0 -XX:MaxRAMPercentage=65.0 -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp/heapdump.hprof -XX:+UseContainerSupport -XX:+UseCompactObjectHeaders"

    REPOSITORY_SCHEMA = local.repository_schema
    DATABASE_NAME     = local.database_name
    DATABASE_HOST     = data.digitalocean_database_cluster.postgres_cluster.private_host
    DATABASE_PORT     = data.digitalocean_database_cluster.postgres_cluster.port

    EMAIL_PROVIDER_HOST = "smtp.gmail.com"
    EMAIL_PROVIDER_PORT = "587"
    EMAIL_ENABLE_TLS    = "true"

    TWILIO_CLIENT_SCHEME = "https"
    TWILIO_CLIENT_HOST   = "api.twilio.com"
    TWILIO_CLIENT_PORT   = "443"
  }

  secret_vars = {
    DATABASE_USERNAME = data.digitalocean_database_user.database_user.name
    DATABASE_PASSWORD = data.digitalocean_database_user.database_user.password

    EMAIL_SENDER_EMAIL    = "mesazon.dev@gmail.com"
    EMAIL_SENDER_PASSWORD = var.email_sender_password

    TWILIO_CLIENT_ACCOUNT_SID = var.twilio_client_account_sid
    TWILIO_CLIENT_AUTH_TOKEN  = var.twilio_client_auth_token

    JWT_SECRET_KEY = var.jwt_secret_key

    S3_CLIENT_ORGANIZATION_MEDIA_ACCESS_KEY_ID     = digitalocean_spaces_key.organization_media_bucket.access_key
    S3_CLIENT_ORGANIZATION_MEDIA_SECRET_ACCESS_KEY = digitalocean_spaces_key.organization_media_bucket.secret_key
  }
}
