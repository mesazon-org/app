terraform {
  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }
}


resource "digitalocean_app" "app_service" {
  count      = var.is_first_deployment ? 0 : 1
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

resource "digitalocean_app" "app_noop_service" {
  count = var.is_first_deployment ? 1 : 0

  project_id = var.project_id

  spec {
    name   = local.app_name
    region = var.region

    service {
      name               = local.service_name
      instance_count     = local.noop_replicas
      instance_size_slug = local.noop_app_size

      run_command = "sh -c 'while true; do printf \"HTTP/1.1 200 OK\\n\\n OK\" | nc -l -p 8080; done'"

      image {
        registry_type = local.noop_registry
        repository    = local.noop_image_name
        # registry_credentials = "sagging:${var.docker_token}"
        tag = local.noop_image_tag
      }

      internal_ports = [8080]

      health_check {
        http_path             = "/"
        port                  = 8080
        initial_delay_seconds = 5
        period_seconds        = 10
      }
    }
  }
}
