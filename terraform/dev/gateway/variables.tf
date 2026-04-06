variable "do_token" {
  description = "DigitalOcean Personal Access Token"
  type        = string
  sensitive   = true
}

variable "project_id" {
  type = string
}

variable "image_name" {
  description = "The Docker image name."
  type        = string
}

variable "image_tag" {
  description = "The Docker image tag."
  type        = string
}

variable "email_sender_password" {
  description = "Password for the email sender account."
  type        = string
  sensitive   = true
}
