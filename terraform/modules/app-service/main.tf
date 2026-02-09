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
      http_port          = 8080

      internal_ports = [8081]

      image {
        registry_type = var.registry_type
        repository    = var.image_name
        tag           = var.image_tag
      }

      health_check {
        http_path             = "/readiness"
        port                  = 8081
        initial_delay_seconds = 5
        period_seconds        = 10
        timeout_seconds       = 5
        success_threshold     = 1
        failure_threshold     = 3
      }

      liveness_health_check {
        http_path             = "/liveness"
        port                  = 8081
        initial_delay_seconds = 10
        period_seconds        = 15
        timeout_seconds       = 5
        failure_threshold     = 3
      }

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

    vpc {
      id = data.digitalocean_vpc.vpc.id
    }
  }
}
