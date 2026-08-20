# Read by pipeline-gateway-destroy.yml so its domain-release apply can pin the tag already deployed
# instead of redeploying whatever the workflow happens to pass in.
output "image_tag" {
  value = var.image_tag
}
