locals {
  graph-labels = {
    app                         = "graph"
    "app.kubernetes.io/name"    = "graph"
    "app.kubernetes.io/part-of" = "graph-executor"
    "lzy.ai/app"                = "graph"
  }
  graph-k8s-name = "graph-executor"
  graph-image    = var.graph-image
}

resource "random_password" "graph_executor_db_passwords" {
  length  = 16
  special = false
}

resource "kubernetes_secret" "graph_executor_db_secret" {
  metadata {
    name      = "db-secret-${local.graph-k8s-name}"
    namespace = "default"
  }

  data = {
    username = local.graph-k8s-name,
    password = random_password.graph_executor_db_passwords.result,
    db_host  = var.db-host
    db_port  = 6432
    db_name  = local.graph-k8s-name
  }

  type = "Opaque"
}

resource "kubernetes_deployment" "graph-executor" {
  metadata {
    name   = local.graph-k8s-name
    labels = local.graph-labels
  }
  spec {
    strategy {
      type = "Recreate"
    }
    selector {
      match_labels = local.graph-labels
    }
    template {
      metadata {
        name   = local.graph-k8s-name
        labels = local.graph-labels
      }
      spec {
        container {
          name              = local.graph-k8s-name
          image             = local.graph-image
          image_pull_policy = "Always"
          port {
            container_port = local.graph-port
          }
          port {
            container_port = local.graph-executor-metrics-port
          }

          env {
            name  = "GRAPH_EXECUTOR_METRICS_PORT"
            value = local.graph-executor-metrics-port
          }

          env {
            name  = "GRAPH_EXECUTOR_PORT"
            value = local.graph-port
          }

          env {
            name  = "GRAPH_EXECUTOR_SCHEDULER_PORT"
            value = local.scheduler-port
          }

          env {
            name  = "GRAPH_EXECUTOR_SCHEDULER_HOST"
            value = kubernetes_service.scheduler_service.spec[0].cluster_ip
          }

          env {
            name  = "GRAPH_EXECUTOR_EXECUTORS_COUNT"
            value = 10
          }

          env {
            name = "GRAPH_EXECUTOR_DATABASE_USERNAME"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.graph_executor_db_secret.metadata[0].name
                key  = "username"
              }
            }
          }
          env {
            name = "GRAPH_EXECUTOR_DATABASE_PASSWORD"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.graph_executor_db_secret.metadata[0].name
                key  = "password"
              }
            }
          }

          env {
            name  = "GRAPH_EXECUTOR_DATABASE_URL"
            value = "jdbc:postgresql://${kubernetes_secret.graph_executor_db_secret.data.db_host}:${kubernetes_secret.graph_executor_db_secret.data.db_port}/${kubernetes_secret.graph_executor_db_secret.data.db_name}"
          }

          env {
            name  = "GRAPH_EXECUTOR_DATABASE_ENABLED"
            value = "true"
          }
          env {
            name  = "GRAPH_EXECUTOR_IAM_ADDRESS"
            value = "${kubernetes_service.iam.spec[0].cluster_ip}:${local.iam-port}"
          }
          env {
            name = "GRAPH_EXECUTOR_IAM_INTERNAL_USER_NAME"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.iam_internal_user_data.metadata[0].name
                key  = "username"
              }
            }
          }
          env {
            name = "GRAPH_EXECUTOR_IAM_INTERNAL_USER_PRIVATE_KEY"
            value_from {
              secret_key_ref {
                name = kubernetes_secret.iam_internal_user_data.metadata[0].name
                key  = "key"
              }
            }
          }
          env {
            name = "K8S_POD_NAME"
            value_from {
              field_ref {
                field_path = "metadata.name"
              }
            }
          }
          env {
            name = "K8S_NAMESPACE"
            value_from {
              field_ref {
                field_path = "metadata.namespace"
              }
            }
          }
          env {
            name  = "K8S_CONTAINER_NAME"
            value = local.graph-k8s-name
          }

          volume_mount {
            name       = "varloglzy"
            mount_path = "/var/log/lzy"
          }
        }
        container {
          name              = "unified-agent"
          image             = var.unified-agent-image
          image_pull_policy = "Always"
          env {
            name  = "FOLDER_ID"
            value = var.folder_id
          }
          volume_mount {
            name       = "unified-agent-config"
            mount_path = "/etc/yandex/unified_agent/conf.d/"
          }
        }

        volume {
          name = "unified-agent-config"
          config_map {
            name = kubernetes_config_map.unified-agent-config["graph-executor"].metadata[0].name
            items {
              key  = "config"
              path = "config.yml"
            }
          }
        }
        volume {
          name = "varloglzy"
          host_path {
            path = "/var/log/lzy"
            type = "DirectoryOrCreate"
          }
        }
        node_selector = {
          type = "lzy"
        }
        affinity {
          pod_anti_affinity {
            preferred_during_scheduling_ignored_during_execution {
              weight = 1
              pod_affinity_term {
                label_selector {
                  match_expressions {
                    key      = "app.kubernetes.io/part-of"
                    operator = "In"
                    values   = ["lzy"]
                  }
                }
                topology_key = "kubernetes.io/hostname"
              }
            }
          }
        }
      }
    }
  }
}

resource "kubernetes_service" "graph_executor_service" {
  metadata {
    name   = "${local.graph-k8s-name}-load-balancer"
    labels = local.graph-labels
  }
  spec {
    selector = local.graph-labels
    port {
      port        = local.graph-port
      target_port = local.graph-port
    }
    type = "ClusterIP"
  }
}