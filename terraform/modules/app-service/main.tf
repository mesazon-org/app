terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}

resource "digitalocean_app" "app" {
  project_id = var.project_id

  spec {
    name   = local.app_name
    region = var.region

    service {
      name               = local.service_name
      instance_count     = var.replicas
      instance_size_slug = var.app_size

      image {
        registry_type = "DOCR"
        repository    = var.image_name
        tag           = var.image_tag
      }

      dynamic "env" {
        for_each = var.env_vars
        content {
          key   = env.key
          value = env.value
          scope = "RUN_AND_BUILD_TIME" # Optional: default
          type  = "GENERAL"            # Optional: use "SECRET" for encrypted vars
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
