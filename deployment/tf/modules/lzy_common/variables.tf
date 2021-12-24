variable "kubernetes_host" {}

variable "kubernetes_client_certificate" {}

variable "kubernetes_client_key" {}

variable "kubernetes_cluster_ca_certificate" {}

variable "installation_name" {
  type = string
}

variable "oauth-github-client-id" {
  type = string
}

variable "oauth-github-client-secret" {
  type = string
}

variable "kharon_public_ip" {}

variable "backoffice_public_ip" {}

variable "backoffice-frontend-image" {
  type    = string
  default = "lzydock/lzy-backoffice-frontend:master"
}

variable "backoffice-backend-image" {
  type    = string
  default = "lzydock/lzy-backoffice-backend:master"
}

variable "clickhouse-image" {
  type    = string
  default = "clickhouse/clickhouse-server"
}

variable "kharon-image" {
  type    = string
  default = "lzydock/lzy-kharon:master"
}

variable "server-image" {
  type    = string
  default = "lzydock/lzy-server:master"
}

variable "whiteboard-image" {
  type = string
  default = "lzydock/lzy-whiteboard:master"
}

variable "s3-bucket-name" {
  type = string
}

variable "amazon-access-key" {
  type = string
  default = ""
}

variable "amazon-secret-key" {
  type = string
  default = ""
}

variable "amazon-service-endpoint" {
  type = string
  default = ""
}

variable "azure-connection-string" {
  type = string
  default = ""
}

variable "storage-provider" {
  type = string
  default = "amazon"
}

variable "azure-resource-group" {
  type = string
  default = ""
}

variable "servant-image" {
  type    = string
}
