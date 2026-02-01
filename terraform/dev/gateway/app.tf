module "gateway_app" {
  source = "../../modules/app"

  project_id  = var.project_id
  environment = var.environment

  image_name = var.image_name
  image_tag  = var.image_tag

  app_name = "gateway"
  region   = "fra1"
  replicas = 1
  app_size = "apps-s-1vcpu-1gb"
}
