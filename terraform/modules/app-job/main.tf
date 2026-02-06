terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

resource "digitalocean_app" "app_job" {
  project_id = var.project_id

  spec {
    name   = local.app_name
    region = var.region

    vpc {
      id = var.vpc_id
    }

    job {
      name               = local.service_name
      instance_count     = var.is_first_deployment ? local.noop_replicas : var.replicas
      instance_size_slug = var.is_first_deployment ? local.noop_app_size : var.app_size
      kind               = "POST_DEPLOY"

      run_command = var.is_first_deployment ? "true" : null

      image {
        registry_type = var.is_first_deployment ? local.noop_registry : "DOCR"
        repository    = var.is_first_deployment ? local.noop_image_name : var.image_name
        # registry_credentials = "sagging:${var.docker_token}"
        tag = var.is_first_deployment ? local.noop_image_tag : var.image_tag
      }

      dynamic "env" {
        for_each = var.is_first_deployment ? {} : var.env_vars
        content {
          key   = env.key
          value = env.value
          scope = "RUN_AND_BUILD_TIME"
          type  = "GENERAL"
        }
      }

      dynamic "env" {
        for_each = var.is_first_deployment ? {} : var.secret_vars
        content {
          key   = env.key
          value = env.value
          type  = "SECRET"
        }
      }
    }
  }
}
