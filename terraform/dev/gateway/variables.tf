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

variable "twilio_client_account_sid" {
  description = "Twilio Client Account SID for sending SMS notifications."
  type        = string
  sensitive   = true
}

variable "twilio_client_auth_token" {
  description = "Twilio Client Auth Token for sending SMS notifications."
  type        = string
  sensitive   = true
}

variable "jwt_secret_key" {
  description = "Secret key for signing JWT tokens."
  type        = string
  sensitive   = true
}

variable "custom_domain_enabled" {
  description = "Whether the app serves its custom hostname. Set false only by the destroy pipeline, which drops the domain before deleting the app so DigitalOcean releases the hostname immediately instead of holding it for up to 24h."
  type        = bool
  default     = true
}
