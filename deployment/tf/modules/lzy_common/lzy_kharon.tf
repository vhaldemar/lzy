resource "kubernetes_pod" "lzy_kharon" {
  metadata {
    name   = "lzy-kharon"
    labels = {
      app : "lzy-kharon"
    }
  }
  spec {
    container {
      name              = "lzy-kharon"
      image             = var.kharon-image
      image_pull_policy = "Always"
      env {
        name = "LZY_HOST"
        value_from {
          field_ref {
            field_path = "status.podIP"
          }
        }
      }
      env {
        name  = "LZY_SERVER_IP"
        value = kubernetes_service.lzy_server.spec[0].cluster_ip
      }
      port {
        container_port = 8899
      }
      port {
        container_port = 8900
      }
      args              = [
        "--lzy-server-address",
        "http://$(LZY_SERVER_IP):8888",
        "--host",
        "$(LZY_HOST)",
        "--port",
        "8899",
        "--servant-proxy-port",
        "8900"
      ]
    }
    node_selector = {
      type = "lzy"
    }
    affinity {
      pod_anti_affinity {
        required_during_scheduling_ignored_during_execution {
          label_selector {
            match_expressions {
              key      = "app"
              operator = "In"
              values   = [
                "lzy-servant",
                "lzy-server",
                "lzy-kharon",
                "lzy-backoffice"
              ]
            }
          }
          topology_key = "kubernetes.io/hostname"
        }
      }
    }
    host_network  = true
    dns_policy    = "ClusterFirstWithHostNet"
  }

  depends_on = [
    kubernetes_pod.lzy_server
  ]
}

resource "kubernetes_service" "lzy_kharon" {
  metadata {
    name        = "lzy-kharon-load-balancer"
    annotations = {
#      "service.beta.kubernetes.io/azure-load-balancer-resource-group" = azurerm_resource_group.test.name
    }
  }
  spec {
    load_balancer_ip = var.kharon_public_ip
    type             = "LoadBalancer"
    port {
      port = 8899
    }
    selector         = {
      app = "lzy-kharon"
    }
  }

  depends_on = [
    kubernetes_pod.lzy_kharon
  ]
}
