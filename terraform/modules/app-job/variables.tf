variable "project_id" {
  description = "DigitalOcean Project ID"
  type        = string
}

variable "environment" {
  description = "The deployment environment (e.g., dev, staging, prod)."
  type        = string
}

variable "app_name_raw" {
  description = "The name of the application."
  type        = string
}

variable "region" {
  description = "DigitalOcean region slug (e.g., nyc1)."
  type        = string
}

variable "replicas" {
  description = "Number of Droplet instances."
  type        = number
  default     = 1
}

variable "app_size" {
  description = "The size of the Droplet (e.g., s-1vcpu-1gb)."
  type        = string
}

variable "job_kind" {
  description = "The size of the Droplet (e.g., s-1vcpu-1gb)."
  type        = string
  default     = "PRE_DEPLOY"
}

variable "registry_type" {
  description = "The type of registry (e.g., DOCKER_HUB, GITHUB_CONTAINER_REGISTRY)."
  type        = string
  default     = "DOCR"
}

variable "image_name" {
  description = "The Docker image name."
  type        = string
}

variable "image_tag" {
  description = "The Docker image tag."
  type        = string
}

variable "env_vars" {
  type        = map(string)
  description = "A map of environment variables for the service"
  default     = {}
}

variable "secret_vars" {
  type        = map(string)
  description = "A map of environment variables for the service"
  default     = {}
}

variable "vpc_name_raw" {
  description = "The raw name for the VPC, which will be combined with the environment and region to create the final name."
  type        = string
}
