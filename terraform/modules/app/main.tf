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
    name   = var.app_name
    region = var.region

    service {
      name               = "web-service"
      instance_count     = var.replicas
      instance_size_slug = var.app_size

      image {
        registry_type = "DOCR"
        repository    = var.image_name
        tag           = var.image_tag
      }

      env {
        key   = "APP_ENV"
        value = var.environment
      }
    }
  }
}
