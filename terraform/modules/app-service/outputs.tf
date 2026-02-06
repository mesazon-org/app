output "app_id" {
  value = var.is_first_deployment ? digitalocean_app.app_noop_service[0].id : digitalocean_app.app_service[0].id
}
