terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

data "digitalocean_vpc" "vpc" {
  name = local.vpc_name
}

resource "digitalocean_app" "app_service" {
  project_id = var.project_id

  spec {
    name   = local.app_name
    region = var.region

    service {
      name               = local.service_name
      instance_count     = var.replicas
      instance_size_slug = var.app_size
      http_port          = var.service_port

      internal_ports = var.internal_ports

      image {
        registry_type = var.registry_type
        repository    = var.image_name
        tag           = var.image_tag
      }

      health_check {
        http_path = "/readiness"
        port      = var.readiness_port
        # 20 + 10*5 = 70s budget. JVM cold start (JIT/class-loading after the DB pool is up) has been
        # observed taking ~44s to bind the readiness port; the old 5/10/3 (35s budget) killed the app
        # as unhealthy before it ever got a chance to answer.
        initial_delay_seconds = 20
        period_seconds        = 10
        timeout_seconds       = 5
        success_threshold     = 1
        failure_threshold     = 5
      }

      # Only directly through app platform is available, until new release contains those features
      # liveness_health_check {
      #   http_path             = "/liveness"
      #   port                  = 8081
      #   initial_delay_seconds = 10
      #   period_seconds        = 15
      #   timeout_seconds       = 5
      #   failure_threshold     = 3
      # }

      dynamic "env" {
        for_each = var.env_vars
        content {
          key   = env.key
          value = env.value
          scope = "RUN_AND_BUILD_TIME"
          type  = "GENERAL"
        }
      }

      dynamic "env" {
        for_each = var.secret_vars
        content {
          key   = env.key
          value = env.value
          type  = "SECRET"
        }
      }
    }

    dynamic "domain" {
      for_each = var.domains
      content {
        name = domain.value.name
        type = domain.value.type
        zone = domain.value.zone
      }
    }

    vpc {
      id = data.digitalocean_vpc.vpc.id
    }
  }
}
