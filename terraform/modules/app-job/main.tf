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

    job {
      name               = local.service_name
      instance_count     = var.replicas
      instance_size_slug = var.app_size
      kind               = var.job_kind

      run_command = var.is_first_deployment ? "true" : null

      image {
        registry_type = var.registry_type
        repository    = var.image_name
        tag           = var.image_tag
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
  }
}
